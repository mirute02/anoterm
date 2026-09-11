package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.TextButton
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
 *
 * 行を短く押せばそこへ飛び、長く押せば消す（確認を挟む）。× を並べない理由は
 * [WindowRow] に書いた。
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun TmuxTreeContent(
    connections: List<TmuxTreeConnection>?,
    /** いま前面にある接続。ここだけ「表示中」を出す。 */
    currentTabId: String,
    activity: TmuxActivityTracker,
    onJump: (TmuxJump) -> Unit,
    /** 長押しで「消す」と言われたウィンドウ。確認は呼び出し側が出す。 */
    onKillWindow: (TmuxJump) -> Unit = {},
    /** 長押しで「消す」と言われたセッション。(タブ, セッション名)。 */
    onKillSession: (String, String) -> Unit = { _, _ -> },
    /** ホスト一覧へ戻る。抽斗は「行き先の一覧」なので、いちばん外側の行き先もここに置く。 */
    onGoHome: () -> Unit = {},
    /** 接続タブごと閉じる。 */
    onCloseTab: (String) -> Unit = {},
) {
  // 「整理」に入っている間だけ × を出す。長押しだけだと、そんな操作があること自体が
  // 見えない。かといって常に × を並べると、行き先を選ぶ的の隣に壊す的が並ぶ。
  // 見えるボタンで入る一時的なモードなら、どちらも避けられる。
  var managing by remember { mutableStateOf(false) }
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
      Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = BuildConfig.VERSION_NAME,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = { managing = !managing }) {
          Text(
              stringResource(
                  if (managing) R.string.tmux_tree_manage_done else R.string.tmux_tree_manage,
              ),
              style = MaterialTheme.typography.labelMedium,
          )
        }
      }
    }

    // 全画面のときは上のバーが無く、⋮ メニューも出せない。抽斗は ≡ で必ず開けるので、
    // 「ここから外へ出る」道はここにも要る。ウィンドウの行き先より前に置くのは、
    // 迷ったときに探されるのが上だから。
    Row(
        modifier =
            Modifier.fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .clickable(onClick = onGoHome)
                .padding(vertical = 10.dp),
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
      Icon(
          Icons.AutoMirrored.Filled.ArrowBack,
          contentDescription = null,
          modifier = Modifier.size(18.dp),
          tint = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      Text(
          text = stringResource(R.string.terminal_back_to_hosts),
          style = MaterialTheme.typography.bodyMedium,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
    HorizontalDivider()

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
              item(key = "c:" + conn.tabId) {
                ConnectionHeader(conn, managing = managing, onClose = { onCloseTab(conn.tabId) })
              }
              conn.sessions.forEach { (session, windows) ->
                item(key = "s:" + conn.tabId + ":" + session) {
                  SessionHeader(
                      session,
                      attached = session == conn.snapshot?.attached,
                      managing = managing,
                      onKill = { onKillSession(conn.tabId, session) },
                  )
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
                      managing = managing,
                      onKill = { onKillWindow(TmuxJump(conn.tabId, w)) },
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
private fun ConnectionHeader(conn: TmuxTreeConnection, managing: Boolean, onClose: () -> Unit) {
  HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
  Row(
      modifier = Modifier.fillMaxWidth().padding(top = 8.dp, bottom = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (managing) KillButton(onClose)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun SessionHeader(
    session: String,
    attached: Boolean,
    managing: Boolean,
    onKill: () -> Unit,
) {
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .combinedClickable(onClick = {}, onLongClick = onKill)
              .padding(start = 12.dp, top = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (managing) KillButton(onKill)
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

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun WindowRow(
    window: TmuxWindow,
    here: Boolean,
    dirty: Boolean,
    onClick: () -> Unit,
    managing: Boolean,
    onKill: () -> Unit,
) {
  val background =
      if (here) MaterialTheme.colorScheme.secondaryContainer else MaterialTheme.colorScheme.surface
  Row(
      modifier =
          Modifier.fillMaxWidth()
              .background(background)
              // 短く押せば飛ぶ、長く押せば消す。並んだ行に × を足すと、行き先を
              // 選ぶ操作の隣に破壊的な的が並ぶことになる。押し間違いの代償が違いすぎる。
              .combinedClickable(onClick = onClick, onLongClick = onKill)
              .padding(start = if (managing) 4.dp else 28.dp, top = 6.dp, bottom = 6.dp, end = 8.dp),
      horizontalArrangement = Arrangement.spacedBy(10.dp),
      verticalAlignment = Alignment.CenterVertically,
  ) {
    if (managing) KillButton(onKill)
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

/** 整理の間だけ出る「閉じる」。押した先で必ず確認が出る。 */
@Composable
private fun KillButton(onClick: () -> Unit) {
  Box(
      modifier =
          Modifier.size(28.dp).clip(CircleShape).clickable(onClick = onClick),
      contentAlignment = Alignment.Center,
  ) {
    Icon(
        Icons.Filled.Close,
        contentDescription = null,
        modifier = Modifier.size(16.dp),
        tint = MaterialTheme.colorScheme.error,
    )
  }
}
