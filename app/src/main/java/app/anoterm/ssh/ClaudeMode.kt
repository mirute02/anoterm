package app.anoterm.ssh

import app.anoterm.util.Logger

/**
 * 向こうで動いている Claude Code が、今どの権限モードにいるか。
 *
 * ⇧Tab を送ればモードは変わるが、変わった結果が端末に返ってくる道は無い。画面の文字を
 * 読んで判定する案は採らない（表示が少し変われば無言で効かなくなる）。代わりに、
 * Claude Code がセッション記録に書いている `permissionMode` をそのまま読む。
 *
 * どの記録がこのウィンドウのものかは、tmux のペインの現在地から辿る。Claude Code は
 * 作業ディレクトリの `/` を `-` に置き換えた名前で記録を分けているので、そこから当てられる。
 * 同じディレクトリで複数立てているときは最後に書かれたものを採る当て推量なので、外すことは
 * ある。外したときは「分からない」に落として、嘘の表示はしない。
 */
object ClaudeMode {

  /** 聞かずに進むモード。ここに無いものは「聞く」側として扱う。 */
  private val NOT_ASKING = setOf("acceptedits", "auto", "bypasspermissions")

  /** 何も確認しないモード。他の自動モードとは色を分ける。 */
  private val UNCHECKED = setOf("bypasspermissions")

  fun isAutoApproving(mode: String?): Boolean = mode?.lowercase() in NOT_ASKING

  fun isUnchecked(mode: String?): Boolean = mode?.lowercase() in UNCHECKED

  /**
   * 読めなければ null。「読めない」と「聞くモード」は別物なので混ぜない。
   *
   * 組み立てているのは POSIX シェルの文。このアプリの他のリモート実行と同じ前提。
   */
  suspend fun read(channel: SshChannel, tmuxSession: String?): String? {
    val paneQuery =
        if (tmuxSession != null && TmuxController.isSafeSessionName(tmuxSession)) {
          "p=\$(tmux display-message -p -t " +
              TmuxController.quote(tmuxSession) +
              " '#{pane_current_path}' 2>/dev/null || true); "
        } else {
          "p=''; "
        }
    val script =
        paneQuery +
            "[ -n \"\$p\" ] || p=\$HOME; " +
            "d=\"\$HOME/.claude/projects/\$(printf '%s' \"\$p\" | sed 's#/#-#g')\"; " +
            "f=\$(ls -t \"\$d\"/*.jsonl 2>/dev/null | head -1); " +
            "[ -n \"\$f\" ] || exit 1; " +
            "grep -oE '\"permissionMode\"[[:space:]]*:[[:space:]]*\"[^\"]*\"' \"\$f\" " +
            "| tail -1 | cut -d'\"' -f4"
    return try {
      val r = channel.exec(script, timeoutMs = 10_000)
      if (!r.isSuccess) return null
      r.stdout
          .trim()
          .takeIf { it.isNotEmpty() && it.length < 40 && it.none { c -> c.isWhitespace() } }
    } catch (t: Throwable) {
      Logger.d("ClaudeMode", "could not read permission mode: " + t.message)
      null
    }
  }
}
