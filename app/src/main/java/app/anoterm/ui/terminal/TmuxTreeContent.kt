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
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.anoterm.BuildConfig
import app.anoterm.R
import app.anoterm.ssh.TmuxWindow

/**
 * 開いている SSH 接続と、その先の tmux をまとめて出す一覧。左の抽斗の中身。
 *
 * バーは「いま見ているセッションの中」を 1 タップで切り替えるためのもので、
 * 別の接続の別のセッションにいるウィンドウには届かない。ここはその逆で、
 * 全部を 1 か所に並べて探せるようにする。
 *
 * 階層は 接続 → セッション → ウィンドウ。SSH タブとセッションは別物なので、
 * 同じ深さに並べると「どのサーバーの work か」が読めなくなる。
 *
 * 入れ物 (抽斗) は呼ぶ側が持つ。ここは中身だけを描く。
 */
@Composable
fun TmuxTreeContent(
    connections: List<TmuxTreeConnection>?,
    /** いま前面にある接続。ここだけ「表示中」を出す。 */
    currentTabId: String,
    activity: TmuxActivityTracker,
    /** 向こうの Claude Code の権限モード。読めていなければ null。 */
    permissionMode: String? = null,
    onJump: (TmuxJump) -> Unit,
) {
  Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
    // 版を出しておく。手元の端末に何が入っているのか確かめる手段が無いと、
    // 「直したはずのものが直っていない」ときに、入っていないのか効いていないのかが
    // 切り分けられない。抽斗は一番よく開く場所なので、ここに置く。
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Text(
          text = stringResource(R.string.tmux_tree_title),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
      )
      // 版と、向こうの権限モード。パッドの点灯だけだと「何色が何」を覚える必要があるので、
      // 読める場所を 1 つ持っておく。
      Text(
          text = listOfNotNull(permissionMode, BuildConfig.VERSION_NAME).joinToString(" · "),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }

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
                items(
                    windows.size,
                    key = { i -> "w:" + conn.tabId + ":" + session + ":" + windows[i].index },
                ) { i ->
                  val w = windows[i]
                  WindowRow(
                      window = w,
                      here = conn.tabId == currentTabId && w.active,
                      dirty = activity.isDirty(conn.tabId, w),
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
      //
      // 理由が取れているならそれを出す。「tmux なし」とだけ出していた頃は、
      // 入っていないのか、起動していないのか、PATH に無いのかが区別できず、
      // 直しに行く先が分からなかった。文言はリモートが返した一行そのまま。
      // 3 つの場合を混ぜない。混ぜていたせいで「tmux なし」としか出ず、
      // どれに当たっているのか分からなかった。
      //   ・理由が返っている  → その一行をそのまま (入っていない / サーバー未起動 / PATH に無い)
      //   ・応答はあったが 0 件 → tmux は動いているが、この接続から見えるウィンドウが無い
      //   ・それ以外          → 従来の文言
      Text(
          text =
              conn.unavailableReason
                  ?: conn.emptyDetail?.let { (total, understood) ->
                    stringResource(R.string.tmux_tree_no_windows, total, understood) +
                        (conn.emptySample?.let { "\n" + it } ?: "")
                  }
                  ?: stringResource(R.string.tmux_tree_no_tmux),
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
private fun WindowRow(
    window: TmuxWindow,
    here: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
) {
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
    // 中で何が動いているか。claude と codex を並べて立てているとき、
    // ウィンドウ名だけではどちらがどちらか分からない。
    if (window.command.isNotBlank() && window.command != window.name) {
      Text(
          text = window.command,
          fontFamily = FontFamily.Monospace,
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.primary,
      )
    }
    if (dirty) {
      Text(
          text = stringResource(R.string.tmux_activity),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
      )
    }
    if (window.panes > 1) {
      Text(
          text = "(" + window.panes + ")",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}
