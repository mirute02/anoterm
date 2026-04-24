package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.example.wanoterm.R

/**
 * ターミナル下部の補助キー群。以下の制約を意識して設計:
 * - 縦の占有を最小化（ボタン高さ 32dp）
 * - 右端に「ショートカット展開」「キーボード閉じ」ボタンを常設
 * - 真ん中のキー群は横スクロール、順序は使用頻度順
 */
@Composable
fun KeyboardToolbar(
    ctrlArmed: Boolean,
    shortcutBarVisible: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleShortcutBar: () -> Unit,
    onHideKeyboard: () -> Unit,
    onSend: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    // スクロール可能な中央のキー群
    Row(
        modifier =
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      KeyButton(stringResource(R.string.kbd_esc)) { onSend(byteArrayOf(0x1B)) }
      KeyButton(stringResource(R.string.kbd_tab)) { onSend(byteArrayOf(0x09)) }
      if (ctrlArmed) {
        FilledTonalButton(
            onClick = onToggleCtrl,
            contentPadding = TinyPadding,
            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 32.dp),
        ) {
          Text(stringResource(R.string.kbd_ctrl), style = MaterialTheme.typography.labelMedium)
        }
      } else {
        OutlinedButton(
            onClick = onToggleCtrl,
            contentPadding = TinyPadding,
            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 32.dp),
        ) {
          Text(stringResource(R.string.kbd_ctrl), style = MaterialTheme.typography.labelMedium)
        }
      }
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
    // 右端は固定: ショートカット展開 / キーボード閉じ
    IconToggleButtonBar(
        shortcutBarVisible = shortcutBarVisible,
        onToggleShortcutBar = onToggleShortcutBar,
        onHideKeyboard = onHideKeyboard,
    )
  }
}

@Composable
private fun IconToggleButtonBar(
    shortcutBarVisible: Boolean,
    onToggleShortcutBar: () -> Unit,
    onHideKeyboard: () -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    IconKeyButton(
        icon = if (shortcutBarVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.Apps,
        contentDescription = "custom shortcuts toggle",
        onClick = onToggleShortcutBar,
    )
    IconKeyButton(
        icon = Icons.Filled.KeyboardHide,
        contentDescription = "hide keyboard",
        onClick = onHideKeyboard,
    )
  }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
  OutlinedButton(
      onClick = onClick,
      contentPadding = TinyPadding,
      modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 32.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun IconKeyButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
  OutlinedButton(
      onClick = onClick,
      contentPadding = TinyPadding,
      modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 32.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
  ) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.defaultMinSize(20.dp, 20.dp))
  }
}

private val TinyPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

// CSI シーケンスは必ず ESC (0x1B) 始まり。0x1B を明示して定義し、Edit/Write で invisibly に
// 落ちる事故を防ぐ。
private val ESC: Byte = 0x1B
private val LBR: Byte = '['.code.toByte()
private val ESC_UP = byteArrayOf(ESC, LBR, 'A'.code.toByte())
private val ESC_DOWN = byteArrayOf(ESC, LBR, 'B'.code.toByte())
private val ESC_RIGHT = byteArrayOf(ESC, LBR, 'C'.code.toByte())
private val ESC_LEFT = byteArrayOf(ESC, LBR, 'D'.code.toByte())
private val ESC_HOME = byteArrayOf(ESC, LBR, 'H'.code.toByte())
private val ESC_END = byteArrayOf(ESC, LBR, 'F'.code.toByte())
private val ESC_PGUP = byteArrayOf(ESC, LBR, '5'.code.toByte(), '~'.code.toByte())
private val ESC_PGDN = byteArrayOf(ESC, LBR, '6'.code.toByte(), '~'.code.toByte())
