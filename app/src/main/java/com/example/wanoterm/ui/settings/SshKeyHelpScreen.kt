package com.example.wanoterm.ui.settings

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.wanoterm.WanotermApp
import com.example.wanoterm.data.db.SshKeyEntity

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyHelpScreen(onBack: () -> Unit) {
  val app = remember { WanotermApp.get() }
  val keys by app.database
      .sshKeyDao()
      .observeAll()
      .collectAsStateWithLifecycle(initialValue = emptyList())
  // 複数の鍵があれば先頭（最新）を初期選択。UI でチップ切替できる。
  var selectedKeyId by remember { mutableStateOf<Long?>(null) }
  val activeKey: SshKeyEntity? =
      keys.firstOrNull { it.id == selectedKeyId } ?: keys.firstOrNull()

  Scaffold(
      topBar = {
        TopAppBar(
            title = { Text("SSH 鍵の使い方") },
            navigationIcon = {
              IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
              }
            },
        )
      },
  ) { inner ->
    Column(
        modifier =
            Modifier.fillMaxSize()
                .padding(inner)
                .padding(horizontal = 20.dp, vertical = 16.dp)
                .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
      Section("そもそも SSH 鍵とは") {
        Text(
            "SSH 鍵は「秘密鍵」と「公開鍵」のペアで、パスワードの代わりに本人確認をするための仕組みです。"
                + "秘密鍵は手元の端末にだけ置き、公開鍵だけをサーバに登録します。"
                + "パスワードと違って総当たり攻撃に強く、より安全です。",
        )
      }

      Section("wanoterm で鍵を作る（推奨）") {
        Text("設定 → 「SSH 鍵を作成」から以下の手順で作れます。")
        Bullet("1. 鍵種類を選ぶ（Ed25519 推奨。RSA 4096 は互換性優先の場合）")
        Bullet("2. コメント（メールアドレス等）を入力")
        Bullet("3. passphrase は空でも可。設定すると鍵盗難時の保険になる")
        Bullet("4. 「作成」→ 秘密鍵は端末内に暗号化保存、公開鍵がコピーできる画面に")
        Bullet("5. その公開鍵をサーバの ~/.ssh/authorized_keys に追記する（詳細は下）")
      }

      Section("他のツールで作って wanoterm に読み込む") {
        Text("既に手元に秘密鍵がある、もしくは別ツールで作りたい場合。")

        SubHeader("macOS / Linux")
        Code("ssh-keygen -t ed25519 -C \"your_email@example.com\"")
        Text("~/.ssh/id_ed25519 が秘密鍵。これを wanoterm の「鍵をインポート」で読み込みます。")

        SubHeader("Windows")
        Text("PowerShell で:")
        Code("ssh-keygen -t ed25519 -C \"your_email@example.com\"")
        Text("または PuTTYgen で生成し、OpenSSH 形式で export してインポート。")
      }

      Section("公開鍵をサーバに登録する") {
        Callout(
            "公開鍵を登録するには、まず「何らかの方法でサーバに入れる状態」が前提です。"
                + "典型的には次のいずれか:\n"
                + "• パスワード認証でひとまず SSH ログインできる（既存サーバ・共有レンタルサーバ等）\n"
                + "• クラウドのコンソール（AWS EC2 Connect, GCP シリアル, さくら VPS コンパネ等）\n"
                + "• 物理/仮想コンソールに直接アクセスできる（自宅サーバ・VM）",
        )

        SubHeader("wanoterm での最短手順")
        Callout(
            "⚠ 前提: このフローはホストが「パスワード認証で繋がる」状態でないと成立しません。"
                + "秘密鍵認証のホスト (「key」バッジ) を選ぶと、そもそも接続できないので登録に進めません。"
                + "\n\n既に秘密鍵認証にしてしまっている場合は、先にホスト編集で「パスワード」に戻してください。",
        )
        Bullet("1. ホスト画面の + から対象サーバを **パスワード認証で** 登録")
        Bullet("   （まだホストが無い、または「key」バッジの場合は先にこの状態に）")
        Bullet("2. 設定 → SSH 鍵一覧 を開く")
        Bullet("3. 登録したい鍵の ☁↑（サーバに登録）をタップ")
        Bullet("4. 「pass」バッジのホストを選ぶ → ターミナルが開く")
        Bullet("5. パスワードを入力してログイン")
        Bullet("6. 画面を長押し → ペースト → Enter （authorized_keys に追記される）")
        Bullet("7. ホスト画面に戻り 🖉 → 認証方法を「秘密鍵」→ 保存済みから同じ鍵を選んで保存")
        Bullet("8. 次回以降はパスワード無しで鍵認証で入れる")
        Text(
            "※ サーバ側で /etc/ssh/sshd_config の PubkeyAuthentication が yes か要確認。"
                + "デフォルトで有効なディストリが多いのでまずは試してみる。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        if (keys.isNotEmpty()) {
          SubHeader("使う公開鍵を選ぶ")
          if (keys.size == 1) {
            Text(
                "「${keys[0].label}」の公開鍵をコマンドに埋め込みました。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
          } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
              keys.forEach { k ->
                FilterChip(
                    selected = (activeKey?.id == k.id),
                    onClick = { selectedKeyId = k.id },
                    label = { Text(k.label) },
                )
              }
            }
          }
        } else {
          Callout(
              "保存済みの鍵がまだありません。下の手順のコマンドでは "
                  + "\"ssh-ed25519 AAAA... your_email@example.com\" をあなたの公開鍵に"
                  + "置き換えてください。設定 → 「SSH 鍵を作成」から鍵を作れば、ここに"
                  + "実際の公開鍵を差し込んだコマンドが表示されます。",
          )
        }

        SubHeader("手動で登録する場合（参考）")
        Text("上の最短手順を使わず直接打ち込む場合、サーバにログイン後これを貼り付け:")
        val pubKey =
            activeKey?.publicSsh?.trim() ?: "ssh-ed25519 AAAA... your_email@example.com"
        Code(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh && "
                + "echo '${pubKey}' >> ~/.ssh/authorized_keys && "
                + "chmod 600 ~/.ssh/authorized_keys",
        )
        Text(
            "※ クオート内に ' (single quote) が含まれる公開鍵は通常ありません。"
                + "万一エラーが出た場合は echo の部分を $ を含まない別の方法で追記してください。",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        SubHeader("AWS EC2 / GCP / Azure")
        Text("インスタンス作成時の「キーペア追加」欄に公開鍵をペースト、")
        Text("もしくはコンソールから後付け可能。")

        SubHeader("GitHub / GitLab")
        Text("Settings → SSH and GPG keys → New SSH key に公開鍵をペースト。")

        SubHeader("ssh-copy-id（上級者向け）")
        Text(
            "PC から `ssh-copy-id user@host` で自動登録できるが、Android から直接使うのは"
                + "手順が多いので wanoterm 画面で公開鍵コピー → SSH 接続 → echo で追記が現実的。",
        )
      }

      Section("passphrase について") {
        Text(
            "passphrase は秘密鍵ファイル自体を暗号化するパスワード。"
                + "端末を盗まれても passphrase を知らない人は鍵を使えないため、"
                + "モバイル端末では設定を推奨。"
                + "wanoterm では Android の biometric lock と組み合わせることで、"
                + "毎回 passphrase を打つ必要はない運用も可能。",
        )
      }

      Section("鍵を失ったら") {
        Text(
            "秘密鍵は復旧不能です。端末紛失・初期化した場合はサーバ側で該当公開鍵を"
                + "~/.ssh/authorized_keys から削除し、新しい鍵を作り直して登録し直してください。",
        )
        Text(
            "このため、重要なサーバではバックアップとして別経路（パスワード認証・"
                + "別の鍵 2 つ目・コンソール接続）を残しておくのが安全です。",
        )
      }

      Section("鍵の形式について（詳細）") {
        Bullet("**Ed25519**: 最新・短い・十分安全。対応サーバが多い。推奨。")
        Bullet("**RSA 4096**: 古いサーバでも確実に通る。鍵が長い。")
        Bullet("**ECDSA**: あまり使わない。特定の会社ポリシーで必要な場合のみ。")
        Bullet("**DSA**: 非推奨・使わない")
        Bullet("**OpenSSH 形式 / PEM 形式**: wanoterm は両方読める。生成時は OpenSSH 形式がデフォルト。")
      }

      HorizontalDivider()
      Text(
          "最終更新: 2026-04-24 / 疑問点があれば 🐛 デバッグ報告 からメモを残してください",
          style = MaterialTheme.typography.labelSmall,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    }
  }
}

