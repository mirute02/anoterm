package com.example.wanoterm.ui.hosts

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.Computer
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.wanoterm.BuildConfig
import com.example.wanoterm.R

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun HostListScreen(
    onAddHost: () -> Unit,
    onEditHost: (Long) -> Unit,
    onOpenTerminal: (String) -> Unit,
    onOpenSettings: () -> Unit,
    vm: HostListViewModel = viewModel(factory = HostListViewModel.Factory),
) {
  val state by vm.state.collectAsStateWithLifecycle()

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_hosts)) },
            actions = {
              IconButton(onClick = onOpenSettings) {
                Icon(Icons.Filled.Settings, contentDescription = stringResource(R.string.nav_settings))
              }
            },
        )
      },
      floatingActionButton = {
        FloatingActionButton(onClick = onAddHost) {
          Icon(Icons.Filled.Add, contentDescription = stringResource(R.string.hosts_add))
        }
      },
  ) { inner ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
      if (BuildConfig.DEBUG) {
        item {
          ListItem(
              leadingContent = { Icon(Icons.Outlined.Computer, contentDescription = null) },
              headlineContent = { Text(stringResource(R.string.loopback_host_label)) },
              supportingContent = { Text("echo back / IME 動作確認用 (長押しで新規)") },
              modifier =
                  Modifier.fillMaxWidth().combinedClickable(
                      // タップ: 既存タブがあれば再利用（tabId 固定）、無ければ新規作成
                      onClick = { onOpenTerminal("loopback") },
                      // 長押し: 強制的に新規セッションを開く
                      onLongClick = { onOpenTerminal("loopback:${System.currentTimeMillis()}") },
                  ),
          )
          HorizontalDivider()
        }
      }
      if (state.hosts.isEmpty()) {
        item { EmptyHostsInline() }
      } else {
        items(state.hosts, key = { it.id }) { h ->
          ListItem(
              headlineContent = { Text(h.label) },
              supportingContent = { Text("${h.username}@${h.address}:${h.port} · 長押しで新規接続") },
              modifier =
                  Modifier.fillMaxWidth().combinedClickable(
                      // タップ: 既存タブがあれば再利用、新規なら作成
                      onClick = { onOpenTerminal("host:${h.id}") },
                      // 長押し: 新規 SSH 接続としてもう 1 タブ追加
                      onLongClick = { onOpenTerminal("host:${h.id}:${System.currentTimeMillis()}") },
                  ),
          )
        }
      }
    }
  }
}

@Composable
private fun EmptyHostsInline() {
  Column(
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(8.dp),
      modifier = Modifier.fillMaxWidth().padding(32.dp),
  ) {
    Text(
        stringResource(R.string.hosts_empty_title),
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        stringResource(R.string.hosts_empty_desc),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
  }
}

@Suppress("unused")
@Composable
private fun EmptyHosts(padding: PaddingValues) {
  Box(modifier = Modifier.fillMaxSize().padding(padding), contentAlignment = Alignment.Center) { EmptyHostsInline() }
}
