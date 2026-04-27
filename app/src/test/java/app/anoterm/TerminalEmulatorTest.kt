package app.anoterm

import app.anoterm.terminal.emulator.AnsiColor
import app.anoterm.terminal.emulator.TerminalEmulator
import app.anoterm.terminal.emulator.TerminalOutput
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

private class NullOutput : TerminalOutput {
  val sent = mutableListOf<ByteArray>()

  override fun write(bytes: ByteArray) {
    sent.add(bytes)
  }
}

class TerminalEmulatorTest {

  private fun emu(rows: Int = 5, cols: Int = 20): Pair<TerminalEmulator, NullOutput> {
    val out = NullOutput()
    return TerminalEmulator(rows, cols, out) to out
  }

  private fun feed(e: TerminalEmulator, s: String) =
      e.feed(s.toByteArray(Charsets.UTF_8))

  @Test
  fun plainAsciiIsWritten() {
    val (e, _) = emu()
    feed(e, "hi")
    assertEquals("hi", e.buffer.rowAsString(0))
    assertEquals(0, e.cursorRow)
    assertEquals(2, e.cursorCol)
  }

  @Test
  fun crLfMovesCursor() {
    val (e, _) = emu()
    feed(e, "a\r\nb")
    assertEquals("a", e.buffer.rowAsString(0))
    assertEquals("b", e.buffer.rowAsString(1))
    assertEquals(1, e.cursorRow)
    assertEquals(1, e.cursorCol)
  }

  @Test
  fun backspaceMovesLeft() {
    val (e, _) = emu()
    feed(e, "abcX")
    assertEquals("abX", e.buffer.rowAsString(0))
  }

  @Test
  fun csiCupHomesCursor() {
    val (e, _) = emu()
    feed(e, "hello[1;1Hx")
    // "x" 上書きで先頭が x になる
    assertEquals("xello", e.buffer.rowAsString(0))
  }

  @Test
  fun csiEraseInLine() {
    val (e, _) = emu()
    feed(e, "abcdef[3G[K")
    // col3 以降消える
    assertEquals("ab", e.buffer.rowAsString(0))
  }

  @Test
  fun sgrResetAndColor() {
    val (e, _) = emu()
    feed(e, "[31mR[0mN")
    val c0 = e.buffer.cellAt(0, 0)
    val c1 = e.buffer.cellAt(0, 1)
    assertEquals(AnsiColor.Indexed(1), c0.style.fg)
    assertEquals(AnsiColor.Default, c1.style.fg)
  }

  @Test
  fun japaneseWrittenAsWide() {
    val (e, _) = emu(cols = 10)
    feed(e, "日本")
    val c0 = e.buffer.cellAt(0, 0)
    val c1 = e.buffer.cellAt(0, 1)
    val c2 = e.buffer.cellAt(0, 2)
    val c3 = e.buffer.cellAt(0, 3)
    assertEquals('日'.code, c0.codePoint)
    assertTrue(c0.wide)
    assertTrue(c1.continuation)
    assertEquals('本'.code, c2.codePoint)
    assertTrue(c2.wide)
    assertTrue(c3.continuation)
    assertEquals(4, e.cursorCol)
  }

  @Test
  fun lineFeedScrollsWhenAtBottom() {
    val (e, _) = emu(rows = 3)
    feed(e, "r0\r\nr1\r\nr2\r\nr3")
    assertEquals("r1", e.buffer.rowAsString(0))
    assertEquals("r2", e.buffer.rowAsString(1))
    assertEquals("r3", e.buffer.rowAsString(2))
  }

  @Test
  fun decsetHidesCursor() {
    val (e, _) = emu()
    feed(e, "[?25l")
    assertFalse(e.cursorVisible)
    feed(e, "[?25h")
    assertTrue(e.cursorVisible)
  }

  @Test
  fun dsrReportsCursorPosition() {
    val (e, out) = emu()
    feed(e, "ab[6n")
    assertEquals(1, out.sent.size)
    assertEquals("[1;3R", out.sent[0].toString(Charsets.US_ASCII))
  }
}
