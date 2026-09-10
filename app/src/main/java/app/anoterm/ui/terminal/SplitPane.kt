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
import androidx.compose.runtime.LaunchedEffect
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
    /**
     * true の間、1 つ目の面の大きさを据え置き、狭くなったぶんは 2 つ目に負わせる。
     *
     * キーボードを出している間に使う。打っている時に見たいのは端末なので、そこの行数が
     * 変わらないほうが素直で、リサイズも 1 回減る。ただし 2 つ目を潰しきりはしない
     * ([AppPrefs.MAX_SPLIT_RATIO] で頭を打つ)。据え置けるのは残りの高さが許す範囲まで。
     *
     * 左右に並べているときは効かない。キーボードは窓の下端を横いっぱいに取るので、
     * 隣り合った 2 面はどちらも同じだけ低くなる。片方だけ背を高いままにする置き方が無い。
     */
    freezeFirst: Boolean = false,
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

    // 据え置きが始まる前の広さを覚えておく。キーボードが出ている間は測り直さない。
    var relaxedSpan by remember { mutableStateOf(span) }
    LaunchedEffect(freezeFirst, span) { if (!freezeFirst) relaxedSpan = span }

    // 1 つ目の面が前と同じ大きさになる配分を逆算する。狭すぎて無理なときは
    // 上限で頭打ちになり、そこまでは近づく。
    val applied =
        if (freezeFirst && vertical && span > 0f && relaxedSpan > 0f) {
          (relaxedSpan * live / span).coerceIn(AppPrefs.MIN_SPLIT_RATIO, AppPrefs.MAX_SPLIT_RATIO)
        } else {
          live
        }

    val handle: @Composable () -> Unit = {
      Box(
          modifier =
              Modifier.then(
                      if (vertical) Modifier.fillMaxWidth().height(HANDLE_THICKNESS)
                      else Modifier.fillMaxHeight().width(HANDLE_THICKNESS),
                  )
                  .background(MaterialTheme.colorScheme.surfaceVariant)
                  .pointerInput(vertical, span, relaxedSpan, freezeFirst) {
                    detectDragGestures(
                        onDragEnd = { onRatioSettled(live) },
                        onDragCancel = { onRatioSettled(live) },
                    ) { change, drag ->
                      change.consume()
                      // 据え置き中、面の高さは relaxedSpan * live で決まる。指の動きと
                      // 仕切りの動きを一致させるには、そちらで割らないと倍率がずれる。
                      val basis = if (freezeFirst && vertical) relaxedSpan else span
                      if (basis <= 0f) return@detectDragGestures
                      val delta = if (vertical) drag.y else drag.x
                      live =
                          (live + delta / basis)
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
        Box(modifier = Modifier.weight(applied).fillMaxWidth()) { first() }
        handle()
        Box(modifier = Modifier.weight(1f - applied).fillMaxWidth()) { second() }
      }
    } else {
      Row(modifier = Modifier.fillMaxSize()) {
        Box(modifier = Modifier.weight(applied).fillMaxHeight()) { first() }
        handle()
        Box(modifier = Modifier.weight(1f - applied).fillMaxHeight()) { second() }
      }
    }
  }
}

/** 指で掴める太さ。細いと掴めず、太いと画面を食う。 */
private val HANDLE_THICKNESS = 16.dp
