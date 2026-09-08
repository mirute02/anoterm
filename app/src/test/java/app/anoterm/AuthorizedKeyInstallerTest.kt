package app.anoterm

import app.anoterm.ssh.AuthorizedKeyInstaller
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 公開鍵はサーバー上で組み立てる sh コマンドに埋め込まれる。ここを緩めると
 * 任意コマンド実行になるので、通してよい形だけを通す。
 */
class AuthorizedKeyInstallerTest {

  private fun errorOf(input: String) =
      AuthorizedKeyInstaller.validate(input).exceptionOrNull()?.message

  @Test
  fun `a normal key passes and yields its type and body`() {
    val key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample test@phone"
    val body = AuthorizedKeyInstaller.validate(key).getOrThrow()
    assertEquals("ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIExample", body)
  }

  @Test
  fun `the comment is excluded so the same key is recognised across devices`() {
    val a = AuthorizedKeyInstaller.validate("ssh-rsa AAAAB3Nz phone").getOrThrow()
    val b = AuthorizedKeyInstaller.validate("ssh-rsa AAAAB3Nz tablet").getOrThrow()
    assertEquals(a, b)
  }

  @Test
  fun `empty input is refused`() {
    assertTrue(errorOf("   ")!!.contains("空"))
  }

  @Test
  fun `multiple lines are refused`() {
    assertTrue(errorOf("ssh-ed25519 AAAA\nssh-rsa BBBB")!!.contains("複数行"))
  }

  @Test
  fun `a single quote is refused rather than escaped`() {
    // これを通すと sh の引用が閉じ、続きが命令として走る。
    val injection = "ssh-ed25519 AAAA'; rm -rf ~; echo '"
    assertTrue(errorOf(injection)!!.contains("引用符"))
  }

  @Test
  fun `a key with no body is refused`() {
    assertTrue(errorOf("ssh-ed25519")!!.contains("形式"))
  }

  @Test
  fun `surrounding whitespace does not matter`() {
    val body = AuthorizedKeyInstaller.validate("  ssh-ed25519   AAAA   note  ").getOrThrow()
    assertEquals("ssh-ed25519 AAAA", body)
  }
}
