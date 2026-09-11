package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import app.anoterm.R
import kotlin.math.roundToInt

/**
 * キーボードを閉じている間だけ端末に重なる、半透明の返答パッド。
 *
 * 承認プロンプトに答えるためだけに IME を開くと、狭い画面の半分が塞がり、
 * 肝心の「何を承認するのか」が見えなくなる。1 文字打つために本文を隠すのは本末転倒。
 *
 * 三角に並べるのは、親指が届く範囲に 3 つ収めるのに一番素直な形だから。
 * 位置は好きな所へ動かせる。右利き・左利きも、片手の持ち方も人それぞれで、
 * 固定の「右下」が誰にとっても正解にはならない。
 *
 * 移動は中央のつまみをドラッグする。ボタン自体をドラッグ移動にすると、
 * 「押したつもりが動いた」「動かしたつもりが送信された」が避けられない。
 *
 * 上の 2 隅には、答えるためではないキーを置いてある。左上がキーボード、右上が ⇧Tab。
 * 数字より小さく、色も変えてあるのは、押し間違いの向きを揃えるため。答えるつもりで
 * モードを切り替えてしまうのは、その逆より取り返しがつかない。
 */
@Composable
fun FloatingReplyPad(
    onSend: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit,
    position: Pair<Float, Float>,
    onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier) {
    val density = LocalDensity.current
    val padPx = with(density) { PAD_SIZE.toPx() }
    val maxX = (constraints.maxWidth - padPx).coerceAtLeast(0f)
    val maxY = (constraints.maxHeight - padPx).coerceAtLeast(0f)

    // 位置は比率のまま持つ。px に直して持つと、使える幅が変わるたびに持ち直しになる。
    //
    // 以前は px を `remember(position, maxX)` で持っていた。ブラウザを開いた、tmux の行が
    // 出た、といった理由で maxX が変わると、その瞬間に remember が作り直されて位置が
    // 飛んでいた。指で動かしている最中に起きると「ワープ」に見える。
    var rx by remember { mutableStateOf(position.first) }
    var ry by remember { mutableStateOf(position.second) }
    // 外から位置が変わったとき（他の画面での変更、初期化）だけ追従する。
    LaunchedEffect(position) {
      rx = position.first
      ry = position.second
    }
    var dragging by remember { mutableStateOf(false) }

    // 掴んでいる最中に大きさが変わっても、ジェスチャを取り消さずに新しい値を読む。
    // pointerInput の key に入れると、変わった瞬間に指ごと離したことにされ、
    // 動かした分が捨てられる。
    val maxXNow = rememberUpdatedState(maxX)
    val maxYNow = rememberUpdatedState(maxY)

    val x = rx * maxX
    val y = ry * maxY

    Box(
        modifier =
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(PAD_SIZE)
                .alpha(if (dragging) 0.95f else 0.55f),
    ) {
      PadButton("1", Alignment.TopCenter) { onSend(replyBytes("1")) }
      PadButton("2", Alignment.BottomStart) { onSend(replyBytes("2")) }
      PadButton("3", Alignment.BottomEnd) { onSend(replyBytes("3")) }

      // 三角形の空いている 2 隅。数字より一回り小さく、色も変えてある。
      UtilityButton(Alignment.TopStart, onShowKeyboard) {
        Icon(
            Icons.Filled.Keyboard,
            contentDescription = stringResource(R.string.terminal_show_keyboard),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      // Claude Code の権限モードを回す Shift+Tab。承認を聞かれるのはキーボードを閉じて
      // 読んでいる時なので、そこから 1 タップで届くのが筋。
      //
      // 今どのモードかを色で示すのは一度作って取り消した。モードは Claude Code の
      // セッション記録から読めるが、どの記録がこのウィンドウのものかは tmux のペインの
      // 現在地からの当て推量で、同じディレクトリに複数立てていると取り違える。
      // 「承認が自動かどうか」で嘘を吐く表示は、表示が無いより悪い。
      UtilityButton(Alignment.TopEnd, { onSend(ESC_BACKTAB) }) {
        Text(
            text = "⇧⇥",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      // 中央のつまみ。ここだけがドラッグを受ける。
      Box(
          modifier =
              Modifier.align(Alignment.Center)
                  .size(HANDLE_SIZE)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.onSurfaceVariant)
                  .pointerInput(Unit) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                          dragging = false
                          onMove(rx, ry)
                        },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                      change.consume()
                      val mx = maxXNow.value
                      val my = maxYNow.value
                      if (mx > 0f) rx = (rx + drag.x / mx).coerceIn(0f, 1f)
                      if (my > 0f) ry = (ry + drag.y / my).coerceIn(0f, 1f)
                    }
                  },
      )
    }
  }
}

/** 答えるためではないキー。数字と見た目を分けて、押し間違いの向きを揃える。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.UtilityButton(
    alignment: Alignment,
    onClick: () -> Unit,
    content: @Composable () -> Unit,
) {
  Surface(
      onClick = onClick,
      modifier = Modifier.align(alignment).size(UTILITY_BUTTON_SIZE),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.surfaceVariant,
      shadowElevation = 3.dp,
  ) {
    Box(contentAlignment = Alignment.Center) { content() }
  }
}

@Composable
private fun androidx.compose.foundation.layout.BoxScope.PadButton(
    label: String,
    alignment: Alignment,
    onClick: () -> Unit,
) {
  Surface(
      onClick = onClick,
      modifier = Modifier.align(alignment).size(BUTTON_SIZE),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.secondaryContainer,
      shadowElevation = 3.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
          text = label,
          fontFamily = FontFamily.Monospace,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
  }
}

/** 番号を選ばせるプロンプトは改行まで来て初めて確定する。 */
private fun replyBytes(key: String): ByteArray = (key + "\r").toByteArray(Charsets.US_ASCII)

private val PAD_SIZE = 136.dp
private val BUTTON_SIZE = 52.dp
private val UTILITY_BUTTON_SIZE = 40.dp

/** Shift+Tab = CSI Z。ESC は 0x1B を明示する（文字列に埋めると編集で落ちる）。 */
val ESC_BACKTAB = byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte())
private val HANDLE_SIZE = 22.dp
