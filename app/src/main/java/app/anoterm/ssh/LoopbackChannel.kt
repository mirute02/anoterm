package app.anoterm.ssh

import app.anoterm.util.Logger
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * デバッグ限定のローカルエコー。SSH サーバが無くても IME・VT 動作を手で確認できるようにするため。
 *
 * 挙動：
 * - outputStream に書かれたバイト列は inputStream に流し戻される
 * - 0x0D（CR）は `\r\n` に展開（cooked モード相当）
 * - 0x7F / 0x08（BS/DEL）は `\b \b` に翻訳（cooked バックスペース）
 * - 起動時に簡単なウェルカムメッセージを流す
 * - 特殊コマンドも一切解釈しない（純エコー）。あくまで "自分が打った物が返ってくる" 検証器。
 *
 * 実装：スレッド識別子に依存する PipedInputStream を避け、BlockingQueue ベースの単純な pipe を用意する。
 * PipedInputStream は書き込み側スレッドが死ぬと `Write end dead` で読み出しが失敗するため、
 * ウェルカムを別スレッドから書き込むモデルでは破綻する。
 */
class LoopbackChannel(
    /**
     * 起動時に流す案内。1 行目だけ色を付ける。
     *
     * この層は Context を持たないので、文言は呼び出し側から受け取る。
     * 空なら何も流さない。
     */
    private val welcomeLines: List<String> = emptyList(),
) : TerminalChannel {
  private val closed = AtomicBoolean(false)
  private val queue = LinkedBlockingQueue<ByteArray>()

  override val inputStream: InputStream =
      object : InputStream() {
        private var current: ByteArray? = null
        private var offset = 0

        override fun read(): Int {
          ensureCurrent() ?: return -1
          val cur = current!!
          val b = cur[offset].toInt() and 0xFF
          offset++
          if (offset >= cur.size) {
            current = null
            offset = 0
          }
          return b
        }

        override fun read(b: ByteArray, off: Int, len: Int): Int {
          if (len <= 0) return 0
          ensureCurrent() ?: return -1
          val cur = current!!
          val avail = cur.size - offset
          val n = minOf(len, avail)
          System.arraycopy(cur, offset, b, off, n)
          offset += n
          if (offset >= cur.size) {
            current = null
            offset = 0
          }
          return n
        }

        private fun ensureCurrent(): Unit? {
          while (current == null) {
            if (closed.get() && queue.isEmpty()) return null
            val next = queue.poll(500, TimeUnit.MILLISECONDS) ?: continue
            if (next.isEmpty()) return null // sentinel = EOF
            current = next
            offset = 0
          }
          return Unit
        }

        override fun close() {
          queue.offer(ByteArray(0)) // sentinel
        }
      }

  override val outputStream: OutputStream =
      object : OutputStream() {
        override fun write(b: Int) {
          handleByte(b and 0xFF)
        }

        override fun write(b: ByteArray, off: Int, len: Int) {
          for (i in 0 until len) handleByte(b[off + i].toInt() and 0xFF)
        }
      }

  init {
    // ウェルカムは即時キューに積む（別スレッド不要）
    runCatching {
      val body =
          welcomeLines
              .mapIndexed { i, line ->
                if (i == 0) "\u001B[36m" + line + "\u001B[0m\r\n" else line + "\r\n"
              }
              .joinToString("")
      queue.offer(
          ("\u001B[2J\u001B[H" + body + "\u001B[32m$ \u001B[0m").toByteArray(Charsets.UTF_8),
      )
    }.onFailure { Logger.w("Loopback", "welcome queue failed", it) }
  }

  private fun handleByte(b: Int) {
    if (closed.get()) return
    val out: ByteArray =
        when (b) {
          0x0D -> byteArrayOf(0x0D, 0x0A)
          0x0A -> byteArrayOf(0x0D, 0x0A)
          0x7F, 0x08 -> byteArrayOf(0x08, 0x20, 0x08) // BS SPACE BS
          else -> byteArrayOf(b.toByte())
        }
    queue.offer(out)
  }

  override fun resize(cols: Int, rows: Int) {
    // Loopback では特になし。
  }

  override fun isAlive(): Boolean = !closed.get()

  override fun close() {
    if (closed.compareAndSet(false, true)) {
      queue.offer(ByteArray(0)) // sentinel to unblock reader
    }
  }
}
