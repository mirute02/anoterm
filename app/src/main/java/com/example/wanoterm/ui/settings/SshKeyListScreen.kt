package com.example.wanoterm.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.VpnKey
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.db.SshKeyEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyListScreen(onBack: () -> Unit, onCreateNew: () -> Unit) {
  val ctx = LocalContext.current
  val app = remember { WanotermApp.get() }
  val dao = remember { app.database.sshKeyDao() }
  val keys by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  val scope = rememberCoroutineScope()
  var renameTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
  var deleteTarget by remember { mutableStateOf<SshKeyEntity?>(null) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("SSH 鍵一覧") },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
        )
      },
      floatingActionButton = {
        FloatingActionButton(onClick = onCreateNew) {
          Icon(Icons.Filled.VpnKey, contentDescription = "新規作成")
        }
      },
  ) { inner ->
    if (keys.isEmpty()) {
      Column(
          modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text("保存済みの鍵はありません", style = MaterialTheme.typography.titleMedium)
        Text(
            "右下の鍵アイコンから Ed25519 / RSA 4096 を作成できます。",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }
    } else {
      LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
        items(keys, key = { it.id }) { k ->
          ListItem(
              headlineContent = { Text(k.label) },
              supportingContent = {
                Column {
                  Text("${k.algo.uppercase()} · ${formatDate(k.createdAt)}")
                  Text(
                      k.publicSsh.take(48) + if (k.publicSsh.length > 48) "…" else "",
                      style =
                          MaterialTheme.typography.labelSmall.copy(
                              fontFamily = FontFamily.Monospace,
                          ),
                      color = MaterialTheme.colorScheme.onSurfaceVariant,
                  )
                }
              },
              trailingContent = {
                Row {
                  IconButton(
                      onClick = {
                        val cm =
                            ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                        cm.setPrimaryClip(ClipData.newPlainText("ssh public key", k.publicSsh))
                        Toast.makeText(ctx, "公開鍵をコピーしました", Toast.LENGTH_SHORT).show()
                      },
                  ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = "コピー")
                  }
                  IconButton(onClick = { renameTarget = k }) {
                    Icon(Icons.Filled.Edit, contentDescription = "名前変更")
                  }
                  IconButton(onClick = { deleteTarget = k }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = "削除",
                        tint = MaterialTheme.colorScheme.error,
                    )
                  }
                }
              },
              modifier = Modifier.fillMaxWidth(),
          )
          HorizontalDivider()
        }
      }
    }

    renameTarget?.let { target ->
      RenameDialog(
          current = target.label,
          onDismiss = { renameTarget = null },
          onConfirm = { newLabel ->
            scope.launch(Dispatchers.IO) {
              dao.update(target.copy(label = newLabel))
            }
            renameTarget = null
          },
      )
    }

    deleteTarget?.let { target ->
      AlertDialog(
          onDismissRequest = { deleteTarget = null },
          title = { Text("鍵を削除しますか?") },
          text = {
            Text(
                "「${target.label}」を削除します。この鍵で接続中のホストは、host 側にコピーされた"
                    + "複製を使っているため動作は続きます。ただし同じ鍵から別ホストを作れなくなります。",
            )
          },
          confirmButton = {
            TextButton(
                onClick = {
                  scope.launch(Dispatchers.IO) {
                    // SecretStore 本体も消す。host 側の複製には影響なし（複製は別 secretId）
                    app.secretStore.delete(target.secretId)
                    dao.deleteById(target.id)
                  }
                  deleteTarget = null
                },
            ) {
              Text("削除", color = MaterialTheme.colorScheme.error)
            }
          },
          dismissButton = {
            TextButton(onClick = { deleteTarget = null }) { Text("キャンセル") }
          },
      )
    }
  }
}

@Composable
private fun RenameDialog(
    current: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
  var value by remember { mutableStateOf(current) }
  AlertDialog(
      onDismissRequest = onDismiss,
      title = { Text("鍵の名前を変更") },
      text = {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text("新しい名前") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        TextButton(
            onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
            enabled = value.isNotBlank() && value != current,
        ) {
          Text("変更")
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text("キャンセル") } },
  )
}

private val dateFormat: SimpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

private fun formatDate(ms: Long): String = dateFormat.format(Date(ms))
