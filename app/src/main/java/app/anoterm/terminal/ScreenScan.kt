package app.anoterm.terminal

/**
 * 画面に流れた文字列の中から、押せるものを見つける。
 *
 * Claude Code や Codex は「スクリーンショットを撮った」と言ってパスだけを残し、開発サーバーは
 * `http://localhost:5173` と出して終わる。端末はそれをただの文字として並べるので、こちらから
 * 拾いに行く以外に触る方法がない。ここはその第一段階、「画面のどこに何があるか」を決める。
 *
 * 端末は行を折り返した記録を持っていない。セルの `continuation` は全角文字の 2 セル目を
 * 指すもので、行の折り返しとは別物。そこで「右端の桁まで文字が届いていたら、次の行はその続き」
 * という推定を使う。長いパスが画面幅で切れるのは普通に起きるので、これが無いとスマホの
 * 狭い画面ではほとんど拾えない。
 */
object ScreenScan {

  /** 中身を絵として出せる拡張子。ここに無いものはパスでも開かない。 */
  private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

  /**
   * 中身を文章として出せる拡張子。
   *
   * Claude Code が書いたものを確かめるのがこれの用途なので、書き換えられがちな
   * ソースと設定と記録を並べている。拡張子で決め打ちにするのは、中身を見るために
   * まず取ってくるという順序では遅すぎるため。
   */
  private val TEXT_EXTENSIONS =
      setOf(
          "kt", "kts", "java", "swift", "py", "rb", "go", "rs", "c", "h", "cc", "cpp", "hpp",
          "ts", "tsx", "js", "jsx", "vue", "svelte", "php", "sh", "bash", "zsh", "fish",
          "gradle", "properties", "toml", "yaml", "yml", "json", "xml", "html", "css", "scss",
          "md", "markdown", "txt", "log", "csv", "tsv", "sql", "diff", "patch", "conf", "ini",
          "cfg", "lock", "gitignore", "dockerfile", "makefile", "cmake", "proto", "graphql",
      )

  /**
   * 単語の切れ目。空白のほか、パスの隣に置かれがちで、かつパス自体には使わない記号。
   *
   * 括弧の開き側だけを入れているのは、閉じ側が「末尾の句読点」として別に落ちるため。
   * `(/tmp/a.png)` の両端を落として `/tmp/a.png` に辿り着きたい。
   */
  private const val BREAKS = " \t\"'`<>|(){}[]"

  /** パスの末尾に付いてきがちで、パスの一部ではない記号。 */
  private const val TRAILING = ".,;:!?"

  /**
   * [row] 行 [col] 桁の位置にある「押せるもの」を返す。無ければ null。
   *
   * [lines] は画面に見えている行をそのまま並べたもの（0 が最上段）。[cols] は端末の桁数で、
   * 折り返しの推定に使う。
   */
  fun targetAt(lines: List<String>, cols: Int, row: Int, col: Int): TapTarget? {
    // 引用符の中を先に見る。`"/tmp/my shot.png"` のような空白入りの名前は、
    // 空白で語を切る限り絶対に拾えない。囲ってあるならそれが境界だと信じる。
    val word = quotedAt(lines, row, col) ?: wordAt(lines, cols, row, col) ?: return null
    val cleaned = clean(word)
    return when {
      isImagePath(cleaned) -> TapTarget.Image(cleaned)
      isTextPath(cleaned) -> TapTarget.Text(cleaned)
      isUrl(cleaned) -> TapTarget.Url(cleaned)
      else -> null
    }
  }

  /**
   * 同じ行の引用符で囲まれた範囲。[col] がその中にあるときだけ返す。
   *
   * 引用符は折り返しを跨いで数えない。閉じ側を見失うと行をいくつも巻き込む。
   */
  private fun quotedAt(lines: List<String>, row: Int, col: Int): String? {
    val line = lines.getOrNull(row) ?: return null
    if (col !in line.indices) return null
    for (quote in charArrayOf('"', '\'')) {
      var i = 0
      while (i < line.length) {
        val open = line.indexOf(quote, i)
        if (open < 0) break
        val close = line.indexOf(quote, open + 1)
        if (close < 0) break
        if (col in (open + 1) until close) {
          return line.substring(open + 1, close).takeIf { it.isNotBlank() }
        }
        i = close + 1
      }
    }
    return null
  }

