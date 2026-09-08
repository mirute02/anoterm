package app.anoterm

import app.anoterm.ssh.TmuxController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControllerTest {

  private fun line(session: String, index: String, active: String, panes: String, name: String) =
      listOf(session, index, active, panes, name).joinToString(TmuxController.SEP)

  @Test
  fun `a normal listing is parsed`() {
    val out =
        listOf(
                line("anoterm", "0", "1", "2", "editor"),
                line("anoterm", "1", "0", "1", "logs"),
            )
            .joinToString("\n")
    val windows = TmuxController.parseWindows(out)
    assertEquals(2, windows.size)
    assertEquals("editor", windows[0].name)
    assertTrue(windows[0].active)
    assertEquals(2, windows[0].panes)
    assertFalse(windows[1].active)
    assertEquals(1, windows[1].index)
  }

  @Test
  fun `the target asks tmux for an exact match`() {
    // "=" が無いと前方一致になり、似た名前の別セッションを掴む。
    val w = TmuxController.parseWindows(line("work", "3", "0", "1", "shell")).single()
    assertEquals("=work:3", w.target)
  }

  @Test
  fun `a window name containing spaces survives`() {
    val w = TmuxController.parseWindows(line("s", "0", "1", "1", "my long name")).single()
    assertEquals("my long name", w.name)
  }

  @Test
  fun `a window name containing a tab survives`() {
    // 区切りにタブを選んでいたら、ここで名前が切れて別フィールドに化けていた。
    val w = TmuxController.parseWindows(line("s", "0", "1", "1", "a\tb")).single()
    assertEquals("a\tb", w.name)
  }

  @Test
  fun `empty output yields no windows`() {
    assertTrue(TmuxController.parseWindows("").isEmpty())
    assertTrue(TmuxController.parseWindows("\n\n").isEmpty())
  }

  @Test
  fun `trailing carriage returns are tolerated`() {
    val w = TmuxController.parseWindows(line("s", "0", "1", "1", "shell") + "\r\n").single()
    assertEquals("shell", w.name)
  }

  @Test
  fun `a line with the wrong field count is dropped rather than guessed`() {
    // 読み違えて別のウィンドウを選ばせるくらいなら、その行は無いことにする。
    val out = line("s", "0", "1", "1", "ok") + "\n" + "garbage without separators"
    val windows = TmuxController.parseWindows(out)
    assertEquals(1, windows.size)
    assertEquals("ok", windows.single().name)
  }

  @Test
  fun `a non-numeric window index is dropped`() {
    assertTrue(TmuxController.parseWindows(line("s", "x", "1", "1", "n")).isEmpty())
  }

  @Test
  fun `session names that cannot be quoted are refused`() {
    assertFalse(TmuxController.isSafeSessionName("it's"))
    assertFalse(TmuxController.isSafeSessionName(""))
    assertFalse(TmuxController.isSafeSessionName("two\nlines"))
  }

  @Test
  fun `ordinary session names are accepted, including ones with spaces`() {
    assertTrue(TmuxController.isSafeSessionName("anoterm"))
    assertTrue(TmuxController.isSafeSessionName("my session"))
    assertTrue(TmuxController.isSafeSessionName("日本語"))
  }
}
