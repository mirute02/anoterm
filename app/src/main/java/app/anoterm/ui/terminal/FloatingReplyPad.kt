package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
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
import androidx.compose.ui.graphics.Color
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
 * 配置と色の決めごと:
 *   上段 Esc(小) / 1(大) / キーボード(小)、下段に 2 / 3(大)、中央につまみ。
 *   **色は役割**。文字を送るもの (Esc・1・2・3) は同じ色、アプリの側を変えるもの
 *   (キーボード・⇧Tab) は別の色。**大きさは頻度**。1/2/3 が一番押されるので一番大きい。
 *
 * 押し間違いの向きを揃えてある。答えるつもりでモードを切り替えるのは、その逆より
 * 取り返しがつかない。
 */
@Composable
fun FloatingReplyPad(
    onSend: (ByteArray) -> Unit,
    onShowKeyboard: () -> Unit,
    /** true なら方向キーと決定、false なら返答キー。 */
    arrowMode: Boolean,
    /** つまみの長押しで中身を入れ替える。 */
    onToggleMode: () -> Unit,
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
      if (arrowMode) {
        // 方向キー。十字の真ん中はつまみが占めているので、決定は右下に置く。
        // 返答版の「3」と同じ場所で、親指の付け根から一番近い角でもある。
        // 矢印は 44dp。48dp だと十字とつまみが重なり、重なった帯は押せなくなる。
        ArrowButton("↑", Alignment.TopCenter) { onSend(ESC_UP) }
        ArrowButton("↓", Alignment.BottomCenter) { onSend(ESC_DOWN) }
        ArrowButton("←", Alignment.CenterStart) { onSend(ESC_LEFT) }
        ArrowButton("→", Alignment.CenterEnd) { onSend(ESC_RIGHT) }
        UtilityButton(
            alignment = Alignment.BottomEnd,
            onClick = { onSend(ENTER) },
            color = MaterialTheme.colorScheme.secondaryContainer,
        ) {
          Text(
              text = "⏎",
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.onSecondaryContainer,
          )
        }
      } else {
        PadButton("1", Alignment.TopCenter) { onSend(replyBytes("1")) }
        PadButton("2", Alignment.BottomStart) { onSend(replyBytes("2")) }
        PadButton("3", Alignment.BottomEnd) { onSend(replyBytes("3")) }
      }

      // Esc。改行は付けない。付けると、閉じた直後の画面に空行が入る。
      // 色は数字と同じ（どちらも文字を送るもの）、大きさは小さめ（頻度が下がる）。
      UtilityButton(
          alignment = Alignment.TopStart,
          onClick = { onSend(byteArrayOf(0x1B)) },
          color = MaterialTheme.colorScheme.secondaryContainer,
      ) {
        Text(
            text = "Esc",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSecondaryContainer,
        )
      }

      // アプリの側を変えるキーは、送るキーと色を分ける。
      UtilityButton(Alignment.TopEnd, onShowKeyboard) {
        Icon(
            Icons.Filled.Keyboard,
            contentDescription = stringResource(R.string.terminal_show_keyboard),
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
      // 中央のつまみ。ここだけがドラッグを受ける。
      //
      // 当たり判定は見た目より大きく取る。見えている丸と同じ 22dp で受けていた頃は、
      // 少し外すと何も起きず「たまに反応しない」になっていた。Material の最小タップは
      // 48dp で、その半分以下だった。指は自分がどこを押したか見えない。
      Box(
          modifier =
              Modifier.align(Alignment.Center)
                  .size(HANDLE_TOUCH_SIZE)
                  // 長押しで中身を入れ替える。パッドの上でいちばん「パッド自身」に近い
                  // 場所なので、パッドの性格を変える操作はここに置く。
                  .pointerInput(onToggleMode) {
                    detectTapGestures(onLongPress = { onToggleMode() })
                  }
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
          contentAlignment = Alignment.Center,
      ) {
        Box(
            modifier =
                Modifier.size(HANDLE_DOT_SIZE)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.onSurfaceVariant),
        )
      }
    }
  }
}

/** 答えるためではないキー。数字と見た目を分けて、押し間違いの向きを揃える。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.UtilityButton(
    alignment: Alignment,
    onClick: () -> Unit,
    /** 既定はアプリの側を変えるキーの色。文字を送るキーは数字と同じ色を渡す。 */
    color: Color = Color.Unspecified,
    content: @Composable () -> Unit,
) {
  Surface(
      onClick = onClick,
      modifier = Modifier.align(alignment).size(UTILITY_BUTTON_SIZE),
      shape = CircleShape,
      color = if (color == Color.Unspecified) MaterialTheme.colorScheme.surfaceVariant else color,
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

/** 方向キー。数字より一回り小さいのは、十字とつまみが重ならない大きさがこれだから。 */
@Composable
private fun androidx.compose.foundation.layout.BoxScope.ArrowButton(
    label: String,
    alignment: Alignment,
    onClick: () -> Unit,
) {
  Surface(
      onClick = onClick,
      modifier = Modifier.align(alignment).size(ARROW_BUTTON_SIZE),
      shape = CircleShape,
      color = MaterialTheme.colorScheme.secondaryContainer,
      shadowElevation = 3.dp,
  ) {
    Box(contentAlignment = Alignment.Center) {
      Text(
          text = label,
          fontWeight = FontWeight.Bold,
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.onSecondaryContainer,
      )
    }
  }
}

// CSI は必ず ESC (0x1B) 始まり。0x1B を明示して定義し、編集で invisibly に落ちるのを防ぐ。
private val ESC_UP = byteArrayOf(0x1B, '['.code.toByte(), 'A'.code.toByte())
private val ESC_DOWN = byteArrayOf(0x1B, '['.code.toByte(), 'B'.code.toByte())
private val ESC_RIGHT = byteArrayOf(0x1B, '['.code.toByte(), 'C'.code.toByte())
private val ESC_LEFT = byteArrayOf(0x1B, '['.code.toByte(), 'D'.code.toByte())
private val ENTER = "\r".toByteArray(Charsets.US_ASCII)

/** 番号を選ばせるプロンプトは改行まで来て初めて確定する。 */
private fun replyBytes(key: String): ByteArray = (key + "\r").toByteArray(Charsets.US_ASCII)

/**
 * パッドの一辺。
 *
 * 6 つ置くので 136dp では角が足りない。中段左 (⇧Tab) と下段左 (2) が 4dp 重なり、
 * 重なった分は後に描いた側が持っていく = 押せない帯ができる。152dp なら
 * 40dp の中段と 52dp の下段が干渉しない。
 */
private val PAD_SIZE = 152.dp
private val BUTTON_SIZE = 52.dp
private val UTILITY_BUTTON_SIZE = 40.dp
private val ARROW_BUTTON_SIZE = 44.dp

/** Shift+Tab = CSI Z。ESC は 0x1B を明示する（文字列に埋めると編集で落ちる）。 */
val ESC_BACKTAB = byteArrayOf(0x1B, '['.code.toByte(), 'Z'.code.toByte())
/** 見えている丸。小さいほうが本文を隠さない。 */
private val HANDLE_DOT_SIZE = 22.dp

/**
 * つまみが指を受ける範囲。丸より大きい。
 *
 * 152dp の枠で 48dp を中央に置くと (52,52)-(100,100) で、上段 52dp・下段 52dp の
 * どのボタンとも辺で接するだけで重ならない。広げるならこの計算をやり直すこと。
 */
private val HANDLE_TOUCH_SIZE = 48.dp
