package app.anoterm.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import app.anoterm.data.db.AuthMethod
import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.HelpOutline
import androidx.compose.material.icons.filled.CloudUpload
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
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import app.anoterm.R
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anoterm.AnotermApp
import app.anoterm.data.db.HostEntity
import app.anoterm.data.db.SshKeyEntity
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyListScreen(
    onBack: () -> Unit,
    onCreateNew: () -> Unit,
    onCreateHost: () -> Unit = {},
    onOpenHelp: () -> Unit = {},
    onRegisterToHost: (publicKey: String, hostId: Long) -> Unit = { _, _ -> },
) {
  val ctx = LocalContext.current
  val app = remember { AnotermApp.get() }
  val dao = remember { app.database.sshKeyDao() }
  val keys by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  val hosts by
      app.database.hostDao().observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  val scope = rememberCoroutineScope()
  var renameTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
  var deleteTarget by remember { mutableStateOf<SshKeyEntity?>(null) }
  // 「サーバに登録」でピックアップ対象の鍵。null でなければ ModalBottomSheet が開く。
  var registerTarget by remember { mutableStateOf<SshKeyEntity?>(null) }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_ssh_keys)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
            actions = {
              IconButton(onClick = onOpenHelp) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = stringResource(R.string.keys_help),
                )
              }
            },
        )
      },
      floatingActionButton = {
        FloatingActionButton(onClick = onCreateNew) {
          Icon(Icons.Filled.VpnKey, contentDescription = stringResource(R.string.keys_create))
        }
      },
  ) { inner ->
    if (keys.isEmpty()) {
      Column(
          modifier = Modifier.fillMaxSize().padding(inner).padding(32.dp),
          verticalArrangement = Arrangement.spacedBy(12.dp),
          horizontalAlignment = Alignment.CenterHorizontally,
      ) {
        Text(stringResource(R.string.keys_empty), style = MaterialTheme.typography.titleMedium)
        Text(
            stringResource(R.string.keys_empty_hint),
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
                        Toast.makeText(ctx, ctx.getString(R.string.keygen_copied), Toast.LENGTH_SHORT).show()
                      },
                  ) {
                    Icon(Icons.Filled.ContentCopy, contentDescription = stringResource(R.string.keys_copy_public))
                  }
                  IconButton(onClick = { registerTarget = k }) {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = stringResource(R.string.keys_install),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                  }
                  IconButton(onClick = { renameTarget = k }) {
                    Icon(Icons.Filled.Edit, contentDescription = stringResource(R.string.keys_rename))
                  }
                  IconButton(onClick = { deleteTarget = k }) {
                    Icon(
                        Icons.Filled.Delete,
                        contentDescription = stringResource(R.string.action_delete),
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

    registerTarget?.let { key ->
      HostPickerSheet(
          hosts = hosts,
          onDismiss = { registerTarget = null },
          onCreateHost = {
            registerTarget = null
            onCreateHost()
          },
          onPick = { host ->
            val cmd = buildAuthorizedKeysCommand(key.publicSsh.trim())
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText(ctx.getString(R.string.keys_clip_label), cmd))
            Toast.makeText(
                    ctx,
                    ctx.getString(R.string.keys_command_copied),
                    Toast.LENGTH_LONG,
                )
                .show()
            registerTarget = null
            onRegisterToHost(key.publicSsh, host.id)
          },
      )
    }

    deleteTarget?.let { target ->
      AlertDialog(
          onDismissRequest = { deleteTarget = null },
          title = { Text(stringResource(R.string.keys_delete_title)) },
          text = {
            Text(
                stringResource(R.string.keys_delete_body, target.label),
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
              Text(stringResource(R.string.action_delete), color = MaterialTheme.colorScheme.error)
            }
          },
          dismissButton = {
            TextButton(onClick = { deleteTarget = null }) { Text(stringResource(R.string.action_cancel)) }
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
      title = { Text(stringResource(R.string.keys_rename_title)) },
      text = {
        OutlinedTextField(
            value = value,
            onValueChange = { value = it },
            label = { Text(stringResource(R.string.keys_new_name)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
      },
      confirmButton = {
        TextButton(
            onClick = { if (value.isNotBlank()) onConfirm(value.trim()) },
            enabled = value.isNotBlank() && value != current,
        ) {
          Text(stringResource(R.string.action_rename))
        }
      },
      dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.action_cancel)) } },
  )
}

private val dateFormat: SimpleDateFormat by lazy { SimpleDateFormat("yyyy-MM-dd", Locale.US) }

private fun formatDate(ms: Long): String = dateFormat.format(Date(ms))

/**
 * authorized_keys 末尾に公開鍵を追記する one-liner。
 * - `mkdir -p && chmod 700` を先に実行するので .ssh が存在しない環境でも動く
 * - echo は single-quote で囲むので shell 展開されない（公開鍵中の $ や ` が安全）
 * - 公開鍵末尾の改行はユーザが Enter で与えるため echo は改行を吐く標準挙動で OK
 */
private fun buildAuthorizedKeysCommand(publicKey: String): String =
    "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
        "echo '$publicKey' >> ~/.ssh/authorized_keys && " +
        "chmod 600 ~/.ssh/authorized_keys"

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HostPickerSheet(
    hosts: List<HostEntity>,
    onDismiss: () -> Unit,
    onCreateHost: () -> Unit,
    onPick: (HostEntity) -> Unit,
) {
  val sheetState = rememberModalBottomSheetState()
  // 秘密鍵認証のホストをタップしたときの警告用。パスワードで繋げないので鍵登録できない。
  var keyAuthWarning by remember { mutableStateOf<HostEntity?>(null) }
  ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheetState) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
          stringResource(R.string.keys_pick_host),
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
          stringResource(R.string.keys_pick_host_note),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
      )
      if (hosts.isEmpty()) {
        Text(
            stringResource(R.string.keys_no_hosts),
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        TextButton(onClick = onCreateHost) { Text(stringResource(R.string.keys_add_host)) }
      } else {
        hosts.forEach { h ->
          ListItem(
              headlineContent = {
                Row(verticalAlignment = Alignment.CenterVertically) {
                  Text(h.label)
                  AuthBadge(h.auth)
                }
              },
              supportingContent = { Text("${h.username}@${h.address}:${h.port}") },
              leadingContent = { Icon(Icons.Filled.CloudUpload, contentDescription = null) },
              modifier =
                  Modifier.fillMaxWidth()
                      .clickable {
                        if (h.auth == AuthMethod.PRIVATE_KEY) keyAuthWarning = h
                        else onPick(h)
                      }
                      .padding(vertical = 2.dp),
          )
          HorizontalDivider()
        }
      }
    }
  }

  keyAuthWarning?.let { h ->
    AlertDialog(
        onDismissRequest = { keyAuthWarning = null },
        title = { Text(stringResource(R.string.keys_host_is_key_auth_title)) },
        text = {
          Text(
              stringResource(R.string.keys_host_is_key_auth_body, h.label),
          )
        },
        confirmButton = {
          TextButton(onClick = { keyAuthWarning = null }) { Text(stringResource(R.string.action_close)) }
        },
    )
  }
}

/** HostPickerSheet 内で使う小型バッジ。HostList と共用したいが private のため複製。 */
@Composable
private fun AuthBadge(auth: AuthMethod) {
  val (text, bg, fg) =
      when (auth) {
        AuthMethod.PRIVATE_KEY ->
            Triple(
                "key",
                MaterialTheme.colorScheme.primaryContainer,
                MaterialTheme.colorScheme.onPrimaryContainer,
            )
        AuthMethod.PASSWORD ->
            Triple(
                "pass",
                MaterialTheme.colorScheme.surfaceVariant,
                MaterialTheme.colorScheme.onSurfaceVariant,
            )
      }
  Surface(color = bg, shape = RoundedCornerShape(4.dp), modifier = Modifier.padding(start = 6.dp)) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = fg,
        modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
    )
  }
}
