# 戻る操作の監査（v0.8.1）

v0.8.1の不具合分析と経路一覧に、v0.9.0で追加した経路を追記した記録です。検証の実行手順は [開発・テスト](testing.md) を参照してください。

番組一覧から番組詳細へ移動した後、音声の長さが判明すると、折りたたまれた再生パネルが戻るイベントを消費していた。さらに、キュー詳細の上に再生画面を開いた場合、背後のキューのハンドラーが先に動いていた。どちらも修正前のテストで失敗を確認した。

## 原因と修正

`PlayerHost` の展開率は `remember { derivedStateOf { ... } }` が初回の `miniHeight` を捕捉していた。再生復元後に長さが 0 → 正の値へ変わると、進捗線の2dp分だけ高さが増える。アンカーは新しい高さ、展開率は古い高さを参照し、折りたたまれていても微小な展開率が残った。`progress > 0.001` に依存する戻るハンドラーが有効になり、既に閉じているパネルを閉じ直すだけで終わっていた。

- 寸法・密度・システム余白が変わった際に、展開率の計算が最新の値を使うようにする。
- 戻るを消費するかは展開率で判断せず、展開要求・現在の状態・移動先の状態から決める。完全に折りたたまれたパネルはイベントを消費しない。
- 再生画面が戻るを処理する間は、背後のキュー詳細のハンドラーを無効にする。Composeへの登録順に優先順位を委ねない。

実装: [PlayerSheet.kt](../app/src/main/java/io/github/sasakiy84/podcast/PlayerSheet.kt)、[PodcastApp.kt](../app/src/main/java/io/github/sasakiy84/podcast/PodcastApp.kt)、[PlaybackQueuesPage.kt](../app/src/main/java/io/github/sasakiy84/podcast/PlaybackQueuesPage.kt)。

## 確認するパターン

経路を「現在の画面・前面ウィンドウ・戻るの所有者」で分ける。エピソードのフィルターや番組の並び順は、同じ画面内の状態であり別の戻り先を作らない。これらの全組合せを重複したテストとして増やさず、異なる所有者・履歴の組合せを検証する。

「自動」はRobolectricでの操作検証、「実装」は該当する分岐・コールバック・標準コンポーネントの確認を示す。

