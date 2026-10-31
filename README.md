# Podcast Android

アカウント不要、購読情報・再生履歴・音声を端末内で管理する個人用ポッドキャストアプリです。

- Apple Podcasts の番組検索、RSS / Atom の登録
- Apple Podcasts・Spotify・Amazon Music・Pocket Casts の番組URLから登録（RSS を直接取得できない場合は候補を確認）
- バックグラウンド再生、再生位置の復元、15秒戻し／30秒送り、0.5〜3倍速
- 音声のダウンロード・削除、オフライン再生、未再生の絞り込み

RSS のない独占配信は登録できません。検索・番組更新・ストリーミング時には配信元へ通信します。プレイリスト・レコメンド・クラウド同期はありません。

Android 16（API 36）以降に対応。JDK 21 / Android SDK 36。`./gradlew testDebugUnitTest lintDebug assembleDebug` で検証できます。クラウドでは先に `source /workspace/.android-dev/env.sh` を実行します。

署名とタグによるリリースは [docs/releasing.md](docs/releasing.md) を参照してください。
