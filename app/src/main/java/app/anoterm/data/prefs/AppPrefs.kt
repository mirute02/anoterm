package app.anoterm.data.prefs

import app.anoterm.util.CharWidth
import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.LocaleListCompat
import app.anoterm.theme.TerminalThemeChoice
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.serialization.json.Json

enum class LineEnding(val bytes: ByteArray) {
  CR(byteArrayOf(0x0D)),
  LF(byteArrayOf(0x0A)),
  CRLF(byteArrayOf(0x0D, 0x0A)),
}

enum class AppLocale(val tag: String) {
  SYSTEM(""),
  EN("en"),
  JA("ja"),
}

/** 端末設定とアプリ設定の一括保存。非機密のみ扱う（鍵やパスワードは SecretStore へ）。 */
class AppPrefs(context: Context) {
  private val sp: SharedPreferences =
      context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

  private val _theme = MutableStateFlow(readTheme())
  val theme: StateFlow<TerminalThemeChoice> = _theme.asStateFlow()

  private val _fontSizeSp = MutableStateFlow(readFontSize())
  val fontSizeSp: StateFlow<Float> = _fontSizeSp.asStateFlow()

  // 行の高さの倍率。フォントの推奨行送りは端末向けに詰まっており、小さい画面で
  // 長時間読むには窮屈になる。1.0 が従来どおり。
  private val _lineSpacing = MutableStateFlow(readLineSpacing())
  val lineSpacing: StateFlow<Float> = _lineSpacing.asStateFlow()

  // tmux 自身のステータス行を隠すか。アプリのバーが同じ情報を出しているので、
  // 狭い画面では 1 行ぶんの重複になる。
  private val _hideTmuxStatus = MutableStateFlow(readHideTmuxStatus())
  val hideTmuxStatus: StateFlow<Boolean> = _hideTmuxStatus.asStateFlow()

  private val _lineEnding = MutableStateFlow(readLineEnding())
  val lineEnding: StateFlow<LineEnding> = _lineEnding.asStateFlow()

  private val _locale = MutableStateFlow(readLocale())
  val locale: StateFlow<AppLocale> = _locale.asStateFlow()

  private val _biometricLockEnabled = MutableStateFlow(readBiometricLock())
  val biometricLockEnabled: StateFlow<Boolean> = _biometricLockEnabled.asStateFlow()

  // 背面に回ってから再ロックするまでの猶予。0 なら即時。
  // 通知を見る、パスワードマネージャを開くといった数秒の離席で毎回生体認証を
  // 求められると、利用者はロックそのものを切ってしまう。
  private val _lockGraceSeconds = MutableStateFlow(readLockGraceSeconds())
  val lockGraceSeconds: StateFlow<Int> = _lockGraceSeconds.asStateFlow()

  // FLAG_SECURE。スクリーンショットと「最近使ったアプリ」のサムネイルを禁じる。
  // これが無いと、生体認証を通さなくてもタスク一覧に端末の中身が見える。
  private val _secureScreen = MutableStateFlow(readSecureScreen())
  val secureScreen: StateFlow<Boolean> = _secureScreen.asStateFlow()

  private val _ambiguousWide = MutableStateFlow(readAmbiguousWide())
  val ambiguousWide: StateFlow<Boolean> = _ambiguousWide.asStateFlow()

  private val _customShortcuts = MutableStateFlow(readCustomShortcuts())
  val customShortcuts: StateFlow<List<CustomShortcut>> = _customShortcuts.asStateFlow()

  // プロセス kill 後に戻った時に自動で再接続するための最終タブ id。
  // SessionManager は Application スコープだが、プロセスが殺されると消えるので
  // SharedPreferences に永続化。
  private val _lastTabId = MutableStateFlow(readLastTabId())
  val lastTabId: StateFlow<String?> = _lastTabId.asStateFlow()

  // 開発者モード。Loopback チャネル等のデバッグ専用 UI を出すかどうか。
  // debug ビルドでのみ Settings にトグルが出る（release では false 固定）。
  private val _developerMode = MutableStateFlow(readDeveloperMode())
  val developerMode: StateFlow<Boolean> = _developerMode.asStateFlow()

