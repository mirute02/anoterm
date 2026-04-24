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
import com.example.wanoterm.BuildConfig
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
    onOpenSshKeyList: () -> Unit,
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
      // 画面幅に入りきらないチップ（Dracula 等）が縦方向に潰れる bug があったので FlowRow で折返し。
      androidx.compose.foundation.layout.FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
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
      // SSH 鍵は以前 3 項目 (一覧/作成/使い方) に分かれていたが、一覧画面から
      // 作成 FAB・ヘルプアイコンでそれぞれに入れるよう統合。導線を 1 本化。
      ListItem(
          headlineContent = { Text("SSH 鍵") },
          supportingContent = { Text("一覧・作成・使い方（サーバ登録の手順付き）") },
          modifier = Modifier.clickable { onOpenSshKeyList() },
      )

      HorizontalDivider()
      val isPro by prefs.isPro.collectAsStateWithLifecycle()
      // Release ビルドでは Pro を切替える UI は出さない（Play Billing 経由の購入でのみ
      // unlock されるべき）。debug ビルドのみ手動トグルを許可して動作確認する。
      // ここをガードしないと SharedPreferences 直書きと同じで課金回避できてしまう。
      if (BuildConfig.DEBUG) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text(if (isPro) "Pro 版（有効・debug）" else "Pro 版切替（debug）")
          Switch(checked = isPro, onCheckedChange = { prefs.setPro(it) })
        }
      } else {
        Text(
            text = if (isPro) "Pro 版（有効）" else "Pro 版にアップグレード（近日対応）",
            style = MaterialTheme.typography.titleSmall,
        )
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

      // Claude Code / Codex の 1. 2. 3. 選択肢が画面に出たとき、下部に大ボタンで
      // 1/2/3 を即送出する機能。普段は隠れていて、選択肢を検出した時だけ表示される。
      val paletteEnabled by prefs.responsePaletteEnabled.collectAsStateWithLifecycle()
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Column(modifier = Modifier.weight(1f)) {
          Text("応答パレット（1/2/3 選択時に大ボタン）")
          Text(
              "Claude Code 等の選択肢プロンプトを検出した時だけ表示",
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant,
          )
        }
        Switch(
            checked = paletteEnabled,
            onCheckedChange = { prefs.setResponsePaletteEnabled(it) },
        )
      }

      // 開発者モード: Loopback 等のデバッグ UI を出すかどうか。debug build のみ露出。
      // release では AppPrefs.developerMode は存在しても UI は出さず、loopback は永遠に
      // 見えないまま（HostListScreen 側が BuildConfig.DEBUG && developerMode で gating）。
      if (BuildConfig.DEBUG) {
        HorizontalDivider()
        val devMode by prefs.developerMode.collectAsStateWithLifecycle()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
          Text("開発者モード（Loopback を表示）")
          Switch(checked = devMode, onCheckedChange = { prefs.setDeveloperMode(it) })
        }
      }
    }
  }
}
