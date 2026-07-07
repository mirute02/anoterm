package app.anoterm.ssh

import app.anoterm.terminal.TerminalSessionController
import app.anoterm.util.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel as KChannel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 1 タブに対応する controller + channel のセット。dispose すると両方閉じる。
 */
class SessionBundle(
    val controller: TerminalSessionController,
    val channel: TerminalChannel,
) {
  private val debugId: String = Integer.toHexString(System.identityHashCode(controller))
  private fun channelStateSuffix(): String =
      if (channel is SshChannel) " ${channel.debugState()}" else ""

  fun isAlive(): Boolean = channel.isAlive()

  // IME の commitText などは UI スレッドで呼ばれる。JSch / Socket の書き込みは
  // NetworkOnMainThreadException を投げるため、専用スレッドに逃がして直列化する。
  // UNLIMITED の channel でバックプレッシャを避けつつ、1 コルーチンで順序を保証する。
  private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  // 以前は capacity=256 / SUSPEND だったが、投入は `trySend`（下記 init）なので SUSPEND は
  // 効かず、満杯になると単に drop していた。大きな貼り付けや高速なキーリピートで 256 チャンクを
  // 超えると送信バイトが欠落し、リモートから見ると入力が化ける/コマンドが壊れる。
  // 入力データは人間由来で有限なので UNLIMITED にして欠落を無くす。
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
    // 1 バイトごとに flush していたのを、キューにまだ溜まっている分を drain してから flush。
    writeScope.launch {
      for (bytes in writeChannel) {
        runCatching {
          channel.outputStream.write(bytes)
          // キューにまだ残っていれば連続で書く（flush を遅らせる）。
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

  fun dispose() {
    writeChannel.close()
    writeScope.cancel()
    // channel.close() は shell / session / ssh の切断で network I/O（DISCONNECT 送信）を伴う。
    // closeTab はメインスレッドから呼ばれるため、ここで直接 close すると
    // NetworkOnMainThreadException になり DISCONNECT が送られず、sshj の Reader / KeepAlive
    // スレッドや socket が生き残って接続がリークする（タブを閉じたのに裏で通信が続く）。
    // 専用の IO スコープに逃がして確実に送信・切断させる（fire-and-forget）。
    teardownScope.launch { runCatching { channel.close() } }
    controller.dispose()
  }

  companion object {
    // 全 SessionBundle 共有の切断用 IO スコープ。dispose 内で自身の writeScope は既に
    // cancel 済みのため、独立した長寿命スコープで close を回す。
    private val teardownScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
  }
}

/**
 * Application スコープで同時に生きている tab を保持。
 * 画面回転 / ロケール切替による Activity 再生成でもタブ状態が維持されるよう、ViewModel には置かない。
 */
class SshSessionManager(private val appContext: android.content.Context) {
  private val bundles = ConcurrentHashMap<String, SessionBundle>()
  private val _activeTabs = MutableStateFlow<Set<String>>(emptySet())
  val activeTabs: StateFlow<Set<String>> = _activeTabs.asStateFlow()

  @Synchronized
  fun getOrCreate(tabId: String, factory: () -> SessionBundle): SessionBundle {
    get(tabId)?.let { return it }
    val bundle = factory()
    bundles[tabId] = bundle
    _activeTabs.value = bundles.keys.toSet()
    // 1 つでも session が出来たら foreground service を起動。既に起動済みでも安全。
    SshForegroundService.start(appContext)
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
}
