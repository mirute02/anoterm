package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
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
          state = TabScreenState.Error(t.message ?: "connection failed")
        }
      }
      else -> state = TabScreenState.Error("unknown tab type")
    }
  }

  // 並び順は登録順（tabId に付与したタイムスタンプで自然に古い順）。
  val sortedTabs by remember { derivedStateOf { activeTabs.toList().sorted() } }
  val initialPage by remember {
    derivedStateOf { sortedTabs.indexOf(tabId).coerceAtLeast(0) }
  }

  val pagerState = rememberPagerState(initialPage = initialPage) { sortedTabs.size }
  val coroutineScope = rememberCoroutineScope()

  // 新しいタブが登録された or 現在のタブが変わったら、pager を該当位置へスクロール。
  LaunchedEffect(tabId, sortedTabs) {
    val idx = sortedTabs.indexOf(tabId)
    if (idx >= 0 && idx != pagerState.currentPage) {
      pagerState.scrollToPage(idx)
    }
  }

  val currentTabId by remember(pagerState, sortedTabs) {
    derivedStateOf {
      sortedTabs.getOrNull(pagerState.currentPage) ?: tabId
    }
  }

  // pager でスライドして現在タブが変わったら、それに紐付く bundle に state を更新
  LaunchedEffect(pagerState) {
    snapshotFlow { pagerState.currentPage }.collect { page ->
      val newTabId = sortedTabs.getOrNull(page) ?: return@collect
      val bundle = app.sessionManager.get(newTabId)
      if (bundle != null) {
        state = TabScreenState.Ready(bundle)
      }
    }
  }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(titleFor(currentTabId), style = MaterialTheme.typography.titleMedium) },
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
          // TerminalView は tabId ごとに map で追跡する。3 タブ以上でページ切替時に
          // recompose 順序によって単一変数だと stale 参照を掴むケースがあったため、
          // ID 引きで確実に「いま見えているページのビュー」を取り出せる形にした。
          val terminalViews = remember { mutableStateMapOf<String, TerminalView>() }
          // 閉じられたタブの entry を掃除する（参照を残したままにすると view 経由で
          // コントローラを掴み続けてしまうので）。
          LaunchedEffect(sortedTabs) {
            terminalViews.keys.toList().forEach { k ->
              if (k !in sortedTabs) terminalViews.remove(k)
            }
          }
          val currentView: TerminalView? = terminalViews[currentTabId]
          var ctrlArmed by remember { mutableStateOf(false) }
          // タブ追加・削除・切替のどの経路でも新 TerminalView に focus が乗るよう保証する。
          // 同時に ctrl armed はタブ毎にリセット（あるタブで Ctrl を armed のまま別タブへ
          // 切替えて打った文字が Ctrl+X 扱いされる事故を防ぐ）。
          LaunchedEffect(currentTabId, currentView) {
            ctrlArmed = false
            currentView?.ctrlArmed = false
            currentView?.focusAndRequestKeyboard()
          }
          // 1 タブのみなら Pager を使わず単独描画（Pager の overhead 回避）。
          // 2 タブ以上で HorizontalPager でスワイプ切替。
          if (sortedTabs.size <= 1) {
            TerminalHost(
                controller = s.bundle.controller,
                palette = theme.toPalette(),
                fontSizeSp = fontSizeSp,
                lineEnding = lineEnding,
                modifier = Modifier.weight(1f).fillMaxSize(),
                viewBinding = { v -> terminalViews[currentTabId] = v },
            )
            CustomShortcutBar(
                shortcuts = customShortcuts,
                lineEnding = lineEnding,
                onSend = { bytes ->
                  currentView?.scrollToBottom()
                  s.bundle.controller.sendToRemote(bytes)
                },
            )
            KeyboardToolbar(
                ctrlArmed = ctrlArmed,
                onToggleCtrl = {
                  ctrlArmed = !ctrlArmed
                  currentView?.ctrlArmed = ctrlArmed
                },
                onSend = { bytes ->
                  currentView?.scrollToBottom()
                  s.bundle.controller.sendToRemote(bytes)
                },
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
                    // ページごとに自分の ID で map に登録。recompose 順序に依存しないので、
                    // 3 タブ以上でもあとから currentTabId で引けば正しい view が手に入る。
                    viewBinding = { v -> terminalViews[pageTabId] = v },
                )
              } else {
                Box(modifier = Modifier.fillMaxSize()) { ConnectingIndicator() }
              }
            }
            CustomShortcutBar(
                shortcuts = customShortcuts,
                lineEnding = lineEnding,
                onSend = { bytes ->
                  currentView?.scrollToBottom()
                  app.sessionManager.get(currentTabId)?.controller?.sendToRemote(bytes)
                },
            )
            KeyboardToolbar(
                ctrlArmed = ctrlArmed,
                onToggleCtrl = {
                  ctrlArmed = !ctrlArmed
                  currentView?.ctrlArmed = ctrlArmed
                },
                onSend = { bytes ->
                  currentView?.scrollToBottom()
                  app.sessionManager.get(currentTabId)?.controller?.sendToRemote(bytes)
                },
            )
          }
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

private fun titleFor(tabId: String): String =
    when {
      tabId.startsWith("loopback") -> "Local echo"
      tabId.startsWith("host:") -> tabId.removePrefix("host:").substringBefore(":")
      else -> tabId
    }
