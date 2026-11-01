# テスト監査（v0.8.0）

基準は「消すと、どんな現実的な不具合を見逃すか」。件数やカバレッジの維持は目的にしない。v0.7.4 の全20ファイル・145メソッドと補助処理を確認した。

通常実行は118件。削除・統合で25件減らし、画像を保存するだけの4メソッドは明示実行の `ScreenCapture` へ移動。今回分離した再生処理には実ExoPlayerを使う2件を追加した。schema 1/2/3 の移行はケースを捨てず、独立fixtureを使う1メソッドへ統合した。

## 削除・統合・修正の候補と対応

「代替・残る保証」がある行は、そこも削除した場合に記載の不具合を見逃す。単なる重複の削除では固有の検出能力を失わない。ライブラリ任せの動作・到達不能な操作・自己検証は固有の保証がないため残さない。

| 対象 | 判断・対応理由 | 代替・残る保証 |
| --- | --- | --- |
| `PodcastAppTest.seekSliderChangesPlaybackPosition` | SetProgress の内部セマンティクス操作は実タップのシーク検証と重複。 | PlayerInteractionTest.pausedSeekAndChapterSelectionDispatchPositionsWithoutTogglingPlayback |
| `PodcastAppTest.libraryShowsLocalSubscriptionsAndMiniPlayer` | 画面撮影と存在確認が重複。未表示タイトルの仕様はミニプレーヤーの中央配置テストで検証。 | ScreenCapture / miniAndFullPlayerDispatchTransportAndSpeed |
| `PodcastAppTest.librarySupportsDarkMode` | 暗色の番組が存在することだけを再確認。色の正否は判定していない。 | ScreenCapture 暗色撮影 / PlayerDeviceLayoutTest 暗色の実描画 |
| `PodcastAppTest.anEpisodeCanBeAddedToTwoQueuesFromItsDetails` | チェックボックスの配列位置に依存。複数所属はDB、ラベル全域の操作は専用UIテストで保証。 | PlaybackQueueTest.oneEpisodeCanBelongToMultipleQueuesWithoutDuplicates / AppNavigationTest.queueMembershipCanBeToggledByItsLabel |
| `PodcastAppTest.fullPlayerChangesSpeedAndSkips` | ミニ/全画面の同じ操作を別のCompose起動で検証していた。 | PodcastAppTest.miniAndFullPlayerDispatchTransportAndSpeed に統合 |
| `PodcastAppTest.emptyLibraryCanImportButCannotExport` | OPMLの空/登録済み状態を同じ画面の遷移として検証できる。 | PodcastAppTest.settingsOpmlAvailabilityAndCallbacksFollowTheLibrary に統合 |
| `PodcastUxTest.miniPlayerOpensWithAnUpwardSwipeAndClosesWithADownwardSwipe` | スワイプ完了後だけの存在確認。指への追従と終了の両方を既存の上下方向テストが保証。 | PlayerPolishTest.upwardDragMovesPlayerBeforeTheFingerIsReleased / AppNavigationTest.playerFollowsTheFingerBeforeSettlingClosed |
| `EpisodeNavigationTest.withoutAPlayerTheListExtendsToNavigation` | 再生項目を取り除いた後に同じ領域の下端を検証している。 | EpisodeNavigationTest.removingThePlayerReclaimsItsHeight に未表示確認を統合 |
| `EpisodeNavigationTest.queueRowsDoNotOfferToAddAnEpisodeAlreadyInTheQueue` | 再生以外のクリック操作がないことは同じファイルの行テストと重複。メニューの入口は統合可能。 | EpisodeNavigationTest.episodeRowsExposeOnlyThePlayButton にメニュー到達確認を統合 |
| `PlayerPolishTest.refreshIntervalButtonsHaveVisibleGaps` | テキスト間の距離だけを確認しており、ボタンの当たり判定や背景間の隙間を保証していない。 | ScreenCapture の設定画面で確認。固有の機能保証は失わない。 |
| `PlayerPolishTest.queueAddFeedbackIsVisibleAboveTheExpandedPlayer` | ダミーコールバックが自ら書き換えたキューを自ら確認していた。UIからの要求と表示のみを検証する形に修正。 | 実キュー保存は AppNavigationTest.quickAddIsIdempotentAndPersistsItsDefaultQueue |
| `PlayerDeviceLayoutTest.restoredPlayerWithGestureInsetsHasVisibleControls` | 初期アンカー12フレームの描画検査が同じ条件をより厳密に検証。 | PlayerDeviceLayoutTest.collapsedControlsStayPaintedWhileInitialAnchorsSettle |
| `PlayerDeviceLayoutTest.collapsedControlsStayPaintedWhileInitialAnchorsSettle` | 全12フレームの画素検査は保持し、同じ1080×2340画像の毎フレームPNG圧縮を最終フレームだけに削減。 | 白抜け・位置ずれの検出は全フレーム継続 |
| `PlaybackQueueTest.completionEverywhereRemovesAllMembershipsButKeepsHistoryAndAudioReference` | Storeの完了処理をRepository経由の実ファイル検証でも通る。 | PodcastDownloadsTest.manuallyMarkedEpisodeLeavesAllQueuesButKeepsDownloadedAudio |
| `PlaybackQueueTest.disabledRuleAndManualRemovalDoNotReAddKnownEpisodes` | 同じ既知IDが再追加されない分岐と無効ルールの判定を別テストが検証。 | newEpisodesAreMatchedOnlyOnceAndExcludedWordsWin / includeAllAndAbsentExcludedWordsAreSupported |
| `PodcastStoreTest.subscriptionsHistoryAndDownloadIdsSurviveReopening` | SQLiteのclose/reopen自体を確認。実際の破壊リスクは各版の移行・フィード更新テストで検証。 | PodcastMigrationTest / PodcastStoreTest.refreshKeepsHistoryAndOldEpisodesMissingFromTheLatestFeed |
| `PodcastLibraryFeaturesTest.rapidTransitionsDoNotRewriteEitherStoredSpeedScope` | 同じ遷移と設定値の不変性を連続遷移の検証に統合可能。 | speedOverridesPersistAndFollowAutomaticQueueTransitions に統合 |
| `PodcastDownloadsTest.duplicateRequestsDoNotStartTwoDownloads` | 自動追加・手動追加で同じ保留中リクエストを再利用するシナリオに統合。 | automaticDownloadsUseOneSharedRequestAndManualRemovalPreventsRedownloading に統合 |
| `PodcastDownloadsTest.removingDownloadClearsTheFileAndPersistedManagerId` | 自動再ダウンロード抑止のシナリオでも同じ削除を行う。 | automaticDownloadsUseOneSharedRequestAndManualRemovalPreventsRedownloading にファイル削除確認を統合 |
| `PodcastOpmlTest.recognizesUtf16ByteOrderAndDeclaredEncoding` | 文字コードを指定せずInputStreamを渡すXmlPullParserのBOM/宣言検出保証を再検証していた。 | アプリ独自のエンコード処理なし。固有の機能保証は失わない。 |
| `PodcastOpmlTest.emptyLibraryExportsAValidOpmlDocument` | 空ライブラリの書き出しはUIから不可。空タグの直列化はXMLライブラリの保証。 | PodcastAppTest.settingsOpmlAvailabilityAndCallbacksFollowTheLibrary |
| `PodcastOpmlTest.partial assertions / importingAddsSubscriptionsWithoutOverwritingExistingData` | 不正URLはスキーム・構文・認証情報の3種類に絞る。XMLの標準エラー、エスケープ断片確認、Storeテスト中のOPML往復ファイルI/O重複を削除。期待URLを本番関数で生成する自己検証も固定の期待値へ変更。 | OPML往復 / DocumentProvider入出力 / 不正入力の原子性テストは保持 |
| `QueueBackgroundSyncTest.backgroundChecksArePersistedNetworkConstrainedAndCanBeCancelled` | JobSchedulerの設定と取消をRepositoryの購読/更新間隔テストに統合。固定クラス名やOS側の同じIDの上書きを別に検査しない。 | AppNavigationTest.allSubscriptionsAreScheduledAndIntervalChangesAreApplied に統合 |
| `FeedParserTest.xmlCharacterReferencesArePreserved` | XMLライブラリのエンティティ処理だけの独立起動は不要。 | rssIncludesMetadataAudioDurationAndPublicationDate の実ドキュメントに統合 |
| `FeedParserTest.readsWebsiteAndNestedNotesWithoutAccumulatingTheWholeChannel` | Webサイトの抽出はRSSメタデータに統合、段落/入れ子は説明文専用シナリオと重複。 | rssIncludesMetadataAudioDurationAndPublicationDate / EpisodeFeaturesTest.notesKeepParagraphsListItemsAndLinkTargetsWithoutSummaryOverwriting |
| `FeedParserTest.platformTitlesAreCleanedWithoutTrustingSimilarDomains` | 文字列補助関数を直接なぞる検査は主要URLからの解決と重複。類似ドメインの拒否は実解決入口で検査する。 | PodcastDirectoryTest 共有URLの解決 / unrecognizedHtmlPageDoesNotRegisterAnArbitraryPodcast |
| `PodcastScreenshotTest.captureLightScreens / captureDarkScreens / captureSettingsLight / captureSettingsDark` | 110枚の撮影は画素の期待値を判定していないため通常の回帰テストから分離。明暗ごとの長い連続操作を毎回実行するコストを削減。 | ScreenCapture として明示実行する撮影処理へ移動。今回は全画面を実行し変更前後を比較 |
| `QueueMigrationTest / EpisodeFeaturesTest.versionOneDataSurvivesUpgradeToQueues / versionTwoQueuesAndHistorySurviveTheLibraryUpgrade / versionThreeMigrationKeepsSubscriptionsQueuesProgressAndDownloads` | 現行DBから列やテーブルを削って旧版を模倣する構成は、新旧の双方で同じ不具合を持ち込みうる。重複する準備と確認を1シナリオへ統合。 | PodcastMigrationTest が公開済みタグから固定した独立SQLを使用。schema 1/2/3 の全経路、購読・進捗・音声参照・キュー・ルール・お気に入り・履歴移行を検証 |
| `EpisodeFeaturesTest.seekingRestoredIdleAndPausedPlayersPreparesWithoutStartingPlayback` | prepareカウンターと偽プレーヤー自身のposition更新を確認する構成を除去。境界値のcoerceIn自体を試すケースも削除。 | 既存の seekingRestoredIdleAndPausedPlayersPreparesWithoutStartingPlayback を実ExoPlayerの位置・停止状態・準備状態で検証する形へ変更 |
| `PodcastAppTest` の途中画面PNG出力 | 同じ画面は全画面撮影にもあり、期待画像との比較がなく通常テストの保証にならないため削除。 | 文字拡大の2画面出力と明示実行の全画面撮影を保持。 |

