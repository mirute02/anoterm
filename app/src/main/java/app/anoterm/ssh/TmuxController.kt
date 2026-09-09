package app.anoterm.ssh

import app.anoterm.util.Logger

/** tmux のウィンドウ 1 つ。 */
data class TmuxWindow(
    val session: String,
    val index: Int,
    val active: Boolean,
    val panes: Int,
    val name: String,
) {
  /** `select-window -t` に渡す形。`=` は前方一致ではなく完全一致を指示する。 */
  val target: String
    get() = "=$session:$index"
}

/**
 * サーバー側の tmux の状態。
 *
 * [attached] は「いまこの接続が見ているセッション」。分からなければ null。
 */
data class TmuxSnapshot(
    /** この接続のクライアント（tmux から見た tty）。特定できなければ null。 */
    val clientTty: String?,
    val attached: String?,
    val windows: List<TmuxWindow>,
) {
  val sessions: List<String>
    get() = windows.map { it.session }.distinct()

  /** いま見えているセッションのウィンドウ。 */
  val attachedWindows: List<TmuxWindow>
    get() = windows.filter { it.session == attached }
}

/** 状態取得の結果。 */
sealed interface TmuxListing {
  data class Ok(val snapshot: TmuxSnapshot) : TmuxListing

  /** tmux が入っていない、サーバーが動いていない、など。[reason] はそのまま画面に出す。 */
  data class Unavailable(val reason: String) : TmuxListing
}

/**
 * 対話シェルを介さずに tmux の状態を読む。
 *
 * 以前の案は `tmux list-windows` を端末へ打ち込み、返ってきた表示をバッファから
 * 切り出してパースするというものだった。プロンプトの形に依存するうえ、利用者の
 * 画面に余計な出力が混ざる。[SshChannel.exec] が別セッションで実行できるので、
 * 標準出力をそのまま読めばよい。
 */
object TmuxController {

  /**
   * フィールド区切り。ユニットセパレータ (0x1F)。
   *
   * tmux のウィンドウ名は任意の文字列で、空白もタブも普通に入る。0x1F なら
   * 名前に現れることはまず無い。
   */
  const val SEP = "\u001F"

  private const val WINDOW_TAG = "W"
  private const val CLIENT_TAG = "C"
  private const val TTY_PREFIX = "ANOTERM_TTY_"

  /**
   * このタブのクライアントを見分けるための環境変数名。
   *
   * tmux のコマンドの多くは「どのクライアントに対する操作か」を要求する。外から
   * `list-clients` を見ても、同じセッションに PC からも繋がっていればどれが自分か
   * 分からない。そこで接続時に自分の tty を tmux サーバーの環境に書いておく。
   *
   * タブごとに別の名前にするのは、同じホストの同じセッションを 2 タブで開いたときに
   * 後から繋いだ方が前の値を上書きしてしまうため。
   */
  fun ttyVarFor(tabId: String): String =
      TTY_PREFIX + tabId.uppercase().map { if (it.isLetterOrDigit()) it else '_' }.joinToString("")

  // 1 回の exec で両方読む。ウィンドウ一覧とアタッチ先を別々に問い合わせると
  // チャネルを 2 本開くことになり、常時表示のバーから定期的に叩くには重い。
  private fun readCommand(ttyVar: String) =
      listOf(
              // 未設定なら "-VAR" が返る。エラーにはしない。
              "tmux show-environment -g " + quote(ttyVar) + " 2>/dev/null || true",
              "tmux list-clients -F " +
                  quote(CLIENT_TAG + SEP + "#{client_tty}" + SEP + "#{client_session}"),
              "tmux list-windows -a -F " +
                  quote(
                      listOf(
                              WINDOW_TAG,
                              "#{session_name}",
                              "#{window_index}",
                              "#{window_active}",
                              "#{window_panes}",
                              // 名前は最後。区切りが壊れたときに巻き込まれる範囲を狭くする。
                              "#{window_name}",
                          )
                          .joinToString(SEP),
                  ),
          )
          .joinToString("; ")

  /**
   * セッション名をシェルに渡してよいか。
   *
   * 名前は単一引用符で囲んで埋め込むので、引用符さえ無ければ空白でも記号でも安全に運べる。
   * 制御文字は tmux 側でも名前として扱えないうえ、混ざると出力のパースが崩れる。
   */
  fun isSafeSessionName(name: String): Boolean =
      name.isNotEmpty() && !name.contains('\'') && name.none { it.isISOControl() }

  /** シェルに埋め込める形にする。安全でない名前は呼び出し側で弾いておくこと。 */
  fun quote(value: String): String = "'" + value + "'"

