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
  // プロセスが一度 kill されて再起動したときでも、生きている SessionBundle があれば
  // HostList ではなく直接そのターミナルに戻せるように、初期 backstack を組む。
  // （フォアグラウンドサービスで process を守るのは別途実装必要だが、その前の回避策）
  // プロセスが一度 kill されて再起動した場合でも最後に開いていたタブへ戻る:
  //   1. activeTabs に生きてる bundle があればそれを優先（同プロセス復帰）
  //   2. なければ SharedPreferences の lastTabId を参照（プロセス kill 復帰）
  //   3. biometric ロック中は両方とも無視、ロック解除後に遷移
  val initial: NavKey = if (lockRequired) Lock else HostList
  val resumeTabId: String? =
      if (lockRequired) null
      else app.sessionManager.activeTabIds().firstOrNull() ?: app.prefs.lastTabId.value
  val backStack =
      rememberNavBackStack(
          *listOfNotNull(initial, resumeTabId?.let { Terminal(it) }).toTypedArray(),
      )
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
              )
            }
          },
  )
  }
}
