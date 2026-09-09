package app.anoterm.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ElevatedFilterChip
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
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
import androidx.annotation.StringRes
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import app.anoterm.R
import app.anoterm.ssh.SshChannel
import app.anoterm.ssh.TmuxController
import app.anoterm.ssh.TmuxListing
import app.anoterm.ssh.TmuxWindow
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * tmux 操作用ダッシュボードの MVP 版。
 *
 * 現状は：prefix 系ショートカットをボタンで送出するランチャー。
 * ボタンを押すと `prefix + <key>` のバイト列を送信する。
 *
 * 本格的な iTerm 風ダッシュボード（ウィンドウ/ペインのリアルタイム一覧とプレビュー）は、
 * `tmux list-windows -F '#I:#W'` などを発行→結果を端末バッファから抽出・パースする
 * サブシステムが必要で規模が大きいので v2 で。
 *
 * prefix はデフォルトで Ctrl-B（0x02）。将来的に設定で変更可に。
 */
private const val TMUX_PREFIX: Byte = 0x02 // Ctrl-B

/** ボタン 1 つ。[label] は表示名のリソース ID（トップレベルの val からは引けないため）。 */
private data class TmuxAction(@StringRes val label: Int, val keys: ByteArray) {
  override fun equals(other: Any?): Boolean = other is TmuxAction && label == other.label
  override fun hashCode(): Int = label.hashCode()
}

private fun actionWithPrefix(@StringRes label: Int, vararg after: Byte): TmuxAction =
    TmuxAction(label, byteArrayOf(TMUX_PREFIX) + after)

private val WINDOW_ACTIONS =
    listOf(
        actionWithPrefix(R.string.tmux_new_window, 'c'.code.toByte()),
        actionWithPrefix(R.string.tmux_next_window, 'n'.code.toByte()),
        actionWithPrefix(R.string.tmux_prev_window, 'p'.code.toByte()),
        actionWithPrefix(R.string.tmux_list_windows, 'w'.code.toByte()),
        actionWithPrefix(R.string.tmux_rename_window, ','.code.toByte()),
        actionWithPrefix(R.string.tmux_close_window, '&'.code.toByte()),
    )

private val PANE_ACTIONS =
    listOf(
        actionWithPrefix(R.string.tmux_split_vertical, '%'.code.toByte()),
        actionWithPrefix(R.string.tmux_split_horizontal, '"'.code.toByte()),
        actionWithPrefix(R.string.tmux_next_pane, 'o'.code.toByte()),
        actionWithPrefix(R.string.tmux_zoom_pane, 'z'.code.toByte()),
        actionWithPrefix(R.string.tmux_number_panes, 'q'.code.toByte()),
        actionWithPrefix(R.string.tmux_close_pane, 'x'.code.toByte()),
    )

/**
 * 記号だけのボタン。矢印や番号は翻訳しないので、リソースではなく文字列を直接持つ。
 */
private data class TmuxKey(val label: String, val keys: ByteArray) {
  override fun equals(other: Any?): Boolean = other is TmuxKey && label == other.label

  override fun hashCode(): Int = label.hashCode()
}

/**
 * pane 方向ジャンプ：prefix + 矢印。tmux の左右上下分割で隣の pane に移動する。
 *
 * 矢印キーは ESC [ A の 3 バイトで、先頭の ESC が要る。これが抜けていたので
 * 送っていたのは prefix + `[` + `A`、つまり tmux のコピーモードに入る操作だった。
 */
private val PANE_DIRECTIONS: List<TmuxKey> =
    listOf(
        TmuxKey("pane \u2191", byteArrayOf(TMUX_PREFIX) + "\u001B[A".toByteArray(Charsets.US_ASCII)),
        TmuxKey("pane \u2193", byteArrayOf(TMUX_PREFIX) + "\u001B[B".toByteArray(Charsets.US_ASCII)),
        TmuxKey("pane \u2190", byteArrayOf(TMUX_PREFIX) + "\u001B[D".toByteArray(Charsets.US_ASCII)),
        TmuxKey("pane \u2192", byteArrayOf(TMUX_PREFIX) + "\u001B[C".toByteArray(Charsets.US_ASCII)),
    )

private val SESSION_ACTIONS =
    listOf(
        actionWithPrefix(R.string.tmux_detach, 'd'.code.toByte()),
        actionWithPrefix(R.string.tmux_list_sessions, 's'.code.toByte()),
        actionWithPrefix(R.string.tmux_rename_session, '$'.code.toByte()),
        actionWithPrefix(R.string.tmux_copy_mode, '['.code.toByte()),
        actionWithPrefix(R.string.tmux_paste, ']'.code.toByte()),
        // ネスト tmux（ローカル tmux → SSH 先の tmux 等）で内側の tmux に prefix を届ける。
        // 外側の prefix を消費させ、次の Ctrl-B を素通ししたい時に使う。
        TmuxAction(R.string.tmux_inner_prefix, byteArrayOf(TMUX_PREFIX, TMUX_PREFIX)),
    )

