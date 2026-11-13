package io.github.sasakiy84.podcast

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
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
class SearchNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val candidates =
        (1..40).map { PodcastCandidate(Podcast("https://example.org/$it/rss", "番組$it", "配信者$it")) }
    private val candidate = candidates[19]
    private val show = candidate.podcast.copy(description = "番組の詳しい説明")
    private val episode = Episode("episode", show.feedUrl, "最新エピソード", "https://example.org/one.mp3")
    private val preview =
        PodcastPreview(show.feedUrl, PodcastFeed(show, listOf(episode)), 1, loading = false)

    @Test
    fun browsingAndBackPreserveSearchAndNeverSubscribe(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val restore = StateRestorationTester(compose)
        restore.setContent {
            PodcastApp(
                PodcastUiState(results = candidates, searched = true, podcastPreview = preview),
                actions::add,
            )
        }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("番組名・キーワード").performTextInput("技術")
        compose.onNodeWithTag("podcast-search-results").performScrollToIndex(19)
        compose.onNodeWithContentDescription("番組を開く: ${show.title}").performClick()
        compose.onNodeWithText(show.description).assertIsDisplayed()
        compose.onNodeWithText(episode.title).assertIsDisplayed()
        compose.onNodeWithContentDescription("番組の設定").assertDoesNotExist()
        assertTrue(actions.filterIsInstance<PodcastAction.Subscribe>().isEmpty())
        assertTrue(PodcastAction.PreviewPodcast(show.feedUrl) in actions)
        restore.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("podcast-preview").assertIsDisplayed()
        compose.backGesture(cancel = true)
        compose.onNodeWithTag("podcast-preview").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithText("技術").assertIsDisplayed()
        compose.onNodeWithContentDescription("番組を開く: ${show.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("番組を開く: 番組1").assertDoesNotExist()
    }

    @Test
    fun registeringFromResultsOpensDetailsOnlyAfterSuccessAndRegisteredRowsReopenThem(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val ui =
            mutableStateOf(
                PodcastUiState(
                    results = listOf(candidate),
                    searched = true,
                    podcastPreview = preview,
                )
            )
        compose.setContent { PodcastApp(ui.value, actions::add) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithText("登録する").performClick()
        assertEquals(
            listOf(PodcastAction.Subscribe(candidate)),
            actions.filterIsInstance<PodcastAction.Subscribe>(),
        )
        compose.runOnIdle { ui.value = ui.value.copy(busyFeeds = setOf(show.feedUrl)) }
        compose.onNodeWithText("登録中…").assertIsNotEnabled()
        // Failure leaves a retryable preview instead of pretending it is subscribed.
        compose.runOnIdle {
            ui.value = ui.value.copy(busyFeeds = emptySet(), message = "登録できませんでした。")
        }
        compose.onNodeWithText("登録する").assertIsEnabled().performClick()
        compose.onNodeWithContentDescription("番組の設定").assertDoesNotExist()
        compose.runOnIdle {
            ui.value =
                ui.value.copy(library = Library(listOf(show), listOf(episode)), message = null)
        }
        compose.onNodeWithContentDescription("番組の設定").assertIsDisplayed()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").assertIsDisplayed()
        compose.onNodeWithTag("podcast-preview").assertDoesNotExist()
        val subscriptions = actions.filterIsInstance<PodcastAction.Subscribe>().size
        compose.backGesture()
        compose.onNodeWithText("開く").performClick()
        compose.onNodeWithContentDescription("番組の設定").assertIsDisplayed()
        assertEquals(subscriptions, actions.filterIsInstance<PodcastAction.Subscribe>().size)
    }

    @Test
    fun previewFailureCanBeRetriedWithoutLosingRegistrationOrSourceActions(): Unit {
        val actions = mutableListOf<PodcastAction>()
        val failed = PodcastPreview(show.feedUrl, loading = false, error = "配信元に接続できませんでした。")
        val ui =
            mutableStateOf(
                PodcastUiState(
                    results = listOf(candidate),
                    searched = true,
                    podcastPreview = failed,
                )
            )
        compose.setContent { PodcastApp(ui.value, actions::add) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.onNodeWithContentDescription("番組を開く: ${show.title}").performClick()
        compose.onNodeWithText(failed.error!!).assertIsDisplayed()
        compose.onNodeWithText("購読元").assertIsDisplayed()
        compose.onNodeWithText("登録する").assertIsEnabled()
        compose.onNodeWithText("再試行").performClick()
        assertTrue(PodcastAction.PreviewPodcast(show.feedUrl, retry = true) in actions)
        compose.runOnIdle { ui.value = ui.value.copy(podcastPreview = preview) }
        compose.onNodeWithText(episode.title).assertIsDisplayed()
        compose.onNodeWithText("再試行").assertDoesNotExist()
    }
}
