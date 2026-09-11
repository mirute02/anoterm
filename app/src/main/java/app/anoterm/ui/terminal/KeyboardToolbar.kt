package app.anoterm.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material.icons.filled.Apps
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import android.view.HapticFeedbackConstants
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import app.anoterm.R

/**
 * ターミナル下部の補助キー列。キーボードを出している間だけ出る。
 *
 * 1 段に収めることを最優先にしている。以前は返答キー (1/2/3/y/n) が別の段に分かれていて、
 * ショートカット段と合わせると 3 段が本文の上に積まれた。狭い画面では本文が数行しか残らず、
 * 何に答えようとしているのかが見えなくなる。列が長くなっても横に流せばよいが、
 * 縦は取り返しがつかない。
 *
 * - ボタン高さ 32dp
 * - 右端に「ショートカット展開」「キーボード閉じ」を固定
 * - 中央は横スクロール、並びは使用頻度順
 */
@Composable
fun KeyboardToolbar(
    ctrlArmed: Boolean,
    shortcutBarVisible: Boolean,
    keyboardVisible: Boolean,
    onToggleCtrl: () -> Unit,
    onToggleShortcutBar: () -> Unit,
    onToggleKeyboard: () -> Unit,
    onSend: (ByteArray) -> Unit,
    modifier: Modifier = Modifier,
) {
  Row(
      modifier = modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(4.dp),
  ) {
    val tap = rememberKeyTap()
    // スクロール可能な中央のキー群
    Row(
        modifier =
            Modifier.weight(1f).horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      KeyButton(stringResource(R.string.kbd_esc)) { onSend(byteArrayOf(0x1B)) }
      KeyButton(stringResource(R.string.kbd_tab)) { onSend(byteArrayOf(0x09)) }
      // Shift+Tab (CSI Z)。定義は FloatingReplyPad.kt と共用。
      // Claude Code はこれで権限モードを切り替える。
      // ソフトキーボードで Shift を押しながら Tab を打つのは現実的でないので、
      // 1 つのキーとして置く。承認のたびに 1 を押す作業から降りる正規の道はこれ。
      KeyButton(stringResource(R.string.kbd_backtab)) { onSend(ESC_BACKTAB) }
      // 承認プロンプトへの返答。文字と改行をまとめて送るので 1 タップで済む。
      // 打てば済む字ではあるが、Claude Code / Codex を触っている間はこれが最頻。
      REPLY_KEYS.forEach { (label, bytes) -> KeyButton(label) { onSend(bytes) } }
      if (ctrlArmed) {
        FilledTonalButton(
            onClick = {
              tap()
              onToggleCtrl()
            },
            contentPadding = TinyPadding,
            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 32.dp),
        ) {
          Text(stringResource(R.string.kbd_ctrl), style = MaterialTheme.typography.labelMedium)
        }
      } else {
        OutlinedButton(
            onClick = {
              tap()
              onToggleCtrl()
            },
            contentPadding = TinyPadding,
            modifier = Modifier.defaultMinSize(minWidth = 44.dp, minHeight = 32.dp),
        ) {
          Text(stringResource(R.string.kbd_ctrl), style = MaterialTheme.typography.labelMedium)
        }
      }
      KeyButton("⌃B") { onSend(byteArrayOf(0x02)) }
      KeyButton(stringResource(R.string.kbd_up)) { onSend(ESC_UP) }
      KeyButton(stringResource(R.string.kbd_down)) { onSend(ESC_DOWN) }
      KeyButton(stringResource(R.string.kbd_left)) { onSend(ESC_LEFT) }
      KeyButton(stringResource(R.string.kbd_right)) { onSend(ESC_RIGHT) }
      KeyButton(stringResource(R.string.kbd_pipe)) { onSend(byteArrayOf('|'.code.toByte())) }
      KeyButton(stringResource(R.string.kbd_home)) { onSend(ESC_HOME) }
      KeyButton(stringResource(R.string.kbd_end)) { onSend(ESC_END) }
      KeyButton(stringResource(R.string.kbd_pgup)) { onSend(ESC_PGUP) }
      KeyButton(stringResource(R.string.kbd_pgdn)) { onSend(ESC_PGDN) }
    }
    // 右端は固定: ショートカット展開 / キーボードトグル
    IconToggleButtonBar(
        shortcutBarVisible = shortcutBarVisible,
        keyboardVisible = keyboardVisible,
        onToggleShortcutBar = onToggleShortcutBar,
        onToggleKeyboard = onToggleKeyboard,
    )
  }
}

