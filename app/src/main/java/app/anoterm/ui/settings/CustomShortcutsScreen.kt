package app.anoterm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.anoterm.R
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anoterm.AnotermApp
import app.anoterm.data.prefs.CustomShortcut

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CustomShortcutsScreen(onBack: () -> Unit) {
  val app = remember { AnotermApp.get() }
  val list by app.prefs.customShortcuts.collectAsStateWithLifecycle()
  var editing: CustomShortcut? by remember { mutableStateOf(null) }
  var editIndex by remember { mutableStateOf(-1) }
  var showDialog by remember { mutableStateOf(false) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.shortcuts_title)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
        )
      },
      floatingActionButton = {
        FloatingActionButton(
            onClick = {
              editing = CustomShortcut(label = "", text = "")
              editIndex = -1
              showDialog = true
            },
        ) { Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.action_add)) }
      },
  ) { inner ->
    if (list.isEmpty()) {
      Box(
          modifier = Modifier.fillMaxSize().padding(inner),
          contentAlignment = Alignment.Center,
      ) {
        Text(
            stringResource(R.string.shortcuts_empty),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
        items(list, key = { it.label + "|" + it.text }) { s ->
          val idx = list.indexOf(s)
          ListItem(
              headlineContent = { Text(s.label) },
              supportingContent = {
                Text(
                    text = s.text + if (s.appendEnter) "  [Enter]" else "",
                    fontFamily = FontFamily.Monospace,
                    style = MaterialTheme.typography.bodySmall,
                )
              },
              trailingContent = {
                IconButton(
                    onClick = { app.prefs.setCustomShortcuts(list.toMutableList().also { it.removeAt(idx) }) },
                ) { Icon(Icons.Filled.Delete, contentDescription = null) }
              },
              modifier = Modifier.fillMaxWidth(),
          )
        }
      }
    }

    if (showDialog) {
      EditDialog(
          initial = editing ?: CustomShortcut("", ""),
          onDismiss = { showDialog = false },
          onSave = { updated ->
            val newList = list.toMutableList()
            if (editIndex in newList.indices) newList[editIndex] = updated else newList.add(updated)
            app.prefs.setCustomShortcuts(newList)
            showDialog = false
          },
      )
    }
  }
}

@Composable
private fun EditDialog(
    initial: CustomShortcut,
    onDismiss: () -> Unit,
    onSave: (CustomShortcut) -> Unit,
) {
  var label by remember { mutableStateOf(initial.label) }
  var text by remember { mutableStateOf(initial.text) }
  var enter by remember { mutableStateOf(initial.appendEnter) }

  AlertDialog(
      onDismissRequest = onDismiss,
      title = {
        Text(
            stringResource(
                if (initial.label.isEmpty()) R.string.shortcuts_add else R.string.shortcuts_edit,
            ),
        )
      },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
              value = label,
              onValueChange = { label = it.take(12) },
              label = { Text(stringResource(R.string.shortcuts_label)) },
              singleLine = true,
              modifier = Modifier.fillMaxWidth(),
          )
          OutlinedTextField(
              value = text,
              onValueChange = { text = it },
              label = { Text(stringResource(R.string.shortcuts_text)) },
              modifier = Modifier.fillMaxWidth(),
          )
          Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(4.dp),
          ) {
            Checkbox(checked = enter, onCheckedChange = { enter = it })
            Text(stringResource(R.string.shortcuts_append_enter))
          }
        }
      },
      confirmButton = {
        TextButton(
            enabled = label.isNotBlank() && text.isNotEmpty(),
            onClick = { onSave(CustomShortcut(label.trim(), text, enter)) },
        ) { Text(stringResource(R.string.action_save)) }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}
