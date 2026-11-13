# 保守時の入口

## 責務の分担

| 変更する対象 | 主な実装 |
| --- | --- |
| タブ・番組詳細・エピソード詳細・スクロール状態 | `PodcastApp`、`LibraryPages`、`EpisodeDetailsSheet` |
| 未登録番組の閲覧と登録への導線 | `PodcastPreviewPage`、`PodcastHeader`、`PodcastRepository.previewPodcast` |
| 再生パネルと戻る操作 | `PlayerHost`（`PlayerSheet.kt`）、`PlayerPage` |
| 設定と保存容量・更新結果 | `SettingsActivity`、`ManagementPage` |
| 共有・操作の取り消し通知 | `Sharing`、`AppSnackbar` |
| 色とアプリアイコン | `PodcastTheme`、`AppIcons`、[ロゴと配色](branding/README.md) |
| 画面と処理の境界 | `PodcastUiState` / `PodcastAction`、`PodcastsViewModel` |
| 再生開始・再開・前後移動・チャプター・音声削除後の切り替え | `PlaybackCommands` |
| 再生セッションと履歴の保存 | `PlaybackService` |
| 音量の自動補正・保存音声の事前解析 | `LoudnessMeter`、`LoudnessNormalizer`、`NormalizingAudioProcessor`、`SavedAudioAnalyzer`、`AudioAnalysisJob`（[処理と検証](audio-normalization.md)） |
| 再生キュー・速度・シークとスキップ | `PlaybackQueueSynchronizer`、`PlaybackSpeedController`、`PlaybackTransport` |
| 更新・保存の排他制御とOSへの接続 | `PodcastRepository`、`PodcastDownloadStorage`、`PodcastNotifications`、`PodcastSyncJob` |
| HTTP取得・番組検索・URL解決 | `PodcastHttp`、`PodcastDirectory` |
| RSS / Atom・説明文・チャプターの解析 | `FeedParser`、`EpisodeContent` |
| リンク・選択・コピー可能な本文の表示 | `ContentText` |
| DB・全文検索・キューと進捗の取り消し | `PodcastStore` |

Composable は `PodcastAction` を渡し、通信や保存はViewModel経由で実行する。ViewModel は接続・コルーチン・エラー処理・UIへの反映を担当し、再生方針は `PlaybackCommands` に置く。設定値のUI反映は `PodcastStatePreferences` の投影処理を使う。

## 再生と保存で守ること

- 単独再生では実行中キューを解除し、現在の回を保存して、初期一覧の範囲外でも表示できるようにする。通常の再開位置と、明示したチャプター位置を区別する。同じ回のチャプターへシークした場合は一時停止状態を保持する。
- 冒頭スキップはキューの自動遷移通知が0秒を過ぎて届いても適用する。適用は遷移ごとに一度だけとし、途中再開・チャプター指定・スキップ後の手動シークを上書きしない。
- 末尾スキップは次の音声へ移動してから完了を保存する。逆順にすると、キューの変更通知でもう一度進むおそれがある。
- 再生済みは全キューから取り除き、お気に入りと履歴を保持する。実再生の完了時だけ保存音声とDownloadManagerの記録を削除する。手動の再生済み変更では音声を削除しない。
- 保存音声を削除した後の再生元は配信URLへ戻す。現在位置・再生状態・タイムラインと明示したチャプター位置を保持する。
- DBはschema 5。旧版の購読・キュー・進捗・音声参照を `onUpgrade` で引き継ぐ。履歴は実際の再生時に記録し、日時のない旧記録は新しい記録より後ろへ並べる。

## 画面遷移と再生パネル

`MainActivity` はタブと番組詳細を保持する。設定は `SettingsActivity` を使い、OSのActivityバックスタックで戻る。再生画面・シート・子画面から順に戻り、アプリ終了は番組一覧のルートに委ねる。経路ごとの確認範囲は [戻る操作の監査](back-navigation-audit.md) を参照する。

`PlayerHost` はミニプレーヤーと展開画面で常設のMaterialシートとアンカーを共有する。開閉は標準のドラッグ・速度・ネストスクロールに任せる。再生中のタブは `HorizontalPager`、モーダルを閉じる操作は `rememberSheetAction` で終了アニメーションを待つ。

- 初期寸法はミニプレーヤー64dp（進捗線があれば+2dp）、ナビ80dpとシステム余白。生のピクセル値や、パネルの描画後サイズを同じアンカー計算へ循環させない。
- 密度・余白・ミニパネル高さを捕捉する `derivedStateOf` は、依存値が変わると更新する。折りたたみ済みなら起動時の折りたたみアニメーションを省く。
- 戻るの所有者は展開要求とSheetStateの現在／移動先の状態で決める。背後の画面のBackHandlerは、再生パネルが処理する間は無効にする。
- 再生項目がない場合はパネルと展開状態を解除する。下部の余白とSnackbarは、Scaffold下部バーの実測高さに合わせる。
- パネルの有無で本文の配置先が変わる場合も、`movableContentOf` で本文の状態を維持する。フィルターや選択中のキューを初期化しない。
- エピソードから番組へ移る場合は `PodcastLink` を使い、元のタブと一覧位置を残す。行の表示ボタンは再生、補助操作はスワイプ・長押しへ集約する。

## 更新と読み込み

起動時はDBを表示し、RSS更新は永続JobSchedulerで設定した間隔に行う。手動更新とOPML取り込み直後の取得は即時実行する。通知だけをオンにした番組も更新対象。通知は番組別に既定オフとし、初回取得で過去回を通知しない。

更新の通信は最大3接続、同じ番組は直列化する。RSSはストリーム解析する。画面の初期一覧は最新1,000件、追加500件、番組ページと履歴は150件ずつ読み込む。キュー・お気に入り・保存済み・再生中の回は範囲外でも利用できる。Wi-Fi制限の変更は未完了のDownloadManagerリクエストへ再適用する。

条件付き取得・全文検索・取り消し・ドラッグ・共有・管理画面の詳細は [library-operations.md](library-operations.md)。検索はDBの全文へのSQL問い合わせ、取り消しは進捗とキューを一緒に更新するトランザクションとして扱う。

変更後の検証と画面撮影は [開発・テスト](testing.md) を参照する。

依存の版・SHA-256・テストSDKの取得と署名鍵の分離は [依存管理と検証](dependencies.md) を参照する。
