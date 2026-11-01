# 署名とリリース

## GitHub Actions Secrets

Repository Settings → Secrets and variables → Actions に次の2項目を登録します。

| 名前 | 値 |
| --- | --- |
| `ANDROID_KEYSTORE_BASE64` | `release.jks` 全体を base64 にした1行の文字列 |
| `ANDROID_KEYSTORE_PASSWORD` | キーストア生成時のパスワード |

鍵の別名は `podcast-release` に固定し、秘密鍵にはキーストアと同じパスワードを使います。追加の Secret や Variable は不要です。

値をチャットや Git に貼り付けないでください。鍵とパスワードは暗号化してバックアップし、同じアプリの更新には同じ鍵を使います。

### 署名情報を作成する場合

JDK の `keytool` が必要です。既存の鍵がある場合は再生成せず、その鍵を使います。

```bash
python3 scripts/create-signing-secrets.py --output "$HOME/.local/share/podcast-android/signing"
```

出力先には `release.jks` と、上記2項目と同じ名前の秘密値ファイルが作られます。パスワードは暗号学的乱数です。既存ファイルは上書きしません。

GitHub CLI から値を表示せず登録することもできます。Repository Secrets の編集権限が必要です。

```bash
./scripts/register-signing-secrets.sh "$HOME/.local/share/podcast-android/signing"
```

初回セットアップで渡された鍵を使う場合は、その秘密値ファイルのディレクトリを上のコマンドに指定してください。スクリプトを再実行して別の鍵を作らないでください。

## タグでリリースする

Secrets 登録後、テストが通った main のコミットにタグを付けて push します。

```bash
git switch main
./gradlew testDebugUnitTest lintDebug assembleDebug
git tag -a v0.1.1 -m 'Android テストアプリ v0.1.1 をリリースする'
git push origin v0.1.1
```

`vMAJOR.MINOR.PATCH` 形式のタグだけを使います。タグ名が versionName、リリースワークフローの run_number が versionCode になります。配布済みアプリの更新では versionCode が過去の値を上回ることを確認してください。同じワークフローを再実行しても versionCode は変わりません。

ワークフローは Secrets を検査し、UI テスト・release lint を実行して署名付き APK と AAB をビルドします。署名検証後、APK・AAB・R8 mapping・SHA256SUMS を GitHub の 通常のリリース として公開します。失敗したビルドは公開しません。Google Play へのアップロードは行いません。

自動の GITHUB_TOKEN を使うため、リリース用 PAT は不要です。リポジトリで GitHub Actions を有効にしておいてください。

## ローカルの署名付きビルド

```bash
signing_dir="$HOME/.local/share/podcast-android/signing"
export ANDROID_KEYSTORE_PATH="$signing_dir/release.jks"
export ANDROID_KEYSTORE_PASSWORD="$(cat "$signing_dir/ANDROID_KEYSTORE_PASSWORD")"
./gradlew lintRelease assembleRelease bundleRelease
"$ANDROID_HOME/build-tools/36.0.0/apksigner" verify --verbose app/build/outputs/apk/release/app-release.apk
jarsigner -verify app/build/outputs/bundle/release/app-release.aab
unset ANDROID_KEYSTORE_PATH ANDROID_KEYSTORE_PASSWORD
```

署名情報を設定せずに release のパッケージを生成するとエラーになります。Debug APK は SDK 標準の開発用署名を使います。
