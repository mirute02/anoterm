package app.anoterm.ssh

import app.anoterm.util.Logger

/**
 * 接続中のセッションを使って、公開鍵を接続先の `~/.ssh/authorized_keys` に追記する。
 * `ssh-copy-id` が長年やってきたことと同じ。
 *
 * Android 単体で鍵認証に移行しようとすると、「公開鍵をサーバーへ届ける」が最大の関門になる。
 * PC があればコピペで済むが、端末しかないと詰まる。既にパスワードで入れているなら、
 * その接続の上で追記してしまえばよい。
 *
 * 気をつけていること:
 *
 * - **二重登録しない。** 既に同じ鍵があれば何もしない。何度押しても結果は同じ。
 * - **既存の内容を壊さない。** 追記のみで、ファイルを書き換えたり並べ替えたりしない。
 * - **パーミッションを直す。** `~/.ssh` が 700、`authorized_keys` が 600 でないと
 *   OpenSSH は黙って鍵認証を拒否する。原因が分かりにくい失敗なので先に整える。
 * - **改行を保証する。** 既存ファイルが改行で終わっていないと、追記した鍵が前の行と
 *   繋がって両方無効になる。
 */
object AuthorizedKeyInstaller {

  /** 公開鍵として受け付けなかった理由。文言は画面側で選ぶ。 */
  enum class Rejection {
    EMPTY,
    MULTIPLE_LINES,
    CONTAINS_QUOTE,
    MALFORMED,
  }

  sealed interface Result {
    /** 追記した。 */
    data object Installed : Result

    /** 既に登録済みだった。 */
    data object AlreadyPresent : Result

    /** 鍵そのものが受け付けられなかった。サーバーには何も送っていない。 */
    data class Invalid(val rejection: Rejection) : Result

    /** コマンドを流せなかった（接続断など）。[detail] は例外クラス名。 */
    data class CommandFailed(val detail: String) : Result

    /** サーバー側で失敗した。[detail] は stderr の 1 行目（空のこともある）。 */
    data class ServerRefused(val exitStatus: Int, val detail: String) : Result
  }

  /** [validate] の結果。 */
  sealed interface Validation {
    /** 通った。[keyBody] は「type base64」の 2 列。 */
    data class Valid(val keyBody: String) : Validation

    data class Invalid(val rejection: Rejection) : Validation
  }

  /**
   * 公開鍵として受け付けてよいかを判定する。
   *
   * 鍵は組み立てた sh コマンドに単一引用符で囲んで埋め込む。引用符が混ざれば
   * そこで文字列が閉じ、後ろが命令として実行される。エスケープで凌ぐより、
   * 鍵に現れるはずのない文字が来た時点で断る方が確実。
   *
   * 通れば「type base64」の 2 列を返す。これが authorized_keys の照合キーになる
   * （コメント欄は端末ごとに違うので比較に含めない）。
   */
  fun validate(publicSsh: String): Validation {
    val key = publicSsh.trim()
    if (key.isEmpty()) return Validation.Invalid(Rejection.EMPTY)
    if (key.contains('\n')) return Validation.Invalid(Rejection.MULTIPLE_LINES)
    if (key.contains('\'')) return Validation.Invalid(Rejection.CONTAINS_QUOTE)
    val columns = key.split(" ").filter { it.isNotEmpty() }
    if (columns.size < 2) return Validation.Invalid(Rejection.MALFORMED)
    return Validation.Valid(columns.take(2).joinToString(" "))
  }

  /**
   * [publicSsh] は authorized_keys 1 行形式（`ssh-ed25519 AAAA... comment`）。
   */
  suspend fun install(channel: SshChannel, publicSsh: String): Result {
    val key = publicSsh.trim()
    val keyBody =
        when (val v = validate(key)) {
          is Validation.Invalid -> return Result.Invalid(v.rejection)
          is Validation.Valid -> v.keyBody
        }

    val script = buildString {
      append("set -e; ")
      append("mkdir -p ~/.ssh && chmod 700 ~/.ssh; ")
      append("touch ~/.ssh/authorized_keys && chmod 600 ~/.ssh/authorized_keys; ")
      // 既にあるなら ALREADY を出して終わる
      append("if grep -qF '$keyBody' ~/.ssh/authorized_keys; then echo ALREADY; else ")
      // 最終行に改行が無ければ足してから追記する
      append("[ -s ~/.ssh/authorized_keys ] && [ \"\$(tail -c1 ~/.ssh/authorized_keys)\" != \"\" ] && echo >> ~/.ssh/authorized_keys; ")
      append("printf '%s\\n' '$key' >> ~/.ssh/authorized_keys; echo INSTALLED; fi")
    }

    val result =
        runCatching { channel.exec(script) }
            .getOrElse { return Result.CommandFailed(it.javaClass.simpleName) }

    val out = result.stdout.trim()
    return when {
      out.endsWith("ALREADY") -> Result.AlreadyPresent
      out.endsWith("INSTALLED") -> Result.Installed
      else -> {
        // 鍵そのものはログに出さない。失敗の原因はサーバー側の stderr にある。
        Logger.w("AuthorizedKey", "install failed: exit=${result.exitStatus}")
        val detail = result.stderr.trim().lines().firstOrNull()?.take(120).orEmpty()
        Result.ServerRefused(result.exitStatus, detail)
      }
    }
  }
}
