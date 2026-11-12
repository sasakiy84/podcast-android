# 依存管理と検証

## 現在の構成

AndroidX / Compose / Material3 / Media3、Kotlin・コルーチンを基盤にする。CoilとJsoupは引き続き利用する。画像キャッシュとHTML解析の置き換えは今回の対象に含めない。

使用するMaterial Iconsの37点だけをVectorDrawableとして同梱し、`AppIcons` から参照する。形・透過度・RTL反転はAndroidX 1.7.8のデータを保持している。出典・SHA-256・Apache-2.0ライセンスは `third_party/material-icons` に置き、同じファイルをAPKのassetsにも含める。`material-icons-extended` / `material-icons-core` のライブラリは使わない。

`ui-tooling-preview` と `ui-tooling` の直接依存は削除。UIテスト用の `ui-test-manifest`、JUnit、Robolectricは維持する。

## 固定するもの

| 対象 | 管理場所 |
| --- | --- |
| Gradle配布物 | `gradle/wrapper/gradle-wrapper.properties` のSHA-256 |
| ビルドプラグインと間接依存の版 | `buildscript-gradle.lockfile` |
| debug/releaseのコンパイル・実行、単体テストのclasspathとSDKの版 | `app/gradle.lockfile` |
| プラグイン・ライブラリ・POM/module・テストSDKのSHA-256 | `gradle/verification-metadata.xml` |
| Actions | リリースworkflow内のコミットSHA |
| Androidコマンドラインツール・署名ツール | workflow / `bootstrap-cloud.sh` の固定URL・版・SHA-256 |

ロック対象のclasspathはstrictモード。依存検証はGradleのstrictモードで行い、未知のハッシュを許可する例外は設けない。通常のテスト・ビルドでは固定値を書き換えない。

lintは警告もエラーとして扱う。ただしライブラリの新版を知らせる `NewerVersionAvailable` と、Gradle / Android Gradle Pluginの新版を知らせる `AndroidGradlePluginVersion` は除外する。依存の更新は下記のレビュー手順で行い、新版の公開によって同じ固定版のリリースが失敗しないようにする。コード・API互換性の検査と依存のSHA-256検証は維持する。

Robolectric 4.17で使っていたAndroid 16の `android-all-instrumented:16-robolectric-13921718-i7` は、専用の `robolectricSdk` 構成でGradleから取得する。検証したJARを `app/build/verified-test-sdk` に配置し、Robolectricはofflineモードで参照する。Robolectric独自のMavenダウンロードは使わない。

## 依存を変更するとき

1. 変更する依存・提供元・版を確認する。既存の依存が変わる理由も確認する。
2. 意図した変更がある場合に限り、次のコマンドでロックと検証メタデータを更新する。

   ```sh
   ./gradlew resolveLockedDependencies --write-locks --write-verification-metadata sha256
   ./gradlew --write-verification-metadata sha256 testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease bundleRelease -PunsignedRelease=true
   ```

3. ロック差分と各ハッシュの取得元をレビューする。チェックサムの生成は、その内容の安全性を判定する処理ではない。初期メタデータは既存の固定版をGoogle Maven・Maven Central・Plugin Portalから解決した結果を基準にする。
4. 書き込み用オプションを外し、strictモードで再確認する。

   ```sh
   ./gradlew --dependency-verification=strict testDebugUnitTest lintDebug assembleDebug
   ./gradlew --dependency-verification=strict lintRelease assembleRelease bundleRelease -PunsignedRelease=true
   ```

初回取得後は `--offline` も指定できる。検証エラーを回避するためにstrictモードを無効化したり、メタデータを無条件で再生成したりしない。

初期導入や依存更新時は、空の依存キャッシュで解決・テスト・ビルドも確認する。既存キャッシュでは親POMやBOMの取得が省略され、検証メタデータの不足を見逃すことがある。コンパイラやlintの依存も確認するため、classpathの解決だけで終わらせない。

```sh
verification_gradle_home="$(mktemp -d)"
GRADLE_USER_HOME="$verification_gradle_home" ./gradlew --dependency-verification=strict resolveLockedDependencies testDebugUnitTest lintDebug assembleDebug lintRelease assembleRelease bundleRelease -PunsignedRelease=true
```

プロキシやCAなどの環境固有のGradle設定が必要な場合は、新しい `GRADLE_USER_HOME` にも引き継ぐ。依存キャッシュはコピーしない。不足が見つかった場合は対象の取得元と公開チェックサムを確認して補完し、strictモードのまま再検証する。

Material Iconsを追加・更新するときは必要なものだけを公式配布元から取り込み、同梱ライセンスと出典を更新する。新たなアイコン集全体への依存を追加しない。

## 署名鍵との境界

同じリリース専用workflowの `build` ジョブは、署名Secretを受け取らず、検証・テスト・未署名APK/AABの生成を行う。`release` ジョブは別のrunnerで成果物だけを受け取り、リポジトリのcheckout・Gradle・テスト・ビルドキャッシュを使わない。

署名Secretは署名ステップだけに渡す。検証済みの公式 `apksigner` / JDK `jarsigner` で署名し、既存の公開証明書のSHA-256と一致する鍵か確認する。秘密値をコマンドライン引数に展開せず、鍵ファイルは終了時に削除する。公開ステップが受け取るのは公開用トークンだけ。

Gradleに署名用の環境変数を渡すと構成時にエラーになる。releaseの生成には `-PunsignedRelease=true` を明示し、署名はGradleの外で行う。設定方法は [署名とリリース](releasing.md) を参照する。
