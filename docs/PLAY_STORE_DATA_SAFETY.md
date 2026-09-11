# Play Store Data Safety 回答テンプレート

Google Play Console の「Data Safety」セクション向けの回答テンプレート。

## データ収集

| 項目 | 回答 |
|---|---|
| このアプリはユーザからデータを収集または共有しますか？ | **はい** |
| 収集されるデータはすべて暗号化されますか？ | **はい**（AES-256 GCM + Android Keystore） |
| ユーザが自分のデータ削除をリクエストできますか？ | **はい**（アプリアンインストールまたは「設定 → Known hosts → すべて削除」で完全削除） |

## 収集するデータ（端末内のみ保存）

| カテゴリ | データ | 収集目的 | 必須/任意 | 共有先 |
|---|---|---|---|---|
| App activity | **App interactions** | アプリ設定・タブ状態の維持 | 必須 | 共有しない |
| Personal info | **Other info** (SSH credentials) | SSH 接続機能の提供 | 必須 | 共有しない |

## 共有するデータ

**無し**。ユーザ入力データは端末外へ一切送信しません（SSH 接続先は除く。SSH 接続先はユーザが自発的に指定するサーバ）。

端末に出た URL をユーザが押した場合、そのページを表示する。`http://` は SSH 接続の中を
通す（待ち受けは端末内の 127.0.0.1 のみ）。`https://` はユーザの端末から直接そのサイトへ
接続する。いずれもユーザの操作によって起きるもので、アプリが保存しているデータを
送るものではない。

## セキュリティ対策

- Data is encrypted in transit: **YES**（TLS ではなく SSH プロトコル、ただしサーバとの通信は完全暗号化）
- Data is encrypted at rest: **YES**（端末内の秘密情報は AES-256 GCM 暗号化）
- Users can request that their data be deleted: **YES**（アンインストール or 個別削除）
- Follows Families Policy: **N/A**（子供向けカテゴリではない）
- Committed to security practices (MASA, etc.): **任意**（現段階は未取得）

## 説明文

> AnoTerm は SSH クライアントです。ユーザが入力した SSH 接続情報は端末内で AES-256 GCM で暗号化して保存され、ユーザが指定した SSH サーバへの接続以外の目的では使用されません。第三者との共有は一切ありません。
