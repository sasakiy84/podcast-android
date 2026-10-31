# 開発規約

- Kotlin / Jetpack Compose、JDK 21、Android SDK 36 を使う。
- このクラウドタスクは既に隔離されている。明示的な依頼なしに worktree を作らない。
- 変更後は `./gradlew testDebugUnitTest lintDebug assembleDebug` を実行する。
- UI 動作テストは Robolectric + Compose Test API で `app/src/test` に置く。
- 署名情報をソース、ログ、チャットに出さない。署名設定は環境変数から読む。
- GitHub Actions は `.github/workflows/release.yml` のリリース専用のみとする。
- リリースと署名の手順は `docs/releasing.md` を参照する。
