package app.anoterm.ssh

import app.anoterm.util.Logger
import java.io.Closeable
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Parameters

/**
 * 端末の向こう側で動いているものを、こちらの WebView から見るための穴。
 *
 * 開発サーバーはたいてい `localhost` にしか口を開けない。外から見えないのは正しい設定で、
 * それを緩めさせるのは本末転倒。SSH の接続はもう張ってあるのだから、その中を通せばよい。
 *
 * こちらの 127.0.0.1 の適当な空きポートで受け、向こうの [remoteHost]:[remotePort] へ流す。
 * 待ち受けは 127.0.0.1 に限定する。0.0.0.0 で開くと、同じ Wi-Fi にいる誰でも
 * 開発中のページに入れてしまう。
 */
class LocalForward
private constructor(
    private val serverSocket: ServerSocket,
    private val scope: CoroutineScope,
) : Closeable {

  /** こちらで待ち受けているポート。WebView にはこれを渡す。 */
  val localPort: Int
    get() = serverSocket.localPort

  override fun close() {
    // listen() は accept で止まっている。ソケットを閉じるのが唯一の起こし方。
    runCatching { serverSocket.close() }
    scope.cancel()
  }

  companion object {
    suspend fun open(ssh: SSHClient, remoteHost: String, remotePort: Int): LocalForward =
        withContext(Dispatchers.IO) {
          val socket =
              ServerSocket().apply {
                reuseAddress = true
                // ポート 0 は「空いているものを任せる」。固定番号を取り合わずに済む。
                bind(InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0))
              }
          val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
          val params =
              Parameters("127.0.0.1", socket.localPort, remoteHost, remotePort)
          scope.launch {
            try {
              // listen() は閉じられるまで戻らない。専用のコルーチンに置いておく。
              ssh.newLocalPortForwarder(params, socket).listen()
            } catch (t: Throwable) {
              // 閉じたときにも例外で出てくる。利用者に見せる意味はない。
              Logger.d("LocalForward", "forwarder stopped: ${t.message}")
            }
          }
          Logger.d("LocalForward", "127.0.0.1:${socket.localPort} -> $remoteHost:$remotePort")
          LocalForward(socket, scope)
        }
  }
}