## 全ファイルの確認結果

| 監査前ファイル | 監査前メソッド数 | 残すと判断した検出価値／対応 |
| --- | ---: | --- |
| `AppNavigationTest` | 7 | 戻る操作でアプリが終了する、チェック欄以外が押せない、ワンタップ追加が重複する、更新設定が反映されない、完了キューが巻き戻る不具合。 |
| `ArtworkCacheTest` | 1 | アプリが no-store を尊重してしまい、再起動・オフライン時に表紙が失われる不具合。Coil 自体でなく本アプリのキャッシュ設定を実通信で通す。 |
| `EpisodeFeaturesTest` | 9 | チャプターの時刻・リンク破損、説明段落の欠落、未視聴を履歴に混ぜる、停止中シークで再生開始、末尾スキップで2回進む、初回の大量通知、番組をまたぐスキップ設定漏れ。 |
| `EpisodeNavigationTest` | 7 | 再生項目が消えても余白が残る、通知が浮く、エピソードから番組へ移動できない、戻るとスクロールを失う、一覧のボタンが増える不具合。 |
| `FeedParserTest` | 12 | RSS/Atomの音声URL・公開日・ID・番組情報が変わる、転送で二重登録する、不正な入力をフィードとして扱う不具合。 |
| `PlaybackQueueTest` | 10 | 複数所属・順番の喪失、遅れて届く進捗による完了解除、広告の誤振り分け、過去回の勝手な追加、音声の二重保存、再生中音声の取り落とし。 |
| `PlayerDeviceLayoutTest` | 4 | 密度3とシステム余白の組合せで起動直後のパネルが白くなる既報の不具合。座標だけでは検出できないため描画画素も検証。 |
| `PlayerInteractionTest` | 6 | アートワーク上から閉じられない、停止中シークやチャプター移動が別動作になる、長押し操作に到達できない、リンクがただの文字になる、履歴や番組設定の操作が途切れる不具合。 |
| `PlayerPolishTest` | 8 | 上スワイプが指に追従しない、左右スワイプがタブに反映されない、説明の展開や表紙の表示条件が変わる、操作結果が見えない、終了アニメーションやシステムバー復元が壊れる不具合。 |
| `PodcastAppTest` | 23 | 検索経路とURL確認の混同、誤購読、保存/再生の入口消失、通信失敗を検索結果0件と誤表示、再生成時の遷移先喪失、キューの作成・再生・自動追加の入力取り違え、確認せず購読解除する不具合。狭幅・文字拡大も操作で確認。 |
| `PodcastDirectoryTest` | 9 | 共有URLから別番組を登録する、確認候補を直接登録する、相対RSSの解決先を誤る、一時的な転送先を購読元として保存する不具合。各プラットフォームの入力は異なるメタデータ経路を検証。 |
| `PodcastDownloadsTest` | 10 | 音声の二重取得、手動削除後の自動再取得、ローカル音声の見失い、完了時の削除漏れ、手動既読時の誤削除、削除後に再生できない、Wi-Fi限定の漏れ。 |
| `PodcastHttpTest` | 3 | 転送・gzip・文字コードの組合せによる読取不能、大きな実RSSの旧8MiB制限、転送先の不正スキーム受入れ。実ループバックHTTPで確認。 |
| `PodcastLibraryFeaturesTest` | 6 | 大量取り込みによる全件メモリ展開、古い回や説明後半の振り分け漏れ、一覧外の番組の更新順間違い、キュー遷移で保存速度の上書き、通信過多や1番組の失敗による全更新中断。 |
| `PodcastOpmlTest` | 11 | フォルダ内の番組喪失、重複購読・不正URL、外部アプリと交換できない出力、巨大/深すぎる入力による失敗、既存データ上書き、DocumentProvider連携不能、不正ファイルの部分取り込み。 |
| `PodcastScreenshotTest` | 4 | 画像を保存するだけでは自動検出する固有の不具合がない。撮影機能は明示実行として残し、目視または別途の画像比較で利用する。 |
| `PodcastStoreTest` | 3 | 最新フィードから消えた過去回・進捗を更新時に消す、1番組の解除で他番組まで削除する不具合。 |
| `PodcastUxTest` | 9 | 縦スクロールで横操作が誤発火する、一覧の位置・検索語が消える、番組別速度が全体へ漏れる、購読URLの誤表示、開いている古いお気に入りが消える、キューから外す操作が全体の既読になる不具合。 |
| `QueueBackgroundSyncTest` | 1 | Repository側の実シナリオに統合。永続・通信制約・間隔変更・最後の購読解除による取消を確認する。 |
| `QueueMigrationTest` | 2 | 旧schema 1/2の端末データ喪失。schema 3も含め、独立した履歴fixtureによる移行テストに統合。 |

