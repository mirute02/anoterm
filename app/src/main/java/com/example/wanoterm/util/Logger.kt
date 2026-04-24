package com.example.wanoterm.util

import android.util.Log
import com.example.wanoterm.BuildConfig

/**
 * wanoterm 全体のロギングヘルパ。
 *
 * セキュリティ方針:
 * - `d` / `i` は release ビルドでは何も出さない（`BuildConfig.DEBUG` ガード）。
 *   これは logcat に SSH の受送バイト・IME の中身など**機密を含む可能性がある文字列**が
 *   リリース端末で流れないようにするため。
 * - `w` / `e` はビルド種別に関わらず出す（障害解析用）。ただし内容は機密を含まない
 *   短いメッセージ／例外のみ。本文や hex dump は載せない。
 * - `bytesPreview` / `textPreview` は機密データを露出させるため削除。
 *   どうしてもデバッグで見たい場合はビルドに一時的に入れて消すポリシー。
 */
object Logger {
  private const val TAG = "wanoterm"

  fun d(subtag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.d(TAG, "[$subtag] $msg")
  }

  fun i(subtag: String, msg: String) {
    if (BuildConfig.DEBUG) Log.i(TAG, "[$subtag] $msg")
  }

  fun w(subtag: String, msg: String, t: Throwable? = null) = Log.w(TAG, "[$subtag] $msg", t)

  fun e(subtag: String, msg: String, t: Throwable? = null) = Log.e(TAG, "[$subtag] $msg", t)
}
