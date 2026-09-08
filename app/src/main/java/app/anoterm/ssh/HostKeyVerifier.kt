package app.anoterm.ssh

import android.util.Base64
import app.anoterm.data.db.KnownHostDao
import app.anoterm.data.db.KnownHostEntity
import app.anoterm.util.Logger
import kotlinx.coroutines.runBlocking
import net.schmizz.sshj.common.Buffer
import net.schmizz.sshj.common.KeyType
import net.schmizz.sshj.transport.verification.HostKeyVerifier as SshjHostKeyVerifier
import java.security.MessageDigest
import java.security.PublicKey

/**
 * sshj の HostKeyVerifier を anoterm 側の Room と繋ぐ TOFU 実装。
 *
 * 方針：
 * - そのホストに記録がまったく無ければ、鍵を記録して接続する（Trust On First Use）
 * - 記録済みの鍵と指紋が一致すれば接続する
 * - **そのホストに記録があるのに一致しない場合は、鍵種別が違っていても拒否する**
 *
 * 最後の一点が要点である。照合を (address, port, keyType) で行うと、ed25519 を記録済みの
 * ホストに対して攻撃者が ecdsa を提示するだけで「未知のホスト」と判定され、警告なく
 * 自動信頼される。鍵交換で提示する種別を選ぶのは相手側なので、これは実質的に TOFU の
 * 迂回路になる。ホスト単位で記録の有無を見て、あるなら一致を要求する。
 *
 * あわせて [findExistingAlgorithms] で記録済みの種別を sshj に伝える。sshj はこれを使って
 * host-key algorithm の提案順を既知の種別優先に並べ替えるため、正規のサーバーとは記録済みの
 * 種別で鍵交換が成立しやすくなる。空リストを返すとこの仕組みが働かない。
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
    val known = runBlocking { dao.findAllForHost(address, this@HostKeyVerifier.port) }

    // このホストの記録が一つも無い → 初回。記録して受け入れる。
    if (known.isEmpty()) {
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
      return true
    }

    // 同じ種別の記録があるなら、指紋の一致を要求する。
    val sameType = known.firstOrNull { it.keyType == kt }
    if (sameType != null) {
      if (sameType.fingerprintSha256 == fp) return true
      Logger.w("TOFU", "host key CHANGED for $address:$port ($kt) — refusing")
      return false
    }

    // 記録はあるが、提示されたのは別種別。自動信頼はしない。
    Logger.w(
        "TOFU",
        "host $address:$port is known with ${known.joinToString(",") { it.keyType }}" +
            " but offered $kt — refusing",
    )
    return false
  }

  /** 記録済みの鍵種別を sshj に伝え、提案順を既知の種別優先にさせる。 */
  override fun findExistingAlgorithms(hostname: String, port: Int): List<String> =
      runBlocking { dao.findAllForHost(address, this@HostKeyVerifier.port) }.map { it.keyType }

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
