package app.anoterm

import androidx.compose.runtime.mutableStateMapOf
import app.anoterm.ssh.TmuxWindow
import app.anoterm.ui.terminal.TmuxActivityTracker
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 「背面で何か起きた」の判定。これを間違えると、印が出っぱなしか、
 * 出るべきときに出ないかのどちらかになり、どちらでも見なくなる。
 */
class TmuxActivityTest {

  private fun tracker() = TmuxActivityTracker(mutableStateMapOf())

  private fun window(index: Int, active: Boolean, activity: Long) =
      TmuxWindow(
          session = "s",
          index = index,
          active = active,
          panes = 1,
          activity = activity,
          command = "bash",
          name = "w$index",
      )

  @Test
  fun `a window seen for the first time is not marked`() {
    // 接続した瞬間に全部へ印が付くと、印そのものが意味を失う。
    val t = tracker()
    val w = window(1, active = false, activity = 100)
    t.observe("tab", listOf(w))
    assertFalse(t.isDirty("tab", w))
  }

  @Test
  fun `output in a background window marks it`() {
    val t = tracker()
    t.observe("tab", listOf(window(1, active = false, activity = 100)))
    assertTrue(t.isDirty("tab", window(1, active = false, activity = 101)))
  }

  @Test
  fun `the window in view is never marked`() {
    val t = tracker()
    t.observe("tab", listOf(window(1, active = true, activity = 100)))
    assertFalse(t.isDirty("tab", window(1, active = true, activity = 999)))
  }

  @Test
  fun `the window in view stays read as it keeps producing output`() {
    // 見ているウィンドウは observe のたびに既読になる。そうしないと、離れた瞬間に
    // 「その間ずっと動いていた」ぶんが未読として現れる。
    val t = tracker()
    t.observe("tab", listOf(window(1, active = true, activity = 100)))
    t.observe("tab", listOf(window(1, active = true, activity = 200)))
    assertFalse(t.isDirty("tab", window(1, active = false, activity = 200)))
  }

  @Test
  fun `jumping to a window clears its mark`() {
    val t = tracker()
    t.observe("tab", listOf(window(1, active = false, activity = 100)))
    val updated = window(1, active = false, activity = 150)
    assertTrue(t.isDirty("tab", updated))
    t.markSeen("tab", updated)
    assertFalse(t.isDirty("tab", updated))
  }

  @Test
  fun `the same window index in two connections is tracked separately`() {
    val t = tracker()
    t.observe("a", listOf(window(1, active = false, activity = 100)))
    t.observe("b", listOf(window(1, active = false, activity = 100)))
    val moved = window(1, active = false, activity = 200)
    t.markSeen("a", moved)
    assertFalse(t.isDirty("a", moved))
    assertTrue(t.isDirty("b", moved))
  }
}
