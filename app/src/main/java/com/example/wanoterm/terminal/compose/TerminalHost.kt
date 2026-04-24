package com.example.wanoterm.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import com.example.wanoterm.data.prefs.LineEnding
import com.example.wanoterm.terminal.TerminalSessionController
import com.example.wanoterm.terminal.view.TerminalView
import com.example.wanoterm.theme.TerminalPalette

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
          viewRef.view = it
          viewBinding(it)
        }
      },
      update = {
        it.bind(controller)
        it.setPalette(palette)
        it.setFontSizeSp(fontSizeSp)
        it.setLineEnding(lineEnding)
        viewRef.view = it
        viewBinding(it)
      },
      modifier = modifier,
      onRelease = { viewRef.view = null },
  )

  LaunchedEffect(controller) {
    controller.redrawSignal.collect { viewRef.view?.postInvalidateOnAnimation() }
  }

  DisposableEffect(controller) { onDispose { /* タブは SessionManager が保持するので dispose しない */ } }
}
