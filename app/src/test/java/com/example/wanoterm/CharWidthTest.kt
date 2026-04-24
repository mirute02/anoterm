package com.example.wanoterm

import com.example.wanoterm.util.CharWidth
import org.junit.Assert.assertEquals
import org.junit.Test

class CharWidthTest {

  @Test
  fun asciiIsSingleWidth() {
    assertEquals(1, CharWidth.widthOf('A'.code))
    assertEquals(1, CharWidth.widthOf('a'.code))
    assertEquals(1, CharWidth.widthOf('0'.code))
    assertEquals(1, CharWidth.widthOf(' '.code))
  }

  @Test
  fun cjkIsDoubleWidth() {
    assertEquals(2, CharWidth.widthOf('日'.code))
    assertEquals(2, CharWidth.widthOf('本'.code))
    assertEquals(2, CharWidth.widthOf('語'.code))
    assertEquals(2, CharWidth.widthOf('あ'.code))
    assertEquals(2, CharWidth.widthOf('ア'.code))
    assertEquals(2, CharWidth.widthOf('가'.code)) // Hangul
  }

  @Test
  fun controlIsZeroWidth() {
    assertEquals(0, CharWidth.widthOf(0x07)) // BEL
    assertEquals(0, CharWidth.widthOf(0x1B)) // ESC
    assertEquals(0, CharWidth.widthOf(0x08)) // BS
    assertEquals(0, CharWidth.widthOf(0))
  }

  @Test
  fun combiningIsZero() {
    assertEquals(0, CharWidth.widthOf(0x0301)) // combining acute
    assertEquals(0, CharWidth.widthOf(0x200B)) // ZWSP
  }

  @Test
  fun stringWidthSumsCells() {
    assertEquals(6, CharWidth.stringWidth("日本語")) // 2*3
    assertEquals(9, CharWidth.stringWidth("hello日本")) // 5 + 2*2
    assertEquals(0, CharWidth.stringWidth(""))
  }
}
