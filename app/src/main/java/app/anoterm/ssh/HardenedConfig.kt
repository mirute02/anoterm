package app.anoterm.ssh

import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.transport.cipher.AES128CTR
import net.schmizz.sshj.transport.cipher.AES192CTR
import net.schmizz.sshj.transport.cipher.AES256CTR
import net.schmizz.sshj.transport.kex.Curve25519SHA256
import net.schmizz.sshj.transport.kex.DHGexSHA256
import net.schmizz.sshj.transport.kex.ECDHNistP
import net.schmizz.sshj.transport.mac.HMACSHA2256
import net.schmizz.sshj.transport.mac.HMACSHA2512

/**
 * sshj の [DefaultConfig] から、現代的でない方式を落とした設定。
 *
 * `DefaultConfig` をそのまま使うと、鍵交換に diffie-hellman-group1-sha1 と
 * group14-sha1、暗号に Blowfish・CAST5・IDEA・3DES・Arcfour、MAC に HMAC-MD5 と
 * HMAC-SHA1-96 が含まれる。これらは提案リストに載る以上、そのうち一つしか受け付けない
 * サーバー（あるいは中間者）に合わせて**ダウングロードが成立してしまう**。
 * 接続先を選べる立場にあるクライアントが、弱い方式を提案し続ける理由はない。
 *
 * 残しているもの：
 * - 鍵交換: curve25519-sha256、ecdh-sha2-nistp256/384/521、
 *   diffie-hellman-group-exchange-sha256
 * - 暗号: aes128/192/256-ctr
 * - MAC: hmac-sha2-256、hmac-sha2-512
 *
 * sshj 0.40.0 の DHG14 が提供する Factory は diffie-hellman-group14-**sha1** のみで、
 * sha256 版は無い。そのため group14 は採用していない。curve25519 も ECDH も
 * group-exchange-sha256 も持たないサーバーには繋がらなくなるが、そこまで古い実装に
 * SHA-1 の鍵交換で繋ぎにいく価値は無いと判断した。
 */
fun hardenedConfig(): DefaultConfig =
    DefaultConfig().apply {
      keyExchangeFactories =
          listOf(
              Curve25519SHA256.Factory(),
              Curve25519SHA256.FactoryLibSsh(),
              ECDHNistP.Factory521(),
              ECDHNistP.Factory384(),
              ECDHNistP.Factory256(),
              DHGexSHA256.Factory(),
          )
      cipherFactories =
          listOf(
              AES256CTR.Factory(),
              AES192CTR.Factory(),
              AES128CTR.Factory(),
          )
      macFactories =
          listOf(
              HMACSHA2256.Factory(),
              HMACSHA2512.Factory(),
          )
    }
