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

Secrets 登録後、テストが通った main のコミットにタグを付けて push します。例の版番号は未使用のものへ置き換えてください。

```bash
git switch main
./gradlew testDebugUnitTest lintDebug assembleDebug
git tag -a v0.1.1 -m 'GalenaCast v0.1.1 をリリースする'
git push origin v0.1.1
```

`vMAJOR.MINOR.PATCH` 形式のタグだけを使います。タグ名が versionName、リリースワークフローの run_number が versionCode になります。配布済みアプリの更新では versionCode が過去の値を上回ることを確認してください。同じワークフローを再実行しても versionCode は変わりません。

既存アプリの更新では `applicationId`（`io.github.sasakiy84.podcast`）と署名鍵を維持します。

リリース専用workflowは二つのジョブで実行します。

1. `build`: 署名Secretを持たず、ロックとSHA-256を検証してUIテスト・release lint・未署名APK/AABの生成を行います。成果物・mapping・リリースノート・チェックサムを1日保持のartifactに渡します。
2. `release`: 別のrunnerでartifactを取得し、リポジトリをcheckoutせず、Gradleやテストも実行しません。署名ステップだけが上記2項目を受け取り、検証済みの公式ツールで署名します。公開証明書が既存のアプリと一致することを確認し、鍵ファイルを削除してから公開します。

APK・AAB・R8 mapping・SHA256SUMSを通常リリースとして公開します。検証・ビルド・署名が失敗した場合は公開しません。Google Playへのアップロードは行いません。署名鍵を変更する際は、Secretだけでなくworkflowの公開証明書SHA-256も意図的に更新する必要があります。既存の鍵を再利用する今回の変更ではSecretの再登録は不要です。

自動の GITHUB_TOKEN を使うため、リリース用 PAT は不要です。リポジトリで GitHub Actions を有効にしておいてください。

## ローカルでの確認

署名情報を環境変数に入れず、未署名で検証します。

```sh
./gradlew --dependency-verification=strict testDebugUnitTest lintDebug assembleDebug
./gradlew --dependency-verification=strict lintRelease assembleRelease bundleRelease -PunsignedRelease=true
```

APKは `app/build/outputs/apk/release/app-release-unsigned.apk`、AABは `app/build/outputs/bundle/release/app-release.aab` に生成されます。未署名の配布物をそのまま公開しないでください。手元で署名する必要がある場合も、Gradleが終了した後の別プロセスで公式の `apksigner` / `jarsigner` を使います。署名コマンドはworkflowの `Sign and verify release artifacts` ステップを参照してください。

署名用の環境変数をGradleへ渡すとエラーになります。`-PunsignedRelease=true` を省いたrelease生成もエラーです。Debug APKはSDK標準の開発用署名を使います。

依存の更新・初期ハッシュの扱いは [依存管理と検証](dependencies.md) を参照してください。
