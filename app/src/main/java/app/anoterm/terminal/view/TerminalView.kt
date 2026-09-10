package app.anoterm.terminal.view

import android.app.Activity
import android.content.ClipData
import android.content.ContextWrapper
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import android.os.Build
import android.provider.Settings
import android.text.InputType
import android.util.AttributeSet
import android.view.ActionMode
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.GestureDetector
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.view.View
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.OverScroller
import android.widget.Toast
import app.anoterm.BuildConfig
import app.anoterm.data.prefs.LineEnding
import app.anoterm.terminal.ScreenScan
import app.anoterm.terminal.TapSpan
import app.anoterm.terminal.TapTarget
import app.anoterm.terminal.TerminalSessionController
import app.anoterm.theme.TerminalPalette
import app.anoterm.R
import app.anoterm.util.Logger
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * anoterm の端末描画ビュー。Compose の AndroidView でホストされる。
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
  private var renderer: TerminalRenderer = TerminalRenderer(context, TerminalPalette.TermiusDark, dipToPx(14f))
  private var lineEnding: LineEnding = LineEnding.CR
  private var monitorCursor: Boolean = false
  private var pendingInvalidate: Boolean = false
  private var lastDrawGeneration: Long = -1L
  private var lastInputType: Int = 0
  private var lastImeOptions: Int = 0
  private var relaxedImePrivacyForClipboard: Boolean = false

  /**
   * スクロールバック位置。0 = 最新（底）。正の値は上方向に何行戻ったか。
   * scrollback の行数で上限クランプ。新しい出力が届いたら 0 に戻す。
   */
  private var scrollOffset: Int = 0

  // 長押し → ドラッグ → リリース で画面上のテキストを範囲選択しクリップボードへ。
  // alt screen / primary どちらでも可視セルから抽出する。選択中は renderer に始端/終端を
  // 渡して選択背景色で塗らせる。シンプルさ優先で「リリース時に自動コピー + Toast」。
  private var selStart: CellPos? = null
  private var selEnd: CellPos? = null
  private val selectionActive: Boolean get() = selStart != null && selEnd != null
  private var selectionActionMode: ActionMode? = null
  // 選択中のドラッグが動かしているのは start か end か。touch down 時にどちらの端点に
  // 近いかで決まり、リリースまで固定。これで両端を自在に調節できる。
  private var draggingStart: Boolean = false
  // 選択中に「選択範囲・ハンドルから離れた位置」が tap されたかを表す。ACTION_UP までに
  // ドラッグが起きなければ ACTION_UP で選択解除する。Termius と同じ「外側タップで解除」UX。
  private var pendingClearOnUp: Boolean = false

  /**
   * キーボードツールバーの Ctrl が押されたら次の文字を Ctrl+X に変換する one-shot 修飾子。
   * 外部（ツールバー）から set してもらう。
   */
  var ctrlArmed: Boolean = false

  // ピンチズームで変化するフォントサイズ（dp 単位で保持 → px に都度変換）
  private var fontSizeDp: Float = 14f

  /**
   * ピンチで決まった大きさを設定へ書き戻すための通知。
   *
   * これが無いと、ピンチはこの View の中だけの値で終わる。設定は元の値のままなので、
   * 次に再コンポーズが起きた瞬間 (ブラウザを開く、タブが増える、設定を触る…) に
   * `setFontSizeSp` が古い値で呼ばれ、縮めたはずの文字が元に戻る。
   *
   * 指を離した時だけ呼ぶ。ピンチ中は 1 フレームごとに値が動くので、その都度
   * SharedPreferences へ書くと書き込みが溜まる。
   */
  var onFontSizeChanged: ((Float) -> Unit)? = null

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

            override fun onScaleEnd(detector: ScaleGestureDetector) {
              onFontSizeChanged?.invoke(fontSizeDp)
            }
          },
      )

  // 縦ドラッグの累積距離（小さい動きでも取りこぼさないため）。セル高さを超えたぶんを
  // scrollBy に流して残りは次回に繰り越す。
  private var scrollAccumPx: Float = 0f

  /**
   * 端末に出ているパスや URL が押されたときの通知。UI 層が中身を取りに行く。
   * View 自身は SSH を知らないので、ここでは「何が押されたか」だけを伝える。
   */
  var onTapTarget: ((TapTarget) -> Unit)? = null

  private var cachedSpans: List<TapSpan>? = null
  private var cachedSpansKey: Triple<Long, Int, Int>? = null

  /**
   * 惰性スクロール。指を離した速度で流れ続ける。
   *
   * 以前は指が動いた分しか動かず、数百行遡るには何十回も擦る必要があった。
   * 端末の履歴は「少し戻る」より「かなり戻る」ほうが多いので、その形は合っていない。
   */
  private val flinger = OverScroller(context)

  private val flingStep =
      object : Runnable {
        override fun run() {
          if (!flinger.computeScrollOffset()) return
          val ch = renderer.cellHeight
          if (ch <= 0f) {
            flinger.forceFinished(true)
            return
          }
          setScrollOffset((flinger.currY / ch).toInt())
          if (!flinger.isFinished) postOnAnimation(this)
        }
      }

  /** 今どれだけ遡っているか (行) を上へ伝える。0 なら最新を見ている。 */
  var onScrollPositionChanged: ((linesBack: Int) -> Unit)? = null

  private var searchQuery: String? = null

  /** 見つかった行。履歴の古い順。値は「履歴 + 画面」を通した行番号。 */
  private var searchMatches: List<Int> = emptyList()

  private var cachedHighlights: List<TapSpan>? = null
  private var cachedHighlightKey: Triple<Long, Int, String>? = null

  private val scrollGestureDetector =
      GestureDetector(
          context,
          object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
              scrollAccumPx = 0f
              // 流れている最中に触ったら止まる。止まらないと狙った所で止められない。
              flinger.forceFinished(true)
              // false を返すと onScroll に入らない GestureDetector もあるが、Android
              // 標準実装では onScroll は onDown の返り値に依らず呼ばれる。ここで true を
              // 返すと tap / click の検出と競合するため false に。
              return false
            }

            // ダブルタップで Tab (0x09) を送る。他ターミナルアプリ（Termius 等）の慣習に合わせ、
            // シェル補完を素早く呼び出せるようにする UX。
            override fun onDoubleTap(e: MotionEvent): Boolean {
              sendBytes(byteArrayOf(0x09))
              return true
            }

            // 長押し開始で選択モード突入。cellAtPixel で開始位置をセルに量子化。
            override fun onLongPress(e: MotionEvent) {
              Logger.d("SEL", "onLongPress x=${e.x} y=${e.y}")
              val pos = cellAtPixel(e.x, e.y)
              if (pos == null) {
                Logger.d("SEL", "cellAtPixel returned null (cell dims not ready?)")
                return
              }
              beginSelection(pos)
              parent?.requestDisallowInterceptTouchEvent(true)
            }

            // シングルタップが効くのは画面下 1/4 だけ。そこを叩くとキーボードが出る。
            // 残りの領域は何もしない。以前は上 3/8 と中間で半画面ずつスクロールさせて
            // いたが、読んでいる最中に触れただけで画面が飛ぶのが嫌われて外した
            // (スクロールしたい時はスワイプ)。
            // `onSingleTapConfirmed` はダブルタップ待機後に発火するので、ダブルタップと
            // 競合しない。~300ms の遅延は意図したもの。
            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
              // 選択中の単タップは ActionMode で Copy メニュー出してるので基本 no-op。
              // もし ActionMode が出てなければ新規選択解除として扱う (保険)。
              if (selectionActive && selectionActionMode == null) {
                clearSelection()
                return true
              }
              if (selectionActive) return false // ActionMode が拾うので素通し
              val h = height
              if (h <= 0) return false
              // パスの上を押したなら、そこはパスを開く場所。位置より優先する。
              // 直前の出力は画面の下に出るので、パスと「下 1/4 でキーボード」は必ずぶつかる。
              // どちらか一方に決めるなら、狙って押した物のほうを採るのが素直。
              val target = tapTargetAtPixel(e.x, e.y)
              if (target != null) {
                performHapticFeedback(
                    HapticFeedbackConstants.KEYBOARD_TAP,
                    HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
                )
                onTapTarget?.invoke(target)
                return true
              }
              // リモートがマウスを見ているなら、触った所にカーソルを置ける。Claude Code の
              // 入力欄はマウスで位置を指せるので、タップをそのまま渡すだけで同じ事ができる。
              // 「単タップは下 1/4 以外なにもしない」という原則の唯一の例外。相手が
              // マウス報告を要求している間だけなので、勝手に誤爆する場面は無い。
              if (sendClickIfTracking(e)) {
                // 入力欄は画面の下にある。そこを触ったのは打ちたいからなので、
                // カーソルを置いたうえでキーボードも開く。
                if (e.y > h * 0.75f) requestInputFocus()
                return true
              }
              // 下 1/4 タップのみ IME 起動、他は no-op（スクロールしたい時は swipe）。
              return if (e.y > h * 0.75f) {
                requestInputFocus()
                true
              } else {
                false
              }
            }

            override fun onFling(
                e1: MotionEvent?,
                e2: MotionEvent,
                velocityX: Float,
                velocityY: Float,
            ): Boolean {
              if (selectionActive) return false
              if (kotlin.math.abs(velocityY) < kotlin.math.abs(velocityX)) return false
              // mouse tracking 中の履歴はリモートが持っている。こちらで流しても何も動かない。
              val trackingEmu = controller?.emulator
              if (trackingEmu != null && trackingEmu.mouseTrackingEnabled) return false
              val ch = renderer.cellHeight
              if (ch <= 0f) return false
              val emu = trackingEmu ?: return false
              val maxLines = synchronized(emu) { emu.buffer.scrollbackSize }
              if (maxLines <= 0) return false
              // 指を下へ払う = 過去へ。scrollOffset が増える向きと velocityY の符号が一致する。
              flinger.forceFinished(true)
              flinger.fling(
                  0,
                  (scrollOffset * ch).toInt(),
                  0,
                  velocityY.toInt(),
                  0,
                  0,
                  0,
                  (maxLines * ch).toInt(),
              )
              postOnAnimation(flingStep)
              return true
            }

            override fun onScroll(
                e1: MotionEvent?,
                e2: MotionEvent,
                distanceX: Float,
                distanceY: Float,
            ): Boolean {
              // 選択中のドラッグは範囲拡張に流用。scrollback は動かさない。
              // ドラッグが起きた時点で「外側タップ→解除」予約はキャンセル (= ユーザは
              // 拡張を意図している)。
              if (selectionActive) {
                pendingClearOnUp = false
                cellAtPixel(e2.x, e2.y)?.let { extendSelection(it) }
                parent?.requestDisallowInterceptTouchEvent(true)
                return true
              }
              val ch = renderer.cellHeight
              if (ch <= 0f) return false
              // 縦ドラッグ優勢のときだけ scrollback を動かす。横ドラッグ中は false を返して
              // 上位（HorizontalPager）にイベントを渡す余地を残す。
              if (kotlin.math.abs(distanceY) < kotlin.math.abs(distanceX)) return false
              // 縦ドラッグと確定した瞬間に、親 (HorizontalPager) にイベント横取りを禁じる。
              // これを呼ばないと、縦スクロール中でも親の ViewPager が水平スワイプを検出して
              // タブが切り替わったりしてスクロール体験が安定しない。
              parent?.requestDisallowInterceptTouchEvent(true)
              // ユーザ感覚に合わせる: 指を下に引く（distanceY 負）→ 過去を遡る、
              // 指を上に押す（distanceY 正）→ 現在方向に戻す。
              // GestureDetector の distanceY は「指が上に動いた分だけ正」なので符号を反転。
              scrollAccumPx -= distanceY
              val lines = (scrollAccumPx / ch).toInt()
              if (lines != 0) {
                scrollAccumPx -= lines * ch
                // tmux / 類似 TUI が mouse tracking を ON にしていれば wheel event を
                // リモートに送る。tmux が `mouse on` だと自動で copy mode に入って
                // 内部 scrollback を動かしてくれる。OFF なら従来通り local scrollback。
                if (!sendWheelIfTracking(e2, lines)) {
                  scrollBy(lines)
                }
                return true
              }
              return false
            }
          },
      )

  init {
    isFocusable = true
    isFocusableInTouchMode = true
    // isClickable を true にしないと onTouchEvent が ACTION_DOWN 以降を受け取らず、
    // GestureDetector のコールバック (onSingleTapUp / onDoubleTap / onScroll) も
    // scaleDetector も全く発火しない。以前は setOnClickListener 呼出で暗黙に
    // isClickable=true になっていたが、キーボード無差別起動をやめるため setOnClickListener
    // を外した結果、ここが false に戻って全ジェスチャが死んでいた。
    isClickable = true
  }

  // 以下の setter は AndroidView の update ブロックから呼ばれる。update は再コンポーズの
  // たびに走るので、値が変わっていないのに reflow / invalidate すると、IME アニメーション中は
  // 毎フレーム PTY へ SIGWINCH が飛び、リモートの tmux / TUI が全画面を描き直す。
  // 「同じ値なら何もしない」を全 setter の入口で守ること。
  fun bind(controller: TerminalSessionController) {
    if (this.controller === controller) return
    this.controller?.onScrollbackShift = null
    this.controller = controller
    controller.onScrollbackShift = ::shiftScrollOffset
    composingState.clear()
    ctrlArmed = false
    reflowToViewport()
    requestTerminalRedraw()
  }

  fun setPalette(palette: TerminalPalette) {
    if (renderer.palette == palette) return
    renderer.palette = palette
    invalidate()
  }

  fun setFontSizeSp(sp: Float) {
    if (fontSizeDp == sp) return
    fontSizeDp = sp
    renderer.updateFontSize(dipToPx(sp))
    reflowToViewport()
    invalidate()
  }

  fun setLineSpacing(multiplier: Float) {
    if (renderer.lineSpacing == multiplier) return
    renderer.lineSpacing = multiplier
    // 行高が変わると 1 画面に入る行数が変わる。PTY にも伝えないと、サーバー側は
    // 古い行数のまま描いてくる。
    reflowToViewport()
    invalidate()
  }

  fun setLineEnding(le: LineEnding) {
    this.lineEnding = le
  }

  fun setRelaxedImePrivacyForClipboard(enabled: Boolean) {
    if (relaxedImePrivacyForClipboard == enabled) return
    relaxedImePrivacyForClipboard = enabled
    if (isFocused && windowToken != null) {
      val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
      imm?.restartInput(this)
    }
  }

  // --- IME バインディング ---

  override fun onCheckIsTextEditor(): Boolean = true

  override fun onCreateInputConnection(outAttrs: EditorInfo): InputConnection {
    // 挙動メモ：
    //   TYPE_CLASS_TEXT + TYPE_TEXT_FLAG_NO_SUGGESTIONS + TYPE_TEXT_FLAG_MULTI_LINE。
    //   - VISIBLE_PASSWORD は絶対に付けない（パスワードモードになり日本語 IME の composition が死ぬ）。
    //   - NO_SUGGESTIONS は Gboard 英語モードで予測 composition を抑え、キー入力毎に commitText を
    //     発火させる → 英語入力が即画面反映になる。言語切替 UI（フリック/日本語への切替）には影響しない。
    //   - 日本語 IME（Google 日本語入力 / Gboard 日本語 / ATOK）では NO_SUGGESTIONS は
    //     composition/変換を妨げないので、普通の変換フロー（ローマ字→かな→漢字確定）が動く。
    //   - MULTI_LINE を立てると IME 側が「文末＝Enter」扱いを止め、Enter 後の自動大文字化が
    //     働かなくなる。ターミナルではコマンドを実行するたびに Enter が入るので、これがないと
    //     `cd ` の c が勝手に大文字化してストレス。
    // VARIATION_URI を足すと Gboard の予測候補バーが消え、`cd` の直後にスペースで
    // `CD` に確定される事故が起きない。日本語 IME の composing 自体は URI モードでも動く。
    outAttrs.inputType =
        InputType.TYPE_CLASS_TEXT or
            InputType.TYPE_TEXT_VARIATION_URI or
            InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS or
            InputType.TYPE_TEXT_FLAG_MULTI_LINE
    var imeOptions =
        EditorInfo.IME_FLAG_NO_FULLSCREEN or
            EditorInfo.IME_FLAG_NO_EXTRACT_UI or
            EditorInfo.IME_ACTION_NONE
    if (!relaxedImePrivacyForClipboard) {
      imeOptions = imeOptions or EditorInfo.IME_FLAG_NO_PERSONALIZED_LEARNING
    }
    outAttrs.imeOptions = imeOptions
    lastInputType = outAttrs.inputType
    lastImeOptions = outAttrs.imeOptions
    outAttrs.initialSelStart = composingState.cursor
    outAttrs.initialSelEnd = composingState.cursor
    Logger.d("IME", "onCreateInputConnection selection=${composingState.cursor}")
    return TerminalInputConnection(this)
  }

  // --- 描画 ---

  override fun onDraw(canvas: Canvas) {
    val ctl = controller ?: return
    renderer.viewPixelWidth = width.toFloat()
    renderer.viewPixelHeight = height.toFloat()
    // emulator.feed と同じ monitor を取得してから読む。これにより feed 中に部分更新された
    // cell grid を描画して「前フレームと重なる / 消えかけの文字が残る」視覚バグを防ぐ。
    // feed 側は @Synchronized、draw 側は explicit synchronized で同じ `emulator` を共有。
    synchronized(ctl.emulator) {
      renderer.draw(
          canvas,
          ctl.emulator,
          composingState,
          cursorBlinkOn = true,
          scrollOffset = scrollOffset,
          selectionStart = selStart,
          selectionEnd = selEnd,
          pathSpans = pathSpans(),
          highlightSpans = highlightSpans(),
      )
    }
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
    // ACTION_DOWN で requestInputFocus していた処理は削除。IME 表示はシングルタップ
    // (画面下 1/4) に限定したので、ここでは focus を要求しない。
    // 選択中は ACTION_DOWN 時点で近い端点を選んでおき、それ以降のドラッグで動かす。
    if (event.action == MotionEvent.ACTION_DOWN && selectionActive) {
      val nearSel = isTouchNearSelection(event.x, event.y)
      // 選択近傍: CAB を一時非表示、端点を掴んで以後のドラッグで動かす。従来挙動。
      // 選択遠方: CAB はそのまま、ドラッグが来なかったら ACTION_UP で「外側タップ」と
      //   みなして選択解除。ドラッグになれば onScroll 側で pendingClearOnUp を取り消す。
      pickDragEndpoint(event.x, event.y)
      if (nearSel) {
        dismissSelectionActionModeOnly()
        pendingClearOnUp = false
      } else {
        pendingClearOnUp = true
      }
      parent?.requestDisallowInterceptTouchEvent(true)
    }
    scaleDetector.onTouchEvent(event)
    val scrolled = scrollGestureDetector.onTouchEvent(event)
    val superHandled = super.onTouchEvent(event)
    // 選択ドラッグ終了時は ActionMode (floating CAB) を起動して選択範囲近くに
    // 「コピー」メニューを出す。ユーザが明示的に Copy をタップするまで選択は残る。
    // ACTION_CANCEL は pager 譲渡等の異常系、選択だけ解除。
    if (event.action == MotionEvent.ACTION_UP && selectionActive) {
      if (pendingClearOnUp) {
        clearSelection()
        pendingClearOnUp = false
      } else if (selectionActionMode == null) {
        showSelectionActionMode()
      }
    } else if (event.action == MotionEvent.ACTION_CANCEL && selectionActive) {
      clearSelection()
      pendingClearOnUp = false
    }
    return scaleDetector.isInProgress || scrolled || superHandled
  }

  /** 縦スクロール位置を相対で変更。正 = 過去方向、負 = 現在方向。 */
  /**
   * 履歴と画面を通して [query] を探す。見つかった行数を返す。null か空なら解除。
   *
   * 探すのは「行」であって桁ではない。1 行に何度出てきても 1 件と数える。飛び先として
   * 意味があるのは行で、同じ行の中を行き来しても画面は動かないため。
   *
   * 大文字小文字は区別しない。端末に流れる文字はログもコードも混ざっていて、
   * どちらで書かれているかを覚えている人はいない。
   */
  fun setSearchQuery(query: String?): Int {
    val q = query?.takeIf { it.isNotEmpty() }
    searchQuery = q
    cachedHighlights = null
    if (q == null) {
      searchMatches = emptyList()
      invalidate()
      return 0
    }
    val needle = q.lowercase()
    searchMatches = allLines().withIndex().filter { needle in it.value.lowercase() }.map { it.index }
    invalidate()
    return searchMatches.size
  }

  /** [index] 件目の一致が見えるところまで飛ぶ。 */
  fun jumpToSearchMatch(index: Int) {
    val line = searchMatches.getOrNull(index) ?: return
    val emu = controller?.emulator ?: return
    val (sbSize, rows) = synchronized(emu) { emu.buffer.scrollbackSize to emu.buffer.rows }
    // 画面の上から 1/3 あたりに置く。一番上に置くと、その行に至る文脈が見えない。
    flinger.forceFinished(true)
    setScrollOffset((sbSize - line + rows / 3).coerceIn(0, sbSize))
  }

  /**
   * 履歴と画面を 1 本に並べた行。0 が一番古い履歴、末尾が画面の最下行。
   *
   * 検索のたびに作り直す。履歴 2000 行 x 120 桁でも 24 万文字ほどで、押した時に一度だけ
   * 走る処理としては十分軽い。常時持つと、出力のたびに作り直すことになって割に合わない。
   */
  private fun allLines(): List<String> {
    val emu = controller?.emulator ?: return emptyList()
    return synchronized(emu) {
      val buffer = emu.buffer
      val out = ArrayList<String>(buffer.scrollbackSize + buffer.rows)
      for (i in buffer.scrollbackSize - 1 downTo 0) {
        val sb = StringBuilder(buffer.cols)
        for (c in 0 until buffer.cols) {
          val cell = buffer.scrollbackCellAt(i, c) ?: break
          sb.append(if (cell.continuation || cell.codePoint == 0) ' ' else cell.codePoint.toChar())
        }
        out.add(sb.toString().trimEnd())
      }
      for (r in 0 until buffer.rows) out.add(buffer.rowAsString(r))
      out
    }
  }

  /** 画面に見えている一致。下線ではなく背景で塗るため、桁の範囲で持つ。 */
  private fun highlightSpans(): List<TapSpan> {
    val q = searchQuery ?: return emptyList()
    val emu = controller?.emulator ?: return emptyList()
    val generation = synchronized(emu) { emu.buffer.generation }
    val key = Triple(generation, scrollOffset, q)
    cachedHighlights?.let { if (cachedHighlightKey == key) return it }
    val needle = q.lowercase()
    val spans = mutableListOf<TapSpan>()
    visibleLines().forEachIndexed { row, line ->
      val haystack = line.lowercase()
      var from = 0
      while (true) {
        val at = haystack.indexOf(needle, from)
        if (at < 0) break
        spans.add(TapSpan(row, at, at + needle.length - 1))
        from = at + needle.length
      }
    }
    cachedHighlightKey = key
    cachedHighlights = spans
    return spans
  }

  fun scrollBy(lines: Int) = setScrollOffset(scrollOffset + lines)

  /** 遡り位置を直接置く。上限の取得と通知はここに集約する。 */
  private fun setScrollOffset(lines: Int) {
    // scrollbackSize は feed スレッドの scrollUp/resize と並行に変化する。emulator monitor を
    // 取ってから読む（描画パスと同じロック）。稀な古い値による一瞬のスクロール上限ずれを防ぐ。
    val emu = controller?.emulator
    val maxOffset = if (emu != null) synchronized(emu) { emu.buffer.scrollbackSize } else 0
    val newOffset = lines.coerceIn(0, maxOffset)
    if (newOffset != scrollOffset) {
      scrollOffset = newOffset
      onScrollPositionChanged?.invoke(newOffset)
      invalidate()
    }
  }

  /**
   * resize で scrollback が [delta] 行動いたぶん、見ている位置を追従させる。
   *
   * 画面が縮むと上端の行が scrollback へ送られ、広がると引き戻される。scrollOffset は
   * 「scrollback の下から何行目を画面上端に出すか」なので、これを直さないと同じ数字が
   * 別の行を指すことになり、キーボードを出し入れするたびに本文が上下に飛ぶ。
   *
   * 底を追っているとき (offset == 0) は動かさない。そこはカーソル行を見ていたい場面で、
   * 上端を固定すると今度はプロンプトが画面外へ出ていく。
   */
  private fun shiftScrollOffset(delta: Int) {
    if (scrollOffset == 0) return
    setScrollOffset(scrollOffset + delta)
  }

  /**
   * 画面に見えている行を、1 セル = 1 文字で書き出す。
   *
   * 桁と文字の位置をずらさないため、全角文字とその 2 桁目はどちらも空白として置く。
   * パスに全角は使われない前提なので、混ざっていれば単語がそこで切れて「見つからない」に
   * なる。誤って別のファイルを開くよりよい。
   */
  private fun visibleLines(): List<String> {
    val emu = controller?.emulator ?: return emptyList()
    return synchronized(emu) {
      val buffer = emu.buffer
      val offset = scrollOffset.coerceAtMost(buffer.rows + buffer.scrollbackSize)
      (0 until buffer.rows).map { r ->
        val sb = StringBuilder(buffer.cols)
        for (c in 0 until buffer.cols) {
          val cell = visibleCellAt(buffer, offset, r, c)
          val cp = cell?.codePoint ?: 0
          sb.append(
              if (cell == null || cell.continuation || cell.wide || cp == 0 || cp > 0xFFFF) ' '
              else cp.toChar(),
          )
        }
        sb.toString()
      }
    }
  }

  /**
   * 画面上の行番号を、scrollback と grid のどちらから読むかに振り分ける。
   * 描画側 (`TerminalRenderer`) と同じ対応にしておかないと、押した場所と見えている物がずれる。
   */
  private fun visibleCellAt(
      buffer: app.anoterm.terminal.emulator.TerminalBuffer,
      offset: Int,
      row: Int,
      col: Int,
  ): app.anoterm.terminal.emulator.Cell? =
      if (row < offset) {
        buffer.scrollbackCellAt(offset - 1 - row, col)
      } else {
        val r = row - offset
        if (r in 0 until buffer.rows) buffer.cellAt(r, col) else null
      }

  /**
   * 画面に出ている画像パスの位置。下線を引くために描画のたびに要る。
   *
   * 毎フレーム全行を走査すると無駄なので、画面が書き換わるかスクロールするまで使い回す。
   * `generation` は buffer が変わるたびに進むカウンタ。
   */
  private fun pathSpans(): List<TapSpan> {
    val emu = controller?.emulator ?: return emptyList()
    val generation = synchronized(emu) { emu.buffer.generation }
    val key = Triple(generation, scrollOffset, emu.cols)
    val cached = cachedSpans
    if (cached != null && cachedSpansKey == key) return cached
    val lines = visibleLines()
    val spans = ScreenScan.tapSpans(lines, emu.cols)
    cachedSpansKey = key
    cachedSpans = spans
    return spans
  }

  /** 最新位置（底）へ戻す。新出力到着時や入力時に呼ぶ。 */
  fun scrollToBottom() {
    flinger.forceFinished(true)
    setScrollOffset(0)
  }

  override fun onFocusChanged(gainFocus: Boolean, direction: Int, previouslyFocusedRect: Rect?) {
    super.onFocusChanged(gainFocus, direction, previouslyFocusedRect)
    Logger.d("IME", "onFocusChanged gainFocus=$gainFocus")
    if (gainFocus) restartInputAndShowKeyboard()
  }

  override fun onWindowFocusChanged(hasWindowFocus: Boolean) {
    super.onWindowFocusChanged(hasWindowFocus)
    Logger.d("IME", "onWindowFocusChanged hasWindowFocus=$hasWindowFocus focused=$isFocused attached=$isAttachedToWindow")
    // window 取得時、この view が画面上に表示されている（attach 中）なら focus + IME 復活。
    // HOME→戻り時に isFocused は false に落ちていることが多いので、focus ごと取り戻す必要あり。
    // ダイアログは別 window 扱いなので、ダイアログ閉じた時点でこちらの window focus が戻る
    // = その時に terminal に focus 戻すのは妥当。
    if (hasWindowFocus && isAttachedToWindow) requestInputFocus()
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
    controller ?: return false
    // 修飾キーの xterm 方式: mod = 1 + (shift) + 2*(alt) + 4*(ctrl)
    val mod =
        1 +
            (if (event.isShiftPressed) 1 else 0) +
            (if (event.isAltPressed) 2 else 0) +
            (if (event.isCtrlPressed) 4 else 0)
    val bytes: ByteArray =
        when (event.keyCode) {
          KeyEvent.KEYCODE_ENTER, KeyEvent.KEYCODE_NUMPAD_ENTER -> lineEnding.bytes
          KeyEvent.KEYCODE_DEL -> byteArrayOf(0x7F)
          KeyEvent.KEYCODE_FORWARD_DEL -> csiTildeMod(3, mod)
          KeyEvent.KEYCODE_DPAD_UP -> csiLetterMod('A', mod)
          KeyEvent.KEYCODE_DPAD_DOWN -> csiLetterMod('B', mod)
          KeyEvent.KEYCODE_DPAD_RIGHT -> csiLetterMod('C', mod)
          KeyEvent.KEYCODE_DPAD_LEFT -> csiLetterMod('D', mod)
          KeyEvent.KEYCODE_TAB ->
              if (event.isShiftPressed) byteArrayOf(0x1B, 0x5B, 0x5A) // ESC [ Z = back-tab
              else byteArrayOf(0x09)
          KeyEvent.KEYCODE_ESCAPE -> byteArrayOf(0x1B)
          KeyEvent.KEYCODE_MOVE_HOME -> csiLetterMod('H', mod)
          KeyEvent.KEYCODE_MOVE_END -> csiLetterMod('F', mod)
          KeyEvent.KEYCODE_PAGE_UP -> csiTildeMod(5, mod)
          KeyEvent.KEYCODE_PAGE_DOWN -> csiTildeMod(6, mod)
          else -> return false
        }
    // sendBytes 経由で DECCKM remap + scrollToBottom を一本化。直接 sendToRemote を呼ぶと
    // application cursor mode の書換えが走らず、tmux 上で矢印が効かなくなる。
    sendBytes(bytes)
    return true
  }

  /** CSI-with-letter 系（A/B/C/D/H/F 等）。modifier 無しなら "ESC [ X"、有りなら "ESC [ 1 ; M X"。 */
  private fun csiLetterMod(final: Char, mod: Int): ByteArray {
    val body = if (mod == 1) "[${final}" else "[1;${mod}${final}"
    return makeEscBytes(body)
  }

  /** CSI-with-tilde 系（3~/5~/6~ 等）。modifier 無しなら "ESC [ N ~"、有りなら "ESC [ N ; M ~"。 */
  private fun csiTildeMod(num: Int, mod: Int): ByteArray {
    val body = if (mod == 1) "[${num}~" else "[${num};${mod}~"
    return makeEscBytes(body)
  }

  private fun makeEscBytes(body: String): ByteArray {
    val out = ByteArray(body.length + 1)
    out[0] = 0x1B
    for (i in body.indices) out[i + 1] = body[i].code.toByte()
    return out
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
    sendBytes(normalizeLineEndings(bytes, lineEnding.bytes))
  }

  /**
   * IME の commitText や貼り付け経由で届く CR / LF / CRLF を設定された lineEnding に揃える。
   *
   * Gboard の英語モードの Enter は `commitText("\n")` で LF が届くため、そのまま送出すると
   * シェル系（Claude Code 等）が「改行＝入力継続」として解釈してしまう。日本語 IME 経由の
   * 変換確定後の Enter は `sendKeyEvent(KEYCODE_ENTER)` → `sendEnter()` 経路で既に
   * lineEnding に変換されている。両経路の挙動を一致させるため、こちらでも同じ正規化をかける。
   */
  private fun normalizeLineEndings(raw: ByteArray, target: ByteArray): ByteArray {
    // どの改行コードも含まない場合は割り当てを省く（ホットパス）
    var needsRewrite = false
    for (b in raw) {
      if (b == 0x0D.toByte() || b == 0x0A.toByte()) {
        needsRewrite = true
        break
      }
    }
    if (!needsRewrite) return raw
    val out = java.io.ByteArrayOutputStream(raw.size)
    var i = 0
    while (i < raw.size) {
      val b = raw[i]
      when {
        b == 0x0D.toByte() && i + 1 < raw.size && raw[i + 1] == 0x0A.toByte() -> {
          out.write(target)
          i += 2
        }
        b == 0x0D.toByte() || b == 0x0A.toByte() -> {
          out.write(target)
          i += 1
        }
        else -> {
          out.write(b.toInt() and 0xFF)
          i += 1
        }
      }
    }
    return out.toByteArray()
  }

  override fun sendEnter() = sendBytes(lineEnding.bytes)

  override fun sendBackspace() = sendBytes(byteArrayOf(0x7F))

  /**
   * mouse tracking が ON ならホイール event を SGR (または X10) 形式で送って true。
   * OFF (= local scrollback 動作) なら false を返す。
   *
   * tmux で `set -g mouse on` されていると、ホイール event を受けて自動的に copy mode へ
   * 入って内部 scrollback をスクロールしてくれる。Claude Code / vim 等でも同じ仕組みで
   * その TUI の解釈に任せられる。
   *
   * [positiveLines] が正で「過去方向スクロール」= wheel up (button 64)、
   * 負なら「現在方向スクロール」= wheel down (button 65)。
   */
  /**
   * タップした桁・行へマウスの左クリックを送る。リモートが mouse tracking を
   * 有効にしていなければ何もせず false を返す。
   *
   * 押下と解放を続けて送る。押しっぱなしにするとドラッグ選択と解釈され、相手の側で
   * 範囲が広がったままになる。
   */
  private fun sendClickIfTracking(e: MotionEvent): Boolean {
    val ctl = controller ?: return false
    val emu = ctl.emulator
    if (!emu.mouseTrackingEnabled) return false
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    if (cw <= 0f || ch <= 0f) return false
    val col = (e.x / cw).toInt().coerceAtLeast(0) + 1 // 1-based
    val row = (e.y / ch).toInt().coerceAtLeast(0) + 1
    if (emu.mouseSgrMode) {
      // SGR は終端の M/m で押下と解放を区別する。ボタン番号は同じ 0 のまま。
      ctl.sendToRemote(sgrMouse(0, col, row, press = true))
      ctl.sendToRemote(sgrMouse(0, col, row, press = false))
    } else {
      // X10 形式に解放専用の符号は無く、ボタン 3 が「離した」を表す。
      ctl.sendToRemote(x10Mouse(0, col, row))
      ctl.sendToRemote(x10Mouse(3, col, row))
    }
    return true
  }

  /** `ESC [ < b ; x ; y M|m`。ESC は 0x1B を明示する（文字列に埋めると編集で落ちる）。 */
  private fun sgrMouse(button: Int, col: Int, row: Int, press: Boolean): ByteArray =
      byteArrayOf(0x1B, '['.code.toByte(), '<'.code.toByte()) +
          (button.toString() + ";" + col + ";" + row + if (press) "M" else "m")
              .toByteArray(Charsets.US_ASCII)

  /** `ESC [ M <b+32> <x+32> <y+32>`。223 桁を超えると壊れる形式なので上限で止める。 */
  private fun x10Mouse(button: Int, col: Int, row: Int): ByteArray =
      byteArrayOf(
          0x1B,
          '['.code.toByte(),
          'M'.code.toByte(),
          (button + 32).toByte(),
          (col + 32).coerceAtMost(255).toByte(),
          (row + 32).coerceAtMost(255).toByte(),
      )

  private fun sendWheelIfTracking(e: MotionEvent, positiveLines: Int): Boolean {
    val ctl = controller ?: return false
    val emu = ctl.emulator
    if (!emu.mouseTrackingEnabled) return false
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    if (cw <= 0f || ch <= 0f) return false
    val col = (e.x / cw).toInt().coerceAtLeast(0) + 1 // 1-based
    val row = (e.y / ch).toInt().coerceAtLeast(0) + 1
    val count = kotlin.math.abs(positiveLines)
    val button = if (positiveLines > 0) 64 else 65
    val sgr = emu.mouseSgrMode
    repeat(count) {
      val bytes =
          if (sgr) {
            "[<$button;$col;${row}M".toByteArray(Charsets.US_ASCII)
          } else {
            // X10 レガシー形式: `ESC [ M <b+32> <x+32> <y+32>`。値域が 223 を超えると
            // 壊れるため大きい row/col では SGR が必須。tmux は通常 1006 も一緒に立てる。
            byteArrayOf(
                0x1B,
                '['.code.toByte(),
                'M'.code.toByte(),
                (button + 32).toByte(),
                (col + 32).coerceAtMost(255).toByte(),
                (row + 32).coerceAtMost(255).toByte(),
            )
          }
      ctl.sendToRemote(bytes)
    }
    return true
  }

  /** キーボードツールバーなどから外部的にバイト列を投入。 */
  fun sendBytes(bytes: ByteArray) {
    // 何か入力したらスクロール位置を底へ戻す（典型的なターミナル挙動）。
    scrollToBottom()
    controller?.sendToRemote(remapArrowIfAppMode(bytes))
  }

  // --- テキスト選択 ---

  /** ピクセル座標を画面上のセル位置 (row/col) に変換。見えない位置は null。 */
  private fun cellAtPixel(x: Float, y: Float): CellPos? {
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    if (cw <= 0f || ch <= 0f) return null
    val col = (x / cw).toInt().coerceAtLeast(0)
    val row = (y / ch).toInt().coerceAtLeast(0)
    val ctl = controller ?: return null
    val rows = ctl.emulator.rows
    val cols = ctl.emulator.cols
    return CellPos(row.coerceAtMost(rows - 1), col.coerceAtMost(cols - 1))
  }

  /**
   * 指の座標にある「押せるもの」。無ければ null。
   *
   * 1 行の高さは指より小さいので、真上に当てるのは難しい。左右に 1 桁だけ見て回る。
   * 上下には広げない。隣の行にも別のパスが並んでいることがあり、そこまで拾うと
   * 押していないほうが開く。
   */
  private fun tapTargetAtPixel(x: Float, y: Float): TapTarget? {
    val pos = cellAtPixel(x, y) ?: return null
    val emu = controller?.emulator ?: return null
    val lines = visibleLines()
    for (dc in intArrayOf(0, -1, 1)) {
      val col = pos.col + dc
      if (col < 0) continue
      ScreenScan.targetAt(lines, emu.cols, pos.row, col)?.let { return it }
    }
    return null
  }

  private fun beginSelection(pos: CellPos) {
    Logger.d("SEL", "beginSelection row=${pos.row} col=${pos.col}")
    selStart = pos
    selEnd = pos
    // 新規選択は end を伸ばしていくのが自然なので end-drag モード。
    draggingStart = false
    // FLAG_IGNORE_VIEW_SETTING を付けて、View 側 haptic が無効でも振動させる。
    // これを付けないとユーザ側で「触覚フィードバック」をオフにしていると振動 0 になり、
    // 「長押しが効いてない」と感じる。
    performHapticFeedback(
        HapticFeedbackConstants.LONG_PRESS,
        HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
    )
    invalidate()
  }

  private fun extendSelection(pos: CellPos) {
    if (selStart == null) return
    if (draggingStart) selStart = pos else selEnd = pos
    invalidate()
    // CAB 位置を選択範囲に追従させる
    selectionActionMode?.invalidateContentRect()
  }

  /**
   * 選択中の ACTION_DOWN で、タッチ位置がどちらの端点に近いかを判定し、以後のドラッグで
   * 動かす側を決める。
   */
  private fun pickDragEndpoint(x: Float, y: Float) {
    val s = selStart ?: return
    val e = selEnd ?: return
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    if (cw <= 0f || ch <= 0f) return
    val hitRadius = selectionHandleTouchRadiusPx()
    val hitRadiusSq = hitRadius * hitRadius
    val handleDistStart = endpointHandleDistanceSq(x, y, s, isStart = true)
    val handleDistEnd = endpointHandleDistanceSq(x, y, e, isStart = false)
    if (handleDistStart <= hitRadiusSq || handleDistEnd <= hitRadiusSq) {
      draggingStart =
          when {
            handleDistStart <= hitRadiusSq && handleDistEnd <= hitRadiusSq ->
                handleDistStart <= handleDistEnd
            handleDistStart <= hitRadiusSq -> true
            else -> false
          }
      Logger.d(
          "SEL",
          "pickDragEndpoint handle draggingStart=$draggingStart startHit=${handleDistStart <= hitRadiusSq} endHit=${handleDistEnd <= hitRadiusSq}",
      )
      return
    }
    val dxs = (x - (s.col + 0.5f) * cw)
    val dys = (y - (s.row + 0.5f) * ch)
    val dxe = (x - (e.col + 0.5f) * cw)
    val dye = (y - (e.row + 0.5f) * ch)
    val distStart = dxs * dxs + dys * dys
    val distEnd = dxe * dxe + dye * dye
    draggingStart = distStart < distEnd
  }

  /**
   * 選択中のタップ位置が「選択範囲の rect 内」または「両端ハンドルの hit radius 内」
   * かを判定する。範囲外 = 「全く違う場所」と判断され、ACTION_UP で選択解除。
   */
  private fun isTouchNearSelection(x: Float, y: Float): Boolean {
    val s = selStart ?: return false
    val e = selEnd ?: return false
    // 1) 選択範囲の cell rect に当たっているか
    val tap = cellAtPixel(x, y)
    if (tap != null) {
      val swap = s.row > e.row || (s.row == e.row && s.col > e.col)
      val r1 = if (swap) e.row else s.row
      val c1 = if (swap) e.col else s.col
      val r2 = if (swap) s.row else e.row
      val c2 = if (swap) s.col else e.col
      val inRow = tap.row in r1..r2
      val inRange =
          when {
            !inRow -> false
            r1 == r2 -> tap.col in c1..c2
            tap.row == r1 -> tap.col >= c1
            tap.row == r2 -> tap.col <= c2
            else -> true
          }
      if (inRange) return true
    }
    // 2) ハンドル (両端) の hit radius 内か
    val hitRadius = selectionHandleTouchRadiusPx()
    val hitRadiusSq = hitRadius * hitRadius
    val handleDistStart = endpointHandleDistanceSq(x, y, s, isStart = true)
    val handleDistEnd = endpointHandleDistanceSq(x, y, e, isStart = false)
    return handleDistStart <= hitRadiusSq || handleDistEnd <= hitRadiusSq
  }

  private fun endpointHandleDistanceSq(x: Float, y: Float, pos: CellPos, isStart: Boolean): Float {
    val cw = renderer.cellWidth
    val ch = renderer.cellHeight
    val anchorX = (if (isStart) pos.col * cw else (pos.col + 1) * cw).coerceIn(0f, width.toFloat())
    val anchorY = ((pos.row + 1) * ch).coerceIn(0f, height.toFloat())
    val dx = x - anchorX
    val dy = y - anchorY
    return dx * dx + dy * dy
  }

  private fun selectionHandleTouchRadiusPx(): Float {
    val densityRadius = resources.displayMetrics.density * SELECTION_HANDLE_TOUCH_RADIUS_DP
    return maxOf(densityRadius, renderer.cellHeight * 1.25f, renderer.cellWidth * 2f)
  }

  private fun dismissSelectionActionModeOnly() {
    val am = selectionActionMode ?: return
    selectionActionMode = null
    am.finish()
  }

  private fun clearSelection() {
    if (selStart == null && selEnd == null) return
    selStart = null
    selEnd = null
    pendingClearOnUp = false
    // ActionMode が立っていたら一緒に閉じる。finish() は onDestroyActionMode を呼ぶが
    // その中で clearSelection() を呼び直さないように selectionActionMode を先に null 化。
    val am = selectionActionMode
    selectionActionMode = null
    am?.finish()
    invalidate()
  }

  /**
   * 選択範囲の近くに floating ActionMode (CAB) を出して「コピー」メニューを提示する。
   * `TYPE_FLOATING` は API 23+。`onGetContentRect` で選択範囲の矩形を返すと OS が
   * 最適な位置に CAB を配置してくれる（選択の直上 or 直下）。
   */
  private fun showSelectionActionMode() {
    val callback = object : ActionMode.Callback2() {
      override fun onCreateActionMode(mode: ActionMode, menu: Menu): Boolean {
        menu.add(0, MENU_COPY, 0, context.getString(R.string.terminal_copy))
        return true
      }

      override fun onPrepareActionMode(mode: ActionMode, menu: Menu): Boolean = false

      override fun onActionItemClicked(mode: ActionMode, item: MenuItem): Boolean =
          when (item.itemId) {
            MENU_COPY -> {
              copySelectionAndClear()
              mode.finish()
              true
            }
            else -> false
          }

      override fun onDestroyActionMode(mode: ActionMode) {
        // 外側タップや端点ドラッグ開始で CAB だけ閉じることがある。ここで選択まで
        // 消すと開始点を掴み直せないので、選択解除は TerminalView の単タップ処理に任せる。
        if (selectionActionMode === mode) {
          selectionActionMode = null
          invalidate()
        }
      }

      override fun onGetContentRect(mode: ActionMode, view: View?, outRect: android.graphics.Rect) {
        val s = selStart
        val e = selEnd
        if (s == null || e == null) {
          super.onGetContentRect(mode, view, outRect)
          return
        }
        val cw = renderer.cellWidth.toInt().coerceAtLeast(1)
        val ch = renderer.cellHeight.toInt().coerceAtLeast(1)
        val minRow = minOf(s.row, e.row)
        val maxRow = maxOf(s.row, e.row)
        val minCol = minOf(s.col, e.col)
        val maxCol = maxOf(s.col, e.col)
        outRect.set(minCol * cw, minRow * ch, (maxCol + 1) * cw, (maxRow + 1) * ch)
      }
    }
    selectionActionMode = startActionMode(callback, ActionMode.TYPE_FLOATING)
  }

  /**
   * 現在の選択範囲からテキストを抽出してクリップボードへ。終わったら選択をクリア。
   * 選択は表示中の可視セルを対象に、startRow..endRow のテキストを改行結合する。
   * 行末の空セルは trim して余計な空白を吐かない。
   */
  private fun copySelectionAndClear() {
    val s = selStart
    val e = selEnd
    val text = extractSelectionText()
    Logger.d(
        "SEL",
        "copySelectionAndClear s=$s e=$e textLen=${text?.length ?: -1}",
    )
    clearSelection()
    if (text.isNullOrEmpty()) {
      Toast.makeText(context, context.getString(R.string.terminal_copy_empty), Toast.LENGTH_SHORT)
          .show()
      return
    }
    val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
    if (cm == null) {
      Logger.w("SEL", "ClipboardManager not available")
      return
    }
    val clip = ClipData.newPlainText("anoterm selection", text)
    // ClipDescription.extras で IS_SENSITIVE=false を明示する。ただし Gboard は
    // IME_FLAG_NO_PERSONALIZED_LEARNING が付いた入力欄のコピーを履歴に残さないため、
    // 履歴を優先する設定では onCreateInputConnection 側でその flag だけ外す。
    // 定数 `EXTRA_IS_SENSITIVE` は API 33+ なので minSdk 24 互換のため文字列リテラル。
    clip.description.extras = android.os.PersistableBundle().apply {
      putBoolean(EXTRA_IS_SENSITIVE, false)
    }
    logClipboardCopyRequest(text, clip)
    cm.setPrimaryClip(clip)
    // 読み戻しで本当にクリップボードに書き込めたか検証。失敗時は Toast で知らせる。
    val primaryClip = try {
      cm.primaryClip
    } catch (t: Throwable) {
      Logger.w("SEL", "readback failed", t)
      null
    }
    val readback = try {
      primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
    } catch (t: Throwable) {
      Logger.w("SEL", "readback text failed", t)
      null
    }
    logClipboardPrimaryClip(readback, primaryClip)
    Logger.d(
        "SEL",
        "setPrimaryClip readbackLen=${readback?.length ?: -1} match=${readback == text}",
    )
    val shown =
        if (readback == text) context.getString(R.string.terminal_copy_done, text.length)
        else context.getString(R.string.terminal_copy_mismatch)
    Toast.makeText(context, shown, Toast.LENGTH_SHORT).show()
  }

  private fun extractSelectionText(): String? {
    val s = selStart ?: return null
    val e = selEnd ?: return null
    val emu = controller?.emulator ?: return null
    // 正規化: (r1,c1) が左上、(r2,c2) が右下になるように。
    val swap = s.row > e.row || (s.row == e.row && s.col > e.col)
    val r1 = if (swap) e.row else s.row
    val c1 = if (swap) e.col else s.col
    val r2 = if (swap) s.row else e.row
    val c2 = if (swap) s.col else e.col
    // feed スレッドの put などと並行にセル内容が書き換わるのを避け、描画パスと同じ
    // emulator monitor 下でまとめて読む（コピー文字列の一瞬の乱れを防ぐ）。
    return synchronized(emu) {
    val buffer = emu.buffer
    // 画面の行番号をそのまま grid の行として読んではいけない。履歴を遡っている間、
    // 見えている上の行は scrollback から来ている。描画と同じ振り分けを通さないと、
    // 選んだ場所と違う文字がコピーされる。
    val offset = scrollOffset.coerceAtMost(buffer.rows + buffer.scrollbackSize)
    val sb = StringBuilder()
    for (r in r1..r2) {
      val startCol = if (r == r1) c1 else 0
      val endCol = if (r == r2) c2 else buffer.cols - 1
      val lineStart = sb.length
      var c = startCol
      while (c <= endCol) {
        val cell = visibleCellAt(buffer, offset, r, c)
        if (cell == null) { c++; continue }
        if (cell.continuation) { c++; continue }
        if (cell.codePoint == 0) sb.append(' ')
        else sb.appendCodePoint(cell.codePoint)
        c += if (cell.wide) 2 else 1
      }
      // 行末の trailing space は削る（セル配列は空きも space になるので行末が散らかる）
      while (sb.length > lineStart && sb[sb.length - 1] == ' ') sb.setLength(sb.length - 1)
      if (r < r2) sb.append('\n')
    }
    sb.toString()
    }
  }

  /**
   * DECCKM (application cursor keys mode) が有効なとき、ソフト矢印・DPAD 由来の
   * `ESC [ A/B/C/D/H/F` を `ESC O A/B/C/D/H/F` に書き換える。tmux や readline は
   * application mode でだけ後者を期待するので、この remap をしないと矢印が効かなくなる。
   *
   * modifier 付き（`ESC [ 1;5 A` など）は元々 CSI 固定の規約なので対象外（3 バイト長で
   * 絞り込み済み）。
   */
  private fun remapArrowIfAppMode(bytes: ByteArray): ByteArray {
    val ctl = controller ?: return bytes
    if (!ctl.emulator.applicationCursorKeys) return bytes
    if (bytes.size != 3) return bytes
    if (bytes[0] != 0x1B.toByte() || bytes[1] != '['.code.toByte()) return bytes
    val final = bytes[2]
    val remap =
        when (final) {
          'A'.code.toByte(), 'B'.code.toByte(), 'C'.code.toByte(), 'D'.code.toByte(),
          'H'.code.toByte(), 'F'.code.toByte() -> true
          else -> false
        }
    if (!remap) return bytes
    return byteArrayOf(0x1B, 'O'.code.toByte(), final)
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

  private fun logClipboardCopyRequest(text: String, clip: ClipData) {
    if (!BuildConfig.DEBUG) return
    val extras = clip.description.extras
    Logger.d(
        "CLIP_DBG",
        buildString {
          append("copyRequest ")
          append("len=${text.length}, ")
          append("fingerprint=${safeClipboardFingerprint(text)}, ")
          append("mime=${clip.description.mimeTypesCsv()}, ")
          append("extrasKeys=${extras?.keySet()?.joinToString() ?: "none"}, ")
          append("isSensitive=${extras?.getBoolean(EXTRA_IS_SENSITIVE)}, ")
          append("inputType=0x${lastInputType.toString(16)}, ")
          append("imeOptions=0x${lastImeOptions.toString(16)}, ")
          append("relaxedImePrivacyForClipboard=$relaxedImePrivacyForClipboard, ")
          append("defaultIme=${defaultInputMethodId()}, ")
          append("flagSecure=${windowFlagSecure()}, ")
          append("importantForAutofill=${importantForAutofillCompat()}, ")
          append("contentSensitivity=${contentSensitivityCompat()}, ")
          append("focused=$isFocused")
        },
    )
  }

  private fun logClipboardPrimaryClip(readback: String?, clip: ClipData?) {
    if (!BuildConfig.DEBUG) return
    val description = clip?.description
    val extras = description?.extras
    Logger.d(
        "CLIP_DBG",
        buildString {
          append("primaryClip ")
          append("readbackLen=${readback?.length ?: -1}, ")
          append("readbackFingerprint=${readback?.let(::safeClipboardFingerprint) ?: "null"}, ")
          append("label=${description?.label ?: "null"}, ")
          append("mime=${description?.mimeTypesCsv() ?: "none"}, ")
          append("extrasKeys=${extras?.keySet()?.joinToString() ?: "none"}, ")
          append("isSensitive=${extras?.getBoolean(EXTRA_IS_SENSITIVE)}")
        },
    )
  }

  private fun android.content.ClipDescription.mimeTypesCsv(): String {
    if (mimeTypeCount == 0) return "none"
    return (0 until mimeTypeCount).joinToString { getMimeType(it) }
  }

  private fun safeClipboardFingerprint(text: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    digest.update(CLIPBOARD_LOG_SALT)
    digest.update(text.toByteArray(Charsets.UTF_8))
    val bytes = digest.digest()
    val out = StringBuilder(16)
    for (i in 0 until 8) {
      val value = bytes[i].toInt() and 0xff
      if (value < 16) out.append('0')
      out.append(value.toString(16))
    }
    return out.toString()
  }

  private fun defaultInputMethodId(): String =
      try {
        Settings.Secure.getString(context.contentResolver, Settings.Secure.DEFAULT_INPUT_METHOD)
            ?: "unknown"
      } catch (_: Throwable) {
        "unknown"
      }

  private fun windowFlagSecure(): String {
    val activity = context.findActivity() ?: return "unknown"
    val flags = activity.window.attributes.flags
    return ((flags and android.view.WindowManager.LayoutParams.FLAG_SECURE) != 0).toString()
  }

  private fun importantForAutofillCompat(): String =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) importantForAutofill.toString()
      else "unsupported"

  private fun contentSensitivityCompat(): String =
      if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.VANILLA_ICE_CREAM) contentSensitivity.toString()
      else "unsupported"

  private fun Context.findActivity(): Activity? {
    var current: Context = this
    while (current is ContextWrapper) {
      if (current is Activity) return current
      current = current.baseContext
    }
    return null
  }
}

/** 可視画面上のセル位置。選択範囲の端点に使う。 */
data class CellPos(val row: Int, val col: Int)

private const val MENU_COPY = 1
private const val SELECTION_HANDLE_TOUCH_RADIUS_DP = 28f
private const val EXTRA_IS_SENSITIVE = "android.content.extra.IS_SENSITIVE"
private val CLIPBOARD_LOG_SALT: ByteArray = ByteArray(16).also { SecureRandom().nextBytes(it) }
