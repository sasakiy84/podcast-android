Android 16 以降向けの個人用ポッドキャストアプリです。

- 音声の長さが復元後に判明したとき、閉じた再生パネルが戻る操作を奪い、番組詳細から一覧へ戻れない問題を修正
- キュー詳細の上に再生画面を開いたとき、戻る操作が背後のキューへ先に届く問題を修正
- キャンセル・確定、Activityの再開・保存状態の復元、タブ・シート・設定Activityの戻る操作を検証し、ポップアップ等の実装も点検

戻る操作のパターンと実装確認結果は [docs/back-navigation-audit.md](https://github.com/sasakiy84/podcast-android/blob/v0.8.1/docs/back-navigation-audit.md) に記載しています。

Robolectricで検証しています。OSのエッジ操作そのものと外部アプリの画面は実機未検証です。

回帰126件・画面撮影4件、Lint、Debug APKビルドを通過しました。110枚の画面画像が前版と画素一致しています。
