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
- **ホスト鍵の検証（TOFU）** — 初回の鍵を記録し、**変わったら接続を拒否する**
- **資格情報の暗号化保存** — Android Keystore の鍵で `EncryptedFile`
- **アプリロック** — 生体認証（任意）
- **コマンド履歴**
- **バックグラウンド接続** — フォアグラウンドサービスで画面OFF中も維持
- **表示のカスタマイズ** — フォントサイズ、配色

## 表示言語

UI の大半は日本語である。`strings.xml` の既定は英語で日本語訳もあるが、
20 画面・約 300 の文字列が Kotlin に直接日本語で書かれており、リソース機構を
経由していない（設定、鍵管理、ヘルプ、フォアグラウンドサービスの通知など）。

ターミナルの表示内容は接続先が出すものなので影響を受けない。日本語なのは
アプリ自身の枠の部分である。リソース化の貢献を歓迎する。

## 動作条件

- Android 7.0 (API 24) 以上
- JetBrains Mono を同梱しているため、端末側のフォント設定に依存しない

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

## 関連プロジェクト

- [tvremocon](https://github.com/mirute02/tvremocon) — Tapo の赤外線ハブ経由で
  テレビを操作する Android ウィジェット
