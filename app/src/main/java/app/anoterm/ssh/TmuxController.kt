package app.anoterm.ssh

import app.anoterm.util.Logger

/** tmux のウィンドウ 1 つ。 */
data class TmuxWindow(
    val session: String,
    val index: Int,
    val active: Boolean,
    val panes: Int,
    /**
     * 最後に何か起きた時刻（tmux の epoch 秒）。
     *
     * `window_activity_flag` ではなくこれを見る。フラグは `monitor-activity` が on の
     * ときしか立たず、既定は off。利用者の `.tmux.conf` を書き換えずに「背面で動きが
     * あった」を知るには、この値が前回より進んだかどうかを見るしかない。
     */
    val activity: Long,
    /** アクティブなペインで動いているコマンド（`bash`, `claude`, `vim` など）。 */
    val command: String,
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
  /**
   * [totalLines] は返ってきた行数、[understoodLines] はそのうち解釈できた行数。
   *
   * ウィンドウが 0 件だったとき、「tmux が本当に何も持っていない」のか
   * 「返っては来たがこちらが読めなかった」のかを、この 2 つの数で切り分ける。
   * 数を見せずに「tmux なし」とだけ出していた頃は、どちらなのか永久に分からなかった。
   */
  data class Ok(
      val snapshot: TmuxSnapshot,
      val totalLines: Int = 0,
      val understoodLines: Int = 0,
      /** 解釈できなかったときに何が返っていたのか。区切りの数と、先頭行そのもの。 */
      val sample: String? = null,
  ) : TmuxListing

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

  /**
   * tmux が返してくる、区切りの「見える形」。
   *
   * 送るときは 0x1F の 1 バイトだが、tmux は出力に含まれる非表示文字を `\\037` という
   * 4 文字の綴りに置き換えて返すことがある（版によって振る舞いが違う）。こちらが
   * 0x1F だけで切っていたせいで、tmux は正しく答えているのに 1 行も読めず、
   * ウィンドウ一覧が「tmux なし」としか言えなくなっていた。
   *
   * どちらで返ってきても読めるようにする。ウィンドウ名にこの 4 文字がそのまま
   * 入っている可能性は無視できる。
   */
  private const val SEP_ESCAPED = "\\037"

  /** 行を項目に切る。生の 0x1F を優先し、無ければ見える形で切る。 */
  private fun splitFields(line: String): List<String> =
      if (line.contains(SEP)) line.split(SEP) else line.split(SEP_ESCAPED)

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
                              "#{window_activity}",
                              "#{pane_current_command}",
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
      val parts = splitFields(line)
      when {
        parts.size == 3 && parts[0] == CLIENT_TAG -> clientSessionByTty[parts[1]] = parts[2]
        parts.size == 8 && parts[0] == WINDOW_TAG -> {
          val index = parts[2].toIntOrNull() ?: continue
          windows +=
              TmuxWindow(
                  session = parts[1],
                  index = index,
                  active = parts[3] == "1",
                  panes = parts[4].toIntOrNull() ?: 1,
                  activity = parts[5].toLongOrNull() ?: 0L,
                  command = parts[6],
                  name = parts[7],
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

  /**
   * tmux 自身のステータス行を隠す／戻すコマンド。
   *
   * 戻すときは `off` を `on` にするのではなく、セッション側の設定を消す（`-u`）。
   * `on` を書き込むと、`.tmux.conf` で自分から消している利用者の設定を、こちらが
   * 勝手に上書きしてしまう。`-u` ならグローバル設定に従うだけになる。
   *
   * 設定が off のときも毎回 `-u` を撃つ。撃たないと、一度隠したあとアプリ側から
   * 戻す手段が無くなる。代償として、tmux 側でセッション単位に `status off` を
   * 設定している人の指定は接続のたびに解除される（グローバル設定は無傷）。
   */
  fun statusLineCommand(session: String, hide: Boolean): String? {
    if (!isSafeSessionName(session)) return null
    val target = quote("=" + session)
    return if (hide) "tmux set-option -t " + target + " status off 2>/dev/null || true"
    else "tmux set-option -u -t " + target + " status 2>/dev/null || true"
  }

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
          val parts = splitFields(line)
          line.startsWith(ttyVar) || (parts.size == 3 && parts[0] == CLIENT_TAG)
        }
    val understood = snapshot.windows.size + others
    if (total > understood) {
      // 解釈できなかった行は黙って捨てている（読み違えて別のウィンドウを選ばせない
      // ための判断）。捨てたこと自体は残しておかないと、一覧が欠ける理由が追えない。
      Logger.w("Tmux", "dropped ${total - understood} unparsable line(s)")
    }
    // 1 行も読めなかったときだけ、返ってきた物の見本を持たせる。区切り文字が届いて
    // いないのか、そもそも tmux 以外の何か（ログインシェルの挨拶など）が混ざって
    // いるのかは、実物を見ないと決められない。
    val sample =
        if (snapshot.windows.isEmpty() && understood == 0) {
          val first = result.stdout.lineSequence().firstOrNull { it.isNotBlank() }?.trimEnd('\r')
          val withSep =
              result.stdout.lineSequence().count { it.contains(SEP) || it.contains(SEP_ESCAPED) }
          val shown =
              first
                  ?.map { if (it == SEP[0]) '·' else if (it.isISOControl()) '?' else it }
                  ?.joinToString("")
                  ?.take(60)
          "区切り${first?.count { it == SEP[0] } ?: 0}個/含む行${withSep} " + (shown ?: "")
        } else {
          null
        }
    return TmuxListing.Ok(
        snapshot,
        totalLines = total,
        understoodLines = understood,
        sample = sample,
    )
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

  /**
   * ウィンドウを閉じる。中で動いているものは道連れになる。
   *
   * `kill-server` は絶対に使わない。あれは自分がぶら下がっている tmux ごと落とすので、
   * 「ウィンドウを 1 つ閉じたい」の答えには決してならない。対象は必ず `-t` で名指しする。
   */
  suspend fun killWindow(channel: SshChannel, window: TmuxWindow): Boolean {
    if (!isSafeSessionName(window.session)) {
      Logger.w("Tmux", "refusing to kill a window whose session name cannot be quoted")
      return false
    }
    return run(channel, "kill-window -t " + quote(window.target))
  }

  /**
   * セッションに新しいウィンドウを作る。
   *
   * `-t` は `=` を付けて完全一致にする。付けないと前方一致で、`work` を指したつもりが
   * `work2` に生えることがある。作られたウィンドウは tmux 側でそのセッションの
   * カレントになるので、そこに attach しているクライアントの表示は自動で追従する。
   */
  suspend fun newWindow(channel: SshChannel, session: String): Boolean {
    if (!isSafeSessionName(session)) {
      Logger.w("Tmux", "refusing to open a window in a session whose name cannot be quoted")
      return false
    }
    return run(channel, "new-window -t " + quote("=" + session))
  }

  /** セッションごと閉じる。中のウィンドウも全部道連れ。 */
  suspend fun killSession(channel: SshChannel, session: String): Boolean {
    if (!isSafeSessionName(session)) {
      Logger.w("Tmux", "refusing to kill a session whose name cannot be quoted")
      return false
    }
    // `=` を付けて完全一致にする。付けないと前方一致で、`work` が `work2` を巻き込む。
    return run(channel, "kill-session -t " + quote("=" + session))
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
