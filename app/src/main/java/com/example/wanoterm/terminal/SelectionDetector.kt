package com.example.wanoterm.terminal

import com.example.wanoterm.terminal.emulator.TerminalBuffer

/**
 * 画面末尾に Claude Code / Codex のような「`1. ...` / `2. ...` / `3. ...`」という
 * 選択肢プロンプトが出ているかを検出する。
 *
 * 戻り値は選択肢の最大番号 (1-9)。2 個以上の別々の番号が連続近傍で見つかった場合のみ非ゼロ。
 * 1 個しか無い（普通の箇条書き）の場合は 0 を返す — 誤検出を避けるため。
 *
 * UI 側はこの値 ≥ 2 の時だけ応答パレットを表示する。
 */
object SelectionDetector {
  // 例にマッチするプレフィックス:
  //   "1. "     "2) "     " ❯ 1. "     "│ 1. "    ">> 1) "
  // ただし "1234." のような長い数字は除外するため \d 1 桁に限定。
  private val pattern = Regex("""(?:^|[\s│❯>])(\d)[.)](?:\s|\z)""")

  /** buffer の下から 20 行を走査し、選択肢らしきものが 2 個以上あれば最大番号を返す。 */
  fun detect(buffer: TerminalBuffer): Int {
    val numbers = mutableSetOf<Int>()
    val rows = buffer.rows
    val start = maxOf(0, rows - 20)
    for (r in start until rows) {
      val line = rowToText(buffer, r).trimEnd()
      if (line.isEmpty()) continue
      // 選択肢が「1 行 1 個」のケース（縦並び）と「1 行に複数」のケース（横並び）の
      // 両方に対応するため findAll で全マッチを拾う。
      pattern.findAll(line).forEach { m ->
        val n = m.groupValues[1].toIntOrNull() ?: return@forEach
        if (n in 1..9) numbers.add(n)
      }
    }
    if (numbers.size < 2) return 0
    return numbers.max()
  }

  private fun rowToText(buffer: TerminalBuffer, row: Int): String {
    val sb = StringBuilder(buffer.cols)
    for (c in 0 until buffer.cols) {
      val cell = buffer.cellAt(row, c)
      // wide cell の右半分はスキップ（文字は左半分が保持）
      if (cell.continuation) continue
      if (cell.codePoint == 0) sb.append(' ')
      else sb.appendCodePoint(cell.codePoint)
    }
    return sb.toString()
  }
}
