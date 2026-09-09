package app.anoterm.ui.terminal

import android.content.Context
import androidx.annotation.StringRes
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.anoterm.AnotermApp
import app.anoterm.R
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
  val context = LocalContext.current
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
      Text(
          stringResource(R.string.install_key_title, hostLabel),
          style = MaterialTheme.typography.titleMedium,
      )
      Text(
          stringResource(R.string.install_key_explanation),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      if (keys.isEmpty()) {
        Text(
            stringResource(R.string.install_key_none),
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
                  message = context.describe(r, key.label)
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

      Button(onClick = onDismiss, modifier = Modifier.fillMaxWidth()) { Text(stringResource(R.string.action_close)) }
    }
  }
}

/**
 * 登録結果を利用者に見せる 1 行にする。
 *
 * 文言はここで組み立てる。[AuthorizedKeyInstaller] 側は理由コードだけを返し、
 * 表示に使う言語を知らない。
 */
private fun Context.describe(result: AuthorizedKeyInstaller.Result, keyLabel: String): String =
    when (result) {
      is AuthorizedKeyInstaller.Result.Installed ->
          getString(R.string.install_key_installed, keyLabel)
      is AuthorizedKeyInstaller.Result.AlreadyPresent ->
          getString(R.string.install_key_already, keyLabel)
      is AuthorizedKeyInstaller.Result.Invalid ->
          getString(R.string.install_key_failed, getString(reasonOf(result.rejection)))
      is AuthorizedKeyInstaller.Result.CommandFailed ->
          getString(
              R.string.install_key_failed,
              getString(R.string.install_key_command_failed, result.detail),
          )
      is AuthorizedKeyInstaller.Result.ServerRefused ->
          getString(
              R.string.install_key_failed,
              // サーバーが理由を言ってきたなら、こちらの一般化した文言より役に立つ。
              result.detail.ifEmpty {
                getString(R.string.install_key_server_refused, result.exitStatus)
              },
          )
    }

@StringRes
private fun reasonOf(rejection: AuthorizedKeyInstaller.Rejection): Int =
    when (rejection) {
      AuthorizedKeyInstaller.Rejection.EMPTY -> R.string.install_key_rejected_empty
      AuthorizedKeyInstaller.Rejection.MULTIPLE_LINES -> R.string.install_key_rejected_multiline
      AuthorizedKeyInstaller.Rejection.CONTAINS_QUOTE -> R.string.install_key_rejected_quote
      AuthorizedKeyInstaller.Rejection.MALFORMED -> R.string.install_key_rejected_malformed
    }
