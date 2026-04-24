package com.example.wanoterm.ssh

import android.util.Base64
import com.example.wanoterm.data.db.KnownHostDao
import com.example.wanoterm.data.db.KnownHostEntity
import com.example.wanoterm.util.Logger
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/**
 * sshj の HostKeyVerifier を wanoterm 側の Room と繋ぐ TOFU 実装。
 *
 * v1 方針：
 * - 未知ホストは自動的に信頼して記録する（Trust On First Use）
 * - 記録済みホスト鍵と一致 → OK
 * - 指紋が変化 → false を返し、sshj が接続を中止する。ユーザには UI 側でメッセージを出す（Phase 9）。
 */
class HostKeyVerifier(
    private val dao: KnownHostDao,
    private val address: String,
    private val port: Int,
) : SshjHostKeyVerifier {

  override fun verify(hostname: String, port: Int, key: PublicKey): Boolean {
    val kt = KeyType.fromKey(key).toString()
    val sshBytes = encodeSshPublicKey(key)
    val fp = fingerprintSha256(sshBytes)
    val existing = runBlocking { dao.find(address, this@HostKeyVerifier.port, kt) }
    return when {
      existing == null -> {
        runBlocking {
          dao.upsert(
              KnownHostEntity(
                  address = address,
                  port = this@HostKeyVerifier.port,
                  keyType = kt,
                  fingerprintSha256 = fp,
                  publicKeyBase64 = Base64.encodeToString(sshBytes, Base64.NO_WRAP),
              ),
          )
        }
        true
      }
      existing.fingerprintSha256 == fp -> true
      else -> {
        Logger.w("TOFU", "host key CHANGED for $address:$port ($kt) — refusing")
        false
      }
    }
  }

  override fun findExistingAlgorithms(hostname: String, port: Int): List<String> = emptyList()

  private fun encodeSshPublicKey(key: PublicKey): ByteArray {
    val buffer = Buffer.PlainBuffer()
    KeyType.fromKey(key).putPubKeyIntoBuffer(key, buffer)
    return buffer.compactData
  }

  private fun fingerprintSha256(key: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(key)
    return "SHA256:" + Base64.encodeToString(digest, Base64.NO_WRAP or Base64.NO_PADDING)
  }
}
