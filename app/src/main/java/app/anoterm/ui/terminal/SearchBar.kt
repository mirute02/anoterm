package app.anoterm.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import app.anoterm.R

/**
 * 履歴を探すための一行。検索中だけ端末の上に出る。
 *
 * 端末そのものに検索を打ち込ませる案（`Ctrl-R` や tmux のコピーモード）は採らなかった。
 * あれはリモートの機能で、相手が何を動かしているかで挙動が変わる。ここで探したいのは
 * 「この画面に流れた文字」であって、シェルの履歴でもファイルの中身でもない。
 */
@Composable
fun TerminalSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    matchCount: Int,
    currentMatch: Int,
    onPrevious: () -> Unit,
    onNext: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  val focusRequester = remember { FocusRequester() }
  // 開いた瞬間に打ち始められるようにする。開いてから欄を押させるのは一手多い。
  LaunchedEffect(Unit) { focusRequester.requestFocus() }

  Row(
      modifier = modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 4.dp),
      verticalAlignment = Alignment.CenterVertically,
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    OutlinedTextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = Modifier.weight(1f).focusRequester(focusRequester),
        singleLine = true,
        placeholder = { Text(stringResource(R.string.search_hint)) },
        textStyle = MaterialTheme.typography.bodyMedium,
        keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { onNext() }),
    )
    Text(
        text =
            if (matchCount == 0) stringResource(R.string.search_none)
            else stringResource(R.string.search_position, currentMatch + 1, matchCount),
        style = MaterialTheme.typography.labelMedium,
        color =
            if (matchCount == 0) MaterialTheme.colorScheme.onSurfaceVariant
            else MaterialTheme.colorScheme.onSurface,
    )
    IconButton(onClick = onPrevious, enabled = matchCount > 0) {
      Icon(Icons.Filled.KeyboardArrowUp, contentDescription = stringResource(R.string.search_prev))
    }
    IconButton(onClick = onNext, enabled = matchCount > 0) {
      Icon(Icons.Filled.KeyboardArrowDown, contentDescription = stringResource(R.string.search_next))
    }
    IconButton(onClick = onClose) {
      Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.search_close))
    }
  }
}
