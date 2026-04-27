package app.anoterm.terminal.view

import android.os.Bundle
import android.text.Selection
import android.text.SpannableStringBuilder
import android.text.TextUtils
import android.view.KeyEvent
import android.view.inputmethod.BaseInputConnection
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.CorrectionInfo
import android.view.inputmethod.CursorAnchorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import app.anoterm.util.Logger

/**
 * anoterm の端末ビュー専用 InputConnection。
 *
 * 設計方針（Termius が日本語入力に失敗する原因を避けるためのポイント）:
 *
 * 1. `BaseInputConnection` を継承し、端末本体とは別に IME 用の一時 `Editable` だけを持つ。
 * 2. composing text は [ComposingState] と `Editable` に反映し、TerminalView に再描画を依頼するが SSH には送らない。
 * 3. 確定（finishComposingText / 非空 commitText）は `onCommit` を通してフラットな UTF-8 として送出。
 * 4. `getTextBeforeCursor` 等は IME 用 `Editable` を返し、選択位置は `updateSelection` で逐次通知する。
 * 5. setComposingText に空文字が来たら "composing 状態を消す" と解釈（Gboard 対策）。
 * 6. KeyEvent.ACTION_MULTIPLE + getCharacters() を拾う（日本語ハードウェアキーボード対策）。
 * 7. requestCursorUpdates が来たら `onCursorAnchorInfoRequested` を呼び、ビューに画面座標を更新させる。
 */
