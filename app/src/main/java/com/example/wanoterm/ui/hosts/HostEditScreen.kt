package com.example.wanoterm.ui.hosts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import com.example.wanoterm.R
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.db.AuthMethod
import com.example.wanoterm.ui.common.ConfirmDialog
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
  val app = remember { WanotermApp.get() }
  val scope = rememberCoroutineScope()
  var showDeleteConfirm by remember { mutableStateOf(false) }
  var showSavedKeyPicker by remember { mutableStateOf(false) }
  // 秘密鍵が wanoterm（sshj）で読めない形式だった時に出す警告メッセージ。
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
                  com.example.wanoterm.ssh.KeyValidator.validate(bytes, null)
                }
            if (validation.isFailure &&
                validation.exceptionOrNull()?.message?.contains("passphrase", ignoreCase = true) != true) {
              keyInvalidMessage =
                  "このファイルは wanoterm で読めない形式です。\n\n" +
                      "対応形式: OpenSSH v1 (-----BEGIN OPENSSH PRIVATE KEY-----) または " +
                      "PKCS8 RSA。\n\n" +
                      "`ssh-keygen -t ed25519 -f newkey` などで作り直してから" +
                      "インポートし直してください。"
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
                TextButton(onClick = { showDeleteConfirm = true }) { Text("Delete") }
              }
            },
        )
      }
  ) { inner ->
    Column(
        modifier =
            Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
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
            "保存済みの認証情報を使用中（変更しない場合はそのまま保存）",
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
                          "パスワードを変更する場合のみ入力"
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
              Text("保存済みから選ぶ")
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
            TextButton(onClick = onOpenKeyGen) { Text("鍵を新規作成") }
            TextButton(onClick = onOpenKeyHelp) { Text("サーバへの登録方法") }
          }
        }
      }

      // tmux 統合（Phase 1: 接続直後に `tmux new -A -s <sessionName>\r` を自動送出）。
      // Switch と session 名入力を auth の種別と関係なく表示する。
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("tmux 統合（接続時に自動 attach）")
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
            label = { Text("tmux セッション名") },
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
        if (hint.isNotEmpty()) {
          Text(
              hint,
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
          confirmLabel = "Delete",
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
          title = { Text("同じホストが既にあります") },
          text = {
            Text(
                "「${dup.label}」(${dup.username}@${dup.address}:${dup.port}) は登録済みです。"
                    + "既存のホストを上書きしますか?\n\n"
                    + "上書きすると入力中の認証情報（${if (dup.auth.name == "PASSWORD") "パスワード" else "秘密鍵"} → "
                    + "${if (state.auth.name == "PASSWORD") "パスワード" else "秘密鍵"}）に置き換わります。",
            )
          },
          confirmButton = {
            androidx.compose.material3.TextButton(
                onClick = { vm.confirmOverwrite(onDone) },
            ) {
              Text("上書き")
            }
          },
          dismissButton = {
            androidx.compose.material3.TextButton(onClick = { vm.dismissDuplicate() }) {
              Text("キャンセル")
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
                      com.example.wanoterm.ssh.KeyValidator.validate(bytes, pass)
                    }
                if (validation.isFailure) {
                  keyInvalidMessage =
                      "「${entity.label}」は wanoterm で読めない形式です。" +
                          "古いバージョンで作成された Ed25519 鍵の可能性があります。\n\n" +
                          "『SSH 鍵を作成』から新しい鍵を作り直し、サーバの authorized_keys に" +
                          "再登録してから使ってください。"
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
          title = { Text("この鍵は使えません") },
          text = { Text(msg) },
          confirmButton = {
            androidx.compose.material3.TextButton(onClick = { keyInvalidMessage = null }) {
              Text("閉じる")
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
    onPick: (com.example.wanoterm.data.db.SshKeyEntity) -> Unit,
) {
  val app = remember { WanotermApp.get() }
  val dao = remember { app.database.sshKeyDao() }
  val keys by dao.observeAll().collectAsStateWithLifecycle(initialValue = emptyList())
  ModalBottomSheet(onDismissRequest = onDismiss) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
      androidx.compose.material3.Text(
          "保存済みの SSH 鍵から選ぶ",
          style = androidx.compose.material3.MaterialTheme.typography.titleMedium,
      )
      if (keys.isEmpty()) {
        androidx.compose.material3.Text(
            "まだ鍵が保存されていません。設定 → SSH 鍵を作成 から作成してください。",
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

/** 保存ボタンが disabled の理由を日本語で返す。ユーザが「押せない→戻る」で詰まるのを防ぐ。 */
private fun validationHint(s: HostEditUiState): String {
  if (s.label.isBlank()) return "ラベルを入力してください"
  if (s.address.isBlank()) return "ホスト名 / IP を入力してください"
  if (s.username.isBlank()) return "ユーザー名を入力してください"
  val port = s.port.toIntOrNull()
  if (port == null || port !in 1..65535) return "ポートは 1〜65535 の数値にしてください"
  // 既存 secret を流用するなら credentials の再入力は不要
  if (s.preserveSecret && s.auth == s.originalAuth) return ""
  return when (s.auth) {
    AuthMethod.PASSWORD -> "パスワードを入力してください（保存ボタンが有効になります）"
    AuthMethod.PRIVATE_KEY -> "秘密鍵ファイルを選択してください（インポート / 保存済みから選ぶ）"
  }
}
