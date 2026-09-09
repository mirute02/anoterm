package app.anoterm.ui.terminal

import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.anoterm.R
import app.anoterm.ssh.SshChannel
import app.anoterm.ssh.TmuxController
import app.anoterm.ssh.TmuxListing
import app.anoterm.ssh.TmuxSnapshot
import kotlinx.coroutines.launch

/**
 * tmux の操作パネル。
 *
 * かつては prefix + キーのバイト列を端末へ流していた。押した結果がサーバーに届くかは
 * その瞬間に前面のアプリが Ctrl-B を横取りしていないかに左右され、prefix を Ctrl-A に
 * 変えている利用者には最初から効かなかった。
 *
 * いまは `tmux` コマンドを別セッションで実行する。押した通りに効き、利用者の画面には
 * 何も出ない。tmux の設定にも依存しない。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxPanel(
    channel: SshChannel,
    /** クライアント特定用の環境変数名。[TmuxController.ttyVarFor] が作る。 */
    ttyVar: String,
    onDismiss: () -> Unit,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  val scope = rememberCoroutineScope()
  var snapshot by remember { mutableStateOf<TmuxSnapshot?>(null) }
  var failed by remember { mutableStateOf(false) }
  var renaming by remember { mutableStateOf<RenameTarget?>(null) }
  var confirming by remember { mutableStateOf<Confirm?>(null) }
  var nonce by remember { mutableStateOf(0) }

  LaunchedEffect(channel, nonce) {
    snapshot = (TmuxController.snapshot(channel, ttyVar) as? TmuxListing.Ok)?.snapshot
  }

  /** コマンドを撃って読み直す。ボタンはすべてこれを通る。 */
  fun act(args: String) {
    scope.launch {
      failed = !TmuxController.run(channel, args)
      nonce++
    }
  }

  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      val current = snapshot
      val window = current?.attachedWindows?.firstOrNull { it.active }
      val session = current?.attached

      if (window == null || session == null) {
        Text(
            text = stringResource(R.string.tmux_no_window),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )
        Spacer(Modifier.height(16.dp))
        return@Column
      }

      // 対象は「いま見ているウィンドウ」。= を付けて完全一致にする。
      val w = TmuxController.quote(window.target)
      val s = TmuxController.quote("=" + session)

      Text(
          text = stringResource(R.string.tmux_touch_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 8.dp),
      )
      if (failed) {
        Text(
            text = stringResource(R.string.tmux_failed),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
      }

      Group(R.string.tmux_window_group) {
        Action(R.string.tmux_new_window) { act("new-window -t " + s) }
        Action(R.string.tmux_next_window) { act("next-window -t " + s) }
        Action(R.string.tmux_prev_window) { act("previous-window -t " + s) }
        Action(R.string.tmux_rename_window) {
          renaming = RenameTarget(target = w, current = window.name, isSession = false)
        }
        Action(R.string.tmux_close_window) {
          confirming =
              Confirm(
                  message = R.string.tmux_close_window_confirm,
                  argument = window.name,
                  command = "kill-window -t " + w,
              )
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      Group(R.string.tmux_pane_group) {
        Action(R.string.tmux_split_vertical) { act("split-window -h -t " + w) }
        Action(R.string.tmux_split_horizontal) { act("split-window -v -t " + w) }
        Action(R.string.tmux_next_pane) {
          // ペインの指定は session:window.pane。".+" が「次のペイン」。
          act("select-pane -t " + TmuxController.quote(window.target + ".+"))
        }
        Action(R.string.tmux_zoom_pane) { act("resize-pane -Z -t " + w) }
        Action(R.string.tmux_close_pane) {
          confirming =
              Confirm(
                  message = R.string.tmux_close_pane_confirm,
                  argument = null,
                  command = "kill-pane -t " + w,
              )
        }
      }
      Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        DIRECTIONS.forEach { (label, flag) ->
          OutlinedButton(onClick = { act("select-pane " + flag + " -t " + w) }, modifier = Modifier.weight(1f)) {
            Text(label, style = MaterialTheme.typography.labelSmall)
          }
        }
      }

      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

      Group(R.string.tmux_session_group) {
        Action(R.string.tmux_rename_session) {
          renaming = RenameTarget(target = s, current = session, isSession = true)
        }
        Action(R.string.tmux_copy_mode) { act("copy-mode -t " + w) }
        Action(R.string.tmux_paste) { act("paste-buffer -t " + w) }
        current.clientTty?.let { tty ->
          Action(R.string.tmux_detach) { act("detach-client -t " + TmuxController.quote(tty)) }
        }
      }
      Spacer(Modifier.height(24.dp))
    }
  }

  renaming?.let { target ->
    RenameDialog(
        initial = target.current,
        onDismiss = { renaming = null },
        onConfirm = { name ->
          renaming = null
          if (TmuxController.isSafeSessionName(name)) {
            val verb = if (target.isSession) "rename-session" else "rename-window"
            act(verb + " -t " + target.target + " " + TmuxController.quote(name))
          } else {
            failed = true
          }
        },
    )
  }

  confirming?.let { c ->
    AlertDialog(
        onDismissRequest = { confirming = null },
        title = { Text(stringResource(R.string.tmux_close_window)) },
        text = {
          Text(
              if (c.argument != null) stringResource(c.message, c.argument)
              else stringResource(c.message),
          )
        },
        confirmButton = {
          TextButton(
              onClick = {
                confirming = null
                act(c.command)
              },
          ) {
            Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
          }
        },
        dismissButton = {
          TextButton(onClick = { confirming = null }) {
            Text(stringResource(R.string.action_cancel))
          }
        },
    )
  }
}

private data class RenameTarget(val target: String, val current: String, val isSession: Boolean)

private data class Confirm(
    @StringRes val message: Int,
    val argument: String?,
    val command: String,
)

/** 方向キーは記号なので翻訳しない。`select-pane -L` などにそのまま対応する。 */
private val DIRECTIONS =
    listOf("←" to "-L", "↓" to "-D", "↑" to "-U", "→" to "-R")

@Composable
private fun Group(@StringRes title: Int, content: @Composable () -> Unit) {
  Text(
      text = stringResource(title),
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 6.dp),
  )
  content()
}

@Composable
private fun Action(@StringRes label: Int, onClick: () -> Unit) {
  OutlinedButton(onClick = onClick, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
    Text(stringResource(label), style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun RenameDialog(initial: String, onDismiss: () -> Unit, onConfirm: (String) -> Unit) {
  var text by remember { mutableStateOf(initial) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text(stringResource(R.string.tmux_rename_title)) },
      text = {
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        TextButton(onClick = { onConfirm(text.trim()) }) {
          Text(stringResource(R.string.action_save))
        }
      },
      dismissButton = {
        TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) }
      },
  )
}
