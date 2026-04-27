package app.anoterm.terminal.emulator

/** 文字色 / 背景色。0..15 は ANSI 16 色、Default はパレット外（テーマ既定）、Rgb は 24bit。 */
sealed interface AnsiColor {
  data object Default : AnsiColor

  data class Indexed(val index: Int) : AnsiColor

  data class Rgb(val r: Int, val g: Int, val b: Int) : AnsiColor
}

/** 1 セルに紐付くスタイル属性。 */
data class CellStyle(
    val fg: AnsiColor = AnsiColor.Default,
    val bg: AnsiColor = AnsiColor.Default,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val underline: Boolean = false,
    val reverse: Boolean = false,
    val strike: Boolean = false,
    val dim: Boolean = false,
) {
  companion object {
    val Default = CellStyle()
  }
}
