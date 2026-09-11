# AnoTerm リリース署名・配布フロー

## 署名鍵について

**一度でも配布した鍵は変更できない。** 鍵が変わったアプリは Android から別アプリとして
扱われ、上書き更新ができなくなる。作り直せるのは、まだ誰にも配っていない間だけ。

証明書の DN は **APK を入手した誰でも読める**。

```bash
apksigner verify --print-certs app-release.apk
# Signer #1 certificate DN: CN=..., OU=..., O=...
```

ここに本名を入れると、公開した瞬間から取り消せない。プロジェクト名や組織名にすること。

## 1. 鍵の生成（初回のみ）

```bash
keytool -genkeypair -v \
  -keystore ~/keys/anoterm-release-v2.jks \
  -alias anoterm \
  -keyalg RSA -keysize 4096 \
  -validity 10950 \
  -dname "CN=AnoTerm, OU=AnoTerm, O=AnoTerm, L=Unknown, ST=Unknown, C=JP"
chmod 600 ~/keys/anoterm-release-v2.jks
```

- `-dname` を明示してプロンプトを避ける。対話で入れると本名を打ちがち
- 有効期限は配布予定期間より長く取る（30 年）
- **キーストアを git にコミットしない**。`.gitignore` に `*.jks` がある

## 2. gradle.properties（git 管理外）

`~/.gradle/gradle.properties` に置く。リポジトリ内の `gradle.properties` ではない。

```
ANOTERM_STORE_FILE=/home/<user>/keys/anoterm-release-v2.jks
ANOTERM_STORE_PASSWORD=<keystore password>
ANOTERM_KEY_ALIAS=anoterm
ANOTERM_KEY_PASSWORD=<key password>
```

`~` は展開されないので絶対パスで書く。

`app/build.gradle.kts` はこれらを `project.findProperty` 経由で読む。旧名の `WANOTERM_*` も読むので、既存の設定はそのままでも動く。プロパティが
無い環境では `signingConfigs` が素通りし、署名なしでビルドされる。CI やクローン
直後でもビルドが通るのはそのため。

## 3. ビルド

```bash
./gradlew assembleRelease
```

`--offline` は使えない。R8 の Compose マッピング生成が追加の依存を取りに行く。

出力: `app/build/outputs/apk/release/app-release.apk`

## 4. 検証

```bash
apksigner verify --verbose --print-certs app/build/outputs/apk/release/app-release.apk
```

確認すること:

- `Verifies` が出ている
- v2 と v3 が `true`（`build.gradle.kts` で v1/v2/v3 を明示している。
  v1 は minSdk 24 では不要なため AGP が省くことがある）
- **DN に本名が入っていない**
- 証明書 SHA-256 が `~/keys/anoterm-keystore-info-v2.txt` の記録と一致する

配布前に、実名やローカルパスが APK に混ざっていないことも見る:

```bash
strings app-release.apk | grep -iE "本名|/home/<user>|メールアドレス"
```

## 5. バックアップ

鍵を失うと、そのアプリは二度と更新できない。

- キーストアファイルとパスワードを**別々の場所**に保管する
- パスワードをリポジトリ、共有ストレージ、チャットに置かない
- 退役した鍵は `~/keys/retired/` に隔離し、配布には使わない

## 3. Release AAB のビルド

```bash
./gradlew :app:bundleRelease
# 出力: app/build/outputs/bundle/release/app-release.aab
```

## 4. Play Console への upload（初回のみ）

1. Play Console → AnoTerm アプリを新規作成
2. **Setup → App integrity → App Signing** で「Play App Signing を使用」を有効化
3. Upload key（上記手順 1 で作成した key）の公開証明書を Play に登録
4. Play が App Signing key を生成して管理（AnoTerm はこの key を知らない）
5. 以後のリリースは Upload key で署名した AAB を Play にアップロード → Play が App Signing key で再署名 → ユーザに配信

## 5. 継続リリース

```bash
./gradlew :app:bundleRelease
# Play Console でアップロード
```

## セキュリティ注意

- **Upload keystore は絶対に git に入れない**（`.gitignore` で `*.jks` `*.keystore` 除外済み）
- Upload keystore / password を紛失した場合、Play Console で upload key のリセット申請が必要（審査に数日）
- CI で自動化する場合は GitHub Actions の encrypted secrets に入れる

## Debuggable

`app/build.gradle.kts` の buildTypes.release は `isMinifyEnabled=true` で debuggable は明示指定なし = default の `false`。
buildTypes.debug は `applicationIdSuffix=".debug"` で配布版と区別される。
