package com.example.wanoterm.data

import com.example.wanoterm.data.db.AuthMethod
import com.example.wanoterm.data.db.HostDao
import com.example.wanoterm.data.db.HostEntity
import com.example.wanoterm.data.secrets.SecretStore
import com.example.wanoterm.ssh.AuthCredentials
import com.example.wanoterm.ssh.SshConnectParams

/**
 * UI / 接続ロジック双方から使える統合ファサード。
 */
class HostRepository(
    private val hostDao: HostDao,
    private val secrets: SecretStore,
) {
  suspend fun findById(id: Long): HostEntity? = hostDao.findById(id)

  suspend fun upsert(
      label: String,
      address: String,
      port: Int,
      username: String,
      auth: AuthMethod,
      secret: SecretInput,
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
    )
  }
}

sealed interface SecretInput {
  data class Password(val value: String) : SecretInput

  data class PrivateKey(val keyBytes: ByteArray, val passphrase: String?) : SecretInput {
    override fun equals(other: Any?): Boolean =
        other is PrivateKey && keyBytes.contentEquals(other.keyBytes) && passphrase == other.passphrase

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + (passphrase?.hashCode() ?: 0)
  }
}
