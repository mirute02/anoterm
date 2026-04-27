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
            title = { Text("SSH 鍵") },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
            actions = {
              IconButton(onClick = onOpenHelp) {
                Icon(
                    Icons.AutoMirrored.Filled.HelpOutline,
                    contentDescription = "使い方",
                )
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
                    Icon(Icons.Filled.ContentCopy, contentDescription = "公開鍵をコピー")
                  }
                  IconButton(onClick = { registerTarget = k }) {
                    Icon(
                        Icons.Filled.CloudUpload,
                        contentDescription = "サーバに登録",
                        tint = MaterialTheme.colorScheme.primary,
                    )
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
            cm.setPrimaryClip(ClipData.newPlainText("authorized_keys 登録コマンド", cmd))
            Toast.makeText(
                    ctx,
                    "登録コマンドをコピー。接続後、長押しでペースト → Enter",
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
          "どのホストに登録する?",
          style = MaterialTheme.typography.titleMedium,
          modifier = Modifier.padding(bottom = 4.dp),
      )
      Text(
          "このフローは「パスワード認証で繋がる」ホスト前提です。"
              + "秘密鍵ホストを選ぶと接続自体ができず登録に進めません。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(bottom = 8.dp),
      )
      if (hosts.isEmpty()) {
        Text("ホストが未登録です。", color = MaterialTheme.colorScheme.onSurfaceVariant)
        TextButton(onClick = onCreateHost) { Text("ホスト一覧から追加") }
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
        title = { Text("秘密鍵認証のホストです") },
        text = {
          Text(
              "「${h.label}」は既に秘密鍵認証に設定されています。"
                  + "鍵登録フローは最初にパスワード認証で接続する必要があるため、このままでは進めません。\n\n"
                  + "対処:\n"
                  + "1. ホスト一覧で「${h.label}」の 🖉 を開き、認証方法を「パスワード」に戻して保存\n"
                  + "2. このフローをやり直す\n"
                  + "3. 鍵登録が終わったら再度 🖉 から「秘密鍵」へ切り替え",
          )
        },
        confirmButton = {
          TextButton(onClick = { keyAuthWarning = null }) { Text("閉じる") }
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
