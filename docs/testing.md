# 開発・テスト

## 環境と基本確認

JDK 21、Android SDK 36（Build Tools 36.0.0）を使う。クラウド環境を作り直す場合は `bash scripts/bootstrap-cloud.sh` で準備し、各シェルで次を読み込む。

```sh
source /workspace/.android-dev/env.sh
```

コードを変更したら、リポジトリのルートで実行する。

```sh
./gradlew testDebugUnitTest lintDebug assembleDebug
```

依存はロックとSHA-256で検証する。RobolectricのSDKも検証済みJARをofflineで参照する。依存を変更する場合は [依存管理と検証](dependencies.md) を参照する。

結果は `app/build/test-results/testDebugUnitTest` のJUnit XML、`app/build/reports/tests/testDebugUnitTest` とlintのHTML、`app/build/outputs/apk/debug/app-debug.apk` に出力される。

対象を絞った確認には、例えば `./gradlew testDebugUnitTest --tests '*PlaybackCommandsTest'` を使う。最終確認は基本コマンドで全体を実行する。

## 画面撮影

通常の回帰テストに加え、明暗の全画面カタログを生成する場合は次を使う。

```sh
./gradlew testDebugUnitTest -PcaptureScreenshots=true
```

カタログだけを再撮影する場合は `--tests '*ScreenCapture'` を追加する。出力は `app/build/reports/screenshots` 以下。

- `all-screens/light` / `dark`: 通常画面、シート、空状態、読み込み・エラー表示と設定。
- `branding.png`: ランチャー、丸型・角丸型、テーマアイコンと通知。
- `features`: 検索・取り消し・ドラッグ・共有・ストレージ管理の操作シナリオ。
- `device`: 高密度端末の再生パネル。
- `library-large-text.png` / `player-large-text.png`: 文字拡大時の一覧と再生画面。

画像カタログはレビュー用で、自動の期待画像比較ではない。見た目を保つ変更では変更前後の画像を比較し、サムネイルの読み込み前後などの非同期表示も確認する。UIの操作・実描画はAndroid 16のRobolectricで検証し、実機や音声デバイスの確認は別途行う。

## 保証のある境界にテストを置く

| 境界 | 主なテストと検出する不具合 |
| --- | --- |
| 再生方針と端末内の状態 | `PlaybackCommandsTest`: 実ExoPlayerとDBを使い、再開・チャプター・キュー移動・保存音声の削除後の位置や状態を検証 |
| シーク・自動スキップ・完了順序 | `EpisodeFeaturesTest`、`PlaybackQueueTest`: 停止中シークの失敗、番組設定の誤適用、二重に次へ進む不具合 |
| DB・旧版データ・整理操作 | `PodcastStoreTest`、`PodcastMigrationTest`、`LibraryUndoTest`: データ喪失、複数キューと並行操作の上書き |
| 更新・解析・URL解決 | `PodcastHttpTest`、`FeedParserTest`、`PodcastDirectoryTest`: 実ループバックHTTPと小さなfixtureで検証 |
| OSへのダウンロード・通知・画像キャッシュの接続 | `PodcastDownloadsTest`、`ArtworkCacheTest`、`EpisodeFeaturesTest`: 回線制限や取得状態、オフライン利用の誤り |
| 画面の実操作と戻る所有者 | `AppNavigationTest`、`SystemBackNavigationTest`、`LibraryFeatureUiTest`など: 遷移・スクロール・操作の誤配線 |
| 初期フレームと再生パネルの実描画 | `PlayerDeviceLayoutTest`: 高密度・初期配置・開閉後・暗色で操作パネルが消える不具合 |

テストの基準は「削除すると、どんな現実的な不具合を見逃すか」。実装のコピー、モックの自己検証、ライブラリの保証の再確認、重複、過剰なケース分けを整理する。実行件数やカバレッジの維持を目的にしない。

移行用の `app/src/test/resources/migrations` は公開済みschema 1〜4の固定SQL。本番のスキーマ変更に合わせて書き換えない。ユーザーのOPML・RSSや署名情報はコード・fixture・ログへ含めない。

過去の判断と測定値は [v0.8.0のテスト監査](test-audit.md)、戻る経路の一覧は [戻る操作の監査](back-navigation-audit.md)、検索などの仕様は [library-operations.md](library-operations.md) を参照する。
