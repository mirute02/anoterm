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

  /** scrollback の下から [fromBottom] 行目を文字列で読む。0 が画面直上の行。 */
  private fun scrollbackLine(e: TerminalEmulator, fromBottom: Int): String {
    val sb = StringBuilder()
    for (c in 0 until e.buffer.cols) {
      val cell = e.buffer.scrollbackCellAt(fromBottom, c) ?: break
      if (cell.continuation) continue
      if (cell.codePoint == 0) sb.append(' ') else sb.appendCodePoint(cell.codePoint)
    }
    return sb.toString().trimEnd()
  }

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
  fun scrollbackLimitTrimsHistory() {
    // メモリバジェット管理（F-13）が使う setScrollbackLimit の挙動を固定する。
    val (e, _) = emu(rows = 5, cols = 20)
    // 画面(5 行)を大きく超えて出力し、履歴を貯める。
    repeat(500) { feed(e, "row$it\r\n") }
    assertTrue("history should accumulate", e.buffer.scrollbackSize > 300)

    // 上限を 250 に締めると即座に古い行が捨てられる。
    e.setScrollbackLimit(250)
    assertEquals(250, e.buffer.scrollbackSize)

    // 以降の出力でも上限を超えない。
    repeat(100) { feed(e, "more$it\r\n") }
    assertTrue("stays within limit", e.buffer.scrollbackSize <= 250)
  }

  @Test
  fun scrollbackLimitClampsBelowMinimum() {
    // 極端に小さい値でも MIN_MAX_SCROLLBACK 未満には潰さない（実用上の下限を保証）。
    val (e, _) = emu(rows = 5, cols = 20)
    repeat(500) { feed(e, "row$it\r\n") }
    e.setScrollbackLimit(1)
    assertEquals(
        app.anoterm.terminal.emulator.TerminalBuffer.MIN_MAX_SCROLLBACK,
        e.buffer.scrollbackSize,
    )
  }

  @Test
  fun dsrReportsCursorPosition() {
    val (e, out) = emu()
    feed(e, "ab[6n")
    assertEquals(1, out.sent.size)
    assertEquals("[1;3R", out.sent[0].toString(Charsets.US_ASCII))
  }

  // --- resize で「読んでいる場所」が動かないこと ---
  //
  // キーボードの出し入れは端末の行数を上下させる。以前は縮む側で上端の行をそのまま
  // 捨て、広がる側は空行で埋めていたので、開閉のたびに本文が飛び、開く前に見えていた
  // 行は二度と戻らなかった。縮むときは scrollback へ送り、広がるときは引き戻す。

  @Test
  fun shrinkingPushesTheTopRowsIntoScrollback() {
    val (e, _) = emu(rows = 5, cols = 20)
    feed(e, "a\r\nb\r\nc\r\nd\r\ne")
    assertEquals(4, e.cursorRow)

    val shift = e.resize(3, 20)

    // 溢れた 2 行は消えずに scrollback の底へ積まれる。
    assertEquals(2, shift)
    assertEquals(2, e.buffer.scrollbackSize)
    assertEquals("b", scrollbackLine(e, 0))
    assertEquals("a", scrollbackLine(e, 1))
    // 画面にはカーソルを含む下側が残る。
    assertEquals("c", e.buffer.rowAsString(0))
    assertEquals("d", e.buffer.rowAsString(1))
    assertEquals("e", e.buffer.rowAsString(2))
    assertEquals(2, e.cursorRow)
  }

  @Test
  fun growingPullsThoseRowsBackOut() {
    val (e, _) = emu(rows = 5, cols = 20)
    feed(e, "a\r\nb\r\nc\r\nd\r\ne")
    e.resize(3, 20)

    val shift = e.resize(5, 20)

    // 空行が下に生えるのではなく、送った 2 行が上に戻る。開閉して元通り。
    assertEquals(-2, shift)
    assertEquals(0, e.buffer.scrollbackSize)
    assertEquals("a", e.buffer.rowAsString(0))
    assertEquals("b", e.buffer.rowAsString(1))
    assertEquals("c", e.buffer.rowAsString(2))
    assertEquals("d", e.buffer.rowAsString(3))
    assertEquals("e", e.buffer.rowAsString(4))
    assertEquals(4, e.cursorRow)
  }

  @Test
  fun growingBeyondScrollbackJustAddsBlankRows() {
    val (e, _) = emu(rows = 3, cols = 20)
    feed(e, "a\r\nb\r\nc")

    val shift = e.resize(5, 20)

    // 引き戻せる履歴が無ければ従来どおり。上端は動かない。
    assertEquals(0, shift)
    assertEquals("a", e.buffer.rowAsString(0))
    assertEquals("", e.buffer.rowAsString(4))
    assertEquals(2, e.cursorRow)
  }

  @Test
  fun alternateScreenNeverTouchesScrollback() {
    val (e, _) = emu(rows = 5, cols = 20)
    feed(e, "\u001B[?1049h")
    feed(e, "a\r\nb\r\nc\r\nd\r\ne")

    val shift = e.resize(3, 20)

    // vim/tmux の作業画面は履歴を汚さない (VT100 仕様)。補正するものも無い。
    assertEquals(0, shift)
    assertEquals(0, e.buffer.scrollbackSize)
  }
}
