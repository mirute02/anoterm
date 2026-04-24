package com.example.wanoterm.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SshKeyHelpScreen(onBack: () -> Unit) {
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

        SubHeader("Android（wanoterm 単体で作れない古い端末等）")
        Text("Termux アプリをインストールし、Termux 内で:")
        Code("pkg install openssh\nssh-keygen -t ed25519")
        Text("生成後、共有メニューか Files アプリで wanoterm の「鍵をインポート」へ。")
      }

      Section("公開鍵をサーバに登録する") {
        SubHeader("一般的な Linux サーバ")
        Text("wanoterm で表示した公開鍵をコピーし、サーバにログインして以下を実行:")
        Code(
            "mkdir -p ~/.ssh && chmod 700 ~/.ssh\n"
                + "echo \"ssh-ed25519 AAAA... your_email@example.com\" >> ~/.ssh/authorized_keys\n"
                + "chmod 600 ~/.ssh/authorized_keys",
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

@Composable
private fun Code(text: String) {
  Text(
      text,
      style =
          MaterialTheme.typography.bodyMedium.copy(
              fontFamily = androidx.compose.ui.text.font.FontFamily.Monospace,
          ),
      color = MaterialTheme.colorScheme.primary,
      modifier =
          Modifier.padding(vertical = 4.dp, horizontal = 4.dp),
  )
}
