package app.anoterm.ssh

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import app.anoterm.terminal.ConnectionState
import app.anoterm.terminal.TerminalSessionController
import app.anoterm.util.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * host タブの再接続手順。
 * - [connect] は channel を張り直す（毎回 params を作り直すこと。秘密鍵バイトは 1 接続で
 *   ゼロクリアされるため使い回せない。呼び出し側で DB から都度読み直す実装にする）。
 * - [startup] は接続直後に流す起動コマンド（tmux attach 等）。無ければ null。
 */
class ReconnectSpec(
    val connect: suspend (cols: Int, rows: Int) -> TerminalChannel,
    val startup: () -> ByteArray?,
)

/**
 * 1 タブに対応する controller + channel のセット。dispose すると両方閉じる。
 * host タブは [reconnectSpec] を持ち、切断時に channel を差し替えて自動再接続できる。
 */
class SessionBundle(
    val controller: TerminalSessionController,
    initialChannel: TerminalChannel,
    val reconnectSpec: ReconnectSpec? = null,
) {
  // 再接続で差し替わるため var。write ループ / resize ハンドラは常にこのプロパティを読むので
  // 差し替え後は自動的に新しい channel を使う。
  var channel: TerminalChannel = initialChannel
    private set

  @Volatile
  var isDisposed = false
    private set

  // 再接続ループの Job（Manager が起動して代入する）。dispose で cancel する。
  var reconnectJob: Job? = null

  private val debugId: String = Integer.toHexString(System.identityHashCode(controller))
  private fun channelStateSuffix(): String =
      (channel as? SshChannel)?.let { " ${it.debugState()}" } ?: ""

  fun isAlive(): Boolean = channel.isAlive()

  // IME の commitText などは UI スレッドで呼ばれる。JSch / Socket の書き込みは
  // NetworkOnMainThreadException を投げるため、専用スレッドに逃がして直列化する。
  private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  private val writeChannel = KChannel<ByteArray>(capacity = KChannel.UNLIMITED)

  init {
    controller.setOutput(
        object : app.anoterm.terminal.emulator.TerminalOutput {
          override fun write(bytes: ByteArray) {
            val ok = writeChannel.trySend(bytes).isSuccess
            if (!ok) Logger.w("Bundle", "write dropped (queue full)")
          }
        },
    )
    controller.setChannelResizeHandler { cols, rows -> channel.resize(cols, rows) }
    controller.attachInput(channel.inputStream)

    // 書き込み専用ループ。Dispatchers.IO のため Socket 呼び出しが許される。
    // 連続投入されたバイト群をまとめて 1 回で flush する（レイテンシ + socket 効率化）。
    writeScope.launch {
      for (bytes in writeChannel) {
        runCatching {
          channel.outputStream.write(bytes)
          while (true) {
            val more = writeChannel.tryReceive().getOrNull() ?: break
            channel.outputStream.write(more)
          }
          channel.outputStream.flush()
        }.onFailure {
          Logger.w(
              "Bundle",
              "id=$debugId write failed alive=${channel.isAlive()}${channelStateSuffix()}",
              it,
          )
        }
      }
    }
  }

  /**
   * 再接続で channel を差し替える。controller（= emulator / scrollback / View 束縛）は保持したまま
   * 入力ストリームを新 channel に繋ぎ直し、現在サイズを新 PTY に送り直す。旧 channel は IO で閉じる。
   */
  fun swapChannel(newChannel: TerminalChannel) {
    val old = channel
    channel = newChannel
    controller.attachInput(newChannel.inputStream)
    controller.reassertChannelSize()
    teardownScope.launch { runCatching { old.close() } }
  }

  fun dispose() {
    isDisposed = true
    reconnectJob?.cancel()
    writeChannel.close()
    writeScope.cancel()
    // channel.close() は network I/O（DISCONNECT 送信）を伴う。メインスレッドから呼ぶと
    // NetworkOnMainThreadException になり接続がリークするため、IO スコープに逃がす。
    teardownScope.launch { runCatching { channel.close() } }
    controller.dispose()
  }

  companion object {
    // 全 SessionBundle 共有の切断用 IO スコープ。
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
}

/**
 * Application スコープで同時に生きている tab を保持。
 * 画面回転 / ロケール切替による Activity 再生成でもタブ状態が維持されるよう、ViewModel には置かない。
 *
 * host タブは切断時に「前面 かつ ネットワーク有り」を条件に指数バックオフで自動再接続する。
 * 背面では再試行しない（Doze 下での連続失敗による電池浪費を避ける）。前面復帰で即再開する。
 */
class SshSessionManager(private val appContext: Context) {
  private val bundles = ConcurrentHashMap<String, SessionBundle>()
  private val _activeTabs = MutableStateFlow<Set<String>>(emptySet())
  val activeTabs: StateFlow<Set<String>> = _activeTabs.asStateFlow()

  // 再接続オーケストレーション用の app スコープと、再接続を許可する 2 つのゲート。
  private val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
  private val appForeground = MutableStateFlow(false)
  private val networkAvailable = MutableStateFlow(true)

  init {
    registerNetworkCallback()
  }

  /** MainActivity の onStart/onStop から前面/背面を通知する。背面では自動再接続を止める。 */
  fun setAppForeground(foreground: Boolean) {
    appForeground.value = foreground
  }

  private fun registerNetworkCallback() {
    val cm = appContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
    runCatching {
      cm.registerDefaultNetworkCallback(
          object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
              networkAvailable.value = true
            }

            override fun onLost(network: Network) {
              networkAvailable.value = false
            }
          },
      )
    }.onFailure { Logger.w("SessionMgr", "network callback register failed", it) }
  }

  @Synchronized
  fun getOrCreate(tabId: String, factory: () -> SessionBundle): SessionBundle {
    get(tabId)?.let { return it }
    val bundle = factory()
    bundles[tabId] = bundle
    _activeTabs.value = bundles.keys.toSet()
    // 1 つでも session が出来たら foreground service を起動。既に起動済みでも安全。
    SshForegroundService.start(appContext)
    if (bundle.reconnectSpec != null) launchReconnect(tabId, bundle)
    return bundle
  }

  @Synchronized
  fun get(tabId: String): SessionBundle? {
    // 死んでいても bundle は返す。自動 dispose すると、ユーザが back 戻り/復帰した
    // タイミングで一瞬 isAlive が false になった拍子に tmux セッション諸共飛ばしてしまう。
    // 明示的な closeTab のときだけ dispose する方針。
    return bundles[tabId]
  }

  @Synchronized
  fun closeTab(tabId: String) {
    bundles.remove(tabId)?.dispose()
    _activeTabs.value = bundles.keys.toSet()
    // 空になったら FGS 停止、残っていれば通知本文（セッション数）を更新するため再 start。
    if (bundles.isEmpty()) SshForegroundService.stop(appContext)
    else SshForegroundService.start(appContext)
  }

  fun activeTabIds(): Set<String> = bundles.keys.toSet()

  fun closeAll() {
    bundles.values.forEach { it.dispose() }
    bundles.clear()
    _activeTabs.value = emptySet()
    SshForegroundService.stop(appContext)
  }

  /**
   * host タブの再接続ループ。切断（Disconnected/Failed）を検知したら、前面かつネットワーク有りに
   * なるまで待ってから接続を試みる。失敗したら 2s→4s→…→30s の指数バックオフで再試行。
   * 成功したら channel を差し替え、tmux attach 等の起動コマンドを流し直す。
   */
  private fun launchReconnect(tabId: String, bundle: SessionBundle) {
    val spec = bundle.reconnectSpec ?: return
    bundle.reconnectJob =
        appScope.launch {
          while (isActive && !bundle.isDisposed) {
            // 現在 or 次に切断されるまで待機。StateFlow なので現在値も評価される。
            bundle.controller.connectionState.first {
              it == ConnectionState.Disconnected || it == ConnectionState.Failed
            }
            if (bundle.isDisposed) break

            var delayMs = 2_000L
            while (isActive && !bundle.isDisposed) {
              // 前面 かつ ネットワーク有りになるまで待つ。背面や圏外では無駄に叩かない。
              combine(appForeground, networkAvailable) { fg, net -> fg && net }.first { it }
              if (bundle.isDisposed) break

              bundle.controller.setConnectionState(ConnectionState.Connecting)
              val newChannel =
                  runCatching {
                        spec.connect(bundle.controller.currentCols, bundle.controller.currentRows)
                      }
                      .onFailure { Logger.w("Reconnect", "id=$tabId attempt failed", it) }
                      .getOrNull()

              if (newChannel != null) {
                bundle.swapChannel(newChannel)
                bundle.controller.setConnectionState(ConnectionState.Connected)
                runCatching { spec.startup()?.let { bundle.controller.sendToRemote(it) } }
                Logger.i("Reconnect", "id=$tabId reconnected")
                break
              }

              bundle.controller.setConnectionState(ConnectionState.Disconnected)
              delay(delayMs)
              delayMs = (delayMs * 2).coerceAtMost(30_000L)
            }
          }
        }
  }
}
