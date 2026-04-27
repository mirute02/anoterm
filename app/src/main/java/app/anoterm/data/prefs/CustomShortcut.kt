package app.anoterm.data.prefs

import kotlinx.serialization.Serializable

/**
 * ユーザーが登録する任意のショートカット。
 *
 * - [label]: チップに表示する短い名前（例：`deploy`, `tail log`）。
 * - [text]: 送信する文字列。末尾に `\n` を含めればタップで即実行、含めなければ入力欄に置くだけ。
 *   エスケープシーケンスも UTF-8 文字列として書ける（例：`[A` で矢印↑）。
 * - [appendEnter]: true なら送信時に Enter（LineEnding 設定に従う）を自動で足す。
 *   label/text を単純な定義に保ちつつ「タップで実行」「タップで挿入だけ」を切り替えられる。
 */
@Serializable
data class CustomShortcut(
    val label: String,
    val text: String,
    val appendEnter: Boolean = true,
)
