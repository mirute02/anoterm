package app.anoterm.terminal.emulator

import app.anoterm.util.CharWidth

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
 *
 * [scrollbackEnabled] = false にすると scrollUp で押し出された行を scrollback に積まない。
 * 代替画面 (ESC[?1049h で切り替わる alt buffer) 用。vim/less の内部スクロールで
 * primary buffer の履歴が汚染されるのを防ぐ（VT100 仕様どおり）。
 */
class TerminalBuffer(
    initialRows: Int,
    initialCols: Int,
    private val scrollbackEnabled: Boolean = true,
) {
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
  private var maxScrollback = DEFAULT_MAX_SCROLLBACK

  /**
   * scrollback の上限行数を変更し、超過分を古い方から捨てる。
   * アプリ全体のメモリバジェット管理（多タブ×横長での OOM/LMK 回避）から呼ばれる。
   * scrollback を lockless に読む描画パスと衝突しないよう、emulator monitor 下で呼ぶこと
   * （呼び出し元 [TerminalEmulator.setScrollbackLimit] が @Synchronized で保証する）。
   */
  @Synchronized
  fun setMaxScrollback(limit: Int) {
    maxScrollback = limit.coerceIn(MIN_MAX_SCROLLBACK, ABSOLUTE_MAX_SCROLLBACK)
    while (scrollback.size > maxScrollback) scrollback.removeFirst()
  }

  /** scrollback に保持している行数。TerminalView のスクロール上限計算に使う。 */
  val scrollbackSize: Int
    get() = scrollback.size

  /**
   * `lineFromBottom == 0` が一番最近 scroll で消えた行（＝画面直上）、
   * 大きくなるほど古い。範囲外なら null。
   */
  /**
   * 描画ホットパスなので @Synchronized を外す。スナップショット的に deque のサイズと要素を
   * ローカル参照で判定する。変更側（scrollUp）は @Synchronized のままだが、読み中に
   * deque を弄る可能性はある。その場合、リストの要素が途中で変わっても segv することは
   * ないので、一瞬ちらつく程度で許容する。
   */
  fun scrollbackCellAt(lineFromBottom: Int, col: Int): Cell? {
    val sb = scrollback
    val idx = sb.size - 1 - lineFromBottom
    if (idx < 0 || idx >= sb.size) return null
    val row = sb.elementAtOrNull(idx) ?: return null
    if (col < 0 || col >= row.size) return null
    return row[col]
  }

  /**
   * `rowOffset` は「旧 buffer のどの行を new 行 0 として扱うか」。
   * 縮小時に cursor を画面内に残したい TerminalEmulator 側で算出して渡す。
   *
   * 戻り値は scrollback の増減行数（正 = 積んだ、負 = 引き戻した）。画面の高さが変わっても
   * 読んでいる行が上下に飛ばないよう、表示位置をこのぶんずらすために返している。
   */
  // VT 受信は Dispatchers.Default コルーチンから、resize は UI スレッドから呼ばれるため、
  // grid/rows/cols を同時に変更するメソッドは全部 @Synchronized で直列化する。
  // これを怠ると ArrayIndexOutOfBoundsException でアプリが落ちる。
  @Synchronized
  fun resize(newRows: Int, newCols: Int, rowOffset: Int = 0): Int {
    if (newRows == rows && newCols == cols) return 0
    // cols 不変なら scrollback は触らない。IME アニメの毎フレームで rows だけ動く
    // ときに 2000 行全 copy していたのが frame drop の主因だった。
    val colsChanged = newCols != cols

    // 縮むときに画面から溢れる上端の行は、捨てずに scrollback へ送る。
    // 捨てていた頃は、キーボードが出た瞬間に読んでいた行が消えていた。
    val pushed =
        if (scrollbackEnabled && rowOffset > 0) {
          val n = minOf(rowOffset, rows)
          for (r in 0 until n) scrollback.addLast(grid[r])
          while (scrollback.size > maxScrollback) scrollback.removeFirst()
          n
        } else {
          0
        }

    // 広がるときはその逆をやる。scrollback の末尾から引き戻して上に足す。
    // 空行で埋めると「キーボードを閉じたら下に空白が生えて、本文が上へ飛んだ」に見える。
    val pulled =
        if (scrollbackEnabled && rowOffset == 0 && newRows > rows) {
          minOf(newRows - rows, scrollback.size)
        } else {
          0
        }

    val newGrid = makeGrid(newRows, newCols)
    val copyCols = minOf(cols, newCols)
    for (r in 0 until newRows) {
      // 上に足した pulled 行は scrollback から、それ以降は旧 grid から取る。
      val src: Array<Cell>? =
          if (r < pulled) {
            scrollback.elementAtOrNull(scrollback.size - pulled + r)
          } else {
            val srcR = rowOffset + (r - pulled)
            if (srcR in 0 until rows) grid[srcR] else null
          }
      if (src != null) {
        for (c in 0 until minOf(copyCols, src.size)) newGrid[r][c].copyFrom(src[c])
      }
    }
    repeat(pulled) { scrollback.removeLast() }
    grid = newGrid
    rows = newRows
    cols = newCols
    // cols が変わると scrollback の各行が異なる幅を持つことになり、遡った時に
    // 表示がガタガタになる。各行を newCols に合わせて詰め直す（短縮は切り詰め、
    // 拡大は空セルで埋める）。
    if (colsChanged && scrollback.isNotEmpty()) {
      val repaired = ArrayDeque<Array<Cell>>(scrollback.size)
      for (line in scrollback) {
        val newLine = Array(newCols) { c -> if (c < line.size) line[c].copy() else Cell() }
        repaired.addLast(newLine)
      }
      scrollback.clear()
      scrollback.addAll(repaired)
    }
    bump()
    // scrollback が何行増減したか。呼び出し側が「見ている行」を追従させるのに使う。
    return pushed - pulled
  }

  /**
   * 描画ホットパスで呼ばれるため @Synchronized は外し、grid 配列自体へのローカル参照を
   * 使って bounds 判定する。これにより「resize で rows/cols/grid が 3 段階で変わる間に
   * 読みに来たときのアウトオブレンジ」を防ぐ（grid.size と row.size 自体で判定する限り、
   * 入れ替わっても矛盾しない）。mutation 側は @Synchronized のままなので cell オブジェクト
   * 自体の fields 更新と並行になる可能性はあるが、それは描画の一瞬のちらつき程度で crash には
   * ならない（クラスは Cell、fields は var の原子型のみ）。
   */
  fun cellAt(row: Int, col: Int): Cell {
    val g = grid
    if (row < 0 || row >= g.size) return EMPTY_CELL
    val rowArr = g[row]
    if (col < 0 || col >= rowArr.size) return EMPTY_CELL
    return rowArr[col]
  }

  /**
   * src セルの内容を dest セルへコピー。両端が範囲内のときのみ実行する。
   * deleteChars / insertChars が以前は `cellAt(...).copyFrom(cellAt(...))` を使っていたが、
   * cellAt は範囲外で共有シングルトン EMPTY_CELL を返すため、境界外アクセス時に
   * センチネルを破壊して全セルの空描画が壊れる潜在バグがあった。境界内の grid 直接
   * アクセスに限定してそれを防ぐ。
   */
  @Synchronized
  fun copyCell(destRow: Int, destCol: Int, srcRow: Int, srcCol: Int) {
    if (destRow !in 0 until rows || destCol !in 0 until cols) return
    if (srcRow !in 0 until rows || srcCol !in 0 until cols) return
    grid[destRow][destCol].copyFrom(grid[srcRow][srcCol])
  }

  /** 1 code point を指定位置に書く。widthOf==2 なら右隣セルを continuation にする。 */
  @Synchronized
  fun put(row: Int, col: Int, codePoint: Int, style: CellStyle) {
    if (row !in 0 until rows || col !in 0 until cols) return
    val width = CharWidth.widthOf(codePoint).coerceAtLeast(1)
    val cell = grid[row][col]
    // 書込先が wide セルの右半分 (continuation) なら、左半分の wide を消す（孤児防止）。
    if (cell.continuation && col > 0) grid[row][col - 1].clear(style)
    // 書込先が wide セルの左半分だった場合、右隣に残っている continuation フラグを必ず
    // 消す。消さないと次に narrow 文字が右隣に書かれる時に「左の wide を消す」ロジックが
    // 暴走して 2 文字ぶん消えて見える（これが「1 文字おきに抜ける」症状の主因）。
    if (cell.wide && col + 1 < cols) {
      val right = grid[row][col + 1]
      if (right.continuation) right.clear(style)
    }
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
    val cell = grid[row][col]
    // wide セルの左半分を消すなら、右隣の continuation も一緒に消す。放置すると
    // 右半分が「孤児の continuation」として残り、次の put が暴走する。
    if (cell.wide && col + 1 < cols && grid[row][col + 1].continuation) {
      grid[row][col + 1].clear(style)
    }
    // continuation (右半分) だけ消されたら、左半分の wide も連動して消す。
    if (cell.continuation && col > 0) grid[row][col - 1].clear(style)
    cell.clear(style)
    bump()
  }

  @Synchronized
  fun clearRow(row: Int, style: CellStyle, fromCol: Int = 0, toCol: Int = cols - 1) {
    if (row !in 0 until rows) return
    var a = fromCol.coerceAtLeast(0)
    var b = toCol.coerceAtMost(cols - 1)
    // 範囲端が wide 文字の半分を割る場合は片側に伸ばす。これをしないと EL (CSI K) で
    // 全角文字の片半分だけ消えて「半身になったまま表示される」症状になる。
    if (a > 0 && grid[row][a].continuation) a -= 1
    if (b < cols - 1 && grid[row][b].wide) b += 1
    for (c in a..b) grid[row][c].clear(style)
    bump()
  }

  @Synchronized
  fun clearAll(style: CellStyle) {
    for (r in 0 until rows) clearRow(r, style)
  }

  /**
   * 上 1 行ぶんスクロール（最上段の行が消え、下から新しい空行を足す）。
   *
   * [pushToScrollback] = false にすると scrollbackEnabled が true でもスクロールバックに
   * 押し出さない。DL (CSI M) のような「画面内の編集操作」で呼ばれるときに使う。
   * DL は本来 cursor 行から下を詰める編集コマンドであり、画面上端を履歴に残す LF スクロールとは
   * 意味が違う。両方とも内部的には同じ配列シフトで処理できるが scrollback への扱いだけ分離する。
   */
  @Synchronized
  fun scrollUp(style: CellStyle, count: Int = 1, pushToScrollback: Boolean = true) {
    val n = count.coerceAtMost(rows)
    if (n <= 0) return
    if (scrollbackEnabled && pushToScrollback) {
      for (i in 0 until n) {
        val snapshot = Array(cols) { c -> grid[i][c].copy() }
        scrollback.addLast(snapshot)
        if (scrollback.size > maxScrollback) scrollback.removeFirst()
      }
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

  /**
   * DECSTBM で指定されたスクロール領域 [top..bottom] を 1 行ぶん上にスクロール。
   * scrollback には push しない（領域外の行は触らない）。tmux の status bar 固定など
   * 部分レイアウト用。
   */
  @Synchronized
  fun scrollUpRegion(top: Int, bottom: Int, style: CellStyle, count: Int = 1) {
    val t = top.coerceIn(0, rows - 1)
    val b = bottom.coerceIn(t, rows - 1)
    val n = count.coerceAtMost(b - t + 1)
    if (n <= 0) return
    for (r in t..(b - n)) {
      for (c in 0 until cols) grid[r][c].copyFrom(grid[r + n][c])
    }
    for (r in (b - n + 1)..b) clearRow(r, style)
  }

  /** 同じく領域版の scrollDown。RI で scrollTop を跨ぐとき等に使う。 */
  @Synchronized
  fun scrollDownRegion(top: Int, bottom: Int, style: CellStyle, count: Int = 1) {
    val t = top.coerceIn(0, rows - 1)
    val b = bottom.coerceIn(t, rows - 1)
    val n = count.coerceAtMost(b - t + 1)
    if (n <= 0) return
    for (r in b downTo (t + n)) {
      for (c in 0 until cols) grid[r][c].copyFrom(grid[r - n][c])
    }
    for (r in t until (t + n)) clearRow(r, style)
  }

  companion object {
    private val EMPTY_CELL = Cell()

    // scrollback 上限のデフォルトと動的調整のクランプ範囲。
    const val DEFAULT_MAX_SCROLLBACK = 2000
    const val MIN_MAX_SCROLLBACK = 200
    const val ABSOLUTE_MAX_SCROLLBACK = 5000
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
