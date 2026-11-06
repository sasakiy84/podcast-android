package io.github.sasakiy84.podcast

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class PodcastUxTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/rss", "朝の番組", website = "https://example.org/")
    private val episode = Episode("one", show.feedUrl, "朝のエピソード", "https://example.org/one.mp3", "説明", 100, 600_000)
    private val extra = episode.copy(id = "two", title = "技術のエピソード")
    private val state = PodcastUiState(library = Library(listOf(show), listOf(episode, extra)), playback = PlaybackUi(episode.id, true, connected = true))

    @Test fun horizontalSwipeRevealsFavoriteAndQueueActions(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        val row = compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}")
        row.performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNode(hasText("お気に入り") and hasAnyAncestor(hasContentDescription("エピソード操作: ${episode.title}"))).performClick()
        assertEquals(PodcastAction.Favorite(episode.id, true), actions.last())
        row.performTouchInput { swipeRight() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithContentDescription("キューに追加: ${episode.title}").performClick()
        assertEquals(PodcastAction.QuickQueue(episode.id), actions.last())
    }

    @Test fun episodeListStartsWithDownloadsAndKeepsTheChosenFilterAcrossTabs(): Unit {
        val ui = state.copy(library = state.library.copy(downloads = mapOf(episode.id to EpisodeDownload(1, DownloadStatus.Complete))))
        compose.setContent { PodcastApp(ui) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("ダウンロード").assertIsSelected()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("エピソード詳細: ${extra.title}").assertDoesNotExist()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${extra.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").assertIsSelected()
        compose.onNodeWithContentDescription("エピソード詳細: ${extra.title}").assertIsDisplayed()
    }

    @Test fun verticalScrollingDoesNotMarkAnEpisodePlayed(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val episodes = (1..30).map { episode.copy(id = "$it", title = "回$it") }
        compose.setContent { PodcastApp(state.copy(library = state.library.copy(episodes = episodes)), actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        compose.onNodeWithContentDescription("エピソード詳細: 回1").performTouchInput { swipeUp() }
        assertTrue(actions.none { it is PodcastAction.MarkPlayed || it is PodcastAction.Favorite })
        compose.onNodeWithContentDescription("キューに追加: 回1").assertDoesNotExist()
    }

    @Test fun returningFromPodcastDetailsRestoresTheGridScrollPosition(): Unit {
        val shows = (0..49).map { show.copy(feedUrl = "https://example.org/$it", title = "番組%02d".format(it)) }
        compose.setContent { PodcastApp(state.copy(library = Library(shows))) }
        compose.onNode(hasScrollToIndexAction()).performScrollToIndex(30)
        compose.onNodeWithContentDescription("番組30").performClick()
        compose.onNodeWithContentDescription("一覧に戻る").performClick()
        compose.onNodeWithContentDescription("番組30").assertIsDisplayed()
        compose.onNodeWithContentDescription("番組00").assertDoesNotExist()
    }

    @Test fun searchQuerySurvivesSwitchingTabs(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("番組名・キーワード").performTextInput("テクノロジー")
        compose.onNodeWithContentDescription("タブ: ポッドキャスト").performClick()
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("テクノロジー").assertIsDisplayed()
    }

    @Test fun favoriteFilterAndScopedSpeedAreAvailable(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state.copy(library = state.library.copy(favorites = setOf(episode.id))), actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("お気に入り").performScrollTo().performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("エピソード詳細: ${extra.title}").assertDoesNotExist()
        compose.onNodeWithContentDescription("タブ: ポッドキャスト").performClick()
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.onNodeWithText("再生速度 1.0×").performClick()
        compose.onNodeWithText("この番組").assertIsSelected()
        compose.onNodeWithText("1.5×").performClick()
        assertEquals(PodcastAction.Speed(1.5f, show.feedUrl), actions.last())
    }

    @Test fun podcastSourceShowsTheExactSubscriptionUrl(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.onNodeWithText("購読元").performClick()
        compose.onNodeWithText(show.feedUrl).assertIsDisplayed()
        compose.onNodeWithText("番組のWebサイト").assertIsDisplayed()
        compose.onNodeWithText("URL をコピー").performClick()
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(show.feedUrl, clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test fun removingAnOldFavoriteDoesNotCloseItsOpenDetails(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val ui = androidx.compose.runtime.mutableStateOf(state.copy(library = Library(listOf(show), listOf(episode), favorites = setOf(episode.id))))
        compose.setContent { PodcastApp(ui.value, { event ->
            actions += event
            if (event is PodcastAction.Favorite) ui.value = ui.value.copy(library = ui.value.library.copy(episodes = emptyList(), favorites = emptySet()))
        }) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performClick()
        compose.onNodeWithContentDescription("お気に入りを解除").performClick()
        compose.onNodeWithText(episode.description).assertIsDisplayed()
        compose.onNodeWithContentDescription("お気に入りに追加").assertIsDisplayed()
    }

    @Test fun swipingInAQueueRemovesOnlyThatMembership(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val queues = listOf(PlaybackQueue("news", "ニュース", listOf(episode.id)), PlaybackQueue("other", "技術", listOf(episode.id)))
        compose.setContent { PodcastApp(state.copy(library = state.library.copy(queues = queues)), actions::add) }
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText("ニュース").performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performTouchInput { swipeLeft() }
        compose.mainClock.advanceTimeBy(200)
        compose.onNodeWithText("外す").performClick()
        assertEquals(PodcastAction.QueueMembership("news", episode.id, false), actions.last())
        assertTrue(actions.none { it is PodcastAction.MarkPlayed })
    }

}