## 個々のメソッドの判定

上の候補表にない保持判定は、ファイル表に記した利用者に影響する不具合を検出するもの。単に「念のため」では残していない。

<details>
<summary>AppNavigationTest（7件）</summary>

| メソッド | 判定 |
| --- | --- |
| `backFromSearchAndPodcastReturnsHomeWithoutFinishingActivity` | 保持（検出価値は上のファイル表） |
| `queueMembershipCanBeToggledByItsLabel` | 保持（検出価値は上のファイル表） |
| `quickAddIsIdempotentAndPersistsItsDefaultQueue` | 保持（検出価値は上のファイル表） |
| `allSubscriptionsAreScheduledAndIntervalChangesAreApplied` | 保持（検出価値は上のファイル表） |
| `playerFollowsTheFingerBeforeSettlingClosed` | 保持（検出価値は上のファイル表） |
| `settingsRowsDispatchWifiIntervalAndDefaultQueue` | 保持（検出価値は上のファイル表） |
| `completedQueueDoesNotRestartItsPreviouslyPlayedPrefix` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>ArtworkCacheTest（1件）</summary>

| メソッド | 判定 |
| --- | --- |
| `artworkRemainsAvailableOfflineEvenWhenTheServerSendsNoStore` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>EpisodeFeaturesTest（9件）</summary>

