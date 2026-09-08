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

/** ウィンドウ一覧の取得結果。 */
sealed interface TmuxListing {
  data class Ok(val windows: List<TmuxWindow>) : TmuxListing

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

  private val FORMAT =
      listOf(
              "#{session_name}",
              "#{window_index}",
              "#{window_active}",
              "#{window_panes}",
              // 名前は最後。区切りが壊れたときに巻き込まれる範囲を狭くする。
              "#{window_name}",
          )
          .joinToString(SEP)

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
   * `list-windows` の出力を解析する。
   *
   * 区切りの数が合わない行は捨てる。名前に区切り文字が入っていた場合など、
   * 解釈のしようがない行を無理に読むと、別のウィンドウを選ばせてしまう。
   */
  fun parseWindows(stdout: String): List<TmuxWindow> =
      stdout
          .lineSequence()
          .map { it.trimEnd('\r') }
          .filter { it.isNotBlank() }
          .mapNotNull { line ->
            val parts = line.split(SEP)
            if (parts.size != 5) return@mapNotNull null
            val index = parts[1].toIntOrNull() ?: return@mapNotNull null
            val panes = parts[3].toIntOrNull() ?: 1
            TmuxWindow(
                session = parts[0],
                index = index,
                active = parts[2] == "1",
                panes = panes,
                name = parts[4],
            )
          }
          .toList()

  /** 全セッションのウィンドウを列挙する。 */
  suspend fun listWindows(channel: SshChannel): TmuxListing {
    val result =
        runCatching { channel.exec("tmux list-windows -a -F " + quote(FORMAT)) }
            .getOrElse { return TmuxListing.Unavailable(it.message ?: "failed to run tmux") }
    if (!result.isSuccess) {
      // tmux が無ければシェルが "command not found" を、サーバー未起動なら tmux 自身が
      // "no server running on ..." を返す。どちらもそのまま見せるのが早い。
      val reason = result.stderr.trim().ifEmpty { "tmux exited with ${result.exitStatus}" }
      return TmuxListing.Unavailable(reason.lineSequence().first())
    }
    val windows = parseWindows(result.stdout)
    val lines = result.stdout.lineSequence().count { it.isNotBlank() }
    if (windows.size != lines) {
      // 解釈できなかった行は黙って捨てている（読み違えて別のウィンドウを選ばせない
      // ための判断）。捨てたこと自体は残しておかないと、一覧が欠ける理由が追えない。
      Logger.w("Tmux", "dropped ${lines - windows.size} unparsable list-windows line(s)")
    }
    return TmuxListing.Ok(windows)
  }

  /** ウィンドウを切り替える。成功したかどうかだけ返す。 */
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
