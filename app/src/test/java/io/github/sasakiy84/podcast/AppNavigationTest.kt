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
import org.robolectric.RuntimeEnvironment
import kotlinx.coroutines.runBlocking

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], qualifiers = "ja-w360dp-h800dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class AppNavigationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()
    private val show = Podcast("https://example.org/rss", "番組")
    private val episode = Episode("one", show.feedUrl, "第一回", "https://example.org/a.mp3")
    @Test fun backFromSearchAndPodcastReturnsHomeWithoutFinishingActivity(): Unit {
        compose.setContent { PodcastApp(PodcastUiState(library = Library(listOf(show)))) }
        compose.onNodeWithContentDescription("タブ: 検索").performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(show.title).performClick()
        compose.runOnIdle { compose.activity.onBackPressedDispatcher.onBackPressed() }
        compose.onNodeWithContentDescription(show.title).assertIsDisplayed()
        assertFalse(compose.activity.isFinishing)
    }
    @Test fun queueMembershipCanBeToggledByItsLabel(): Unit {
        val events = mutableListOf<PodcastAction>()
        compose.setContent { PodcastTheme { QueueMembershipDialog(episode, Library(queues = listOf(PlaybackQueue("q", "技術"))), events::add, {}) } }
        compose.onNodeWithText("技術").performClick()
        assertEquals(PodcastAction.QueueMembership("q", "one", true), events.last())
    }
    @Test fun quickAddIsIdempotentAndPersistsItsDefaultQueue(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("podcasts.db")
        context.getSharedPreferences("playback", 0).edit().clear().commit()
        val repository = PodcastRepository(context)
        try {
            repository.store.saveFeed(PodcastFeed(show, listOf(episode)))
            repository.quickQueue(episode.id); repository.quickQueue(episode.id)
            assertEquals(listOf("one"), repository.store.queues().single().episodeIds)
            repository.store.saveQueue("tech", "技術"); repository.settings.setDefaultQueue("tech")
            repository.quickQueue(episode.id)
            assertEquals(listOf("one"), repository.store.queueEpisodeIds("tech"))
            assertEquals("tech", PlaybackPreferences(context.getSharedPreferences("playback", 0)).defaultQueue)
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }
    @Test fun allSubscriptionsAreScheduledAndIntervalChangesAreApplied(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("podcasts.db")
        val repository = PodcastRepository(context)
        try {
            repository.store.saveFeed(PodcastFeed(show, listOf(episode)))
            repository.settings.setRefreshHours(6); repository.configureBackgroundSync()
            val job = context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.single()
            assertEquals(6 * 3600_000L, job.intervalMillis)
            assertTrue(job.isPersisted)
            assertTrue(job.requiredNetwork?.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET) == true)
            repository.settings.setRefreshHours(12); repository.configureBackgroundSync()
            assertEquals(12 * 3600_000L, context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.single().intervalMillis)
            repository.unsubscribe(show)
            assertTrue(context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.isEmpty())
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }
    @Test fun playerFollowsTheFingerBeforeSettlingClosed(): Unit {
        compose.setContent { PodcastApp(PodcastUiState(library = Library(listOf(show), listOf(episode)), playback = PlaybackUi(episode.id, connected = true))) }
        compose.onNodeWithContentDescription("プレーヤーを開く").performClick()
        val header = compose.onNodeWithContentDescription("プレーヤーを閉じる")
        val start = header.fetchSemanticsNode().boundsInRoot.top
        header.performTouchInput { down(center); moveBy(androidx.compose.ui.geometry.Offset(0f, 120f), delayMillis = 150) }
        compose.waitForIdle()
        assertTrue(header.fetchSemanticsNode().boundsInRoot.top > start + 30)
        header.performTouchInput { up() }
        compose.mainClock.advanceTimeBy(1000)
        compose.onNodeWithContentDescription("プレーヤーを開く").assertIsDisplayed()
    }
    @Test fun settingsRowsDispatchWifiIntervalAndDefaultQueue(): Unit {
        val events = mutableListOf<PodcastAction>()
        compose.setContent { SettingsPage(PodcastUiState(library = Library(queues = listOf(PlaybackQueue("tech", "技術")))), events::add, {}, {}, {}) }
        compose.onNodeWithText("Wi-Fi のみでダウンロード").performClick()
        assertEquals(PodcastAction.WifiOnly(true), events.last())
        compose.onNodeWithText("6h").performClick()
        assertEquals(PodcastAction.RefreshInterval(6), events.last())
        compose.onNodeWithText("技術").performClick()
        assertEquals(PodcastAction.DefaultQueue("tech"), events.last())
    }

    @Test fun completedQueueDoesNotRestartItsPreviouslyPlayedPrefix(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("podcasts.db")
        val repository = PodcastRepository(context)
        repository.preferences.edit().putString("active_queue", "q").commit()
        val item = androidx.media3.common.MediaItem.Builder().setMediaId("one").build()
        val player = java.lang.reflect.Proxy.newProxyInstance(androidx.media3.common.Player::class.java.classLoader, arrayOf(androidx.media3.common.Player::class.java)) { _, method, _ -> when (method.name) {
            "getCurrentMediaItem" -> item
            "isPlaying" -> false
            "getMediaItemCount" -> 0
            "setMediaItems" -> throw AssertionError("Completed queue restarted")
            else -> null
        } } as androidx.media3.common.Player
        try {
            synchronizePlaybackQueue(player, repository, Library(episodes = listOf(episode), queues = listOf(PlaybackQueue("q", "キュー")), progress = mapOf("one" to ListeningProgress(100, 100, true))))
            assertFalse(repository.preferences.contains("active_queue"))
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }

}
