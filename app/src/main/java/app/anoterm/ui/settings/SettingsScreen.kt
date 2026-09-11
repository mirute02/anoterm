package app.anoterm.ui.settings

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
import app.anoterm.BuildConfig
import app.anoterm.R
import app.anoterm.AnotermApp
import app.anoterm.data.prefs.AppLocale
import app.anoterm.data.prefs.AppPrefs
import app.anoterm.data.prefs.LineEnding
import app.anoterm.i18n.LocaleManager
import app.anoterm.theme.TerminalThemeChoice

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenKnownHosts: () -> Unit,
    onOpenCustomShortcuts: () -> Unit,
    onOpenSshKeyList: () -> Unit,
) {
  val app = remember { AnotermApp.get() }
  val prefs = app.prefs

  val theme by prefs.theme.collectAsStateWithLifecycle()
  val fontSizeSp by prefs.fontSizeSp.collectAsStateWithLifecycle()
  val lineEnding by prefs.lineEnding.collectAsStateWithLifecycle()
  val locale by prefs.locale.collectAsStateWithLifecycle()
  val lineSpacing by prefs.lineSpacing.collectAsStateWithLifecycle()
  val hideTmuxStatus by prefs.hideTmuxStatus.collectAsStateWithLifecycle()
  val replyPad by prefs.replyPadEnabled.collectAsStateWithLifecycle()
  val leftMargin by prefs.leftMarginDp.collectAsStateWithLifecycle()
  val splitVertical by prefs.splitVertical.collectAsStateWithLifecycle()
  val splitRatio by prefs.splitRatio.collectAsStateWithLifecycle()
  val bioLock by prefs.biometricLockEnabled.collectAsStateWithLifecycle()
  val lockGrace by prefs.lockGraceSeconds.collectAsStateWithLifecycle()
  val secureScreen by prefs.secureScreen.collectAsStateWithLifecycle()
  val ambiguous by prefs.ambiguousWide.collectAsStateWithLifecycle()
  val terminalClipboardHistoryEnabled by
      prefs.terminalClipboardHistoryEnabled.collectAsStateWithLifecycle()
  val claudeCodeFullscreen by prefs.claudeCodeFullscreen.collectAsStateWithLifecycle()
  val keepAlive by prefs.keepAliveSeconds.collectAsStateWithLifecycle()

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
                    stringResource(
                        when (l) {
                          AppLocale.SYSTEM -> R.string.language_system
                          // 言語名はその言語で書く。設定画面が何語で出ていても、
                          // 自分の言語を探している人が見つけられるように。
                          AppLocale.EN -> R.string.language_english
                          AppLocale.JA -> R.string.language_japanese
                        },
                    ),
                )
              },
          )
        }
      }

      HorizontalDivider()
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_line_spacing)) },
          supportingContent = {
            Column {
              Text(stringResource(R.string.settings_line_spacing_summary))
              Slider(
                  value = lineSpacing,
                  onValueChange = { prefs.setLineSpacing(it) },
                  valueRange = AppPrefs.MIN_LINE_SPACING..AppPrefs.MAX_LINE_SPACING,
                  // 0.05 刻み。連続だと再レイアウトが走り続けて重い。
                  steps = 11,
              )
            }
          },
          trailingContent = { Text(String.format("%.2f", lineSpacing)) },
      )

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
      // 再ロックまでの猶予。ロックが無効なら意味がないので操作させない。
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_lock_grace_title)) },
          supportingContent = {
            Column {
              Text(stringResource(R.string.settings_lock_grace_summary))
              Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                LOCK_GRACE_CHOICES.forEach { (seconds, label) ->
                  FilterChip(
                      selected = lockGrace == seconds,
                      enabled = bioLock,
                      onClick = { prefs.setLockGraceSeconds(seconds) },
                      label = { Text(stringResource(label)) },
                  )
                }
              }
            }
          },
      )
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_secure_screen_title)) },
          supportingContent = { Text(stringResource(R.string.settings_secure_screen_summary)) },
          trailingContent = {
            Switch(checked = secureScreen, onCheckedChange = { prefs.setSecureScreen(it) })
          },
      )
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_clipboard_history)) },
          supportingContent = {
            Text(stringResource(R.string.settings_clipboard_history_summary))
          },
          trailingContent = {
            Switch(
                checked = terminalClipboardHistoryEnabled,
                onCheckedChange = { prefs.setTerminalClipboardHistoryEnabled(it) },
            )
          },
      )
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_reply_pad)) },
          supportingContent = { Text(stringResource(R.string.settings_reply_pad_summary)) },
          trailingContent = {
            Switch(checked = replyPad, onCheckedChange = { prefs.setReplyPadEnabled(it) })
          },
      )
      HorizontalDivider()
      // 内側カメラは displayCutout で自動的に避ける。これはそれとは別の、
      // 端が詰まって見えるのが嫌なとき用の調整。
      Text(
          stringResource(R.string.settings_left_margin, leftMargin.toInt()),
          style = MaterialTheme.typography.bodyMedium,
      )
      Slider(
          value = leftMargin,
          valueRange = 0f..AppPrefs.MAX_LEFT_MARGIN_DP,
          steps = 11,
          onValueChange = { prefs.setLeftMarginDp(it) },
          modifier = Modifier.fillMaxWidth(),
      )

      HorizontalDivider()
      // 仕切りは指でも動かせるが、狙った配分にぴったり合わせるのは難しい。
      // 折りたたみを開いた時と閉じた時で好みが変わるので、ここでも決められるようにする。
      Text(stringResource(R.string.settings_split), style = MaterialTheme.typography.titleMedium)
      Text(
          stringResource(R.string.settings_split_orientation),
          style = MaterialTheme.typography.bodyMedium,
      )
      Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = splitVertical,
            onClick = { prefs.setSplitVertical(true) },
            label = { Text(stringResource(R.string.settings_split_vertical)) },
        )
        FilterChip(
            selected = !splitVertical,
            onClick = { prefs.setSplitVertical(false) },
            label = { Text(stringResource(R.string.settings_split_horizontal)) },
        )
      }
      Text(
          stringResource(
              R.string.settings_split_ratio,
              (splitRatio * 100).toInt(),
              100 - (splitRatio * 100).toInt(),
          ),
      )
      Slider(
          value = splitRatio,
          valueRange = AppPrefs.MIN_SPLIT_RATIO..AppPrefs.MAX_SPLIT_RATIO,
          steps = 11,
          onValueChange = { prefs.setSplitRatio(it) },
          modifier = Modifier.fillMaxWidth(),
      )
      HorizontalDivider()

      ListItem(
          headlineContent = { Text(stringResource(R.string.host_hide_tmux_status)) },
          supportingContent = { Text(stringResource(R.string.host_hide_tmux_status_summary)) },
          trailingContent = {
            Switch(checked = hideTmuxStatus, onCheckedChange = { prefs.setHideTmuxStatus(it) })
          },
      )
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_claude_code)) },
          supportingContent = {
            Text(
                stringResource(R.string.settings_claude_code_summary),
            )
          },
          trailingContent = {
            Switch(
                checked = claudeCodeFullscreen,
                onCheckedChange = { prefs.setClaudeCodeFullscreen(it) },
            )
          },
      )
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_known_hosts)) },
          modifier = Modifier.clickable { onOpenKnownHosts() },
      )
      ListItem(
          headlineContent = { Text(stringResource(R.string.shortcuts_title)) },
          modifier = Modifier.clickable { onOpenCustomShortcuts() },
      )
      // SSH 鍵は以前 3 項目 (一覧/作成/使い方) に分かれていたが、一覧画面から
      // 作成 FAB・ヘルプアイコンでそれぞれに入れるよう統合。導線を 1 本化。
      ListItem(
          headlineContent = { Text(stringResource(R.string.settings_ssh_keys)) },
          supportingContent = { Text(stringResource(R.string.settings_ssh_keys_summary)) },
          modifier = Modifier.clickable { onOpenSshKeyList() },
      )

      HorizontalDivider()
      Text(stringResource(R.string.settings_connection), style = MaterialTheme.typography.titleMedium)
      Text(stringResource(R.string.settings_keepalive), style = MaterialTheme.typography.bodyMedium)
      Text(
          stringResource(R.string.settings_keepalive_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
      androidx.compose.foundation.layout.FlowRow(
          horizontalArrangement = Arrangement.spacedBy(8.dp),
          verticalArrangement = Arrangement.spacedBy(4.dp),
      ) {
        listOf(0, 30, 60, 120, 300).forEach { s ->
          FilterChip(
              selected = keepAlive == s,
              onClick = { prefs.setKeepAliveSeconds(s) },
              label = { Text(if (s == 0) "OFF" else "${s}s") },
          )
        }
      }
      Text(
          stringResource(R.string.settings_autoreconnect_summary),
          style = MaterialTheme.typography.bodySmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )

      HorizontalDivider()
      Row(
          modifier = Modifier.fillMaxWidth(),
          horizontalArrangement = Arrangement.SpaceBetween,
      ) {
        Text(stringResource(R.string.settings_ambiguous_width))
        Switch(checked = ambiguous, onCheckedChange = { prefs.setAmbiguousWide(it) })
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
          Text(stringResource(R.string.settings_developer_mode))
          Switch(checked = devMode, onCheckedChange = { prefs.setDeveloperMode(it) })
        }
      }
    }
  }
}

private val LOCK_GRACE_CHOICES =
    listOf(
        0 to R.string.settings_lock_grace_immediate,
        30 to R.string.settings_lock_grace_30s,
        60 to R.string.settings_lock_grace_1m,
        300 to R.string.settings_lock_grace_5m,
    )
