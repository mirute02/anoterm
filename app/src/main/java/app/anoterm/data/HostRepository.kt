package app.anoterm.data

import app.anoterm.data.db.AuthMethod
import app.anoterm.data.db.HostDao
import app.anoterm.data.db.HostEntity
import app.anoterm.data.secrets.SecretStore
import app.anoterm.ssh.AuthCredentials
import app.anoterm.ssh.SshConnectParams

/**
 * UI / 接続ロジック双方から使える統合ファサード。
 */
class HostRepository(
    private val hostDao: HostDao,
    private val secrets: SecretStore,
) {
  suspend fun findById(id: Long): HostEntity? = hostDao.findById(id)

  /**
   * 同じ label / address / port / username / tmux attach 先のホストが既に存在するかを確認。
   * 自分自身（existingId）は除外するので、単なる編集・更新は conflict にならない。
   */
  suspend fun findDuplicate(
      label: String,
      address: String,
      port: Int,
      username: String,
      useTmux: Boolean,
      tmuxSession: String,
      excludeId: Long?,
  ): HostEntity? =
      hostDao.findDuplicate(
          label = label,
          address = address,
          port = port,
          username = username,
          useTmux = useTmux,
          tmuxSession = tmuxSession.ifBlank { "anoterm" },
          excludeId = excludeId ?: -1L,
      )

  suspend fun upsert(
      label: String,
      address: String,
      port: Int,
      username: String,
      auth: AuthMethod,
      secret: SecretInput,
      useTmux: Boolean = false,
      tmuxSession: String = "anoterm",
      existingId: Long? = null,
  ): Long {
    // 既存の secret は SecretStore 経由で新規保存し、古い secret は削除
    val existing = existingId?.let { hostDao.findById(it) }
    if (existing != null) secrets.delete(existing.secretId)
    val secretId =
        when (secret) {
          is SecretInput.Password -> secrets.putPassword(secret.value)
          is SecretInput.PrivateKey -> secrets.putPrivateKey(secret.keyBytes, secret.passphrase)
        }
    val entity =
        HostEntity(
            id = existingId ?: 0L,
            label = label,
            address = address,
            port = port,
            username = username,
            auth = auth,
            secretId = secretId,
            useTmux = useTmux,
            tmuxSession = tmuxSession.ifBlank { "anoterm" },
        )
    return hostDao.upsert(entity)
  }

  /**
   * 認証情報に触らない編集（label / address / port / username / tmux 等）。
   * 既存 secret_id / auth / createdAt はそのまま引き継ぐ。
   */
  suspend fun upsertMetadata(
      id: Long,
      label: String,
      address: String,
      port: Int,
      username: String,
      useTmux: Boolean,
      tmuxSession: String,
  ): Long {
    val existing = hostDao.findById(id) ?: return -1L
    val updated =
        existing.copy(
            label = label,
            address = address,
            port = port,
            username = username,
            useTmux = useTmux,
            tmuxSession = tmuxSession.ifBlank { "anoterm" },
        )
    return hostDao.upsert(updated)
  }

  /**
   * 既存ホストの認証情報を複製して、別の表示行として保存する。
   * tmux attach 先違いのホストを並べる用途で、secret_id は共有しない。
   */
  suspend fun duplicateWithMetadata(
      sourceId: Long,
      label: String,
      address: String,
      port: Int,
      username: String,
      useTmux: Boolean,
      tmuxSession: String,
  ): Long {
    val existing = hostDao.findById(sourceId) ?: return -1L
    val secretId =
        when (existing.auth) {
          AuthMethod.PASSWORD ->
              secrets.loadPassword(existing.secretId)?.let { chars ->
                try {
                  secrets.putPassword(chars)
                } finally {
                  chars.fill('\u0000')
                }
              }
          AuthMethod.PRIVATE_KEY ->
              secrets.loadPrivateKey(existing.secretId)?.let { (keyBytes, passphrase) ->
                secrets.putPrivateKey(keyBytes, passphrase)
              }
        } ?: return -1L
    val entity =
        existing.copy(
            id = 0L,
            label = label,
            address = address,
            port = port,
            username = username,
            secretId = secretId,
            useTmux = useTmux,
            tmuxSession = tmuxSession.ifBlank { "anoterm" },
            createdAt = System.currentTimeMillis(),
            lastUsedAt = null,
        )
    return hostDao.upsert(entity)
  }

  suspend fun delete(host: HostEntity) {
    secrets.delete(host.secretId)
    hostDao.delete(host)
  }

  suspend fun toConnectParams(host: HostEntity): SshConnectParams? {
    val creds =
        when (host.auth) {
          AuthMethod.PASSWORD -> secrets.loadPassword(host.secretId)?.let { AuthCredentials.Password(it) }
          AuthMethod.PRIVATE_KEY -> secrets.loadPrivateKey(host.secretId)?.let { (k, p) -> AuthCredentials.PrivateKey(k, p) }
        } ?: return null
    hostDao.touchLastUsed(host.id)
    return SshConnectParams(
        label = host.label,
        address = host.address,
        port = host.port,
        username = host.username,
        auth = creds,
        useTmux = host.useTmux,
        tmuxSession = host.tmuxSession,
    )
  }
}

sealed interface SecretInput {
  data class Password(val value: String) : SecretInput {
    /** data class の既定 toString は中身を出す。ログに一行書かれるだけで秘密が漏れるので封じる。 */
    override fun toString(): String = "Password(redacted)"
  }

  data class PrivateKey(val keyBytes: ByteArray, val passphrase: String?) : SecretInput {
    /** data class の既定 toString は中身を出す。ログに一行書かれるだけで秘密が漏れるので封じる。 */
    override fun toString(): String = "PrivateKey(redacted)"

    override fun equals(other: Any?): Boolean =
        other is PrivateKey && keyBytes.contentEquals(other.keyBytes) && passphrase == other.passphrase

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + (passphrase?.hashCode() ?: 0)
  }
}