/** window 番号ジャンプ：0..9 */
private val NUMBER_ACTIONS: List<TmuxKey> =
    (0..9).map { n -> TmuxKey("w$n", byteArrayOf(TMUX_PREFIX, ('0'.code + n).toByte())) }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxPanel(
    onSend: (ByteArray) -> Unit,
    onDismiss: () -> Unit,
    /** ウィンドウ一覧の取得に使う。null（未接続やループバック）ならボタンだけ出す。 */
    channel: SshChannel? = null,
) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      Text(
          text = stringResource(R.string.tmux_dashboard),
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(vertical = 8.dp),
      )
      Text(
          text = stringResource(R.string.tmux_prefix_hint),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 12.dp),
      )
      if (channel != null) {
        WindowList(channel)
        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      }
      ActionGroup("window", WINDOW_ACTIONS, onSend)
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      ActionGroup("pane", PANE_ACTIONS, onSend)
      Text(
          text = stringResource(R.string.tmux_pane_move),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 4.dp),
      )
      Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 4.dp),
          horizontalArrangement = Arrangement.spacedBy(6.dp),
      ) {
        PANE_DIRECTIONS.forEach { a ->
          FilledTonalButton(
              onClick = { onSend(a.keys) },
              contentPadding = PaddingValues(horizontal = 4.dp, vertical = 6.dp),
              modifier = Modifier.weight(1f),
          ) { Text(a.label, style = MaterialTheme.typography.labelSmall) }
        }
      }
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      ActionGroup("session / clipboard", SESSION_ACTIONS, onSend)
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      Text(
          text = stringResource(R.string.tmux_jump),
          style = MaterialTheme.typography.labelLarge,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(vertical = 4.dp),
      )
      Row(
          modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
          horizontalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        NUMBER_ACTIONS.forEach { a ->
          FilledTonalButton(
              onClick = { onSend(a.keys) },
              contentPadding = PaddingValues(horizontal = 2.dp, vertical = 4.dp),
              modifier = Modifier.weight(1f),
          ) { Text(a.label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelSmall) }
        }
      }
      Spacer(Modifier.height(16.dp))
    }
  }
}

@Composable
private fun ActionGroup(title: String, actions: List<TmuxAction>, onSend: (ByteArray) -> Unit) {
  Text(
      text = title,
      style = MaterialTheme.typography.labelLarge,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      modifier = Modifier.padding(bottom = 6.dp),
  )
  LazyVerticalGrid(
      columns = GridCells.Fixed(2),
      verticalArrangement = Arrangement.spacedBy(6.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
      modifier = Modifier.fillMaxWidth().height(((actions.size + 1) / 2 * 48).dp),
  ) {
    items(actions) { a ->
      OutlinedButton(onClick = { onSend(a.keys) }, modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(a.label), style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}

/**
 * サーバー側の tmux ウィンドウ一覧。
 *
 * 端末に `tmux list-windows` を打ち込むのではなく、別セッションで実行して
 * 標準出力を読む（[TmuxController]）。利用者の画面に出力が混ざらない。
 *
 * 開いている間だけ数秒ごとに引き直す。端末側で新しいウィンドウを作られても
 * 一覧が古いままにならないようにするためで、閉じれば止まる。
 */
@Composable
private fun WindowList(channel: SshChannel) {
  val scope = rememberCoroutineScope()
  var listing by remember { mutableStateOf<TmuxListing?>(null) }
  var switchFailed by remember { mutableStateOf(false) }
  var reloadNonce by remember { mutableStateOf(0) }

  LaunchedEffect(channel, reloadNonce) {
    while (true) {
      listing = TmuxController.listWindows(channel)
      delay(REFRESH_INTERVAL_MS)
    }
  }

  Row(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.SpaceBetween,
  ) {
    Text(
        text = stringResource(R.string.tmux_windows),
        style = MaterialTheme.typography.labelLarge,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    TextButton(onClick = { reloadNonce++ }) {
      Text(stringResource(R.string.tmux_refresh), style = MaterialTheme.typography.labelMedium)
    }
  }

  when (val current = listing) {
    null ->
        Text(
            text = stringResource(R.string.tmux_loading),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    is TmuxListing.Unavailable ->
        Text(
            text = stringResource(R.string.tmux_unavailable, current.reason),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    is TmuxListing.Ok ->
        if (current.windows.isEmpty()) {
          Text(
              text = stringResource(R.string.tmux_no_windows),
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        } else {
          // 複数セッションが動いていることもあるので、そのときだけ名前を出す。
          val multipleSessions = current.windows.map { it.session }.distinct().size > 1
          Row(
              modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
              horizontalArrangement = Arrangement.spacedBy(6.dp),
          ) {
            current.windows.forEach { w ->
              ElevatedFilterChip(
                  selected = w.active,
                  onClick = {
                    scope.launch {
                      switchFailed = !TmuxController.selectWindow(channel, w)
                      listing = TmuxController.listWindows(channel)
                    }
                  },
                  label = { Text(chipLabel(w, multipleSessions), style = MaterialTheme.typography.labelMedium) },
              )
            }
          }
        }
  }

  if (switchFailed) {
    Text(
        text = stringResource(R.string.tmux_switch_failed),
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
    )
  }
}

private const val REFRESH_INTERVAL_MS = 5_000L

private fun chipLabel(w: TmuxWindow, withSession: Boolean): String {
  val panes = if (w.panes > 1) " (${w.panes})" else ""
  val prefix = if (withSession) "${w.session}:" else ""
  return "$prefix${w.index} ${w.name}$panes"
}
