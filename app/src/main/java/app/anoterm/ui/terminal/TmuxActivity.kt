package app.anoterm.ui.terminal

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.mutableStateMapOf
import app.anoterm.ssh.TmuxWindow

/**
 * 「背面のウィンドウで何か起きた」を覚えておく。
 *
 * これが無いと、切り替えたい理由（ビルドが終わった、ログが出た）が画面から読めず、
 * 一つずつ覗きに行くことになる。
 *
 * tmux の `window_activity_flag` は `monitor-activity` を on にしないと立たず、既定は
 * off。利用者の設定を勝手に変えるより、`window_activity` の時刻が前回より進んだかを
 * こちらで見る方が副作用が無い。
 */
class TmuxActivityTracker(private val seen: SnapshotStateMap<String, Long>) {

  private fun key(tabId: String, window: TmuxWindow) = tabId + "|" + window.target

  /**
   * 一覧を読み直すたびに呼ぶ。
   *
   * 初めて見るウィンドウは「見た」ことにする。接続した瞬間に全部へ印が付くと、
   * 印そのものが意味を失う。表示中のウィンドウも常に既読にする。
   */
  fun observe(tabId: String, windows: List<TmuxWindow>) {
    for (w in windows) {
      val k = key(tabId, w)
      if (w.active || k !in seen) seen[k] = w.activity
    }
  }

  /** 前回見たときより後に動きがあり、いま表示していないウィンドウ。 */
  fun isDirty(tabId: String, window: TmuxWindow): Boolean {
    if (window.active) return false
    val last = seen[key(tabId, window)] ?: return false
    return window.activity > last
  }

  /** そのウィンドウを読んだことにする。飛んだ直後に呼ぶ。 */
  fun markSeen(tabId: String, window: TmuxWindow) {
    seen[key(tabId, window)] = window.activity
  }
}

/** 画面をまたいで共有する。バーとツリーが同じ既読状態を見るために要る。 */
@Composable
fun rememberTmuxActivityTracker(): TmuxActivityTracker {
  val seen = remember { mutableStateMapOf<String, Long>() }
  return remember(seen) { TmuxActivityTracker(seen) }
}
