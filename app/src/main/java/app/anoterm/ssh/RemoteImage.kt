package app.anoterm.ssh

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import androidx.annotation.StringRes
import app.anoterm.R
import app.anoterm.terminal.PathScan
import app.anoterm.util.Logger

/** 画像を読めなかった理由。分岐に使う値と、人に見せる文字列は分けておく。 */
enum class RemoteImageFailure(@StringRes val message: Int) {
  NOT_FOUND(R.string.image_fail_not_found),
  TOO_BIG(R.string.image_fail_too_big),
  UNREADABLE(R.string.image_fail_unreadable),
  NOT_AN_IMAGE(R.string.image_fail_not_an_image),
}

sealed interface RemoteImageResult {
  data class Ok(val bitmap: Bitmap, val byteCount: Long) : RemoteImageResult

  data class Failed(val reason: RemoteImageFailure) : RemoteImageResult
}

/**
 * リモートに置かれた画像を、シェルを邪魔せずに読んで返す。
 *
 * Claude Code は画像を作ってもパスを書くだけで、端末に絵は流れてこない。インライン画像の
 * プロトコル (kitty / iTerm2) を実装しても、相手がそれを喋らない以上は意味がない。
 * こちらから取りに行くのが唯一まともに動く方法になる。
 *
 * 対話シェルには一切流さない。`SshChannel.exec` は別セッションを開くので、利用者の
 * 作業中のコマンドラインに `base64 ...` が混ざることがない。ただし別セッションは
 * 作業ディレクトリがホームなので、相対パスは扱わない ([PathScan.isImagePath] が弾く)。
 */
object RemoteImage {

  /**
   * 読み込む上限。base64 は 4/3 に膨らみ、それを String (UTF-16) で受けるので、
   * 実際にはこの 3 倍強のメモリが一時的に要る。スクリーンショットなら十分収まる大きさ。
   */
  const val MAX_BYTES = 5L * 1024 * 1024

  /** 長辺がこれを超える画像は間引いて読む。原寸で持つ意味が無いうえ OOM を招く。 */
  private const val MAX_DIMENSION = 2048

  suspend fun read(channel: SshChannel, path: String): RemoteImageResult =
      try {
        readOrThrow(channel, path)
      } catch (t: Throwable) {
        // 接続が切れかけている最中に押されるのは普通に起きる。ここで投げると
        // LaunchedEffect の中なのでアプリごと落ちる。理由を出して閉じるだけにする。
        Logger.w("RemoteImage", "read failed for $path", t)
        RemoteImageResult.Failed(RemoteImageFailure.UNREADABLE)
      }

  private suspend fun readOrThrow(channel: SshChannel, path: String): RemoteImageResult {
    val word = PathScan.shellWord(path)

    // 先に大きさを見る。数十 MB を base64 で引っ張ってから諦めるのでは遅すぎる。
    // `wc -c < f` はどの環境にもある。`stat` は GNU と BSD で書式が違う。
    val stat = channel.exec("test -f $word && wc -c < $word")
    if (!stat.isSuccess) return RemoteImageResult.Failed(RemoteImageFailure.NOT_FOUND)
    val size = stat.stdout.trim().toLongOrNull()
    if (size == null || size <= 0L) {
      return RemoteImageResult.Failed(RemoteImageFailure.UNREADABLE)
    }
    if (size > MAX_BYTES) return RemoteImageResult.Failed(RemoteImageFailure.TOO_BIG)

    // `-w0` は GNU coreutils だけの綴り。素の base64 を使い、改行はこちらで落とす。
    // 転送は回線任せなので、待ち時間は大きさから見積もる。
    val timeout = 15_000L + size / 10_000L
    val encoded = channel.exec("base64 -- $word", timeoutMs = timeout)
    if (!encoded.isSuccess) return RemoteImageResult.Failed(RemoteImageFailure.UNREADABLE)

    val raw =
        try {
          Base64.decode(encoded.stdout.filterNot { it.isWhitespace() }, Base64.DEFAULT)
        } catch (t: IllegalArgumentException) {
          Logger.w("RemoteImage", "base64 decode failed", t)
          return RemoteImageResult.Failed(RemoteImageFailure.UNREADABLE)
        }

    val bitmap = decodeSampled(raw) ?: return RemoteImageResult.Failed(RemoteImageFailure.NOT_AN_IMAGE)
    return RemoteImageResult.Ok(bitmap, size)
  }

  /**
   * 画面より大きい画像を原寸で展開しない。4000x3000 の PNG は 48MB のビットマップになり、
   * 見る前に落ちる。
   */
  private fun decodeSampled(raw: ByteArray): Bitmap? {
    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
    BitmapFactory.decodeByteArray(raw, 0, raw.size, bounds)
    if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
    val opts =
        BitmapFactory.Options().apply {
          inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, MAX_DIMENSION)
        }
    return BitmapFactory.decodeByteArray(raw, 0, raw.size, opts)
  }

  /**
   * 長辺が [limit] 以下になる最小の 2 の冪。BitmapFactory は 2 の冪しか受け付けない。
   *
   * よくある `while (longest / 2 >= limit)` の書き方は「[limit] を下回らない最小」を選ぶので、
   * 4000px の画像が原寸のまま通る。ここで欲しいのは逆で、上限を必ず下回らせるほう。
   * 4000x3000 を原寸で展開すると 48MB になり、見る前に落ちる。
   */
  internal fun sampleSizeFor(width: Int, height: Int, limit: Int): Int {
    var sample = 1
    var longest = maxOf(width, height)
    while (longest > limit) {
      longest /= 2
      sample *= 2
    }
    return sample
  }
}