  /**
   * 端末の表示にくっついてくる飾りを落とす。
   *
   * `file://` を付けて出す道具があり、そのままでは開けない。末尾の句読点は文の一部で、
   * ファイル名の一部ではない。
   */
  private fun clean(word: String): String =
      word.removePrefix("file://").trimEnd { it in TRAILING }

  /** 画面全体を走査して、押せるものの位置を返す。下線を引いて「押せる」と示すのに使う。 */
  fun tapSpans(lines: List<String>, cols: Int): List<TapSpan> {
    val spans = mutableListOf<TapSpan>()
    for (row in lines.indices) {
      val line = lines[row]
      var col = 0
      while (col < line.length) {
        if (line[col] in BREAKS) {
          col++
          continue
        }
        // 単語の右端まで飛ばしてから、その単語がパスかどうかを一度だけ判定する。
        var end = col
        while (end < line.length && line[end] !in BREAKS) end++
        // 画面が書き換わるたびに全行を見る場所なので、点を含まない語は先に落とす。
        // パスの拡張子にも URL のホスト名にも点が要る。折り返しの解決はその後で一度だけやる。
        val mayMatch = (col until end).any { line[it] == '.' || line[it] == ':' } ||
            end >= line.length
        if (mayMatch && targetAt(lines, cols, row, col) != null) {
          spans.add(TapSpan(row, col, end - 1))
        }
        col = end
      }
    }
    return spans
  }

  /**
   * 折り返しを跨いで単語を切り出す。
   *
   * 前後の行が右端まで埋まっていれば同じ論理行とみなして繋ぐ。行が右端に届いていなければ
   * そこで改行されたということなので、繋がない。
   */
  private fun wordAt(lines: List<String>, cols: Int, row: Int, col: Int): String? {
    if (row !in lines.indices) return null
    val line = lines[row]
    if (col !in line.indices) return null
    if (line[col] in BREAKS) return null

    var start = col
    while (start > 0 && line[start - 1] !in BREAKS) start--
    var end = col
    while (end < line.length - 1 && line[end + 1] !in BREAKS) end++

    val head = StringBuilder()
    // 単語が行頭から始まっているなら、その上の行の末尾から続いている可能性がある。
    if (start == 0) {
      var r = row - 1
      while (r >= 0 && wraps(lines[r], cols)) {
        val prev = lines[r]
        var s = prev.length
        while (s > 0 && prev[s - 1] !in BREAKS) s--
        head.insert(0, prev.substring(s))
        // 前の行も丸ごと単語なら、さらに上へ遡る。
        if (s > 0) break
        r--
      }
    }

    val tail = StringBuilder()
    // 単語が行末で切れているなら、次の行の先頭へ続いている可能性がある。
    if (end == line.length - 1 && wraps(line, cols)) {
      var r = row + 1
      while (r < lines.size) {
        val next = lines[r]
        var e = 0
        while (e < next.length && next[e] !in BREAKS) e++
        tail.append(next.substring(0, e))
        // 次の行も丸ごと単語なら、さらに下へ続く。
        if (e < next.length || !wraps(next, cols)) break
        r++
      }
    }

    val word = head.toString() + line.substring(start, end + 1) + tail.toString()
    return word.trimEnd { it in TRAILING }.takeIf { it.isNotEmpty() }
  }

  /**
   * その行が次の行へ折り返しているか。
   *
   * 右端の桁まで文字が届いているかで判断する。呼び出し側は行を桁数ぴったりに揃えて渡すことも、
   * 行末の空白を落として渡すこともあるので、どちらでも同じ答えになるようにしてある。
   */
  private fun wraps(line: String, cols: Int): Boolean =
      line.length >= cols && line.isNotEmpty() && line.last() !in BREAKS

