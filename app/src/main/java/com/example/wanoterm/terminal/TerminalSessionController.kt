package com.example.wanoterm.terminal

import com.example.wanoterm.terminal.emulator.TerminalEmulator
import com.example.wanoterm.terminal.emulator.TerminalOutput
import com.example.wanoterm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.InputStream

/**
 * 1 タブ分のセッション状態。
 *
 * - 自前 `TerminalEmulator` を保持
 * - 送信先 [TerminalOutput] に書き出すとリモートへ行く（SSH チャネル or Loopback）
 * - [attachInput] でリモートからのバイト列 `InputStream` を bind し、coroutine で読み続ける
 * - 描画層は `redrawSignal` を collect して invalidate
 */
class TerminalSessionController(
    initialRows: Int,
    initialCols: Int,
) {
  private val debugId: String = Integer.toHexString(System.identityHashCode(this))
  private var sendOutput: TerminalOutput = NullOutput()
  private var onChannelResize: (cols: Int, rows: Int) -> Unit = { _, _ -> }

  val commandHistory = CommandHistory()

  // リモートから OSC 0/2 で来たタイトル（タブ表示に反映できる）
  private val _remoteTitle = MutableStateFlow<String?>(null)
  val remoteTitle: StateFlow<String?> = _remoteTitle.asStateFlow()

  // BEL (0x07) 通知: 受信ごとに 1 回 emit
  private val _bell = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val bell = _bell.asSharedFlow()

  val emulator: TerminalEmulator =
      TerminalEmulator(
          initialRows,
          initialCols,
          object : TerminalOutput {
            override fun write(bytes: ByteArray) {
              sendOutput.write(bytes)
            }
          },
      ).apply {
        onTitleChanged = { t -> _remoteTitle.value = t }
        onBell = { _bell.tryEmit(Unit) }
      }

  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

  private val _redrawSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val redrawSignal = _redrawSignal.asSharedFlow()

  private val _connectionState = MutableStateFlow(ConnectionState.Idle)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private var readJob: Job? = null

  fun setConnectionState(s: ConnectionState) {
    _connectionState.value = s
  }

  fun setOutput(out: TerminalOutput) {
    sendOutput = out
  }

  /** 画面側がリサイズを通知したとき、SSH チャネルにも PTY サイズを伝える。 */
  fun setChannelResizeHandler(handler: (cols: Int, rows: Int) -> Unit) {
    onChannelResize = handler
  }

  /** 上位層（View / キーボードツールバー）からの送信エントリ。 */
  fun sendToRemote(bytes: ByteArray) {
    // 機密（パスワード・鍵入力含む）を logcat に出さない。サイズだけログ。
    Logger.d("CTL", "id=$debugId sendToRemote bytes=${bytes.size}")
    commandHistory.observe(bytes)
    sendOutput.write(bytes)
  }

  /** ホストからのバイト列を読み続けるループを開始。 */
  fun attachInput(input: InputStream) {
    readJob?.cancel()
    readJob =
        scope.launch {
          val buf = ByteArray(4096)
          try {
            while (true) {
              val n =
                  runCatching { input.read(buf) }.getOrElse {
                    Logger.w("CTL", "id=$debugId read failed", it)
                    return@launch
                  }
              if (n < 0) {
                Logger.d("CTL", "id=$debugId readFromRemote eof")
                break
              }
              if (n == 0) {
                Logger.d("CTL", "id=$debugId readFromRemote zero-byte read")
                continue
              }
              // 受信内容（サーバの出力）はユーザデータなので hex/text dump しない。
              Logger.d("CTL", "id=$debugId readFromRemote bytes=$n")
              emulator.feed(buf, n)
              Logger.d(
                  "CTL",
                  "id=$debugId buffer generation=${emulator.buffer.generation} cursor=${emulator.cursorRow},${emulator.cursorCol}",
              )
              _redrawSignal.tryEmit(Unit)
            }
          } finally {
            _connectionState.value = ConnectionState.Disconnected
            Logger.d("CTL", "id=$debugId attachInput finished")
            _redrawSignal.tryEmit(Unit)
          }
        }
  }

  fun resize(rows: Int, cols: Int) {
    emulator.resize(rows, cols)
    onChannelResize(cols, rows)
    _redrawSignal.tryEmit(Unit)
  }

  fun dispose() {
    readJob?.cancel()
    scope.cancel()
  }

  private class NullOutput : TerminalOutput {
    override fun write(bytes: ByteArray) = Unit
  }
}

enum class ConnectionState {
  Idle,
  Connecting,
  Connected,
  Disconnected,
  Failed,
}
