package app.anoterm.terminal.emulator

import app.anoterm.util.CharWidth
import app.anoterm.util.Logger

/**
 * リモートから送られてくるバイト列を解釈し、画面バッファを更新する最小 VT100 / xterm 風エミュレータ。
 *
 * 対応範囲（MVP）:
 * - C0 制御文字（BEL, BS, HT, LF, VT, FF, CR, SO, SI）
 * - CSI：CUU/CUD/CUF/CUB (A/B/C/D)、CNL/CPL (E/F)、CHA (G)、CUP/HVP (H, f)、ED (J)、EL (K)、
 *   IL (L)、DL (M)、DCH (P)、ECH (X)、ICH (@)、VPA (d)、SGR (m)、DSR (n) 基本
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
  private var savedPrimaryScrollTop = 0
  private var savedPrimaryScrollBottom = initialRows - 1

  var cursorRow: Int = 0
    private set

  var cursorCol: Int = 0
    private set

  var cursorVisible: Boolean = true
    private set

  /**
   * DECCKM (Cursor Keys Mode, CSI ? 1 h/l)。true のとき方向キーは
   * `ESC O X` (SS3 系) で送信されるべき。false (default) は `ESC [ X`。
   * tmux や readline が active なときに on になることがある。
   */
  var applicationCursorKeys: Boolean = false
    private set

  /** mouse tracking 有効 (ボタン/モーション報告のどれか)。tmux `set -g mouse on` 等で ON。 */
  var mouseTrackingEnabled: Boolean = false
    private set

  /** SGR 拡張 mouse 報告フォーマット (`ESC [ < b ; x ; y M/m`) を使うか。1006 系。 */
  var mouseSgrMode: Boolean = false
    private set

  private var style: CellStyle = CellStyle.Default
  private var savedRow = 0
  private var savedCol = 0
  private var savedStyle: CellStyle = CellStyle.Default

  // DECSTBM (Set Top/Bottom Margins) によるスクロール領域。inclusive, 0-based。
  // tmux は content 行 0..rows-2 だけを自スクロール領域に指定し、最下行の status は
  // 固定したい。未実装だと LF が画面全体を push-to-scrollback してしまい、status 行が
  // 何度も scrollback に積まれて「同じ行が繰り返し流れる」症状になる。
  private var scrollTop = 0
  private var scrollBottom = initialRows - 1

  private val utf8 = Utf8Decoder()

  // CSI パラメータ蓄積
  private enum class State {
    Ground,
    Esc,
    // ESC の後に中間バイト (0x20-0x2F: '(' ')' '*' '+' '#' など) が来た時、
    // 直後にもう 1 バイト (final byte) を食わせて捨てる状態。代表的には
    // `ESC ( B` (US-ASCII を G0 に指定) — これを扱わないと 'B' が通常テキストとして
    // 画面に描かれて「B が勝手に入力欄に出る」症状になる。
    EscIntermediate,
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

  /**
   * 描画・feed と同じ emulator monitor を取得して直列化する。これがないと UI スレッドの
   * `onSizeChanged` 起因の resize が、背景スレッドの `feed` と並行で動き、buffer の
   * grid 入れ替えと put が競合して「gradlew が g a l w に見える」ような中抜け描画になる。
   */
  @Synchronized
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
    pendingWrap = false
    // resize で rows が変わるので scroll 領域を全画面に戻す。tmux 等は SIGWINCH で
    // 改めて DECSTBM を送り直すので、一旦リセットしておくのが安全。
    scrollTop = 0
    scrollBottom = buffer.rows - 1
  }

  /**
   * 受信バイト列を VT パーサに流す。
   *
   * 描画 (`TerminalRenderer.draw`) と race すると、セル grid が部分更新された状態で
   * 読まれて「前フレームと新フレームが重なって見える」視覚バグになる。
   * そのため feed 全体を `this` の monitor で直列化し、描画側も同じ monitor を
   * 取得してから読むようにする（呼び出し側で synchronized(emulator) を使う）。
   *
   * ただし、大きい SSH チャンク (tmux の全画面再描画等で数 KB) を 1 ロックで処理すると
   * UI スレッドの draw が数十 ms ブロックされフレーム落ちになる。そのため内部で 1KB
   * ごとに lock を解放し、draw に割り込む隙を作る。
   */
  fun feed(bytes: ByteArray, length: Int = bytes.size) {
    val chunkSize = 1024
    var i = 0
    while (i < length) {
      val end = (i + chunkSize).coerceAtMost(length)
      feedChunk(bytes, i, end)
      i = end
    }
  }

  @Synchronized
  private fun feedChunk(bytes: ByteArray, start: Int, end: Int) {
    var i = start
    while (i < end) {
      processByte(bytes[i])
      i++
    }
  }

  private fun processByte(b: Byte) {
    val x = b.toInt() and 0xFF
    when (state) {
      State.Ground -> handleGround(x)
      State.Esc -> handleEsc(x)
      State.EscIntermediate -> {
        // ESC ( B / ESC ) 0 / ESC # 8 等の final 文字を読み捨て。中身のチャーセット
        // テーブル切替は実装しない（UTF-8 運用前提、DEC Special Graphics などはごく稀）。
        state = State.Ground
      }
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
    // 途中で切れた UTF-8 シーケンスを壊したバイトは捨てず、先頭として読み直す。
    // ESC が失われると、以降のエスケープシーケンスが本文として描画されてしまう。
    utf8.pending()?.let { processByte(it) }
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
        indexDown()
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
        pendingWrap = false
        state = State.Ground
      }
      'D' -> { // IND
        indexDown()
        state = State.Ground
      }
      'E' -> { // NEL
        indexDown()
        cursorCol = 0
        state = State.Ground
      }
      'M' -> { // RI
        indexUp()
        state = State.Ground
      }
      // ESC ( / ) / * / + — G0..G3 文字集合指定。final 1 バイトを食わせて捨てる。
      // ESC # — DEC 二重高/幅・DECALN 等。ここも 1 バイト読み捨てで Ground 復帰。
      // 実装しないと次の文字が素通しで画面に描かれる。
      '(', ')', '*', '+', '#' -> state = State.EscIntermediate
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
      // SU (Scroll Up) / SD (Scroll Down): スクロール領域内を N 行ぶんシフトする。
      // Claude Code (ratatui) が `CSI 31 S` のような形でまとめてスクロールを要求する。
      // カーソル位置は変更しない。未実装だと画面が追従せず旧内容が重なって残骸に見える。
      'S' -> {
        val n = param(0).coerceAtLeast(1)
        repeat(n.coerceAtMost(rows)) { scrollRegionUpOne() }
        pendingWrap = false
      }
      'T' -> {
        val n = param(0).coerceAtLeast(1)
        repeat(n.coerceAtMost(rows)) { scrollRegionDownOne() }
        pendingWrap = false
      }
      'P' -> deleteChars(param(0))
      // ECH (Erase Character): カーソルを動かさず現在位置から N 文字を空セルに。
      // tmux のステータスバー更新や ratatui 系 TUI (Claude Code 等) が部分再描画で
      // 多用する。未実装だと旧文字が残り、新文字と重なって「表示がグチャグチャ」に見える。
      'X' -> eraseChars(param(0))
      '@' -> insertChars(param(0))
      'm' -> applySgr()
      'n' -> {
        if ((csiParams.firstOrNull() ?: 0) == 6) {
          // DSR 6 - Report Cursor Position
          val report = "[${cursorRow + 1};${cursorCol + 1}R"
          output.write(report.toByteArray(Charsets.US_ASCII))
        }
      }
      'r' -> {
        // DECSTBM: Set Top/Bottom Margins (scroll region)。引数は 1-based inclusive。
        // 引数省略時は画面全体。tmux が最下行の status bar を固定するために常用する。
        val top = (param(0, default = 1) - 1).coerceIn(0, rows - 1)
        val bottom = (param(1, default = rows) - 1).coerceIn(top, rows - 1)
        scrollTop = top
        scrollBottom = bottom
        // DECSTBM 後はカーソルを Home に戻す（xterm 互換）
        cursorRow = 0
        cursorCol = 0
        pendingWrap = false
      }
      's' -> {
        savedRow = cursorRow; savedCol = cursorCol; savedStyle = style
      }
      'u' -> {
        cursorRow = savedRow.coerceIn(0, rows - 1)
        cursorCol = savedCol.coerceIn(0, cols - 1)
        style = savedStyle
        pendingWrap = false
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
        // DECCKM: application cursor keys mode。tmux / readline が enable にすると
        // 矢印キーを `ESC O A` 系で送ってくる期待に変わる。TerminalView.sendBytes で
        // この flag を見て送信バイトを remap する。
        1 -> applicationCursorKeys = set
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
            pendingWrap = false
          }
        }
        // マウストラッキング系は flag を保持して wheel event 送信可否に使う。
        //  1000: X10/VT200 mouse button tracking
        //  1002: button-event mouse tracking
        //  1003: any-event mouse tracking
        //  1006: SGR extended mouse reporting (現代の tmux はほぼ常にこちら)
        1000, 1002, 1003 -> mouseTrackingEnabled = set
        1006 -> mouseSgrMode = set
        // カーソル blink / focus events / bracketed paste は実装せず受理のみ。
        // 受理せず Unhandled を吐くと tmux/Claude Code が set/reset を連打して
        // logcat が埋まる。
        1004, 2004, 12 -> { /* 受理のみ */ }
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
    savedPrimaryScrollTop = scrollTop
    savedPrimaryScrollBottom = scrollBottom
    onAlternate = true
    // 代替画面に切替える前に alternate 側を現在サイズに合わせる（resize 追従）
    alternateBuffer.resize(primaryBuffer.rows, primaryBuffer.cols)
    alternateBuffer.clearAll(CellStyle.Default)
    buffer = alternateBuffer
    cursorRow = 0
    cursorCol = 0
    style = CellStyle.Default
    pendingWrap = false
    // alt screen は fresh な scroll 領域（全画面）でスタート。tmux が必要なら改めて設定。
    scrollTop = 0
    scrollBottom = buffer.rows - 1
  }

  /** 代替画面から抜け、プライマリバッファに戻る。カーソル・スタイルを復元。 */
  private fun leaveAlternateScreen() {
    if (!onAlternate) return
    onAlternate = false
    buffer = primaryBuffer
    cursorRow = savedPrimaryRow.coerceIn(0, rows - 1)
    cursorCol = savedPrimaryCol.coerceIn(0, cols - 1)
    style = savedPrimaryStyle
    pendingWrap = false
    scrollTop = savedPrimaryScrollTop.coerceIn(0, buffer.rows - 1)
    scrollBottom = savedPrimaryScrollBottom.coerceIn(scrollTop, buffer.rows - 1)
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

  /**
   * scrollback（履歴は primary バッファのみが持つ）の上限行数を変更する。
   * emulator monitor を取るので、feed / draw と直列化され安全。メモリバジェット管理から呼ぶ。
   */
  @Synchronized
  fun setScrollbackLimit(limit: Int) {
    primaryBuffer.setMaxScrollback(limit)
  }

  private fun deleteChars(count: Int) {
    val n = count.coerceAtMost(cols - cursorCol)
    for (c in cursorCol until cols - n) {
      buffer.copyCell(cursorRow, c, cursorRow, c + n)
    }
    for (c in cols - n until cols) buffer.clearCell(cursorRow, c, style)
  }

  private fun eraseChars(count: Int) {
    val n = count.coerceAtLeast(1).coerceAtMost(cols - cursorCol)
    for (c in cursorCol until cursorCol + n) buffer.clearCell(cursorRow, c, style)
  }

  private fun insertChars(count: Int) {
    val n = count.coerceAtMost(cols - cursorCol)
    for (c in cols - 1 downTo cursorCol + n) {
      buffer.copyCell(cursorRow, c, cursorRow, c - n)
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
    // カーソル移動（CUP/CUF/CUB/HVP/CR/BS/HT 等）は必ず pendingWrap を解除。
    // これがないと CUP で移動した先でも「前の右端到達フラグ」が残って、次の文字が
    // 不要に改行してしまう。
    pendingWrap = false
  }

  /**
   * xterm 互換の pending-wrap (DECAWM の末端挙動)。右端列に 1 文字書き込んだ直後は
   * カーソルを動かさず、次の 1 文字が来たタイミングで初めて改行する。これを実装しないと
   * 長い行の最終列に書かれた文字が次々と上書きされ、行末の文字列が欠落して「表示が
   * グチャグチャ」に見える症状になる。
   */
  private var pendingWrap = false

  private fun writeCodePoint(cp: Int) {
    val width = CharWidth.widthOf(cp).coerceAtLeast(1)
    if (pendingWrap) {
      pendingWrap = false
      indexDownForWrap()
      cursorCol = 0
    }
    if (cursorCol + width > cols) {
      // width=2 の文字で cursorCol = cols-1 に乗り上げた場合のみここに入る。
      indexDownForWrap()
      cursorCol = 0
    }
    buffer.put(cursorRow, cursorCol, cp, style)
    cursorCol += width
    if (cursorCol >= cols) {
      cursorCol = cols - 1
      pendingWrap = true
    }
  }

  /**
   * LF/IND/NEL 系の下方向 index。scrollBottom 到達なら領域スクロール、領域内なら単純にカーソル
   * 下げ、scroll region より下の行（tmux の status bar 等）にいる場合は rows-1 を超えないよう
   * clamp する。この clamp がないと autowrap で cursorRow が rows まで飛び、put() が bounds
   * 越えで no-op になり続ける。
   */
  private fun indexDown() {
    when {
      cursorRow == scrollBottom -> scrollRegionUpOne()
      cursorRow < rows - 1 -> cursorRow++
      // else: scroll region の下に取り残された最下行 (status 行など) — 移動せず
    }
    pendingWrap = false
  }

  /** autowrap 用: scroll region 外にいるときは現行で clamp。物理最下行からの wrap は無視。 */
  private fun indexDownForWrap() {
    when {
      cursorRow == scrollBottom -> scrollRegionUpOne()
      cursorRow < rows - 1 -> cursorRow++
    }
  }

  private fun indexUp() {
    when {
      cursorRow == scrollTop -> scrollRegionDownOne()
      cursorRow > 0 -> cursorRow--
    }
    pendingWrap = false
  }

  /**
   * LF/IND/自動折返し等でカーソルが scrollBottom に達した時のスクロール。
   * スクロール領域が画面全体 (0..rows-1) なら従来通り scrollback に push するが、
   * 部分領域なら push せず、領域内だけを上にシフトする。これをやらないと tmux の
   * status 行付きレイアウトで「LF のたびに status 行が scrollback に積まれ、
   * 画面が同じ行で埋め尽くされる」症状になる。
   */
  private fun scrollRegionUpOne() {
    if (scrollTop == 0 && scrollBottom == rows - 1) {
      buffer.scrollUp(style)
    } else {
      buffer.scrollUpRegion(scrollTop, scrollBottom, style)
    }
  }

  private fun scrollRegionDownOne() {
    if (scrollTop == 0 && scrollBottom == rows - 1) {
      buffer.scrollDown(style)
    } else {
      buffer.scrollDownRegion(scrollTop, scrollBottom, style)
    }
  }

  /**
   * RIS (ESC c) と同等の soft reset。画面セル・カーソル・SGR 状態をリセットする。
   *
   * xterm 準拠でスクロールバックは意図的に保持する。clearAll はセルだけをクリアし
   * scrollback deque には触れない。ユーザが `reset` コマンドで画面をリセットしても、
   * 過去に流れたログまで失われると使い勝手が悪い。
   */
  @Synchronized
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
    applicationCursorKeys = false
    mouseTrackingEnabled = false
    mouseSgrMode = false
    pendingWrap = false
    scrollTop = 0
    scrollBottom = buffer.rows - 1
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
