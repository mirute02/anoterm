package com.example.wanoterm.data.prefs

import android.content.Context
import android.content.SharedPreferences
import androidx.core.os.LocaleListCompat
import com.example.wanoterm.theme.TerminalThemeChoice
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

  private val _lineEnding = MutableStateFlow(readLineEnding())
  val lineEnding: StateFlow<LineEnding> = _lineEnding.asStateFlow()

  private val _locale = MutableStateFlow(readLocale())
  val locale: StateFlow<AppLocale> = _locale.asStateFlow()

  private val _biometricLockEnabled = MutableStateFlow(readBiometricLock())
  val biometricLockEnabled: StateFlow<Boolean> = _biometricLockEnabled.asStateFlow()

  private val _ambiguousWide = MutableStateFlow(readAmbiguousWide())
  val ambiguousWide: StateFlow<Boolean> = _ambiguousWide.asStateFlow()

  private val _customShortcuts = MutableStateFlow(readCustomShortcuts())
  val customShortcuts: StateFlow<List<CustomShortcut>> = _customShortcuts.asStateFlow()

  // プロセス kill 後に戻った時に自動で再接続するための最終タブ id。
  // SessionManager は Application スコープだが、プロセスが殺されると消えるので
  // SharedPreferences に永続化。
  private val _lastTabId = MutableStateFlow(readLastTabId())
  val lastTabId: StateFlow<String?> = _lastTabId.asStateFlow()

  // Pro エンタイトルメント。Google Play Billing が有効化されるまでは SharedPreferences
  // の値を使う。デバッグ時は設定画面から切替可能。
  private val _isPro = MutableStateFlow(readIsPro())
  val isPro: StateFlow<Boolean> = _isPro.asStateFlow()

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

  fun setAmbiguousWide(wide: Boolean) {
    sp.edit().putBoolean(KEY_AMBIGUOUS_WIDE, wide).apply()
    _ambiguousWide.value = wide
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

  fun setPro(pro: Boolean) {
    sp.edit().putBoolean(KEY_IS_PRO, pro).apply()
    _isPro.value = pro
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

  private fun readBiometricLock(): Boolean = sp.getBoolean(KEY_BIO_LOCK, false)

  private fun readAmbiguousWide(): Boolean = sp.getBoolean(KEY_AMBIGUOUS_WIDE, false)

  private fun readLastTabId(): String? = sp.getString(KEY_LAST_TAB_ID, null)

  private fun readIsPro(): Boolean = sp.getBoolean(KEY_IS_PRO, false)

  private fun readCustomShortcuts(): List<CustomShortcut> {
    val raw = sp.getString(KEY_CUSTOM_SHORTCUTS, null) ?: return DEFAULT_SHORTCUTS
    return try {
      Json.decodeFromString(kotlinx.serialization.builtins.ListSerializer(CustomShortcut.serializer()), raw)
    } catch (_: Throwable) {
      DEFAULT_SHORTCUTS
    }
  }

  companion object {
    private const val PREFS_NAME = "wanoterm_prefs"
    private const val KEY_THEME = "theme"
    private const val KEY_FONT_SIZE = "font_size_sp"
    private const val KEY_LINE_ENDING = "line_ending"
    private const val KEY_LOCALE = "locale"
    private const val KEY_BIO_LOCK = "biometric_lock"
    private const val KEY_AMBIGUOUS_WIDE = "ambiguous_wide"
    private const val KEY_CUSTOM_SHORTCUTS = "custom_shortcuts"
    private const val KEY_LAST_TAB_ID = "last_tab_id"
    private const val KEY_IS_PRO = "is_pro"

    // Free tier の上限。Pro で解除。
    const val FREE_TIER_HOST_LIMIT = 3
    const val FREE_TIER_TAB_LIMIT = 2
    const val DEFAULT_FONT_SIZE_SP = 14f

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
