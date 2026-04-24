package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/**
 * Claude Code / Codex の「1. ... / 2. ... / 3. ...」選択肢プロンプトに応答するための
 * 大ボタン行。検出時のみ表示される前提。
 *
 * maxChoice=2 → [承認 / 拒否] 2 ボタン、maxChoice=3 → [承認 / 拒否 / 継続] 3 ボタン。
 * 4 以上は現状 Claude / Codex のユースケースに無いので 3 で打ち止め。
 *
 * 送信は Enter 込み（上位の sendBytes が `lineEnding.bytes` を連結する）。
 */
@Composable
fun ResponsePalette(
    maxChoice: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
  val labels = listOf("✓ 承認", "✗ 拒否", "⟲ 継続")
  val colors =
      listOf(
          ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
          ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.errorContainer),
          ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.tertiary),
      )
  Row(
      modifier =
          modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (n in 1..maxChoice.coerceIn(2, 3)) {
      Button(
          onClick = { onSelect(n) },
          modifier = Modifier.weight(1f).heightIn(min = 52.dp),
          colors = colors.getOrElse(n - 1) { ButtonDefaults.buttonColors() },
      ) {
        Text(
            labels.getOrNull(n - 1) ?: "$n",
            style = MaterialTheme.typography.titleMedium,
        )
      }
    }
  }
}
