package app.anoterm

import app.anoterm.ui.terminal.buildStartupCommand
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 接続直後に流し込むコマンド。ここはサーバー上でシェルに解釈されるので、
 * ホスト設定に入れた文字列がそのまま語として並ばないことを確かめる。
 */
class StartupCommandTest {

  private fun command(useTmux: Boolean, session: String, fullscreen: Boolean = false): String? =
      buildStartupCommand(useTmux, session, fullscreen)?.toString(Charsets.UTF_8)

  @Test
  fun `nothing to send yields null rather than a bare newline`() {
    assertNull(command(useTmux = false, session = "anoterm"))
  }

  @Test
  fun `a plain session name attaches with -A`() {
    assertEquals("tmux new -A -s 'anoterm'\r", command(useTmux = true, session = "anoterm"))
  }

  @Test
  fun `a session name with a space stays one argument`() {
    // 引用符が無いと `tmux new -A -s work log` になり、"work" という別のセッションが立つ。
    assertEquals("tmux new -A -s 'work log'\r", command(useTmux = true, session = "work log"))
  }

  @Test
  fun `a session name that cannot be quoted does not start tmux at all`() {
    val out = command(useTmux = true, session = "it's", fullscreen = true)
    assertTrue(out!!.contains("CLAUDE_CODE_NO_FLICKER=1"))
    assertFalse(out.contains("tmux"))
  }

  @Test
  fun `a non-ascii session name is not mangled`() {
    // US-ASCII で符号化していたころは '?' に潰れ、毎回別のセッションができていた。
    assertEquals("tmux new -A -s '作業'\r", command(useTmux = true, session = "作業"))
  }

  @Test
  fun `the fullscreen environment is set on the same session`() {
    val out = command(useTmux = true, session = "my session", fullscreen = true)!!
    assertTrue(out.contains("tmux set-environment -t 'my session' CLAUDE_CODE_NO_FLICKER 1"))
    assertTrue(out.contains("tmux new -A -s 'my session'"))
  }

  @Test
  fun `without tmux only the exported variable is sent`() {
    assertEquals(
        "export CLAUDE_CODE_NO_FLICKER=1\r",
        command(useTmux = false, session = "anoterm", fullscreen = true),
    )
  }
}
