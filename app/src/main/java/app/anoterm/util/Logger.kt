package app.anoterm.util

import android.util.Log
import app.anoterm.BuildConfig

/**
 * anoterm 全体のロギングヘルパ。
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
  private const val TAG = "anoterm"

  fun d(subtag: String, msg: String) {
    if (BuildConfig.DEBUG) safely { Log.d(TAG, "[$subtag] $msg") }
  }

  fun i(subtag: String, msg: String) {
    if (BuildConfig.DEBUG) safely { Log.i(TAG, "[$subtag] $msg") }
  }

  fun w(subtag: String, msg: String, t: Throwable? = null) {
    safely { Log.w(TAG, "[$subtag] $msg", t) }
  }

  fun e(subtag: String, msg: String, t: Throwable? = null) {
    safely { Log.e(TAG, "[$subtag] $msg", t) }
  }

  /**
   * ログを書くこと自体で落ちないようにする。
   *
   * `android.util.Log` は JVM のユニットテストでは実装が無く、呼ぶと
   * "not mocked" の [RuntimeException] を投げる。テストのためだけの配慮ではなく、
   * 記録を残そうとした処理が記録に失敗して巻き添えで落ちる、というのがそもそも
   * 筋が悪い。ここで握り潰す。
   */
  private inline fun safely(block: () -> Unit) {
    try {
      block()
    } catch (_: Throwable) {
      // 出力先が無い。呼び出し元の処理は続ける。
    }
  }
}
