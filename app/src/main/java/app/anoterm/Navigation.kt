package app.anoterm

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import app.anoterm.ui.hosts.HostEditScreen
import app.anoterm.ui.hosts.HostListScreen
import app.anoterm.ui.lock.AppLock
import app.anoterm.ui.lock.AppLockScreen
import app.anoterm.ui.settings.CustomShortcutsScreen
import app.anoterm.ui.settings.KnownHostsScreen
import app.anoterm.ui.settings.SettingsScreen
import app.anoterm.ui.settings.SshKeyGenScreen
import app.anoterm.ui.settings.SshKeyHelpScreen
import app.anoterm.ui.settings.SshKeyListScreen
import app.anoterm.ui.terminal.TerminalScreen

@Composable
fun MainNavigation() {
  val app = remember { AnotermApp.get() }
  val locked by AppLock.locked.collectAsStateWithLifecycle()
  // 起動時は常にホスト選択（HostList）から始める。
  // 以前は lastTabId や activeTabIds から Terminal を自動で積んでいたが、
  // 削除済みホストや認証失敗 tab がいきなり表示される UX が混乱を招くため廃止。
  // 生きている activeTabs は HostList 上で「接続中」バッジとして見えるので、
  // ユーザがタップすれば 1 操作で復帰できる。
  val initial: NavKey = HostList
  val backStack = rememberNavBackStack(initial)
  val eventOwner = rememberNavigationEventDispatcherOwner(parent = null)

  // ロック画面はバックスタックの要素ではなく、上に被せる。
  // 要素にすると再ロックのたびに画面遷移が起き、解錠後にどこへ戻すかを
  // 自前で覚える羽目になる。被せるだけなら解錠すれば元の画面がそのまま出る。
  Box(modifier = Modifier.fillMaxSize()) {
  CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides eventOwner) {
  NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
          entryProvider {
            entry<HostList> {
              HostListScreen(
                  onAddHost = { backStack.add(HostEdit()) },
                  onEditHost = { id -> backStack.add(HostEdit(id)) },
                  onOpenTerminal = { tabId -> backStack.add(Terminal(tabId)) },
                  onOpenSettings = { backStack.add(Settings) },
              )
            }
            entry<HostEdit> { key ->
              HostEditScreen(
                  hostId = key.hostId,
                  onDone = { backStack.removeLastOrNull() },
                  onOpenKeyHelp = { backStack.add(SshKeyHelp) },
                  onOpenKeyGen = { backStack.add(SshKeyGen) },
              )
            }
            entry<Terminal> { key ->
              TerminalScreen(
                  tabId = key.tabId,
                  onBack = { backStack.removeLastOrNull() },
                  onEditHost = { id -> backStack.add(HostEdit(id)) },
                  onOpenKnownHosts = { backStack.add(KnownHosts) },
              )
            }
            entry<Settings> {
              SettingsScreen(
                  onBack = { backStack.removeLastOrNull() },
                  onOpenKnownHosts = { backStack.add(KnownHosts) },
                  onOpenCustomShortcuts = { backStack.add(CustomShortcuts) },
                  onOpenSshKeyList = { backStack.add(SshKeyList) },
              )
            }
            entry<KnownHosts> { KnownHostsScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<CustomShortcuts> { CustomShortcutsScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<SshKeyHelp> { SshKeyHelpScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<SshKeyGen> { SshKeyGenScreen(onBack = { backStack.removeLastOrNull() }) }
            entry<SshKeyList> {
              SshKeyListScreen(
                  onBack = { backStack.removeLastOrNull() },
                  onCreateNew = { backStack.add(SshKeyGen) },
                  onCreateHost = { backStack.add(HostEdit()) },
                  onOpenHelp = { backStack.add(SshKeyHelp) },
                  // 鍵一覧 → ホスト選択シートで選ばれたホストに繋ぐ。
                  // クリップボードへのコマンドコピーは画面側で完了済み。
                  onRegisterToHost = { _, hostId ->
                    backStack.add(Terminal("host:$hostId"))
                  },
              )
            }
          },
  )
  }

  if (locked) {
    // 裏の画面を隠しきるため不透明な Surface で覆う。裏は破棄しないので
    // 端末セッションも入力状態もそのまま残る。
    Surface(
        modifier = Modifier.fillMaxSize(),
        color = MaterialTheme.colorScheme.background,
    ) {
      // 戻るボタンでロック画面を素通りできないようにする。
      BackHandler(enabled = true) {}
      AppLockScreen(onUnlocked = { AppLock.unlock() })
    }
  }
  }
}
