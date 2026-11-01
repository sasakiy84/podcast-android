package io.github.sasakiy84.podcast

import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastStoreTest {
    private lateinit var store: PodcastStore
    private val podcast = Podcast("https://example.org/rss", "番組", "配信者")
    private val episode = Episode("one", podcast.feedUrl, "最初の回", "https://example.org/one.mp3")

    @Before fun open(): Unit { RuntimeEnvironment.getApplication().deleteDatabase("store-test.db"); store = PodcastStore(RuntimeEnvironment.getApplication(), "store-test.db") }
    @After fun close(): Unit { store.close(); RuntimeEnvironment.getApplication().deleteDatabase("store-test.db") }

    @Test
    fun refreshKeepsHistoryAndOldEpisodesMissingFromTheLatestFeed(): Unit {
        store.saveFeed(PodcastFeed(podcast, listOf(episode, episode.copy(id = "old"))))
        store.saveProgress(episode.id, ListeningProgress(123_000, 600_000))
        store.saveFeed(PodcastFeed(podcast.copy(title = "新しい番組名"), listOf(episode.copy(title = "更新された回"))))
        assertEquals(2, store.library().episodes.size)
        assertEquals(123_000, store.progress(episode.id).positionMs)
        assertEquals("更新された回", store.episode(episode.id)?.title)
    }

    @Test
    fun unsubscribeRemovesOnlyThatShowsHistoryAndDownloads(): Unit {
        store.saveFeed(PodcastFeed(podcast, listOf(episode)))
        store.saveFeed(PodcastFeed(podcast.copy(feedUrl = "https://example.org/other"), listOf(episode.copy(id = "other", feedUrl = "https://example.org/other"))))
        store.saveProgress(episode.id, ListeningProgress(10))
        store.saveDownload(episode.id, 42)
        store.unsubscribe(podcast.feedUrl)
        assertEquals(listOf("other"), store.library().episodes.map { it.id })
        assertFalse(store.library().progress.containsKey(episode.id))
        assertTrue(store.downloadIds().isEmpty())
    }
}
