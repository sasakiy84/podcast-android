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
    private val show = Podcast("https://example.org/feed", "朝のノート", "暮らしのラジオ", description = "日々の小さな発見を、ゆっくり話す番組です。")
    private val second = Podcast("https://example.org/second", "深呼吸ラジオ", "音の便り")
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
        assertEquals(PodcastAction.Subscribe(candidate), actions.last())
    }

    @Test
    fun episodeCanBePlayedAndDownloaded(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(PodcastUiState(library = library, playback = PlaybackUi(connected = true)), actions::add) }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("再生: ${episode.title}").performClick()
        assertEquals(PodcastAction.Play(episode), actions.last())
        compose.onNodeWithText("保存する").performClick()
        assertEquals(PodcastAction.Download(episode), actions.last())
        screenshot("episodes")
    }

    @Test
    fun bufferingPlaybackCanStillBePaused(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state.copy(playback = state.playback.copy(buffering = true)), actions::add) }
        compose.onNodeWithContentDescription("一時停止").performClick()
        assertEquals(PodcastAction.TogglePlayback, actions.last())
    }

    @Test
    fun fullPlayerChangesSpeedAndSkips(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithText(episode.title).performClick()
        compose.onNodeWithText("−15秒").performClick()
        assertEquals(PodcastAction.Skip(-15_000), actions.last())
        compose.onNodeWithText("+30秒").performClick()
        assertEquals(PodcastAction.Skip(30_000), actions.last())
        compose.onNodeWithText("再生速度 1.0×").performClick()
        compose.onNodeWithText("1.5×").performClick()
        assertEquals(PodcastAction.Speed(1.5f), actions.last())
        screenshot("player")
    }

    @Test
    fun seekSliderChangesPlaybackPosition(): Unit {
        val actions = mutableListOf<PodcastAction>()
        compose.setContent { PodcastApp(state, actions::add) }
        compose.onNodeWithText(episode.title).performClick()
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
        compose.onNodeWithText(second.title).performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithContentDescription("一覧に戻る").assertIsDisplayed()
    }

    @Test
    fun libraryShowsLocalSubscriptionsAndMiniPlayer(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithText(second.title).assertIsDisplayed()
        compose.onNodeWithText(episode.title).assertIsDisplayed()
        screenshot("library")
    }

    @Test
    @Config(qualifiers = "ja-w320dp-h640dp")
    fun narrowScreenSupportsLargeText(): Unit {
        compose.setContent {
            val density = androidx.compose.ui.platform.LocalDensity.current.density
            androidx.compose.runtime.CompositionLocalProvider(
                androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density, 1.5f),
            ) { PodcastApp(state) }
        }
        compose.onNodeWithContentDescription("タブ: 検索").assertIsDisplayed()
        compose.onNodeWithText(episode.title).assertIsDisplayed()
        screenshot("library-large-text")
    }

    @Test
    @Config(qualifiers = "ja-w360dp-h800dp-night")
    fun librarySupportsDarkMode(): Unit {
        compose.setContent { PodcastApp(state) }
        compose.onNodeWithText(second.title).assertIsDisplayed()
        screenshot("library-dark")
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
