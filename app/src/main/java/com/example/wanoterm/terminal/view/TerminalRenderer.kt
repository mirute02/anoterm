package com.example.wanoterm.terminal.view

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import com.example.wanoterm.terminal.emulator.AnsiColor
import com.example.wanoterm.terminal.emulator.TerminalBuffer
import com.example.wanoterm.terminal.emulator.TerminalEmulator
import com.example.wanoterm.theme.TerminalPalette

/**
 * 端末バッファを Canvas に描画する。1 文字幅 = cellWidth、1 行高 = cellHeight。
 *
 * - 和文グリフ（全角）は 2 セル分の幅をとる。Paint の advance と異なる可能性があるため、
 *   `wide` セルは 2 * cellWidth の矩形として描画し、中央に寄せる。
 * - composing 中の文字列はカーソル位置からアンダーライン付きで半透明に重ねる。
 * - 選択範囲は後段フェーズで追加。
 */
class TerminalRenderer(
    var palette: TerminalPalette,
    var fontSizePx: Float,
) {
  private val textPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.MONOSPACE
        textSize = fontSizePx
      }
  private val bgPaint = Paint()
  private val underlinePaint = Paint()
  private val cursorPaint = Paint().apply { style = Paint.Style.FILL }
  private val selectionPaint = Paint()
  private val composingBgPaint = Paint()
  private val reusedBounds = Rect()

  val cellWidth: Float
    get() {
      // ASCII の "M" で等幅幅を測る。
      val w = textPaint.measureText("M")
      return w
    }

  val cellHeight: Float
    get() {
      val fm = textPaint.fontMetrics
      return fm.descent - fm.ascent + fm.leading
    }

  val baselineOffset: Float
    get() {
      val fm = textPaint.fontMetrics
      return -fm.ascent
    }

  fun updateFontSize(newSizePx: Float) {
    fontSizePx = newSizePx
    textPaint.textSize = newSizePx
  }

  /**
   * 描画本体。view 寸法で何列 x 何行まで見えるかは呼び出し側が emulator に伝える責務。
   * ここはすでに確定した buffer を素直に描く。
   */
  /**
   * View の実寸法。`draw` を呼ぶ側（TerminalView.onDraw）からセットしてもらう。
   * この値で「セル境界の外側の余白」も含めて全面塗りつぶす（残骸ピクセル対策）。
   */
  var viewPixelWidth: Float = 0f
  var viewPixelHeight: Float = 0f

  fun draw(
      canvas: Canvas,
      emulator: TerminalEmulator,
      composing: ComposingState,
      cursorBlinkOn: Boolean,
      scrollOffset: Int = 0,
  ) {
    val cw = cellWidth
    val ch = cellHeight
    val buffer = emulator.buffer
    val bgDefault = palette.background.toAndroidColorInt()

    // 全面クリア。view 寸法が来ていればそのサイズ、無ければセル数 * セルサイズで。
    // セル数 * セルサイズは view 寸法より小さいことがあり、右端・下端に前フレームの
    // 残骸ピクセルが残る原因になるため、必ず view 寸法以上で塗り切る。
    val clearW = maxOf(viewPixelWidth, cw * buffer.cols)
    val clearH = maxOf(viewPixelHeight, ch * buffer.rows)
    bgPaint.color = bgDefault
    canvas.drawRect(0f, 0f, clearW, clearH, bgPaint)

    val effectiveOffset = scrollOffset.coerceAtMost(buffer.rows + buffer.scrollbackSize)

    // 可視行 r に表示すべきセル列を返すヘルパ。scrollback 側 / buffer 側の分岐はここに閉じる。
    fun cellAtVisible(r: Int, c: Int): com.example.wanoterm.terminal.emulator.Cell? =
        if (r < effectiveOffset) {
          val lineFromBottomOfScrollback = effectiveOffset - 1 - r
          buffer.scrollbackCellAt(lineFromBottomOfScrollback, c)
        } else {
          val br = r - effectiveOffset
          if (br in 0 until buffer.rows) buffer.cellAt(br, c) else null
        }

    // ===== Pass 1: 全セルの背景矩形 =====
    // continuation セル（全角文字の右半分）は本セルの bg を引き継ぐべき。
    // bgOfCell() で continuation 側を左隣の bg にマッピングする。
    // DrawStyle を作らず resolveBgInt で int を直接取る（GC 圧軽減）。
    fun bgOfCell(r: Int, c: Int): Int {
      val cell = cellAtVisible(r, c) ?: return bgDefault
      if (cell.continuation) {
        val left = cellAtVisible(r, c - 1)
        return if (left != null && left.wide) left.style.resolveBgInt() else bgDefault
      }
      return cell.style.resolveBgInt()
    }

    for (r in 0 until buffer.rows) {
      val y = ch * r
      var c = 0
      while (c < buffer.cols) {
        val bg = bgOfCell(r, c)
        var c2 = c + 1
        while (c2 < buffer.cols) {
          if (bgOfCell(r, c2) != bg) break
          c2++
        }
        if (bg != bgDefault) {
          bgPaint.color = bg
          canvas.drawRect(cw * c, y, cw * c2, y + ch, bgPaint)
        }
        c = c2
      }
    }

    // ===== Pass 2: グリフ =====
    for (r in 0 until buffer.rows) {
      val y = ch * r
      var c = 0
      while (c < buffer.cols) {
        val cell = cellAtVisible(r, c)
        if (cell == null) { c++; continue }
        if (cell.continuation) { c++; continue }
        val w = if (cell.wide) 2 else 1
        val x = cw * c
        val cellRectWidth = cw * w
        if (cell.codePoint != 0) {
          drawGlyph(canvas, cell.codePoint, cell.style.toDrawStyle(), x, y, cellRectWidth, ch)
        }
        c += w
      }
    }

    // カーソル / composing は「底を見ている（scrollOffset == 0）」ときだけ表示。
    if (scrollOffset == 0) {
      if (composing.isActive) {
        val x = cw * emulator.cursorCol
        val y = ch * emulator.cursorRow
        drawComposing(canvas, composing.text, x, y, cw, ch)
      } else if (emulator.cursorVisible && cursorBlinkOn) {
        cursorPaint.color = palette.cursor.toAndroidColorInt()
        val x = cw * emulator.cursorCol
        val y = ch * emulator.cursorRow
        canvas.drawRect(x, y, x + cw, y + ch, cursorPaint)
        val cell = buffer.cellAt(emulator.cursorRow, emulator.cursorCol)
        if (cell.codePoint != 0) {
          textPaint.color = bgDefault
          val s = String(Character.toChars(cell.codePoint))
          val tw = textPaint.measureText(s)
          canvas.drawText(s, x + (cw - tw) / 2f, y + baselineOffset, textPaint)
        }
      }
    }
  }

  private fun drawGlyph(
      canvas: Canvas,
      codePoint: Int,
      style: DrawStyle,
      x: Float,
      y: Float,
      cellRectWidth: Float,
      cellRectHeight: Float,
  ) {
    textPaint.color = style.fg
    textPaint.isFakeBoldText = style.bold
    textPaint.typeface =
        if (style.italic) Typeface.create(Typeface.MONOSPACE, Typeface.ITALIC) else Typeface.MONOSPACE
    textPaint.isUnderlineText = style.underline
    textPaint.isStrikeThruText = style.strike
    val s = String(Character.toChars(codePoint))
    // セル矩形を超えるグリフ（emoji 等）が隣セルに漏れないよう clip してから描画。
    canvas.save()
    canvas.clipRect(x, y, x + cellRectWidth, y + cellRectHeight)
    val tw = textPaint.measureText(s)
    // セル幅より大きいグリフは左寄せ、そうでなければ中央寄せ（等幅 ASCII 用）
    val dx = if (tw > cellRectWidth) 0f else (cellRectWidth - tw) / 2f
    canvas.drawText(s, x + dx, y + baselineOffset, textPaint)
    canvas.restore()
    textPaint.isFakeBoldText = false
    textPaint.isUnderlineText = false
    textPaint.isStrikeThruText = false
  }

  private fun drawComposing(
      canvas: Canvas,
      text: String,
      startX: Float,
      startY: Float,
      cw: Float,
      ch: Float,
  ) {
    // 合成文字列は「IME 下書き」色で背景を塗り、テキスト + アンダーライン
    composingBgPaint.color = palette.selection.toAndroidColorInt()
    var dx = startX
    val width = com.example.wanoterm.util.CharWidth.stringWidth(text) * cw
    canvas.drawRect(startX, startY, startX + width, startY + ch, composingBgPaint)
    // 文字描画
    textPaint.color = palette.foreground.toAndroidColorInt()
    textPaint.isUnderlineText = true
    canvas.drawText(text, startX, startY + baselineOffset, textPaint)
    textPaint.isUnderlineText = false
  }

  /** CellStyle から実際の描画色へ解決（DrawStyle 生成あり）。glyph 描画用。 */
  private fun com.example.wanoterm.terminal.emulator.CellStyle.toDrawStyle(): DrawStyle {
    val fgBase = fg.resolve(palette, defaultIsForeground = true)
    val bgBase = bg.resolve(palette, defaultIsForeground = false)
    val finalFg = if (reverse) bgBase else fgBase
    val finalBg = if (reverse) fgBase else bgBase
    val boldFg = if (bold && fg is AnsiColor.Indexed && fg.index < 8) {
      palette.ansi[fg.index + 8].toAndroidColorInt()
    } else finalFg
    return DrawStyle(
        fg = boldFg,
        bg = finalBg,
        bold = bold,
        italic = italic,
        underline = underline,
        strike = strike,
    )
  }

  /**
   * 背景色だけを int で返す。DrawStyle を作らないので GC 圧にならない。
   * 背景 pass は 1 フレームで rows × cols 回呼ばれるため、allocation を避けたい。
   */
  private fun com.example.wanoterm.terminal.emulator.CellStyle.resolveBgInt(): Int {
    val bgBase = bg.resolve(palette, defaultIsForeground = false)
    return if (reverse) fg.resolve(palette, defaultIsForeground = true) else bgBase
  }

  private data class DrawStyle(
      val fg: Int,
      val bg: Int,
      val bold: Boolean,
      val italic: Boolean,
      val underline: Boolean,
      val strike: Boolean,
  )
}

private fun AnsiColor.resolve(palette: TerminalPalette, defaultIsForeground: Boolean): Int =
    when (this) {
      AnsiColor.Default -> if (defaultIsForeground) palette.foreground.toAndroidColorInt() else palette.background.toAndroidColorInt()
      is AnsiColor.Indexed -> {
        if (index in 0..15) palette.ansi[index].toAndroidColorInt()
        else if (index < 232) {
          // 6x6x6 cube
          val i = index - 16
          val r = (i / 36) * 51
          val g = ((i / 6) % 6) * 51
          val b = (i % 6) * 51
          android.graphics.Color.rgb(r, g, b)
        } else {
          // grayscale
          val v = 8 + (index - 232) * 10
          android.graphics.Color.rgb(v, v, v)
        }
      }
      is AnsiColor.Rgb -> android.graphics.Color.rgb(r, g, b)
    }

private fun androidx.compose.ui.graphics.Color.toAndroidColorInt(): Int = this.value.let {
  // Compose Color の ULong から ARGB int に変換
  android.graphics.Color.argb(
      (alpha * 255).toInt(),
      (red * 255).toInt(),
      (green * 255).toInt(),
      (blue * 255).toInt(),
  )
}
