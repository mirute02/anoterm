package app.anoterm.ui.lock

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * アプリロックの状態。
 *
 * ロックはこれまで起動直後の画面を選ぶだけで、一度解錠すると背面に回して戻っても
 * 二度と掛からなかった。守れていたのはコールドスタートだけで、端末を人に渡す・
 * 置き忘れるといった実際に困る場面では役に立たない。
 *
 * ここでは前面復帰のたびに判定し、背面にいた時間が猶予を超えていれば掛け直す。
 *
 * 時刻は [android.os.SystemClock.elapsedRealtime] を使う。壁時計だと端末の時刻を
 * 進めるだけで猶予を回避できてしまう。
 */
object AppLock {

  private val _locked = MutableStateFlow(false)
  val locked: StateFlow<Boolean> = _locked.asStateFlow()

  private var backgroundedAt: Long? = null

  /**
   * 掛け直すべきか。
   *
   * [backgroundedAt] が null なのは一度も背面に回っていない場合で、そのときは掛けない。
   * 経過が負になるのは計測が壊れているときなので、その場合は掛ける側に倒す。
   */
  fun shouldRelock(
      enabled: Boolean,
      backgroundedAt: Long?,
      now: Long,
      graceSeconds: Int,
  ): Boolean {
    if (!enabled) return false
    if (backgroundedAt == null) return false
    if (graceSeconds <= 0) return true
    val elapsed = now - backgroundedAt
    if (elapsed < 0) return true
    return elapsed >= graceSeconds * 1000L
  }

  /** プロセス起動時。ロックが有効なら掛かった状態から始める。 */
  fun onProcessStart(enabled: Boolean) {
    _locked.value = enabled
    backgroundedAt = null
  }

  fun onBackgrounded(now: Long) {
    // 既にロック中なら、背面時刻を上書きしても意味がないが害もない。
    backgroundedAt = now
  }

  fun onForegrounded(enabled: Boolean, now: Long, graceSeconds: Int) {
    if (shouldRelock(enabled, backgroundedAt, now, graceSeconds)) _locked.value = true
    backgroundedAt = null
  }

  fun unlock() {
    _locked.value = false
    backgroundedAt = null
  }

  /** テスト用。object なので状態が持ち越されるのを防ぐ。 */
  internal fun resetForTest() {
    _locked.value = false
    backgroundedAt = null
  }
}