| メソッド | 判定 |
| --- | --- |
| `podloveAndJsonChaptersRetainOrderLinksAndFractionalTimes` | 保持（検出価値は上のファイル表） |
| `notesKeepParagraphsListItemsAndLinkTargetsWithoutSummaryOverwriting` | 保持（検出価値は上のファイル表） |
| `historyIsRecordedOnlyForListeningAndOldEntriesRemainAccessible` | 保持（検出価値は上のファイル表） |
| `versionThreeMigrationKeepsSubscriptionsQueuesProgressAndDownloads` | 候補表の対応を実施 |
| `seekingRestoredIdleAndPausedPlayersPreparesWithoutStartingPlayback` | 候補表の対応を実施 |
| `skipOptionsPersistIndependentlyAndDoNotOverwriteResumedPositions` | 保持（検出価値は上のファイル表） |
| `endSkipOnlyAdvancesOnceBeforeQueueCompletionIsPublished` | 保持（検出価値は上のファイル表） |
| `notificationOnlySubscriptionSchedulesSyncAndDoesNotAlertInitialBackfill` | 保持（検出価値は上のファイル表） |
| `automaticSkipsFollowTheCurrentPodcastAndRespectPausedOrExplicitPositions` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>EpisodeNavigationTest（7件）</summary>

