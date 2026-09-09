# AnoTerm

**日本語** | [English](README.en.md)

Android 向けの SSH クライアント / ターミナルエミュレータ。Kotlin + Jetpack Compose。

日本語・中国語を含む**全角文字を正しい幅で描画する**ことを主眼にしている。Android の
ターミナルアプリで CJK を扱うと、カーソル位置がずれて表示が崩れることが多い。
文字幅の計算と UTF-8 のデコードを自前で実装しているのはそのため。

## 機能

- **ターミナルエミュレーション** — `TerminalEmulator.kt` による実装
- **全角・結合文字の正しい描画** — `CharWidth.kt` が East Asian Width を解決し、
  `Utf8Decoder.kt` がサロゲートペアと不完全なバイト列を扱う
- **接続先の管理** — ホスト・ポート・ユーザー名を保存（Room）
- **認証** — パスワードと秘密鍵の両方。パスフレーズ付き鍵にも対応
- **公開鍵の登録（`ssh-copy-id` 相当）** — パスワードで繋がっている接続を使い、
  端末画面の鍵アイコンから公開鍵を接続先の `~/.ssh/authorized_keys` に追記する。
  Android だけで鍵認証に移れる。二重登録はせず、既存の鍵も壊さない
- **tmux の自動 attach** — ホスト設定で tmux セッション名を入れておくと、接続後に
  `tmux new -A -s <名前>` を送る（`-A` は無ければ作成）。切断されても作業が残る
- **tmux の切り替えバー** — 接続タブのバーの下に、サーバー側の tmux ウィンドウが
  並ぶ。**1 タップで背面のウィンドウに移れる**（`prefix n` を連打しなくてよい）。
  セッションが複数あれば左のメニューでアタッチ先も変えられる。
  一覧は端末に打ち込むのではなく別の SSH セッションで読むので、作業中の画面に
  出力が混ざらない。前面にいる間だけ 10 秒ごとに引き直す
- **tmux ダッシュボード** — 分割・ペイン移動・コピーモード・名前変更などをボタンで。
  **prefix キーは一切送らない**。`tmux` コマンドを別セッションで実行するので、
  前面のアプリが Ctrl-B を横取りしていても効き、prefix を変更していても関係ない
- **ホスト鍵の検証（TOFU）** — 初回の鍵を記録し、**変わったら接続を拒否する**。
  初回接続時には記録した指紋を表示するので、サーバー側の `ssh-keygen -lf` と
  突き合わせて確認できる
- **資格情報の暗号化保存** — Android Keystore の鍵で `EncryptedFile`
- **アプリロック** — 生体認証（任意）
- **コマンド履歴**
- **バックグラウンド接続** — フォアグラウンドサービスで画面OFF中も維持
- **表示のカスタマイズ** — フォントサイズ、配色

## 表示言語

英語と日本語。既定は端末の設定に従い、設定画面で切り替えられる（アプリを
再起動する必要はない）。

文言は全て `strings.xml` にある。Kotlin に直接書かれた文字列は残っていない。
`tools/check-strings.py` が CI で、キーの重複・片方の言語にしかないキー・
書式引数の不一致・どこからも参照されていないキーを検査する。

ターミナルの表示内容は接続先が出すものなので、この設定の影響を受けない。

## 動作条件

- Android 7.0 (API 24) 以上
- JetBrains Mono を同梱しているため、端末側のフォント設定に依存しない

## インストール

### APK を入れる（推奨）

[Releases](https://github.com/mirute02/wanoterm/releases) から `app-release.apk` を
Android 端末のブラウザでダウンロードし、通知またはファイルアプリからタップする。
PC も adb も要らない。

初回は「提供元不明のアプリ」の許可を求められる。Android 8.0 以降はアプリ単位の
許可なので、ブラウザやファイルアプリに対して一度許可すればよい。

Play ストア経由ではないため、Play Protect が確認画面を出すことがある。
署名は自己署名で、証明書のフィンガープリントは Releases に記載している。

### 自分でビルドする

```bash
git clone https://github.com/mirute02/wanoterm.git
cd wanoterm
./gradlew assembleDebug
```

`app/build/outputs/apk/debug/app-debug.apk` ができる。
署名鍵を持たない環境ではリリース版は署名なしになるため、配布用には使えない。

## ビルド

```bash
./gradlew assembleDebug
```

`namespace` / `applicationId` は `app.anoterm`（デバッグビルドは `.debug` サフィックス）。

### テスト

```bash
./gradlew test
```

ターミナルエミュレーション、文字幅計算、UTF-8 デコード、IME の未確定文字列を
対象にした単体テストが 43 件ある。CI で毎回実行している。

### リリースビルド

署名鍵はリポジトリに含めない。`gradle.properties`（または CI の Secrets）に置く:

```properties
WANOTERM_STORE_FILE=/absolute/path/to/release.jks
WANOTERM_STORE_PASSWORD=...
WANOTERM_KEY_ALIAS=...
WANOTERM_KEY_PASSWORD=...
```

証明書の DN は APK から誰でも読めるので、本名ではなくプロジェクト名を入れること。
一度配布した鍵は変更できない。詳細は [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)。

`app/build.gradle.kts` の `signingConfigs` は `project.findProperty()` 経由で読むため、
プロパティが無い環境では署名なしビルドになる。クローン直後でもビルドが通る。

詳細は [docs/RELEASE_SIGNING.md](docs/RELEASE_SIGNING.md)。

## セキュリティ

SSH クライアントは接続先の資格情報を預かり、その鍵で任意のコマンドを実行できる
端末になる。[docs/security.md](docs/security.md) に、暗号化して保存していること、
ホスト鍵が変わったら**警告して続行するのではなく拒否する**こと、そして端末の
ロックを破られた場合は資格情報が取り出されうるという限界を書いてある。

プライバシーポリシーは [docs/PRIVACY_POLICY.md](docs/PRIVACY_POLICY.md)。

## ライセンス

MIT — [LICENSE](LICENSE) を参照。依存のライセンスは（各モジュールの POM から
読み取って）[THIRD_PARTY_LICENSES.md](THIRD_PARTY_LICENSES.md) に記載。

SSH は SSH Communications Security の登録商標。本プロジェクトは独立した
クライアントであり、同社とは無関係。
