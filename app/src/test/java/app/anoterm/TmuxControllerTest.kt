package app.anoterm

import app.anoterm.ssh.TmuxController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControllerTest {

  private fun window(session: String, index: String, active: String, panes: String, name: String) =
      listOf("W", session, index, active, panes, name).joinToString(TmuxController.SEP)

  private fun client(session: String) = listOf("C", session).joinToString(TmuxController.SEP)

  // ── 一覧の読み取り ────────────────────────────────────────────────────

  @Test
  fun `a normal listing is parsed`() {
    val out =
        listOf(
                client("anoterm"),
                window("anoterm", "0", "1", "2", "editor"),
                window("anoterm", "1", "0", "1", "logs"),
            )
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out)
    assertEquals("anoterm", s.attached)
    assertEquals(2, s.windows.size)
    assertEquals("editor", s.windows[0].name)
    assertTrue(s.windows[0].active)
    assertEquals(2, s.windows[0].panes)
    assertEquals(1, s.windows[1].index)
  }

  @Test
  fun `the target asks tmux for an exact match`() {
    // "=" が無いと前方一致になり、似た名前の別セッションを掴む。
    val w = TmuxController.parseSnapshot(window("work", "3", "0", "1", "shell")).windows.single()
    assertEquals("=work:3", w.target)
  }

  @Test
  fun `a window name containing spaces or a tab survives`() {
    val s = TmuxController.parseSnapshot(window("s", "0", "1", "1", "a\tb c"))
    assertEquals("a\tb c", s.windows.single().name)
  }

  @Test
  fun `empty output yields nothing`() {
    val s = TmuxController.parseSnapshot("")
    assertTrue(s.windows.isEmpty())
    assertNull(s.attached)
  }

  @Test
  fun `trailing carriage returns are tolerated`() {
    val s = TmuxController.parseSnapshot(window("s", "0", "1", "1", "shell") + "\r\n")
    assertEquals("shell", s.windows.single().name)
  }

  @Test
  fun `a line with the wrong field count is dropped rather than guessed`() {
    // 読み違えて別のウィンドウを選ばせるくらいなら、その行は無いことにする。
    val out = window("s", "0", "1", "1", "ok") + "\n" + "garbage without separators"
    assertEquals("ok", TmuxController.parseSnapshot(out).windows.single().name)
  }

  @Test
  fun `a non-numeric window index is dropped`() {
    assertTrue(TmuxController.parseSnapshot(window("s", "x", "1", "1", "n")).windows.isEmpty())
  }

  // ── アタッチ先の判定 ──────────────────────────────────────────────────

  @Test
  fun `one client means that client is us`() {
    val out = client("work") + "\n" + window("work", "0", "1", "1", "sh")
    assertEquals("work", TmuxController.parseSnapshot(out).attached)
  }

  @Test
  fun `with several clients we fall back to what the app remembers`() {
    // PC からも同じ tmux に繋いでいる状況。どれが自分かは出力から分からない。
    val out =
        listOf(client("work"), client("other"), window("other", "0", "1", "1", "sh"))
            .joinToString("\n")
    assertEquals("other", TmuxController.parseSnapshot(out, fallbackAttached = "other").attached)
  }

  @Test
  fun `a remembered session that no longer exists is not trusted`() {
    val out = listOf(client("a"), client("b"), window("a", "0", "1", "1", "sh")).joinToString("\n")
    assertNull(TmuxController.parseSnapshot(out, fallbackAttached = "gone").attached)
  }

  @Test
  fun `attachedWindows keeps only the session in view`() {
    val out =
        listOf(
                client("a"),
                window("a", "0", "1", "1", "mine"),
                window("b", "0", "1", "1", "theirs"),
            )
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out)
    assertEquals(listOf("mine"), s.attachedWindows.map { it.name })
    assertEquals(listOf("a", "b"), s.sessions)
  }

  // ── アタッチの切り替え ────────────────────────────────────────────────

  @Test
  fun `switching attachment goes through the tmux command prompt`() {
    // exec から switch-client を投げるとどのクライアントが動くか指定できず、
    // PC からも繋いでいるとそちらが切り替わる。prefix + ":" なら自分に限られる。
    val keys = TmuxController.buildSwitchClientKeys("work")!!
    assertEquals(TmuxController.PREFIX, keys[0])
    assertEquals(":switch-client -t '=work'\r", keys.drop(1).toByteArray().toString(Charsets.UTF_8))
  }

  @Test
  fun `a session name with a space stays one argument`() {
    val keys = TmuxController.buildSwitchClientKeys("my session")!!
    assertTrue(keys.toString(Charsets.UTF_8).contains("-t '=my session'"))
  }

  @Test
  fun `a session name that cannot be quoted is refused`() {
    assertNull(TmuxController.buildSwitchClientKeys("it's"))
    assertNull(TmuxController.buildSwitchClientKeys(""))
  }

  // ── 名前の検査 ────────────────────────────────────────────────────────

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
    assertTrue(TmuxController.isSafeSessionName("\u65e5\u672c\u8a9e"))
  }
}
