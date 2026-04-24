package com.example.wanoterm.ui.terminal

import android.content.Context
import android.view.inputmethod.InputMethodManager
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wanoterm.R
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.ssh.LoopbackChannel
import com.example.wanoterm.ssh.SessionBundle
import com.example.wanoterm.ssh.SshChannel
import com.example.wanoterm.terminal.ConnectionState
import com.example.wanoterm.terminal.TerminalSessionController
import com.example.wanoterm.terminal.compose.TerminalHost
import com.example.wanoterm.terminal.view.TerminalView
import kotlinx.coroutines.launch

sealed interface TabScreenState {
  data object Loading : TabScreenState

  data object Connecting : TabScreenState

  data class Ready(val bundle: SessionBundle) : TabScreenState

  data class Error(val message: String) : TabScreenState
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TerminalScreen(tabId: String, onBack: () -> Unit) {
  val app = remember { WanotermApp.get() }
  val theme by app.prefs.theme.collectAsStateWithLifecycle()
  val fontSizeSp by app.prefs.fontSizeSp.collectAsStateWithLifecycle()
  val lineEnding by app.prefs.lineEnding.collectAsStateWithLifecycle()
  val activeTabs by app.sessionManager.activeTabs.collectAsStateWithLifecycle()
  val customShortcuts by app.prefs.customShortcuts.collectAsStateWithLifecycle()

  var state: TabScreenState by remember { mutableStateOf(TabScreenState.Loading) }
  var showHelp by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var showTmux by remember { mutableStateOf(false) }
  // カスタムショートカットバーはデフォルトで非表示。下部のツールバー右端の apps アイコンで切替。
  var showShortcutBar by remember { mutableStateOf(false) }

  // 端末ラベル resolver。tabId → 表示名を DB から引いてキャッシュ。
  // タイトルとタブバー両方で使い回す。
  val tabLabels = remember { mutableStateMapOf<String, String>() }
  LaunchedEffect(activeTabs) {
    for (t in activeTabs) {
      if (tabLabels.containsKey(t)) continue
      tabLabels[t] =
          when {
            t.startsWith("loopback") -> "Local echo"
            t.startsWith("host:") -> {
              val hostId = t.removePrefix("host:").substringBefore(":").toLongOrNull()
              val host = hostId?.let { app.database.hostDao().findById(it) }
              host?.label ?: t.removePrefix("host:").substringBefore(":")
            }
            else -> t
          }
    }
  }
  fun labelFor(id: String): String = tabLabels[id] ?: id.substringBefore(":")

  LaunchedEffect(tabId) {
    val existing = app.sessionManager.get(tabId)
    if (existing != null) {
      state = TabScreenState.Ready(existing)
      return@LaunchedEffect
    }
    when {
      tabId.startsWith("loopback") -> {
        val controller = TerminalSessionController(initialRows = 24, initialCols = 80)
        controller.setConnectionState(ConnectionState.Connected)
        val channel = LoopbackChannel()
        val bundle = app.sessionManager.getOrCreate(tabId) { SessionBundle(controller, channel) }
        state = TabScreenState.Ready(bundle)
      }
      tabId.startsWith("host:") -> {
        state = TabScreenState.Connecting
        val hostId = tabId.removePrefix("host:").substringBefore(":").toLongOrNull()
        if (hostId == null) {
          state = TabScreenState.Error("invalid tabId")
          return@LaunchedEffect
        }
        val host = app.database.hostDao().findById(hostId)
        if (host == null) {
          state = TabScreenState.Error("host not found")
          return@LaunchedEffect
        }
        val params = app.hostRepository.toConnectParams(host)
        if (params == null) {
          state = TabScreenState.Error("secret missing")
          return@LaunchedEffect
        }
        val controller = TerminalSessionController(initialRows = 24, initialCols = 80)
        controller.setConnectionState(ConnectionState.Connecting)
        try {
          val channel =
              SshChannel.connect(
                  params = params,
                  knownHostDao = app.database.knownHostDao(),
                  initialCols = 80,
                  initialRows = 24,
              )
          val bundle = app.sessionManager.getOrCreate(tabId) { SessionBundle(controller, channel) }
          controller.setConnectionState(ConnectionState.Connected)
          state = TabScreenState.Ready(bundle)
        } catch (t: Throwable) {
          controller.setConnectionState(ConnectionState.Failed)
          controller.dispose()
          // 例外 message はサーバ名/ユーザ名/鍵パス等を含みうるため UI には出さない。
          // カテゴリ別に固定文言に落とす（詳細はデバッグビルドの Logger.e で別途確認可能）。
          com.example.wanoterm.util.Logger.e("TerminalScreen", "connect failed", t)
          state = TabScreenState.Error(sanitizedConnectError(t))
        }
      }
      else -> state = TabScreenState.Error("unknown tab type")
    }
  }

  val sortedTabs by remember { derivedStateOf { activeTabs.toList().sorted() } }
  val initialPage by remember { derivedStateOf { sortedTabs.indexOf(tabId).coerceAtLeast(0) } }

  val pagerState = rememberPagerState(initialPage = initialPage) { sortedTabs.size }
  val coroutineScope = rememberCoroutineScope()

  LaunchedEffect(tabId, sortedTabs) {
    val idx = sortedTabs.indexOf(tabId)
    if (idx >= 0 && idx != pagerState.currentPage) {
      pagerState.scrollToPage(idx)
    }
  }

  val currentTabId by remember(pagerState, sortedTabs) {
    derivedStateOf { sortedTabs.getOrNull(pagerState.currentPage) ?: tabId }
  }

  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage }.collect { page ->
      val newTabId = sortedTabs.getOrNull(page) ?: return@collect
      val bundle = app.sessionManager.get(newTabId)
      if (bundle != null) state = TabScreenState.Ready(bundle)
    }
  }

  // 現在のタブの接続状態を dot で表示するため、StateFlow を collect
  val currentBundle = app.sessionManager.get(currentTabId)
  val currentConnectionState by
      (currentBundle?.controller?.connectionState ?: remember { kotlinx.coroutines.flow.MutableStateFlow(ConnectionState.Idle) })
          .collectAsStateWithLifecycle(initialValue = ConnectionState.Idle)

  val context = LocalContext.current
  val composeView = LocalView.current

  Scaffold(
      topBar = {
        TopAppBar(
            title = {
              Row(verticalAlignment = Alignment.CenterVertically) {
                ConnectionDot(currentConnectionState)
                Spacer(Modifier.width(8.dp))
                Text(
                    labelFor(currentTabId),
                    style = MaterialTheme.typography.titleMedium,
                )
              }
            },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
            actions = {
              IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Filled.History, contentDescription = "履歴")
              }
              IconButton(onClick = { showTmux = true }) {
                Icon(Icons.Filled.Dashboard, contentDescription = "tmux")
              }
              IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "ヘルプ")
              }
            },
            colors = TopAppBarDefaults.topAppBarColors(),
        )
      }
  ) { inner ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(inner)
                .imePadding()
                .navigationBarsPadding(),
    ) {
      if (sortedTabs.size > 1) {
        TabBar(
            tabs = sortedTabs,
            activeTabId = currentTabId,
            tabTitle = ::labelFor,
            onSelect = { id ->
              val idx = sortedTabs.indexOf(id)
              if (idx >= 0) coroutineScope.launch { pagerState.animateScrollToPage(idx) }
            },
            onClose = { app.sessionManager.closeTab(it) },
            modifier = Modifier.height(36.dp),
        )
      }
      when (val s = state) {
        is TabScreenState.Loading, is TabScreenState.Connecting ->
            Box(modifier = Modifier.weight(1f).fillMaxSize()) { ConnectingIndicator() }
        is TabScreenState.Error ->
            Box(modifier = Modifier.weight(1f).fillMaxSize()) { ErrorMessage(s.message) }
        is TabScreenState.Ready -> {
          val terminalViews = remember { mutableStateMapOf<String, TerminalView>() }
          LaunchedEffect(sortedTabs) {
            terminalViews.keys.toList().forEach { k ->
              if (k !in sortedTabs) terminalViews.remove(k)
            }
          }
          val currentView: TerminalView? = terminalViews[currentTabId]
          var ctrlArmed by remember { mutableStateOf(false) }
          LaunchedEffect(currentTabId, currentView) {
            ctrlArmed = false
            currentView?.ctrlArmed = false
            currentView?.focusAndRequestKeyboard()
          }
          val sendBytes: (ByteArray) -> Unit = { bytes ->
            currentView?.scrollToBottom()
            app.sessionManager.get(currentTabId)?.controller?.sendToRemote(bytes)
          }
          if (sortedTabs.size <= 1) {
            TerminalHost(
                controller = s.bundle.controller,
                palette = theme.toPalette(),
                fontSizeSp = fontSizeSp,
                lineEnding = lineEnding,
                modifier = Modifier.weight(1f).fillMaxSize(),
                viewBinding = { v -> terminalViews[currentTabId] = v },
            )
          } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxSize(),
                key = { page -> sortedTabs.getOrNull(page) ?: page },
            ) { page ->
              val pageTabId = sortedTabs.getOrNull(page)
              val pageBundle = pageTabId?.let { app.sessionManager.get(it) }
              if (pageTabId != null && pageBundle != null) {
                TerminalHost(
                    controller = pageBundle.controller,
                    palette = theme.toPalette(),
                    fontSizeSp = fontSizeSp,
                    lineEnding = lineEnding,
                    modifier = Modifier.fillMaxSize(),
                    viewBinding = { v -> terminalViews[pageTabId] = v },
                )
              } else {
                Box(modifier = Modifier.fillMaxSize()) { ConnectingIndicator() }
              }
            }
          }

          // ショートカットバーは開閉可能。閉じておくと端末の表示領域が増える。
          AnimatedVisibility(
              visible = showShortcutBar,
              enter = expandVertically(),
              exit = shrinkVertically(),
          ) {
            CustomShortcutBar(
                shortcuts = customShortcuts,
                lineEnding = lineEnding,
                onSend = sendBytes,
            )
          }
          KeyboardToolbar(
              ctrlArmed = ctrlArmed,
              shortcutBarVisible = showShortcutBar,
              onToggleCtrl = {
                ctrlArmed = !ctrlArmed
                currentView?.ctrlArmed = ctrlArmed
              },
              onToggleShortcutBar = { showShortcutBar = !showShortcutBar },
              onHideKeyboard = {
                val imm = context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                imm?.hideSoftInputFromWindow(composeView.windowToken, 0)
              },
              onSend = sendBytes,
          )
        }
      }
    }

    if (showHelp) HelpSheet(onDismiss = { showHelp = false })
    val readyBundle = app.sessionManager.get(currentTabId)
    if (showHistory && readyBundle != null) {
      val history by readyBundle.controller.commandHistory.history.collectAsStateWithLifecycle()
      HistorySheet(
          history = history,
          onInsert = { cmd ->
            val bytes = (cmd + "\r").toByteArray(Charsets.UTF_8)
            readyBundle.controller.sendToRemote(bytes)
          },
          onDismiss = { showHistory = false },
      )
    }
    if (showTmux && readyBundle != null) {
      TmuxPanel(
          onSend = { bytes -> readyBundle.controller.sendToRemote(bytes) },
          onDismiss = { showTmux = false },
      )
    }
  }
}

