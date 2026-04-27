package app.anoterm.ssh

import java.io.InputStream
import java.io.OutputStream

/**
 * 端末セッションの 1 チャネル抽象。
 *
 * SSH / Loopback / Mosh 等の実装を差し替えられるよう最小化した。
 * - `inputStream`：リモート→ローカル（VT へ流し込む）
 * - `outputStream`：ローカル→リモート（キー入力 / 合成確定）
 */
interface TerminalChannel {
  val inputStream: InputStream
  val outputStream: OutputStream

  fun resize(cols: Int, rows: Int)

  fun isAlive(): Boolean

  fun close()
}
