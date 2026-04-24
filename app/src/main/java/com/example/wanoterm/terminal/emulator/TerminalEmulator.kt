package com.example.wanoterm.terminal.emulator

import com.example.wanoterm.util.CharWidth
import com.example.wanoterm.util.Logger

/**
 * リモートから送られてくるバイト列を解釈し、画面バッファを更新する最小 VT100 / xterm 風エミュレータ。
 *
 * 対応範囲（MVP）:
 * - C0 制御文字（BEL, BS, HT, LF, VT, FF, CR, SO, SI）
 * - CSI：CUU/CUD/CUF/CUB (A/B/C/D)、CNL/CPL (E/F)、CHA (G)、CUP/HVP (H, f)、ED (J)、EL (K)、
 *   IL (L)、DL (M)、DCH (P)、ICH (@)、VPA (d)、SGR (m)、DSR (n) 基本
 * - SGR: reset(0)、bold(1)、dim(2)、italic(3)、underline(4)、reverse(7)、strike(9)、
 *   bold off(22)、italic off(23)、underline off(24)、reverse off(27)、strike off(29)、
 *   FG 30-37 / 38;5;n / 38;2;r;g;b / 39、BG 40-47 / 48;5;n / 48;2;r;g;b / 49、bright 90-97 / 100-107
 * - DECSET/RST: ?25 (cursor visible) をフラグとして持つのみ
 * 非対応（後続フェーズ）: 代替画面バッファ（?1049）、スクロール領域 (DECSTBM) の完全対応、
 *   マウス、DEC 文字集合切替、DA, OSC のほとんど。
 *
 * 座標系：内部は 0-based。CSI 引数は 1-based なので処理時に変換する。
 */
