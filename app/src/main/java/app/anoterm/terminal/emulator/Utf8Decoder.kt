package app.anoterm.terminal.emulator

/**
 * バイト列から code point を徐々に組み立てる最小 UTF-8 デコーダ。
 *
 * 不正バイト列は U+FFFD に置き換える。状態は `feed` 間で保持されるため、チャネルから到着する
 * 途中バイトでも破綻しない。
 *
 * 端末は信頼できない相手からバイトを受け取る。UTF-8 として不正な入力で例外を投げると、
 * 接続先が `cat /dev/urandom` を流すだけでアプリが落ちる。そのためこのクラスは
 * **常に妥当な scalar value を返す**：
 *
 * - 先頭バイト 0xF5..0xFF は 4 バイト長を名乗れない（U+10FFFF 超になる）ので不正として扱う
 * - 完成した code point が U+10FFFF を超える、またはサロゲート領域 D800..DFFF なら U+FFFD
 * - 過剰長エンコード（minValid 未満）も U+FFFD
 *
 * 途中で継続バイトでないものが来た場合、シーケンスを捨てて U+FFFD を返すが、
 * **そのバイト自体は捨てない**。[pending] に退避し、呼び出し側が次に読み出す。
 * ESC が来て切り捨てられると、以降のエスケープシーケンスがテキストとして描画され
 * パーサが同期を失うため。
 */
class Utf8Decoder {
  private var remaining: Int = 0
  private var acc: Int = 0
  private var minValid: Int = 0
  private var pendingByte: Int = -1

  /**
   * 直前の [feed] が U+FFFD を返した際に、再解釈すべきバイトがあればそれを返して消費する。
   * なければ null。[feed] の直後に呼ぶこと。
   */
  fun pending(): Byte? {
    if (pendingByte < 0) return null
    val b = pendingByte
    pendingByte = -1
    return b.toByte()
  }

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
        // 0xF0..0xF4 のみ。0xF5 以上は必ず U+10FFFF を超える。
        x and 0xF8 == 0xF0 && x <= 0xF4 -> {
          remaining = 3
          acc = x and 0x07
          minValid = 0x10000
          null
        }
        else -> 0xFFFD // 不正な先頭バイト（継続バイト単独、0xF5..0xFF を含む）
      }
    }
    if (x and 0xC0 != 0x80) {
      // 継続バイトのはずが違う。シーケンスを捨てて FFFD を返し、
      // このバイトは pending() 経由で先頭として再解釈させる。
      remaining = 0
      acc = 0
      pendingByte = x
      return 0xFFFD
    }
    acc = (acc shl 6) or (x and 0x3F)
    remaining -= 1
    if (remaining == 0) {
      val cp = acc
      acc = 0
      return if (cp < minValid || cp > MAX_CODE_POINT || cp in SURROGATE_RANGE) 0xFFFD else cp
    }
    return null
  }

  fun reset() {
    remaining = 0
    acc = 0
    minValid = 0
    pendingByte = -1
  }

  private companion object {
    const val MAX_CODE_POINT = 0x10FFFF
    val SURROGATE_RANGE = 0xD800..0xDFFF
  }
}