  /**
   * `list-clients` と `list-windows` を混ぜた出力を解析する。
   *
   * 区切りの数が合わない行は捨てる。名前に区切り文字が入っていた場合など、
   * 解釈のしようがない行を無理に読むと、別のウィンドウを選ばせてしまう。
   */
  fun parseSnapshot(stdout: String, ttyVar: String): TmuxSnapshot {
    val windows = mutableListOf<TmuxWindow>()
    val clientSessionByTty = mutableMapOf<String, String>()
    var ourTty: String? = null
    for (raw in stdout.lineSequence()) {
      val line = raw.trimEnd('\r')
      if (line.startsWith(ttyVar + "=")) {
        ourTty = line.substringAfter('=').takeIf { it.isNotBlank() }
        continue
      }
      val parts = line.split(SEP)
      when {
        parts.size == 3 && parts[0] == CLIENT_TAG -> clientSessionByTty[parts[1]] = parts[2]
        parts.size == 6 && parts[0] == WINDOW_TAG -> {
          val index = parts[2].toIntOrNull() ?: continue
          windows +=
              TmuxWindow(
                  session = parts[1],
                  index = index,
                  active = parts[3] == "1",
                  panes = parts[4].toIntOrNull() ?: 1,
                  name = parts[5],
              )
        }
      }
    }
    // 自分の tty が分かればアタッチ先は一意に決まる。分からない（古い接続や、
    // 環境変数を書く前の tmux）ときだけ「クライアントが 1 つならそれが自分」に頼る。
    val tty = ourTty?.takeIf { clientSessionByTty.containsKey(it) }
    val attached =
        if (tty != null) clientSessionByTty[tty]
        else clientSessionByTty.values.distinct().singleOrNull()
    return TmuxSnapshot(clientTty = tty, attached = attached, windows = windows)
  }

  /**
   * 接続直後に自分の tty を tmux サーバーの環境へ書くコマンド。
   *
   * これを撃っておかないと、後から外側の `exec` で「どのクライアントが自分か」を
   * 決められない。`start-server` はサーバーがまだ無いときのために要る（既にあれば
   * 何もしない）。tmux が入っていない環境では黙って失敗させる。
   */
  fun markClientCommand(ttyVar: String): String =
      "tmux start-server 2>/dev/null && " +
          "tmux set-environment -g " +
          quote(ttyVar) +
          " \"\$(tty)\" 2>/dev/null || true"

  /** サーバー側の状態を読む。 */
  suspend fun snapshot(channel: SshChannel, ttyVar: String): TmuxListing {
    val result =
        runCatching { channel.exec(readCommand(ttyVar)) }
            .getOrElse { return TmuxListing.Unavailable(it.message ?: "failed to run tmux") }
    if (!result.isSuccess) {
      // tmux が無ければシェルが "command not found" を、サーバー未起動なら tmux 自身が
      // "no server running on ..." を返す。どちらもそのまま見せるのが早い。
      val reason = result.stderr.trim().ifEmpty { "tmux exited with ${result.exitStatus}" }
      return TmuxListing.Unavailable(reason.lineSequence().first())
    }
    val snapshot = parseSnapshot(result.stdout, ttyVar)
    val total = result.stdout.lineSequence().count { it.isNotBlank() }
    val others =
        result.stdout.lineSequence().count { raw ->
          val line = raw.trimEnd('\r')
          val parts = line.split(SEP)
          line.startsWith(ttyVar) || (parts.size == 3 && parts[0] == CLIENT_TAG)
        }
    val understood = snapshot.windows.size + others
    if (total > understood) {
      // 解釈できなかった行は黙って捨てている（読み違えて別のウィンドウを選ばせない
      // ための判断）。捨てたこと自体は残しておかないと、一覧が欠ける理由が追えない。
      Logger.w("Tmux", "dropped ${total - understood} unparsable line(s)")
    }
    return TmuxListing.Ok(snapshot)
  }

  /**
   * 同じアタッチの中でウィンドウを切り替える。成功したかどうかだけ返す。
   *
   * クライアントは自分のセッションのカレントウィンドウを映すので、別セッションから
   * `select-window` を投げても表示は追随する。
   */
  suspend fun selectWindow(channel: SshChannel, window: TmuxWindow): Boolean {
    if (!isSafeSessionName(window.session)) {
      Logger.w("Tmux", "refusing to switch to a session whose name cannot be quoted")
      return false
    }
    return run(channel, "select-window -t " + quote(window.target))
  }

  /**
   * アタッチ先（このクライアントが見るセッション）を切り替える。
   *
   * `-c` が要る。省くと tmux が適当なクライアントを選ぶので、PC からも同じ tmux に
   * 繋いでいるとそちらが切り替わってしまう。[clientTty] は [snapshot] が返す値。
   */
  suspend fun switchClient(channel: SshChannel, clientTty: String, session: String): Boolean {
    if (!isSafeSessionName(session) || !isSafeSessionName(clientTty)) {
      Logger.w("Tmux", "refusing to switch: name cannot be quoted")
      return false
    }
    return run(channel, "switch-client -c " + quote(clientTty) + " -t " + quote("=" + session))
  }

  /** `tmux <args>` を実行し、成功したかどうかだけ返す。 */
  suspend fun run(channel: SshChannel, args: String): Boolean {
    val result =
        runCatching { channel.exec("tmux " + args) }
            .getOrElse {
              Logger.w("Tmux", "tmux " + args.substringBefore(' ') + " failed", it)
              return false
            }
    if (!result.isSuccess) {
      Logger.w("Tmux", "tmux " + args.substringBefore(' ') + " exited " + result.exitStatus)
    }
    return result.isSuccess
  }
}
