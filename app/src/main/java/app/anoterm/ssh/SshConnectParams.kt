package app.anoterm.ssh

/**
 * SSH 接続の確定パラメータ。ホストメタデータ（DB）と機密情報（SecretStore）を合成した結果。
 */
data class SshConnectParams(
    val label: String,
    val address: String,
    val port: Int,
    val username: String,
    val auth: AuthCredentials,
    /** 接続成功後に `tmux new -A -s <tmuxSession>` を送って永続セッションに attach するか */
    val useTmux: Boolean = false,
    val tmuxSession: String = "anoterm",
)

sealed interface AuthCredentials {
  /**
   * パスワード認証。[value] は認証後に [SshChannel] がゼロ埋めする。
   *
   * `String` ではなく `CharArray` なのは、使い終わった値を確実に消せるようにするため。
   */
  data class Password(val value: CharArray) : AuthCredentials {
    /** data class の既定 toString は中身を出す。ログに一行書かれるだけで秘密が漏れるので封じる。 */
    override fun toString(): String = "Password(redacted)"

    override fun equals(other: Any?): Boolean = other is Password && value.contentEquals(other.value)

    override fun hashCode(): Int = value.contentHashCode()
  }

  data class PrivateKey(val keyBytes: ByteArray, val passphrase: String?) : AuthCredentials {
    /** data class の既定 toString は中身を出す。ログに一行書かれるだけで秘密が漏れるので封じる。 */
    override fun toString(): String = "PrivateKey(redacted)"

    override fun equals(other: Any?): Boolean =
        other is PrivateKey && keyBytes.contentEquals(other.keyBytes) && passphrase == other.passphrase

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + (passphrase?.hashCode() ?: 0)
  }
}
