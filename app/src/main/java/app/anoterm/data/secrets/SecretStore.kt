package app.anoterm.data.secrets

import android.content.Context
import app.anoterm.util.Logger
import androidx.security.crypto.EncryptedFile
import androidx.security.crypto.MasterKey
import java.io.File
import java.util.UUID

/**
 * 暗号化されたファイル単位でパスワード / 秘密鍵本体を格納。
 *
 * レコードは `<secretId>.bin` として `filesDir/secrets/` に保存される。
 * 1 レコード内の形式：
 *   magic(8B) | version(1B) | payloadLen(4B) | payload(UTF-8 JSON)
 *
 * 復号 API は本ファイルのメソッド経由のみで行う（呼び出し元にバイト列を渡さない）。
 */
class SecretStore(context: Context) {
  private val appContext = context.applicationContext
  private val dir: File = File(appContext.filesDir, "secrets").apply { mkdirs() }

  private val masterKey: MasterKey by lazy {
    MasterKey.Builder(appContext).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
  }

  /** パスワードを保存し、参照 id を返す。 */
  fun putPassword(password: String): String = putBlob(Payload.Password(password).toBytes())

  /** [putPassword] の `CharArray` 版。呼び出し側が元の配列をゼロ埋めできる。 */
  fun putPassword(password: CharArray): String = putPassword(String(password))

  /** 秘密鍵 (+ optional passphrase) を保存し、参照 id を返す。 */
  fun putPrivateKey(keyBytes: ByteArray, passphrase: String?): String =
      putBlob(Payload.PrivateKey(keyBytes, passphrase).toBytes())

  /** 参照 id からパスワードを取り出す。鍵レコードの場合は null。 */
  /**
   * 参照 id からパスワードを取り出す。パスワードレコードでなければ null。
   *
   * `CharArray` で返すのは、使い終わったら呼び出し側でゼロ埋めできるようにするため。
   * `String` は不変なので、いったん作ると GC されるまでヒープに残り、メモリダンプから
   * 読める。認証に使う値は保持時間を自分で決められる形にしておく。
   */
  fun loadPassword(secretId: String): CharArray? =
      (loadBlob(secretId) as? Payload.Password)?.value?.toCharArray()

  /** 参照 id から秘密鍵を取り出す。パスワードレコードの場合は null。 */
  fun loadPrivateKey(secretId: String): Pair<ByteArray, String?>? =
      (loadBlob(secretId) as? Payload.PrivateKey)?.let { it.keyBytes to it.passphrase }

  fun delete(secretId: String) {
    fileFor(secretId).delete()
  }

  private fun putBlob(bytes: ByteArray): String {
    val secretId = UUID.randomUUID().toString()
    val file = fileFor(secretId)
    val enc =
        EncryptedFile.Builder(appContext, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
            .build()
    enc.openFileOutput().use { it.write(bytes) }
    return secretId
  }

  /**
   * 復号して取り出す。取り出せなければ null。
   *
   * 復号は落ちうる：ファイルが壊れている、Keystore の鍵が端末のロック変更や
   * 生体情報の再登録で無効化された、GCM タグが合わない、など。ここで例外を投げると
   * 接続ボタンを押しただけでアプリが落ちるので、null を返して呼び出し側の
   * 「secret missing」経路に合流させる。
   *
   * ここで復号できないレコードを消しはしない。Keystore の一時的な失敗と、
   * 本当に復号不能になった状態を、この層では区別できないため。
   */
  private fun loadBlob(secretId: String): Payload? {
    val file = fileFor(secretId)
    if (!file.exists()) return null
    return try {
      val enc =
          EncryptedFile.Builder(appContext, file, masterKey, EncryptedFile.FileEncryptionScheme.AES256_GCM_HKDF_4KB)
              .build()
      val bytes = enc.openFileInput().use { it.readBytes() }
      Payload.fromBytes(bytes)
    } catch (e: java.io.IOException) {
      // 例外オブジェクトはログに渡さない。メッセージに経路が載りうるため種別だけ記録する。
      Logger.w("SecretStore", "decrypt failed for a stored secret (${e.javaClass.simpleName})")
      null
    } catch (e: java.security.GeneralSecurityException) {
      Logger.w("SecretStore", "decrypt failed for a stored secret (${e.javaClass.simpleName})")
      null
    }
  }

  private fun fileFor(secretId: String): File = File(dir, "$secretId.bin")
}

private sealed interface Payload {
  fun toBytes(): ByteArray

  data class Password(val value: String) : Payload {
    /** data class の既定 toString は中身を出す。ログに一行書かれるだけで秘密が漏れるので封じる。 */
    override fun toString(): String = "Password(redacted)"

    override fun toBytes(): ByteArray = encode(TYPE_PASSWORD) { writeUtf8(value) }
  }

  data class PrivateKey(val keyBytes: ByteArray, val passphrase: String?) : Payload {
    /** data class の既定 toString は中身を出す。ログに一行書かれるだけで秘密が漏れるので封じる。 */
    override fun toString(): String = "PrivateKey(redacted)"

    override fun toBytes(): ByteArray =
        encode(TYPE_KEY) {
          writeInt(keyBytes.size)
          write(keyBytes)
          writeUtf8(passphrase ?: "")
        }

    override fun equals(other: Any?): Boolean =
        other is PrivateKey && keyBytes.contentEquals(other.keyBytes) && passphrase == other.passphrase

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + (passphrase?.hashCode() ?: 0)
  }

  companion object {
    private const val TYPE_PASSWORD: Byte = 0x01
    private const val TYPE_KEY: Byte = 0x02

    private fun encode(type: Byte, body: java.io.DataOutputStream.() -> Unit): ByteArray {
      val baos = java.io.ByteArrayOutputStream()
      val dos = java.io.DataOutputStream(baos)
      dos.writeByte(type.toInt())
      dos.body()
      dos.flush()
      return baos.toByteArray()
    }

    fun fromBytes(bytes: ByteArray): Payload? {
      if (bytes.isEmpty()) return null
      val dis = java.io.DataInputStream(bytes.inputStream())
      return when (dis.readByte()) {
        TYPE_PASSWORD -> Password(dis.readUtf8())
        TYPE_KEY -> {
          val keyLen = dis.readInt()
          val key = ByteArray(keyLen).also { dis.readFully(it) }
          val phrase = dis.readUtf8().ifEmpty { null }
          PrivateKey(key, phrase)
        }
        else -> null
      }
    }

    private fun java.io.DataOutputStream.writeUtf8(s: String) {
      val b = s.toByteArray(Charsets.UTF_8)
      writeInt(b.size)
      write(b)
    }

    private fun java.io.DataInputStream.readUtf8(): String {
      val len = readInt()
      val b = ByteArray(len).also { readFully(it) }
      return String(b, Charsets.UTF_8)
    }
  }
}
