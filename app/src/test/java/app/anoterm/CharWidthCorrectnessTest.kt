package app.anoterm

import app.anoterm.util.CharWidth
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 幅計算はこのアプリの存在理由なので、以前に誤っていた具体的な code point を
 * 名指しで固定する。範囲表を手で書くと必ず抜けるため、回帰はここで捕まえる。
 */
class CharWidthCorrectnessTest {

  @After fun reset() { CharWidth.ambiguousWide = false }

  private fun w(cp: Int) = CharWidth.widthOf(cp)

  @Test
  fun `combining marks are zero width`() {
    // NFD 分解された日本語。macOS 由来のファイル名で頻出し、幅 2 だと桁がずれていた。
    assertEquals("U+3099 combining voiced mark", 0, w(0x3099))
    assertEquals("U+309A combining semi-voiced", 0, w(0x309A))
    assertEquals("U+302A ideographic tone mark", 0, w(0x302A))
    assertEquals("U+0E31 Thai vowel", 0, w(0x0E31))
    assertEquals("U+1AB0 combining diacritical ext", 0, w(0x1AB0))
    assertEquals("U+20D0 combining for symbols", 0, w(0x20D0))
    assertEquals("U+FE20 half mark", 0, w(0xFE20))
    assertEquals("U+0300 grave accent", 0, w(0x0300))
  }

  @Test
  fun `format characters are zero width`() {
    assertEquals("U+200E LRM", 0, w(0x200E))
    assertEquals("U+200F RLM", 0, w(0x200F))
    assertEquals("U+00AD soft hyphen", 0, w(0x00AD))
    assertEquals("U+2060 word joiner", 0, w(0x2060))
    assertEquals("U+200B zero width space", 0, w(0x200B))
    assertEquals("U+FEFF BOM", 0, w(0xFEFF))
    assertEquals("U+200D ZWJ", 0, w(0x200D))
  }

  @Test
  fun `east asian wide ranges that were previously missed`() {
    assertEquals("U+FE10 vertical comma", 2, w(0xFE10))
    assertEquals("U+A960 hangul jamo ext-A", 2, w(0xA960))
    assertEquals("U+17000 Tangut", 2, w(0x17000))
    assertEquals("U+1B000 hentaigana", 2, w(0x1B000))
  }

  @Test
  fun `regional indicators are one cell each`() {
    // 2 つで国旗になるが、それぞれ 2 セルにすると xterm/tmux では 4 セルずれる。
    assertEquals(1, w(0x1F1E6))
    assertEquals(1, w(0x1F1EF)) // J
    assertEquals(1, w(0x1F1F5)) // P
    assertEquals("🇯🇵 は 2 セル", 2, CharWidth.stringWidth("🇯🇵"))
  }

  @Test
  fun `ordinary cases still hold`() {
    assertEquals(1, w('A'.code))
    assertEquals(2, w('日'.code))
    assertEquals(2, w('あ'.code))
    assertEquals(2, w('한'.code))
    assertEquals(1, w('ｱ'.code))        // 半角カタカナ
    assertEquals(0, w(0x1B))            // ESC
    assertEquals(0, w(0x00))
  }

  @Test
  fun `ambiguous width follows the setting`() {
    CharWidth.ambiguousWide = false
    assertEquals("α narrow by default", 1, w(0x03B1))
    assertEquals("box drawing narrow by default", 1, w(0x2500))
    CharWidth.ambiguousWide = true
    assertEquals("α wide when enabled", 2, w(0x03B1))
    assertEquals("box drawing wide when enabled", 2, w(0x2500))
    // 曖昧幅の設定は Wide/Narrow の確定した文字には影響しない
    assertEquals(2, w('日'.code))
    assertEquals(1, w('A'.code))
  }

  @Test
  fun `stringWidth sums code points, not chars`() {
    assertEquals(4, CharWidth.stringWidth("日本"))
    assertEquals(6, CharWidth.stringWidth("ab日本"))
    // NFD の「が」= か + 濁点 → 2 セル（濁点は 0）
    assertEquals(2, CharWidth.stringWidth("が"))
    // サロゲートペア 1 文字
    assertEquals(2, CharWidth.stringWidth("𠮟")) // U+20B9F CJK Ext-B
  }
}
