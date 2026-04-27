package app.anoterm.ssh

import app.anoterm.terminal.TerminalSessionController
import app.anoterm.util.Logger
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
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
  private val writeChannel = KChannel<ByteArray>(capacity = 256, onBufferOverflow = BufferOverflow.SUSPEND)

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
    runCatching { channel.close() }
    controller.dispose()
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
    if (bundles.isEmpty()) SshForegroundService.stop(appContext)
  }

  fun activeTabIds(): Set<String> = bundles.keys.toSet()

  fun closeAll() {
    bundles.values.forEach { it.dispose() }
    bundles.clear()
    _activeTabs.value = emptySet()
    SshForegroundService.stop(appContext)
  }
}
