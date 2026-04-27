package app.anoterm.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.userauth.password.PasswordUtils

/**
 * 秘密鍵 PEM が sshj で読める形式か事前検証するヘルパー。
 *
 * 以下のいずれかに該当すれば NG：
 * - `-----BEGIN PRIVATE KEY-----` ヘッダの Ed25519 (PKCS8 形式)。sshj 0.40 の PKCS8KeyFile は
 *   OID 1.3.101.112 を未サポートなので、この形式で保存された鍵はロード時に例外を投げる。
 * - 破損した PEM
 * - passphrase 付きで passphrase が合わない
 *
 * 鍵生成・インポート・ホスト紐付け前に呼び出し、UI 側で「使えない鍵」を即時に警告する。
 */
object KeyValidator {
  /**
   * @param pemBytes 秘密鍵の PEM（UTF-8）
   * @param passphrase passphrase 付き鍵のときのみ指定。それ以外は null。
   * @return 成功なら Result.success(Unit)。失敗なら原因を含む Result.failure。
   */
  fun validate(pemBytes: ByteArray, passphrase: String? = null): Result<Unit> =
      runCatching {
        val pem = String(pemBytes, Charsets.UTF_8)
        val ssh = SSHClient(DefaultConfig())
        try {
          val provider =
              if (passphrase != null) {
                ssh.loadKeys(pem, null, PasswordUtils.createOneOff(passphrase.toCharArray()))
              } else {
                ssh.loadKeys(pem, null, null)
              }
          // sshj の KeyProvider は遅延評価なので、ここで実際に公開鍵を取り出して
          // parse エラーが潜在していないか確認する。PKCS8 Ed25519 はこの時点で例外。
          provider.public
          provider.private
        } finally {
          runCatching { ssh.close() }
        }
        Unit
      }
}
