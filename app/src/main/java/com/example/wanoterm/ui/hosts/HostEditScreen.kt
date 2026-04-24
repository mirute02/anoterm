package com.example.wanoterm.ui.hosts

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import com.example.wanoterm.data.db.AuthMethod
import com.example.wanoterm.ui.common.ConfirmDialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HostEditScreen(
    hostId: Long?,
    onDone: () -> Unit,
    vm: HostEditViewModel = viewModel(factory = HostEditViewModel.factory(hostId)),
) {
  val state by vm.state.collectAsStateWithLifecycle()
  val ctx = LocalContext.current
  val scope = rememberCoroutineScope()
  var showDeleteConfirm by remember { mutableStateOf(false) }

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
          if (bytes != null) vm.setKey(bytes, name)
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
            onClick = { vm.update { it.copy(auth = AuthMethod.PASSWORD) } },
            label = { Text(stringResource(R.string.host_auth_password)) },
        )
        FilterChip(
            selected = state.auth == AuthMethod.PRIVATE_KEY,
            onClick = { vm.update { it.copy(auth = AuthMethod.PRIVATE_KEY) } },
            label = { Text(stringResource(R.string.host_auth_key)) },
        )
      }

      when (state.auth) {
        AuthMethod.PASSWORD ->
            OutlinedTextField(
                value = state.password,
                onValueChange = { v -> vm.update { it.copy(password = v) } },
                label = { Text(stringResource(R.string.host_password)) },
                singleLine = true,
                visualTransformation = PasswordVisualTransformation(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        AuthMethod.PRIVATE_KEY -> {
          OutlinedButton(onClick = { keyPicker.launch(arrayOf("*/*")) }) {
            Text(state.keyFileName ?: stringResource(R.string.host_key_import))
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
        }
      }

      Button(
          onClick = { vm.save(onDone) },
          enabled = state.isValid() && !state.isBusy,
          modifier = Modifier.fillMaxWidth(),
      ) { Text(stringResource(R.string.host_save)) }
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
  }
}
