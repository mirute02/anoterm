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
    assertTrue(command(useTmux = true, session = "anoterm")!!.endsWith("tmux new -A -s 'anoterm'\r"))
  }

  @Test
  fun `a session name with a space stays one argument`() {
    // 引用符が無いと `tmux new -A -s work log` になり、"work" という別のセッションが立つ。
    assertTrue(command(useTmux = true, session = "work log")!!.endsWith("tmux new -A -s 'work log'\r"))
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
    assertTrue(command(useTmux = true, session = "作業")!!.endsWith("tmux new -A -s '作業'\r"))
  }

  @Test
  fun `the fullscreen environment is set on the same session`() {
    val out = command(useTmux = true, session = "my session", fullscreen = true)!!
    assertTrue(out.contains("tmux set-environment -t 'my session' CLAUDE_CODE_NO_FLICKER 1"))
    assertTrue(out.contains("tmux new -A -s 'my session'"))
  }

  @Test
  fun `attaching records the tty so the client can be found later`() {
    // これが無いと、外側の exec から「どのクライアントが自分か」を決められず、
    // アタッチ先の切り替えが別の端末に効いてしまう。
    val out =
        buildStartupCommand(true, "anoterm", false, "ANOTERM_TTY_HOST_1")!!
            .toString(Charsets.UTF_8)
    val mark = out.indexOf("set-environment -g 'ANOTERM_TTY_HOST_1'")
    val attach = out.indexOf("tmux new -A -s 'anoterm'")
    assertTrue(mark >= 0)
    // アタッチすると tmux がその端末を掴んで戻ってこないので、刻むのは先。
    assertTrue(mark < attach)
  }

  @Test
  fun `hiding the tmux status line is asked for before attaching`() {
    val out =
        buildStartupCommand(true, "work", false, "ANOTERM_TTY_X", hideTmuxStatus = true)!!
            .toString(Charsets.UTF_8)
    val set = out.indexOf("status off")
    val attach = out.indexOf("tmux new -A -s 'work'")
    assertTrue(set in 0 until attach)
  }

  @Test
  fun `showing it again unsets the session option instead of forcing it on`() {
    // "on" を書き込むと、.tmux.conf で自分から消している利用者の設定を上書きする。
    val out =
        buildStartupCommand(true, "work", false, "ANOTERM_TTY_X", hideTmuxStatus = false)!!
            .toString(Charsets.UTF_8)
    assertTrue(out.contains("set-option -u -t '=work' status"))
    assertFalse(out.contains("status on"))
  }

  @Test
  fun `without tmux nothing is recorded`() {
    val out = command(useTmux = false, session = "anoterm", fullscreen = true)
    assertFalse(out!!.contains("set-environment"))
  }

  @Test
  fun `without tmux only the exported variable is sent`() {
    assertEquals(
        "export CLAUDE_CODE_NO_FLICKER=1\r",
        command(useTmux = false, session = "anoterm", fullscreen = true),
    )
  }
}
