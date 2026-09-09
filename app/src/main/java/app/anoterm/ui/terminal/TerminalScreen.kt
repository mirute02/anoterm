package app.anoterm.ui.terminal

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
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material.icons.filled.VpnKey
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
import androidx.annotation.StringRes
import app.anoterm.AnotermApp
import app.anoterm.ssh.LoopbackChannel
import app.anoterm.ssh.ReconnectSpec
import app.anoterm.ssh.SessionBundle
import app.anoterm.ssh.SshChannel
import app.anoterm.ssh.TmuxController
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
      val failure: ConnectFailure,
      val canRetry: Boolean = false,
      val hostId: Long? = null,
  ) : TabScreenState
}

/**
 * 接続できなかった理由。
 *
 * 以前は表示用の日本語をそのまま持ち回り、「ホスト鍵」という文字列が含まれるかで
 * 既知ホスト画面へのボタンを出すか決めていた。翻訳した時点で一致しなくなる。
 * 分岐に使う値と、人に見せる文字列は別物にしておく。
 */
enum class ConnectFailure(@StringRes val message: Int) {
  AUTH(R.string.conn_fail_auth),
  UNREACHABLE(R.string.conn_fail_unreachable),
  TIMEOUT(R.string.conn_fail_timeout),
  HOST_KEY(R.string.conn_fail_host_key),
  SECRET_MISSING(R.string.conn_fail_secret_missing),
  HOST_NOT_FOUND(R.string.conn_fail_host_not_found),
  INVALID_TAB(R.string.conn_fail_invalid_tab),
  UNKNOWN(R.string.conn_fail_unknown),
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
  val context = LocalContext.current
  val theme by app.prefs.theme.collectAsStateWithLifecycle()
  val fontSizeSp by app.prefs.fontSizeSp.collectAsStateWithLifecycle()
  val lineEnding by app.prefs.lineEnding.collectAsStateWithLifecycle()
  val activeTabs by app.sessionManager.activeTabs.collectAsStateWithLifecycle()
  val customShortcuts by app.prefs.customShortcuts.collectAsStateWithLifecycle()
  val developerMode by app.prefs.developerMode.collectAsStateWithLifecycle()
  val terminalClipboardHistoryEnabled by
      app.prefs.terminalClipboardHistoryEnabled.collectAsStateWithLifecycle()

