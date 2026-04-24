package com.example.wanoterm.i18n

import androidx.appcompat.app.AppCompatDelegate
import androidx.core.os.LocaleListCompat
import com.example.wanoterm.data.prefs.AppLocale

object LocaleManager {
  /** 現在アプリに適用されているロケールコードを読み取り（system / en / ja）。 */
  fun currentCode(): AppLocale {
    val list = AppCompatDelegate.getApplicationLocales()
    if (list.isEmpty) return AppLocale.SYSTEM
    return when (list.get(0)?.language) {
      "ja" -> AppLocale.JA
      "en" -> AppLocale.EN
      else -> AppLocale.SYSTEM
    }
  }

  /** プリファレンスを変更するだけでなく、即時反映する。Activity は再生成される。 */
  fun apply(loc: AppLocale) {
    val list =
        when (loc) {
          AppLocale.SYSTEM -> LocaleListCompat.getEmptyLocaleList()
          AppLocale.EN -> LocaleListCompat.forLanguageTags("en")
          AppLocale.JA -> LocaleListCompat.forLanguageTags("ja")
        }
    AppCompatDelegate.setApplicationLocales(list)
  }
}