  // ターミナルでコピーしたテキストを Gboard 等のクリップボード履歴に残しやすくする。
  // true のとき TerminalView で IME_FLAG_NO_PERSONALIZED_LEARNING だけを外す。
  // デフォルトは false。SSH セッションでは入力やコピーに秘密情報が混ざる可能性があるため。
  private val _terminalClipboardHistoryEnabled =
      MutableStateFlow(readTerminalClipboardHistoryEnabled())
  val terminalClipboardHistoryEnabled: StateFlow<Boolean> =
      _terminalClipboardHistoryEnabled.asStateFlow()

  // Claude Code 等の TUI が non-fullscreen モードで吐く redraw が tmux/scrollback に
  // 蓄積されて「同じ応答が 3〜4 回ループする」現象を防ぐ。true のときは接続後に
  // `export CLAUDE_CODE_NO_FLICKER=1` を送って Claude Code を fullscreen モードで起動させる。
  // デフォルトは true (= 防御を ON で始める)。Aider 等の他 TUI には別途設定が要るので
  // ここはあくまで Claude Code 専用の予防策。
  private val _claudeCodeFullscreen = MutableStateFlow(readClaudeCodeFullscreen())
  val claudeCodeFullscreen: StateFlow<Boolean> = _claudeCodeFullscreen.asStateFlow()

  // SSH keepalive 間隔（秒）。短いほど切断検知・自動再接続が速いが、モバイル無線の
  // ウェイクアップが増えて電池を食う。0 = 無効。既定 60 は電池と反応の折衷。
  private val _keepAliveSeconds = MutableStateFlow(readKeepAliveSeconds())
  val keepAliveSeconds: StateFlow<Int> = _keepAliveSeconds.asStateFlow()

  /** Lock 画面に遷移するかどうかを算出するためのフロー（Navigation から購読） */
  val biometricLock: Flow<Boolean>
    get() = _biometricLockEnabled.asStateFlow()

  fun setTheme(t: TerminalThemeChoice) {
    sp.edit().putString(KEY_THEME, t.name).apply()
    _theme.value = t
  }

  fun setFontSizeSp(value: Float) {
    val clamped = value.coerceIn(8f, 28f)
    sp.edit().putFloat(KEY_FONT_SIZE, clamped).apply()
    _fontSizeSp.value = clamped
  }

  fun setHideTmuxStatus(hide: Boolean) {
    sp.edit().putBoolean(KEY_HIDE_TMUX_STATUS, hide).apply()
    _hideTmuxStatus.value = hide
  }