class TerminalEmulator(
    initialRows: Int,
    initialCols: Int,
    private val output: TerminalOutput,
) {
  // プライマリバッファ（通常のシェル表示）と、代替画面バッファ（vim/less/tmux が使う）。
  // `buffer` は現在アクティブな方を指す。切替時は cursor / style も保存・復元する。
  private val primaryBuffer = TerminalBuffer(initialRows, initialCols)
  // 代替画面は vim/less/tmux が「この画面で作業して出ればクリア」用に使うので
  // スクロールバックに痕跡を残してはいけない（VT100 仕様）。
  private val alternateBuffer =
      TerminalBuffer(initialRows, initialCols, scrollbackEnabled = false)
  var buffer: TerminalBuffer = primaryBuffer
    private set
  private var onAlternate = false

  // プライマリに戻るとき用に保存しておくカーソル/スタイル
  private var savedPrimaryRow = 0
  private var savedPrimaryCol = 0
  private var savedPrimaryStyle: CellStyle = CellStyle.Default

  var cursorRow: Int = 0
    private set

  var cursorCol: Int = 0
    private set

  var cursorVisible: Boolean = true
    private set

  private var style: CellStyle = CellStyle.Default
  private var savedRow = 0
  private var savedCol = 0
  private var savedStyle: CellStyle = CellStyle.Default

  private val utf8 = Utf8Decoder()

  // CSI パラメータ蓄積
  private enum class State {
    Ground,
    Esc,
    Csi,
    Osc,
    // 下の 4 つは payload を読み飛ばすだけ。ST (ESC\) か BEL で Ground に戻る。
    // この分岐を持たないと、DCS (ESC P ...) が飛んできたときに payload が
    // そのままテキストとして画面に出てしまい「B がたくさん出る」等の崩れになる。
    Dcs,
    Apc,
    Pm,
    Sos,
  }
  private var state = State.Ground
  private val csiParams = ArrayDeque<Int>()
  private var csiCurrent = -1 // -1 は未指定
  private var csiPrivate = false // '?'
  private val oscBuffer = StringBuilder()

  val rows: Int
    get() = buffer.rows

  val cols: Int
    get() = buffer.cols

  fun currentStyle(): CellStyle = style

  fun resize(newRows: Int, newCols: Int) {
    if (newRows == buffer.rows && newCols == buffer.cols) return
    // 縮小時はカーソルを画面内に収めるため、旧行のうち cursor を含む下側を保持する。
    // 拡大時は旧行を上から並べ、下に空行が増える。
    val rowOffset =
        if (newRows >= buffer.rows) 0
        else (cursorRow - (newRows - 1)).coerceIn(0, buffer.rows - newRows)
    // プライマリ・代替両方をリサイズ（非アクティブでも寸法は揃えておく、切替後の表示崩れ防止）
    primaryBuffer.resize(newRows, newCols, if (buffer === primaryBuffer) rowOffset else 0)
    alternateBuffer.resize(newRows, newCols, if (buffer === alternateBuffer) rowOffset else 0)
    cursorRow = (cursorRow - rowOffset).coerceIn(0, buffer.rows - 1)
    cursorCol = cursorCol.coerceIn(0, buffer.cols - 1)
  }

  fun feed(bytes: ByteArray, length: Int = bytes.size) {
    var i = 0
    while (i < length) {
      processByte(bytes[i])
      i++
    }
  }

  private fun processByte(b: Byte) {
    val x = b.toInt() and 0xFF
    when (state) {
      State.Ground -> handleGround(x)
      State.Esc -> handleEsc(x)
      State.Csi -> handleCsi(x)
      State.Osc -> handleOsc(x)
      State.Dcs, State.Apc, State.Pm, State.Sos -> handleStringTerminated(x)
    }
  }

  /**
   * DCS / APC / PM / SOS は ST (ESC\\) または BEL まで読み飛ばす。
   * ESC を見たら Esc 状態へ行かせて、次が '\\' なら ST 終端、それ以外は元の状態へ戻らず Ground に落とす（単純化）。
   */
  private fun handleStringTerminated(x: Int) {
    when (x) {
      0x07 -> state = State.Ground // BEL terminator
      0x1B -> state = State.Esc // ST の前半。後続の '\\' は handleEsc の else で Ground へ戻る
      else -> { /* payload 読み飛ばし */ }
    }
  }

  private fun handleGround(x: Int) {
    if (x < 0x20 || x == 0x7F) {
      handleC0(x)
      return
    }
    if (x == 0x1B) {
      state = State.Esc
      return
    }
    // 通常テキスト — UTF-8 デコーダへ
    val cp = utf8.feed(x.toByte()) ?: return
    writeCodePoint(cp)
  }

  private fun handleC0(x: Int) {
    when (x) {
      0x07 -> onBell?.invoke() // BEL — 上位層でハプティックフィードバック等
      0x08 -> moveCursor(cursorRow, (cursorCol - 1).coerceAtLeast(0)) // BS
      0x09 -> { // HT
        val next = ((cursorCol / 8) + 1) * 8
        moveCursor(cursorRow, next.coerceAtMost(cols - 1))
      }
      0x0A, 0x0B, 0x0C -> { // LF/VT/FF
        if (cursorRow == rows - 1) buffer.scrollUp(style) else moveCursor(cursorRow + 1, cursorCol)
      }
      0x0D -> moveCursor(cursorRow, 0) // CR
      0x1B -> state = State.Esc
      else -> { /* SO/SI 等は無視 */ }
    }
  }

  private fun handleEsc(x: Int) {
    when (x.toChar()) {
      '[' -> {
        state = State.Csi
        csiParams.clear()
        csiCurrent = -1
        csiPrivate = false
      }
      ']' -> {
        state = State.Osc
        oscBuffer.setLength(0)
      }
      'P' -> state = State.Dcs // Device Control String
      '_' -> state = State.Apc // Application Program Command
      '^' -> state = State.Pm // Privacy Message
      'X' -> state = State.Sos // Start Of String
      'c' -> { // RIS - リセット
        reset()
        state = State.Ground
      }
      '7' -> { // DECSC
        savedRow = cursorRow
        savedCol = cursorCol
        savedStyle = style
        state = State.Ground
      }
      '8' -> { // DECRC
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        style = savedStyle
        state = State.Ground
      }
      'D' -> { // IND
        if (cursorRow == rows - 1) buffer.scrollUp(style) else cursorRow++
        state = State.Ground
      }
      'E' -> { // NEL
        if (cursorRow == rows - 1) buffer.scrollUp(style) else cursorRow++
        cursorCol = 0
        state = State.Ground
      }
      'M' -> { // RI
        if (cursorRow == 0) buffer.scrollDown(style) else cursorRow--
        state = State.Ground
      }
      else -> state = State.Ground
    }
  }

  private fun handleCsi(x: Int) {
    val ch = x.toChar()
    when {
      ch == '?' && csiParams.isEmpty() && csiCurrent == -1 -> csiPrivate = true
      ch in '0'..'9' -> {
        if (csiCurrent < 0) csiCurrent = 0
        csiCurrent = csiCurrent * 10 + (ch - '0')
      }
      ch == ';' -> {
        csiParams.addLast(csiCurrent.coerceAtLeast(0))
        csiCurrent = -1
      }
      ch in '@'..'~' -> {
        if (csiCurrent >= 0) csiParams.addLast(csiCurrent)
        dispatchCsi(ch)
        state = State.Ground
      }
      else -> { /* 無視 */ }
    }
  }

  private fun handleOsc(x: Int) {
    // OSC は BEL または ST (ESC \) で終端
    if (x == 0x07) {
      dispatchOsc()
      state = State.Ground
      return
    }
    if (x == 0x1B) {
      // 続くバイトが '\' なら ST — handleEsc 側で Ground に戻す前に dispatchOsc しておく
      dispatchOsc()
      state = State.Esc
      return
    }
    oscBuffer.append(x.toChar())
  }

  /**
   * OSC 文字列を解釈。現状サポート:
   *  - OSC 0;title   ウィンドウタイトル + アイコンタイトル
   *  - OSC 1;title   アイコンタイトル
   *  - OSC 2;title   ウィンドウタイトル
   * タイトルは `onTitleChanged` コールバック経由で UI に通知。
   */
  private fun dispatchOsc() {
    val s = oscBuffer.toString()
    oscBuffer.setLength(0)
    val sep = s.indexOf(';')
    if (sep < 0) return
    val code = s.substring(0, sep).toIntOrNull() ?: return
    val arg = s.substring(sep + 1)
    when (code) {
      0, 1, 2 -> onTitleChanged?.invoke(arg)
      // 他の OSC は今はログのみ
      else -> Logger.d("VT", "Unhandled OSC $code (${arg.length} chars)")
    }
  }

  /** リモートから OSC 0/2 でタイトルが来た時に呼ばれるハンドラ。 */
  var onTitleChanged: ((String) -> Unit)? = null

  /** BEL (0x07) 受信時のハンドラ。UI は軽いハプティック等に使う。 */
  var onBell: (() -> Unit)? = null

  private fun param(index: Int, default: Int = 1): Int {
    val v = csiParams.elementAtOrNull(index) ?: return default
    return if (v <= 0) default else v
  }

  private fun dispatchCsi(final: Char) {
    if (csiPrivate) {
      dispatchPrivateCsi(final)
      return
    }
    when (final) {
      'A' -> moveCursor(cursorRow - param(0), cursorCol)
      'B' -> moveCursor(cursorRow + param(0), cursorCol)
      'C' -> moveCursor(cursorRow, cursorCol + param(0))
      'D' -> moveCursor(cursorRow, cursorCol - param(0))
      'E' -> moveCursor(cursorRow + param(0), 0)
      'F' -> moveCursor(cursorRow - param(0), 0)
      'G' -> moveCursor(cursorRow, param(0) - 1)
      'H', 'f' -> moveCursor(param(0) - 1, param(1) - 1)
      'd' -> moveCursor(param(0) - 1, cursorCol)
      'J' -> eraseInDisplay(csiParams.firstOrNull() ?: 0)
      'K' -> eraseInLine(csiParams.firstOrNull() ?: 0)
      'L' -> buffer.scrollDown(style, param(0)) // IL (粗)
      // DL は画面内 editing 操作なので primary buffer でも scrollback に残さない
      'M' -> buffer.scrollUp(style, param(0), pushToScrollback = false) // DL (粗)
      'P' -> deleteChars(param(0))
      '@' -> insertChars(param(0))
      'm' -> applySgr()
      'n' -> {
        if ((csiParams.firstOrNull() ?: 0) == 6) {
          // DSR 6 - Report Cursor Position
          val report = "[${cursorRow + 1};${cursorCol + 1}R"
          output.write(report.toByteArray(Charsets.US_ASCII))
        }
      }
      's' -> {
        savedRow = cursorRow; savedCol = cursorCol; savedStyle = style
      }
      'u' -> {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        style = savedStyle
      }
      else -> Logger.d("VT", "Unhandled CSI ${csiParams.joinToString(";")} $final")
    }
  }

  private fun dispatchPrivateCsi(final: Char) {
    val set = final == 'h'
    val reset = final == 'l'
    if (!set && !reset) return
    for (p in csiParams) {
      when (p) {
        25 -> cursorVisible = set
        // 1049: 代替画面 + カーソル保存/復元 (xterm 拡張、vim/tmux/less で必須)
        1049 -> if (set) enterAlternateScreen() else leaveAlternateScreen()
        // 47 / 1047: 単純な代替画面切替（1049 のカーソル保存なし版）
        47, 1047 -> if (set) enterAlternateScreen() else leaveAlternateScreen()
        // 1048: カーソル位置の保存/復元のみ
        1048 -> {
          if (set) {
            savedPrimaryRow = cursorRow
            savedPrimaryCol = cursorCol
            savedPrimaryStyle = style
          } else {
            cursorRow = savedPrimaryRow.coerceIn(0, rows - 1)
            cursorCol = savedPrimaryCol.coerceIn(0, cols - 1)
            style = savedPrimaryStyle
          }
        }
        // 他の DECSET は保留
        else -> Logger.d("VT", "Unhandled DEC ${if (set) "SET" else "RST"} $p")
      }
    }
  }

  /**
   * 代替画面バッファへ切替。プライマリバッファのカーソル・スタイルを保存し、
   * 代替画面は常にクリアされた状態でスタートする（vim 流）。
   */
  private fun enterAlternateScreen() {
    if (onAlternate) return
    savedPrimaryRow = cursorRow
    savedPrimaryCol = cursorCol
    savedPrimaryStyle = style
    onAlternate = true
    // 代替画面に切替える前に alternate 側を現在サイズに合わせる（resize 追従）
    alternateBuffer.resize(primaryBuffer.rows, primaryBuffer.cols)
    alternateBuffer.clearAll(CellStyle.Default)
    buffer = alternateBuffer
    cursorRow = 0
    cursorCol = 0
    style = CellStyle.Default
  }

  /** 代替画面から抜け、プライマリバッファに戻る。カーソル・スタイルを復元。 */
  private fun leaveAlternateScreen() {
    if (!onAlternate) return
    onAlternate = false
    buffer = primaryBuffer
    cursorRow = savedPrimaryRow.coerceIn(0, rows - 1)
    cursorCol = savedPrimaryCol.coerceIn(0, cols - 1)
    style = savedPrimaryStyle
  }

  private fun eraseInDisplay(mode: Int) {
    when (mode) {
      0 -> {
        // カーソル位置から末尾まで
        buffer.clearRow(cursorRow, style, cursorCol, cols - 1)
        for (r in cursorRow + 1 until rows) buffer.clearRow(r, style)
      }
      1 -> {
        for (r in 0 until cursorRow) buffer.clearRow(r, style)
        buffer.clearRow(cursorRow, style, 0, cursorCol)
      }
      2, 3 -> buffer.clearAll(style)
    }
  }

  private fun eraseInLine(mode: Int) {
    when (mode) {
      0 -> buffer.clearRow(cursorRow, style, cursorCol, cols - 1)
      1 -> buffer.clearRow(cursorRow, style, 0, cursorCol)
      2 -> buffer.clearRow(cursorRow, style)
    }
  }

  private fun deleteChars(count: Int) {
    val n = count.coerceAtMost(cols - cursorCol)
    for (c in cursorCol until cols - n) {
      buffer.cellAt(cursorRow, c).copyFrom(buffer.cellAt(cursorRow, c + n))
    }
    for (c in cols - n until cols) buffer.clearCell(cursorRow, c, style)
  }

  private fun insertChars(count: Int) {
    val n = count.coerceAtMost(cols - cursorCol)
    for (c in cols - 1 downTo cursorCol + n) {
      buffer.cellAt(cursorRow, c).copyFrom(buffer.cellAt(cursorRow, c - n))
    }
    for (c in cursorCol until cursorCol + n) buffer.clearCell(cursorRow, c, style)
  }

  private fun applySgr() {
    if (csiParams.isEmpty()) {
      style = CellStyle.Default
      return
    }
    val it = csiParams.iterator()
    while (it.hasNext()) {
      val p = it.next()
      when (p) {
        0 -> style = CellStyle.Default
        1 -> style = style.copy(bold = true)
        2 -> style = style.copy(dim = true)
        3 -> style = style.copy(italic = true)
        4 -> style = style.copy(underline = true)
        7 -> style = style.copy(reverse = true)
        9 -> style = style.copy(strike = true)
        22 -> style = style.copy(bold = false, dim = false)
        23 -> style = style.copy(italic = false)
        24 -> style = style.copy(underline = false)
        27 -> style = style.copy(reverse = false)
        29 -> style = style.copy(strike = false)
        in 30..37 -> style = style.copy(fg = AnsiColor.Indexed(p - 30))
        38 -> {
          val kind = if (it.hasNext()) it.next() else break
          when (kind) {
            5 -> if (it.hasNext()) style = style.copy(fg = AnsiColor.Indexed(it.next() and 0xFF))
            2 -> {
              val r = if (it.hasNext()) it.next() else 0
              val g = if (it.hasNext()) it.next() else 0
              val b = if (it.hasNext()) it.next() else 0
              style = style.copy(fg = AnsiColor.Rgb(r, g, b))
            }
          }
        }
        39 -> style = style.copy(fg = AnsiColor.Default)
        in 40..47 -> style = style.copy(bg = AnsiColor.Indexed(p - 40))
        48 -> {
          val kind = if (it.hasNext()) it.next() else break
          when (kind) {
            5 -> if (it.hasNext()) style = style.copy(bg = AnsiColor.Indexed(it.next() and 0xFF))
            2 -> {
              val r = if (it.hasNext()) it.next() else 0
              val g = if (it.hasNext()) it.next() else 0
              val b = if (it.hasNext()) it.next() else 0
              style = style.copy(bg = AnsiColor.Rgb(r, g, b))
            }
          }
        }
        49 -> style = style.copy(bg = AnsiColor.Default)
        in 90..97 -> style = style.copy(fg = AnsiColor.Indexed(p - 90 + 8))
        in 100..107 -> style = style.copy(bg = AnsiColor.Indexed(p - 100 + 8))
      }
    }
  }

  private fun moveCursor(row: Int, col: Int) {
    cursorRow = row.coerceIn(0, rows - 1)
    cursorCol = col.coerceIn(0, cols - 1)
  }

  private fun writeCodePoint(cp: Int) {
    val width = CharWidth.widthOf(cp).coerceAtLeast(1)
    if (cursorCol + width > cols) {
      // 自動改行
      if (cursorRow == rows - 1) buffer.scrollUp(style) else cursorRow++
      cursorCol = 0
    }
    buffer.put(cursorRow, cursorCol, cp, style)
    cursorCol += width
    if (cursorCol >= cols) cursorCol = cols - 1
  }

  /**
   * RIS (ESC c) と同等の soft reset。画面セル・カーソル・SGR 状態をリセットする。
   *
   * xterm 準拠でスクロールバックは意図的に保持する。clearAll はセルだけをクリアし
   * scrollback deque には触れない。ユーザが `reset` コマンドで画面をリセットしても、
   * 過去に流れたログまで失われると使い勝手が悪い。
   */
  fun reset() {
    utf8.reset()
    csiParams.clear()
    csiCurrent = -1
    csiPrivate = false
    oscBuffer.setLength(0)
    state = State.Ground
    cursorRow = 0
    cursorCol = 0
    cursorVisible = true
    style = CellStyle.Default
    savedRow = 0
    savedCol = 0
    savedStyle = CellStyle.Default
    // alt screen を抜けて primary に戻してからクリア
    onAlternate = false
    buffer = primaryBuffer
    primaryBuffer.clearAll(style)
    alternateBuffer.clearAll(style)
  }
}

/** エミュレータからホスト（SSH チャネル）へのバイト送出インターフェース。 */
interface TerminalOutput {
  fun write(bytes: ByteArray)
}