| メソッド | 判定 |
| --- | --- |
| `withoutAPlayerTheListExtendsToNavigation` | 候補表の対応を実施 |
| `removingThePlayerReclaimsItsHeight` | 保持（検出価値は上のファイル表） |
| `withoutAPlayerMessagesDoNotReserveItsHeight` | 保持（検出価値は上のファイル表） |
| `episodeDetailsOpenThePodcastAndBackRestoresListPosition` | 保持（検出価値は上のファイル表） |
| `playerDetailsCanAlsoOpenThePodcast` | 保持（検出価値は上のファイル表） |
| `queueRowsDoNotOfferToAddAnEpisodeAlreadyInTheQueue` | 候補表の対応を実施 |
| `episodeRowsExposeOnlyThePlayButton` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>FeedParserTest（12件）</summary>

| メソッド | 判定 |
| --- | --- |
| `rssIncludesMetadataAudioDurationAndPublicationDate` | 保持（検出価値は上のファイル表） |
| `xmlCharacterReferencesArePreserved` | 候補表の対応を実施 |
| `atomEnclosuresAndIsoDatesAreSupported` | 保持（検出価値は上のファイル表） |
| `redirectBaseDoesNotChangeEpisodeIdentity` | 保持（検出価値は上のファイル表） |
| `duplicateGuidsAndNonAudioEntriesDoNotPolluteLibrary` | 保持（検出価値は上のファイル表） |
| `externalEntityDeclarationsAreRejected` | 保持（検出価値は上のファイル表） |
| `htmlIsNotMistakenForAFeed` | 保持（検出価値は上のファイル表） |
| `invalidDurationsDoNotCreateNegativeOrInfiniteProgress` | 保持（検出価値は上のファイル表） |
| `appleDirectoryDiscardsEntriesWithoutPublicRssAndDeduplicates` | 保持、PodcastDirectoryTest へ移動（責務を統一） |
| `platformTitlesAreCleanedWithoutTrustingSimilarDomains` | 候補表の対応を実施 |
| `credentialsAndLocalSchemesCannotBeUsedAsSubscriptionUrls` | 保持（検出価値は上のファイル表） |
| `readsWebsiteAndNestedNotesWithoutAccumulatingTheWholeChannel` | 候補表の対応を実施 |

</details>

<details>
<summary>PlaybackQueueTest（10件）</summary>

| メソッド | 判定 |
| --- | --- |
| `oneEpisodeCanBelongToMultipleQueuesWithoutDuplicates` | 保持（検出価値は上のファイル表） |
| `completionEverywhereRemovesAllMembershipsButKeepsHistoryAndAudioReference` | 候補表の対応を実施 |
| `latePlaybackProgressCannotUndoCompletionOrResurrectQueueEntries` | 保持（検出価値は上のファイル表） |
| `newEpisodesAreMatchedOnlyOnceAndExcludedWordsWin` | 保持（検出価値は上のファイル表） |
| `existingBacklogIsAddedOnlyWhenExplicitlyRequested` | 保持（検出価値は上のファイル表） |
| `automaticDownloadsAreDeduplicatedAcrossRulesAndPodcastSettings` | 保持（検出価値は上のファイル表） |
| `includeAllAndAbsentExcludedWordsAreSupported` | 保持（検出価値は上のファイル表） |
| `orderAndRulesSurviveReopeningAndQueueDeletionDoesNotUnsubscribe` | 保持（検出価値は上のファイル表） |
| `activePlaybackKeepsCurrentAudioButDropsOtherCompletedEpisodes` | 保持（検出価値は上のファイル表） |
| `disabledRuleAndManualRemovalDoNotReAddKnownEpisodes` | 候補表の対応を実施 |

</details>

<details>
<summary>PlayerDeviceLayoutTest（4件）</summary>

| メソッド | 判定 |
| --- | --- |
| `restoredPlayerWithGestureInsetsHasVisibleControls` | 候補表の対応を実施 |
| `loadingThenRestoringPlayerAndClosingItDoesNotLeaveAWhitePanel` | 保持（検出価値は上のファイル表） |
| `collapsedControlsStayPaintedWhileInitialAnchorsSettle` | 候補表の対応を実施 |
| `darkThemeAlsoPaintsTheCollapsedControls` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PlayerInteractionTest（6件）</summary>

