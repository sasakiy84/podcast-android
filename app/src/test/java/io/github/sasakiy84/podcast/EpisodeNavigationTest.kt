package io.github.sasakiy84.podcast

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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
class EpisodeNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/rss", "朝の番組")
    private val episodes = (1..40).map { Episode("$it", show.feedUrl, "エピソード$it", "https://example.org/$it.mp3") }
    private val state = PodcastUiState(library = Library(listOf(show), episodes))

    @Test fun removingThePlayerReclaimsItsHeight(): Unit {
        val ui = mutableStateOf(state.copy(playback = PlaybackUi("1", connected = true)))
        compose.setContent { PodcastApp(ui.value) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        val before = compose.onNodeWithTag("episodes-list").fetchSemanticsNode().boundsInRoot.bottom
        compose.runOnIdle { ui.value = state }
        compose.onNodeWithText("すべて").assertIsSelected()
        val after = compose.onNodeWithTag("episodes-list").fetchSemanticsNode().boundsInRoot.bottom
        val nav = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue(after - before >= 48f)
        assertEquals(nav.top, after, 1f)
        compose.onNodeWithContentDescription("プレーヤーを開く").assertDoesNotExist()
    }

    @Test fun queueSelectionSurvivesRemovingItsPlayerAfterClearing(): Unit {
        val queue = PlaybackQueue("q", "あとで聴く", listOf("1"))
        val ui = mutableStateOf(state.copy(library = state.library.copy(queues = listOf(queue)), playback = PlaybackUi("1", connected = true)))
        compose.setContent { PodcastApp(ui.value) }
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText(queue.name).performClick()
        compose.runOnIdle { ui.value = ui.value.copy(library = ui.value.library.copy(queues = listOf(queue.copy(episodeIds = emptyList()))), playback = PlaybackUi()) }
        compose.onNodeWithContentDescription("キュー一覧に戻る").assertIsDisplayed()
        compose.onNodeWithText("エピソードはまだありません").assertIsDisplayed()
        compose.onNodeWithText("キューとローカルデータを削除").assertIsNotEnabled()
    }

    @Test fun withoutAPlayerMessagesDoNotReserveItsHeight(): Unit {
        compose.setContent { PodcastApp(state.copy(message = "更新しました")) }
        val message = compose.onNodeWithText("更新しました").fetchSemanticsNode().boundsInRoot
        // The text has Snackbar's standard bottom padding; the removed player adds none.
        val nav = compose.onNodeWithTag("bottom-navigation").fetchSemanticsNode().boundsInRoot
        assertTrue("message gap=${nav.top - message.bottom}", nav.top - message.bottom < 40f)
    }
    @Test fun episodeDetailsOpenThePodcastAndBackRestoresListPosition(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        compose.onNodeWithTag("episodes-list").performScrollToIndex(19)
        compose.onNodeWithContentDescription("エピソード詳細: エピソード20").performClick()
        compose.onNodeWithContentDescription("番組を開く: ${show.title}").performClick()
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
        compose.onNodeWithText("購読元").assertIsDisplayed()
        compose.backGesture(cancel = true)
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithContentDescription("エピソード詳細: エピソード20").assertIsDisplayed()
        compose.onNodeWithContentDescription("エピソード詳細: エピソード1").assertDoesNotExist()
    }

    @Test fun playerDetailsCanAlsoOpenThePodcast(): Unit {
        compose.setContent { PodcastApp(state.copy(playback = PlaybackUi("1", connected = true))) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithText("詳細").performClick()
        compose.onNodeWithContentDescription("番組を開く: ${show.title}").performClick()
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
        compose.onNodeWithContentDescription("プレーヤーを開く").assertIsDisplayed()
    }

    @Test fun episodeRowsExposeOnlyThePlayButton(): Unit {
        compose.setContent { PodcastApp(state.copy(library = state.library.copy(queues = listOf(PlaybackQueue("q", "あとで聴く", listOf("1")))))) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithText("すべて").performScrollTo().performClick()
        val row = hasContentDescription("エピソード詳細: エピソード1")
        compose.onAllNodes(hasAnyAncestor(row) and hasClickAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText("あとで聴く").performClick()
        compose.onAllNodes(hasAnyAncestor(row) and hasClickAction()).assertCountEquals(1)
        compose.onNodeWithContentDescription("エピソード詳細: エピソード1").performTouchInput { longClick() }
        compose.onNodeWithText("ダウンロード").assertIsDisplayed()
        compose.onNodeWithText("キューから外す").assertIsDisplayed()
        compose.onNodeWithText("ダウンロード").performClick()
        compose.onNodeWithContentDescription("キューのメニュー").performClick()
        compose.onNodeWithText("名前を変更").assertIsDisplayed()
        compose.onNodeWithText("キューを削除").assertIsDisplayed()
    }

}
