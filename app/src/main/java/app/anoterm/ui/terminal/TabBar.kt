package app.anoterm.ui.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.anoterm.terminal.ConnectionState

/**
 * 接続タブの列。上のバーの中に収める。
 *
 * 以前は独立した 1 行を占めていた。上には既にバーと tmux の行があり、狭い画面で
 * 3 行を常に譲るのは高い。タブの名前はそのまま「今どの接続にいるか」なので、
 * バーの見出しと役割が重なっている。重なっているものは 1 つにする。
 *
 * × は選んでいるタブにだけ出す。閉じるのは今見ている物で、隣の物を閉じたい場面は
 * まず無い。並んだ × は幅を食ううえ、誤タップの的が増える。
 * どのタブも長押しで閉じられる（従来どおり）。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabStrip(
    tabs: List<String>,
    activeTabId: String,
    tabTitle: (String) -> String,
    /** 選んでいるタブの接続状態。丸で示す。 */
    activeState: ConnectionState,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  if (tabs.isEmpty()) return
  Row(
      modifier = modifier.horizontalScroll(rememberScrollState()),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    for (t in tabs) {
      val selected = t == activeTabId
      Row(
          modifier =
              Modifier.clip(RoundedCornerShape(8.dp))
                  .background(
                      if (selected) MaterialTheme.colorScheme.primaryContainer
                      else MaterialTheme.colorScheme.surfaceVariant,
                  )
                  .combinedClickable(
                      onClick = { onSelect(t) },
                      // 長押しで閉じる（間違って閉じる事故防止）
                      onLongClick = { onClose(t) },
                  )
                  .padding(
                      start = 8.dp,
                      end = if (selected) 2.dp else 8.dp,
                      top = 3.dp,
                      bottom = 3.dp,
                  ),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        if (selected) {
          Box(
              modifier = Modifier.size(7.dp).clip(CircleShape).background(dotColor(activeState)),
          )
          Spacer(Modifier.width(6.dp))
        }
        Text(
            text = tabTitle(t),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            color =
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (selected) {
          // × のタップ領域は 30dp。Material の最小 48dp より小さいが、バーの中に
          // 収める以上これ以上は取れない。長押しという逃げ道が別にある。
          Box(
              modifier =
                  Modifier.size(30.dp)
                      .clip(CircleShape)
                      .combinedClickable(onClick = { onClose(t) }, onLongClick = { onClose(t) }),
              contentAlignment = Alignment.Center,
          ) {
            Icon(
                Icons.Filled.Close,
                contentDescription = "close tab",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
            )
          }
        }
      }
    }
  }
}

private fun dotColor(state: ConnectionState): Color =
    when (state) {
      ConnectionState.Connected -> Color(0xFF4CAF50)
      ConnectionState.Connecting -> Color(0xFFFFC107)
      ConnectionState.Disconnected, ConnectionState.Failed -> Color(0xFFE53935)
      ConnectionState.Idle -> Color(0xFF9E9E9E)
    }
