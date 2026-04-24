package com.example.wanoterm

import com.example.wanoterm.terminal.emulator.Utf8Decoder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Utf8DecoderTest {

  private fun decodeString(s: String): List<Int> {
    val dec = Utf8Decoder()
    val out = mutableListOf<Int>()
    for (b in s.toByteArray(Charsets.UTF_8)) {
      dec.feed(b)?.let { out.add(it) }
    }
    return out
  }

  @Test
  fun ascii() {
    assertEquals(listOf('H'.code, 'i'.code), decodeString("Hi"))
  }

  @Test
  fun multibyteJapanese() {
    val cps = decodeString("日本")
    assertEquals(2, cps.size)
    assertEquals('日'.code, cps[0])
    assertEquals('本'.code, cps[1])
  }

  @Test
  fun streamingPartialBytes() {
    val dec = Utf8Decoder()
    val bytes = "日".toByteArray(Charsets.UTF_8) // 3 bytes
    assertNull(dec.feed(bytes[0]))
    assertNull(dec.feed(bytes[1]))
    assertEquals('日'.code, dec.feed(bytes[2]))
  }

  @Test
  fun invalidLeadByteYieldsReplacement() {
    val dec = Utf8Decoder()
    assertEquals(0xFFFD, dec.feed(0xFF.toByte()))
  }

  @Test
  fun truncatedSequenceRecovers() {
    val dec = Utf8Decoder()
    // 0xE6 はじまり（3 byte のはず）の途中で ASCII が来た場合
    assertNull(dec.feed(0xE6.toByte()))
    // 0x41 は継続バイトにならない → FFFD を返し、そのあとで A が再解釈される必要あり
    assertEquals(0xFFFD, dec.feed(0x41.toByte()))
    // 次に 'B' を渡すとそのまま通る
    assertEquals('B'.code, dec.feed('B'.code.toByte()))
  }
}