| 現在の状態・入口 | 確定後の戻り先／作用 | 確認・結果 |
| --- | --- | --- |
| 番組一覧 → 番組詳細、再生項目なし | 番組一覧 | 自動: `podcastReturnsWithoutAPlayer` |
| 同上、復元したミニプレーヤーあり | 番組一覧。キャンセルでは詳細を保持 | 自動: `podcastReturnsToLibraryWithRestoredMiniPlayer` |
| 同上、復元後に音声の長さが判明 | 番組一覧。閉じた再生パネルは消費しない | **修正前に再現／修正後成功**: `durationArrivingAfterRestorationDoesNotMakeCollapsedPlayerConsumeBack` |
| 再生画面を開閉 → 番組詳細、Activity停止・再開後 | 番組一覧 | 自動: `podcastReturnsAfterPlayerHasBeenClosedAndActivityResumed` |
| 保存した状態から番組詳細を復元 | 番組一覧 | 自動: `PodcastAppTest.selectedPodcastSurvivesStateRestoration` |
| エピソード一覧 → 詳細シート → 番組 | 元の一覧の位置へ戻る。キャンセルでは番組を保持 | 自動: `EpisodeNavigationTest.episodeDetailsOpenThePodcastAndBackRestoresListPosition` |
| キュー一覧 → キュー詳細 | キュー一覧。その次は番組タブ | 自動: `playerBackLeavesTheUnderlyingQueueSelectionIntact` の後半 |
| キュー詳細の上に再生画面 | まず再生画面だけを閉じ、選択キューを保持 | **修正前に再現／修正後成功**: `playerBackLeavesTheUnderlyingQueueSelectionIntact` |
| 通常画面の上に再生画面 | ミニプレーヤーと元の画面へ | 自動: `PlayerPolishTest.upwardDragMovesPlayerBeforeTheFingerIsReleased`。背後のキュー以外に競合するページ固有ハンドラーはない |
| 再生画面の「詳細」「チャプター」タブ | 再生画面全体を閉じる | 実装: PlayerPageのタブは同じシート内、各タブに独自BackHandlerなし |
| 再生画面の開閉アニメーション中 | 展開要求・currentValue・targetValueを見て再生画面が処理。背後は戻さない | 実装: `playerHandlesBack` を共用。指に追従する開閉は既存の操作テストでも検証 |
| 再生項目が消える | パネルを除去し、その後は現在のページが処理 | 実装: `PodcastApp` の `LaunchedEffect(current?.id)`、既存のパネル除去テスト |
| エピソード／キュー／検索タブのルート | 番組タブ。キャンセルでは選択タブを保持 | 自動: `tabsReturnToLibraryAndOnlyItsRootDelegatesBackToTheOs` |
| 番組タブのルート | アプリのハンドラーは無効。終了・バックグラウンド化はOSに委ねる | 自動: 同テストで `hasEnabledCallbacks == false` を確認 |
| 番組詳細 → 購読元シート | シートだけを閉じ、番組詳細を保持 | 自動: `podcastSheetsAndNestedRuleEditorReturnOneLevelWithoutCommitting` |
| 番組詳細 → 番組別速度シート | 同上。閉じるだけで速度変更を送信しない | 自動: 同上 |
| 番組詳細 → 通知・先頭末尾スキップ設定 | 同上。保存操作を実行しない | 自動: 同上 |
| 番組詳細 → 購読解除の確認 | 確認だけを閉じ、購読を解除しない | 自動: 同上 |
| 番組詳細 → 自動追加のキュー選択 → ルール編集 | 編集 → キュー選択 → 番組詳細、と1段ずつ戻る。未保存のルールを送信しない | 自動: 同上 |
| キューから自動追加ルールを表示 | キュー一覧 | 実装: ルール表示はキュー詳細内のセクションで、独立ルートではない |
| キュー側の既存ルール → 編集シート | 編集シートだけを閉じる | 実装: 同じ `QueueRuleDialog` / `QueueFormSheet` と、`ruleEditor = null` のdismiss |
| キューの新規作成／名前変更 | 入力シートだけを閉じ、保存しない | 実装: `QueueFormSheet.onDismissRequest` はdismissのみ。キュー追加編集でも同じシートの戻るを自動確認 |
| キュー詳細 → 削除確認 | 確認だけを閉じ、キューを削除しない | 自動: `episodeAndMembershipSheetsReturnToTheirSourceQueue` |
| エピソード一覧／番組詳細／キュー → エピソード詳細 | シートだけを閉じ、元の一覧を保持 | 自動: キュー経路を同上で確認。その他も同じ `EpisodeDetailsSheet` |
| エピソード詳細 → キュー追加編集 | キュー追加編集を閉じ、起点の一覧へ（エピソード詳細との入替え） | 自動: 同上。所属の変更操作を自動送信しないことも確認 |
| 再生画面 → 再生速度シート | 再生画面へ戻る。背後のキューは戻さない | 自動: `playerBackLeavesTheUnderlyingQueueSelectionIntact` |
| 再生画面 → キュー追加編集シート | 再生画面へ戻る | 自動: 同上 |
| 番組一覧／キュー詳細／エピソード長押し／再生画面のメニュー | ポップアップだけを閉じる | 実装: 標準DropdownMenuの前面Popupで処理し、onDismissRequestはローカルのメニュー状態を閉じるだけ。画面遷移や削除を送信しない |
| エピソード詳細のコピー項目メニュー | メニューだけを閉じる | 実装: `EpisodeCopyButton` の標準DropdownMenu、dismissはローカルのexpandedをfalseにするだけ |
| 再生画面 → 番組リンク | パネルを閉じて番組へ。次の戻るで元のタブの一覧へ | 実装: `openPodcast` が `playerExpanded=false` と `selectedFeed` を設定。既存の番組リンク到達テストも保持 |
| 再生画面 → キューボタン | パネルを閉じてキュータブへ。キュー詳細なら一覧、タブルートなら番組へ | 実装: `queues` コールバックと、上記キュー・タブの戻り処理を共用 |
| 設定Activity → 全体の速度シート | シートだけを閉じて設定Activityを保持 | 自動: `SettingsNavigationTest.backDismissesTheSpeedSheetBeforeFinishingTheSettingsActivity` |
| 設定Activityのルート | Activityを終了しMainActivityへ戻る | 自動: 同テストでActivity終了。実装: 起動IntentとManifestの親Activityを確認 |
| 設定ツールバーの戻る | 同上 | 実装: 同じSettingsActivityの `finish()` を使用 |
| OPMLドキュメント選択／保存画面のキャンセル | 設定へ戻り、nullのURIで処理しない | 実装: ActivityResultの `it?.let`。外部のOS画面そのものは実機未検証 |
| 通知権限ダイアログのキャンセル | 番組設定へ戻り、通知を有効にしない | 実装: 許可された場合だけdispatchし、pendingを解除。OS画面そのものは実機未検証 |
| RSS・番組サイト・説明リンクからブラウザーへ移動 | OSのActivityスタックで元のアプリへ | 実装: `LocalUriHandler` / `Intent`。アプリ復帰時のハンドラー順は上記停止・再開テストも確認 |
| IME・文字選択メニューが前面 | 標準の入力・選択UIが処理した後、現在の画面の戻り処理へ | 実装: 独自キー横取りなし。OSの優先順位・見た目は実機未検証 |
| 並べ替え・エピソード絞り込み・説明全文展開・スワイプ操作帯 | 同じページ内の状態なので、そのページの戻り先へ | 実装: 独立した戻るハンドラー／ルートを登録していない |

