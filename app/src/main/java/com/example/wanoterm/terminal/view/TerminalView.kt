package com.example.wanoterm.terminal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.text.InputType
import android.util.AttributeSet
import android.view.KeyEvent
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import com.example.wanoterm.data.prefs.LineEnding
import com.example.wanoterm.terminal.TerminalSessionController
import com.example.wanoterm.theme.TerminalPalette
import com.example.wanoterm.util.Logger

/**
 * wanoterm の端末描画ビュー。Compose の AndroidView でホストされる。
 *
 * 責務:
 * - Canvas によるセルグリッド描画（[TerminalRenderer] に委譲）
 * - IME 入力の中継（[TerminalInputConnection]）
 * - フォーカス管理、特殊キーと合成テキストの SSH 送出
 */
class TerminalView
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null) : View(context, attrs), ITerminalInputTarget {

  override val composingState: ComposingState = ComposingState()

  private var controller: TerminalSessionController? = null
  private var renderer: TerminalRenderer = TerminalRenderer(TerminalPalette.TermiusDark, dipToPx(14f))
  private var lineEnding: LineEnding = LineEnding.CR
  private var monitorCursor: Boolean = false
  private var pendingInvalidate: Boolean = false
  private var lastDrawGeneration: Long = -1L

  /**
   * スクロールバック位置。0 = 最新（底）。正の値は上方向に何行戻ったか。
   * scrollback の行数で上限クランプ。新しい出力が届いたら 0 に戻す。
   */
  private var scrollOffset: Int = 0

  /**
   * キーボードツールバーの Ctrl が押されたら次の文字を Ctrl+X に変換する one-shot 修飾子。
   * 外部（ツールバー）から set してもらう。
   */
  var ctrlArmed: Boolean = false

  // ピンチズームで変化するフォントサイズ（dp 単位で保持 → px に都度変換）
  private var fontSizeDp: Float = 14f

  private val scaleDetector =
      ScaleGestureDetector(
          context,
          object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
            override fun onScale(detector: ScaleGestureDetector): Boolean {
              val newDp = (fontSizeDp * detector.scaleFactor).coerceIn(8f, 28f)
              if (newDp != fontSizeDp) {
                fontSizeDp = newDp
                renderer.updateFontSize(dipToPx(newDp))
                reflowToViewport()
                invalidate()
              }
              return true
            }
          },
      )

  // 縦ドラッグの累積距離（小さい動きでも取りこぼさないため）。セル高さを超えたぶんを
  // scrollBy に流して残りは次回に繰り越す。
  private var scrollAccumPx: Float = 0f

  private val scrollGestureDetector =
      GestureDetector(
          context,
          object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
              scrollAccumPx = 0f
              // false を返すと onScroll に入らない GestureDetector もあるが、Android
              // 標準実装では onScroll は onDown の返り値に依らず呼ばれる。ここで true を
              // 返すと tap / click の検出と競合するため false に。
              return false
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
              val ch = renderer.cellHeight
              if (ch <= 0f) return false
              // 縦ドラッグ優勢のときだけ scrollback を動かす。横ドラッグ中は false を返して
              // 上位（HorizontalPager）にイベントを渡す余地を残す。
              if (kotlin.math.abs(distanceY) < kotlin.math.abs(distanceX)) return false
              scrollAccumPx += distanceY
              val lines = (scrollAccumPx / ch).toInt()
              if (lines != 0) {
                scrollAccumPx -= lines * ch
                scrollBy(lines)
                return true
              }
              return false
            }
          },
      )

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    setOnClickListener { requestInputFocus() }
  }

  fun bind(controller: TerminalSessionController) {
    val controllerChanged = this.controller !== controller
    this.controller = controller
    if (controllerChanged) {
      composingState.clear()
      ctrlArmed = false
    }
    reflowToViewport()
    requestTerminalRedraw()
  }

  fun setPalette(palette: TerminalPalette) {
    renderer.palette = palette
    invalidate()
  }

  fun setFontSizeSp(sp: Float) {
    fontSizeDp = sp
    renderer.updateFontSize(dipToPx(sp))
    reflowToViewport()
    invalidate()
  }

  fun setLineEnding(le: LineEnding) {
    this.lineEnding = le
  }

  // --- IME バインディング ---

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    // 挙動メモ：
    //   TYPE_CLASS_TEXT + TYPE_TEXT_FLAG_NO_SUGGESTIONS。
    //   - VISIBLE_PASSWORD は絶対に付けない（パスワードモードになり日本語 IME の composition が死ぬ）。
    //   - NO_SUGGESTIONS は Gboard 英語モードで予測 composition を抑え、キー入力毎に commitText を
    //     発火させる → 英語入力が即画面反映になる。言語切替 UI（フリック/日本語への切替）には影響しない。
    //   - 日本語 IME（Google 日本語入力 / Gboard 日本語 / ATOK）では NO_SUGGESTIONS は
    //     composition/変換を妨げないので、普通の変換フロー（ローマ字→かな→漢字確定）が動く。
    outAttrs.inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS
    outAttrs.imeOptions =
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING or
            EditorInfo.IME_ACTION_NONE
    outAttrs.initialSelStart = composingState.cursor
    outAttrs.initialSelEnd = composingState.cursor
    Logger.d("IME", "onCreateInputConnection selection=${composingState.cursor}")
    return TerminalInputConnection(this)
  }

  // --- 描画 ---

  override fun onDraw(canvas: Canvas) {
    val ctl = controller ?: return
    val generation = ctl.emulator.buffer.generation
    if (generation != lastDrawGeneration) {
      lastDrawGeneration = generation
      Logger.d(
          "DRAW",
          "onDraw generation=$generation cursor=${ctl.emulator.cursorRow},${ctl.emulator.cursorCol}",
      )
    }
    // 残骸ピクセル対策で renderer にも実寸を渡す（セル数 * セルサイズでは
    // 端数を塗り切れないため）。
    renderer.viewPixelWidth = width.toFloat()
    renderer.viewPixelHeight = height.toFloat()
    renderer.draw(canvas, ctl.emulator, composingState, cursorBlinkOn = true, scrollOffset = scrollOffset)
  }

  override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
    super.onSizeChanged(w, h, oldw, oldh)
    reflowToViewport()
  }

  private fun reflowToViewport() {
    if (width <= 0 || height <= 0) return
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    if (cw <= 0f || ch <= 0f) return
    val cols = (width / cw).toInt().coerceAtLeast(1)
    val rows = (height / ch).toInt().coerceAtLeast(1)
    controller?.resize(rows, cols)
  }

  // --- 入力 ---

  override fun onTouchEvent(event: MotionEvent): Boolean {
    // 処理したかを明示的に積み上げる。`|| true` で無条件に消費していた過去版だと
    // Compose HorizontalPager の横スワイプが届かずタブ切替できなかった。
    scaleDetector.onTouchEvent(event)
    val scrolled = scrollGestureDetector.onTouchEvent(event)
    if (event.action == MotionEvent.ACTION_DOWN) {
      requestInputFocus()
    }
    val superHandled = super.onTouchEvent(event)
    return scaleDetector.isInProgress || scrolled || superHandled
  }

  /** 縦スクロール位置を相対で変更。正 = 過去方向、負 = 現在方向。 */
  fun scrollBy(lines: Int) {
    val maxOffset = controller?.emulator?.buffer?.scrollbackSize ?: 0
    val newOffset = (scrollOffset + lines).coerceIn(0, maxOffset)
    if (newOffset != scrollOffset) {
      scrollOffset = newOffset
      invalidate()
    }
  }

  /** 最新位置（底）へ戻す。新出力到着時や入力時に呼ぶ。 */
  fun scrollToBottom() {
    if (scrollOffset != 0) {
      scrollOffset = 0
      invalidate()
    }
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    Logger.d("IME", "onFocusChanged gainFocus=$gainFocus")
    if (gainFocus) restartInputAndShowKeyboard()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    Logger.d("IME", "onWindowFocusChanged hasWindowFocus=$hasWindowFocus focused=$isFocused")
    // app 切替→戻りで focus と IME 接続を復活させる。ただし isFocused が false の
    // 時（ダイアログ表示中など、意図的に他に focus がある）は奪わない。
    // isFocused が true のままウインドウ focus だけ戻ったケース（典型的な app 切替）では
    // IME の再接続だけ行う。
    if (hasWindowFocus && isFocused) restartInputAndShowKeyboard()
  }

  override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
    if (onSpecialKey(event)) return true
    // Ctrl+英字 を制御文字にマップ（shell の Ctrl+C / Ctrl+D / Ctrl+L 等）。
    // Android の unicodeChar は Ctrl+X を API バージョン依存でそのまま返す場合と
    // 0 にする場合があり不安定なので明示変換する。
    if (event.isCtrlPressed) {
      val c = event.keyCode
      if (c in KeyEvent.KEYCODE_A..KeyEvent.KEYCODE_Z) {
        val ctrlByte = (c - KeyEvent.KEYCODE_A + 1).toByte()
        sendBytes(byteArrayOf(ctrlByte))
        return true
      }
    }
    val unicode = event.unicodeChar
    if (unicode != 0) {
      composeAndCommit(unicode.toChar().toString())
      return true
    }
    return super.onKeyDown(keyCode, event)
  }

  override fun onSpecialKey(event: KeyEvent): Boolean {
    val ctl = controller ?: return false
    // CSI シーケンスは必ず ESC (0x1B) プレフィックス付きで送出。これが欠けると
    // shell/tmux/vim 等が何も解釈せず、生テキスト "[A" 等が画面に流れてしまう。
    val bytes: ByteArray =
        when (event.keyCode) {
          KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> lineEnding.bytes
          KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
          KeyEvent.KEYCODE_FORWARD_DEL -> "[3~".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_DPAD_UP -> "[A".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_DPAD_DOWN -> "[B".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_DPAD_RIGHT -> "[C".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_DPAD_LEFT -> "[D".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_TAB -> byteArrayOf(0x09)
          KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
          KeyEvent.KEYCODE_MOVE_HOME -> "[H".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_MOVE_END -> "[F".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_PAGE_UP -> "[5~".toByteArray(Charsets.US_ASCII)
          KeyEvent.KEYCODE_PAGE_DOWN -> "[6~".toByteArray(Charsets.US_ASCII)
          else -> return false
        }
    ctl.sendToRemote(bytes)
    return true
  }

  override fun requestTerminalRedraw() {
    if (!pendingInvalidate) {
      pendingInvalidate = true
      postOnAnimation {
        pendingInvalidate = false
        invalidate()
      }
    }
  }

  override fun requestCursorAnchorInfo() {
    val ctl = controller ?: return
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    val x = cw * ctl.emulator.cursorCol
    val y = ch * ctl.emulator.cursorRow
    val m = Matrix()
    val loc = IntArray(2)
    getLocationOnScreen(loc)
    m.postTranslate(loc[0].toFloat(), loc[1].toFloat())
    val builder =
        CursorAnchorInfo.Builder()
            .setMatrix(m)
            .setInsertionMarkerLocation(x, y, y + ch, y + ch, CursorAnchorInfo.FLAG_HAS_VISIBLE_REGION)
    imm.updateCursorAnchorInfo(this, builder.build())
  }

  override fun setCursorUpdateMode(monitor: Boolean) {
    monitorCursor = monitor
    if (monitor) requestCursorAnchorInfo()
  }

  override fun notifyImeSelection(
      selStart: Int,
      selEnd: Int,
      candidatesStart: Int,
      candidatesEnd: Int,
  ) {
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    imm.updateSelection(this, selStart, selEnd, candidatesStart, candidatesEnd)
    if (monitorCursor) requestCursorAnchorInfo()
  }

  override fun composeAndCommit(text: CharSequence) {
    if (text.isEmpty()) return
    if (ctrlArmed && text.length == 1) {
      val c = text[0].code
      if (c in 0x40..0x7E) {
        ctrlArmed = false
        sendBytes(byteArrayOf((c and 0x1F).toByte()))
        return
      }
      if (c in 0x20..0x3F) {
        // Ctrl+space (0) や Ctrl+/ (0x1F) 等も拾う
        ctrlArmed = false
        sendBytes(byteArrayOf(((c - 0x20) and 0x1F).toByte()))
        return
      }
      // 対象外キー（全角など）が来たら armed を解除して素通し
      ctrlArmed = false
    }
    val bytes = text.toString().toByteArray(Charsets.UTF_8)
    sendBytes(bytes)
  }

  override fun sendEnter() = sendBytes(lineEnding.bytes)

  override fun sendBackspace() = sendBytes(byteArrayOf(0x7F))

  /** キーボードツールバーなどから外部的にバイト列を投入。 */
  fun sendBytes(bytes: ByteArray) {
    // 何か入力したらスクロール位置を底へ戻す（典型的なターミナル挙動）。
    scrollToBottom()
    controller?.sendToRemote(bytes)
  }

  private fun requestInputFocus() {
    Logger.d("IME", "requestInputFocus focused=$isFocused")
    if (!isFocused) {
      requestFocusFromTouch()
      requestFocus()
    }
    restartInputAndShowKeyboard()
  }

  /**
   * 外部（タブ切替時など）から focus を取り戻させるためのフック。
   * Pager のページが切り替わっても、Pager 自体は focus を新ページに移してくれないため、
   * ホスト側で tab 切替を検知したらここを叩いて IME を新 TerminalView に紐付け直す。
   */
  fun focusAndRequestKeyboard() {
    requestInputFocus()
  }

  private fun restartInputAndShowKeyboard() {
    if (windowToken == null) return
    val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager ?: return
    post {
      if (!isFocused || windowToken == null) return@post
      Logger.d("IME", "restartInputAndShowKeyboard")
      imm.restartInput(this)
      imm.showSoftInput(this, InputMethodManager.SHOW_IMPLICIT)
    }
  }

  private fun dipToPx(dp: Float): Float {
    val dm = resources.displayMetrics
    return dp * dm.density * resources.configuration.fontScale
  }
}
