package com.example.wanoterm.ui.settings

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.example.wanoterm.R
import com.example.wanoterm.WanotermApp
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KnownHostsScreen(onBack: () -> Unit) {
  val app = remember { WanotermApp.get() }
  val dao = app.database.knownHostDao()
  val scope = rememberCoroutineScope()
  val entries by dao.observeAll().collectAsState(initial = emptyList())

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.settings_known_hosts)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
        )
      }
  ) { inner ->
    LazyColumn(modifier = Modifier.fillMaxSize().padding(inner)) {
      items(entries, key = { it.id }) { e ->
        ListItem(
            headlineContent = { Text("${e.address}:${e.port}") },
            supportingContent = { Text("${e.keyType}  ${e.fingerprintSha256}") },
            trailingContent = {
              IconButton(
                  onClick = { scope.launch { dao.deleteById(e.id) } },
              ) { Icon(Icons.Filled.Delete, contentDescription = null) }
            },
        )
      }
    }
  }
}
