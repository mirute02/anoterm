package app.anoterm.ui.terminal

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

/**
 * 番号や y/n を選ばせるプロンプトに、キーボードを出さずに答えるための列。
 *
 * Claude Code や Codex の実行承認は「1/2/3 を打て」という形で来る。スマホでは
 * そのためだけに IME を開いて、狭い画面を半分潰して、1 文字打って閉じることになる。
 *
 * プロンプトを検出して専用のボタンを出す案は採らなかった。相手の画面表示を読んで
 * 「これは承認プロンプトだ」と判断する仕組みは、表示が少し変わった日に無言で
 * 効かなくなる。ここは常に同じキーを出すだけにして、何のプロンプトにも使える形にする。
 */
@Composable
fun ReplyKeyBar(onSend: (ByteArray) -> Unit, modifier: Modifier = Modifier) {
  Row(
      modifier =
          modifier
              .fillMaxWidth()
              .horizontalScroll(rememberScrollState())
              .padding(horizontal = 8.dp, vertical = 2.dp),
      horizontalArrangement = Arrangement.spacedBy(6.dp),
  ) {
    REPLY_KEYS.forEach { (label, bytes) ->
      FilledTonalButton(
          onClick = { onSend(bytes) },
          contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
          modifier = Modifier.height(36.dp),
      ) {
        Text(label, fontFamily = FontFamily.Monospace, style = MaterialTheme.typography.labelLarge)
      }
    }
  }
}

/**
 * 送るのは「文字 + 改行」。番号を選ばせるプロンプトは改行まで来て初めて確定する。
 *
 * Esc は改行を付けない。付けると、Esc で閉じた直後の画面に空行が入る。
 */
private val REPLY_KEYS: List<Pair<String, ByteArray>> =
    listOf(
        "1" to "1\r".toByteArray(Charsets.US_ASCII),
        "2" to "2\r".toByteArray(Charsets.US_ASCII),
        "3" to "3\r".toByteArray(Charsets.US_ASCII),
        "y" to "y\r".toByteArray(Charsets.US_ASCII),
        "n" to "n\r".toByteArray(Charsets.US_ASCII),
        "Enter" to "\r".toByteArray(Charsets.US_ASCII),
        "Esc" to byteArrayOf(0x1B),
    )
