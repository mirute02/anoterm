package com.example.wanoterm.ssh

import com.example.wanoterm.data.db.KnownHostDao
import com.example.wanoterm.util.Logger
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.connection.channel.direct.Session as SshjSession
import net.schmizz.sshj.userauth.password.PasswordUtils
import java.io.InputStream
import java.io.OutputStream

/**
 * sshj の SSHClient + Session + Shell をまとめた wanoterm の TerminalChannel 実装。
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
    ): SshChannel =
        withContext(Dispatchers.IO) {
          val ssh = SSHClient(DefaultConfig())
          ssh.connectTimeout = connectTimeoutMs
          ssh.timeout = connectTimeoutMs
          ssh.addHostKeyVerifier(
              HostKeyVerifier(knownHostDao, params.address, params.port),
          )

          ssh.connect(params.address, params.port)

          when (val auth = params.auth) {
            is AuthCredentials.Password -> ssh.authPassword(params.username, auth.value)
            is AuthCredentials.PrivateKey -> {
              val privateKeyPem = String(auth.keyBytes, Charsets.UTF_8)
              val keyProvider =
                  if (auth.passphrase != null) {
                    ssh.loadKeys(
                        privateKeyPem,
                        null,
                        PasswordUtils.createOneOff(auth.passphrase.toCharArray()),
                    )
                  } else {
                    ssh.loadKeys(privateKeyPem, null, null)
                  }
              ssh.authPublickey(params.username, keyProvider)
            }
          }

          // キープアライブ（30 秒間隔）。sshj は connection-layer で keepalive を送る。
          ssh.connection.keepAlive.keepAliveInterval = 30

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
        }
  }
}
