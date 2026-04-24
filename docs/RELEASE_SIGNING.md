# wanoterm リリース署名・配布フロー

## 1. Upload key の生成（初回のみ）

```bash
keytool -genkey -v \
  -keystore ~/.android/wanoterm-upload.jks \
  -alias wanoterm-upload \
  -keyalg RSA -keysize 2048 -validity 10000
```

プロンプトで組織情報・パスワード設定。**このキーストアファイルを絶対に git にコミットしない**。

## 2. gradle.properties（git 管理外）

ユーザホームの `~/.gradle/gradle.properties` に以下を追加:

```
WANOTERM_STORE_FILE=~/.android/wanoterm-upload.jks
WANOTERM_STORE_PASSWORD=<keystore password>
WANOTERM_KEY_ALIAS=wanoterm-upload
WANOTERM_KEY_PASSWORD=<key password>
```

`app/build.gradle.kts` は既に以下のように `project.findProperty` で参照している:

```kotlin
signingConfigs {
  create("release") {
    val keystorePath = (project.findProperty("WANOTERM_STORE_FILE") as String?)
    if (keystorePath != null) {
      storeFile = file(keystorePath)
      storePassword = project.findProperty("WANOTERM_STORE_PASSWORD") as String?
      keyAlias = project.findProperty("WANOTERM_KEY_ALIAS") as String?
      keyPassword = project.findProperty("WANOTERM_KEY_PASSWORD") as String?
    }
  }
}
```

## 3. Release AAB のビルド

```bash
./gradlew :app:bundleRelease
# 出力: app/build/outputs/bundle/release/app-release.aab
```

## 4. Play Console への upload（初回のみ）

1. Play Console → wanoterm アプリを新規作成
2. **Setup → App integrity → App Signing** で「Play App Signing を使用」を有効化
3. Upload key（上記手順 1 で作成した key）の公開証明書を Play に登録
4. Play が App Signing key を生成して管理（wanoterm はこの key を知らない）
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
