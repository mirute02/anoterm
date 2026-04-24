package com.example.wanoterm.theme

import androidx.compose.ui.graphics.Color

// Tokyo Night 由来の UI 配色。端末背景と自然に連続するよう UI も紫寄りのダークで統一。
val BgDeep = Color(0xFF1A1B26) // terminal/app 背景
val BgSurface = Color(0xFF24283B)
val BgElevated = Color(0xFF2F334D)
val AccentCyan = Color(0xFF7AA2F7) // Tokyo Night 青（リンク色）
val AccentCyanDim = Color(0xFF414868)
val FgPrimary = Color(0xFFC0CAF5)
val FgSecondary = Color(0xFF565F89)
val Divider = Color(0xFF2F334D)
val Danger = Color(0xFFF7768E)
val Warn = Color(0xFFE0AF68)
val Ok = Color(0xFF9ECE6A)

/** ターミナル用 16 色パレット（ANSI）。UI のアクセントとは独立。 */
data class TerminalPalette(
    val background: Color,
    val foreground: Color,
    val cursor: Color,
    val selection: Color,
    val ansi: List<Color>, // 0..15
) {
  init {
    require(ansi.size == 16) { "ANSI palette must be 16 colors" }
  }

  companion object {
    /** wanoterm のデフォルト — Tokyo Night（Storm） */
    val TokyoNight =
        TerminalPalette(
            background = Color(0xFF1A1B26),
            foreground = Color(0xFFC0CAF5),
            cursor = Color(0xFF7AA2F7),
            selection = Color(0x55364A82),
            ansi =
                listOf(
                    Color(0xFF15161E), // 0 black
                    Color(0xFFF7768E), // 1 red
                    Color(0xFF9ECE6A), // 2 green
                    Color(0xFFE0AF68), // 3 yellow
                    Color(0xFF7AA2F7), // 4 blue
                    Color(0xFFBB9AF7), // 5 magenta
                    Color(0xFF7DCFFF), // 6 cyan
                    Color(0xFFA9B1D6), // 7 white
                    Color(0xFF414868), // 8 bright black
                    Color(0xFFFF7A93), // 9 bright red
                    Color(0xFFB9F27C), // 10 bright green
                    Color(0xFFFF9E64), // 11 bright yellow / orange
                    Color(0xFF7DA6FF), // 12 bright blue
                    Color(0xFFBB9AF7), // 13 bright magenta
                    Color(0xFF0DB9D7), // 14 bright cyan
                    Color(0xFFC0CAF5), // 15 bright white
                ),
        )

    val TermiusDark =
        TerminalPalette(
            background = Color(0xFF161C24),
            foreground = Color(0xFFE5EAF2),
            cursor = Color(0xFF5CC5F2),
            selection = Color(0x505CC5F2),
            ansi =
                listOf(
                    Color(0xFF2B313C),
                    Color(0xFFE06C75),
                    Color(0xFF98C379),
                    Color(0xFFE5C07B),
                    Color(0xFF61AFEF),
                    Color(0xFFC678DD),
                    Color(0xFF56B6C2),
                    Color(0xFFE5EAF2),
                    Color(0xFF5C6370),
                    Color(0xFFFF7F87),
                    Color(0xFFB0E08F),
                    Color(0xFFFFD98A),
                    Color(0xFF85C7FF),
                    Color(0xFFDA95F0),
                    Color(0xFF70D5E2),
                    Color(0xFFFFFFFF),
                ),
        )

    val SolarizedDark =
        TerminalPalette(
            background = Color(0xFF002B36),
            foreground = Color(0xFF93A1A1),
            cursor = Color(0xFF93A1A1),
            selection = Color(0x5093A1A1),
            ansi =
                listOf(
                    Color(0xFF073642),
                    Color(0xFFDC322F),
                    Color(0xFF859900),
                    Color(0xFFB58900),
                    Color(0xFF268BD2),
                    Color(0xFFD33682),
                    Color(0xFF2AA198),
                    Color(0xFFEEE8D5),
                    Color(0xFF586E75),
                    Color(0xFFCB4B16),
                    Color(0xFF93A1A1),
                    Color(0xFF657B83),
                    Color(0xFF839496),
                    Color(0xFF6C71C4),
                    Color(0xFFAEB6B6),
                    Color(0xFFFDF6E3),
                ),
        )

    val Dracula =
        TerminalPalette(
            background = Color(0xFF282A36),
            foreground = Color(0xFFF8F8F2),
            cursor = Color(0xFFF8F8F0),
            selection = Color(0x5044475A),
            ansi =
                listOf(
                    Color(0xFF21222C),
                    Color(0xFFFF5555),
                    Color(0xFF50FA7B),
                    Color(0xFFF1FA8C),
                    Color(0xFFBD93F9),
                    Color(0xFFFF79C6),
                    Color(0xFF8BE9FD),
                    Color(0xFFF8F8F2),
                    Color(0xFF6272A4),
                    Color(0xFFFF6E6E),
                    Color(0xFF69FF94),
                    Color(0xFFFFFFA5),
                    Color(0xFFD6ACFF),
                    Color(0xFFFF92DF),
                    Color(0xFFA4FFFF),
                    Color(0xFFFFFFFF),
                ),
        )
  }
}

/** ユーザー設定用の列挙。 */
enum class TerminalThemeChoice(val displayName: String) {
  TokyoNight("Tokyo Night"),
  TermiusDark("Termius Dark"),
  SolarizedDark("Solarized Dark"),
  Dracula("Dracula");

  fun toPalette(): TerminalPalette =
      when (this) {
        TokyoNight -> TerminalPalette.TokyoNight
        TermiusDark -> TerminalPalette.TermiusDark
        SolarizedDark -> TerminalPalette.SolarizedDark
        Dracula -> TerminalPalette.Dracula
      }
}
