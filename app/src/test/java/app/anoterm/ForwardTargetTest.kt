package app.anoterm

import app.anoterm.ui.terminal.ForwardTarget
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ForwardTargetTest {

  @Test
  fun readsHostAndPortAndKeepsTheRest() {
    val t = ForwardTarget.of("http://localhost:5173/app?x=1")
    assertEquals("localhost", t?.host)
    assertEquals(5173, t?.port)
    // 転送先はこちらの 127.0.0.1 に変わるが、パスとクエリは触らない。
    assertEquals("http://127.0.0.1:41234/app?x=1", t?.rewrite(41234))
  }

  @Test
  fun defaultsToTheHttpPort() {
    val t = ForwardTarget.of("http://dev.internal")
    assertEquals("dev.internal", t?.host)
    assertEquals(80, t?.port)
    // パスが無ければ / を補う。空のままだと 127.0.0.1:41234 が URL にならない。
    assertEquals("http://127.0.0.1:41234/", t?.rewrite(41234))
  }

  @Test
  fun leavesHttpsAlone() {
    // 転送すると宛先が 127.0.0.1 になり、証明書の名前が合わず必ず警告になる。
    assertNull(ForwardTarget.of("https://example.com/"))
  }

  @Test
  fun refusesWhatItCannotAim() {
    assertNull(ForwardTarget.of("http://"))
    assertNull(ForwardTarget.of("http://host:0/"))
    assertNull(ForwardTarget.of("ws://host/"))
    // 数字でないポートを 80 番と読み替えない。頼んでいない所に穴を開けないため。
    assertNull(ForwardTarget.of("http://host:abc/"))
  }
}
