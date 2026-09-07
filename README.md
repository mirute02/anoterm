# AnoTerm

Android 向けの SSH クライアント / ターミナルエミュレータ。Kotlin + Jetpack Compose。

## 機能

- **ターミナルエミュレーション** — `TerminalEmulator.kt` による自前実装
- **UTF-8 / 全角対応** — `Utf8Decoder.kt` と `CharWidth.kt` で東アジア文字幅を正しく扱う
- **ホスト管理** — 接続先の保存（`HostRepository.kt`）
- **認証方式** — パスワード / 秘密鍵の両対応（`AuthMethod`）
- **秘密情報の保護** — `data/secrets/SecretStore.kt` に隔離。パスフレーズ等は端末内に保持
- **コマンド履歴** — `CommandHistory.kt`
- **セッション制御** — `TerminalSessionController.kt`
- **表示カスタマイズ** — `TerminalStyle.kt` / テーマ
- **ロケール切替** — `LocaleManager.kt`

## ビルド

```bash
./gradlew assembleDebug
```

`namespace` / `applicationId`: `app.anoterm`（デバッグビルドは `.debug` サフィックス）

## リリースビルドについて

署名鍵はリポジトリに含めない。`gradle.properties`（またはCI Secrets）で指定する:

```properties
WANOTERM_STORE_FILE=/path/to/release.jks
WANOTERM_STORE_PASSWORD=...
WANOTERM_KEY_ALIAS=...
WANOTERM_KEY_PASSWORD=...
```

## 注意

SSH の秘密鍵とパスワードを扱うアプリのため、`SecretStore` 周辺を変更する際は
鍵がログやクラッシュレポートに載らないことを必ず確認すること。
