# 開発規約

- Kotlin / Jetpack Compose、JDK 21、Android SDK 36 を使う。最低対応は Android 16（minSdk 36）。
- このクラウドタスクは既に隔離されている。明示的な依頼なしに worktree を作らない。
- 変更後は `./gradlew testDebugUnitTest lintDebug assembleDebug` を実行する。
- UI 動作テストは Robolectric + Compose Test API で `app/src/test` に置く。
- 署名情報をソース、ログ、チャットに出さない。署名設定は環境変数から読む。
- GitHub Actions は `.github/workflows/release.yml` のリリース専用のみとする。
- リリースと署名の手順は `docs/releasing.md` を参照する。

- 購読・再生履歴・音声は端末内に保存する。アカウント、プレイリスト、レコメンド、クラウド同期を追加しない。
- キーワード検索は Apple Podcasts、主要プラットフォームの共有URLは公開 RSS または登録前に確認する候補として扱う。
