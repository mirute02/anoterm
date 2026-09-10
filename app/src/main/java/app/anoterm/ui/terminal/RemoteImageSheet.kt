package app.anoterm.ui.terminal

import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.anoterm.R
import app.anoterm.ssh.RemoteImage
import app.anoterm.ssh.RemoteImageResult
import app.anoterm.ssh.SshChannel

/**
 * 端末に出たパスを押したときに開く、画像の覗き窓。
 *
 * 「スクリーンショットで確認します」と言われても、パスだけ書かれても何も確かめられない。
 * 出来上がった図を見るために PC の前に戻るなら、スマホで作業している意味が薄れる。
 *
 * 全画面のビューアにはしていない。端末の内容が上に見えたまま、画像だけ下から出るほうが
 * 「今どの話をしているのか」を見失わない。拡大したければピンチで広げられる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteImageSheet(
    channel: SshChannel,
    path: String,
    onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var result by remember(path) { mutableStateOf<RemoteImageResult?>(null) }

  LaunchedEffect(channel, path) { result = RemoteImage.read(channel, path) }

  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
      // ファイル名を先に、太く。パスの全体は下に小さく置く。狭い画面で頭から省略されると
      // 「どのファイルの話か」だけが消えるので、順序を逆にしている。
      Text(
          text = path.substringAfterLast('/'),
          style = MaterialTheme.typography.titleMedium,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      Text(
          text = path,
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 2,
          overflow = TextOverflow.Ellipsis,
      )

      when (val r = result) {
        null ->
            Column(
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              CircularProgressIndicator()
              Text(
                  stringResource(R.string.image_loading),
                  style = MaterialTheme.typography.bodyMedium,
              )
            }
        is RemoteImageResult.Failed ->
            Text(
                text = stringResource(r.reason.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
        is RemoteImageResult.Ok -> {
          ZoomableImage(r)
          Text(
              text =
                  stringResource(
                      R.string.image_size,
                      humanBytes(r.byteCount),
                      r.bitmap.width,
                      r.bitmap.height,
                  ),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

/**
 * ピンチで拡大、ドラッグで移動、ダブルタップで元に戻す。
 *
 * 移動は画像が枠から離れない範囲に留める。自由に流せると、拡大したあと画像を見失って
 * 「消えた」と思わせる。
 */
@Composable
private fun ZoomableImage(image: RemoteImageResult.Ok) {
  var scale by remember(image) { mutableStateOf(1f) }
  var offset by remember(image) { mutableStateOf(Offset.Zero) }

  // 高さは画面から自分で決める。ボトムシートの中身には高さの制約が付かないことがあり、
  // 割合指定 (fillMaxHeight) だと潰れる。かといって固定 dp では小さい端末で
  // シートからはみ出して下が切れる。
  val frameHeight = (LocalConfiguration.current.screenHeightDp * 0.55f).dp
  BoxWithConstraints(
      modifier = Modifier.fillMaxWidth().height(frameHeight).clipToBounds(),
  ) {
    val maxX = constraints.maxWidth * (scale - 1f) / 2f
    val maxY = constraints.maxHeight * (scale - 1f) / 2f
    Image(
        bitmap = image.bitmap.asImageBitmap(),
        contentDescription = null,
        contentScale = ContentScale.Fit,
        modifier =
            Modifier.fillMaxSize()
                .graphicsLayer {
                  scaleX = scale
                  scaleY = scale
                  translationX = offset.x
                  translationY = offset.y
                }
                .pointerInput(image) {
                  detectTapGestures(
                      onDoubleTap = {
                        scale = 1f
                        offset = Offset.Zero
                      },
                  )
                }
                .pointerInput(image) {
                  detectTransformGestures { _, pan, zoom, _ ->
                    scale = (scale * zoom).coerceIn(1f, 8f)
                    val nx = if (maxX > 0f) (offset.x + pan.x).coerceIn(-maxX, maxX) else 0f
                    val ny = if (maxY > 0f) (offset.y + pan.y).coerceIn(-maxY, maxY) else 0f
                    offset = Offset(nx, ny)
                  }
                },
    )
  }
}

private fun humanBytes(n: Long): String =
    when {
      n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
      n >= 1024 -> "%.0f kB".format(n / 1024.0)
      else -> "$n B"
    }