  var state: TabScreenState by remember { mutableStateOf(TabScreenState.Loading) }
  var showHelp by remember { mutableStateOf(false) }
  var showHistory by remember { mutableStateOf(false) }
  var showTmux by remember { mutableStateOf(false) }
  var showTmuxTree by remember { mutableStateOf(false) }
  var tmuxTree by remember { mutableStateOf<List<TmuxTreeConnection>?>(null) }
  var showInstallKey by remember { mutableStateOf(false) }
  // TOFU で初めて記録したホスト鍵。何を信頼したのかを利用者に見せるため。
  var firstSeenKey by remember { mutableStateOf<Pair<String, String>?>(null) }
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
  // tmux 統合が有効なタブのセッション名。バーはこれを手掛かりに「いま見ている
  // セッション」を決める（クライアントが複数繋がっていると自動判別できないため）。
  val tabTmuxSessions = remember { mutableStateMapOf<String, String>() }
  LaunchedEffect(activeTabs, tabId) {
    // 閉じたタブのキャッシュを掃除。放置するとラベル/タイトルの map が session 寿命を超えて
    // 肥大化し、同じ tabId が再利用されたときに古い名前を見せてしまう事故もありうる。
    val alive = activeTabs.toSet() + tabId
    (tabLabels.keys - alive).forEach { tabLabels.remove(it) }
    (remoteTitles.keys - alive).forEach { remoteTitles.remove(it) }
    (tabTmuxSessions.keys - alive).forEach { tabTmuxSessions.remove(it) }

    for (t in alive) {
      if (!tabLabels.containsKey(t)) {
        tabLabels[t] =
            when {
              t.startsWith("loopback") -> "Local echo"
              t.startsWith("host:") -> {
                val hostId = hostIdFromTabId(t)
                val host = hostId?.let { app.database.hostDao().findById(it) }
                val base = host?.label ?: t.removePrefix("host:").substringBefore(":")
                if (host?.useTmux == true) {
                  tabTmuxSessions[t] = host.tmuxSession.ifBlank { "anoterm" }
                }
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
    when {
      tabId.startsWith("loopback") -> {
        val controller = TerminalSessionController(initialRows = 24, initialCols = 80)
        controller.setConnectionState(ConnectionState.Connected)
        val channel =
            LoopbackChannel(
                listOf(
                    context.getString(R.string.loopback_banner) +
                        " — " +
                        context.getString(R.string.loopback_explanation),
                    context.getString(R.string.loopback_echo),
                ),
            )
        val bundle = app.sessionManager.getOrCreate(tabId) { SessionBundle(controller, channel) }
        state = TabScreenState.Ready(bundle)
      }
      tabId.startsWith("host:") -> {
        state = TabScreenState.Connecting
        val hostId = hostIdFromTabId(tabId)
        if (hostId == null) {
          state = TabScreenState.Error(ConnectFailure.INVALID_TAB)
          return@LaunchedEffect
        }
        val host = app.database.hostDao().findById(hostId)
        if (host == null) {
          state = TabScreenState.Error(ConnectFailure.HOST_NOT_FOUND)
          return@LaunchedEffect
        }
        val params = app.hostRepository.toConnectParams(host)
        if (params == null) {
          state = TabScreenState.Error(ConnectFailure.SECRET_MISSING, hostId = hostId)
          return@LaunchedEffect
        }
        val controller = TerminalSessionController(initialRows = 24, initialCols = 80)
        controller.setConnectionState(ConnectionState.Connecting)
        val keepAlive = app.prefs.keepAliveSeconds.value
        // tmux 系フィールドは auth と違い消えないので capture して再接続の startup で使う。
        val useTmux = params.useTmux
        val tmuxSession = params.tmuxSession
        // 再接続手順: 切断時に channel を張り直す。秘密鍵バイトは 1 接続でゼロクリアされるため、
        // 毎回 DB から host を読み直して params を作り直す（toConnectParams が secret を復号）。
        val reconnectSpec =
            ReconnectSpec(
                connect = { cols, rows ->
                  val h = app.database.hostDao().findById(hostId) ?: error("host removed")
                  val p = app.hostRepository.toConnectParams(h) ?: error("secret missing")
                  SshChannel.connect(
                      params = p,
                      knownHostDao = app.database.knownHostDao(),
                      initialCols = cols,
                      initialRows = rows,
                      keepAliveSeconds = app.prefs.keepAliveSeconds.value,
                      // 再接続では既知ホストのはずだが、鍵が入れ替わっていれば
                      // verify が false を返して接続自体が失敗する。ここで初回扱いに
                      // なるのは、既知ホストの記録を消したあとに繋ぎ直した場合のみ。
                      onFirstSeenHostKey = { kt, fp -> firstSeenKey = kt to fp },
                  )
                },
                startup = {
                  buildStartupCommand(
                      useTmux,
                      tmuxSession,
                      app.prefs.claudeCodeFullscreen.value,
                      TmuxController.ttyVarFor(tabId),
                  )
                },
            )
        try {
          val channel =
              SshChannel.connect(
                  params = params,
                  knownHostDao = app.database.knownHostDao(),
                  initialCols = 80,
                  initialRows = 24,
                  keepAliveSeconds = keepAlive,
                  onFirstSeenHostKey = { kt, fp -> firstSeenKey = kt to fp },
              )
          val bundle =
              app.sessionManager.getOrCreate(tabId) { SessionBundle(controller, channel, reconnectSpec) }
          controller.setConnectionState(ConnectionState.Connected)
          state = TabScreenState.Ready(bundle)
          // 接続成功直後の自動投入コマンド（tmux attach / Claude Code の fullscreen env 注入）。
          // この分岐は「既存 bundle がなくて新規 SSH を張った場合」にしか来ないので 2 重送信にはならない
          // （前段の sessionManager.get(tabId) != null で早期 return 済み）。再接続時は
          // ReconnectSpec.startup が同じコマンドを流し直す。
          buildStartupCommand(
                      useTmux,
                      tmuxSession,
                      app.prefs.claudeCodeFullscreen.value,
                      TmuxController.ttyVarFor(tabId),
                  )?.let {
            controller.sendToRemote(it)
          }
        } catch (t: Throwable) {
          // 画面遷移でこの LaunchedEffect が cancel された場合の CancellationException は
          // 「接続失敗」ではないので UI に出さず再スローする（コルーチンの正常なキャンセル）。
          // SshChannel.connect 側が ssh を閉じてリークも防いでいる。
          if (t is kotlinx.coroutines.CancellationException) throw t
          controller.setConnectionState(ConnectionState.Failed)
          controller.dispose()
          // 例外 message はサーバ名/ユーザ名/鍵パス等を含みうるため UI には出さない。
          // カテゴリ別に固定文言に落とす（詳細はデバッグビルドの Logger.e で別途確認可能）。
          Logger.e("TerminalScreen", "connect failed", t)
          state = TabScreenState.Error(connectFailureOf(t), canRetry = true, hostId = hostId)
        }
      }
      else -> state = TabScreenState.Error(ConnectFailure.INVALID_TAB)
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
              IconButton(onClick = { showTmuxTree = true }) {
                Icon(
                    Icons.Filled.Menu,
                    contentDescription = stringResource(R.string.tmux_tree_open),
                )
              }
              IconButton(onClick = { showHistory = true }) {
                Icon(Icons.Filled.History, contentDescription = stringResource(R.string.terminal_history))
              }
              // tmux ダッシュボードは Phase 2 以降の機能 (control mode 未完成)。
              // 一般ユーザには未使用機能が紛れて見えるのでノイズ、debug + 開発者モード時のみ出す。
              if (BuildConfig.DEBUG && developerMode) {
                IconButton(onClick = { showTmux = true }) {
                  Icon(Icons.Filled.Dashboard, contentDescription = "tmux")
                }
              }
              IconButton(onClick = { showInstallKey = true }) {
                Icon(Icons.Filled.VpnKey, contentDescription = stringResource(R.string.terminal_install_key))
              }
              IconButton(onClick = { showMemo = true }) {
                Icon(Icons.AutoMirrored.Filled.NoteAdd, contentDescription = stringResource(R.string.terminal_memo))
              }
              IconButton(onClick = { showHelp = true }) {
                Icon(Icons.Filled.HelpOutline, contentDescription = stringResource(R.string.terminal_help))
              }
              IconButton(onClick = { showDisconnectConfirm = true }) {
                Icon(
                    Icons.Filled.PowerSettingsNew,
                    contentDescription = stringResource(R.string.host_disconnect),
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
      // tmux のウィンドウ列。SSH タブのバーと同じ場所に置くのは、どちらも
      // 「いま見ているものを切り替える」操作で、探す場所が同じ方がよいため。
      val tmuxBundle = app.sessionManager.get(currentTabId)
      val tmuxChannel = tmuxBundle?.channel as? SshChannel
      val tmuxSessionName = tabTmuxSessions[currentTabId]
      if (tmuxChannel != null && tmuxSessionName != null) {
        TmuxBar(channel = tmuxChannel, ttyVar = TmuxController.ttyVarFor(currentTabId))
      }
      when (val s = state) {
        is TabScreenState.Loading, is TabScreenState.Connecting ->
            Box(modifier = Modifier.weight(1f).fillMaxSize()) { ConnectingIndicator() }
        is TabScreenState.Error ->
            Box(modifier = Modifier.weight(1f).fillMaxSize()) {
              ErrorMessage(
                  failure = s.failure,
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
          // BEL(0x07)の触覚フィードバックは TerminalHost 側の 1 経路に集約した
          // （スロットル + lifecycle 対応済み）。ここで二重に collect すると表示中タブで
          // 二重振動し、かつバックグラウンドでも振動していた。
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

    firstSeenKey?.let { (keyType, fingerprint) ->
      AlertDialog(
          onDismissRequest = { firstSeenKey = null },
          title = { Text(stringResource(R.string.first_seen_title)) },
          text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              Text(
                  stringResource(R.string.first_seen_recorded),
                  style = MaterialTheme.typography.bodyMedium,
              )
              Text("$keyType\n$fingerprint", style = MaterialTheme.typography.bodySmall)
              Text(
                  stringResource(
                      R.string.first_seen_verify,
                      "ssh-keygen -lf /etc/ssh/ssh_host_" +
                          keyType.removePrefix("ssh-") +
                          "_key.pub",
                  ),
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
              )
            }
          },
          confirmButton = { TextButton(onClick = { firstSeenKey = null }) { Text("OK") } },
      )
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
    if (showInstallKey) {
      val ch = readyBundle?.channel
      if (ch is app.anoterm.ssh.SshChannel) {
        InstallKeySheet(
            channel = ch,
            hostLabel = tabLabels[currentTabId] ?: stringResource(R.string.terminal_this_connection),
            onDismiss = { showInstallKey = false },
        )
      } else {
        // 未接続やループバックでは登録できない。黙って閉じるより理由を出す。
        LaunchedEffect(Unit) { showInstallKey = false }
      }
    }
    if (showTmux && readyBundle != null) {
      val panelChannel = readyBundle.channel as? SshChannel
      if (panelChannel != null) {
        TmuxPanel(
            channel = panelChannel,
            ttyVar = TmuxController.ttyVarFor(currentTabId),
            onDismiss = { showTmux = false },
        )
      } else {
        LaunchedEffect(Unit) { showTmux = false }
      }
    }
    if (showTmuxTree) {
      LaunchedEffect(showTmuxTree, activeTabs) {
        tmuxTree = null
        tmuxTree =
            collectTmuxTree(
                sortedTabs.mapNotNull { id ->
                  val ch = app.sessionManager.get(id)?.channel as? SshChannel ?: return@mapNotNull null
                  Triple(id, labelFor(id), ch)
                },
            )
      }
      TmuxTreeSheet(
          connections = tmuxTree,
          currentTabId = currentTabId,
          onJump = { jump ->
            showTmuxTree = false
            coroutineScope.launch {
              // 別の接続なら、まずその接続を前面に出す。ページを跨いだ後で
              // tmux を触らないと、切り替えた結果が見えないまま終わる。
              val page = sortedTabs.indexOf(jump.tabId)
              if (page >= 0 && page != pagerState.currentPage) {
                pagerState.animateScrollToPage(page)
              }
              val bundle = app.sessionManager.get(jump.tabId)
              val ch = bundle?.channel as? SshChannel ?: return@launch
              val snapshot = tmuxTree?.firstOrNull { it.tabId == jump.tabId }?.snapshot
              // アタッチ先が違うならクライアントごと動かす。select-window だけでは
              // そのセッションのカレントが変わるだけで、見えている画面は変わらない。
              if (snapshot?.attached != jump.window.session) {
                snapshot?.clientTty?.let { tty ->
                  TmuxController.switchClient(ch, tty, jump.window.session)
                }
              }
              TmuxController.selectWindow(ch, jump.window)
            }
          },
          onDismiss = { showTmuxTree = false },
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
          title = { Text(stringResource(R.string.terminal_disconnect_title)) },
          text = {
            Text(
                stringResource(R.string.terminal_disconnect_body),
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
              Text(stringResource(R.string.host_disconnect), color = MaterialTheme.colorScheme.error)
            }
          },
          dismissButton = {
            TextButton(onClick = { showDisconnectConfirm = false }) { Text(stringResource(R.string.action_cancel)) }
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
    failure: ConnectFailure,
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
        text = stringResource(R.string.conn_failed, stringResource(failure.message)),
        textAlign = TextAlign.Center,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
      if (canRetry) {
        TextButton(onClick = onRetry) { Text(stringResource(R.string.action_retry)) }
      }
      if (hostId != null) {
        TextButton(onClick = { onEditHost(hostId) }) {
          Text(stringResource(R.string.action_edit_host))
        }
      }
      if (failure == ConnectFailure.HOST_KEY) {
        TextButton(onClick = onOpenKnownHosts) {
          Text(stringResource(R.string.action_known_hosts))
        }
      }
    }
  }
}

private fun hostIdFromTabId(tabId: String): Long? =
    tabId.removePrefix("host:").substringBefore(":").toLongOrNull()

/**
 * 接続直後（初回・再接続とも）にリモートへ流す起動コマンドを組み立てる。
 * - claudeCodeFullscreen ON: `export CLAUDE_CODE_NO_FLICKER=1` で Claude Code を fullscreen 起動させ、
 *   non-fullscreen モードの「同じ応答が scrollback に重複する」現象を防ぐ。無関係なシェルには無害。
 * - claudeCodeFullscreen ON + useTmux ON: 既存 tmux session の env にも書き込む（PC で先に立てた
 *   session に attach しても env を継承できるように）。session 不在は `|| true` で握りつぶす。
 * - useTmux ON: `tmux new -A -s <session>` で attach（-A = 無ければ作成）。
 * 送る物が無ければ null。
 */
internal fun buildStartupCommand(
    useTmux: Boolean,
    tmuxSession: String,
    claudeCodeFullscreen: Boolean,
    /** クライアント特定用の環境変数名。null なら刻印しない（テスト用）。 */
    ttyVar: String? = null,
): ByteArray? {
  // セッション名はそのままシェルの語として並んでいた。`work log` のように空白を含む
  // 名前は二語に割れて別のセッションを作ってしまう。引用符で囲めない名前
  // （単一引用符や制御文字を含むもの）は tmux 部分ごと諦める。
  val session = if (TmuxController.isSafeSessionName(tmuxSession)) tmuxSession else null
  if (useTmux && session == null) {
    Logger.w("Terminal", "tmux session name cannot be quoted safely — not starting tmux")
  }
  val withTmux = useTmux && session != null
  val parts = buildList {
    if (claudeCodeFullscreen) add("export CLAUDE_CODE_NO_FLICKER=1")
    if (claudeCodeFullscreen && withTmux) {
      add(
          "tmux set-environment -t " +
              TmuxController.quote(session!!) +
              " CLAUDE_CODE_NO_FLICKER 1 2>/dev/null || true",
      )
    }
    // アタッチする前に自分の tty を tmux の環境へ書く。後から外側の exec で
    // 「どのクライアントが自分か」を決める唯一の手掛かりになる。
    if (withTmux && ttyVar != null) add(TmuxController.markClientCommand(ttyVar))
    if (withTmux) add("tmux new -A -s " + TmuxController.quote(session!!))
  }
  if (parts.isEmpty()) return null
  // 名前には非 ASCII も入りうる。US-ASCII だと '?' に潰れて別セッションになる。
  return (parts.joinToString("; ") + "\r").toByteArray(Charsets.UTF_8)
}

/**
 * 例外 → UI 用の無害な説明文にマップ。認証失敗時に username/host を表示しないのが要点。
 * 詳細デバッグは Logger で別途出力済み。
 */
private fun connectFailureOf(t: Throwable): ConnectFailure {
  val m = t.message.orEmpty().lowercase()
  return when {
    "authentication" in m || "auth fail" in m || "permission denied" in m -> ConnectFailure.AUTH
    "unknownhost" in m || "no route" in m || "connect" in m && "refused" in m ->
        ConnectFailure.UNREACHABLE
    "timeout" in m || "timed out" in m -> ConnectFailure.TIMEOUT
    "hostkey" in m || "host key" in m -> ConnectFailure.HOST_KEY
    "secret" in m -> ConnectFailure.SECRET_MISSING
    else -> ConnectFailure.UNKNOWN
  }
}
