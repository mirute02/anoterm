package app.anoterm.util

/**
 * 端末セル幅（wcwidth 相当）。
 *
 * 0 セル … 制御文字、結合文字 (Mn/Me)、書式文字 (Cf)、ゼロ幅スペース類
 * 2 セル … East Asian Width が Wide または Fullwidth
 * 1 セル … それ以外
 *
 * 結合文字と書式文字の判定は手書きの範囲表ではなく [Character.getType] に委ねている。
 * 範囲表は必ず抜ける — 実際、U+3099（NFD 分解された日本語の濁点）が幅 2 と判定され、
 * macOS 由来のファイル名を表示すると桁がずれていた。カテゴリで引けばこの種の抜けは
 * 原理的に起きない。
 *
 * 幅 2 の側は、カテゴリでは判定できないため範囲表を使う。Unicode の
 * EastAsianWidth.txt の W と F の範囲を写したもの。
 *
 * ## 曖昧幅 (East Asian Ambiguous)
 *
 * ギリシャ文字や罫線素片など、東アジア文脈では 2 セル、それ以外では 1 セルで
 * 描かれる文字がある。どちらが正しいかは接続先の端末とフォント次第なので、
 * [ambiguousWide] で切り替える。既定は 1（xterm や tmux の既定に合わせる）。
 *
 * ## 意図的にやっていないこと
 *
 * 絵文字の ZWJ シーケンス（👨‍👩‍👧 のような合字）を 1 つのクラスタとして扱わない。
 * 構成する code point の幅を単純に足す。xterm と tmux も同じ挙動で、端末側で
 * 揃えないと相手の想定とずれるため。
 *
 * 地域表示子 (U+1F1E6..U+1F1FF) は 1 セルとする。2 つ並べて国旗になるが、
 * それぞれを 2 セルとすると xterm/tmux では 4 セル分ずれる。
 */
object CharWidth {

  /** 曖昧幅の文字を 2 セルとして扱うか。既定は false（1 セル）。 */
  @Volatile var ambiguousWide: Boolean = false

  /** code point のセル幅（0, 1, 2）を返す。 */
  fun widthOf(codePoint: Int): Int {
    if (codePoint == 0) return 0
    // C0 / C1 制御文字
    if (codePoint < 0x20 || codePoint in 0x7F..0x9F) return 0
    if (isZeroWidth(codePoint)) return 0
    if (isWide(codePoint)) return 2
    if (ambiguousWide && isAmbiguous(codePoint)) return 2
    return 1
  }

  /** 文字列を走査し、セル総幅を返す。 */
  fun stringWidth(s: CharSequence): Int {
    var w = 0
    var i = 0
    while (i < s.length) {
      val cp = Character.codePointAt(s, i)
      w += widthOf(cp)
      i += Character.charCount(cp)
    }
    return w
  }

  private fun isZeroWidth(cp: Int): Boolean {
    // 結合文字 (Mn/Me) と書式文字 (Cf) は幅を持たない。
    when (Character.getType(cp).toByte()) {
      Character.NON_SPACING_MARK,
      Character.ENCLOSING_MARK,
      Character.FORMAT -> return true
    }
    // Cf ではないゼロ幅スペースと、ハングルの字母フィラー。
    return cp == 0x200B || cp == 0x2060 || cp == 0xFEFF ||
        cp == 0x1160 || cp in 0x2060..0x2064
  }

