package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import app.anoterm.R
import app.anoterm.ssh.SshChannel
import app.anoterm.ssh.TmuxController
import app.anoterm.ssh.TmuxListing
import app.anoterm.ssh.TmuxWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * サーバー側の tmux を、端末を見ながら切り替えるバー。
 *
 * ボトムシートに入れていたときは「開く → 選ぶ → 閉じる」の 3 操作が要り、しかも
 * 切り替えた結果はシートの裏に隠れていた。アプリの SSH タブが上に出ているのと
 * 同じ理由で、ここも常に出しておく。
 *
 * 左が**アタッチ先**（いま見ているセッション）、右が**そのセッションのウィンドウ**。
 * この 2 つは別の操作で、tmux 側でも別のコマンドになる。
 */
@Composable
fun TmuxBar(
    channel: SshChannel,
    /** クライアント特定用の環境変数名。[TmuxController.ttyVarFor] が作る。 */
    ttyVar: String,
    tabId: String,
    activity: TmuxActivityTracker,
    modifier: Modifier = Modifier,
) {
  val scope = rememberCoroutineScope()
  var listing by remember { mutableStateOf<TmuxListing?>(null) }
  var sessionMenuOpen by remember { mutableStateOf(false) }
  var reloadNonce by remember { mutableStateOf(0) }

  // 背面でも回し続けると、10 秒ごとに SSH のチャネルを開くことになる。
  // SessionManager が背面で自動再接続を止めているのと同じ理由で、ここも止める。
  val lifecycleOwner = LocalLifecycleOwner.current
  LaunchedEffect(channel, reloadNonce, lifecycleOwner) {
    lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
      while (true) {
        val next = TmuxController.snapshot(channel, ttyVar)
        listing = next
        if (next is TmuxListing.Ok) activity.observe(tabId, next.snapshot.windows)
        delay(POLL_INTERVAL_MS)
      }
    }
  }

  var switchFailed by remember { mutableStateOf(false) }

  when (val current = listing) {
    null -> return
    is TmuxListing.Unavailable -> {
      // ホスト設定で tmux を有効にしているのに使えない、という状況。バーを黙って
      // 消すと「自動 attach が効いていない」ことに気づけないので理由を出す。
      Text(
          text = stringResource(R.string.tmux_unavailable, current.reason),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
          modifier =
              modifier
                  .fillMaxWidth()
                  .background(MaterialTheme.colorScheme.surfaceVariant)
                  .padding(horizontal = 8.dp, vertical = 4.dp),
      )
      return
    }
    is TmuxListing.Ok -> Unit
  }
  val snapshot = (listing as TmuxListing.Ok).snapshot
  val windows = snapshot.attachedWindows
  // 切り替える先が無いなら場所を取らせない。SSH タブのバーと同じ考え方。
  if (windows.size <= 1 && snapshot.sessions.size <= 1) return

  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .background(MaterialTheme.colorScheme.surfaceVariant)
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 8.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    if (snapshot.sessions.size > 1) {
      TextButton(onClick = { sessionMenuOpen = true }) {
        Text(
            text = snapshot.attached ?: stringResource(R.string.tmux_session_unknown),
            style = MaterialTheme.typography.labelMedium,
        )
        Icon(Icons.Filled.ArrowDropDown, contentDescription = null)
      }
      DropdownMenu(expanded = sessionMenuOpen, onDismissRequest = { sessionMenuOpen = false }) {
        snapshot.sessions.forEach { name ->
          DropdownMenuItem(
              text = { Text(name) },
              onClick = {
                sessionMenuOpen = false
                val tty = snapshot.clientTty
                scope.launch {
                  // 自分のクライアントが特定できていないと、別の端末を切り替えて
                  // しまいかねない。押しても何も起きない方がまだ良い。
                  switchFailed =
                      tty == null || !TmuxController.switchClient(channel, tty, name)
                  reloadNonce++
                }
              },
          )
        }
      }
    }

    if (switchFailed) {
      Text(
          text = stringResource(R.string.tmux_switch_failed),
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.error,
      )
    }

    windows.forEach { w ->
      val dirty = activity.isDirty(tabId, w)
      FilterChip(
          selected = w.active,
          onClick = {
            activity.markSeen(tabId, w)
            scope.launch {
              switchFailed = !TmuxController.selectWindow(channel, w)
              reloadNonce++
            }
          },
          label = {
            Text(
                chipLabel(w, dirty),
                fontFamily = FontFamily.Monospace,
                style = MaterialTheme.typography.labelMedium,
            )
          },
          modifier = Modifier.height(32.dp),
      )
    }
  }
}

/** 開いている間だけ引き直す。端末側で新しいウィンドウを作られても一覧が古びない。 */
private const val POLL_INTERVAL_MS = 10_000L

private fun chipLabel(w: TmuxWindow, dirty: Boolean): String {
  val panes = if (w.panes > 1) " (" + w.panes + ")" else ""
  // 更新があったウィンドウには印を付ける。色だけで示すと、色覚や輝度設定に左右される。
  val mark = if (dirty) "\u25cf " else ""
  return mark + w.index + " " + w.name + panes
}
