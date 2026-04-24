package com.example.wanoterm.terminal.emulator

import com.example.wanoterm.util.CharWidth

/**
 * 1 セル。`codePoint == 0` で空セル。`wide == true` のセルは右隣の `continuation` セルと対。
 */
data class Cell(
    var codePoint: Int = 0,
    var style: CellStyle = CellStyle.Default,
    var wide: Boolean = false,
    var continuation: Boolean = false,
) {
  fun clear(style: CellStyle = CellStyle.Default) {
    codePoint = 0
    this.style = style
    wide = false
    continuation = false
  }

  fun copyFrom(other: Cell) {
    codePoint = other.codePoint
    style = other.style
    wide = other.wide
    continuation = other.continuation
  }
}

/**
 * 固定サイズ row x col のセルグリッド。スクロールバックは後続フェーズで追加。
 *
 * すべての座標は 0-based。`row` は 0 が最上段、`cols` 列が 0-based で並ぶ。
 */
class TerminalBuffer(initialRows: Int, initialCols: Int) {
  var rows: Int = initialRows
    private set

  var cols: Int = initialCols
    private set

  private var grid: Array<Array<Cell>> = makeGrid(rows, cols)

  /** 画面全体が一度でも書き換わったかを示す単調カウンタ（再描画トリガ）。 */
  var generation: Long = 0L
    private set

  /**
   * スクロールバックバッファ。上から押し出された行をここに積む。
   * 新しい行ほど `last()` 側に来る。古い行は上限を超えたら捨てる。
   */
  private val scrollback: ArrayDeque<Array<Cell>> = ArrayDeque()
  private val maxScrollback = 2000

  /** scrollback に保持している行数。TerminalView のスクロール上限計算に使う。 */
  val scrollbackSize: Int
    get() = scrollback.size

  /**
   * `lineFromBottom == 0` が一番最近 scroll で消えた行（＝画面直上）、
   * 大きくなるほど古い。範囲外なら null。
   */
  @Synchronized
  fun scrollbackCellAt(lineFromBottom: Int, col: Int): Cell? {
    val idx = scrollback.size - 1 - lineFromBottom
    if (idx < 0 || idx >= scrollback.size) return null
    val row = scrollback[idx]
    if (col < 0 || col >= row.size) return null
    return row[col]
  }

  /**
   * `rowOffset` は「旧 buffer のどの行を new 行 0 として扱うか」。
   * 縮小時に cursor を画面内に残したい TerminalEmulator 側で算出して渡す。
   */
  // VT 受信は Dispatchers.Default コルーチンから、resize は UI スレッドから呼ばれるため、
  // grid/rows/cols を同時に変更するメソッドは全部 @Synchronized で直列化する。
  // これを怠ると ArrayIndexOutOfBoundsException でアプリが落ちる。
  @Synchronized
  fun resize(newRows: Int, newCols: Int, rowOffset: Int = 0) {
    if (newRows == rows && newCols == cols) return
    val newGrid = makeGrid(newRows, newCols)
    val copyCols = minOf(cols, newCols)
    for (r in 0 until newRows) {
      val srcR = rowOffset + r
      if (srcR in 0 until rows) {
        for (c in 0 until copyCols) newGrid[r][c].copyFrom(grid[srcR][c])
      }
    }
    grid = newGrid
    rows = newRows
    cols = newCols
    // cols が変わると scrollback の各行が異なる幅を持つことになり、遡った時に
    // 表示がガタガタになる。各行を newCols に合わせて詰め直す（短縮は切り詰め、
    // 拡大は空セルで埋める）。2000 行の全 copy だが resize は稀なので許容。
    if (scrollback.isNotEmpty()) {
      val repaired = ArrayDeque<Array<Cell>>(scrollback.size)
      for (line in scrollback) {
        val newLine = Array(newCols) { c -> if (c < line.size) line[c].copy() else Cell() }
        repaired.addLast(newLine)
      }
      scrollback.clear()
      scrollback.addAll(repaired)
    }
    bump()
  }

  @Synchronized
  fun cellAt(row: Int, col: Int): Cell {
    // 描画側からも呼ばれる。範囲外は空セルを返して落ちさせない。
    if (row !in 0 until rows || col !in 0 until cols) return EMPTY_CELL
    return grid[row][col]
  }

  /** 1 code point を指定位置に書く。widthOf==2 なら右隣セルを continuation にする。 */
  @Synchronized
  fun put(row: Int, col: Int, codePoint: Int, style: CellStyle) {
    if (row !in 0 until rows || col !in 0 until cols) return
    val width = CharWidth.widthOf(codePoint).coerceAtLeast(1)
    val cell = grid[row][col]
    if (cell.continuation && col > 0) grid[row][col - 1].clear(style)
    cell.codePoint = codePoint
    cell.style = style
    cell.wide = width == 2
    cell.continuation = false
    if (width == 2 && col + 1 < cols) {
      val right = grid[row][col + 1]
      right.codePoint = 0
      right.style = style
      right.wide = false
      right.continuation = true
    }
    bump()
  }

  @Synchronized
  fun clearCell(row: Int, col: Int, style: CellStyle) {
    if (row !in 0 until rows || col !in 0 until cols) return
    grid[row][col].clear(style)
    bump()
  }

  @Synchronized
  fun clearRow(row: Int, style: CellStyle, fromCol: Int = 0, toCol: Int = cols - 1) {
    if (row !in 0 until rows) return
    val a = fromCol.coerceAtLeast(0)
    val b = toCol.coerceAtMost(cols - 1)
    for (c in a..b) grid[row][c].clear(style)
    bump()
  }

  @Synchronized
  fun clearAll(style: CellStyle) {
    for (r in 0 until rows) clearRow(r, style)
  }

  /** 上 1 行ぶんスクロール（最上段の行が消え、下から新しい空行を足す）。 */
  @Synchronized
  fun scrollUp(style: CellStyle, count: Int = 1) {
    val n = count.coerceAtMost(rows)
    if (n <= 0) return
    for (i in 0 until n) {
      val snapshot = Array(cols) { c -> grid[i][c].copy() }
      scrollback.addLast(snapshot)
      if (scrollback.size > maxScrollback) scrollback.removeFirst()
    }
    for (r in 0 until rows - n) {
      for (c in 0 until cols) grid[r][c].copyFrom(grid[r + n][c])
    }
    for (r in rows - n until rows) clearRow(r, style)
  }

  /** 下 1 行ぶんスクロール（最下段の行が消え、上から空行を挿入）。 */
  @Synchronized
  fun scrollDown(style: CellStyle, count: Int = 1) {
    val n = count.coerceAtMost(rows)
    if (n <= 0) return
    for (r in rows - 1 downTo n) {
      for (c in 0 until cols) grid[r][c].copyFrom(grid[r - n][c])
    }
    for (r in 0 until n) clearRow(r, style)
  }

  companion object {
    private val EMPTY_CELL = Cell()
  }

  /** デバッグ用。1 行の文字列表現。 */
  fun rowAsString(row: Int): String {
    if (row !in 0 until rows) return ""
    val sb = StringBuilder(cols)
    for (c in 0 until cols) {
      val cell = grid[row][c]
      if (cell.continuation) continue
      if (cell.codePoint == 0) sb.append(' ') else sb.appendCodePoint(cell.codePoint)
    }
    return sb.toString().trimEnd()
  }

  private fun bump() {
    generation++
  }

  private fun makeGrid(r: Int, c: Int): Array<Array<Cell>> =
      Array(r) { Array(c) { Cell() } }
}
