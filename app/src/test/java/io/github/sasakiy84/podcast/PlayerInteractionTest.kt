package io.github.sasakiy84.podcast

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.semantics.SemanticsProperties
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
class PlayerInteractionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/rss", "番組")
    private val episode = Episode("one", show.feedUrl, "タイトル", "https://example.org/one.mp3", "段落1\n\n段落2 https://example.org/notes", durationMs = 600_000, chapters = listOf(EpisodeChapter(0, "はじめに"), EpisodeChapter(222_000, "次の話題")))
    private val state = PodcastUiState(library = Library(listOf(show), listOf(episode)), playback = PlaybackUi(episode.id, false, durationMs = episode.durationMs, connected = true))

    @Test fun downwardSwipeOnArtworkReturnsToTheUnderlyingScreen(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("プレーヤーのアートワーク").performTouchInput { swipe(center, center + Offset(0f, 220f), 400) }
        compose.onNodeWithContentDescription("プレーヤーを開く").assertIsDisplayed()
        compose.onNodeWithContentDescription("プレーヤーを閉じる").assertDoesNotExist()
    }

    @Test fun pausedSeekAndChapterSelectionDispatchPositionsWithoutTogglingPlayback(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("再生位置").performTouchInput { click(Offset(width * .6f, height / 2f)) }
        assertTrue(actions.any { it is PodcastAction.Seek && it.positionMs in 300_000..400_000 })
        compose.onNodeWithText("チャプター").performClick()
        compose.onNodeWithText("次の話題").performClick()
        assertEquals(PodcastAction.PlayChapter(episode, 222_000), actions.last())
        assertFalse(actions.any { it == PodcastAction.TogglePlayback })
    }

    @Test fun episodeRowsDownloadDirectlyAndLongPressCopiesTheTitle(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("ダウンロード: ${episode.title}").performClick()
        assertEquals(PodcastAction.Download(episode), actions.last())
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performTouchInput { longClick() }
        val clipboard = compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(episode.title, clipboard.primaryClip?.getItemAt(0)?.text.toString())
    }

    @Test fun descriptionsExposeRealLinksAndKeepParagraphBreaks(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").performClick()
        val text = compose.onNodeWithText(episode.description).fetchSemanticsNode().config[SemanticsProperties.Text].single()
        assertTrue(text.text.contains("\n\n"))
        assertEquals("https://example.org/notes", (text.getLinkAnnotations(0, text.length).single().item as androidx.compose.ui.text.LinkAnnotation.Url).url)
    }

    @Test fun historyShowsListeningOrderAndCanLoadOlderEpisodes(): Unit {
        val older = episode.copy(id = "old", title = "前に聴いた回")
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state.copy(library = state.library.copy(episodes = listOf(episode, older), historyIds = listOf(older.id), hasMoreHistory = true)), actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithTag("episode-filters").performScrollToNode(hasText("再生履歴"))
        compose.onNodeWithText("再生履歴").performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${older.title}").assertIsDisplayed()
        compose.onNodeWithContentDescription("エピソード詳細: ${episode.title}").assertDoesNotExist()
        compose.onNodeWithText("さらに表示").performClick()
        assertEquals(PodcastAction.LoadMoreHistory, actions.last())
    }

    @Test fun podcastSettingsSaveStartAndEndSkipsAndNotificationScope(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.onNodeWithContentDescription("番組の設定").performClick()
        compose.onNodeWithText("最初をスキップ（秒）").performTextReplacement("45")
        compose.onNodeWithText("最後をスキップ（秒）").performTextReplacement("30")
        compose.onNodeWithText("保存").performScrollTo().performClick()
        assertEquals(PodcastAction.SetPodcastOptions(show.feedUrl, PodcastOptions(false, 45, 30)), actions.last())
    }
}