@Composable
private fun IconToggleButtonBar(
    shortcutBarVisible: Boolean,
    keyboardVisible: Boolean,
    onToggleShortcutBar: () -> Unit,
    onToggleKeyboard: () -> Unit,
) {
  Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
    IconKeyButton(
        icon = if (shortcutBarVisible) Icons.Filled.KeyboardArrowDown else Icons.Filled.Apps,
        contentDescription = "custom shortcuts toggle",
        onClick = onToggleShortcutBar,
    )
    // IME 表示中は「隠す」アイコン、非表示中は「表示する」アイコン。
    // 以前は Hide 専用で、一度閉じたあと同じボタンを押しても何も起きなかった。
    IconKeyButton(
        icon =
            if (keyboardVisible) Icons.Filled.KeyboardHide
            else Icons.Filled.Keyboard,
        contentDescription = if (keyboardVisible) "hide keyboard" else "show keyboard",
        onClick = onToggleKeyboard,
    )
  }
}

/**
 * 押した手応えを返す。
 *
 * 画面の上の平らなボタンは、押せたかどうかが指に返ってこない。端末に何が送られたかは
 * 相手の反応を待たないと分からないので、返事が遅いときほど「押せたのか」が分からない。
 * `FLAG_IGNORE_VIEW_SETTING` を付けるのは、端末側の触覚設定を切っている人でも
 * このボタンだけは返したいから（文字を送るキーは、押し損ねが直接の実害になる）。
 */
@Composable
private fun rememberKeyTap(): () -> Unit {
  val view = LocalView.current
  return remember(view) {
    {
      view.performHapticFeedback(
          HapticFeedbackConstants.KEYBOARD_TAP,
          HapticFeedbackConstants.FLAG_IGNORE_VIEW_SETTING,
      )
    }
  }
}

@Composable
private fun KeyButton(label: String, onClick: () -> Unit) {
  val tap = rememberKeyTap()
  OutlinedButton(
      onClick = {
        tap()
        onClick()
      },
      contentPadding = TinyPadding,
      modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 32.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
  ) {
    Text(label, style = MaterialTheme.typography.labelMedium)
  }
}

@Composable
private fun IconKeyButton(
    icon: ImageVector,
    contentDescription: String,
    onClick: () -> Unit,
) {
  val tap = rememberKeyTap()
  OutlinedButton(
      onClick = {
        tap()
        onClick()
      },
      contentPadding = TinyPadding,
      modifier = Modifier.defaultMinSize(minWidth = 40.dp, minHeight = 32.dp),
      colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.onSurface),
  ) {
    Icon(icon, contentDescription = contentDescription, modifier = Modifier.defaultMinSize(20.dp, 20.dp))
  }
}

private val TinyPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp)

// CSI シーケンスは必ず ESC (0x1B) 始まり。0x1B を明示して定義し、Edit/Write で invisibly に
// 落ちる事故を防ぐ。
private val ESC: Byte = 0x1B
private val LBR: Byte = '['.code.toByte()
private val ESC_UP = byteArrayOf(ESC, LBR, 'A'.code.toByte())
private val ESC_DOWN = byteArrayOf(ESC, LBR, 'B'.code.toByte())
private val ESC_RIGHT = byteArrayOf(ESC, LBR, 'C'.code.toByte())
private val ESC_LEFT = byteArrayOf(ESC, LBR, 'D'.code.toByte())
private val ESC_HOME = byteArrayOf(ESC, LBR, 'H'.code.toByte())
private val ESC_END = byteArrayOf(ESC, LBR, 'F'.code.toByte())
private val ESC_PGUP = byteArrayOf(ESC, LBR, '5'.code.toByte(), '~'.code.toByte())
private val ESC_PGDN = byteArrayOf(ESC, LBR, '6'.code.toByte(), '~'.code.toByte())

/**
 * 番号や y/n を選ばせるプロンプトに 1 タップで答えるためのキー。
 *
 * 送るのは「文字 + 改行」。番号を選ばせるプロンプトは改行まで来て初めて確定する。
 * プロンプトを検出して専用のボタンを出す案は採らなかった。相手の画面表示を読んで
 * 「これは承認プロンプトだ」と判断する仕組みは、表示が少し変わった日に無言で効かなくなる。
 * 常に同じキーを出すだけにして、何のプロンプトにも使える形にする。
 */
private val REPLY_KEYS: List<Pair<String, ByteArray>> =
    listOf(
        "1" to "1\r".toByteArray(Charsets.US_ASCII),
        "2" to "2\r".toByteArray(Charsets.US_ASCII),
        "3" to "3\r".toByteArray(Charsets.US_ASCII),
        "y" to "y\r".toByteArray(Charsets.US_ASCII),
        "n" to "n\r".toByteArray(Charsets.US_ASCII),
    )
