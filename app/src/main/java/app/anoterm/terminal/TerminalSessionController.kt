package app.anoterm.terminal

import app.anoterm.terminal.emulator.TerminalEmulator
import app.anoterm.terminal.emulator.TerminalOutput
import app.anoterm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
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

  // 受信ループは input.read() でブロックし続けるため Dispatchers.IO を使う。
  // 以前は Dispatchers.Default だったが、Default のワーカー数は CPU コア数（最低 2）しかなく、
  // タブ 1 つにつき 1 ワーカーを read で常時占有する。多タブで Default プールが枯渇し、
  // Compose 等 Default に載る他コルーチンが動けなくなってフリーズ/ANR 級の不安定動作を招いていた。
  private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  /** 落ち着くのを待っている resize。次が来たら捨てる。 */
  private var pendingResize: Job? = null

  /**
   * resize で scrollback が何行動いたかを View に返す口。
   *
   * 画面の高さが変われば、同じ行を見続けるにはスクロール位置もそのぶん動かす必要がある。
   * Flow にしないのは、描画と同じフレームで補正したいから。1 フレーム遅れると、
   * まさに直したかった「読んでいた行が上下に飛ぶ」が 1 回起きる。
   */
  var onScrollbackShift: ((Int) -> Unit)? = null

  private val _redrawSignal = MutableSharedFlow<Unit>(extraBufferCapacity = 1, onBufferOverflow = BufferOverflow.DROP_OLDEST)
  val redrawSignal = _redrawSignal.asSharedFlow()

  private val _connectionState = MutableStateFlow(ConnectionState.Idle)
  val connectionState: StateFlow<ConnectionState> = _connectionState.asStateFlow()

  private var readJob: Job? = null

  // 直近に適用した PTY サイズ。同一サイズの resize では SIGWINCH を飛ばさないためのガード。
  private var lastResizeRows: Int = -1
  private var lastResizeCols: Int = -1

  fun setConnectionState(s: ConnectionState) {
    _connectionState.value = s
  }

  /** 現在の端末サイズ。再接続時に新しい PTY を同じサイズで開くために使う。 */
  val currentCols: Int get() = emulator.buffer.cols
  val currentRows: Int get() = emulator.buffer.rows

  /**
   * 再接続で channel を差し替えた直後に呼ぶ。resize の no-op ガード（同一サイズなら SIGWINCH を
   * 送らない）が古いサイズを覚えたままだと、新しい PTY に現在サイズが伝わらない。ガードを
   * リセットして今のサイズを新 channel に必ず送り直す。
   */
  fun reassertChannelSize() {
    val r = emulator.buffer.rows
    val c = emulator.buffer.cols
    lastResizeRows = -1
    lastResizeCols = -1
    resize(r, c)
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

  // 再接続で attachInput が張り替えられるたびに増える世代番号。古い readJob の finally が
  // 新しい接続の Connected を Disconnected で打ち消す race を防ぐため、finally は
  // 「自分が最新世代のときだけ」状態を Disconnected にする。
  @Volatile
  private var readGeneration: Int = 0

  /** ホストからのバイト列を読み続けるループを開始。 */
  fun attachInput(input: InputStream) {
    readJob?.cancel()
    val myGen = ++readGeneration
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
              // 以前はバイト数や generation を逐次 Logger.d に流していたが、`yes` のような
              // 大量出力時に string template 作成の allocation 圧が UI jank に寄与するため削除。
              // 必要ならローカル実験時に一時的に戻す。
              emulator.feed(buf, n)
              _redrawSignal.tryEmit(Unit)
            }
          } finally {
            // 自分より新しい attachInput（= 再接続）が来ていたら状態は触らない。
            if (myGen == readGeneration) {
              _connectionState.value = ConnectionState.Disconnected
              _redrawSignal.tryEmit(Unit)
            }
            Logger.d("CTL", "id=$debugId attachInput finished gen=$myGen")
          }
        }
  }

  fun resize(rows: Int, cols: Int) {
    // サイズが前回と同じなら何もしない。AndroidView.update は再コンポーズのたびに
    // setFontSizeSp → reflowToViewport → resize を呼ぶため、IME アニメ中などに
    // 同一サイズの resize が連発する。無条件に onChannelResize すると PTY へ SIGWINCH が
    // 飛び続け、リモートの tmux / TUI が全画面再描画して無駄トラフィック・ちらつきを生む。
    if (rows == lastResizeRows && cols == lastResizeCols) return

    // 一度も測れていないうちは待たない。初回だけは即座に確定させないと、
    // 接続直後の数百ミリ秒が既定サイズのまま描かれてから作り直しになる。
    if (lastResizeRows <= 0 || lastResizeCols <= 0) {
      applyResize(rows, cols)
      return
    }

    // ここから先は「変わり続けている最中」でありうる。IME の開閉やバーの出し入れは
    // 途中の高さを何度も通過し、その一つ一つに SIGWINCH を送ると、リモートの TUI が
    // そのたびに全画面を描き直す。これが画面のガタつきの実体なので、落ち着くまで待つ。
    pendingResize?.cancel()
    // Main で適用する。emulator の中身が変わるのと、それに合わせた表示位置の補正が
    // 別スレッドに分かれると、1 フレームだけ補正前の位置が描かれてガタつく。
    // SIGWINCH 送信は SshChannel が自前の IO スコープへ逃がすのでここは塞がらない。
    pendingResize =
        scope.launch(Dispatchers.Main) {
          delay(RESIZE_SETTLE_MS)
          applyResize(rows, cols)
        }
  }

  private fun applyResize(rows: Int, cols: Int) {
    lastResizeRows = rows
    lastResizeCols = cols
    val shift = emulator.resize(rows, cols)
    if (shift != 0) onScrollbackShift?.invoke(shift)
    onChannelResize(cols, rows)
    _redrawSignal.tryEmit(Unit)
  }

  fun dispose() {
    pendingResize?.cancel()
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

/**
 * 高さが変わり終わるのを待つ時間。
 *
 * IME の開閉アニメーションは 200〜300ms 程度で、その間に何段階もの高さを通る。
 * 短すぎると途中の高さで SIGWINCH を送ってしまい、長いと画面が追従しないと感じる。
 */
private const val RESIZE_SETTLE_MS = 120L
