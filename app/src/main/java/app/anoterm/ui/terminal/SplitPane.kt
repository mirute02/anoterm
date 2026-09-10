package app.anoterm.ui.terminal

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import app.anoterm.data.prefs.AppPrefs

/**
 * 2 つの面を並べ、仕切りを指で動かして配分を変えられる入れ物。
 *
 * 向きを選べるようにしてあるのは、端末とページで欲しい形が違うから。文字を読むには
 * 横幅が要るので上下に、ページの見え方を確かめるには縦が要るので左右に、と場面で変わる。
 * 折りたたみ端末を開けば左右のほうが自然になる。どちらが正解ということはない。
 *
 * [second] が null なら仕切りごと消えて、[first] が全部を使う。分割していない状態を
 * 別の枝として書かずに済ませるため。
 */
@Composable
fun SplitPane(
    vertical: Boolean,
    ratio: Float,
    onRatioSettled: (Float) -> Unit,
    modifier: Modifier = Modifier,
    second: (@Composable () -> Unit)? = null,
    first: @Composable () -> Unit,
) {
  if (second == null) {
    Box(modifier) { first() }
    return
  }
  BoxWithConstraints(modifier) {
    // ドラッグ中は自分で持つ。1 本の指の動きごとに設定へ書き戻すと、保存が追いつかない。
    var live by remember(ratio) { mutableStateOf(ratio) }
    val span = (if (vertical) constraints.maxHeight else constraints.maxWidth).toFloat()

    val handle: @Composable () -> Unit = {
      Box(
          modifier =
              Modifier.then(
                      if (vertical) Modifier.fillMaxWidth().height(HANDLE_THICKNESS)
                      else Modifier.fillMaxHeight().width(HANDLE_THICKNESS),
                  )
                  .background(MaterialTheme.colorScheme.surfaceVariant)
                  .pointerInput(vertical, span) {
                    detectDragGestures(
                        onDragEnd = { onRatioSettled(live) },
                        onDragCancel = { onRatioSettled(live) },
                    ) { change, drag ->
                      change.consume()
                      if (span <= 0f) return@detectDragGestures
                      val delta = if (vertical) drag.y else drag.x
                      live =
                          (live + delta / span)
                              .coerceIn(AppPrefs.MIN_SPLIT_RATIO, AppPrefs.MAX_SPLIT_RATIO)
                    }
                  },
          contentAlignment = Alignment.Center,
      ) {
        // つまみの絵。無地の帯だと、動かせる物だと分からない。
        Box(
            modifier =
                Modifier.clip(RoundedCornerShape(2.dp))
                    .background(MaterialTheme.colorScheme.onSurfaceVariant)
                    .then(if (vertical) Modifier.size(36.dp, 3.dp) else Modifier.size(3.dp, 36.dp)),
        )
      }
    }

    if (vertical) {
      Column(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(live).fillMaxWidth()) { first() }
        handle()
        Box(modifier = Modifier.weight(1f - live).fillMaxWidth()) { second() }
      }
    } else {
      Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(live).fillMaxHeight()) { first() }
        handle()
        Box(modifier = Modifier.weight(1f - live).fillMaxHeight()) { second() }
      }
    }
  }
}

/** 指で掴める太さ。細いと掴めず、太いと画面を食う。 */
private val HANDLE_THICKNESS = 16.dp
