package app.anoterm.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

private val AnotermColorScheme =
    darkColorScheme(
        primary = AccentCyan,
        onPrimary = BgDeep,
        primaryContainer = AccentCyanDim,
        onPrimaryContainer = BgDeep,
        secondary = FgSecondary,
        onSecondary = BgDeep,
        background = BgDeep,
        onBackground = FgPrimary,
        surface = BgSurface,
        onSurface = FgPrimary,
        surfaceVariant = BgElevated,
        onSurfaceVariant = FgSecondary,
        outline = Divider,
        error = Danger,
        onError = BgDeep,
    )

@Composable
fun AnotermTheme(content: @Composable () -> Unit) {
  // Termius 風のブランド色を常に優先 — dynamic color には追従しない（ターミナルユーザーは色の一貫性を求める）
  MaterialTheme(colorScheme = AnotermColorScheme, typography = AnotermTypography, content = content)
}