@Composable
private fun Section(title: String, content: @Composable () -> Unit) {
  Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
    Text(title, style = MaterialTheme.typography.titleMedium)
    content()
    HorizontalDivider(thickness = 0.5.dp)
  }
}

@Composable
private fun SubHeader(text: String) {
  Text(text, style = MaterialTheme.typography.titleSmall)
}

@Composable
private fun Bullet(text: String) {
  Text("• $text", style = MaterialTheme.typography.bodyMedium)
}

/** セル単位の強調メッセージ。ユーザが読み飛ばしやすい前提条件を箱で囲う。 */
@Composable
private fun Callout(text: String) {
  Surface(
      shape = RoundedCornerShape(8.dp),
      color = MaterialTheme.colorScheme.secondaryContainer,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSecondaryContainer,
        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
    )
  }
}

@Composable
private fun Code(text: String) {
  val ctx = LocalContext.current
  Surface(
      shape = RoundedCornerShape(6.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.fillMaxWidth(),
  ) {
    Row(verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
      Text(
          text,
          style =
              MaterialTheme.typography.bodySmall.copy(
                  fontFamily = FontFamily.Monospace,
              ),
          color = MaterialTheme.colorScheme.primary,
          modifier =
              Modifier.weight(1f).padding(start = 10.dp, top = 8.dp, bottom = 8.dp, end = 4.dp),
      )
      IconButton(
          onClick = {
            val cm = ctx.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("shell snippet", text))
            Toast.makeText(ctx, "コピーしました", Toast.LENGTH_SHORT).show()
          },
      ) {
        Icon(
            Icons.Filled.ContentCopy,
            contentDescription = "コピー",
            modifier = Modifier.padding(2.dp),
        )
      }
    }
  }
}
