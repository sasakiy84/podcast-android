# 保守時の入口

- `PodcastApp` はタブ・番組・モーダルの遷移と画面状態の保持を担当する。番組とエピソード一覧は `LibraryPages`、再生画面は `PlayerPage`、共通の色は `PodcastTheme`。
- `PodcastUiState` / `PodcastAction` が画面と `PodcastsViewModel` の境界。通信や保存を Composable から直接実行しない。
- `PodcastRepository` が更新の同時実行制限と保存の排他制御を担当する。`PodcastDownloadStorage` は DownloadManager と音声ファイル、`PodcastNotifications` は新着通知を扱う。
- `PlaybackService` が MediaSession と再生履歴の保存を管理する。キュー反映は `PlaybackQueueSynchronizer`、シークと先頭・末尾スキップは `PlaybackTransport`、速度の追従は `PlaybackSpeedController` に分ける。画面を閉じても再生設定は有効。
- `FeedParser` は RSS / Atom と Podlove チャプターをストリーム解析する。`EpisodeContent` は説明文の段落・安全なリンク先と Podcasting 2.0 JSON チャプターを扱い、`ContentText` が選択・コピー可能なリンク付きテキストを描画する。
- `PodcastStore` は schema 4。更新は `onUpgrade` で既存の購読・キュー・音声参照を保持する。履歴は実際の再生時に記録し、古い版の再生進捗も引き継ぐ。ただし古い版には視聴日時がないため、移行前の履歴は日時不明として新しい記録より後ろに並ぶ。

末尾スキップは次の音声へ移動してから完了を保存する。順序を逆にすると、キューの変更通知でもう一度進むおそれがある。再生済みはすべてのキューから取り除き、お気に入りと履歴は保持する。実際に再生が完了した場合は保存音声と DownloadManager の記録を削除する（末尾スキップも含む）。手動で再生済みにしただけでは削除しない。削除後の再生元は配信URLに戻し、単独再生でも再生できるようにする。

新着通知は番組別の既定値がオフ。オンにすると Android の通知権限を要求する。初回の取得では過去回の通知を出さず、以降の新着を番組単位でまとめる。通知だけをオンにした番組も定期更新の対象になる。

変更後は `./gradlew testDebugUnitTest lintDebug assembleDebug`。再生・保存の回帰は `EpisodeFeaturesTest`、操作は `PlayerInteractionTest` / `PodcastUxTest`、画面の明暗・文字拡大は `PodcastScreenshotTest` で確認する。実 RSS / OPML の私的データはテストコードやリポジトリに含めない。

`MainActivity` は単一の再生セッションとタブ・番組詳細を保持し、サブ画面では BackHandler で上位へ戻る。アプリ終了は番組一覧のルートだけに委ねる。設定は `SettingsActivity` に分け、OS の Activity バックスタックで戻る。再生画面は `PlayerHost` の常設 Material BottomSheetScaffold とし、ミニプレーヤーと展開画面でアンカーを共有する。標準のアンカー・速度・ネストスクロール・hide アニメーションで開閉する。

起動時はDBを表示するだけでRSSを更新しない。全購読を対象に永続 JobScheduler を設定し、設定した間隔で更新する。明示した更新操作やOPML取り込み直後の取得は即時に実行する。Wi-Fi 制限の変更は未完了の DownloadManager リクエストを再作成して適用する。

`PlayerHost` は常設の BottomSheetScaffold と標準 SheetState の展開・折りたたみアンカーを共有する。ミニプレーヤー側で距離だけを判定して別画面を出さない。再生中のタブは HorizontalPager と同じ状態で管理する。シートをボタンで閉じる操作は `rememberSheetAction` で hide 完了後に解除し、未完了の終了処理の重複を防ぐ。キュー追加の結果はプレーヤーより上の Snackbar と追加済みアイコンで示す。

通知の下余白は固定のプレーヤー高さを使わず、Scaffold の下部バーの実測高さに合わせる。再生項目がなくなった場合は展開状態も解除する。エピソードから番組への移動は `PodcastLink` を使い、元のタブ・一覧状態を残して `selectedFeed` を切り替える。キュー内の行には追加操作を重ねず、再生と補助操作を分離する。
