package app.anoterm.ui.hosts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.annotation.StringRes
import app.anoterm.R
import app.anoterm.AnotermApp
import app.anoterm.data.db.AuthMethod
import app.anoterm.data.db.SshKeyEntity
import app.anoterm.ssh.KeyValidator
import app.anoterm.ui.common.ConfirmDialog
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ListItem
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    hostId: Long?,
    onDone: () -> Unit,
    onOpenKeyHelp: () -> Unit = {},
    onOpenKeyGen: () -> Unit = {},
    vm: HostEditViewModel = viewModel(factory = HostEditViewModel.factory(hostId)),
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  val app = remember { AnotermApp.get() }
  val scope = rememberCoroutineScope()
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var showSavedKeyPicker by remember { mutableStateOf(false) }
  // 秘密鍵が AnoTerm（sshj）で読めない形式だった時に出す警告メッセージ。
  var keyInvalidMessage by remember { mutableStateOf<String?>(null) }

  val keyPicker =
      rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        scope.launch {
          val bytes = withContext(Dispatchers.IO) { ctx.contentResolver.openInputStream(uri)?.use { it.readBytes() } }
          val name =
              ctx.contentResolver
                  .query(uri, arrayOf(android.provider.OpenableColumns.DISPLAY_NAME), null, null, null)
                  ?.use { c ->
                    if (c.moveToFirst()) c.getString(0) else null
                  } ?: "key"
          if (bytes != null) {
            // インポートした鍵も sshj で読める形式か即時検証（passphrase はこの時点で未入力なので
            // 非暗号化鍵 or パスフレーズ無しでチェック。パスフレーズ付き暗号化鍵の場合は
            // 後段の「保存」ボタン押下時に再検証する）。
            val validation =
                withContext(Dispatchers.IO) {
                  KeyValidator.validate(bytes, null)
                }
            if (validation.isFailure &&
                validation.exceptionOrNull()?.message?.contains("passphrase", ignoreCase = true) != true) {
              keyInvalidMessage =
                  ctx.getString(R.string.host_key_unreadable)
              return@launch
            }
            vm.setKey(bytes, name)
          }
        }
      }

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(if (hostId == null) stringResource(R.string.hosts_add) else state.label) },
            navigationIcon = {
              IconButton(onClick = onDone) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
            actions = {
              if (hostId != null) {
                TextButton(onClick = { showDeleteConfirm = true }) { Text(stringResource(R.string.action_delete)) }
              }
            },
        )
      }
  ) { inner ->
    Column(
        // imePadding で IME の高さを content に含める。verticalScroll と組み合わせると
        // フォーカスされた TextField が自動で IME の上に送り込まれる（隠れない）。
        modifier =
            Modifier.fillMaxSize()
                .padding(inner)
                .padding(16.dp)
                .imePadding()
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      OutlinedTextField(
          value = state.label,
          onValueChange = { v -> vm.update { it.copy(label = v) } },
          label = { Text(stringResource(R.string.host_label)) },
          singleLine = true,
          modifier = Modifier.fillMaxWidth(),
      )
      OutlinedTextField(
          value = state.address,
          onValueChange = { v -> vm.update { it.copy(address = v.trim()) } },
          label = { Text(stringResource(R.string.host_address)) },
          singleLine = true,
          keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false),
          modifier = Modifier.fillMaxWidth(),
      )
      Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(
            value = state.port,
            onValueChange = { v -> vm.update { it.copy(port = v.filter { ch -> ch.isDigit() }.take(5)) } },
            label = { Text(stringResource(R.string.host_port)) },
            singleLine = true,
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(120.dp),
        )
        OutlinedTextField(
            value = state.username,
            onValueChange = { v -> vm.update { it.copy(username = v.trim()) } },
            label = { Text(stringResource(R.string.host_user)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
      }

      Text(stringResource(R.string.host_auth))
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = state.auth == AuthMethod.PASSWORD,
            // auth 切り替えは既存 secret 流用を終了させる（型が変わるので流用不可）。
            onClick = {
              vm.update {
                val changed = it.auth != AuthMethod.PASSWORD
                it.copy(
                    auth = AuthMethod.PASSWORD,
                    preserveSecret = if (changed) false else it.preserveSecret,
                )
              }
            },
            label = { Text(stringResource(R.string.host_auth_password)) },
        )
        FilterChip(
            selected = state.auth == AuthMethod.PRIVATE_KEY,
            onClick = {
              vm.update {
                val changed = it.auth != AuthMethod.PRIVATE_KEY
                it.copy(
                    auth = AuthMethod.PRIVATE_KEY,
                    preserveSecret = if (changed) false else it.preserveSecret,
                )
              }
            },
            label = { Text(stringResource(R.string.host_auth_key)) },
        )
      }

      if (state.preserveSecret && state.originalAuth == state.auth) {
        Text(
            stringResource(R.string.host_secret_kept),
            style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
            color = androidx.compose.material3.MaterialTheme.colorScheme.onSurfaceVariant,
        )
      }

      when (state.auth) {
        AuthMethod.PASSWORD ->
            OutlinedTextField(
                value = state.password,
                // パスワードを 1 文字でも入れたら既存 secret の流用は中止。
                onValueChange = { v ->
                  vm.update {
                    it.copy(password = v, preserveSecret = if (v.isNotEmpty()) false else it.preserveSecret)
                  }
                },
                label = {
                  Text(
                      if (state.preserveSecret && state.originalAuth == AuthMethod.PASSWORD)
                          stringResource(R.string.host_password_change_only)
                      else stringResource(R.string.host_password),
                  )
                },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        AuthMethod.PRIVATE_KEY -> {
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { showSavedKeyPicker = true }) {
              Text(stringResource(R.string.host_pick_saved_key))
            }
            OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
              Text(state.keyFileName ?: stringResource(R.string.host_key_import))
            }
          }
          OutlinedTextField(
              value = state.keyPassphrase,
              onValueChange = { v -> vm.update { it.copy(keyPassphrase = v) } },
              label = { Text(stringResource(R.string.host_key_passphrase)) },
              singleLine = true,
              visualTransformation = PasswordVisualTransformation(),
              keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
              modifier = Modifier.fillMaxWidth(),
          )
          // 鍵が未設定・未作成のユーザが「何をすればいいか」を迷わないための導線。
          // ここに出さないと Settings → SSH 鍵ヘルプ まで 2 画面潜る必要があり、
          // 初心者は鍵の用意もできないまま詰む。
          Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onOpenKeyGen) { Text(stringResource(R.string.host_new_key)) }
            TextButton(onClick = onOpenKeyHelp) { Text(stringResource(R.string.host_key_help)) }
          }
        }
      }

      // tmux 統合（Phase 1: 接続直後に `tmux new -A -s <sessionName>\r` を自動送出）。
      // Switch と session 名入力を auth の種別と関係なく表示する。
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(stringResource(R.string.host_use_tmux))
        Switch(
            checked = state.useTmux,
            onCheckedChange = { v -> vm.update { it.copy(useTmux = v) } },
        )
      }
      if (state.useTmux) {
        OutlinedTextField(
            value = state.tmuxSession,
            onValueChange = { v ->
              vm.update { it.copy(tmuxSession = v.filter { ch -> ch.isLetterOrDigit() || ch == '_' || ch == '-' }) }
            },
            label = { Text(stringResource(R.string.host_tmux_session)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
      }

      Button(
          onClick = { vm.save(onDone) },
          enabled = state.isValid() && !state.isBusy,
          modifier = Modifier.fillMaxWidth(),
      ) { Text(stringResource(R.string.host_save)) }

      // 「保存」が disabled だと押せないのに理由がわからず戻ってしまう事故を防ぐ。
      // 不足している入力を具体的に示す。
      if (!state.isValid() && !state.isBusy) {
        val hint = validationHint(state)
        if (hint != null) {
          Text(
              stringResource(hint),
              color = androidx.compose.material3.MaterialTheme.colorScheme.error,
              style = androidx.compose.material3.MaterialTheme.typography.bodySmall,
          )
        }
      }
    }

    if (showDeleteConfirm) {
      ConfirmDialog(
          title = stringResource(R.string.hosts_delete_confirm_title),
          message = stringResource(R.string.hosts_delete_confirm_message),
          confirmLabel = stringResource(R.string.action_delete),
          onConfirm = {
            showDeleteConfirm = false
            vm.deleteSelf(onDone)
          },
          onDismiss = { showDeleteConfirm = false },
      )
    }

    // 同じ label / host / port / user のホストが既にあれば確認ダイアログを出す。
    // 「上書き」を選ぶと既存レコードを更新（pass → key 切替もこれで 1 レコードに収束）。
    state.duplicateHost?.let { dup ->
      androidx.compose.material3.AlertDialog(
          onDismissRequest = { vm.dismissDuplicate() },
          title = { Text(stringResource(R.string.host_duplicate_title)) },
          text = {
            Text(
                stringResource(
                    R.string.host_duplicate_body,
                    dup.label,
                    "${dup.username}@${dup.address}:${dup.port}",
                    stringResource(authNameOf(dup.auth.name)),
                    stringResource(authNameOf(state.auth.name)),
                ),
            )
          },
          confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { vm.confirmOverwrite(onDone) },
            ) {
              Text(stringResource(R.string.host_overwrite))
            }
          },
          dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.dismissDuplicate() }) {
              Text(stringResource(R.string.action_cancel))
            }
          },
      )
    }

    if (showSavedKeyPicker) {
      SavedKeyPickerSheet(
          onDismiss = { showSavedKeyPicker = false },
          onPick = { entity ->
            scope.launch {
              val loaded =
                  withContext(Dispatchers.IO) {
                    app.secretStore.loadPrivateKey(entity.secretId)
                  }
              if (loaded != null) {
                val (bytes, pass) = loaded
                // 選んだ鍵が sshj で読める形式か検証。古い PKCS8 Ed25519 など未対応形式は
                // ここで弾いてユーザに作り直しを促す。ホストに紐づける前に止める。
                val validation =
                    withContext(Dispatchers.IO) {
                      KeyValidator.validate(bytes, pass)
                    }
                if (validation.isFailure) {
                  keyInvalidMessage =
                      ctx.getString(R.string.host_saved_key_unreadable, entity.label)
                  showSavedKeyPicker = false
                  return@launch
                }
                vm.setKey(bytes, entity.label)
                if (!pass.isNullOrEmpty()) vm.update { it.copy(keyPassphrase = pass) }
              }
              showSavedKeyPicker = false
            }
          },
      )
    }

    keyInvalidMessage?.let { msg ->
      androidx.compose.material3.AlertDialog(
          onDismissRequest = { keyInvalidMessage = null },
          title = { Text(stringResource(R.string.host_key_unusable_title)) },
          text = { Text(msg) },
          confirmButton = {
            androidx.compose.material3.TextButton(onClick = { keyInvalidMessage = null }) {
              Text(stringResource(R.string.action_close))
            }
          },
      )
    }
  }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SavedKeyPickerSheet(
    onDismiss: () -> Unit,
    onPick: (SshKeyEntity) -> Unit,
) {
  val app = remember { AnotermApp.get() }
  val dao = remember { app.database.sshKeyDao() }
  val keys by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      androidx.compose.material3.Text(
          stringResource(R.string.host_pick_from_saved),
          style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
      )
      if (keys.isEmpty()) {
        androidx.compose.material3.Text(
            stringResource(R.string.host_no_saved_keys),
            style = androidx.compose.material3.MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(vertical = 16.dp),
        )
      } else {
        LazyColumn {
          items(keys, key = { it.id }) { k ->
            ListItem(
                headlineContent = { androidx.compose.material3.Text(k.label) },
                supportingContent = { androidx.compose.material3.Text(k.algo.uppercase()) },
                modifier = Modifier.fillMaxWidth().clickable { onPick(k) },
            )
          }
        }
      }
    }
  }
}

/**
 * 保存ボタンが押せない理由。足りていなければ文言のリソース ID、揃っていれば null。
 *
 * 「押せない → 理由が分からない → 戻る」で詰まるのを防ぐための表示なので、
 * どの入力が足りないかを具体的に示す。
 */
@StringRes
private fun validationHint(s: HostEditUiState): Int? {
  if (s.label.isBlank()) return R.string.hint_label
  if (s.address.isBlank()) return R.string.hint_address
  if (s.username.isBlank()) return R.string.hint_username
  val port = s.port.toIntOrNull()
  if (port == null || port !in 1..65535) return R.string.hint_port
  // 既存 secret を流用するなら credentials の再入力は不要
  if (s.preserveSecret && s.auth == s.originalAuth) return null
  return when (s.auth) {
    AuthMethod.PASSWORD -> R.string.hint_password
    AuthMethod.PRIVATE_KEY -> R.string.hint_private_key
  }
}

/** 認証方法の表示名。上書き確認で「何から何に変わるか」を示すのに使う。 */
@StringRes
private fun authNameOf(name: String): Int =
    if (name == "PASSWORD") R.string.auth_password else R.string.auth_private_key
