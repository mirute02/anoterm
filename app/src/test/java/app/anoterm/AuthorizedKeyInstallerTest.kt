package app.anoterm

import app.anoterm.ssh.AuthorizedKeyInstaller
import app.anoterm.ssh.AuthorizedKeyInstaller.Rejection
import app.anoterm.ssh.AuthorizedKeyInstaller.Validation
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * 公開鍵はサーバー上で組み立てる sh コマンドに埋め込まれる。ここを緩めると
 * 任意コマンド実行になるので、通してよい形だけを通す。
 */
class AuthorizedKeyInstallerTest {

  private fun bodyOf(input: String): String =
      (AuthorizedKeyInstaller.validate(input) as Validation.Valid).keyBody

  private fun rejectionOf(input: String): Rejection =
      (AuthorizedKeyInstaller.validate(input) as Validation.Invalid).rejection

  @Test
  fun `a normal key passes and yields its type and body`() {
    val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample test@phone"
    assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample", bodyOf(key))
  }

  @Test
  fun `the comment is excluded so the same key is recognised across devices`() {
    assertEquals(bodyOf("ssh-rsa AAAAB3Nz phone"), bodyOf("ssh-rsa AAAAB3Nz tablet"))
  }

  @Test
  fun `empty input is refused`() {
    assertEquals(Rejection.EMPTY, rejectionOf("   "))
  }

  @Test
  fun `multiple lines are refused`() {
    assertEquals(Rejection.MULTIPLE_LINES, rejectionOf("ssh-ed25519 AAAA\nssh-rsa BBBB"))
  }

  @Test
  fun `a single quote is refused rather than escaped`() {
    // これを通すと sh の引用が閉じ、続きが命令として走る。
    val injection = "ssh-ed25519 AAAA\'; rm -rf ~; echo \'"
    assertEquals(Rejection.CONTAINS_QUOTE, rejectionOf(injection))
  }

  @Test
  fun `a key with no body is refused`() {
    assertEquals(Rejection.MALFORMED, rejectionOf("ssh-ed25519"))
  }

  @Test
  fun `surrounding whitespace does not matter`() {
    assertEquals("ssh-ed25519 AAAA", bodyOf("  ssh-ed25519   AAAA   note  "))
  }
}
