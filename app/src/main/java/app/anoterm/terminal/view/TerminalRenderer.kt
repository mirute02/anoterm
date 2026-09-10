package app.anoterm.terminal.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Typeface
import android.util.LruCache
import androidx.core.content.res.ResourcesCompat
import app.anoterm.R
import app.anoterm.terminal.emulator.AnsiColor
import app.anoterm.terminal.emulator.TerminalBuffer
import app.anoterm.terminal.PathSpan
import app.anoterm.terminal.emulator.TerminalEmulator
import app.anoterm.theme.TerminalPalette

/**
 * 端末バッファを Canvas に描画する。1 文字幅 = cellWidth、1 行高 = cellHeight。
 *
 * - 和文グリフ（全角）は 2 セル分の幅をとる。Paint の advance と異なる可能性があるため、
 *   `wide` セルは 2 * cellWidth の矩形として描画し、中央に寄せる。
 * - composing 中の文字列はカーソル位置からアンダーライン付きで半透明に重ねる。
 * - 選択範囲は後段フェーズで追加。
 */
class TerminalRenderer(
    context: Context,
    var palette: TerminalPalette,
    var fontSizePx: Float,
) {
  // プログラムを読みやすい等幅フォント（JetBrains Mono）をバンドルして優先使用。
  // 存在しないグリフ（CJK 等）は Android のフォントフォールバックで system monospace が
  // 使われるため、基本 ASCII/記号の視認性向上が目的。
  private val programFont: Typeface =
      ResourcesCompat.getFont(context, R.font.jetbrains_mono_regular) ?: Typeface.MONOSPACE
  private val programFontItalic: Typeface = Typeface.create(programFont, Typeface.ITALIC)

  private val textPaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = programFont
        textSize = fontSizePx
      }
  private val bgPaint = Paint()
  private val linkPaint = android.graphics.Paint()
  private val underlinePaint = Paint()
  private val cursorPaint = Paint().apply { style = Paint.Style.FILL }
  private val selectionPaint = Paint()
  private val selectionHandlePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
  private val selectionHandleStrokePaint =
      Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = context.resources.displayMetrics.density * 1.5f
      }
  private val composingBgPaint = Paint()
  private val reusedBounds = Rect()
  private val density = context.resources.displayMetrics.density

  // 毎グリフ `String(Character.toChars(cp))` が効く。描画は 1 フレームで数千回呼ばれるため、
  // code point → String を LRU キャッシュして allocation と GC 圧を抑える。
  // サイズは ASCII + CJK 基本域をカバーして十分な 4096。
  private val glyphStringCache = object : LruCache<Int, String>(4096) {}

  private fun glyphStringFor(cp: Int): String {
    val cached = glyphStringCache.get(cp)
    if (cached != null) return cached
    val s = String(Character.toChars(cp))
    glyphStringCache.put(cp, s)
    return s
  }

  val cellWidth: Float
    get() {
      // ASCII の "M" で等幅幅を測る。
      val w = textPaint.measureText("M")
      return w
    }

  /** 行の高さの倍率。1.0 でフォントの推奨行送りそのまま。 */
  var lineSpacing: Float = 1.0f
    set(value) {
      field = value.coerceIn(1.0f, 2.0f)
    }

  /** フォントが要求する最小の行高。ここを下回るとグリフが切れる。 */
  private val glyphHeight: Float
    get() {
      val fm = textPaint.fontMetrics
      return fm.descent - fm.ascent + fm.leading
    }

  val cellHeight: Float
    get() = glyphHeight * lineSpacing

  val baselineOffset: Float
    get() {
      val fm = textPaint.fontMetrics
      // 広げた分はセルの上下に均等に割る。上だけに足すと行が下寄りに見える。
      return (cellHeight - glyphHeight) / 2f - fm.ascent
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
      selectionStart: CellPos? = null,
      selectionEnd: CellPos? = null,
      pathSpans: List<PathSpan> = emptyList(),
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
    fun cellAtVisible(r: Int, c: Int): app.anoterm.terminal.emulator.Cell? =
        if (r < effectiveOffset) {
          val lineFromBottomOfScrollback = effectiveOffset - 1 - r
          buffer.scrollbackCellAt(lineFromBottomOfScrollback, c)
        } else {
          val br = r - effectiveOffset
          if (br in 0 until buffer.rows) buffer.cellAt(br, c) else null
        }

    // ===== 選択範囲の正規化 =====
    // 選択は「可視行 r (scrollback 含む拡張行番号)」ではなく、TerminalView 側の CellPos が
    // 画面上の `row`（0 = 最上段可視行）として来るので、そのまま比較する。
    val selBgInt = palette.selection.toAndroidColorInt()
    val sr1: Int
    val sc1: Int
    val sr2: Int
    val sc2: Int
    val selEnabled: Boolean
    if (selectionStart != null && selectionEnd != null) {
      val s = selectionStart
      val e = selectionEnd
      val swap = s.row > e.row || (s.row == e.row && s.col > e.col)
      sr1 = if (swap) e.row else s.row
      sc1 = if (swap) e.col else s.col
      sr2 = if (swap) s.row else e.row
      sc2 = if (swap) s.col else e.col
      selEnabled = true
    } else {
      sr1 = 0; sc1 = 0; sr2 = 0; sc2 = 0
      selEnabled = false
    }

    fun isSelected(r: Int, c: Int): Boolean {
      if (!selEnabled) return false
      if (r < sr1 || r > sr2) return false
      if (sr1 == sr2) return c in sc1..sc2
      if (r == sr1) return c >= sc1
      if (r == sr2) return c <= sc2
      return true
    }

    // ===== Pass 1: 全セルの背景矩形 =====
    // continuation セル（全角文字の右半分）は本セルの bg を引き継ぐべき。
    // bgOfCell() で continuation 側を左隣の bg にマッピングする。
    // DrawStyle を作らず resolveBgInt で int を直接取る（GC 圧軽減）。
    fun bgOfCell(r: Int, c: Int): Int {
      if (isSelected(r, c)) return selBgInt
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
          drawGlyphInline(canvas, cell.codePoint, cell.style, x, y, cellRectWidth, ch)
        }
        c += w
      }
    }

    // ===== Pass 3: 押せるパスに下線 =====
    // 押せると分かる印が無ければ誰も押さない。色はカーソルと同じにして、
    // 「この端末が注目してほしい所」の見た目を 1 つに保つ。
    if (pathSpans.isNotEmpty()) {
      linkPaint.color = palette.cursor.toAndroidColorInt()
      val thickness = maxOf(1f, ch * 0.06f)
      for (s in pathSpans) {
        if (s.row !in 0 until buffer.rows) continue
        val y = ch * (s.row + 1) - thickness * 2f
        canvas.drawRect(cw * s.startCol, y, cw * (s.endCol + 1), y + thickness, linkPaint)
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
          val s = glyphStringFor(cell.codePoint)
          val tw = textPaint.measureText(s)
          canvas.drawText(s, x + (cw - tw) / 2f, y + baselineOffset, textPaint)
        }
      }
    }

    if (selEnabled && selectionStart != null && selectionEnd != null) {
      drawSelectionHandles(canvas, selectionStart, selectionEnd, cw, ch, clearW, clearH)
    }
  }

  /**
   * CellStyle を直接受けてそのまま描画する。DrawStyle data class を経由しないので、
   * 1 フレームあたり数千のセル描画で数千回走る per-cell アロケーションを避けられる。
   */
  private fun drawGlyphInline(
      canvas: Canvas,
      codePoint: Int,
      style: app.anoterm.terminal.emulator.CellStyle,
      x: Float,
      y: Float,
      cellRectWidth: Float,
      cellRectHeight: Float,
  ) {
    val fgBase = style.fg.resolve(palette, defaultIsForeground = true)
    val bgBase = style.bg.resolve(palette, defaultIsForeground = false)
    val finalFg = if (style.reverse) bgBase else fgBase
    val boldFg =
        if (style.bold && style.fg is AnsiColor.Indexed && style.fg.index < 8) {
          palette.ansi[style.fg.index + 8].toAndroidColorInt()
        } else finalFg
    textPaint.color = boldFg
    textPaint.isFakeBoldText = style.bold
    textPaint.typeface = if (style.italic) programFontItalic else programFont
    textPaint.isUnderlineText = style.underline
    textPaint.isStrikeThruText = style.strike
    val s = glyphStringFor(codePoint)
    canvas.save()
    canvas.clipRect(x, y, x + cellRectWidth, y + cellRectHeight)
    val tw = textPaint.measureText(s)
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
    val width = app.anoterm.util.CharWidth.stringWidth(text) * cw
    canvas.drawRect(startX, startY, startX + width, startY + ch, composingBgPaint)
    // 文字描画
    textPaint.color = palette.foreground.toAndroidColorInt()
    textPaint.isUnderlineText = true
    canvas.drawText(text, startX, startY + baselineOffset, textPaint)
    textPaint.isUnderlineText = false
  }

  private fun drawSelectionHandles(
      canvas: Canvas,
      start: CellPos,
      end: CellPos,
      cw: Float,
      ch: Float,
      maxWidth: Float,
      maxHeight: Float,
  ) {
    val radius = maxOf(5f * density, minOf(ch * 0.32f, 9f * density))
    selectionHandlePaint.color = palette.cursor.toAndroidColorInt()
    selectionHandleStrokePaint.color = palette.background.toAndroidColorInt()

    fun drawHandle(pos: CellPos, isStart: Boolean) {
      val rawX = if (isStart) pos.col * cw else (pos.col + 1) * cw
      val rawY = (pos.row + 1) * ch
      val maxX = maxOf(radius, maxWidth - radius)
      val maxY = maxOf(radius, maxHeight - radius)
      val x = rawX.coerceIn(radius, maxX)
      val y = rawY.coerceIn(radius, maxY)
      val stemTop = (pos.row * ch).coerceIn(0f, maxHeight)
      canvas.drawRect(x - density, stemTop, x + density, y, selectionHandlePaint)
      canvas.drawCircle(x, y, radius, selectionHandlePaint)
      canvas.drawCircle(x, y, radius, selectionHandleStrokePaint)
    }

    drawHandle(start, isStart = true)
    if (start != end) drawHandle(end, isStart = false)
  }

  /**
   * 背景色だけを int で返す。DrawStyle を作らないので GC 圧にならない。
   * 背景 pass は 1 フレームで rows × cols 回呼ばれるため、allocation を避けたい。
   */
  private fun app.anoterm.terminal.emulator.CellStyle.resolveBgInt(): Int {
    val bgBase = bg.resolve(palette, defaultIsForeground = false)
    return if (reverse) fg.resolve(palette, defaultIsForeground = true) else bgBase
  }
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
