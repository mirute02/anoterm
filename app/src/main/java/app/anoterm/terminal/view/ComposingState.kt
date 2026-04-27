package app.anoterm.terminal.view

/**
 * IME composing 中の文字列とカーソル位置を保持するだけの純粋なデータ。
 *
 * 描画層はこれを端末グリッド上にアンダーライン付きオーバーレイとして描く。
 * 確定（finishComposingText）時に `text` を SSH に送出して `clear()`。
 */
class ComposingState {
  var text: String = ""
    private set

  /** text 中の IME カーソル位置（code unit オフセット）。Gboard が送ってくる newCursorPosition に従う。 */
  var cursor: Int = 0
    private set

  val isActive: Boolean
    get() = text.isNotEmpty()

  fun setFromIme(newText: CharSequence, newCursorPosition: Int) {
    text = newText.toString()
    cursor = resolveCursor(text.length, newCursorPosition)
  }

  fun setAbsolute(newText: CharSequence, absoluteCursor: Int) {
    text = newText.toString()
    cursor = absoluteCursor.coerceIn(0, text.length)
  }

  fun clear() {
    text = ""
    cursor = 0
  }

  private fun resolveCursor(textLength: Int, newCursorPosition: Int): Int =
      when {
        newCursorPosition > 0 -> (textLength + newCursorPosition - 1).coerceIn(0, textLength)
        else -> newCursorPosition.coerceIn(0, textLength)
      }
}
