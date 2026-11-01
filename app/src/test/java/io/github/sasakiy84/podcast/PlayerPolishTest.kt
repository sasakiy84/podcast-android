package io.github.sasakiy84.podcast

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.text.TextLayoutResult
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
class PlayerPolishTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/rss", "番組")
    private val episode = Episode("one", show.feedUrl, "第一回", "https://example.org/one.mp3", "説明の本文", durationMs = 600_000, chapters = listOf(EpisodeChapter(0, "導入"), EpisodeChapter(60_000, "次の話")))
    private val base = PodcastUiState(library = Library(listOf(show), listOf(episode)), playback = PlaybackUi(episode.id, connected = true))

    @Test fun upwardDragMovesPlayerBeforeTheFingerIsReleased(): Unit {
        compose.setContent { PodcastApp(base) }
        val mini = compose.onNodeWithContentDescription("プレーヤーを開く")
        val top = mini.fetchSemanticsNode().boundsInRoot.top
        mini.performTouchInput { down(center); moveBy(Offset(0f, -120f), delayMillis = 150) }
        compose.waitForIdle()
        assertTrue(mini.fetchSemanticsNode().boundsInRoot.top < top - 30)
        compose.onNodeWithContentDescription("プレーヤーを閉じる").assertExists()
        mini.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("再生位置").assertIsDisplayed()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription("プレーヤーを開く").assertIsDisplayed()
    }

    @Test fun horizontalSwipesChangePlayerPagesInBothDirections(): Unit {
        compose.setContent { PodcastApp(base) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("プレーヤーのアートワーク").performTouchInput { swipeLeft() }
        compose.onNodeWithText(episode.description).assertIsDisplayed()
        compose.onNodeWithText(episode.description).performTouchInput { swipeLeft() }
        compose.onNodeWithText("次の話").assertIsDisplayed()
        compose.onNodeWithText("次の話").performTouchInput { swipeRight() }
        compose.onNodeWithText(episode.description).assertIsDisplayed()
    }

    @Test fun longDescriptionsCollapseAtFiveLinesAndExpandOnTap(): Unit {
        val text = (1..20).joinToString("\n") { "番組の説明 $it" }
        compose.setContent { PodcastTheme { CollapsibleDescription(text) } }
        fun lines(): Int { val result = mutableListOf<TextLayoutResult>(); compose.onNodeWithText(text).performSemanticsAction(SemanticsActions.GetTextLayoutResult) { it(result) }; return result.single().lineCount }
        assertEquals(5, lines())
        compose.onNodeWithText(text).performClick()
        assertEquals(20, lines())
        compose.onNodeWithText("折りたたむ").performClick()
        assertEquals(5, lines())
    }

    @Test fun mixedListsShowCoversWhilePodcastListsDoNot(): Unit {
        compose.setContent { PodcastApp(base) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("番組画像: ${show.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("タブ: ポッドキャスト").performClick()
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.onNodeWithContentDescription("番組画像: ${show.title}").assertDoesNotExist()
    }

    @Test fun queueAddFeedbackIsVisibleAboveTheExpandedPlayer(): Unit {
        val state = mutableStateOf(base)
        compose.setContent { PodcastApp(state.value, { if (it is PodcastAction.QuickQueue) state.value = state.value.copy(library = state.value.library.copy(queues = listOf(PlaybackQueue("default", "あとで聴く", listOf(episode.id)))), message = "あとで聴くに追加しました") }) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("デフォルトキューに追加").performClick()
        compose.onNodeWithText("あとで聴くに追加しました").assertIsDisplayed()
        assertEquals(listOf(episode.id), state.value.library.queues.single().episodeIds)
    }

    @Test fun refreshIntervalButtonsHaveVisibleGaps(): Unit {
        compose.setContent { SettingsPage(base, {}, {}, {}, {}) }
        val first = compose.onNodeWithText("1h").fetchSemanticsNode().boundsInRoot
        val second = compose.onNodeWithText("3h").fetchSemanticsNode().boundsInRoot
        assertTrue(second.left > first.right)
    }
    @Test fun completedSheetsRemainMountedUntilTheExitAnimationFinishes(): Unit {
        val visible = mutableStateOf(true)
        compose.setContent { PodcastTheme { if (visible.value) QueueMembershipDialog(episode, base.library, {}, { visible.value = false }) } }
        compose.onNodeWithText("完了").assertIsDisplayed()
        compose.mainClock.autoAdvance = false
        compose.onNodeWithText("完了").performClick()
        compose.mainClock.advanceTimeByFrame()
        assertTrue(visible.value)
        compose.mainClock.advanceTimeBy(1500)
        compose.mainClock.autoAdvance = true
        compose.waitForIdle()
        assertFalse(visible.value)
    }

}
