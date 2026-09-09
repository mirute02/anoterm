package app.anoterm

import app.anoterm.ssh.TmuxController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class TmuxControllerTest {

  private val ttyVar = TmuxController.ttyVarFor("host:12")

  private fun window(
      session: String,
      index: String,
      active: String,
      panes: String,
      name: String,
      activity: String = "100",
      command: String = "bash",
  ) = listOf("W", session, index, active, panes, activity, command, name)
      .joinToString(TmuxController.SEP)

  private fun client(tty: String, session: String) =
      listOf("C", tty, session).joinToString(TmuxController.SEP)

  private fun ours(tty: String) = ttyVar + "=" + tty

  // ── 一覧の読み取り ────────────────────────────────────────────────────

  @Test
  fun `a normal listing is parsed`() {
    val out =
        listOf(
                ours("/dev/pts/3"),
                client("/dev/pts/3", "anoterm"),
                window("anoterm", "0", "1", "2", "editor"),
                window("anoterm", "1", "0", "1", "logs"),
            )
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out, ttyVar)
    assertEquals("/dev/pts/3", s.clientTty)
    assertEquals("anoterm", s.attached)
    assertEquals(2, s.windows.size)
    assertTrue(s.windows[0].active)
    assertEquals(2, s.windows[0].panes)
    assertEquals(1, s.windows[1].index)
  }

  @Test
  fun `the running command and activity time are read`() {
    // window_activity_flag は monitor-activity が on のときしか立たない。既定は off なので
    // 時刻の方を読む。pane_current_command は claude と codex の見分けに使う。
    val out = window("s", "0", "0", "1", "agent", activity = "1788967832", command = "claude")
    val w = TmuxController.parseSnapshot(out, ttyVar).windows.single()
    assertEquals(1788967832L, w.activity)
    assertEquals("claude", w.command)
  }

  @Test
  fun `the target asks tmux for an exact match`() {
    // "=" が無いと前方一致になり、似た名前の別セッションを掴む。
    val w = TmuxController.parseSnapshot(window("work", "3", "0", "1", "sh"), ttyVar).windows.single()
    assertEquals("=work:3", w.target)
  }

  @Test
  fun `a window name containing spaces or a tab survives`() {
    val s = TmuxController.parseSnapshot(window("s", "0", "1", "1", "a\tb c"), ttyVar)
    assertEquals("a\tb c", s.windows.single().name)
  }

  @Test
  fun `empty output yields nothing`() {
    val s = TmuxController.parseSnapshot("", ttyVar)
    assertTrue(s.windows.isEmpty())
    assertNull(s.attached)
    assertNull(s.clientTty)
  }

  @Test
  fun `a line with the wrong field count is dropped rather than guessed`() {
    // 読み違えて別のウィンドウを選ばせるくらいなら、その行は無いことにする。
    val out = window("s", "0", "1", "1", "ok") + "\n" + "garbage without separators"
    assertEquals("ok", TmuxController.parseSnapshot(out, ttyVar).windows.single().name)
  }

  @Test
  fun `a non-numeric window index is dropped`() {
    assertTrue(TmuxController.parseSnapshot(window("s", "x", "1", "1", "n"), ttyVar).windows.isEmpty())
  }

  // ── クライアントの特定 ────────────────────────────────────────────────

  @Test
  fun `our client is found among several by its recorded tty`() {
    // PC からも同じ tmux に繋いでいる状況。tty を刻んでいなければ判別できない。
    val out =
        listOf(
                ours("/dev/pts/9"),
                client("/dev/pts/1", "work"),
                client("/dev/pts/9", "phone"),
                window("phone", "0", "1", "1", "sh"),
                window("work", "0", "1", "1", "sh"),
            )
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out, ttyVar)
    assertEquals("/dev/pts/9", s.clientTty)
    assertEquals("phone", s.attached)
  }

  @Test
  fun `a recorded tty that is no longer attached is not trusted`() {
    val out =
        listOf(ours("/dev/pts/9"), client("/dev/pts/1", "work"), window("work", "0", "1", "1", "s"))
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out, ttyVar)
    assertNull(s.clientTty)
    // クライアントが 1 つしかないので、そちらが自分だと分かる。
    assertEquals("work", s.attached)
  }

  @Test
  fun `without a recorded tty and several clients we admit we do not know`() {
    val out =
        listOf(client("/dev/pts/1", "a"), client("/dev/pts/2", "b"), window("a", "0", "1", "1", "s"))
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out, ttyVar)
    assertNull(s.clientTty)
    assertNull(s.attached)
  }

  @Test
  fun `an unset environment variable is not read as a tty`() {
    // 未設定のとき tmux は "-VAR" を返す。これを tty と取り違えない。
    val out =
        listOf("-" + ttyVar, client("/dev/pts/1", "a"), window("a", "0", "1", "1", "s"))
            .joinToString("\n")
    assertNull(TmuxController.parseSnapshot(out, ttyVar).clientTty)
  }

  @Test
  fun `attachedWindows keeps only the session in view`() {
    val out =
        listOf(
                ours("/dev/pts/3"),
                client("/dev/pts/3", "a"),
                window("a", "0", "1", "1", "mine"),
                window("b", "0", "1", "1", "theirs"),
            )
            .joinToString("\n")
    val s = TmuxController.parseSnapshot(out, ttyVar)
    assertEquals(listOf("mine"), s.attachedWindows.map { it.name })
    assertEquals(listOf("a", "b"), s.sessions)
  }

  // ── 環境変数名 ────────────────────────────────────────────────────────

  @Test
  fun `each tab gets its own variable`() {
    // 同じホストを 2 タブで開いたとき、後から繋いだ方が前の tty を上書きしては困る。
    assertFalse(TmuxController.ttyVarFor("host:1") == TmuxController.ttyVarFor("host:2"))
  }

  @Test
  fun `the variable name is a usable shell identifier`() {
    val name = TmuxController.ttyVarFor("host:12")
    assertEquals("ANOTERM_TTY_HOST_12", name)
    assertTrue(name.all { it.isLetterOrDigit() || it == '_' })
  }

  @Test
  fun `marking records the tty of the terminal we are about to attach from`() {
    val command = TmuxController.markClientCommand(ttyVar)
    assertTrue(command.contains("set-environment -g '" + ttyVar + "'"))
    assertTrue(command.contains("$(tty)"))
    // tmux が無い環境でシェルを止めない。
    assertTrue(command.endsWith("|| true"))
  }

  // ── 名前の検査 ────────────────────────────────────────────────────────

  @Test
  fun `session names that cannot be quoted are refused`() {
    assertFalse(TmuxController.isSafeSessionName("it\'s"))
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
