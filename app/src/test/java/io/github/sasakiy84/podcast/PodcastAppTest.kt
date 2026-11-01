package io.github.sasakiy84.podcast

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PodcastAppTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/feed", "朝のノート", "暮らしのラジオ", artwork = artwork("MORNING\nNOTES", 0xFF123954.toInt()), description = "日々の小さな発見を、ゆっくり話す番組です。")
    private val second = Podcast("https://example.org/second", "深呼吸ラジオ", "音の便り", artwork = artwork("DEEP\nBREATH", 0xFFE8AD60.toInt()))
    private val episode = Episode("episode-one", show.feedUrl, "静かな朝のつづき", "https://example.org/one.mp3", "コーヒーを淹れながら、今日の予定を考えます。", 1_700_000_000_000, 1_800_000)
    private val library = Library(listOf(show, second), listOf(episode))
    private val state = PodcastUiState(library = library, playback = PlaybackUi(episode.id, true, positionMs = 120_000, durationMs = 1_800_000, connected = true))

    @Test
    fun emptyLibraryLeadsToSearch(): Unit {
        compose.setContent { PodcastApp() }
        compose.onNodeWithText("番組を探す").performClick()
        compose.onNodeWithText("番組名・キーワード").assertIsDisplayed()
    }

    @Test
    fun keywordSearchDispatchesTheEnteredQuery(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(onAction = actions::add) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("番組名・キーワード").performTextInput("歴史")
        compose.onNodeWithText("検索する").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Search("歴史"), actions.last())
    }

    @Test
    fun pastingSpotifyShowUrlUsesUrlResolution(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val url = "https://open.spotify.com/show/abc123"
        compose.setContent { PodcastApp(onAction = actions::add) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("番組名・キーワード").performTextInput(url)
        compose.onNodeWithText("番組を確認").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Resolve(url), actions.last())
    }

    @Test
    fun searchResultRequiresExplicitSubscription(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val candidate = PodcastCandidate(show, "Spotify の番組候補 · Apple Podcasts")
        compose.setContent { PodcastApp(PodcastUiState(results = listOf(candidate), searched = true), actions::add) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("番組名・配信者を確認して登録してください。").assertIsDisplayed()
        compose.onNodeWithText("登録する").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Subscribe(candidate), actions.last())
    }

    @Test
    fun episodeCanBePlayedAndDownloaded(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(PodcastUiState(library = library, playback = PlaybackUi(connected = true)), actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("再生: ${episode.title}").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Play(episode), actions.last())
        screenshot("episodes")
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performClick()
        compose.onNode(hasText("ダウンロード") and hasAnyAncestor(isDialog())).performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Download(episode), actions.last())
    }

    @Test
    fun bufferingPlaybackCanStillBePaused(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state.copy(playback = state.playback.copy(buffering = true)), actions::add) }
        compose.onNodeWithContentDescription("一時停止").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.TogglePlayback, actions.last())
    }

    @Test
    fun fullPlayerChangesSpeedAndSkips(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("15秒戻る").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Skip(-15_000), actions.last())
        compose.onNodeWithContentDescription("30秒進む").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Skip(30_000), actions.last())
        compose.onNodeWithContentDescription("再生速度 1.0×").performClick()
        compose.onNodeWithText("1.5×").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Speed(1.5f), actions.last())
        screenshot("player")
    }

    @Test
    fun seekSliderChangesPlaybackPosition(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("再生位置").performSemanticsAction(androidx.compose.ui.semantics.SemanticsActions.SetProgress) { it(600_000f) }
        assertEquals(PodcastAction.Seek(600_000), actions.last())
    }

    @Test
    fun failedSearchRemainsVisibleInsteadOfClaimingNoResults(): Unit {
        compose.setContent { PodcastApp(PodcastUiState(searched = true, searchError = "接続できませんでした。")) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("検索できませんでした").assertIsDisplayed()
        compose.onNodeWithText("番組が見つかりませんでした").assertDoesNotExist()
    }

    @Test
    fun selectedPodcastSurvivesStateRestoration(): Unit {
        val restoration = StateRestorationTester(compose)
        restoration.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription(second.title).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
    }

    @Test
    fun libraryShowsLocalSubscriptionsAndMiniPlayer(): Unit {
        val shows = listOf(show, second) + listOf("WORLD NEWS", "TECH TABLE", "ENGLISH", "WEEKLY", "DESIGN", "SCIENCE", "DAILY").mapIndexed { index, name ->
            Podcast("https://example.org/$index", name, artwork = artwork(name.replace(" ", "\n"), listOf(0xFFAA262C.toInt(), 0xFF07304A.toInt(), 0xFF40855E.toInt())[index % 3]))
        }
        compose.setContent { PodcastApp(state.copy(library = library.copy(podcasts = shows))) }
        compose.onNodeWithContentDescription(second.title).assertIsDisplayed()
        compose.onNodeWithText(episode.title).assertDoesNotExist()
        screenshot("library")
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h640dp")
    fun narrowScreenSupportsLargeText(): Unit {
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription("タブ: 検索").assertIsDisplayed()
        compose.onNodeWithText(episode.title).assertDoesNotExist()
        screenshot("library-large-text")
    }

    @Test
    @Config(qualifiers = "ja-w360dp-h800dp-night")
    fun librarySupportsDarkMode(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription(second.title).assertIsDisplayed()
        screenshot("library-dark")
    }

    @Test
    fun queueCreationAndPlaybackAreAvailableFromTheQueueTab(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val withQueues = state.copy(library = library.copy(queues = listOf(PlaybackQueue("news", "ニュース", listOf(episode.id)))))
        compose.setContent { PodcastApp(withQueues, actions::add) }
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText("キューを作成").performClick()
        compose.onNodeWithText("キュー名").performTextInput("英語学習")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        assertEquals("英語学習", (actions.last() as PodcastAction.SaveQueue).name)
        compose.onNodeWithText("ニュース").performClick()
        compose.onNodeWithContentDescription("キューを再生").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.PlayQueue("news"), actions.last())
        screenshot("queue")
    }

    @Test
    fun anEpisodeCanBeAddedToTwoQueuesFromItsDetails(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val withQueues = state.copy(library = library.copy(queues = listOf(PlaybackQueue("news", "ニュース"), PlaybackQueue("english", "英語学習"))))
        compose.setContent { PodcastApp(withQueues, actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performClick()
        compose.onNodeWithContentDescription("キューに追加・編集").performClick()
        compose.onNodeWithText("キャンセル").assertDoesNotExist()
        compose.onAllNodes(isToggleable())[0].performClick()
        compose.onAllNodes(isToggleable())[1].performClick()
        compose.waitForIdle()
        assertEquals(listOf(PodcastAction.QueueMembership("news", episode.id, true), PodcastAction.QueueMembership("english", episode.id, true)), actions.filterIsInstance<PodcastAction.QueueMembership>())
    }

    @Test
    fun queueRuleEditorSavesKeywordsAndAutomaticDownload(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state.copy(library = library.copy(queues = listOf(PlaybackQueue("tech", "技術")))) , actions::add) }
        compose.onNodeWithContentDescription(library.podcasts.first().title).performClick()
        compose.onNodeWithText("キューへの自動追加").performScrollTo().performClick()
        compose.onNodeWithText("技術").performClick()
        compose.onNodeWithText("含む語（カンマ区切り）").performTextInput("AI,Python")
        compose.onNodeWithText("除外する語（カンマ区切り）").performScrollTo().performTextInput("広告")
        compose.onNodeWithText("一致した回を自動ダウンロード").performScrollTo().performClick()
        compose.onNodeWithText("既存の未再生にも追加").performScrollTo().performClick()
        screenshot("queue-rule")
        compose.onNodeWithText("保存").performClick()
        compose.waitForIdle()
        val event = actions.last() as PodcastAction.SaveQueueRule
        assertEquals("AI,Python", event.rule.includeWords)
        assertEquals("広告", event.rule.excludeWords)
        org.junit.Assert.assertTrue(event.rule.autoDownload)
        org.junit.Assert.assertTrue(event.applyExisting)
    }

    @Test
    fun miniPlayerCentersPlaybackAndKeepsItsTitleOnlyForAccessibility(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithText(episode.title).assertDoesNotExist()
        val left = compose.onNodeWithContentDescription("プレーヤーを開く").fetchSemanticsNode().boundsInRoot.center.x
        val right = compose.onNodeWithContentDescription("キューを開く").fetchSemanticsNode().boundsInRoot.center.x
        val play = compose.onNodeWithContentDescription("一時停止").fetchSemanticsNode().boundsInRoot.center.x
        assertEquals((left + right) / 2f, play, 0.5f)
        compose.onNodeWithContentDescription("15秒戻る（ミニプレーヤー）").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Skip(-15_000), actions.last())
        compose.onNodeWithContentDescription("30秒スキップ").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Skip(30_000), actions.last())
    }

    @Test
    fun fullPlayerDetailsAndQueueActionsRemainAccessible(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithText("詳細").performClick()
        compose.onNodeWithText(episode.description).assertIsDisplayed()
        compose.onNodeWithContentDescription("ダウンロード").assertDoesNotExist()
        compose.onNodeWithContentDescription("その他の操作").performClick()
        compose.onNodeWithText("キューに追加・編集").performClick()
        compose.onNodeWithText("新しいキュー名").assertIsDisplayed()
    }

    @Test
    fun queueOrderingAndRemovalAreAvailableInEpisodeMenu(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val extra = episode.copy(id = "two", title = "午後のノート")
        val queues = listOf(PlaybackQueue("news", "ニュース", listOf(episode.id, extra.id)))
        compose.setContent { PodcastApp(state.copy(library = library.copy(episodes = listOf(episode, extra), queues = queues)), actions::add) }
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText("ニュース").performClick()
        compose.onNodeWithText("番組の自動追加を設定").assertDoesNotExist()
        compose.onNodeWithContentDescription("キュー内の操作: ${episode.title}").performClick()
        compose.onNodeWithText("下へ移動").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.MoveQueueEpisode("news", episode.id, 1), actions.last())
        compose.onNodeWithContentDescription("キュー内の操作: ${episode.title}").performClick()
        compose.onNodeWithText("キューから外す").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.QueueMembership("news", episode.id, false), actions.last())
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h640dp-night")
    fun playerSupportsLargeTextOnNarrowScreen(): Unit {
        val actions = mutableListOf<PodcastAction>()
        org.robolectric.RuntimeEnvironment.setFontScale(1.5f)
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.waitUntil(5_000) { compose.onNodeWithContentDescription("再生速度 1.0×").isDisplayed() }
        compose.onNodeWithContentDescription("再生速度 1.0×").assertIsDisplayed()
        compose.onNodeWithContentDescription("30秒進む").performScrollTo().performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Skip(30_000), actions.last())
        screenshot("player-large-text")
    }

    @Test
    fun libraryMenuOpensOpmlImportAndExport(): Unit {
        var imports = 0; var exports = 0
        compose.setContent { SettingsPage(state, {}, {}, { imports++ }, { exports++ }) }
        compose.onNodeWithText("OPML をインポート").performClick()
        compose.onNodeWithText("OPML をエクスポート").performClick()
        compose.waitForIdle()
        assertEquals(1, imports); assertEquals(1, exports)
    }

    @Test
    fun emptyLibraryCanImportButCannotExport(): Unit {
        compose.setContent { SettingsPage(PodcastUiState(), {}, {}, {}, {}) }
        compose.onNodeWithText("OPML をインポート").assertIsEnabled()
        compose.onNodeWithText("OPML をエクスポート").assertIsNotEnabled()
    }

    @Test
    fun unsubscribeConfirmationIsAnchoredAtTheBottomAndCanBeCancelled(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription(second.title).performClick()
        compose.onNodeWithContentDescription("購読を解除").performClick()
        val title = compose.onNodeWithText("購読を解除しますか？")
        compose.waitUntil(5_000) { title.isDisplayed() }
        compose.mainClock.advanceTimeBy(500)
        org.junit.Assert.assertTrue(title.fetchSemanticsNode().boundsInRoot.top > 400f)
        compose.onNodeWithText("キャンセル").performClick()
        org.junit.Assert.assertTrue(actions.none { it is PodcastAction.Unsubscribe })
        compose.onNodeWithContentDescription("購読を解除").performClick()
        compose.onNodeWithText("購読を解除").performClick()
        compose.waitForIdle()
        assertEquals(PodcastAction.Unsubscribe(second), actions.last())
    }

    private fun artwork(title: String, color: Int): String {
        val file = File(requireNotNull(System.getProperty("podcast.screenshotDir")), "fixtures/${title.hashCode()}.png")
        check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
        val bitmap = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        canvas.drawColor(color)
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply { this.color = android.graphics.Color.WHITE; textSize = 52f; typeface = android.graphics.Typeface.DEFAULT_BOLD }
        title.split('\n').forEachIndexed { i, line -> canvas.drawText(line, 30f, 150f + i * 62f, paint) }
        paint.alpha = 90
        paint.strokeWidth = 3f
        canvas.drawLine(30f, 320f, 370f, 320f, paint)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return file.toURI().toString()
    }

    private fun screenshot(name: String): Unit {
        val directory = File(requireNotNull(System.getProperty("podcast.screenshotDir")))
        check(directory.mkdirs() || directory.isDirectory)
        compose.runOnIdle {
            val view = org.robolectric.shadows.ShadowDialog.getLatestDialog()?.takeIf { it.isShowing }?.window?.decorView ?: compose.activity.window.decorView
            val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
            view.draw(Canvas(bitmap))
            File(directory, "$name.png").outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) }
        }
    }
}
