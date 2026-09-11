package app.anoterm.terminal.compose

import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.anoterm.data.prefs.LineEnding
import app.anoterm.terminal.TapTarget
import app.anoterm.terminal.TerminalSessionController
import app.anoterm.terminal.view.TerminalView
import app.anoterm.theme.TerminalPalette

/**
 * TerminalView を Compose の階層に載せる薄いブリッジ。
 *
 * - palette / fontSizeSp / lineSpacing / lineEnding が変わったら update ブロックで反映
 * - redrawSignal を監視して invalidate をトリガ（TerminalView 側の coalesce に委ねる）
 */
@Composable
fun TerminalHost(
    controller: TerminalSessionController,
    palette: TerminalPalette,
    fontSizeSp: Float,
    lineSpacing: Float,
    leftInsetDp: Float = 0f,
    lineEnding: LineEnding,
    relaxedImePrivacyForClipboard: Boolean = false,
    onTapTarget: (TapTarget) -> Unit = {},
    onScrollPosition: (Int) -> Unit = {},
    onFontSizeChanged: (Float) -> Unit = {},
    onTwoFingerDoubleTap: () -> Unit = {},
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
          it.setLineSpacing(lineSpacing)
          it.setLeftInsetDp(leftInsetDp)
          it.setLineEnding(lineEnding)
          it.setRelaxedImePrivacyForClipboard(relaxedImePrivacyForClipboard)
          it.onTapTarget = onTapTarget
          it.onScrollPositionChanged = onScrollPosition
          it.onFontSizeChanged = onFontSizeChanged
        it.onTwoFingerDoubleTap = onTwoFingerDoubleTap
          it.onTwoFingerDoubleTap = onTwoFingerDoubleTap
          viewRef.view = it
          viewBinding(it)
        }
      },
      update = {
        it.bind(controller)
        it.setPalette(palette)
        it.setFontSizeSp(fontSizeSp)
        // 設定で行間を変えても反映されなかったのは、ここに無かったから。
        it.setLineSpacing(lineSpacing)
        it.setLeftInsetDp(leftInsetDp)
        it.setLineEnding(lineEnding)
        it.setRelaxedImePrivacyForClipboard(relaxedImePrivacyForClipboard)
        it.onTapTarget = onTapTarget
        it.onScrollPositionChanged = onScrollPosition
        it.onFontSizeChanged = onFontSizeChanged
        viewRef.view = it
        viewBinding(it)
      },
      modifier = modifier,
      onRelease = { viewRef.view = null },
  )

  val lifecycleOwner = LocalLifecycleOwner.current

  // redraw / bell の購読はいずれも repeatOnLifecycle(STARTED) でラップし、アプリが
  // バックグラウンド（STOPPED）に入ったら停止する。これによりバックグラウンドのタブが
  // サーバ出力のたびに invalidate したり、BEL で振動したりする無駄を防ぐ。
  // 前面復帰時は collect が張り直され、下記のとおり一度 invalidate して最新状態を描く。
  LaunchedEffect(controller, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
      // 復帰直後は redrawSignal(replay 無し)が来るまで古い画面のままなので、まず 1 回描く。
      viewRef.view?.postInvalidateOnAnimation()
      controller.redrawSignal.collect { viewRef.view?.postInvalidateOnAnimation() }
    }
  }

  // BEL（0x07）受信で軽いハプティック。連続バイブは邪魔なので 500ms 以内は間引く。
  // 振動経路はここ 1 本に集約（以前は TerminalScreen 側にもスロットル無しの collect があり
  // 表示中タブの BEL で二重振動していた）。
  LaunchedEffect(controller, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
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
  }

  DisposableEffect(controller) { onDispose { /* タブは SessionManager が保持するので dispose しない */ } }
}