class TerminalInputConnection(
    private val view: ITerminalInputTarget,
) : BaseInputConnection(view as android.view.View, /* fullEditor= */ true) {

  private val editable = SpannableStringBuilder()

  /**
   * IME が setSelection で絶対位置を渡してきた時の直前位置。Gboard の「長押し spacebar →
   * 左右スワイプ」などで IME が setSelection でカーソルを動かしてくるケースで、
   * delta を検出して DPAD_LEFT/RIGHT に変換し PTY に送るために使う。
   * editable 側のカーソル位置は `Selection.getSelectionStart(editable)` で拾えるが、
   * 複数回連続で setSelection が来ると「既に適用済み」の値と区別できないため、
   * 独立して保持する。
   */
  private var lastRawSelection = 0

  override fun getEditable(): android.text.Editable? {
    return editable
  }

  // --- Composing ---
  //
  // 方針：composition 中は絶対に SSH に送らない。`commitText` / `finishComposingText` が来て
  // 初めて送出する。IME の途中状態（日本語 IME なら "n" → "ん" への変換過程）が SSH 側に
  // 漏れて "半角が入る" 現象を防ぐため。
  // 英語の即時反映は EditorInfo の TYPE_TEXT_FLAG_NO_SUGGESTIONS によって Gboard が
  // composition を介さず直接 commitText を呼ぶ挙動を利用して実現する。

  override fun setComposingText(text: CharSequence?, newCursorPosition: Int): Boolean {
    val s = text?.toString() ?: ""
    if (s.isEmpty()) {
      view.composingState.clear()
      clearEditable()
    } else {
      view.composingState.setFromIme(s, newCursorPosition)
      syncEditable(s, view.composingState.cursor)
    }
    Logger.d("IME", "setComposingText len=${s.length} cursor=${view.composingState.cursor}")
    view.requestTerminalRedraw()
    notifyImeState()
    return true
  }

  override fun setComposingRegion(start: Int, end: Int): Boolean {
    // 端末バッファに合成範囲は存在しないため無視。
    return true
  }

  override fun finishComposingText(): Boolean {
    val committed = view.composingState.text
    if (committed.isNotEmpty()) {
      view.composeAndCommit(committed)
    }
    view.composingState.clear()
    clearEditable()
    Logger.d("IME", "finishComposingText len=${committed.length}")
    view.requestTerminalRedraw()
    notifyImeState()
    return true
  }

  override fun commitText(text: CharSequence?, newCursorPosition: Int): Boolean {
    val s = text?.toString() ?: ""
    view.composingState.clear()
    if (s.isNotEmpty()) {
      view.composeAndCommit(s)
    }
    clearEditable()
    Logger.d("IME", "commitText len=${s.length}")
    view.requestTerminalRedraw()
    notifyImeState()
    return true
  }

  override fun commitCompletion(text: CompletionInfo?): Boolean {
    val s = text?.text?.toString().orEmpty()
    if (s.isNotEmpty()) view.composeAndCommit(s)
    view.composingState.clear()
    clearEditable()
    Logger.d("IME", "commitCompletion len=${s.length}")
    notifyImeState()
    return true
  }

  override fun commitCorrection(correctionInfo: CorrectionInfo?): Boolean = true

  // --- Special keys ---

  @Suppress("DEPRECATION")
  override fun sendKeyEvent(event: KeyEvent): Boolean {
    Logger.d("IME", "sendKeyEvent action=${event.action} code=${event.keyCode}")
    if (event.action == KeyEvent.ACTION_MULTIPLE) {
      val chars = event.characters
      if (!chars.isNullOrEmpty()) {
        view.composeAndCommit(chars)
        clearEditable()
        notifyImeState()
        return true
      }
    }
    if (event.action == KeyEvent.ACTION_DOWN) {
      // 特殊キー（矢印・Backspace・Enter 等）は View 側で一次処理。合成中なら先に確定。
      if (view.composingState.isActive) {
        val committed = view.composingState.text
        view.composingState.clear()
        view.composeAndCommit(committed)
        clearEditable()
        notifyImeState()
      }
      val handled = view.onSpecialKey(event)
      if (handled) return true
      // ASCII 文字（物理 KB で日本語 IME 無効時など）
      val unicode = event.unicodeChar
      if (unicode != 0) {
        view.composeAndCommit(unicode.toChar().toString())
        clearEditable()
        notifyImeState()
        return true
      }
    }
    return super.sendKeyEvent(event)
  }

  // --- IME trace ログ ---
  //
  // Android のソフトキーボード「テキスト編集」パネル（↑↓←→、選択、コピー等）の矢印が
  // 効かない時に経路を特定するためのログ。多くの IME は `sendKeyEvent` ではなく
  // `setSelection` / `deleteSurroundingText` / `performPrivateCommand` 経由で来るので、
  // 全メソッドにトレースを仕込んで何が呼ばれているかを logcat で確認する。

  override fun beginBatchEdit(): Boolean {
    Logger.d("IME", "beginBatchEdit")
    return super.beginBatchEdit()
  }

  override fun endBatchEdit(): Boolean {
    Logger.d("IME", "endBatchEdit")
    return super.endBatchEdit()
  }

  override fun performContextMenuAction(id: Int): Boolean {
    Logger.d("IME", "performContextMenuAction id=$id")
    return super.performContextMenuAction(id)
  }

  override fun performEditorAction(editorAction: Int): Boolean {
    Logger.d("IME", "performEditorAction action=$editorAction")
    // Enter 扱い。composing があればまず確定。
    if (view.composingState.isActive) {
      val committed = view.composingState.text
      view.composingState.clear()
      view.composeAndCommit(committed)
    }
    clearEditable()
    view.sendEnter()
    notifyImeState()
    return true
  }

  override fun deleteSurroundingText(beforeLength: Int, afterLength: Int): Boolean {
    Logger.d("IME", "deleteSurroundingText before=$beforeLength after=$afterLength composing=${view.composingState.isActive}")
    if (view.composingState.isActive) {
      // composing 中の削除は composing から削る。Backspace を SSH へは送らない
      // （まだ SSH に行っていない文字なので）。
      val t = view.composingState.text
      if (t.isNotEmpty()) {
        val trimmed = t.dropLast(beforeLength.coerceAtMost(t.length))
        view.composingState.setAbsolute(trimmed, trimmed.length)
        syncEditable(trimmed, trimmed.length)
        view.requestTerminalRedraw()
      }
      notifyImeState()
      return true
    }
    repeat(beforeLength) { view.sendBackspace() }
    clearEditable()
    notifyImeState()
    return true
  }

  override fun deleteSurroundingTextInCodePoints(beforeLength: Int, afterLength: Int): Boolean =
      deleteSurroundingText(beforeLength, afterLength)

  // --- Query (return non-null empty — ATOK 対策) ---

  override fun getTextBeforeCursor(length: Int, flags: Int): CharSequence {
    val sel = selectionStart()
    val start = (sel - length).coerceAtLeast(0)
    return editable.subSequence(start, sel)
  }

  override fun getTextAfterCursor(length: Int, flags: Int): CharSequence {
    val sel = selectionEnd()
    val end = (sel + length).coerceAtMost(editable.length)
    return editable.subSequence(sel, end)
  }

  override fun getSelectedText(flags: Int): CharSequence {
    val start = selectionStart()
    val end = selectionEnd()
    if (start >= end) return ""
    return editable.subSequence(start, end)
  }

  override fun getCursorCapsMode(reqModes: Int): Int {
    // ターミナル入力は「文の先頭」の概念が無いので常に 0（CAP なし）を返す。
    // TextUtils.getCapsMode(empty, 0, ...) は SENTENCE start と判定し、
    // Gboard 等が自動で 1 文字目を大文字化して "Android" になる事故を起こす。
    return 0
  }

  override fun getExtractedText(request: ExtractedTextRequest?, flags: Int): ExtractedText =
      ExtractedText().also {
        it.text = editable
        it.partialStartOffset = -1
        it.partialEndOffset = -1
        it.selectionStart = selectionStart()
        it.selectionEnd = selectionEnd()
        it.startOffset = 0
      }

  override fun setSelection(start: Int, end: Int): Boolean {
    // 合成中は editable と composing 内のカーソル位置を同期するだけ（従来挙動）。
    if (view.composingState.isActive) {
      val clampedStart = start.coerceIn(0, editable.length)
      val clampedEnd = end.coerceIn(0, editable.length)
      Selection.setSelection(editable, clampedStart, clampedEnd)
      if (clampedStart == clampedEnd) {
        view.composingState.setAbsolute(view.composingState.text, clampedStart)
        view.requestTerminalRedraw()
      }
      lastRawSelection = start
      Logger.d("IME", "setSelection (composing) start=$clampedStart end=$clampedEnd")
      notifyImeState()
      return true
    }
    // 合成なしで caret が動いた場合: IME の「テキストカーソル移動」操作とみなし、
    // delta ぶんの DPAD_LEFT/RIGHT を合成して PTY に送る。これをしないと Gboard の
    // 長押しスペース→スワイプや Samsung Keyboard の方向矢印キーが全く効かない。
    if (start == end) {
      val delta = start - lastRawSelection
      if (delta != 0) {
        val keycode =
            if (delta > 0) android.view.KeyEvent.KEYCODE_DPAD_RIGHT
            else android.view.KeyEvent.KEYCODE_DPAD_LEFT
        val count = kotlin.math.abs(delta).coerceAtMost(16) // 暴走防止
        repeat(count) {
          val ev = android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, keycode)
          view.onSpecialKey(ev)
        }
        Logger.d("IME", "setSelection → arrow delta=$delta sent=${count}×${keycode}")
      }
    }
    lastRawSelection = start
    // editable 側も同期（IME が次回 delta 計算のため内部状態に合わせたがることがある）。
    val clampedStart = start.coerceIn(0, editable.length)
    val clampedEnd = end.coerceIn(0, editable.length)
    Selection.setSelection(editable, clampedStart, clampedEnd)
    notifyImeState()
    return true
  }

  override fun requestCursorUpdates(cursorUpdateMode: Int): Boolean {
    val immediate = (cursorUpdateMode and android.view.inputmethod.InputConnection.CURSOR_UPDATE_IMMEDIATE) != 0
    val monitor = (cursorUpdateMode and android.view.inputmethod.InputConnection.CURSOR_UPDATE_MONITOR) != 0
    view.setCursorUpdateMode(monitor)
    if (immediate) view.requestCursorAnchorInfo()
    return true
  }

  override fun performPrivateCommand(action: String?, data: Bundle?): Boolean {
    Logger.d("IME", "performPrivateCommand action=$action")
    return true
  }

  override fun reportFullscreenMode(enabled: Boolean): Boolean {
    // anoterm はフルスクリーン IME を使わない（EditorInfo.IME_FLAG_NO_FULLSCREEN 指定済）。
    return false
  }

  override fun closeConnection() {
    // IME 切断時の composing をクリアし、バッファに残させない。
    view.composingState.clear()
    clearEditable()
    view.requestTerminalRedraw()
    super.closeConnection()
  }

  private fun syncEditable(text: String, selection: Int) {
    editable.replace(0, editable.length, text)
    Selection.setSelection(editable, selection.coerceIn(0, editable.length))
  }

  private fun clearEditable() {
    editable.clear()
    Selection.setSelection(editable, 0)
    lastRawSelection = 0
  }

  private fun selectionStart(): Int = Selection.getSelectionStart(editable).coerceAtLeast(0)

  private fun selectionEnd(): Int = Selection.getSelectionEnd(editable).coerceAtLeast(0)

  private fun notifyImeState() {
    val selStart = selectionStart()
    val selEnd = selectionEnd()
    val candidatesStart = if (view.composingState.isActive) 0 else -1
    val candidatesEnd = if (view.composingState.isActive) editable.length else -1
    view.notifyImeSelection(selStart, selEnd, candidatesStart, candidatesEnd)
    // requestCursorAnchorInfo は内部で notifyImeSelection から呼ばれる（monitorCursor 経由）ので、
    // 合成中や IME がモニタしているときだけ明示的に再要求する。常時呼ぶと IPC が無駄に嵩む。
    if (view.composingState.isActive) view.requestCursorAnchorInfo()
  }
}

/**
 * TerminalInputConnection が呼び返すコールバック群。
 *
 * これを View で実装する（TerminalView）。テスト時にはモックで代替可能にするため interface に。
 */
interface ITerminalInputTarget {
  val composingState: ComposingState

  /** 再描画を要求。coalesce 済の invalidate。 */
  fun requestTerminalRedraw()

  /** IME に現在のカーソル画面座標を通知。 */
  fun requestCursorAnchorInfo()

  /** monitor モードのオン/オフ。 */
  fun setCursorUpdateMode(monitor: Boolean)

  /** IME に選択位置と composing 範囲を通知。 */
  fun notifyImeSelection(selStart: Int, selEnd: Int, candidatesStart: Int, candidatesEnd: Int)

  /** 文字列を確定送出（UTF-8 で SSH へ）。 */
  fun composeAndCommit(text: CharSequence)

  /** Enter キー送出（CR/LF 設定に従う）。 */
  fun sendEnter()

  /** Backspace 送出（通常 0x7F）。 */
  fun sendBackspace()

  /** 特殊キー処理。処理したら true。 */
  fun onSpecialKey(event: KeyEvent): Boolean
}
