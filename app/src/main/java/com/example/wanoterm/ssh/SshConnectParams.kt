package com.example.wanoterm.ssh

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
    val tmuxSession: String = "wanoterm",
)

sealed interface AuthCredentials {
  data class Password(val value: String) : AuthCredentials

  data class PrivateKey(val keyBytes: ByteArray, val passphrase: String?) : AuthCredentials {
    override fun equals(other: Any?): Boolean =
        other is PrivateKey && keyBytes.contentEquals(other.keyBytes) && passphrase == other.passphrase

    override fun hashCode(): Int = 31 * keyBytes.contentHashCode() + (passphrase?.hashCode() ?: 0)
  }
}