  /**
   * 画像として開けそうな綴りか。
   *
   * 相対パスも受ける。押して開かないより、押せば開くほうがよい。基準になる場所は
   * 読む側 ([app.anoterm.ssh.RemoteImage]) が tmux のペインの現在地などから決める。
   * ここで弾いてしまうと、`docs/shot.png` と書かれた行がただの文字のままになる。
   */
  fun isImagePath(candidate: String): Boolean = hasExtensionIn(candidate, IMAGE_EXTENSIONS)

  private fun hasExtensionIn(candidate: String, extensions: Set<String>): Boolean {
    if (candidate.isEmpty() || candidate.length > MAX_PATH_LENGTH) return false
    if (candidate.any { it.isISOControl() }) return false
    // URL の末尾が .png や .md のこともある。そちらはブラウザの仕事。
    if (candidate.contains("://")) return false
    val name = candidate.substringAfterLast('/')
    if (!name.contains('.')) return false
    if (name.startsWith(".")) return false
    return name.substringAfterLast('.').lowercase() in extensions
  }

  /** 文章として開けそうな綴りか。判定の作りは [isImagePath] と同じ。 */
  fun isTextPath(candidate: String): Boolean = hasExtensionIn(candidate, TEXT_EXTENSIONS)

  /** 基準となる場所を要するパスか。絶対パスと `~` 始まりはそのまま読める。 */
  fun isRelative(path: String): Boolean = !path.startsWith("/") && !path.startsWith("~/")

  /**
   * ブラウザで開ける URL か。
   *
   * `https` も受けるが、扱いは後段で分かれる。SSH のポート転送はこちらの 127.0.0.1 に
   * 口を開けるだけなので、証明書が合わなくなる https は転送できない。
   */
  fun isUrl(candidate: String): Boolean {
    if (candidate.length > MAX_URL_LENGTH) return false
    if (!candidate.startsWith("http://") && !candidate.startsWith("https://")) return false
    if (candidate.any { it.isISOControl() }) return false
    val rest = candidate.substringAfter("://")
    val authority = rest.substringBefore('/').substringBefore('?')
    if (authority.isEmpty()) return false
    // ポート番号だけは形を見ておく。`http://host:abc` は開いても意味がない。
    val port = authority.substringAfterLast(':', "")
    if (authority.contains(':') && port.toIntOrNull()?.takeIf { it in 1..65535 } == null) {
      return false
    }
    return true
  }

  /**
   * シェルに 1 語として渡せる形にする。
   *
   * 単純に全体を単引用符で囲むと `~` が展開されない。先頭の `~/` だけ `"$HOME"/` に
   * 置き換え、残りは引用符の中に閉じ込める。パスに単引用符が入っていても壊れないよう、
   * `'` は `'\''` に展開する。
   */
  fun shellWord(path: String): String =
      if (path.startsWith("~/")) "\"\$HOME\"/" + singleQuote(path.removePrefix("~/"))
      else singleQuote(path)

  private fun singleQuote(s: String): String = "'" + s.replace("'", "'\\''") + "'"

  private const val MAX_PATH_LENGTH = 4096
  private const val MAX_URL_LENGTH = 2048
}

/** 画面上で押せるものが占める範囲。[row] 行の [startCol]..[endCol] 桁。 */
data class TapSpan(val row: Int, val startCol: Int, val endCol: Int)

/** 端末の文字のうち、押すと何かが起きるもの。 */
sealed interface TapTarget {
  /** 中身を絵として出せるファイル。リモートから読んで表示する。 */
  data class Image(val path: String) : TapTarget

  /** 中身を文章として出せるファイル。Claude Code が書いた物を確かめるのに使う。 */
  data class Text(val path: String) : TapTarget

  /** ブラウザで開く先。localhost なら SSH のポート転送を通す。 */
  data class Url(val url: String) : TapTarget
}
