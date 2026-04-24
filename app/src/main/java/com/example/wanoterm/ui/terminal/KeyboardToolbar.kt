package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.wanoterm.R

/**
 * ターミナル下部の補助キー群。
 *
 * - Esc / Tab / Ctrl（sticky）/ `⌃B`（tmux prefix 単発）/ 矢印 / Pipe / Home / End / PgUp / PgDn
 * - Ctrl は sticky トグル：アクティブ状態で次の文字が Ctrl+X として送られる
 * - ⌃B は tmux の prefix を 1 タップで送出（= Ctrl-B）
 */
@Composable
fun KeyboardToolbar(
    ctrlArmed: Boolean,
    onToggleCtrl: () -> Unit,
    onSend: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    KeyButton(stringResource(R.string.kbd_esc)) { onSend(byteArrayOf(0x1B)) }
    KeyButton(stringResource(R.string.kbd_tab)) { onSend(byteArrayOf(0x09)) }
    if (ctrlArmed) {
      FilledTonalButton(onClick = onToggleCtrl, contentPadding = SmallPadding) {
        Text(stringResource(R.string.kbd_ctrl))
      }
    } else {
      OutlinedButton(onClick = onToggleCtrl, contentPadding = SmallPadding) {
        Text(stringResource(R.string.kbd_ctrl))
      }
    }
    // tmux prefix (Ctrl-B = 0x02) 専用ボタン。1 タップで送出して次のキーと組み合わせ可能。
    KeyButton("⌃B") { onSend(byteArrayOf(0x02)) }
    KeyButton(stringResource(R.string.kbd_up)) { onSend(ESC_UP) }
    KeyButton(stringResource(R.string.kbd_down)) { onSend(ESC_DOWN) }
    KeyButton(stringResource(R.string.kbd_left)) { onSend(ESC_LEFT) }
    KeyButton(stringResource(R.string.kbd_right)) { onSend(ESC_RIGHT) }
    KeyButton(stringResource(R.string.kbd_pipe)) { onSend(byteArrayOf('|'.code.toByte())) }
    KeyButton(stringResource(R.string.kbd_home)) { onSend(ESC_HOME) }
    KeyButton(stringResource(R.string.kbd_end)) { onSend(ESC_END) }
    KeyButton(stringResource(R.string.kbd_pgup)) { onSend(ESC_PGUP) }
    KeyButton(stringResource(R.string.kbd_pgdn)) { onSend(ESC_PGDN) }
  }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
  OutlinedButton(
      onClick = onClick,
      contentPadding = SmallPadding,
      modifier = Modifier.defaultMinSize(minWidth = 48.dp, minHeight = 36.dp),
      colors =
          ButtonDefaults.outlinedButtonColors(
              contentColor = MaterialTheme.colorScheme.onSurface,
          ),
  ) {
    Text(label, style = MaterialTheme.typography.labelLarge)
  }
}

private val SmallPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp)

// 名前だけ ESC で本体に 0x1B が無いと、shell は "[A" を素のテキストとして受ける。
// CSI シーケンスは必ず ESC (0x1B) 始まり。
private val ESC_UP = "[A".toByteArray(Charsets.US_ASCII)
private val ESC_DOWN = "[B".toByteArray(Charsets.US_ASCII)
private val ESC_RIGHT = "[C".toByteArray(Charsets.US_ASCII)
private val ESC_LEFT = "[D".toByteArray(Charsets.US_ASCII)
private val ESC_HOME = "[H".toByteArray(Charsets.US_ASCII)
private val ESC_END = "[F".toByteArray(Charsets.US_ASCII)
private val ESC_PGUP = "[5~".toByteArray(Charsets.US_ASCII)
private val ESC_PGDN = "[6~".toByteArray(Charsets.US_ASCII)
