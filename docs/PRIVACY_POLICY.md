# wanoterm プライバシーポリシー

最終更新: 2026-04-24

## 1. 収集するデータ

wanoterm は**一切の個人情報を端末外に送信しません**。

- **SSH 接続情報**（ホスト名、ユーザ名、パスワード、秘密鍵、パスフレーズ）: ユーザが入力した情報は端末内に保存され、暗号化されて SSH 接続以外の目的で使用されることはありません。
- **Known hosts（ホスト鍵指紋）**: サーバ偽装防止のために端末内で保存します。
- **アプリ設定**（テーマ、フォントサイズ、ロケール、改行方式など）: 端末内の SharedPreferences に保存されます。

## 2. データの保管

- **秘密情報**（パスワード、秘密鍵、パスフレーズ）は AES-256 GCM で暗号化されたファイルに格納され、鍵は Android Keystore に保管されます。
- **Known hosts** は端末内の Room データベースに格納されます。
- 上記データは Google クラウドバックアップ / Auto Backup 対象から除外されています（`android:allowBackup=false` + backup rules）。

## 3. ネットワーク通信

- wanoterm は SSH サーバ（ユーザが追加したホスト）に対してのみ TCP 接続を行います。
- 外部サーバ（Google Analytics, Firebase, クラッシュレポート SaaS など）への通信は**一切行いません**。
- HTTP（平文）通信は Network Security Config で禁止しています。

## 4. 権限

アプリが使用する Android 権限と用途:

- **INTERNET**: SSH サーバへの接続
- **ACCESS_NETWORK_STATE**: 接続可否の判定
- **USE_BIOMETRIC**: アプリロックで生体認証を使うため（任意機能）
- **VIBRATE**: BEL（0x07）受信時の振動通知（予定）
- **POST_NOTIFICATIONS**: SSH セッション実行中の常駐通知表示
- **FOREGROUND_SERVICE / FOREGROUND_SERVICE_DATA_SYNC**: SSH セッションを OS に kill されないようにするため

## 5. 広告・アナリティクス

wanoterm は広告 SDK / アナリティクス SDK を一切含みません。

## 6. 第三者提供

wanoterm は**第三者へのデータ提供を一切行いません**。SSH 接続先サーバはユーザ自身が指定するもので、wanoterm が管理・仲介するものではありません。

## 7. 子供の保護

13 歳未満のお子様によるアプリ使用を意図していません。

## 8. お問い合わせ

- GitHub Issues: https://github.com/mirute02/wanoterm/issues

## 9. 改定履歴

- 2026-04-24: 初版
