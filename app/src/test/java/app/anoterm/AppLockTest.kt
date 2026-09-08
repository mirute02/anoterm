package app.anoterm

import app.anoterm.ui.lock.AppLock
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLockTest {

  @After fun tearDown() = AppLock.resetForTest()

  private fun relock(
      enabled: Boolean = true,
      backgroundedAt: Long? = 0L,
      now: Long = 0L,
      graceSeconds: Int = 30,
  ) = AppLock.shouldRelock(enabled, backgroundedAt, now, graceSeconds)

  @Test
  fun `a disabled lock never re-locks`() {
    assertFalse(relock(enabled = false, now = 10_000_000))
  }

  @Test
  fun `never having been backgrounded does not re-lock`() {
    assertFalse(relock(backgroundedAt = null, now = 10_000_000))
  }

  @Test
  fun `within the grace period it stays unlocked`() {
    assertFalse(relock(now = 29_999))
  }

  @Test
  fun `the boundary itself re-locks`() {
    assertTrue(relock(now = 30_000))
  }

  @Test
  fun `a zero grace re-locks immediately`() {
    assertTrue(relock(now = 0, graceSeconds = 0))
  }

  @Test
  fun `time running backwards re-locks rather than trusting the reading`() {
    assertTrue(relock(backgroundedAt = 5_000, now = 1_000))
  }

  @Test
  fun `the process starts locked when the lock is on`() {
    AppLock.onProcessStart(enabled = true)
    assertTrue(AppLock.locked.value)
  }

  @Test
  fun `the process starts unlocked when the lock is off`() {
    AppLock.onProcessStart(enabled = false)
    assertFalse(AppLock.locked.value)
  }

  @Test
  fun `returning after the grace period locks the app`() {
    AppLock.onProcessStart(enabled = true)
    AppLock.unlock()
    AppLock.onBackgrounded(now = 1_000)
    AppLock.onForegrounded(enabled = true, now = 61_000, graceSeconds = 30)
    assertTrue(AppLock.locked.value)
  }

  @Test
  fun `a brief switch away leaves it unlocked`() {
    AppLock.onProcessStart(enabled = true)
    AppLock.unlock()
    AppLock.onBackgrounded(now = 1_000)
    AppLock.onForegrounded(enabled = true, now = 3_000, graceSeconds = 30)
    assertFalse(AppLock.locked.value)
  }

  @Test
  fun `the grace does not accumulate across two short absences`() {
    // 20 秒離れて戻り、また 20 秒離れて戻る。どちらも猶予内なので掛からない。
    // 背面時刻を戻るたびに捨てていないと、合計 40 秒とみなして誤って掛かる。
    AppLock.onProcessStart(enabled = true)
    AppLock.unlock()
    AppLock.onBackgrounded(now = 0)
    AppLock.onForegrounded(enabled = true, now = 20_000, graceSeconds = 30)
    AppLock.onBackgrounded(now = 25_000)
    AppLock.onForegrounded(enabled = true, now = 45_000, graceSeconds = 30)
    assertFalse(AppLock.locked.value)
  }
}
