package app.anoterm.ui.terminal

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
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.NoteAdd
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anoterm.BuildConfig
import app.anoterm.R
import app.anoterm.AnotermApp
import app.anoterm.data.prefs.AppPrefs
import app.anoterm.ssh.LoopbackChannel
import app.anoterm.ssh.SessionBundle
import app.anoterm.ssh.SshChannel
import app.anoterm.terminal.ConnectionState
import app.anoterm.terminal.TerminalSessionController
import app.anoterm.terminal.compose.TerminalHost
import app.anoterm.terminal.view.TerminalView
import app.anoterm.util.Logger
import kotlinx.coroutines.launch

sealed interface TabScreenState {
  data object Loading : TabScreenState

  data object Connecting : TabScreenState

  data class Ready(val bundle: SessionBundle) : TabScreenState

  data class Error(
      val message: String,
      val canRetry: Boolean = false,
      val hostId: Long? = null,
  ) : TabScreenState
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun TerminalScreen(
    tabId: String,
    onBack: () -> Unit,
    onEditHost: (Long) -> Unit = {},
    onOpenKnownHosts: () -> Unit = {},
) {
  val app = remember { AnotermApp.get() }
  val theme by app.prefs.theme.collectAsStateWithLifecycle()
  val fontSizeSp by app.prefs.fontSizeSp.collectAsStateWithLifecycle()
  val lineEnding by app.prefs.lineEnding.collectAsStateWithLifecycle()
  val activeTabs by app.sessionManager.activeTabs.collectAsStateWithLifecycle()
  val customShortcuts by app.prefs.customShortcuts.collectAsStateWithLifecycle()
  val isPro by app.prefs.isPro.collectAsStateWithLifecycle()
  val developerMode by app.prefs.developerMode.collectAsStateWithLifecycle()
  val terminalClipboardHistoryEnabled by
      app.prefs.terminalClipboardHistoryEnabled.collectAsStateWithLifecycle()

  var state: TabScreenState by remember { mutableStateOf(TabScreenState.Loading) }
  var showHelp by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var showTmux by remember { mutableStateOf(false) }
  var showMemo by remember { mutableStateOf(false) }
  var showDisconnectConfirm by remember { mutableStateOf(false) }
  var retryNonce by remember { mutableStateOf(0) }
  // カスタムショートカットバーはデフォルトで非表示。下部のツールバー右端の apps アイコンで切替。
  var showShortcutBar by remember { mutableStateOf(false) }

  // 端末ラベル resolver。tabId → 表示名を DB から引いてキャッシュ。
  // リモートが OSC 2 で送ってきたタイトルがあればそれを優先表示。
  // 2 つの LaunchedEffect に分けると activeTabs 変化で片方だけ発火した瞬間の race が
  // 発生するため、1 本にまとめて「DB label の解決」と「remoteTitle の collect 購読」を
  // 同時に開始する。
  val tabLabels = remember { mutableStateMapOf<String, String>() }
  val remoteTitles = remember { mutableStateMapOf<String, String?>() }
  LaunchedEffect(activeTabs, tabId) {
    // 閉じたタブのキャッシュを掃除。放置するとラベル/タイトルの map が session 寿命を超えて
    // 肥大化し、同じ tabId が再利用されたときに古い名前を見せてしまう事故もありうる。
    val alive = activeTabs.toSet() + tabId
    (tabLabels.keys - alive).forEach { tabLabels.remove(it) }
    (remoteTitles.keys - alive).forEach { remoteTitles.remove(it) }

    for (t in alive) {
      if (!tabLabels.containsKey(t)) {
        tabLabels[t] =
            when {
              t.startsWith("loopback") -> "Local echo"
              t.startsWith("host:") -> {
                val hostId = hostIdFromTabId(t)
                val host = hostId?.let { app.database.hostDao().findById(it) }
                val base = host?.label ?: t.removePrefix("host:").substringBefore(":")
                // tmux 統合ホストは label に badge を付けて視覚的に区別する。
                if (host?.useTmux == true) "$base · tmux:${host.tmuxSession.ifBlank { "anoterm" }}"
                else base
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

  LaunchedEffect(tabId, retryNonce) {
    val existing = app.sessionManager.get(tabId)
    if (existing != null) {
      state = TabScreenState.Ready(existing)
      return@LaunchedEffect
    }
    // Free tier のタブ上限チェック（既存タブの再利用ではない = 新規作成時のみ）
    val currentCount = app.sessionManager.activeTabIds().size
    if (!isPro && currentCount >= AppPrefs.FREE_TIER_TAB_LIMIT) {
      state = TabScreenState.Error("Free 版は同時 ${AppPrefs.FREE_TIER_TAB_LIMIT} タブまで。設定から Pro にアップグレードしてください。")
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
        val hostId = hostIdFromTabId(tabId)
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
          state = TabScreenState.Error("secret missing", hostId = hostId)
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
          // 接続成功直後の自動投入コマンド。複数の機能を 1 行にまとめて送る。
          // - claudeCodeFullscreen ON: `export CLAUDE_CODE_NO_FLICKER=1` を先に流す。
          //   Claude Code の non-fullscreen モードで生じる「同じ応答が scrollback に
          //   3〜4 重複する」現象を防ぐ。env が未参照のシェル/コマンドには無害。
          // - claudeCodeFullscreen ON + useTmux ON: 既存 tmux session の env にも
          //   `tmux set-environment -t <session>` で書き込む。これがないと PC 等で
          //   既に立てていた tmux session に AnoTerm から attach した場合、
          //   tmux の session env に変数が無いため新しい claude プロセスが env を継承できない。
          //   `|| true` で session 不在時の失敗を握りつぶす (この場合は次の `tmux new -A`
          //   が新規作成し、login shell の env を継承するので問題なし)。
          // - useTmux ON: 続けて `tmux new -A -s <session>` で attach (-A = なければ作成)。
          // この分岐は「既存 bundle がなくて新規 SSH を張った場合」にしか来ないので
          // 2 重送信にはならない（前段の sessionManager.get(tabId) != null で早期 return 済み）。
          val claudeCodeFullscreen = app.prefs.claudeCodeFullscreen.value
          val parts = buildList {
            if (claudeCodeFullscreen) add("export CLAUDE_CODE_NO_FLICKER=1")
            if (claudeCodeFullscreen && params.useTmux) {
              add(
                  "tmux set-environment -t ${params.tmuxSession} CLAUDE_CODE_NO_FLICKER 1 2>/dev/null || true",
              )
            }
            if (params.useTmux) add("tmux new -A -s ${params.tmuxSession}")
          }
          if (parts.isNotEmpty()) {
            val cmd = parts.joinToString("; ") + "\r"
            controller.sendToRemote(cmd.toByteArray(Charsets.US_ASCII))
          }
        } catch (t: Throwable) {
          controller.setConnectionState(ConnectionState.Failed)
          controller.dispose()
          // 例外 message はサーバ名/ユーザ名/鍵パス等を含みうるため UI には出さない。
          // カテゴリ別に固定文言に落とす（詳細はデバッグビルドの Logger.e で別途確認可能）。
          Logger.e("TerminalScreen", "connect failed", t)
          state = TabScreenState.Error(sanitizedConnectError(t), canRetry = true, hostId = hostId)
        }
      }
      else -> state = TabScreenState.Error("unknown tab type")
    }
  }

  val sortedTabs by remember { derivedStateOf { activeTabs.toList().sorted() } }
  val initialPage by remember { derivedStateOf { sortedTabs.indexOf(tabId).coerceAtLeast(0) } }

  val pagerState = rememberPagerState(initialPage = initialPage) { sortedTabs.size }
  val coroutineScope = rememberCoroutineScope()

  // 「表示中の tabId」を真の状態として保持し、pagerState.currentPage はそれに追従させる。
  // pagerState は Int 番号でしか page を持てないため、左側のタブを閉じると同じ番号の page が
  // 別の tab を指す事故が起きる。tabId ベースで同期を取ることでこのズレを防ぐ。
  val displayedTabId = remember { mutableStateOf<String?>(null) }

  // 1. 初回マウント / Navigation で別エントリに来た時: tabId に displayedTabId を寄せる。
  //    sortedTabs にまだ tabId が現れていなければ load を待つ。
  LaunchedEffect(tabId, sortedTabs) {
    if (tabId in sortedTabs && displayedTabId.value != tabId) {
      displayedTabId.value = tabId
    }
  }

  // 2. pager の currentPage または sortedTabs が変化したとき、現在 page にある tab を
  //    displayedTabId に反映し、その bundle を state に流し込む。
  //    - ユーザーが pager を swipe したとき
  //    - 表示中タブが close されて pager が次の tab を映すとき
  //    の両方で発火する。
  LaunchedEffect(pagerState, sortedTabs) {
    snapshotFlow { sortedTabs.getOrNull(pagerState.currentPage) }
        .collect { tab ->
          if (tab == null) return@collect
          if (tab != displayedTabId.value) displayedTabId.value = tab
          val bundle = app.sessionManager.get(tab)
          if (bundle != null) state = TabScreenState.Ready(bundle)
        }
  }

  // 3. displayedTabId に pager を追従させる。
  //    - ユーザーが Navigation 経由で別 tab に来た直後 (Effect 1 が displayedTabId を更新)
  //    - 左側の tab を close して displayedTabId の new index がズレた時
  //    のいずれでも、scrollToPage で同じ tab を映し続ける。
  LaunchedEffect(displayedTabId.value, sortedTabs) {
    val target = displayedTabId.value ?: return@LaunchedEffect
    val idx = sortedTabs.indexOf(target)
    if (idx >= 0 && idx != pagerState.currentPage) {
      pagerState.scrollToPage(idx)
    }
  }

  val currentTabId by remember(pagerState, sortedTabs) {
    derivedStateOf { sortedTabs.getOrNull(pagerState.currentPage) ?: tabId }
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
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
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
              if (BuildConfig.DEBUG && developerMode) {
                IconButton(onClick = { showTmux = true }) {
                  Icon(Icons.Filled.Dashboard, contentDescription = "tmux")
                }
              }
              IconButton(onClick = { showMemo = true }) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = "メモ")
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
    // adjustNothing + edge-to-edge 構成。imePadding は「ツールバーだけ」に付け、
    // ターミナル領域は IME による window resize の影響を受けないようにする。
    // これで tmux / Claude Code に SIGWINCH が連打されず、キーボード開閉時の
    // 「画面が縦に圧縮・復元される」ちらつきが消える。
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(inner)
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
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
              ErrorMessage(
                  message = s.message,
                  canRetry = s.canRetry,
                  hostId = s.hostId,
                  onRetry = { retryNonce++ },
                  onEditHost = onEditHost,
                  onOpenKnownHosts = onOpenKnownHosts,
              )
            }
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
          // IME 可視状態を WindowInsets の ime bottom で判定。
          val imeBottomPx =
              WindowInsets.ime.getBottom(androidx.compose.ui.platform.LocalDensity.current)
          val imeVisible = imeBottomPx > 0
          // Termius 風のレイアウト: ターミナルが weight(1f) で残余を占め、ツールバーは
          // そのすぐ下に Column の子として並ぶ。ツールバーに `imePadding` を付けると
          // IME 表示時にその下に IME 高さ分の余白が入り、結果としてターミナルが
          // その分だけ縮む → リモートシェルの入力行（cursor 行）がツールバーの直上、
          // つまり IME の上に見える。ターミナルが縮むので SIGWINCH は飛ぶが、
          // adjustNothing + Compose 内のレイアウト変化だけなので一度で settle する。
          if (sortedTabs.size <= 1) {
            TerminalHost(
                controller = s.bundle.controller,
                palette = theme.toPalette(),
                fontSizeSp = fontSizeSp,
                lineEnding = lineEnding,
                relaxedImePrivacyForClipboard = terminalClipboardHistoryEnabled,
                modifier = Modifier.weight(1f).fillMaxWidth(),
                viewBinding = { v -> terminalViews[currentTabId] = v },
            )
          } else {
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.weight(1f).fillMaxWidth(),
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
                    relaxedImePrivacyForClipboard = terminalClipboardHistoryEnabled,
                    modifier = Modifier.fillMaxSize(),
                    viewBinding = { v -> terminalViews[pageTabId] = v },
                )
              } else {
                Box(modifier = Modifier.fillMaxSize()) { ConnectingIndicator() }
              }
            }
          }
          // ツールバー + ショートカットバーはターミナル直下に固定し、IME が出れば
          // その上に押し上げる。`imePadding()` はアニメ補間値を読むので毎フレーム
          // 再レイアウトが走り terminal 側の SIGWINCH も連発される。代わりに
          // `imeAnimationTarget` (最終値) を windowInsetsPadding で当てることで、
          // 開閉開始時点で position を snap させる — Termius がカクカク見えない理由。
          Column(
              modifier =
                  Modifier.fillMaxWidth()
                      .background(MaterialTheme.colorScheme.surface)
                      .windowInsetsPadding(WindowInsets.imeAnimationTarget),
          ) {
            // AnimatedVisibility だと expand/shrink 中に毎フレーム terminal が縮み、
            // PTY resize → feed/draw が連発して「ショートカット展開がカクつく」。
            // snap 表示にすれば layout は 1 回で決まる。
            if (showShortcutBar) {
              CustomShortcutBar(
                  shortcuts = customShortcuts,
                  lineEnding = lineEnding,
                  onSend = sendBytes,
              )
            }
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
                      context.getSystemService(Context.INPUT_METHOD_SERVICE)
                          as? InputMethodManager
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
    if (showMemo) {
      MemoSheet(
          contextLabel = labelFor(currentTabId),
          onDismiss = { showMemo = false },
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
private fun ErrorMessage(
    message: String,
    canRetry: Boolean,
    hostId: Long?,
    onRetry: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenKnownHosts: () -> Unit,
) {
  Column(
      modifier = Modifier.fillMaxSize().padding(24.dp),
      verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
      horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Text(
        text = stringResource(R.string.conn_failed, message),
        textAlign = TextAlign.Center,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (canRetry) {
        TextButton(onClick = onRetry) { Text("再試行") }
      }
      if (hostId != null) {
        TextButton(onClick = { onEditHost(hostId) }) { Text("ホスト編集") }
      }
      if ("ホスト鍵" in message || "TOFU" in message) {
        TextButton(onClick = onOpenKnownHosts) { Text("信頼済みホスト") }
      }
    }
  }
}

private fun hostIdFromTabId(tabId: String): Long? =
    tabId.removePrefix("host:").substringBefore(":").toLongOrNull()

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
