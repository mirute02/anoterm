package app.anoterm.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.anoterm.R
import app.anoterm.ssh.RemoteImage
import app.anoterm.ssh.RemoteTextResult
import app.anoterm.ssh.SshChannel

/**
 * 端末に出たパスを押したときに開く、文章の覗き窓。
 *
 * Claude Code は「直した」と言ってパスを書くが、端末に残るのはそのパスだけで、
 * 何が書かれたのかは見えない。書いた物を確かめるのがこの作業の中心なのに、
 * それだけが手元でできなかった。
 *
 * 色は付けていない。言語ごとの色分けは、対応していない言語で急に素っ気なくなり、
 * 「対応が中途半端」に見える。行番号だけ振って、あとは等幅で素直に出す。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RemoteTextSheet(
    channel: SshChannel,
    path: String,
    /** 相対パスをどこから探すか決める手掛かり。tmux を使っていなければ null。 */
    tmuxSession: String?,
    onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  var result by remember(path) { mutableStateOf<RemoteTextResult?>(null) }

  LaunchedEffect(channel, path, tmuxSession) {
    result = RemoteImage.readText(channel, path, tmuxSession)
  }

  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
  ) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp).padding(bottom = 24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
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
        is RemoteTextResult.Failed ->
            Text(
                text = stringResource(r.reason.message),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(vertical = 48.dp),
            )
        is RemoteTextResult.Ok -> {
          val lines = remember(r.text) { r.text.split("\n") }
          // 横は折り返さずに流す。コードを折り返すと、字下げが崩れて構造が読めなくなる。
          val hScroll = rememberScrollState()
          LazyColumn(
              modifier =
                  Modifier.fillMaxWidth()
                      .height((LocalConfiguration.current.screenHeightDp * 0.6f).dp),
          ) {
            itemsIndexed(lines) { i, line ->
              Row(modifier = Modifier.horizontalScroll(hScroll)) {
                Text(
                    text = (i + 1).toString(),
                    modifier = Modifier.width(44.dp).padding(end = 8.dp),
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    textAlign = TextAlign.End,
                    maxLines = 1,
                )
                Text(
                    text = line.ifEmpty { " " },
                    fontFamily = FontFamily.Monospace,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    softWrap = false,
                )
              }
            }
          }
          Text(
              text = stringResource(R.string.text_lines, lines.size, humanSize(r.byteCount)),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}

private fun humanSize(n: Long): String =
    when {
      n >= 1024 * 1024 -> "%.1f MB".format(n / (1024.0 * 1024.0))
      n >= 1024 -> "%.0f kB".format(n / 1024.0)
      else -> "$n B"
    }