| メソッド | 判定 |
| --- | --- |
| `downwardSwipeOnArtworkReturnsToTheUnderlyingScreen` | 保持（検出価値は上のファイル表） |
| `pausedSeekAndChapterSelectionDispatchPositionsWithoutTogglingPlayback` | 保持（検出価値は上のファイル表） |
| `episodeRowsOfferDownloadsAndCopyingThroughLongPress` | 保持（検出価値は上のファイル表） |
| `descriptionsExposeRealLinksAndKeepParagraphBreaks` | 保持（検出価値は上のファイル表） |
| `historyShowsListeningOrderAndCanLoadOlderEpisodes` | 保持（検出価値は上のファイル表） |
| `podcastSettingsSaveStartAndEndSkipsAndNotificationScope` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PlayerPolishTest（8件）</summary>

| メソッド | 判定 |
| --- | --- |
| `upwardDragMovesPlayerBeforeTheFingerIsReleased` | 保持（検出価値は上のファイル表） |
| `horizontalSwipesChangePlayerPagesInBothDirections` | 保持（検出価値は上のファイル表） |
| `longDescriptionsCollapseAtFiveLinesAndExpandOnTap` | 保持（検出価値は上のファイル表） |
| `mixedListsShowCoversWhilePodcastListsDoNot` | 保持（検出価値は上のファイル表） |
| `queueAddFeedbackIsVisibleAboveTheExpandedPlayer` | 候補表の対応を実施 |
| `refreshIntervalButtonsHaveVisibleGaps` | 候補表の対応を実施 |
| `completedSheetsRemainMountedUntilTheExitAnimationFinishes` | 保持（検出価値は上のファイル表） |
| `systemBarIconsFollowThePlayerAndRestoreAfterClosing` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastAppTest（23件）</summary>

| メソッド | 判定 |
| --- | --- |
| `emptyLibraryLeadsToSearch` | 保持（検出価値は上のファイル表） |
| `keywordSearchDispatchesTheEnteredQuery` | 保持（検出価値は上のファイル表） |
| `pastingSpotifyShowUrlUsesUrlResolution` | 保持（検出価値は上のファイル表） |
| `searchResultRequiresExplicitSubscription` | 保持（検出価値は上のファイル表） |
| `episodeCanBePlayedAndDownloaded` | 保持（検出価値は上のファイル表） |
| `bufferingPlaybackCanStillBePaused` | 保持（検出価値は上のファイル表） |
| `fullPlayerChangesSpeedAndSkips` | 候補表の対応を実施 |
| `seekSliderChangesPlaybackPosition` | 候補表の対応を実施 |
| `failedSearchRemainsVisibleInsteadOfClaimingNoResults` | 保持（検出価値は上のファイル表） |
| `selectedPodcastSurvivesStateRestoration` | 保持（検出価値は上のファイル表） |
| `libraryShowsLocalSubscriptionsAndMiniPlayer` | 候補表の対応を実施 |
| `narrowScreenSupportsLargeText` | 保持（検出価値は上のファイル表） |
| `librarySupportsDarkMode` | 候補表の対応を実施 |
| `queueCreationAndPlaybackAreAvailableFromTheQueueTab` | 保持（検出価値は上のファイル表） |
| `anEpisodeCanBeAddedToTwoQueuesFromItsDetails` | 候補表の対応を実施 |
| `queueRuleEditorSavesKeywordsAndAutomaticDownload` | 保持（検出価値は上のファイル表） |
| `miniPlayerCentersPlaybackAndKeepsItsTitleOnlyForAccessibility` | 統合先として保持・改名 → `miniAndFullPlayerDispatchTransportAndSpeed` |
| `fullPlayerDetailsAndQueueActionsRemainAccessible` | 保持（検出価値は上のファイル表） |
| `queueOrderingAndRemovalAreAvailableInEpisodeMenu` | 保持（検出価値は上のファイル表） |
| `playerSupportsLargeTextOnNarrowScreen` | 保持（検出価値は上のファイル表） |
| `libraryMenuOpensOpmlImportAndExport` | 統合先として保持・改名 → `settingsOpmlAvailabilityAndCallbacksFollowTheLibrary` |
| `emptyLibraryCanImportButCannotExport` | 候補表の対応を実施 |
| `unsubscribeConfirmationIsAnchoredAtTheBottomAndCanBeCancelled` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastDirectoryTest（9件）</summary>

