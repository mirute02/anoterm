package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
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
 */
@Composable
fun FloatingReplyPad(
    onSend: (ByteArray) -> Unit,
    position: Pair<Float, Float>,
    onMove: (Float, Float) -> Unit,
    modifier: Modifier = Modifier,
) {
  BoxWithConstraints(modifier = modifier) {
    val density = LocalDensity.current
    val padPx = with(density) { PAD_SIZE.toPx() }
    val maxX = (constraints.maxWidth - padPx).coerceAtLeast(0f)
    val maxY = (constraints.maxHeight - padPx).coerceAtLeast(0f)

    // 比率で受け取り、画面内に収まる座標へ直す。回転しても同じあたりに残る。
    var x by remember(position.first, maxX) { mutableStateOf(position.first * maxX) }
    var y by remember(position.second, maxY) { mutableStateOf(position.second * maxY) }
    var dragging by remember { mutableStateOf(false) }

    Box(
        modifier =
            Modifier.offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                .size(PAD_SIZE)
                .alpha(if (dragging) 0.95f else 0.55f),
    ) {
      PadButton("1", Alignment.TopCenter) { onSend(replyBytes("1")) }
      PadButton("2", Alignment.BottomStart) { onSend(replyBytes("2")) }
      PadButton("3", Alignment.BottomEnd) { onSend(replyBytes("3")) }

      // 中央のつまみ。ここだけがドラッグを受ける。
      Box(
          modifier =
              Modifier.align(Alignment.Center)
                  .size(HANDLE_SIZE)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.onSurfaceVariant)
                  .pointerInput(maxX, maxY) {
                    detectDragGestures(
                        onDragStart = { dragging = true },
                        onDragEnd = {
                          dragging = false
                          onMove(
                              if (maxX > 0f) x / maxX else 0f,
                              if (maxY > 0f) y / maxY else 0f,
                          )
                        },
                        onDragCancel = { dragging = false },
                    ) { change, drag ->
                      change.consume()
                      x = (x + drag.x).coerceIn(0f, maxX)
                      y = (y + drag.y).coerceIn(0f, maxY)
                    }
                  },
      )
    }
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
private val HANDLE_SIZE = 22.dp
