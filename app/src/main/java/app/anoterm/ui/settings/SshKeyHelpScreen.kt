package app.anoterm.ui.settings

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.anoterm.AnotermApp
import app.anoterm.R
import app.anoterm.data.db.SshKeyEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyHelpScreen(onBack: () -> Unit) {
  val app = remember { AnotermApp.get() }
  val keys by app.database
      .sshKeyDao()
      .observeAll()
      .collectAsStateWithLifecycle(initialValue = emptyList())
  // 複数の鍵があれば先頭（最新）を初期選択。UI でチップ切替できる。
  var selectedKeyId by remember { mutableStateOf<Long?>(null) }
  val activeKey: SshKeyEntity? =
      keys.firstOrNull { it.id == selectedKeyId } ?: keys.firstOrNull()

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.kh_title)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
        )
      },
  ) { inner ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Section(stringResource(R.string.kh_what_title)) {
        Text(stringResource(R.string.kh_what_body))
      }

      Section(stringResource(R.string.kh_create_title)) {
        Text(stringResource(R.string.kh_create_body))
        Bullet(stringResource(R.string.kh_create_1))
        Bullet(stringResource(R.string.kh_create_2))
        Bullet(stringResource(R.string.kh_create_3))
        Bullet(stringResource(R.string.kh_create_4))
        Bullet(stringResource(R.string.kh_create_5))
      }

      Section(stringResource(R.string.kh_import_title)) {
        Text(stringResource(R.string.kh_import_body))

        SubHeader("macOS / Linux")
        Code("ssh-keygen -t ed25519 -C \"your_email@example.com\"")
        Text(stringResource(R.string.kh_import_unix))

        SubHeader("Windows")
        Text(stringResource(R.string.kh_import_ps))
        Code("ssh-keygen -t ed25519 -C \"your_email@example.com\"")
        Text(stringResource(R.string.kh_import_putty))
      }

      Section(stringResource(R.string.kh_install_title)) {
        Callout(stringResource(R.string.kh_install_prereq))

        SubHeader(stringResource(R.string.kh_shortest_title))
        Callout(stringResource(R.string.kh_shortest_note))
        Bullet(stringResource(R.string.kh_shortest_1))
        Bullet(stringResource(R.string.kh_shortest_2))
        Bullet(stringResource(R.string.kh_shortest_3))
        Bullet(stringResource(R.string.kh_shortest_4))
        Bullet(stringResource(R.string.kh_shortest_5))
        Bullet(stringResource(R.string.kh_shortest_6))
        Bullet(stringResource(R.string.kh_shortest_7))
        Text(
            stringResource(R.string.kh_sshd_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (keys.isNotEmpty()) {
          SubHeader(stringResource(R.string.kh_pick_key))
          if (keys.size == 1) {
            Text(
                stringResource(R.string.kh_one_key, keys[0].label),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              keys.forEach { k ->
                FilterChip(
                    selected = (activeKey?.id == k.id),
                    onClick = { selectedKeyId = k.id },
                    label = { Text(k.label) },
                )
              }
            }
          }
        } else {
          Callout(stringResource(R.string.kh_no_keys))
        }

        SubHeader(stringResource(R.string.kh_manual_title))
        Text(stringResource(R.string.kh_manual_body))
        val pubKey = activeKey?.publicSsh?.trim() ?: "ssh-ed25519 AAAA... your_email@example.com"
        Code(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && " +
                "echo '${pubKey}' >> ~/.ssh/authorized_keys && " +
                "chmod 600 ~/.ssh/authorized_keys",
        )
        Text(
            stringResource(R.string.kh_manual_note),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SubHeader(stringResource(R.string.kh_cloud_title))
        Text(stringResource(R.string.kh_cloud_1))
        Text(stringResource(R.string.kh_cloud_2))

        SubHeader(stringResource(R.string.kh_forge_title))
        Text(stringResource(R.string.kh_forge_body))

        SubHeader(stringResource(R.string.kh_copyid_title))
        Text(stringResource(R.string.kh_copyid_body))
      }

      Section(stringResource(R.string.kh_passphrase_title)) {
        Text(stringResource(R.string.kh_passphrase_body))
      }

      Section(stringResource(R.string.kh_lost_title)) {
        Text(stringResource(R.string.kh_lost_body))
        Text(stringResource(R.string.kh_lost_backup))
      }

      Section(stringResource(R.string.kh_formats_title)) {
        Bullet(stringResource(R.string.kh_fmt_ed25519))
        Bullet(stringResource(R.string.kh_fmt_rsa))
        Bullet(stringResource(R.string.kh_fmt_ecdsa))
        Bullet(stringResource(R.string.kh_fmt_dsa))
        Bullet(stringResource(R.string.kh_fmt_pem))
      }

      HorizontalDivider()
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
    HorizontalDivider(thickness = 0.5.dp)
  }
}

@Composable
private fun SubHeader(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Bullet(text: String) {
  Text("• $text", style = MaterialTheme.typography.bodyMedium)
}

/** セル単位の強調メッセージ。ユーザが読み飛ばしやすい前提条件を箱で囲う。 */
@Composable
private fun Callout(text: String) {
  Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

@Composable
private fun Code(text: String) {
  val ctx = LocalContext.current
  Surface(
      shape = RoundedCornerShape(6.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
      Text(
          text,
          style =
              MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
              ),
          color = MaterialTheme.colorScheme.primary,
          modifier =
              Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
      )
      IconButton(
          onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("shell snippet", text))
            Toast.makeText(ctx, ctx.getString(R.string.keygen_copied), Toast.LENGTH_SHORT).show()
          },
      ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = stringResource(R.string.action_copy),
            modifier = Modifier.padding(2.dp),
        )
      }
    }
  }
}