| メソッド | 判定 |
| --- | --- |
| `appleEpisodeSharedUrlResolvesItsParentShow` | 保持（検出価値は上のファイル表） |
| `spotifyShowPageFindsCandidatesThatRequireConfirmation` | 保持（検出価値は上のファイル表） |
| `spotifyEpisodeMetadataResolvesItsParentSeries` | 保持（検出価値は上のファイル表） |
| `amazonSharedPageFallsBackToConfirmedDirectoryCandidates` | 統合先として保持・改名 → `sharedPlatformTitlesResolveConfirmedDirectoryCandidates` |
| `sharedPageWithAlternateRssUsesThatExactFeed` | 保持（検出価値は上のファイル表） |
| `directRssWithLeadingCommentsIsRecognized` | 保持（検出価値は上のファイル表） |
| `temporaryRedirectDoesNotBecomeTheStoredSubscriptionAddress` | 保持（検出価値は上のファイル表） |
| `unrecognizedHtmlPageDoesNotRegisterAnArbitraryPodcast` | 保持（検出価値は上のファイル表） |
| `platformExclusiveWithoutPublicRssExplainsTheMissingFeed` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastDownloadsTest（10件）</summary>

| メソッド | 判定 |
| --- | --- |
| `restoredPlaybackHasShowMetadataBeforeTheUiLibraryLoads` | 保持（検出価値は上のファイル表） |
| `automaticDownloadsUseOneSharedRequestAndManualRemovalPreventsRedownloading` | 保持（検出価値は上のファイル表） |
| `manuallyMarkedEpisodeLeavesAllQueuesButKeepsDownloadedAudio` | 保持（検出価値は上のファイル表） |
| `duplicateRequestsDoNotStartTwoDownloads` | 候補表の対応を実施 |
| `finishedDownloadUsesTheLocalAudioAndSurvivesReloading` | 保持（検出価値は上のファイル表） |
| `missingFileFallsBackToStreamingInsteadOfClaimingOfflineAvailability` | 保持（検出価値は上のファイル表） |
| `removingDownloadClearsTheFileAndPersistedManagerId` | 候補表の対応を実施 |
| `finishingPlaybackRemovesAudioAndDownloadRecordButKeepsHistoryAndFavorite` | 保持（検出価値は上のファイル表） |
| `finishedLocalAudioCanBeReplayedFromTheNetworkWithoutAnActiveQueue` | 保持（検出価値は上のファイル表） |
| `wifiRestrictionRecreatesOutstandingRequestsAndPreservesCompletedFiles` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastHttpTest（3件）</summary>

| メソッド | 判定 |
| --- | --- |
| `redirectsGzipAndDeclaredCharsetAreHandledTogether` | 保持（検出価値は上のファイル表） |
| `redirectsToLocalFileSchemesAreRejected` | 保持（検出価値は上のファイル表） |
| `largeFeedsStreamPastTheOldEightMegabyteLimit` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastLibraryFeaturesTest（6件）</summary>

| メソッド | 判定 |
| --- | --- |
| `largeCatalogKeepsHistoryOnDiskAndPagesTheUi` | 保持（検出価値は上のファイル表） |
| `backlogRulesSeeOldEpisodesAndTextBeyondThePreview` | 保持（検出価値は上のファイル表） |
| `updateOrderUsesTheEntireCatalogRatherThanOnlyTheVisiblePage` | 保持（検出価値は上のファイル表） |
| `speedOverridesPersistAndFollowAutomaticQueueTransitions` | 保持（検出価値は上のファイル表） |
| `rapidTransitionsDoNotRewriteEitherStoredSpeedScope` | 候補表の対応を実施 |
| `refreshIsBoundedAndOneBadFeedDoesNotAbortTheOtherSubscriptions` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastOpmlTest（11件）</summary>

| メソッド | 判定 |
| --- | --- |
| `importsNestedFoldersAndSkipsRepeatedFeeds` | 保持（検出価値は上のファイル表） |
| `rejectsUnsupportedAndRelativeUrlsButKeepsOtherSubscriptions` | 保持（検出価値は上のファイル表） |
| `exportedUtf8SubscriptionsRoundTripWithXmlEscaping` | 保持（検出価値は上のファイル表） |
| `recognizesUtf16ByteOrderAndDeclaredEncoding` | 候補表の対応を実施 |
| `rejectsMalformedXmlAndOtherDocumentTypes` | 保持（検出価値は上のファイル表） |
| `rejectsDoctypeAndExternalEntities` | 保持（検出価値は上のファイル表） |
| `boundsFileSizeAndXmlDepth` | 保持（検出価値は上のファイル表） |
| `emptyLibraryExportsAValidOpmlDocument` | 候補表の対応を実施 |
| `importingAddsSubscriptionsWithoutOverwritingExistingData` | 候補表の対応を実施 |
| `importsAndExportsThroughDocumentProviderUris` | 保持（検出価値は上のファイル表） |
| `malformedDocumentDoesNotPartiallyImportSubscriptions` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastScreenshotTest（4件）</summary>

