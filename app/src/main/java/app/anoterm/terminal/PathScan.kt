package app.anoterm.terminal

/**
 * 画面に流れた文字列の中から、ファイルパスらしきものを見つける。
 *
 * Claude Code や Codex は「スクリーンショットを撮った」「図を書き出した」と言って
 * パスだけを残す。端末はそれをただの文字として表示するので、こちらから読みに行く以外に
 * 中身を見る方法がない。ここはその第一段階、「画面のどこに何のパスがあるか」を決める。
 *
 * 端末は行を折り返した記録を持っていない。セルの `continuation` は全角文字の 2 セル目を
 * 指すもので、行の折り返しとは別物。そこで「前の行が右端まで埋まっていたら、それは
 * 折り返しの続き」という推定を使う。長いパスが画面幅で切れるのは普通に起きるので、
 * これが無いとスマホの狭い画面ではほとんど拾えない。
 */
object PathScan {

  /** 中身を絵として出せる拡張子。ここに無いものはパスでも開かない。 */
  private val IMAGE_EXTENSIONS = setOf("png", "jpg", "jpeg", "webp", "gif", "bmp")

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
   * [row] 行 [col] 桁の位置にある画像パスを返す。無ければ null。
   *
   * [lines] は画面に見えている行をそのまま並べたもの（0 が最上段）。[cols] は端末の桁数で、
   * 折り返しの推定に使う。
   */
  fun imagePathAt(lines: List<String>, cols: Int, row: Int, col: Int): String? {
    val word = wordAt(lines, cols, row, col) ?: return null
    return word.takeIf { isImagePath(it) }
  }

  /** 画面全体を走査して、画像パスの位置を返す。下線を引いて「押せる」と示すのに使う。 */
  fun imagePathSpans(lines: List<String>, cols: Int): List<PathSpan> {
    val spans = mutableListOf<PathSpan>()
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
        // 画面が書き換わるたびに全行を見る場所なので、拡張子を持ちえない語は先に落とす。
        // 単語の切り出しと折り返しの解決はその後で一度だけやる。
        val mayBeAPath = (col until end).any { line[it] == '.' } || end >= line.length
        if (mayBeAPath && imagePathAt(lines, cols, row, col) != null) {
          spans.add(PathSpan(row, col, end - 1))
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
   * 絶対パスだけを受け付ける。相対パスは基準が分からない。
   *
   * 画像を読むのはシェルとは別の SSH セッションで、そちらの作業ディレクトリは
   * ホームであってシェルの現在地ではない。`docs/a.png` を渡すと別の場所を見に行く。
   */
  fun isImagePath(candidate: String): Boolean {
    if (candidate.length > MAX_PATH_LENGTH) return false
    if (!candidate.startsWith("/") && !candidate.startsWith("~/")) return false
    if (candidate.any { it.isISOControl() }) return false
    val name = candidate.substringAfterLast('/')
    if (!name.contains('.')) return false
    return name.substringAfterLast('.').lowercase() in IMAGE_EXTENSIONS
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
}

/** 画面上で画像パスが占める範囲。[row] 行の [startCol]..[endCol] 桁。 */
data class PathSpan(val row: Int, val startCol: Int, val endCol: Int)
