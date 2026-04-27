package app.anoterm.ui.terminal

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
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
import androidx.compose.ui.unit.dp

/**
 * タブバー。タップで切替、× で閉じる。× のタップ領域を広めに取り、誤タップで
 * 隣のタブを閉じてしまう事故を防ぐ。
 * 閉じる前に一段確認（簡易 UX）：長押しで即閉じ、通常タップはラベル選択扱い。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TabBar(
    tabs: List<String>,
    activeTabId: String,
    tabTitle: (String) -> String,
    onSelect: (String) -> Unit,
    onClose: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
  if (tabs.isEmpty()) return
  Row(
      modifier = modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp),
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
                  .padding(start = 10.dp, end = 4.dp, top = 2.dp, bottom = 2.dp),
          verticalAlignment = Alignment.CenterVertically,
      ) {
        Text(
            text = tabTitle(t),
            style = MaterialTheme.typography.labelLarge,
            color =
                if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                else MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.width(6.dp))
        // × のタップ領域を 32dp（Material 推奨の最小タップ 48dp より少し小さいが、
        // 密度の高いタブバーとしては許容範囲）
        Box(
            modifier =
                Modifier.size(32.dp)
                    .clip(RoundedCornerShape(16.dp))
                    .combinedClickable(onClick = { onClose(t) }, onLongClick = { onClose(t) }),
            contentAlignment = Alignment.Center,
        ) {
          Icon(
              Icons.Filled.Close,
              contentDescription = "close tab",
              modifier = Modifier.size(16.dp),
              tint =
                  if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                  else MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
      }
    }
  }
}