| メソッド | 判定 |
| --- | --- |
| `captureLightScreens` | 明示実行の撮影処理へ移動 |
| `captureDarkScreens` | 明示実行の撮影処理へ移動 |
| `captureSettingsLight` | 明示実行の撮影処理へ移動 |
| `captureSettingsDark` | 明示実行の撮影処理へ移動 |

</details>

<details>
<summary>PodcastStoreTest（3件）</summary>

| メソッド | 判定 |
| --- | --- |
| `refreshKeepsHistoryAndOldEpisodesMissingFromTheLatestFeed` | 保持（検出価値は上のファイル表） |
| `subscriptionsHistoryAndDownloadIdsSurviveReopening` | 候補表の対応を実施 |
| `unsubscribeRemovesOnlyThatShowsHistoryAndDownloads` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>PodcastUxTest（9件）</summary>

| メソッド | 判定 |
| --- | --- |
| `horizontalSwipeRevealsFavoriteAndQueueActions` | 保持（検出価値は上のファイル表） |
| `verticalScrollingDoesNotMarkAnEpisodePlayed` | 保持（検出価値は上のファイル表） |
| `returningFromPodcastDetailsRestoresTheGridScrollPosition` | 保持（検出価値は上のファイル表） |
| `searchQuerySurvivesSwitchingTabs` | 保持（検出価値は上のファイル表） |
| `favoriteFilterAndScopedSpeedAreAvailable` | 保持（検出価値は上のファイル表） |
| `podcastSourceShowsTheExactSubscriptionUrl` | 保持（検出価値は上のファイル表） |
| `miniPlayerOpensWithAnUpwardSwipeAndClosesWithADownwardSwipe` | 候補表の対応を実施 |
| `removingAnOldFavoriteDoesNotCloseItsOpenDetails` | 保持（検出価値は上のファイル表） |
| `swipingInAQueueRemovesOnlyThatMembership` | 保持（検出価値は上のファイル表） |

</details>

<details>
<summary>QueueBackgroundSyncTest（1件）</summary>

| メソッド | 判定 |
| --- | --- |
| `backgroundChecksArePersistedNetworkConstrainedAndCanBeCancelled` | 候補表の対応を実施 |

</details>

<details>
<summary>QueueMigrationTest（2件）</summary>

| メソッド | 判定 |
| --- | --- |
| `versionOneDataSurvivesUpgradeToQueues` | 候補表の対応を実施 |
| `versionTwoQueuesAndHistorySurviveTheLibraryUpgrade` | 候補表の対応を実施 |

</details>

## 過剰な内部依存とテストダブルの扱い

- 旧版の移行試験は現行 `PodcastStore` のDDLを使って旧版を作らない。公開済みの v0.2.0 / v0.3.0 / v0.5.0 から固定したSQLを `resources/migrations` に置く。本番スキーマ変更に追従して書き換えない。
- 停止中シークは実ExoPlayerで位置・準備状態・停止状態を確認する。偽のprepare回数・偽positionの自己検証を廃止した。
- 残すPlayerダブルは既知の長さと遷移を与え、アプリ独自の先頭/末尾スキップや完了公開の順番を検証する用途。Media3自体の再生の正しさを主張しない。
- Wi-Fi制限テストのOSリクエスト内部値の読取は残す。Robolectricには実際の回線制御がなく、アプリが設定し忘れるとモバイル通信で取得する現実的な不具合があるため。フィールド名変更時の修正箇所はこの境界テストのみ。
- 高密度パネルの全12初期フレームで描画と位置を検査する。PNG圧縮は最後だけにして、検出力を落とさずI/Oを削減した。
- 外部サービスに接続しない。HTTPとキャッシュはループバックサーバー、入力は公開形式の小さなfixtureを使う。ユーザーの実OPML/RSSは含めない。

## 実行方法と比較

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug

# 明暗の全画面カタログを必要なときだけ生成する
./gradlew testDebugUnitTest -PcaptureScreenshots=true --tests "*ScreenCapture"
```

撮影先は `app/build/reports/screenshots/all-screens`。このコマンドは画像比較を自動の合否判定にするものではない。今回の変更では、変更前に保存した全画面と文字拡大の画像を比較した。実機の描画・音声デバイスは未検証。

- `testDebugUnitTest lintDebug assembleDebug`：118件すべて成功、LintとDebug APKビルドも成功。
- `-PcaptureScreenshots=true`：回帰118件と撮影4件を含む122件が成功。
- 明暗108画面＋文字拡大2画面：変更前の画像と110枚すべて画素一致。
- JUnit XMLに記録されたテスト時間合計：49.25秒 → 37.21秒（約24%短縮）。同じクラウド環境で各1回の観測であり、Gradle起動・コンパイル時間は含めない。
