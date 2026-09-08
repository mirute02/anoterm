package app.anoterm.ssh

import app.anoterm.data.db.KnownHostDao
import app.anoterm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session as SshjSession
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.InputStream
import java.io.OutputStream

/**
 * sshj の SSHClient + Session + Shell をまとめた anoterm の TerminalChannel 実装。
 *
 * JSch から sshj に差し替えた理由:
 * JSch 0.2.21 / 2.28.0 ともに、OpenSSH 9.6 + 特定 cipher の組み合わせで、
 * 最初の CHANNEL_DATA パケットのフレーミング/MAC がずれて sshd に
 * "incomplete message" / "message authentication code incorrect" で
 * 切断される不具合が再現したため（同じ端末の Termux では正常動作）。
 */
class SshChannel private constructor(
    private val ssh: SSHClient,
    private val session: SshjSession,
    private val shell: SshjSession.Shell,
    override val inputStream: InputStream,
    override val outputStream: OutputStream,
) : TerminalChannel {

  // resize は onSizeChanged から呼ばれるため UI スレッド。Socket I/O は禁止されているので
  // 専用 IO スコープに逃がす。ここで同期的に write してしまうと NetworkOnMainThreadException で
  // 送信途中の SSH パケットが TCP ストリームに残り、以降の全パケットが "Bad packet length" で
  // サーバに拒否される（現象として "最初の 1 バイトで切断される" の本当の原因）。
  private val ioScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

  override fun resize(cols: Int, rows: Int) {
    ioScope.launch {
      try {
        shell.changeWindowDimensions(cols, rows, cols * 8, rows * 16)
      } catch (t: Throwable) {
        Logger.w("SshChannel", "changeWindowDimensions failed", t)
      }
    }
  }

  override fun isAlive(): Boolean = ssh.isConnected && shell.isOpen

  fun debugState(): String =
      "sshConnected=${ssh.isConnected} shellOpen=${shell.isOpen} sessionOpen=${session.isOpen}"

  override fun close() {
    Logger.d("SshChannel", "close ${debugState()}")
    ioScope.cancel()
    runCatching { shell.close() }
    runCatching { session.close() }
    runCatching { ssh.disconnect() }
    runCatching { ssh.close() }
  }

  companion object {
    /** IO thread 上でのみ呼ぶこと。 */
    suspend fun connect(
        params: SshConnectParams,
        knownHostDao: KnownHostDao,
        initialCols: Int,
        initialRows: Int,
        connectTimeoutMs: Int = 15_000,
        keepAliveSeconds: Int = 60,
    ): SshChannel =
        withContext(Dispatchers.IO) {
          val ssh = SSHClient(hardenedConfig())
          try {
          ssh.connectTimeout = connectTimeoutMs
          ssh.timeout = connectTimeoutMs
          ssh.addHostKeyVerifier(
              HostKeyVerifier(knownHostDao, params.address, params.port),
          )

          ssh.connect(params.address, params.port)

          when (val auth = params.auth) {
            is AuthCredentials.Password -> {
              // CharArray のまま sshj へ渡し、認証が終わったら自分たちのコピーを消す。
              // sshj 側の内部コピーは接続維持に要るので残る。
              try {
                ssh.authPassword(params.username, auth.value)
              } finally {
                auth.value.fill('\u0000')
              }
            }
            is AuthCredentials.PrivateKey -> {
              // 秘密鍵 PEM を一度 String にして sshj に渡し、使い終わったら
              // `auth.keyBytes` の中身（暗号化されていない元バイト列）を上書き消去。
              val privateKeyPem = String(auth.keyBytes, Charsets.UTF_8)
              val passphraseChars: CharArray? = auth.passphrase?.toCharArray()
              try {
                val keyProvider =
                    if (passphraseChars != null) {
                      ssh.loadKeys(privateKeyPem, null, PasswordUtils.createOneOff(passphraseChars))
                    } else {
                      ssh.loadKeys(privateKeyPem, null, null)
                    }
                ssh.authPublickey(params.username, keyProvider)
              } finally {
                // 認証後、本アプリが保持しているコピーはゼロクリア。sshj 側の
                // 内部コピー（KeyProvider 内）は残るがこれは接続維持に必要。
                auth.keyBytes.fill(0)
                passphraseChars?.fill('\u0000')
              }
            }
          }

          // キープアライブ。sshj は connection-layer で keepalive を送る。間隔が短いほど
          // 切断検知は速いがモバイル無線のウェイクアップが増えて電池を食う。0 で無効。
          // 既定 60 秒は「電池」と「死んだ接続の検知速度（= 自動再接続の反応）」の折衷。
          ssh.connection.keepAlive.keepAliveInterval = keepAliveSeconds.coerceAtLeast(0)

          val session = ssh.startSession()
          session.allocatePTY(
              "xterm-256color",
              initialCols,
              initialRows,
              initialCols * 8,
              initialRows * 16,
              emptyMap(),
          )
          runCatching { session.setEnvVar("LANG", "en_US.UTF-8") }
          runCatching { session.setEnvVar("LC_ALL", "en_US.UTF-8") }

          val shell = session.startShell()

          Logger.i(
              "SshChannel",
              "connected ${params.username}@${params.address}:${params.port}",
          )

          SshChannel(ssh, session, shell, shell.inputStream, shell.outputStream)
          } catch (t: Throwable) {
            // connect / auth / startShell のいずれで失敗・キャンセルされても ssh を確実に閉じる。
            // ここで閉じないと、接続確立・keepalive スレッド稼働中の SSHClient が誰にも参照
            // されず浮遊し（返り値にならないので close する手段が無い）、30 秒ごとに通信し続けて
            // 電池と接続枠を食う。CancellationException（画面遷移で LaunchedEffect が cancel）も
            // ここに来るので、キャンセル時のリークもこの経路で塞がる。
            runCatching { ssh.disconnect() }
            runCatching { ssh.close() }
            throw t
          }
        }
  }
}
