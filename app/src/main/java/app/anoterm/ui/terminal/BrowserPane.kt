package app.anoterm.ui.terminal

import android.annotation.SuppressLint
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.SwapVert
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import app.anoterm.R
import app.anoterm.ssh.LocalForward
import app.anoterm.ssh.SshChannel
import app.anoterm.util.Logger

/**
 * 端末の隣で、向こう側のページを見るための面。
 *
 * 開発サーバーはたいてい `localhost` にしか口を開けない。それは正しい設定なので、
 * 緩めさせるのではなく SSH の中を通す。転送の出口はこちらの 127.0.0.1 で、外からは見えない。
 *
 * https だけは転送しない。転送すると WebView から見た宛先は 127.0.0.1 になり、
 * 証明書の名前が合わなくなって必ず警告になる。素直に端末から直接開く。
 */
@Composable
fun BrowserPane(
    channel: SshChannel,
    url: String,
    vertical: Boolean,
    onToggleOrientation: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
  var forward by remember(channel, url) { mutableStateOf<LocalForward?>(null) }
  var loadUrl by remember(channel, url) { mutableStateOf<String?>(null) }
  var failed by remember(channel, url) { mutableStateOf(false) }
  var webView by remember { mutableStateOf<WebView?>(null) }

  LaunchedEffect(channel, url) {
    val target = ForwardTarget.of(url)
    if (target == null) {
      // https や、そもそも host が読めないもの。端末から直接開く。
      loadUrl = url
      return@LaunchedEffect
    }
    try {
      val f = channel.openLocalForward(target.host, target.port)
      forward = f
      loadUrl = target.rewrite(f.localPort)
    } catch (t: Throwable) {
      Logger.w("BrowserPane", "could not forward $url", t)
      failed = true
    }
  }

  // 面を閉じたら穴も閉じる。開けっ放しにすると、見ていないページのために
  // 接続の中に口が残り続ける。
  //
  // 値をここで捕まえるのが要点。onDispose の中で `forward` を読むと、破棄の時点の値、
  // つまり「今しがた開いたばかりの穴」を閉じることになる (null から差し替わった瞬間に
  // 古い後始末が走るため)。
  DisposableEffect(forward) {
    val opened = forward
    onDispose { opened?.close() }
  }

  // 読み込みは (WebView, 宛先) の組ごとに 1 回だけ。あとはページ側の遷移に任せる。
  LaunchedEffect(webView, loadUrl) {
    val v = webView ?: return@LaunchedEffect
    val u = loadUrl ?: return@LaunchedEffect
    v.loadUrl(u)
  }

  Column(modifier = modifier.fillMaxSize().background(MaterialTheme.colorScheme.surface)) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(4.dp),
    ) {
      Text(
          text = url,
          modifier = Modifier.weight(1f),
          style = MaterialTheme.typography.bodySmall,
          fontFamily = FontFamily.Monospace,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis,
      )
      IconButton(onClick = { webView?.reload() }) {
        Icon(Icons.Filled.Refresh, contentDescription = stringResource(R.string.browser_reload))
      }
      IconButton(onClick = onToggleOrientation) {
        Icon(
            if (vertical) Icons.Filled.SwapHoriz else Icons.Filled.SwapVert,
            contentDescription = stringResource(R.string.browser_flip),
        )
      }
      IconButton(onClick = onClose) {
        Icon(Icons.Filled.Close, contentDescription = stringResource(R.string.browser_close))
      }
    }

    Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
      when {
        failed ->
            Text(
                text = stringResource(R.string.browser_forward_failed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.align(Alignment.Center).padding(24.dp),
            )
        loadUrl == null ->
            CircularProgressIndicator(modifier = Modifier.align(Alignment.Center).size(32.dp))
        else ->
            AndroidView(
                factory = { ctx ->
                  WebView(ctx).also { v ->
                    // 開発サーバーの中身はまず JavaScript で動く。切ると白い画面しか出ない。
                    // 見せる先は SSH の向こうの自分のサーバーだけなので、ここは開けてよい。
                    @SuppressLint("SetJavaScriptEnabled")
                    v.settings.javaScriptEnabled = true
                    v.settings.domStorageEnabled = true
                    // 中のリンクを踏んでも外のブラウザに飛ばさない。転送の穴は
                    // この WebView のためだけに開いているので、外へ出ると繋がらない。
                    v.webViewClient = WebViewClient()
                    webView = v
                  }
                },
                // ここで読み込ませない。`v.url` はリダイレクト後の住所になるので、
                // それと比べて読み直すと、飛ばされるページで永久に往復する。
                update = {},
                onRelease = { v ->
                  webView = null
                  v.destroy()
                },
                modifier = Modifier.fillMaxSize(),
            )
      }
    }
  }
}

/**
 * 転送して見るべき宛先。転送しないもの（https など）には null を返す。
 */
internal data class ForwardTarget(val host: String, val port: Int, private val suffix: String) {

  /** こちらの [localPort] を指す URL に書き換える。パスとクエリはそのまま残す。 */
  fun rewrite(localPort: Int): String = "http://127.0.0.1:$localPort$suffix"

  companion object {
    fun of(url: String): ForwardTarget? {
      if (!url.startsWith("http://")) return null
      val rest = url.removePrefix("http://")
      val authority = rest.substringBefore('/')
      if (authority.isEmpty()) return null
      val suffix = rest.removePrefix(authority).ifEmpty { "/" }
      val host = authority.substringBeforeLast(':', authority)
      // `:` があるのに数字でないなら、宛先を推測しない。`http://host:abc` を 80 番と
      // みなすと、頼んでいない所へ穴を開けることになる。
      val port =
          if (authority.contains(':')) authority.substringAfterLast(':').toIntOrNull() ?: return null
          else 80
      if (host.isEmpty() || port !in 1..65535) return null
      return ForwardTarget(host, port, suffix)
    }
  }
}