  /** East Asian Width = W または F。 */
  private fun isWide(cp: Int): Boolean = when (cp) {
    in 0x1100..0x115F -> true   // Hangul Jamo 初声（1160 以降のフィラーは幅 0）
    in 0x231A..0x231B -> true
    in 0x2329..0x232A -> true
    in 0x23E9..0x23EC -> true
    0x23F0, 0x23F3 -> true
    in 0x25FD..0x25FE -> true
    in 0x2614..0x2615 -> true
    in 0x2648..0x2653 -> true
    0x267F, 0x2693, 0x26A1 -> true
    in 0x26AA..0x26AB -> true
    in 0x26BD..0x26BE -> true
    in 0x26C4..0x26C5 -> true
    0x26CE, 0x26D4, 0x26EA -> true
    in 0x26F2..0x26F3 -> true
    0x26F5, 0x26FA, 0x26FD, 0x2705 -> true
    in 0x270A..0x270B -> true
    0x2728, 0x274C, 0x274E -> true
    in 0x2753..0x2755 -> true
    0x2757 -> true
    in 0x2795..0x2797 -> true
    0x27B0, 0x27BF -> true
    in 0x2B1B..0x2B1C -> true
    0x2B50, 0x2B55 -> true
    in 0x2E80..0x303E -> true   // CJK 部首補助〜漢文用記号
    in 0x3041..0x33FF -> true   // かな、記号、互換
    in 0x3400..0x4DBF -> true   // CJK 拡張 A
    in 0x4E00..0x9FFF -> true   // CJK 統合漢字
    in 0xA000..0xA4CF -> true   // イ文字
    in 0xA960..0xA97F -> true   // ハングル字母拡張 A
    in 0xAC00..0xD7A3 -> true   // ハングル音節
    in 0xF900..0xFAFF -> true   // CJK 互換漢字
    in 0xFE10..0xFE19 -> true   // 縦書き用記号
    in 0xFE30..0xFE52 -> true   // CJK 互換形
    in 0xFE54..0xFE66 -> true
    in 0xFE68..0xFE6B -> true
    in 0xFF01..0xFF60 -> true   // 全角形
    in 0xFFE0..0xFFE6 -> true   // 全角記号
    in 0x16FE0..0x16FE4 -> true // タングート・女書の記号
    in 0x17000..0x18AFF -> true // タングート
    in 0x18B00..0x18CD5 -> true // 契丹小字
    in 0x1AFF0..0x1B16F -> true // 仮名補助、変体仮名、片仮名拡張
    in 0x1F004..0x1F004 -> true
    0x1F0CF -> true
    in 0x1F18E..0x1F19A -> true
    // 地域表示子 (1F1E6..1F1FF) は 1 セル。xterm/tmux に合わせる。
    in 0x1F200..0x1F320 -> true
    in 0x1F32D..0x1F335 -> true
    in 0x1F337..0x1F37C -> true
    in 0x1F37E..0x1F393 -> true
    in 0x1F3A0..0x1F3CA -> true
    in 0x1F3CF..0x1F3D3 -> true
    in 0x1F3E0..0x1F3F0 -> true
    0x1F3F4 -> true
    in 0x1F3F8..0x1F43E -> true
    0x1F440 -> true
    in 0x1F442..0x1F4FC -> true
    in 0x1F4FF..0x1F53D -> true
    in 0x1F54B..0x1F54E -> true
    in 0x1F550..0x1F567 -> true
    0x1F57A -> true
    in 0x1F595..0x1F596 -> true
    0x1F5A4 -> true
    in 0x1F5FB..0x1F64F -> true
    in 0x1F680..0x1F6C5 -> true
    0x1F6CC -> true
    in 0x1F6D0..0x1F6D2 -> true
    in 0x1F6D5..0x1F6D7 -> true
    in 0x1F6EB..0x1F6EC -> true
    in 0x1F6F4..0x1F6FC -> true
    in 0x1F7E0..0x1F7EB -> true
    in 0x1F90C..0x1F93A -> true
    in 0x1F93C..0x1F945 -> true
    in 0x1F947..0x1F9FF -> true
    in 0x1FA70..0x1FAFF -> true
    in 0x20000..0x2FFFD -> true // CJK 拡張 B〜
    in 0x30000..0x3FFFD -> true
    else -> false
  }

  /** East Asian Width = A（曖昧幅）。主要な範囲のみ。 */
  private fun isAmbiguous(cp: Int): Boolean = when (cp) {
    0x00A1, 0x00A4 -> true
    in 0x00A7..0x00A8 -> true
    0x00AA, 0x00AD, 0x00AE -> true
    in 0x00B0..0x00B4 -> true
    in 0x00B6..0x00BA -> true
    in 0x00BC..0x00BF -> true
    0x00C6, 0x00D0 -> true
    in 0x00D7..0x00D8 -> true
    in 0x00DE..0x00E1 -> true
    0x00E6 -> true
    in 0x00E8..0x00EA -> true
    in 0x00EC..0x00ED -> true
    0x00F0 -> true
    in 0x00F2..0x00F3 -> true
    in 0x00F7..0x00FA -> true
    0x00FC, 0x00FE -> true
    in 0x0391..0x03C9 -> true   // ギリシャ文字
    in 0x0401..0x044F -> true   // キリル文字
    in 0x2010..0x2027 -> true   // 約物
    in 0x2030..0x205E -> true
    in 0x2160..0x217F -> true   // ローマ数字
    in 0x2190..0x2199 -> true   // 矢印
    in 0x21D2..0x21D4 -> true
    in 0x2200..0x22FF -> true   // 数学記号
    in 0x2460..0x24FF -> true   // 囲み英数字
    in 0x2500..0x257F -> true   // 罫線素片
    in 0x2580..0x259F -> true   // ブロック要素
    in 0x25A0..0x25FF -> true   // 幾何学模様
    in 0x2600..0x26FF -> true   // その他の記号
    in 0xE000..0xF8FF -> true   // 私用領域
    in 0xFFFD..0xFFFD -> true
    in 0xF0000..0xFFFFD -> true
    in 0x100000..0x10FFFD -> true
    else -> false
  }
}
