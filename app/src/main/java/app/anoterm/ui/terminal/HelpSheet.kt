package app.anoterm.ui.terminal

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
import androidx.annotation.StringRes
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.stringResource
import app.anoterm.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 一覧の 1 行。左の欄は打鍵そのもの ([keys]) なので普通は翻訳しない。
 * ただし画面の操作の節だけは左も日本語・英語で書き分ける必要があるので [keysRes] を使う。
 * [desc] は常に説明文なのでリソース ID で持つ（この一覧はトップレベルの val）。
 */
// 引数の並びは既存の位置指定呼び出し (`Shortcut("prefix c", R.string.…)`) を壊さない順。
private data class Shortcut(
    val keys: String? = null,
    @StringRes val desc: Int,
    @StringRes val keysRes: Int? = null,
)

/** [title] は "tmux: prefix = Ctrl-B" のような技術的ラベル。訳す節は [titleRes] を使う。 */
private data class Section(
    val title: String? = null,
    val entries: List<Shortcut>,
    @StringRes val titleRes: Int? = null,
)

/** tmux + 端末で共通的に使うショートカットの一覧。prefix は Ctrl-B（デフォルト）を前提。 */
private val SECTIONS =
    listOf(
        // 最初に置くのは、これが唯一「アプリを触って初めて分かる」情報だから。
        // tmux とシェルのキーバインドは他所でも調べられるが、この画面の触り方はここにしかない。
        Section(
            titleRes = R.string.help_section_gestures,
            entries =
                listOf(
                    Shortcut(
                        keysRes = R.string.help_g_tap_bottom,
                        desc = R.string.help_g_tap_bottom_desc,
                    ),
                    Shortcut(
                        keysRes = R.string.help_g_tap_cursor,
                        desc = R.string.help_g_tap_cursor_desc,
                    ),
                    Shortcut(
                        keysRes = R.string.help_g_tap_path,
                        desc = R.string.help_g_tap_path_desc,
                    ),
                    Shortcut(
                        keysRes = R.string.help_g_tap_url,
                        desc = R.string.help_g_tap_url_desc,
                    ),
                    Shortcut(
                        keysRes = R.string.help_g_tap_text,
                        desc = R.string.help_g_tap_text_desc,
                    ),
                    Shortcut(
                        keysRes = R.string.help_g_backtab,
                        desc = R.string.help_g_backtab_desc,
                    ),
                    Shortcut(keysRes = R.string.help_g_find, desc = R.string.help_g_find_desc),
                    Shortcut(
                        keysRes = R.string.help_g_fullscreen,
                        desc = R.string.help_g_fullscreen_desc,
                    ),
                    Shortcut(keysRes = R.string.help_g_swipe, desc = R.string.help_g_swipe_desc),
                    Shortcut(
                        keysRes = R.string.help_g_double_tap,
                        desc = R.string.help_g_double_tap_desc,
                    ),
                    Shortcut(
                        keysRes = R.string.help_g_long_press,
                        desc = R.string.help_g_long_press_desc,
                    ),
                    Shortcut(keysRes = R.string.help_g_pinch, desc = R.string.help_g_pinch_desc),
                    Shortcut(
                        keysRes = R.string.help_g_swipe_side,
                        desc = R.string.help_g_swipe_side_desc,
                    ),
                    Shortcut(keysRes = R.string.help_g_pad, desc = R.string.help_g_pad_desc),
                    Shortcut(keysRes = R.string.help_g_menu, desc = R.string.help_g_menu_desc),
                    Shortcut(keysRes = R.string.help_g_kill, desc = R.string.help_g_kill_desc),
                    Shortcut(
                        keysRes = R.string.help_g_keys_hidden,
                        desc = R.string.help_g_keys_hidden_desc,
                    ),
                ),
        ),
        Section(
            "tmux: prefix = Ctrl-B",
            listOf(
                Shortcut("prefix c", R.string.help_new_window),
                Shortcut("prefix n / p", R.string.help_next_prev_window),
                Shortcut("prefix 0..9", R.string.help_window_by_number),
                Shortcut("prefix ,", R.string.help_rename_window),
                Shortcut("prefix &", R.string.help_close_window),
                Shortcut("prefix w", R.string.help_list_windows),
            ),
        ),
        Section(
            "tmux: pane",
            listOf(
                Shortcut("prefix %", R.string.help_split_v),
                Shortcut("prefix \"", R.string.help_split_h),
                Shortcut("prefix o", R.string.help_next_pane),
                Shortcut("prefix \u2190\u2191\u2193\u2192", R.string.help_move_pane),
                Shortcut("prefix z", R.string.help_zoom_pane),
                Shortcut("prefix x", R.string.help_close_pane),
                Shortcut("prefix q", R.string.help_number_panes),
                Shortcut("prefix {} / }", R.string.help_swap_panes),
            ),
        ),
        Section(
            "tmux: session",
            listOf(
                Shortcut("prefix d", R.string.help_detach),
                Shortcut("prefix s", R.string.help_list_sessions),
                Shortcut("prefix $", R.string.help_rename_session),
                Shortcut("prefix ( / )", R.string.help_prev_next_session),
            ),
        ),
        Section(
            "tmux: copy / scroll",
            listOf(
                Shortcut("prefix [", R.string.help_copy_mode),
                Shortcut("space → enter", R.string.help_copy_select),
                Shortcut("prefix ]", R.string.help_paste),
                Shortcut("q", R.string.help_leave_copy_mode),
            ),
        ),
        Section(
            "shell / readline",
            listOf(
                Shortcut("Ctrl-A / Ctrl-E", R.string.help_line_start_end),
                Shortcut("Ctrl-U / Ctrl-K", R.string.help_kill_before_after),
                Shortcut("Ctrl-W", R.string.help_kill_word),
                Shortcut("Ctrl-R", R.string.help_history_search),
                Shortcut("Ctrl-C", R.string.help_interrupt),
                Shortcut("Ctrl-D", R.string.help_eof),
                Shortcut("Ctrl-L", R.string.help_clear),
                Shortcut("Ctrl-Z", R.string.help_suspend),
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
              text = section.titleRes?.let { stringResource(it) } ?: section.title.orEmpty(),
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
                  text = sc.keysRes?.let { stringResource(it) } ?: sc.keys.orEmpty(),
                  modifier =
                      Modifier.width(140.dp)
                          .clip(RoundedCornerShape(4.dp))
                          .padding(horizontal = 6.dp, vertical = 2.dp),
                  fontFamily = FontFamily.Monospace,
                  style = MaterialTheme.typography.bodyMedium,
                  color = MaterialTheme.colorScheme.primary,
              )
              Text(
                  text = stringResource(sc.desc),
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
