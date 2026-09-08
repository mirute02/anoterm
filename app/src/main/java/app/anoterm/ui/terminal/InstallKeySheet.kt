package app.anoterm.ui.terminal

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import app.anoterm.AnotermApp
import app.anoterm.data.db.SshKeyEntity
import app.anoterm.ssh.AuthorizedKeyInstaller
import app.anoterm.ssh.SshChannel
import kotlinx.coroutines.launch

/**
 * 接続中のセッションを使って、この端末の公開鍵を接続先の authorized_keys に登録する。
 *
 * Android だけで鍵認証に移ろうとすると「公開鍵をサーバーへ届ける」で詰まる。
 * パスワードで入れている今なら、その接続の上で追記してしまえる。`ssh-copy-id` と同じ発想。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun InstallKeySheet(channel: SshChannel, hostLabel: String, onDismiss: () -> Unit) {
  val app = remember { AnotermApp.get() }
  val scope = rememberCoroutineScope()
  var keys by remember { mutableStateOf<List<SshKeyEntity>>(emptyList()) }
  var busy by remember { mutableStateOf(false) }
  var message by remember { mutableStateOf<String?>(null) }

  LaunchedEffect(Unit) { keys = app.database.sshKeyDao().findAll() }

  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      Text("公開鍵を $hostLabel に登録", style = MaterialTheme.typography.titleMedium)
      Text(
          "接続中のセッションを使って、選んだ鍵の公開鍵を接続先の "
              + "~/.ssh/authorized_keys に追記します。登録後、ホスト設定の認証方法を"
              + "「秘密鍵」に変えると次回から鍵で入れます。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (keys.isEmpty()) {
        Text(
            "登録できる鍵がありません。設定 → SSH 鍵 から作成してください。",
            style = MaterialTheme.typography.bodyMedium,
        )
      } else {
        keys.forEach { key ->
          OutlinedButton(
              onClick = {
                if (busy) return@OutlinedButton
                busy = true
                message = null
                scope.launch {
                  val r = AuthorizedKeyInstaller.install(channel, key.publicSsh)
                  message =
                      when (r) {
                        is AuthorizedKeyInstaller.Result.Installed ->
                            "「${key.label}」を登録しました。ホスト設定で認証方法を秘密鍵に変えてください。"
                        is AuthorizedKeyInstaller.Result.AlreadyPresent ->
                            "「${key.label}」は既に登録されています。"
                        is AuthorizedKeyInstaller.Result.Failed -> "失敗: ${r.message}"
                      }
                  busy = false
                }
              },
              enabled = !busy,
              modifier = Modifier.fillMaxWidth(),
          ) {
            Text("${key.label}  (${key.algo})")
          }
        }
      }

      if (busy) CircularProgressIndicator()
      message?.let {
        Text(it, style = MaterialTheme.typography.bodyMedium)
      }

      Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text("閉じる") }
    }
  }
}
