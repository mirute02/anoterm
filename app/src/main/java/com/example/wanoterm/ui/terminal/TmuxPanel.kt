package com.example.wanoterm.ui.terminal

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
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

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

private data class TmuxAction(val label: String, val keys: ByteArray) {
  override fun equals(other: Any?): Boolean = other is TmuxAction && label == other.label
  override fun hashCode(): Int = label.hashCode()
}

private fun actionWithPrefix(label: String, vararg after: Byte): TmuxAction =
    TmuxAction(label, byteArrayOf(TMUX_PREFIX) + after)

private val WINDOW_ACTIONS =
    listOf(
        actionWithPrefix("新規 window", 'c'.code.toByte()),
        actionWithPrefix("次の window", 'n'.code.toByte()),
        actionWithPrefix("前の window", 'p'.code.toByte()),
        actionWithPrefix("window 一覧", 'w'.code.toByte()),
        actionWithPrefix("window 名変更", ','.code.toByte()),
        actionWithPrefix("window 閉じる", '&'.code.toByte()),
    )

private val PANE_ACTIONS =
    listOf(
        actionWithPrefix("縦分割 %", '%'.code.toByte()),
        actionWithPrefix("横分割 \"", '"'.code.toByte()),
        actionWithPrefix("次の pane (o)", 'o'.code.toByte()),
        actionWithPrefix("pane ズーム (z)", 'z'.code.toByte()),
        actionWithPrefix("pane 番号 (q)", 'q'.code.toByte()),
        actionWithPrefix("pane 閉じる (x)", 'x'.code.toByte()),
    )

/** pane 方向ジャンプ：prefix + 矢印。tmux の左右上下分割で隣の pane に移動する。 */
private val PANE_DIRECTIONS: List<TmuxAction> =
    listOf(
        TmuxAction("pane ↑", byteArrayOf(TMUX_PREFIX) + "[A".toByteArray(Charsets.US_ASCII)),
        TmuxAction("pane ↓", byteArrayOf(TMUX_PREFIX) + "[B".toByteArray(Charsets.US_ASCII)),
        TmuxAction("pane ←", byteArrayOf(TMUX_PREFIX) + "[D".toByteArray(Charsets.US_ASCII)),
        TmuxAction("pane →", byteArrayOf(TMUX_PREFIX) + "[C".toByteArray(Charsets.US_ASCII)),
    )

private val SESSION_ACTIONS =
    listOf(
        actionWithPrefix("detach", 'd'.code.toByte()),
        actionWithPrefix("session 一覧", 's'.code.toByte()),
        actionWithPrefix("session 名変更", '$'.code.toByte()),
        actionWithPrefix("copy mode", '['.code.toByte()),
        actionWithPrefix("ペースト", ']'.code.toByte()),
        // ネスト tmux（ローカル tmux → SSH 先の tmux 等）で内側の tmux に prefix を届ける。
        // 外側の prefix を消費させ、次の Ctrl-B を素通ししたい時に使う。
        TmuxAction("内 tmux へ prefix", byteArrayOf(TMUX_PREFIX, TMUX_PREFIX)),
    )

/** window 番号ジャンプ：0..9 */
private val NUMBER_ACTIONS: List<TmuxAction> =
    (0..9).map { n ->
      TmuxAction("w$n", byteArrayOf(TMUX_PREFIX, ('0'.code + n).toByte()))
    }

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TmuxPanel(onSend: (ByteArray) -> Unit, onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
  ) {
    Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
      Text(
          text = "tmux ダッシュボード",
          style = MaterialTheme.typography.titleMedium,
          color = MaterialTheme.colorScheme.primary,
          modifier = Modifier.padding(vertical = 8.dp),
      )
      Text(
          text = "prefix = Ctrl-B。ボタンで prefix 付きショートカットを送出します。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 12.dp),
      )
      ActionGroup("window", WINDOW_ACTIONS, onSend)
      HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
      ActionGroup("pane", PANE_ACTIONS, onSend)
      Text(
          text = "pane 移動（prefix + 矢印）",
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
          text = "window 番号でジャンプ",
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
        Text(a.label, style = MaterialTheme.typography.labelMedium)
      }
    }
  }
}
