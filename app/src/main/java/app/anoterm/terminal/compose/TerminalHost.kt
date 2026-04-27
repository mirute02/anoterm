package app.anoterm.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import app.anoterm.data.prefs.LineEnding
import app.anoterm.terminal.TerminalSessionController
import app.anoterm.terminal.view.TerminalView
import app.anoterm.theme.TerminalPalette

/**
 * TerminalView を Compose の階層に載せる薄いブリッジ。
 *
 * - palette / fontSizeSp / lineEnding が変わったら update ブロックで反映
 * - redrawSignal を監視して invalidate をトリガ（TerminalView 側の coalesce に委ねる）
 */
@Composable
fun TerminalHost(
    controller: TerminalSessionController,
    palette: TerminalPalette,
    fontSizeSp: Float,
    lineEnding: LineEnding,
    relaxedImePrivacyForClipboard: Boolean = false,
    modifier: Modifier = Modifier,
    viewBinding: (TerminalView) -> Unit = {},
) {
  val viewRef = remember { object { var view: TerminalView? = null } }

  AndroidView(
      factory = { ctx ->
        TerminalView(ctx).also {
          it.bind(controller)
          it.setPalette(palette)
          it.setFontSizeSp(fontSizeSp)
          it.setLineEnding(lineEnding)
          it.setRelaxedImePrivacyForClipboard(relaxedImePrivacyForClipboard)
          viewRef.view = it
          viewBinding(it)
        }
      },
      update = {
        it.bind(controller)
        it.setPalette(palette)
        it.setFontSizeSp(fontSizeSp)
        it.setLineEnding(lineEnding)
        it.setRelaxedImePrivacyForClipboard(relaxedImePrivacyForClipboard)
        viewRef.view = it
        viewBinding(it)
      },
      modifier = modifier,
      onRelease = { viewRef.view = null },
  )

  LaunchedEffect(controller) {
    controller.redrawSignal.collect { viewRef.view?.postInvalidateOnAnimation() }
  }

  // BEL（0x07）受信で軽いハプティック。連続バイブは邪魔なので 500ms 以内は間引く。
  LaunchedEffect(controller) {
    var last = 0L
    controller.bell.collect {
      val now = System.currentTimeMillis()
      if (now - last < 500) return@collect
      last = now
      viewRef.view?.performHapticFeedback(
          android.view.HapticFeedbackConstants.KEYBOARD_TAP,
          android.view.HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
      )
    }
  }

  DisposableEffect(controller) { onDispose { /* タブは SessionManager が保持するので dispose しない */ } }
}