  fun setLineSpacing(multiplier: Float) {
    val clamped = multiplier.coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)
    sp.edit().putFloat(KEY_LINE_SPACING, clamped).apply()
    _lineSpacing.value = clamped
  }

  fun setLineEnding(le: LineEnding) {
    sp.edit().putString(KEY_LINE_ENDING, le.name).apply()
    _lineEnding.value = le
  }

  fun setLocale(loc: AppLocale) {
    sp.edit().putString(KEY_LOCALE, loc.name).apply()
    _locale.value = loc
  }

  fun setBiometricLockEnabled(enabled: Boolean) {
    sp.edit().putBoolean(KEY_BIO_LOCK, enabled).apply()
    _biometricLockEnabled.value = enabled
  }

  fun setLockGraceSeconds(seconds: Int) {
    val clamped = seconds.coerceIn(0, MAX_LOCK_GRACE_SECONDS)
    sp.edit().putInt(KEY_LOCK_GRACE_SECONDS, clamped).apply()
    _lockGraceSeconds.value = clamped
  }

  fun setSecureScreen(enabled: Boolean) {
    sp.edit().putBoolean(KEY_SECURE_SCREEN, enabled).apply()
    _secureScreen.value = enabled
  }

  fun setAmbiguousWide(wide: Boolean) {
    sp.edit().putBoolean(KEY_AMBIGUOUS_WIDE, wide).apply()
    _ambiguousWide.value = wide
    // 描画側は CharWidth を直接引くので、設定を保持するだけでは効かない。
    CharWidth.ambiguousWide = wide
  }

  fun setCustomShortcuts(list: List<CustomShortcut>) {
    val json = Json.encodeToString(kotlinx.serialization.builtins.ListSerializer(CustomShortcut.serializer()), list)
    sp.edit().putString(KEY_CUSTOM_SHORTCUTS, json).apply()
    _customShortcuts.value = list
  }

  fun setLastTabId(tabId: String?) {
    if (tabId == null) sp.edit().remove(KEY_LAST_TAB_ID).apply()
    else sp.edit().putString(KEY_LAST_TAB_ID, tabId).apply()
    _lastTabId.value = tabId
  }

  fun setDeveloperMode(enabled: Boolean) {
    sp.edit().putBoolean(KEY_DEV_MODE, enabled).apply()
    _developerMode.value = enabled
  }

  fun setTerminalClipboardHistoryEnabled(enabled: Boolean) {
    sp.edit()
        .putBoolean(KEY_TERMINAL_CLIPBOARD_HISTORY_ENABLED, enabled)
        .remove(KEY_GBOARD_CLIPBOARD_HISTORY_PROBE_LEGACY)
        .apply()
    _terminalClipboardHistoryEnabled.value = enabled
  }

  fun setClaudeCodeFullscreen(enabled: Boolean) {
    sp.edit().putBoolean(KEY_CLAUDE_CODE_FULLSCREEN, enabled).apply()
    _claudeCodeFullscreen.value = enabled
  }

  fun setKeepAliveSeconds(seconds: Int) {
    val v = seconds.coerceIn(0, 600)
    sp.edit().putInt(KEY_KEEPALIVE_SECONDS, v).apply()
    _keepAliveSeconds.value = v
  }

  fun localeList(): LocaleListCompat =
      when (_locale.value) {
        AppLocale.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
        AppLocale.EN -> LocaleListCompat.forLanguageTags("en")
        AppLocale.JA -> LocaleListCompat.forLanguageTags("ja")
      }

  /** Any SharedPreferences edit notifies this flow (for reactive settings screens). */
  fun anyChange(): Flow<Unit> =
      callbackFlow<Unit> {
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> trySend(Unit) }
        sp.registerOnSharedPreferenceChangeListener(listener)
        awaitClose { sp.unregisterOnSharedPreferenceChangeListener(listener) }
      }

  private fun readTheme(): TerminalThemeChoice =
      try {
        TerminalThemeChoice.valueOf(sp.getString(KEY_THEME, TerminalThemeChoice.TokyoNight.name)!!)
      } catch (_: Throwable) {
        TerminalThemeChoice.TokyoNight
      }

  private fun readFontSize(): Float = sp.getFloat(KEY_FONT_SIZE, DEFAULT_FONT_SIZE_SP)

  private fun readLineEnding(): LineEnding =
      try {
        LineEnding.valueOf(sp.getString(KEY_LINE_ENDING, LineEnding.CR.name)!!)
      } catch (_: Throwable) {
        LineEnding.CR
      }

  private fun readLocale(): AppLocale =
      try {
        AppLocale.valueOf(sp.getString(KEY_LOCALE, AppLocale.SYSTEM.name)!!)
      } catch (_: Throwable) {
        AppLocale.SYSTEM
      }

  private fun readHideTmuxStatus(): Boolean = sp.getBoolean(KEY_HIDE_TMUX_STATUS, false)

  private fun readLineSpacing(): Float =
      sp.getFloat(KEY_LINE_SPACING, DEFAULT_LINE_SPACING).coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING)

  private fun readBiometricLock(): Boolean = sp.getBoolean(KEY_BIO_LOCK, false)

  private fun readLockGraceSeconds(): Int =
      sp.getInt(KEY_LOCK_GRACE_SECONDS, DEFAULT_LOCK_GRACE_SECONDS).coerceIn(0, MAX_LOCK_GRACE_SECONDS)

  // 既定で有効。SSH の画面はほぼ常に人に見せたくないものが出ているので、
  // 撮れないことより見えてしまうことの方が困る。設定で切れる。
  private fun readSecureScreen(): Boolean = sp.getBoolean(KEY_SECURE_SCREEN, true)

  private fun readAmbiguousWide(): Boolean = sp.getBoolean(KEY_AMBIGUOUS_WIDE, false)

  private fun readLastTabId(): String? = sp.getString(KEY_LAST_TAB_ID, null)

  private fun readDeveloperMode(): Boolean = sp.getBoolean(KEY_DEV_MODE, false)

  // 新規インストール時のデフォルトは true (= 履歴に残す)。SSH クライアント業界標準
  // (Termius / Blink) と挙動を揃え、初見ユーザの「コピーが Gboard 履歴に出ない」混乱を避ける。
  // 機密入力を打つホストでは Settings から個別に OFF にする運用。
  private fun readTerminalClipboardHistoryEnabled(): Boolean =
      if (sp.contains(KEY_TERMINAL_CLIPBOARD_HISTORY_ENABLED)) {
        sp.getBoolean(KEY_TERMINAL_CLIPBOARD_HISTORY_ENABLED, true)
      } else {
        sp.getBoolean(KEY_GBOARD_CLIPBOARD_HISTORY_PROBE_LEGACY, true)
      }

  private fun readClaudeCodeFullscreen(): Boolean =
      sp.getBoolean(KEY_CLAUDE_CODE_FULLSCREEN, true)

  private fun readKeepAliveSeconds(): Int = sp.getInt(KEY_KEEPALIVE_SECONDS, DEFAULT_KEEPALIVE_SECONDS)

  private fun readCustomShortcuts(): List<CustomShortcut> {
    val raw = sp.getString(KEY_CUSTOM_SHORTCUTS, null) ?: return DEFAULT_SHORTCUTS
    return try {
      Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CustomShortcut.serializer()), raw)
    } catch (_: Throwable) {
      DEFAULT_SHORTCUTS
    }
  }

  companion object {
    private const val PREFS_NAME = "anoterm_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_LINE_ENDING = "line_ending"
    private const val KEY_LOCALE = "locale"
    private const val KEY_BIO_LOCK = "biometric_lock"
    private const val KEY_AMBIGUOUS_WIDE = "ambiguous_wide"
    private const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
    private const val KEY_LAST_TAB_ID = "last_tab_id"
    private const val KEY_DEV_MODE = "developer_mode"
    private const val KEY_TERMINAL_CLIPBOARD_HISTORY_ENABLED = "terminal_clipboard_history_enabled"
    private const val KEY_GBOARD_CLIPBOARD_HISTORY_PROBE_LEGACY = "gboard_clipboard_history_probe"
    private const val KEY_CLAUDE_CODE_FULLSCREEN = "claude_code_fullscreen"
    private const val KEY_KEEPALIVE_SECONDS = "keepalive_seconds"
    private const val KEY_LINE_SPACING = "line_spacing"
    private const val KEY_HIDE_TMUX_STATUS = "hide_tmux_status"
    private const val KEY_LOCK_GRACE_SECONDS = "lock_grace_seconds"
    private const val KEY_SECURE_SCREEN = "secure_screen"

    const val DEFAULT_FONT_SIZE_SP = 14f
    const val DEFAULT_KEEPALIVE_SECONDS = 60
    const val DEFAULT_LOCK_GRACE_SECONDS = 30

    // 既定を 1.0 より上げる。詰まった行送りは端末の慣習だが、スマホの画面で
    // 読むには窮屈で、1 行増やして得られる情報より読みやすさの方が効く。
    const val DEFAULT_LINE_SPACING = 1.15f
    const val MIN_LINE_SPACING = 1.0f
    const val MAX_LINE_SPACING = 1.6f
    const val MAX_LOCK_GRACE_SECONDS = 300

    // companion object に置くことでインスタンスプロパティの初期化順に依存しない。
    // インスタンス側の `_customShortcuts = MutableStateFlow(readCustomShortcuts())` から
    // 参照されても常に非 null。
    private val DEFAULT_SHORTCUTS: List<CustomShortcut> =
        listOf(
            CustomShortcut("ls", "ls"),
            CustomShortcut("ll", "ls -lah"),
            CustomShortcut("clear", "clear"),
        )
  }
}
