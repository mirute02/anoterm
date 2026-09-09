package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.anoterm.R
import app.anoterm.ssh.TmuxWindow

/**
 * 開いている SSH 接続と、その先の tmux をまとめて出す一覧。
 *
 * バーは「いま見ているセッションの中」を 1 タップで切り替えるためのもので、
 * 別の接続の別のセッションにいるウィンドウには届かない。ここはその逆で、
 * 全部を 1 か所に並べて探せるようにする。
 *
 * 階層は 接続 → セッション → ウィンドウ。SSH タブとセッションは別物なので、
 * 同じ深さに並べると「どのサーバーの work か」が読めなくなる。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxTreeSheet(
    connections: List<TmuxTreeConnection>?,
    /** いま前面にある接続。ここだけ「表示中」を出す。 */
    currentTabId: String,
    onJump: (TmuxJump) -> Unit,
    onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      Text(
          text = stringResource(R.string.tmux_tree_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(vertical = 8.dp),
      )

      when {
        connections == null ->
            Text(
                text = stringResource(R.string.tmux_tree_loading),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        connections.isEmpty() ->
            Text(
                text = stringResource(R.string.tmux_tree_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        else ->
            LazyColumn(modifier = Modifier.fillMaxWidth()) {
              connections.forEach { conn ->
                item(key = "c:" + conn.tabId) { ConnectionHeader(conn) }
                conn.sessions.forEach { (session, windows) ->
                  item(key = "s:" + conn.tabId + ":" + session) {
                    SessionHeader(session, attached = session == conn.snapshot?.attached)
                  }
                  items(windows.size, key = { i -> "w:" + conn.tabId + ":" + session + ":" + windows[i].index }) { i ->
                    val w = windows[i]
                    WindowRow(
                        window = w,
                        here = conn.tabId == currentTabId && w.active,
                        onClick = { onJump(TmuxJump(conn.tabId, w)) },
                    )
                  }
                }
              }
            }
      }
      Spacer(Modifier.height(24.dp))
    }
  }
}

@Composable
private fun ConnectionHeader(conn: TmuxTreeConnection) {
  HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(conn.label, style = MaterialTheme.typography.labelLarge)
    if (conn.snapshot == null || conn.snapshot.windows.isEmpty()) {
      // 接続は生きているが tmux が使えない。行ごと消すと「繋いだはずの接続が
      // 一覧に無い」ことになり、原因を探しに行く先が無くなる。
      Text(
          text = stringResource(R.string.tmux_tree_no_tmux),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun SessionHeader(session: String, attached: Boolean) {
  Row(
      modifier = Modifier.fillMaxWidth().padding(start = 12.dp, top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    Text(session, style = MaterialTheme.typography.labelMedium)
    if (attached) {
      Text(
          text = stringResource(R.string.tmux_tree_attached),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
      )
    }
  }
}

@Composable
private fun WindowRow(window: TmuxWindow, here: Boolean, onClick: () -> Unit) {
  val background =
      if (here) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(background)
              .clickable(onClick = onClick)
              .padding(start = 28.dp, top = 10.dp, bottom = 10.dp, end = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
  ) {
    Text(
        text = window.index.toString(),
        fontFamily = FontFamily.Monospace,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Text(text = window.name, style = MaterialTheme.typography.bodyMedium)
    if (window.panes > 1) {
      Text(
          text = "(" + window.panes + ")",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
