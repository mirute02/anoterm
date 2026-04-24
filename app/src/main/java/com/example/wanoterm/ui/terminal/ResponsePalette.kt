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
 * ボタンには数字だけ出す。「承認/拒否/継続」のような意味ラベルは Yes/No 以外の選択肢
 * （ファイル編集/絞込 etc.）で混乱を招くので廃止。
 *
 * maxChoice=2 → [1 / 2] 2 ボタン、maxChoice=3 → [1 / 2 / 3] 3 ボタン。
 * 送信は Enter 込み（上位の sendBytes が `lineEnding.bytes` を連結する）。
 */
@Composable
fun ResponsePalette(
    maxChoice: Int,
    onSelect: (Int) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier =
          modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      horizontalArrangement = Arrangement.spacedBy(8.dp),
  ) {
    for (n in 1..maxChoice.coerceIn(2, 3)) {
      Button(
          onClick = { onSelect(n) },
          modifier = Modifier.weight(1f).heightIn(min = 52.dp),
          // 全ボタン同じ色。数字だけなので誤解を招かない。
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
