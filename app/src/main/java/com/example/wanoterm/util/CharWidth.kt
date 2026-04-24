package com.example.wanoterm.util

/**
 * Minimal East-Asian Width / wcwidth 相当。
 *
 * 全角 (Wide / Fullwidth) → 2 セル、それ以外の通常文字 → 1 セル、制御文字 → 0 セル。
 * ゼロ幅は結合文字・異体字セレクタのみ扱う（コア用途で十分）。サロゲート上位は呼び出し側で結合済み code point を渡すこと。
 *
 * 精密な Unicode 14 完全準拠ではなく、ターミナルのカーソル列ずれを防ぐ実用範囲を狙う。
 * 未収録範囲の拡張は必要になった時点で追加する。
 */
object CharWidth {

  /** code point のセル幅（0, 1, 2）を返す。 */
  fun widthOf(codePoint: Int): Int {
    if (codePoint == 0) return 0
    // C0 / C1 控制字符
    if (codePoint < 0x20 || (codePoint in 0x7F..0x9F)) return 0
    if (isZeroWidth(codePoint)) return 0
    if (isWide(codePoint)) return 2
    return 1
  }

  private fun isZeroWidth(cp: Int): Boolean {
    // 結合文字
    if (cp in 0x0300..0x036F) return true
    if (cp in 0x0483..0x0489) return true
    if (cp in 0x0591..0x05BD) return true
    if (cp == 0x05BF) return true
    if (cp in 0x05C1..0x05C2) return true
    if (cp in 0x05C4..0x05C5) return true
    if (cp == 0x05C7) return true
    if (cp in 0x0610..0x061A) return true
    if (cp in 0x064B..0x065F) return true
    if (cp == 0x0670) return true
    if (cp in 0x06D6..0x06DC) return true
    if (cp in 0x06DF..0x06E4) return true
    if (cp in 0x06E7..0x06E8) return true
    if (cp in 0x06EA..0x06ED) return true
    // ゼロ幅スペース系
    if (cp == 0x200B || cp == 0x200C || cp == 0x200D || cp == 0xFEFF) return true
    // 異体字セレクタ
    if (cp in 0xFE00..0xFE0F) return true
    if (cp in 0xE0100..0xE01EF) return true
    return false
  }

  private fun isWide(cp: Int): Boolean {
    // CJK 統合漢字など代表的な Wide / Fullwidth 範囲
    return when (cp) {
      in 0x1100..0x115F -> true // Hangul Jamo
      in 0x231A..0x231B -> true // Watch, Hourglass (emoji default)
      in 0x2329..0x232A -> true // angle brackets
      in 0x23E9..0x23EC -> true // black arrows
      0x23F0 -> true
      0x23F3 -> true
      in 0x25FD..0x25FE -> true // small squares (emoji)
      in 0x2614..0x2615 -> true
      in 0x2648..0x2653 -> true // zodiac
      0x267F -> true
      0x2693 -> true
      0x26A1 -> true
      in 0x26AA..0x26AB -> true
      in 0x26BD..0x26BE -> true
      in 0x26C4..0x26C5 -> true
      0x26CE -> true
      0x26D4 -> true
      0x26EA -> true
      in 0x26F2..0x26F3 -> true
      0x26F5 -> true
      0x26FA -> true
      0x26FD -> true
      0x2705 -> true
      in 0x270A..0x270B -> true
      0x2728 -> true
      0x274C -> true
      0x274E -> true
      in 0x2753..0x2755 -> true
      0x2757 -> true
      in 0x2795..0x2797 -> true
      0x27B0 -> true
      0x27BF -> true
      in 0x2B1B..0x2B1C -> true
      0x2B50 -> true
      0x2B55 -> true
      in 0x2E80..0x303E -> true // CJK Radicals etc
      in 0x3041..0x33FF -> true // Hiragana, Katakana, CJK symbols
      in 0x3400..0x4DBF -> true // CJK Ext A
      in 0x4E00..0x9FFF -> true // CJK Unified
      in 0xA000..0xA4CF -> true // Yi
      in 0xAC00..0xD7A3 -> true // Hangul Syllables
      in 0xF900..0xFAFF -> true // CJK Compat Ideographs
      in 0xFE30..0xFE4F -> true // CJK Compat Forms
      in 0xFF00..0xFF60 -> true // Fullwidth Forms
      in 0xFFE0..0xFFE6 -> true // Fullwidth Signs
      // Emoji ブロックを広めに（presentation-default = emoji のものだけ伸ばすのが本筋だが、
      // 実用上はこのブロック内の文字は全角扱いにしないと崩れる）
      in 0x1F000..0x1F02F -> true // Mahjong
      in 0x1F0A0..0x1F0FF -> true // Playing cards
      in 0x1F100..0x1F1FF -> true // Enclosed Alphanumerics / Regional indicator
      in 0x1F200..0x1F2FF -> true // Enclosed Ideographic
      in 0x1F300..0x1F64F -> true // Misc Symbols / Emoticons
      in 0x1F680..0x1F6FF -> true // Transport
      in 0x1F700..0x1F77F -> true // Alchemical
      in 0x1F780..0x1F7FF -> true // Geometric Shapes Extended
      in 0x1F800..0x1F8FF -> true // Supplemental Arrows-C
      in 0x1F900..0x1F9FF -> true // Supplemental Symbols / Emoji
      in 0x1FA00..0x1FA6F -> true // Chess Symbols
      in 0x1FA70..0x1FAFF -> true // Symbols and Pictographs Ext-A
      in 0x1FB00..0x1FBFF -> true // Symbols for Legacy Computing
      in 0x20000..0x2FFFD -> true // CJK Ext B..F
      in 0x30000..0x3FFFD -> true
      else -> false
    }
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
}
