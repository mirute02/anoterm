package app.anoterm.ui.terminal

import app.anoterm.ssh.SshChannel
import app.anoterm.ssh.TmuxController
import app.anoterm.ssh.TmuxListing
import app.anoterm.ssh.TmuxSnapshot
import app.anoterm.ssh.TmuxWindow
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/** 1 本の SSH 接続と、その先で見えている tmux。 */
data class TmuxTreeConnection(
    val tabId: String,
    val label: String,
    /** tmux が読めなかった接続は null。接続自体は生きているので行としては出す。 */
    val snapshot: TmuxSnapshot?,
    /**
     * 読めなかった理由。tmux が入っていない、サーバーが動いていない、PATH に無い、など。
     *
     * これを捨てて一律「tmux なし」と出していたせいで、原因が何ひとつ分からなかった。
     * `TmuxController.snapshot` はリモートが返した一行をそのまま持たせてくれている。
     */
    val unavailableReason: String? = null,
) {
  /** セッション名 → そのウィンドウ。表示順は tmux が返した順。 */
  val sessions: List<Pair<String, List<TmuxWindow>>>
    get() =
        snapshot?.windows.orEmpty().groupBy { it.session }.toList().map { (name, windows) ->
          name to windows.sortedBy { it.index }
        }
}

/** どのウィンドウへ飛ぶかの指定。 */
data class TmuxJump(val tabId: String, val window: TmuxWindow)

/**
 * 開いている SSH 接続すべてから tmux の状態を集める。
 *
 * 接続 1 本につき exec を 1 回開くので、常時ではなく一覧を開いた瞬間だけ呼ぶ。
 * 直列に回すと接続数だけ待たされるため並列に投げる。
 *
 * [channels] は tabId → チャネル。SSH でない接続（ループバック）は呼び出し側で除く。
 */
suspend fun collectTmuxTree(
    channels: List<Triple<String, String, SshChannel>>,
): List<TmuxTreeConnection> = coroutineScope {
  channels
      .map { (tabId, label, channel) ->
        async {
          val listing = TmuxController.snapshot(channel, TmuxController.ttyVarFor(tabId))
          TmuxTreeConnection(
              tabId = tabId,
              label = label,
              snapshot = (listing as? TmuxListing.Ok)?.snapshot,
              unavailableReason = (listing as? TmuxListing.Unavailable)?.reason,
          )
        }
      }
      .map { it.await() }
}
