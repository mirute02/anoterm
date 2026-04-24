package com.example.wanoterm.ui.terminal

import android.content.Context
import android.view.HapticFeedbackConstants
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
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
import androidx.compose.material.icons.filled.BugReport
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.HelpOutline
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
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
  val isPro by app.prefs.isPro.collectAsStateWithLifecycle()
  val developerMode by app.prefs.developerMode.collectAsStateWithLifecycle()

  var state: TabScreenState by remember { mutableStateOf(TabScreenState.Loading) }
  var showHelp by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var showTmux by remember { mutableStateOf(false) }
  var showDebug by remember { mutableStateOf(false) }
  var showDisconnectConfirm by remember { mutableStateOf(false) }
  // カスタムショートカットバーはデフォルトで非表示。下部のツールバー右端の apps アイコンで切替。
  var showShortcutBar by remember { mutableStateOf(false) }

  // 端末ラベル resolver。tabId → 表示名を DB から引いてキャッシュ。
  // リモートが OSC 2 で送ってきたタイトルがあればそれを優先表示。
  // 2 つの LaunchedEffect に分けると activeTabs 変化で片方だけ発火した瞬間の race が
  // 発生するため、1 本にまとめて「DB label の解決」と「remoteTitle の collect 購読」を
  // 同時に開始する。
  val tabLabels = remember { mutableStateMapOf<String, String>() }
  val remoteTitles = remember { mutableStateMapOf<String, String?>() }
  LaunchedEffect(activeTabs) {
    // 閉じたタブのキャッシュを掃除。放置するとラベル/タイトルの map が session 寿命を超えて
    // 肥大化し、同じ tabId が再利用されたときに古い名前を見せてしまう事故もありうる。
    val alive = activeTabs.toSet()
    (tabLabels.keys - alive).forEach { tabLabels.remove(it) }
    (remoteTitles.keys - alive).forEach { remoteTitles.remove(it) }

    for (t in activeTabs) {
      if (!tabLabels.containsKey(t)) {
        tabLabels[t] =
            when {
              t.startsWith("loopback") -> "Local echo"
              t.startsWith("host:") -> {
                val hostId = t.removePrefix("host:").substringBefore(":").toLongOrNull()
                val host = hostId?.let { app.database.hostDao().findById(it) }
                val base = host?.label ?: t.removePrefix("host:").substringBefore(":")
                // tmux 統合ホストは label に badge を付けて視覚的に区別する。
                if (host?.useTmux == true) "$base · tmux" else base
              }
              else -> t
            }
      }
      val b = app.sessionManager.get(t)
      if (b != null) {
        launch {
          b.controller.remoteTitle.collect { title ->
            remoteTitles[t] = title?.takeIf { it.isNotBlank() }
          }
        }
      }
    }
  }
  fun labelFor(id: String): String = remoteTitles[id] ?: tabLabels[id] ?: id.substringBefore(":")

  // 開いたタブ id を永続化。プロセス kill 後の再起動時に Navigation が参照する。
  LaunchedEffect(tabId) { app.prefs.setLastTabId(tabId) }

  LaunchedEffect(tabId) {
    val existing = app.sessionManager.get(tabId)
    if (existing != null) {
      state = TabScreenState.Ready(existing)
      return@LaunchedEffect
    }
    // Free tier のタブ上限チェック（既存タブの再利用ではない = 新規作成時のみ）
    val currentCount = app.sessionManager.activeTabIds().size
    if (!isPro && currentCount >= com.example.wanoterm.data.prefs.AppPrefs.FREE_TIER_TAB_LIMIT) {
      state = TabScreenState.Error("Free 版は同時 ${com.example.wanoterm.data.prefs.AppPrefs.FREE_TIER_TAB_LIMIT} タブまで。設定から Pro にアップグレードしてください。")
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
          // tmux 統合: 接続成功直後に `tmux new -A -s <session>\r` を送出。
          // -A: セッションがなければ作成、あれば attach（再接続で同じセッションに復帰）
          // この分岐は「既存 bundle がなくて新規 SSH を張った場合」にしか来ないので
          // 2 重送信にはならない（前段の sessionManager.get(tabId) != null で早期 return 済み）。
          if (params.useTmux) {
            val cmd = "tmux new -A -s ${params.tmuxSession}\r"
            controller.sendToRemote(cmd.toByteArray(Charsets.US_ASCII))
          }
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
              // tmux ダッシュボードは Phase 2 以降の機能 (control mode 未完成)。
              // 一般ユーザには未使用機能が紛れて見えるのでノイズ、debug + 開発者モード時のみ出す。
              if (com.example.wanoterm.BuildConfig.DEBUG && developerMode) {
                IconButton(onClick = { showTmux = true }) {
                  Icon(Icons.Filled.Dashboard, contentDescription = "tmux")
                }
              }
              IconButton(onClick = { showDebug = true }) {
                Icon(Icons.Filled.BugReport, contentDescription = "デバッグ報告")
              }
              IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Filled.HelpOutline, contentDescription = "ヘルプ")
              }
              IconButton(onClick = { showDisconnectConfirm = true }) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = "切断",
                    tint = MaterialTheme.colorScheme.error,
                )
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
      // タブ数の変化（1→2 や 2→1）で TabBar が「ヒュッと出て消える」flicker に見えないよう、
      // AnimatedVisibility で高さと不透明度を滑らかに変える。
      // 表示自体は「2 タブ以上」の従来条件のまま。
      androidx.compose.animation.AnimatedVisibility(
          visible = sortedTabs.size > 1,
          enter =
              androidx.compose.animation.expandVertically() +
                  androidx.compose.animation.fadeIn(),
          exit =
              androidx.compose.animation.shrinkVertically() +
                  androidx.compose.animation.fadeOut(),
      ) {
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
          // リモートが BEL (0x07) を送ってきたら短い触覚フィードバック。
          // 音は鳴らさない方針（夜間 SSH 作業で迷惑なので）。視覚フラッシュも今はなし。
          val hapticView = LocalView.current
          LaunchedEffect(s.bundle.controller) {
            s.bundle.controller.bell.collect {
              hapticView.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            }
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
          // IME 可視状態を WindowInsets の ime bottom で判定。0 より大きければ出ている。
          // isImeVisible ext prop は Compose 1.5+ なので、こちらの書き方で互換性確保。
          val imeBottomPx =
              WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
          val imeVisible = imeBottomPx > 0
          KeyboardToolbar(
              ctrlArmed = ctrlArmed,
              shortcutBarVisible = showShortcutBar,
              keyboardVisible = imeVisible,
              onToggleCtrl = {
                ctrlArmed = !ctrlArmed
                currentView?.ctrlArmed = ctrlArmed
              },
              onToggleShortcutBar = { showShortcutBar = !showShortcutBar },
              onToggleKeyboard = {
                val imm =
                    context.getSystemService(Context.INPUT_METHOD_SERVICE) as? InputMethodManager
                if (imeVisible) {
                  imm?.hideSoftInputFromWindow(composeView.windowToken, 0)
                } else {
                  // TerminalView に focus させてから soft input を要求する。
                  // focus が取れないと IME は開かないので requestFocus を先に。
                  currentView?.let { v ->
                    v.requestFocus()
                    imm?.showSoftInput(v, InputMethodManager.SHOW_IMPLICIT)
                  }
                }
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
    if (showDebug) {
      DebugReportSheet(
          contextLabel = labelFor(currentTabId),
          onDismiss = { showDebug = false },
      )
    }
    if (showDisconnectConfirm) {
      AlertDialog(
          onDismissRequest = { showDisconnectConfirm = false },
          title = { Text("切断しますか?") },
          text = {
            Text(
                "このタブの SSH 接続を終了し、ホスト一覧に戻ります。"
                    + "tmux 統合が ON のホストではリモート側のセッションは残るので、"
                    + "次回接続時に続きから再開できます。",
            )
          },
          confirmButton = {
            TextButton(
                onClick = {
                  showDisconnectConfirm = false
                  app.sessionManager.closeTab(currentTabId)
                  // すべてのタブを閉じた場合 onBack を呼んでホスト一覧へ戻す。
                  if (app.sessionManager.activeTabIds().isEmpty()) onBack()
                },
            ) {
              Text("切断", color = MaterialTheme.colorScheme.error)
            }
          },
          dismissButton = {
            TextButton(onClick = { showDisconnectConfirm = false }) { Text("キャンセル") }
          },
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
