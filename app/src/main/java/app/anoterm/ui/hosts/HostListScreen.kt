package app.anoterm.ui.hosts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import app.anoterm.data.db.AuthMethod
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import app.anoterm.BuildConfig
import app.anoterm.R
import app.anoterm.AnotermApp
import app.anoterm.data.prefs.AppPrefs
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HostListScreen(
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: HostListViewModel = viewModel(factory = HostListViewModel.Factory),
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val app = remember { AnotermApp.get() }
  val isPro by app.prefs.isPro.collectAsStateWithLifecycle()
  val developerMode by app.prefs.developerMode.collectAsStateWithLifecycle()
  val activeTabs by app.sessionManager.activeTabs.collectAsStateWithLifecycle()
  var showUpgradeDialog by remember { mutableStateOf(false) }
  var disconnectTarget by remember { mutableStateOf<Pair<Long, String>?>(null) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_hosts)) },
            actions = {
              IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
              }
            },
        )
      },
      floatingActionButton = {
        FloatingActionButton(
            onClick = {
              // Free tier は 3 個までに制限。4 個目以降はアップグレードダイアログ。
              if (!isPro && state.hosts.size >= AppPrefs.FREE_TIER_HOST_LIMIT) {
                showUpgradeDialog = true
              } else onAddHost()
            },
        ) {
          Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.hosts_add))
        }
      },
  ) { inner ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
      // Loopback は開発者モード (debug build かつ Settings で有効化) のときだけ見せる。
      // 一般ユーザの目に触れるとノイズでしかなく、release では存在を隠す。
      if (BuildConfig.DEBUG && developerMode) {
        item {
          ListItem(
              leadingContent = { Icon(Icons.Outlined.Computer, contentDescription = null) },
              headlineContent = { Text(stringResource(R.string.loopback_host_label)) },
              supportingContent = { Text("echo back / IME 動作確認用 (長押しで新規)") },
              modifier =
                  Modifier.fillMaxWidth().combinedClickable(
                      onClick = { onOpenTerminal("loopback") },
                      onLongClick = { onOpenTerminal("loopback:${System.currentTimeMillis()}") },
                  ),
          )
          HorizontalDivider()
        }
      }
      if (state.hosts.isEmpty()) {
        item { EmptyHostsInline() }
      } else {
        items(state.hosts, key = { it.id }) { h ->
          // このホストに紐づいている生きているタブがあるか（"host:<id>" / "host:<id>:<ts>"）。
          val hostPrefix = "host:${h.id}"
          val connected =
              activeTabs.any { it == hostPrefix || it.startsWith("$hostPrefix:") }
          ListItem(
              headlineContent = {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                  if (connected) {
                    Icon(
                        imageVector = Icons.Outlined.Computer,
                        contentDescription = "接続中",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp).padding(end = 4.dp),
                    )
                  }
                  Text(
                      h.label,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis,
                      modifier = Modifier.weight(1f, fill = false),
                  )
                  AuthBadge(h.auth)
                  if (h.useTmux) TmuxBadge(h.tmuxSession)
                }
              },
              supportingContent = {
                Text(
                    "${h.username}@${h.address}:${h.port} · 長押しで新規接続",
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
              },
              trailingContent = {
                Row {
                  if (connected) {
                    IconButton(
                        onClick = { disconnectTarget = h.id to h.label },
                    ) {
                      Icon(
                          Icons.Filled.PowerSettingsNew,
                          contentDescription = "切断",
                          tint = MaterialTheme.colorScheme.error,
                      )
                    }
                  }
                  IconButton(onClick = { onEditHost(h.id) }) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = stringResource(R.string.hosts_edit),
                    )
                  }
                }
              },
              modifier =
                  Modifier.fillMaxWidth().combinedClickable(
                      // タップ: 既存タブがあれば再利用、新規なら作成
                      onClick = { onOpenTerminal("host:${h.id}") },
                      // 長押し: 新規 SSH 接続としてもう 1 タブ追加
                      onLongClick = { onOpenTerminal("host:${h.id}:${System.currentTimeMillis()}") },
                  ),
          )
        }
      }
    }

    disconnectTarget?.let { (targetId, targetLabel) ->
      AlertDialog(
          onDismissRequest = { disconnectTarget = null },
          title = { Text("切断しますか?") },
          text = {
            Text(
                "${targetLabel} への SSH 接続を終了します。tmux 統合中の場合、リモート側の "
                    + "セッションは残るので次回接続時に続きから再開できます。",
            )
          },
          confirmButton = {
            TextButton(
                onClick = {
                  // そのホストに紐づいているタブを全て閉じる（host:<id>, host:<id>:<ts>…）
                  val prefix = "host:${targetId}"
                  activeTabs
                      .filter { it == prefix || it.startsWith("$prefix:") }
                      .forEach { app.sessionManager.closeTab(it) }
                  disconnectTarget = null
                },
            ) {
              Text("切断", color = MaterialTheme.colorScheme.error)
            }
          },
          dismissButton = {
            TextButton(onClick = { disconnectTarget = null }) { Text("キャンセル") }
          },
      )
    }

    if (showUpgradeDialog) {
      AlertDialog(
          onDismissRequest = { showUpgradeDialog = false },
          title = { Text("Pro 版にアップグレード") },
          text = {
            Text(
                "Free 版ではホストを ${AppPrefs.FREE_TIER_HOST_LIMIT} 個まで保存できます。\n"
                    + "Pro 版（買い切り ¥980）で無制限に保存、tmux 統合・SFTP・ポートフォワード等が解放されます。\n\n"
                    + "現在 Play Billing は準備中。設定画面の Pro スイッチから一時的に有効化できます。",
            )
          },
          confirmButton = {
            TextButton(
                onClick = {
                  showUpgradeDialog = false
                  onOpenSettings()
                },
            ) {
              Text("設定を開く")
            }
          },
          dismissButton = {
            TextButton(onClick = { showUpgradeDialog = false }) { Text("閉じる") }
          },
      )
    }
  }
}

/** 認証方式を表す小さなバッジ。label の右に置いて鍵 / パス どちらで繋ぐかを一目で示す。 */
@Composable
private fun AuthBadge(auth: AuthMethod) {
  val (text, bg, fg) =
      when (auth) {
        AuthMethod.PRIVATE_KEY ->
            Triple(
                "key",
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        AuthMethod.PASSWORD ->
            Triple(
                "pass",
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
      }
  Surface(
      color = bg,
      shape = RoundedCornerShape(4.dp),
      modifier = Modifier.padding(start = 6.dp),
  ) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
    )
  }
}

/** tmux 統合 ON のホストに「tmux:<session>」を表示して attach 先を見えるようにする。 */
@Composable
private fun TmuxBadge(session: String) {
  Surface(
      color = MaterialTheme.colorScheme.secondaryContainer,
      shape = RoundedCornerShape(4.dp),
      modifier = Modifier.padding(start = 6.dp),
  ) {
    Text(
        "tmux:$session",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.widthIn(max = 120.dp).padding(horizontal = 6.dp, vertical = 1.dp),
    )
  }
}

@Composable
private fun EmptyHostsInline() {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth().padding(32.dp),
  ) {
    Text(
        stringResource(R.string.hosts_empty_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(R.string.hosts_empty_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}
