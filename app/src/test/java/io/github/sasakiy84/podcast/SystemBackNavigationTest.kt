package io.github.sasakiy84.podcast

import androidx.activity.ComponentActivity
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
class SystemBackNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/feed", "番組")
    private val episode =
        Episode("one", show.feedUrl, "第一回", "https://example.org/one.mp3", durationMs = 600_000)
    private val base =
        PodcastUiState(
            library = Library(listOf(show), listOf(episode)),
            playback = PlaybackUi(episode.id, connected = true, durationMs = 600_000),
        )

    @Test
    fun podcastReturnsToLibraryWithRestoredMiniPlayer(): Unit {
        compose.setContent { PodcastApp(base) }
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
        compose.backGesture(cancel = true)
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithContentDescription("一覧に戻る").assertDoesNotExist()
        compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }

    @Test
    fun podcastReturnsAfterPlayerHasBeenClosedAndActivityResumed(): Unit {
        compose.setContent { PodcastApp(base) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("プレーヤーを閉じる").performClick()
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(androidx.lifecycle.Lifecycle.State.RESUMED)
        compose.backGesture()
        compose.onNodeWithContentDescription("一覧に戻る").assertDoesNotExist()
        compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
    }

    @Test
    fun durationArrivingAfterRestorationDoesNotMakeCollapsedPlayerConsumeBack(): Unit {
        val ui =
            androidx.compose.runtime.mutableStateOf(
                base.copy(playback = base.playback.copy(durationMs = 0))
            )
        compose.setContent { PodcastApp(ui.value) }
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.runOnIdle { ui.value = base }
        compose.backGesture()
        compose.onNodeWithContentDescription("一覧に戻る").assertDoesNotExist()
        compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
    }

    @Test
    fun playerBackLeavesTheUnderlyingQueueSelectionIntact(): Unit {
        compose.setContent {
            PodcastApp(
                base.copy(
                    library =
                        base.library.copy(
                            queues = listOf(PlaybackQueue("news", "ニュース", listOf(episode.id)))
                        )
                )
            )
        }
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText("ニュース").performClick()
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("再生速度 1.0×").performClick()
        compose.backGesture()
        compose.onNodeWithContentDescription("プレーヤーを閉じる").assertIsDisplayed()
        compose.onNodeWithContentDescription("その他の操作").performClick()
        compose.onNodeWithText("キューに追加・編集").performClick()
        compose.backGesture()
        compose.onNodeWithContentDescription("プレーヤーを閉じる").assertIsDisplayed()
        compose.backGesture(cancel = true)
        compose.onNodeWithContentDescription("プレーヤーを閉じる").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithContentDescription("プレーヤーを開く").assertIsDisplayed()
        compose.onNodeWithContentDescription("キュー一覧に戻る").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithText("キューを作成").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
    }

    @Test
    fun podcastReturnsWithoutAPlayer(): Unit {
        compose.setContent { PodcastApp(base.copy(playback = PlaybackUi())) }
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.backGesture()
        compose.onNodeWithContentDescription("一覧に戻る").assertDoesNotExist()
        compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
    }

    @Test
    fun tabsReturnToLibraryAndOnlyItsRootDelegatesBackToTheOs(): Unit {
        compose.setContent { PodcastApp(base) }
        for (tab in listOf("エピソード", "キュー", "検索")) {
            compose.onNodeWithContentDescription("タブ: $tab").performClick()
            compose.backGesture(cancel = true)
            compose.onNodeWithContentDescription("タブ: $tab").assertIsSelected()
            compose.backGesture()
            compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
            compose.runOnIdle {
                assertFalse(compose.activity.onBackPressedDispatcher.hasEnabledCallbacks())
            }
        }
    }

    @Test
    fun podcastSheetsAndNestedRuleEditorReturnOneLevelWithoutCommitting(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent {
            PodcastApp(
                base.copy(
                    library = base.library.copy(queues = listOf(PlaybackQueue("news", "ニュース")))
                ),
                actions::add,
            )
        }
        compose.onNodeWithContentDescription(show.title).performClick()
        val openSheets =
            listOf<() -> Unit>(
                { compose.onNodeWithText("購読元").performClick() },
                { compose.onNodeWithText("再生速度 1.0×").performClick() },
                { compose.onNodeWithContentDescription("番組の設定").performClick() },
                { compose.onNodeWithContentDescription("購読を解除").performClick() },
            )
        for (open in openSheets) {
            open()
            compose.onNode(isDialog()).assertExists()
            compose.backGesture(cancel = true)
            compose.onNode(isDialog()).assertExists()
            compose.backGesture()
            compose.onNode(isDialog()).assertDoesNotExist()
            compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
        }
        compose.onNodeWithText("キューへの自動追加").performScrollTo().performClick()
        compose.onNodeWithText("ニュース").performClick()
        compose.onNodeWithText("含む語（カンマ区切り）").assertIsDisplayed()
        compose.backGesture()
        compose.onNode(hasText("キューへの自動追加") and hasAnyAncestor(isDialog())).assertIsDisplayed()
        compose.backGesture()
        compose.onNode(isDialog()).assertDoesNotExist()
        assertTrue(
            actions.none {
                it is PodcastAction.Unsubscribe ||
                    it is PodcastAction.SetPodcastOptions ||
                    it is PodcastAction.SaveQueueRule ||
                    it is PodcastAction.Speed
            }
        )
    }

    @Test
    fun episodeAndMembershipSheetsReturnToTheirSourceQueue(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent {
            PodcastApp(
                base.copy(
                    library =
                        base.library.copy(
                            queues = listOf(PlaybackQueue("news", "ニュース", listOf(episode.id)))
                        )
                ),
                actions::add,
            )
        }
        compose.onNodeWithContentDescription("タブ: キュー").performClick()
        compose.onNodeWithText("ニュース").performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performClick()
        compose.backGesture()
        compose.onNodeWithContentDescription("キュー一覧に戻る").assertIsDisplayed()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performClick()
        compose.onNodeWithContentDescription("キューに追加・編集").performClick()
        compose.backGesture()
        compose.onNode(isDialog()).assertDoesNotExist()
        compose.onNodeWithContentDescription("キュー一覧に戻る").assertIsDisplayed()
        compose.onNodeWithContentDescription("キューのメニュー").performClick()
        compose.onNodeWithText("キューを削除").performClick()
        compose.backGesture()
        compose.onNodeWithContentDescription("キュー一覧に戻る").assertIsDisplayed()
        assertTrue(
            actions.none { it is PodcastAction.DeleteQueue || it is PodcastAction.QueueMembership }
        )
    }

}