@Composable
private fun ConnectionDot(state: ConnectionState) {
  val color =
      when (state) {
        ConnectionState.Connected -> Color(0xFF4CAF50) // green
        ConnectionState.Connecting -> Color(0xFFFFC107) // amber
        ConnectionState.Disconnected, ConnectionState.Failed -> Color(0xFFE53935) // red
        ConnectionState.Idle -> Color(0xFF9E9E9E) // grey
      }
  Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(color))
}

@Composable
private fun ConnectingIndicator() {
  Column(
      modifier = Modifier.fillMaxSize(),
      verticalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    CircularProgressIndicator()
    Text(stringResource(R.string.conn_connecting))
  }
}

@Composable
private fun ErrorMessage(message: String) {
  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) { Text(text = stringResource(R.string.conn_failed, message)) }
}

/**
 * 例外 → UI 用の無害な説明文にマップ。認証失敗時に username/host を表示しないのが要点。
 * 詳細デバッグは Logger で別途出力済み。
 */
private fun sanitizedConnectError(t: Throwable): String {
  val m = t.message.orEmpty().lowercase()
  return when {
    "authentication" in m || "auth fail" in m || "permission denied" in m -> "認証に失敗しました"
    "unknownhost" in m || "no route" in m || "connect" in m && "refused" in m -> "サーバに接続できません"
    "timeout" in m || "timed out" in m -> "接続がタイムアウトしました"
    "hostkey" in m || "host key" in m -> "ホスト鍵が一致しません（TOFU）"
    "secret" in m -> "認証情報が見つかりません"
    else -> "接続に失敗しました"
  }
}
