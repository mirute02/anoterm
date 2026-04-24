package com.example.wanoterm.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wanoterm.R
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.prefs.AppLocale
import com.example.wanoterm.data.prefs.LineEnding
import com.example.wanoterm.i18n.LocaleManager
import com.example.wanoterm.theme.TerminalThemeChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKnownHosts: () -> Unit,
    onOpenCustomShortcuts: () -> Unit,
    onOpenSshKeyHelp: () -> Unit,
) {
  val app = remember { WanotermApp.get() }
  val prefs = app.prefs

  val theme by prefs.theme.collectAsStateWithLifecycle()
  val fontSizeSp by prefs.fontSizeSp.collectAsStateWithLifecycle()
  val lineEnding by prefs.lineEnding.collectAsStateWithLifecycle()
  val locale by prefs.locale.collectAsStateWithLifecycle()
  val bioLock by prefs.biometricLockEnabled.collectAsStateWithLifecycle()
  val ambiguous by prefs.ambiguousWide.collectAsStateWithLifecycle()

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text(stringResource(R.string.nav_settings)) },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
        )
      }
  ) { inner ->
    Column(
        modifier =
            Modifier.fillMaxSize().padding(inner).padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
      Text(stringResource(R.string.settings_appearance), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.settings_theme), style = MaterialTheme.typography.bodyMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TerminalThemeChoice.entries.forEach { t ->
          FilterChip(
              selected = theme == t,
              onClick = { prefs.setTheme(t) },
              label = { Text(t.displayName) },
          )
        }
      }
      Text("${stringResource(R.string.settings_font_size)}: ${fontSizeSp.toInt()}sp")
      Slider(
          value = fontSizeSp,
          valueRange = 8f..28f,
          steps = 19,
          onValueChange = { prefs.setFontSizeSp(it) },
          modifier = Modifier.fillMaxWidth(),
      )

      HorizontalDivider()
      Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        AppLocale.entries.forEach { l ->
          FilterChip(
              selected = locale == l,
              onClick = {
                prefs.setLocale(l)
                LocaleManager.apply(l)
              },
              label = {
                Text(
                    when (l) {
                      AppLocale.SYSTEM -> "System"
                      AppLocale.EN -> "English"
                      AppLocale.JA -> "日本語"
                    },
                )
              },
          )
        }
      }

      HorizontalDivider()
      Text(stringResource(R.string.settings_line_ending), style = MaterialTheme.typography.titleMedium)
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        LineEnding.entries.forEach { le ->
          FilterChip(
              selected = lineEnding == le,
              onClick = { prefs.setLineEnding(le) },
              label = {
                Text(
                    when (le) {
                      LineEnding.CR -> "CR"
                      LineEnding.LF -> "LF"
                      LineEnding.CRLF -> "CR LF"
                    },
                )
              },
          )
        }
      }

      HorizontalDivider()
      Text(stringResource(R.string.settings_security), style = MaterialTheme.typography.titleMedium)
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(stringResource(R.string.settings_biometric_lock))
        Switch(checked = bioLock, onCheckedChange = { prefs.setBiometricLockEnabled(it) })
      }
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_known_hosts)) },
          modifier = Modifier.clickable { onOpenKnownHosts() },
      )
      ListItem(
          headlineContent = { Text("カスタムショートカット") },
          modifier = Modifier.clickable { onOpenCustomShortcuts() },
      )
      ListItem(
          headlineContent = { Text("SSH 鍵の使い方") },
          supportingContent = { Text("作成・インポート・サーバへの登録方法") },
          modifier = Modifier.clickable { onOpenSshKeyHelp() },
      )

      HorizontalDivider()
      val isPro by prefs.isPro.collectAsStateWithLifecycle()
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(if (isPro) "Pro 版（有効）" else "Pro 版にアップグレード")
        Switch(checked = isPro, onCheckedChange = { prefs.setPro(it) })
      }
      Text(
          text = if (isPro) "ホスト・タブ無制限、tmux / SFTP 等が解放されています。" else "Free 版: ホスト ${com.example.wanoterm.data.prefs.AppPrefs.FREE_TIER_HOST_LIMIT} 個 / 同時タブ ${com.example.wanoterm.data.prefs.AppPrefs.FREE_TIER_TAB_LIMIT} 個まで。",
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          modifier = Modifier.padding(start = 4.dp),
      )

      HorizontalDivider()
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text("East-Asian ambiguous width → 2 cells")
        Switch(checked = ambiguous, onCheckedChange = { prefs.setAmbiguousWide(it) })
      }
    }
  }
}
