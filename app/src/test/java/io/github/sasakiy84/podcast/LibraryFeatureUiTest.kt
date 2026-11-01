package io.github.sasakiy84.podcast

import android.content.Intent
import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class LibraryFeatureUiTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val events = mutableListOf<PodcastAction>()
    private val show = Podcast("https://example.org/feed", "番組")

    private fun episode(id: Int) =
        Episode(
            "$id",
            show.feedUrl,
            "エピソード$id",
            "https://example.org/$id.mp3",
            published = id.toLong(),
            durationMs = 600_000,
        )

    private fun withModel(
        episodes: List<Episode>,
        block: (PodcastsViewModel, PodcastRepository) -> Unit,
    ): Unit {
        val app = RuntimeEnvironment.getApplication() as PodcastApplication
        Shadows.shadowOf(app)
            .declareComponentUnbindable(
                android.content.ComponentName(app, PlaybackService::class.java)
            )
        app.deleteDatabase("podcasts.db")
        app.getSharedPreferences("playback", 0).edit().clear().commit()
        val repo = app.repository
        repo.store.saveFeed(PodcastFeed(show, episodes))
        repo.store.saveQueue("a", "ニュース")
        repo.store.saveQueue("b", "技術")
        episodes.takeLast(3).forEach { repo.store.addToQueue("a", it.id) }
        runBlocking { repo.load() }
        val store = ViewModelStore()
        val model =
            ViewModelProvider(
                object : ViewModelStoreOwner {
                    override val viewModelStore = store
                },
                ViewModelProvider.AndroidViewModelFactory(app),
            )[PodcastsViewModel::class.java]
        try {
            compose.setContent {
                val state by model.state.collectAsState()
                PodcastApp(
                    state,
                    {
                        events += it
                        model.dispatch(it)
                    },
                )
            }
            awaitState { model.state.value.message?.contains("プレーヤー") == true }
            compose.runOnIdle { model.dispatch(PodcastAction.ClearMessage) }
            block(model, repo)
        } finally {
            compose.runOnIdle { store.clear() }
            runBlocking { repo.load() }
            repo.store.close()
            app.deleteDatabase("podcasts.db")
        }
    }

    @Test
    fun archiveSearchAndUndoUseTheRealViewModelAndRestoreEveryQueue(): Unit {
        val all =
            (1..1005).map {
                episode(it).let { e ->
                    if (it == 1) e.copy(description = "x".repeat(700) + "探索キーワード") else e
                }
            }
        withModel(all) { model, _ ->
            assertFalse(model.state.value.library.episodes.any { it.id == "1" })
            compose.onNodeWithContentDescription("タブ: エピソード").performClick()
            compose.onNodeWithTag("episodes-list").performScrollToIndex(19)
            compose.onNodeWithContentDescription("エピソードを検索").performClick()
            compose.onNodeWithText("タイトル・説明を検索").performTextInput("探索")
            compose.waitForIdle()
            compose.runOnIdle {
                Shadows.shadowOf(android.os.Looper.getMainLooper())
                    .idleFor(350, java.util.concurrent.TimeUnit.MILLISECONDS)
            }
            try {
                awaitState {
                    model.state.value.episodeSearch.result.episodes.map { it.id } == listOf("1")
                }
            } catch (e: Throwable) {
                println(
                    "Search state: ${model.state.value.episodeSearch}; events=${events.takeLast(8)}"
                )
                throw e
            }
            compose.onNodeWithContentDescription("エピソード詳細: エピソード1").performClick()
            compose.onNodeWithContentDescription("キューに追加・編集").performClick()
            compose.onNodeWithText("ニュース").performClick()
            compose.onNodeWithText("技術").performClick()
            awaitState { model.state.value.library.queues.all { "1" in it.episodeIds } }
            compose.onNodeWithText("完了").performClick()
            screenshot("local-search")
            val before = model.state.value.library.queues.associate { it.id to it.episodeIds }
            compose.onNodeWithContentDescription("エピソード詳細: エピソード1").performTouchInput {
                longClick()
            }
            compose.onNodeWithText("再生済みにする").performClick()
            awaitState {
                model.state.value.undo != null &&
                    model.state.value.library.progress["1"]?.completed == true &&
                    model.state.value.library.queues.none { "1" in it.episodeIds }
            }
            compose.onNodeWithText("元に戻す").assertIsDisplayed()
            screenshot("undo")
            compose.onNodeWithText("元に戻す").performClick()
            awaitState {
                model.state.value.undo == null &&
                    model.state.value.library.progress["1"]?.completed != true &&
                    model.state.value.library.queues.associate { it.id to it.episodeIds } == before
            }
            compose.backGesture()
            compose.onNodeWithText("タイトル・説明を検索").assertDoesNotExist()
            compose.onNodeWithContentDescription("タブ: エピソード").assertIsSelected()
            compose.onNodeWithContentDescription("エピソード詳細: エピソード986").assertIsDisplayed()
            compose.onNodeWithContentDescription("エピソード詳細: エピソード1005").assertDoesNotExist()
        }
    }

    @Test
    fun draggingAQueuePersistsOneMoveAndBackCancelsTheNextDrag(): Unit =
        withModel((1..3).map(::episode)) { model, _ ->
            compose.onNodeWithContentDescription("タブ: キュー").performClick()
            compose.onNodeWithText("ニュース").performClick()
            val first = compose.onNodeWithContentDescription("ドラッグして並べ替え: エピソード1")
            val second = compose.onNodeWithContentDescription("ドラッグして並べ替え: エピソード2")
            val distance =
                second.fetchSemanticsNode().boundsInRoot.center.y -
                    first.fetchSemanticsNode().boundsInRoot.center.y
            first.performTouchInput { swipe(center, center + Offset(0f, distance + 32), 600) }
            try {
                awaitState {
                    model.state.value.library.queues.first { it.id == "a" }.episodeIds ==
                        listOf("2", "1", "3")
                }
            } catch (e: Throwable) {
                println(
                    "Drag events=${events.takeLast(8)}, queues=${model.state.value.library.queues}, distance=$distance"
                )
                throw e
            }
            screenshot("queue-reordered")
            first.performTouchInput {
                down(center)
                moveBy(Offset(0f, -distance - 32), delayMillis = 300)
            }
            compose.backGesture()
            first.performTouchInput { up() }
            assertEquals(
                listOf("2", "1", "3"),
                model.state.value.library.queues.first { it.id == "a" }.episodeIds,
            )
            compose.onNodeWithContentDescription("キュー一覧に戻る").assertIsDisplayed()
        }

    @Test
    fun sharingFreezesTheOptionalPositionAndSupportsCanonicalOrAudioLinks(): Unit {
        val ep = episode(1).copy(webUrl = "https://example.org/episodes/one")
        val base =
            PodcastUiState(
                library = Library(listOf(show), listOf(ep)),
                playback =
                    PlaybackUi(ep.id, connected = true, positionMs = 61_234, durationMs = 600_000),
            )
        val state = mutableStateOf(base)
        compose.setContent { PodcastApp(state.value) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        compose.onNodeWithContentDescription("エピソードを共有").performClick()
        compose.runOnIdle {
            state.value =
                state.value.copy(playback = state.value.playback.copy(positionMs = 90_000))
        }
        compose.onNodeWithText("再生位置を含める（1:01）").performClick()
        screenshot("share-position")
        compose.onNodeWithText("他のアプリへ共有").performClick()
        val chooser = Shadows.shadowOf(compose.activity).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, chooser.action)
        val send = chooser.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)!!
        assertEquals("text/plain", send.type)
        val text = send.getStringExtra(Intent.EXTRA_TEXT)!!
        assertTrue(text.contains(ep.title))
        assertTrue(text.contains(ep.webUrl))
        assertTrue(text.contains("61秒"))
        assertFalse(text.contains("90秒"))
        compose.onNodeWithText("本文をコピー").performClick()
        val clipboard =
            compose.activity.getSystemService(android.content.ClipboardManager::class.java)
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.onNodeWithContentDescription("エピソードを共有").performClick()
        compose.backGesture(cancel = true)
        compose.onNodeWithText("共有・コピー").assertIsDisplayed()
        compose.backGesture()
        compose.onNodeWithContentDescription("プレーヤーを閉じる").assertIsDisplayed()
        compose.runOnIdle {
            state.value =
                base.copy(
                    library = base.library.copy(episodes = listOf(ep.copy(webUrl = ""))),
                    playback = PlaybackUi(),
                )
        }
        compose.onNodeWithContentDescription("タブ: エピソード").performClick()
        compose.onNodeWithContentDescription("エピソード詳細: ${ep.title}").performTouchInput {
            longClick()
        }
        compose.onNodeWithText("共有・コピー").performClick()
        compose.onNodeWithText("再生位置を含める", substring = true).assertDoesNotExist()
        compose.onNodeWithText("リンクをコピー").performClick()
        assertEquals(ep.audioUrl, clipboard.primaryClip!!.getItemAt(0).text.toString())
        compose.runOnIdle {
            state.value =
                state.value.copy(library = state.value.library.copy(episodes = emptyList()))
        }
        compose.onNodeWithText("共有・コピー").assertIsDisplayed()
        compose.onNodeWithText("タイトルをコピー").performClick()
        assertEquals(ep.title, clipboard.primaryClip!!.getItemAt(0).text.toString())
    }

    @Test
    fun managementShowsStorageFailuresAndWifiWaitAndRetriesTheSelectedFeed(): Unit {
        val ep = episode(1)
        val actions = mutableListOf<PodcastAction>()
        val download = EpisodeDownload(1, DownloadStatus.Waiting, reason = "Wi-Fi 接続待ち")
        val state =
            PodcastUiState(
                library =
                    Library(
                        listOf(show),
                        listOf(ep),
                        feedUpdates = mapOf(show.feedUrl to FeedUpdateStatus(1000, 500, "HTTP 503")),
                    ),
                management =
                    LibraryManagement(
                        StorageUsage(1024L * 1024, 2048, 1024L * 1024 * 50),
                        listOf(ManagedDownload(ep, show.title, download)),
                    ),
            )
        compose.setContent { SettingsPage(state, actions::add, {}, {}, {}) }
        compose.onNodeWithText("ストレージと更新").performScrollTo().performClick()
        compose.onNodeWithText("Wi-Fi 接続待ち").assertIsDisplayed()
        screenshot("management-storage")
        compose.onNodeWithText("更新").performClick()
        compose.onNodeWithText("HTTP 503").assertIsDisplayed()
        screenshot("management-updates")
        compose.onNodeWithContentDescription("番組を再取得: ${show.title}").performClick()
        assertEquals(
            listOf(PodcastAction.RetryFeed(show.feedUrl)),
            actions.filterIsInstance<PodcastAction.RetryFeed>(),
        )
        compose.backGesture()
        compose.onNodeWithText("OPML をインポート").assertExists()
    }

    private fun awaitState(condition: () -> Boolean): Unit {
        compose.waitForIdle()
        compose.waitUntil(5000) {
            Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            condition()
        }
        compose.waitForIdle()
    }

    private fun screenshot(name: String): Unit {
        compose.waitForIdle()
        compose.mainClock.advanceTimeBy(500)
        compose.waitForIdle()
        compose.runOnIdle {
            captureScreen(
                compose.activity,
                java.io.File(System.getProperty("podcast.screenshotDir"), "features/$name.png"),
            )
        }
    }
}
