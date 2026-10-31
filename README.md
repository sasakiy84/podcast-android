# Podcast Android

Kotlin / Jetpack Compose の Android テストアプリ。カウントの増加、リセット、状態復元を確認できます。ポッドキャストの検索・音声再生は今後実装します。

## 開発

- JDK 21
- Android SDK Platform 36 / Build Tools 36.0.0
- 最小 Android 8.0（API 26）
- Gradle 8.14.6 / Android Gradle Plugin 8.13.2 / Kotlin 2.2.21

Android Studio ではこのディレクトリを開きます。SDK パスは `ANDROID_HOME` または未追跡の `local.properties` で指定します。

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
adb shell am start -n io.github.sasakiy84.podcast/.MainActivity
```

UI テストは Robolectric + Compose Test API を使い、エミュレーターなしで実行します。実機のシステムバーや端末固有の表示確認は別途行います。

## このクラウド環境

```bash
./scripts/bootstrap-cloud.sh
source /workspace/.android-dev/env.sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

初回の Android CLI とグローバルスキルの導入には、ホームディレクトリへの書き込みが必要です。

```bash
python3 scripts/install-global-skills.py
```

公式 `android/skills` と `agent-toolbox` の `pkg00-base`・`pkg01-dev` を `~/.codex/skills` に導入します。取得元コミットは `scripts/SKILLS.lock` に記録しています。認識させるには新しい Codex タスクを開始してください。

## リリース

[署名とリリース手順](docs/releasing.md)を参照してください。GitHub Actions はバージョンタグを push したときのリリースワークフローだけです。通常のブランチ push や Pull Request では実行しません。