## 検証の範囲

[SystemBackNavigationTest](../app/src/test/java/io/github/sasakiy84/podcast/SystemBackNavigationTest.kt) は、AndroidXの `OnBackInvokedInput` が呼ぶ dispatcher の開始・進行・キャンセル・確定APIを通す。モーダルが前面にある場合はそのComponentDialogのdispatcherを使う。[BackGestureTestDriver](../app/src/test/java/io/github/sasakiy84/podcast/BackGestureTestDriver.kt) に集約し、アプリの戻るラムダを直接呼んだり、遷移状態をテスト側で直接書き換えて成功扱いにしたりしない。

ポップアップの標準Backキー処理だけを繰り返すテストは追加せず、各onDismissRequestの副作用を実装で確認した。設定Activityのテストは実Activityを使い、音声サービスのbindだけを不成功にして、RobolectricのMediaSession接続制約から切り離している。

Robolectricには端末のエッジジェスチャーやランチャーがないため、OSからActivityへの配信、IME・権限・外部アプリの画面は実機未検証。上表で「実装」とした項目を自動検証済みとは扱わない。

旧 `AppNavigationTest.backFromSearchAndPodcastReturnsHomeWithoutFinishingActivity` は、今回のタブ・番組詳細・キャンセル検証と重複するため置き換えた。保存状態から戻るテストと、エピソードから番組へ移動した後のスクロール保持テストは、既存ケースの戻る操作を同じドライバーに変更した。

検証結果: 回帰126件と明示実行の画面撮影4件、計130件が成功。`lintDebug` / `assembleDebug` も成功。明暗108画面と文字拡大2画面はv0.8.0と110枚すべて画素一致した。

## v0.9.0で追加した経路

| 状態 | 戻り先・作用 | 確認 |
| --- | --- | --- |
| エピソード内検索 | 検索を閉じて元の一覧位置へ。再生画面が前面なら再生画面を優先 | 実ViewModelの操作テスト／backEnabledの共用 |
| キューのドラッグ中 | 並べ替えプレビューを破棄し、同じキューに留まる | 実ViewModelの操作テスト |
| 共有・コピーのシート | シートだけを閉じ、元の詳細・一覧・再生画面へ | 共有のキャンセル・確定を操作テスト |
| 設定内のストレージと更新 | 設定のルートへ戻る。タブ切り替えでは戻り先を増やさない | 管理画面の操作テスト |
