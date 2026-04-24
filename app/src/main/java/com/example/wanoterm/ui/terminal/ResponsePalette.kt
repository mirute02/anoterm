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
 * Claude Code / Codex 等の「1. ... / 2. ... / 3. ...」選択肢プロンプトに応答するための
 * 大ボタン行。検出時のみ表示される前提。
 *
 * 選択肢数だけ数字ボタンを並べる（意味ラベルなし）。Yes/No 以外の選択肢でも破綻しない。
 * 画面幅の関係で最大 9 までに制限（Claude / Codex 実例は 3-4 が多く、9 超えはほぼ無い）。
 *
 * 送信は Enter 込み（上位の sendBytes が `lineEnding.bytes` を連結する）。
 */
@Composable
fun ResponsePalette(
    maxChoice: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
  val count = maxChoice.coerceIn(2, 9)
  Row(
      modifier =
          modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (n in 1..count) {
      Button(
          onClick = { onSelect(n) },
          modifier = Modifier.weight(1f).heightIn(min = 52.dp),
          colors =
              ButtonDefaults.buttonColors(
                  containerColor = MaterialTheme.colorScheme.primary,
              ),
      ) {
        Text("$n", style = MaterialTheme.typography.titleLarge)
      }
    }
  }
}
