package com.example.wanoterm

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.compose.LocalNavigationEventDispatcherOwner
import androidx.navigationevent.compose.rememberNavigationEventDispatcherOwner
import com.example.wanoterm.ui.hosts.HostEditScreen
import com.example.wanoterm.ui.hosts.HostListScreen
import com.example.wanoterm.ui.lock.AppLockScreen
import com.example.wanoterm.ui.settings.CustomShortcutsScreen
import com.example.wanoterm.ui.settings.KnownHostsScreen
import com.example.wanoterm.ui.settings.SettingsScreen
import com.example.wanoterm.ui.settings.SshKeyGenScreen
import com.example.wanoterm.ui.settings.SshKeyHelpScreen
import com.example.wanoterm.ui.settings.SshKeyListScreen
import com.example.wanoterm.ui.terminal.TerminalScreen

@Composable
fun MainNavigation() {
  val app = remember { WanotermApp.get() }
  val lockRequired by app.prefs.biometricLock.collectAsStateWithLifecycle(initialValue = false)
  // 起動時は常にホスト選択（HostList）から始める。
  // 以前は lastTabId や activeTabIds から Terminal を自動で積んでいたが、
  // 削除済みホストや認証失敗 tab がいきなり表示される UX が混乱を招くため廃止。
  // 生きている activeTabs は HostList 上で「接続中」バッジとして見えるので、
  // ユーザがタップすれば 1 操作で復帰できる。
  val initial: NavKey = if (lockRequired) Lock else HostList
  val backStack = rememberNavBackStack(initial)
  val eventOwner = rememberNavigationEventDispatcherOwner(parent = null)

  CompositionLocalProvider(LocalNavigationEventDispatcherOwner provides eventOwner) {
  NavDisplay(
      backStack = backStack,
      onBack = { backStack.removeLastOrNull() },
      entryProvider =
          entryProvider {
            entry<Lock> {
              AppLockScreen(
                  onUnlocked = {
                    backStack.clear()
                    backStack.add(HostList)
                  },
              )
            }
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
              TerminalScreen(tabId = key.tabId, onBack = { backStack.removeLastOrNull() })
            }
            entry<Settings> {
              SettingsScreen(
                  onBack = { backStack.removeLastOrNull() },
                  onOpenKnownHosts = { backStack.add(KnownHosts) },
                  onOpenCustomShortcuts = { backStack.add(CustomShortcuts) },
                  onOpenSshKeyHelp = { backStack.add(SshKeyHelp) },
                  onOpenSshKeyGen = { backStack.add(SshKeyGen) },
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
}
