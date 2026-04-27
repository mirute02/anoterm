package app.anoterm.terminal.emulator

/**
 * バイト列から code point を徐々に組み立てる最小 UTF-8 デコーダ。
 *
 * 不正バイト列は U+FFFD に置き換える。状態は `feed` 間で保持されるため、チャネルから到着する
 * 途中バイトでも破綻しない。
 */
class Utf8Decoder {
  private var remaining: Int = 0
  private var acc: Int = 0
  private var minValid: Int = 0

  /** 1 バイト入力。完成したら code point、未完ならば null を返す。 */
  fun feed(b: Byte): Int? {
    val x = b.toInt() and 0xFF
    if (remaining == 0) {
      return when {
        x and 0x80 == 0x00 -> x // ASCII
        x and 0xE0 == 0xC0 -> {
          remaining = 1
          acc = x and 0x1F
          minValid = 0x80
          null
        }
        x and 0xF0 == 0xE0 -> {
          remaining = 2
          acc = x and 0x0F
          minValid = 0x800
          null
        }
        x and 0xF8 == 0xF0 -> {
          remaining = 3
          acc = x and 0x07
          minValid = 0x10000
          null
        }
        else -> 0xFFFD // 不正な先頭バイト
      }
    }
    if (x and 0xC0 != 0x80) {
      // 継続バイトのはずが違う → リセットし FFFD を返して先頭として再解釈
      remaining = 0
      acc = 0
      return 0xFFFD
    }
    acc = (acc shl 6) or (x and 0x3F)
    remaining -= 1
    if (remaining == 0) {
      val cp = acc
      acc = 0
      return if (cp < minValid) 0xFFFD else cp
    }
    return null
  }

  fun reset() {
    remaining = 0
    acc = 0
    minValid = 0
  }
}
