package app.anoterm.terminal

import app.anoterm.terminal.emulator.Utf8Decoder
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * ユーザーがリモートに送ったバイト列を観察し、Enter（CR/LF）で区切られた「コマンド行」を記録する。
 *
 * - UTF-8 を復号し、制御文字（0x20 未満）は無視。
 * - Backspace（0x7F/0x08）は現在行の末尾 1 文字を消す（ローカルでも一応反映）。
 * - 同じコマンドを何度実行しても履歴は先頭に 1 件だけ残す（MRU）。
 * - 最大 [maxEntries] 件まで保持。
 *
 * ASCII 即コミット + Japanese composition の双方の経路を通ってきた bytes を全て通すため、
 * [TerminalSessionController.sendToRemote] のエントリポイントでこのクラスに流す。
 */
class CommandHistory(private val maxEntries: Int = 50) {
  private val _history = MutableStateFlow<List<String>>(emptyList())
  val history: StateFlow<List<String>> = _history.asStateFlow()

  private val current = StringBuilder()
  private val utf8 = Utf8Decoder()

  fun observe(bytes: ByteArray, length: Int = bytes.size) {
    var i = 0
    while (i < length) {
      val x = bytes[i].toInt() and 0xFF
      when (x) {
        0x0D, 0x0A -> {
          finishLine()
        }
        0x7F, 0x08 -> {
          if (current.isNotEmpty()) current.deleteCharAt(current.length - 1)
        }
        else -> {
          val cp = utf8.feed(bytes[i])
          if (cp != null && cp >= 0x20) current.appendCodePoint(cp)
        }
      }
      i++
    }
  }

  private fun finishLine() {
    val line = current.toString().trim()
    current.clear()
    utf8.reset()
    if (line.isEmpty()) return
    val prev = _history.value
    _history.value = (listOf(line) + prev.filterNot { it == line }).take(maxEntries)
  }

  fun clear() {
    current.clear()
    utf8.reset()
    _history.value = emptyList()
  }
}
