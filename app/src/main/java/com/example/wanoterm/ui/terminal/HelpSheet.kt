package com.example.wanoterm.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

private data class Shortcut(val keys: String, val desc: String)

private data class Section(val title: String, val entries: List<Shortcut>)

/** tmux + 端末で共通的に使うショートカットの一覧。prefix は Ctrl-B（デフォルト）を前提。 */
private val SECTIONS =
    listOf(
        Section(
            "tmux: prefix = Ctrl-B",
            listOf(
                Shortcut("prefix c", "新しい window"),
                Shortcut("prefix n / p", "次 / 前 の window"),
                Shortcut("prefix 0..9", "window 番号で切替"),
                Shortcut("prefix ,", "window 名を変更"),
                Shortcut("prefix &", "window を閉じる"),
                Shortcut("prefix w", "window 一覧"),
            ),
        ),
        Section(
            "tmux: pane",
            listOf(
                Shortcut("prefix %", "縦分割"),
                Shortcut("prefix \"", "横分割"),
                Shortcut("prefix o", "次の pane"),
                Shortcut("prefix 矢印", "pane 移動"),
                Shortcut("prefix z", "pane ズーム切替"),
                Shortcut("prefix x", "pane を閉じる"),
                Shortcut("prefix q", "pane 番号表示"),
                Shortcut("prefix {} / }", "pane 入替"),
            ),
        ),
        Section(
            "tmux: session",
            listOf(
                Shortcut("prefix d", "detach（再接続可能に）"),
                Shortcut("prefix s", "session 一覧"),
                Shortcut("prefix $", "session 名変更"),
                Shortcut("prefix ( / )", "前後の session"),
            ),
        ),
        Section(
            "tmux: copy / scroll",
            listOf(
                Shortcut("prefix [", "copy mode に入る（矢印でスクロール）"),
                Shortcut("space → enter", "copy mode で選択→コピー"),
                Shortcut("prefix ]", "ペースト"),
                Shortcut("q", "copy mode から抜ける"),
            ),
        ),
        Section(
            "shell / readline",
            listOf(
                Shortcut("Ctrl-A / Ctrl-E", "行頭 / 行末"),
                Shortcut("Ctrl-U / Ctrl-K", "カーソルより前 / 後を削除"),
                Shortcut("Ctrl-W", "単語削除"),
                Shortcut("Ctrl-R", "履歴インクリメンタル検索"),
                Shortcut("Ctrl-C", "実行中コマンド中断"),
                Shortcut("Ctrl-D", "EOF / ログアウト"),
                Shortcut("Ctrl-L", "画面クリア"),
                Shortcut("Ctrl-Z", "サスペンド（bg/fg で再開）"),
            ),
        ),
    )

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HelpSheet(onDismiss: () -> Unit) {
  val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
  ModalBottomSheet(
      onDismissRequest = onDismiss,
      sheetState = sheetState,
      containerColor = MaterialTheme.colorScheme.surface,
      shape = RoundedCornerShape(topStart = 16.dp, topEnd = 16.dp),
  ) {
    LazyColumn(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 32.dp),
    ) {
      items(SECTIONS) { section ->
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
          Text(
              text = section.title,
              style = MaterialTheme.typography.titleMedium,
              color = MaterialTheme.colorScheme.primary,
          )
          section.entries.forEach { sc ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.Top,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
              Text(
                  text = sc.keys,
                  modifier =
                      Modifier.width(140.dp)
                          .clip(RoundedCornerShape(4.dp))
                          .padding(horizontal = 6.dp, vertical = 2.dp),
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.primary,
              )
              Text(
                  text = sc.desc,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.onSurface,
              )
            }
          }
        }
      }
    }
  }
}
