package com.example.wanoterm

import com.example.wanoterm.terminal.view.ComposingState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ComposingStateTest {

  @Test
  fun emptyInitially() {
    val cs = ComposingState()
    assertFalse(cs.isActive)
    assertEquals("", cs.text)
    assertEquals(0, cs.cursor)
  }

  @Test
  fun setStoresTextAndCursor() {
    val cs = ComposingState()
    cs.setAbsolute("にほん", 3)
    assertTrue(cs.isActive)
    assertEquals("にほん", cs.text)
    assertEquals(3, cs.cursor)
  }

  @Test
  fun cursorClampedToRange() {
    val cs = ComposingState()
    cs.setAbsolute("ab", 5)
    assertEquals(2, cs.cursor)
    cs.setAbsolute("ab", -3)
    assertEquals(0, cs.cursor)
  }

  @Test
  fun imeCursorPositionOneMeansAfterText() {
    val cs = ComposingState()
    cs.setFromIme("ab", 1)
    assertEquals(2, cs.cursor)
  }

  @Test
  fun imeCursorPositionZeroMeansStartOfText() {
    val cs = ComposingState()
    cs.setFromIme("ab", 0)
    assertEquals(0, cs.cursor)
  }

  @Test
  fun clearResets() {
    val cs = ComposingState()
    cs.setAbsolute("abc", 2)
    cs.clear()
    assertFalse(cs.isActive)
    assertEquals("", cs.text)
    assertEquals(0, cs.cursor)
  }
}
