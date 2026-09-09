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
data class TmuxSnapshot(val attached: String?, val windows: List<TmuxWindow>) {
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

  /** tmux の既定 prefix（Ctrl-B）。 */
  const val PREFIX: Byte = 0x02

  /**
   * フィールド区切り。ユニットセパレータ (0x1F)。
   *
   * tmux のウィンドウ名は任意の文字列で、空白もタブも普通に入る。0x1F なら
   * 名前に現れることはまず無い。
   */
  const val SEP = "\u001F"

  private const val WINDOW_TAG = "W"
  private const val CLIENT_TAG = "C"

  // 1 回の exec で両方読む。ウィンドウ一覧とアタッチ先を別々に問い合わせると
  // チャネルを 2 本開くことになり、常時表示のバーから定期的に叩くには重い。
  private val COMMAND =
      listOf(
              "tmux list-clients -F " + quote(CLIENT_TAG + SEP + "#{client_session}"),
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
  fun parseSnapshot(stdout: String, fallbackAttached: String? = null): TmuxSnapshot {
    val windows = mutableListOf<TmuxWindow>()
    val clientSessions = mutableListOf<String>()
    for (raw in stdout.lineSequence()) {
      val parts = raw.trimEnd('\r').split(SEP)
      when {
        parts.size == 2 && parts[0] == CLIENT_TAG -> clientSessions += parts[1]
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
    // クライアントが 1 つならそれが自分。PC からも同じ tmux に繋いでいるとどれが
    // 自分か分からないので、アプリ側が覚えている値に頼る（実在しない名前なら諦める）。
    val attached =
        clientSessions.singleOrNull()
            ?: fallbackAttached?.takeIf { name -> windows.any { it.session == name } }
    return TmuxSnapshot(attached = attached, windows = windows)
  }

  /**
   * アタッチ先を切り替えるキー列。安全でない名前なら null。
   *
   * prefix → `:` で tmux のコマンドプロンプトを開き、`switch-client` を打って改行する。
   * これを読むのはシェルではなく tmux なので、利用者のコマンドラインには何も残らない。
   *
   * `exec` から `switch-client` を投げないのは、どのクライアントを動かすかを
   * 指定できないため。PC からも同じセッションに繋いでいると、そちらが切り替わる。
   */
  fun buildSwitchClientKeys(session: String): ByteArray? {
    if (!isSafeSessionName(session)) return null
    val command = ":switch-client -t " + quote("=" + session) + "\r"
    return byteArrayOf(PREFIX) + command.toByteArray(Charsets.UTF_8)
  }

  /** サーバー側の状態を読む。 */
  suspend fun snapshot(channel: SshChannel, fallbackAttached: String? = null): TmuxListing {
    val result =
        runCatching { channel.exec(COMMAND) }
            .getOrElse { return TmuxListing.Unavailable(it.message ?: "failed to run tmux") }
    if (!result.isSuccess) {
      // tmux が無ければシェルが "command not found" を、サーバー未起動なら tmux 自身が
      // "no server running on ..." を返す。どちらもそのまま見せるのが早い。
      val reason = result.stderr.trim().ifEmpty { "tmux exited with ${result.exitStatus}" }
      return TmuxListing.Unavailable(reason.lineSequence().first())
    }
    val snapshot = parseSnapshot(result.stdout, fallbackAttached)
    val total = result.stdout.lineSequence().count { it.isNotBlank() }
    val clients = result.stdout.lineSequence().count { line ->
      val parts = line.trimEnd('\r').split(SEP)
      parts.size == 2 && parts[0] == CLIENT_TAG
    }
    val understood = snapshot.windows.size + clients
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
    val result =
        runCatching { channel.exec("tmux select-window -t " + quote(window.target)) }
            .getOrElse {
              Logger.w("Tmux", "select-window failed", it)
              return false
            }
    return result.isSuccess
  }
}
