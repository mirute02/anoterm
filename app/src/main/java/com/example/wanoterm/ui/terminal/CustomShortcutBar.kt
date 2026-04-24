package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.wanoterm.data.prefs.CustomShortcut
import com.example.wanoterm.data.prefs.LineEnding

/**
 * カスタムショートカットのチップ列。KeyboardToolbar の上に並べて表示。
 * タップで定義された文字列を送信する（`appendEnter` が true なら改行も）。
 */
@Composable
fun CustomShortcutBar(
    shortcuts: List<CustomShortcut>,
    lineEnding: LineEnding,
    onSend: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
  if (shortcuts.isEmpty()) return
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 4.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    shortcuts.forEach { s ->
      FilledTonalButton(
          onClick = {
            val payload = s.text.toByteArray(Charsets.UTF_8)
            onSend(payload)
            if (s.appendEnter) onSend(lineEnding.bytes)
          },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
      ) { Text(s.label, style = MaterialTheme.typography.labelLarge) }
    }
  }
}
