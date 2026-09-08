package app.anoterm

import app.anoterm.terminal.emulator.Utf8Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 端末は信頼できない相手からバイトを受け取る。不正な UTF-8 で例外が出ると、
 * 接続先が `cat /dev/urandom` を流すだけでアプリが落ちる。
 */
class Utf8DecoderHardeningTest {

  private fun decode(vararg bytes: Int): List<Int> {
    val dec = Utf8Decoder()
    val out = mutableListOf<Int>()
    for (b in bytes) {
      dec.feed(b.toByte())?.let { out.add(it) }
      dec.pending()?.let { p -> dec.feed(p)?.let { out.add(it) } }
    }
    return out
  }

  @Test
  fun `code points above U+10FFFF become the replacement character`() {
    // F7 BF BF BF は 0x1FFFFF を名乗る。Character.toChars がこれで例外を投げていた。
    assertEquals(listOf(0xFFFD, 0xFFFD, 0xFFFD, 0xFFFD), decode(0xF7, 0xBF, 0xBF, 0xBF))
  }

  @Test
  fun `F4 90 80 80 is rejected as beyond the maximum code point`() {
    assertEquals(listOf(0xFFFD), decode(0xF4, 0x90, 0x80, 0x80))
  }

  @Test
  fun `lead bytes F5 through FF are rejected`() {
    for (lead in 0xF5..0xFF) {
      assertEquals("lead byte 0x${lead.toString(16)}", listOf(0xFFFD), decode(lead))
    }
  }

  @Test
  fun `surrogate code points are rejected`() {
    // ED A0 80 = U+D800, which is not a scalar value.
    assertEquals(listOf(0xFFFD), decode(0xED, 0xA0, 0x80))
  }

  @Test
  fun `every possible byte is decodable without throwing`() {
    val dec = Utf8Decoder()
    for (b in 0..255) {
      val cp = dec.feed(b.toByte())
      if (cp != null) {
        // 返る値は常に妥当な scalar value でなければならない
        assert(cp <= 0x10FFFF) { "cp $cp out of range for byte $b" }
        assert(cp !in 0xD800..0xDFFF) { "cp $cp is a surrogate for byte $b" }
        Character.toChars(cp) // ここで例外が出ないこと
      }
      dec.pending()
    }
  }

  @Test
  fun `a byte that breaks a truncated sequence is reinterpreted, not dropped`() {
    // E6 に続けて 'A' が来たら、シーケンスは壊れるが 'A' は文字として残るべき。
    assertEquals(listOf(0xFFFD, 'A'.code), decode(0xE6, 'A'.code))
  }

  @Test
  fun `ESC after a truncated sequence survives`() {
    // ESC が失われると、以降のエスケープシーケンスが本文として描画されパーサが同期を失う。
    val dec = Utf8Decoder()
    assertNull(dec.feed(0xE6.toByte()))
    assertEquals(0xFFFD, dec.feed(0x1B.toByte()))
    assertEquals(0x1B.toByte(), dec.pending())
  }

  @Test
  fun `valid sequences still decode`() {
    assertEquals(listOf('A'.code), decode(0x41))
    assertEquals(listOf('日'.code), decode(0xE6, 0x97, 0xA5))
    assertEquals(listOf(0x1F600), decode(0xF0, 0x9F, 0x98, 0x80)) // U+1F600
  }
}
