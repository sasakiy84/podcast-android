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
class PlaybackQueueTest {
    private lateinit var store: PodcastStore
    private val show = Podcast("https://example.org/feed", "Tech news")
    private val first = Episode("one", show.feedUrl, "AI news", "https://example.org/one.mp3", "Weekly Python report")
    private val second = first.copy(id = "two", title = "Kotlin news")

    @Before fun open(): Unit {
        RuntimeEnvironment.getApplication().deleteDatabase("queues-test.db")
        store = PodcastStore(RuntimeEnvironment.getApplication(), "queues-test.db")
        store.saveFeed(PodcastFeed(show, listOf(first, second)))
        store.saveQueue("news", "ニュース")
        store.saveQueue("tech", "技術")
    }
    @After fun close(): Unit { store.close(); RuntimeEnvironment.getApplication().deleteDatabase("queues-test.db") }

    @Test fun oneEpisodeCanBelongToMultipleQueuesWithoutDuplicates(): Unit {
        assertTrue(store.addToQueue("news", first.id)); assertTrue(store.addToQueue("tech", first.id))
        assertFalse(store.addToQueue("news", first.id))
        assertEquals(listOf("one"), store.queueEpisodeIds("news")); assertEquals(listOf("one"), store.queueEpisodeIds("tech"))
    }

    @Test fun completionEverywhereRemovesAllMembershipsButKeepsHistoryAndAudioReference(): Unit {
        store.addToQueue("news", first.id); store.addToQueue("tech", first.id)
        store.saveDownload(first.id, 99)
        store.saveProgress(first.id, ListeningProgress(1000, 1000, true))
        assertTrue(store.queueEpisodeIds("news").isEmpty()); assertTrue(store.queueEpisodeIds("tech").isEmpty())
        assertTrue(store.progress(first.id).completed); assertNotNull(store.episode(first.id))
        assertEquals(99L, store.downloadIds()[first.id])
    }

    @Test fun latePlaybackProgressCannotUndoCompletionOrResurrectQueueEntries(): Unit {
        store.addToQueue("news", first.id)
        store.saveProgress(first.id, ListeningProgress(100, 100, true))
        store.saveProgress(first.id, ListeningProgress(90, 100, false))
        assertTrue(store.progress(first.id).completed)
        assertEquals(100L, store.progress(first.id).positionMs)
        assertFalse(store.addToQueue("news", first.id))
        store.saveProgress(first.id, ListeningProgress(), resetCompletion = true)
        assertTrue(store.addToQueue("news", first.id))
    }

    @Test fun newEpisodesAreMatchedOnlyOnceAndExcludedWordsWin(): Unit {
        store.saveRule(QueueRule("tech", show.feedUrl, "AI,Python", "広告"), false)
        store.saveFeed(PodcastFeed(show, listOf(first, second, first.copy(id = "new", title = "ＡＩ最新ニュース"), first.copy(id = "ad", title = "AI広告"))))
        assertEquals(listOf("new"), store.queueEpisodeIds("tech"))
        store.removeFromQueue("tech", "new")
        store.saveFeed(PodcastFeed(show, listOf(first.copy(id = "new", title = "AI更新版"))))
        assertTrue(store.queueEpisodeIds("tech").isEmpty())
    }

    @Test fun existingBacklogIsAddedOnlyWhenExplicitlyRequested(): Unit {
        val rule = QueueRule("news", show.feedUrl, "news", autoDownload = true)
        store.saveRule(rule, false)
        assertTrue(store.queueEpisodeIds("news").isEmpty())
        store.saveProgress(second.id, ListeningProgress(completed = true))
        store.saveRule(rule, true)
        assertEquals(listOf(first.id), store.queueEpisodeIds("news"))
        assertEquals(listOf(first.id), store.automaticDownloadIds())
    }

    @Test fun automaticDownloadsAreDeduplicatedAcrossRulesAndPodcastSettings(): Unit {
        store.setAutoDownload(show.feedUrl, true)
        store.saveRule(QueueRule("news", show.feedUrl, autoDownload = true), false)
        store.saveRule(QueueRule("tech", show.feedUrl, autoDownload = true), false)
        store.saveFeed(PodcastFeed(show, listOf(first.copy(id = "new"))))
        store.close(); store = PodcastStore(RuntimeEnvironment.getApplication(), "queues-test.db")
        assertEquals(listOf("new"), store.automaticDownloadIds())
        assertEquals(listOf("new"), store.queueEpisodeIds("news")); assertEquals(listOf("new"), store.queueEpisodeIds("tech"))
        store.saveProgress("new", ListeningProgress(completed = true))
        assertTrue(store.automaticDownloadIds().isEmpty())
    }

    @Test fun includeAllAndAbsentExcludedWordsAreSupported(): Unit {
        assertTrue(QueueRule("tech", show.feedUrl, "ai,python", matchAll = true).matches(first))
        assertFalse(QueueRule("tech", show.feedUrl, "ai,kotlin", matchAll = true).matches(first))
        assertTrue(QueueRule("tech", show.feedUrl, excludeWords = "広告,再放送").matches(first))
        assertFalse(QueueRule("tech", show.feedUrl, excludeWords = "ＰＹＴＨＯＮ").matches(first))
        assertFalse(QueueRule("tech", show.feedUrl, enabled = false).matches(first))
        assertFalse(QueueRule("tech", "https://example.org/other").matches(first))
    }

    @Test fun orderAndRulesSurviveReopeningAndQueueDeletionDoesNotUnsubscribe(): Unit {
        store.addToQueue("news", first.id); store.addToQueue("news", second.id)
        store.moveInQueue("news", second.id, -1)
        store.saveRule(QueueRule("news", show.feedUrl, "news"), false)
        store.close(); store = PodcastStore(RuntimeEnvironment.getApplication(), "queues-test.db")
        assertEquals(listOf(second.id, first.id), store.queueEpisodeIds("news"))
        assertEquals("news", store.rules().single().includeWords)
        store.saveQueue("news", "朝のニュース")
        assertEquals(listOf(second.id, first.id), store.queueEpisodeIds("news"))
        store.deleteQueue("news")
        assertTrue(store.rules().isEmpty()); assertEquals(1, store.library().podcasts.size); assertEquals(2, store.library().episodes.size)
    }

    @Test fun activePlaybackKeepsCurrentAudioButDropsOtherCompletedEpisodes(): Unit {
        val queue = PlaybackQueue("news", "ニュース", listOf(first.id, second.id))
        val library = Library(episodes = listOf(first, second), progress = mapOf(first.id to ListeningProgress(completed = true)))
        assertEquals(listOf(second.id), queuePlaybackIds(queue, second.id, library))
        assertEquals(listOf(second.id), queuePlaybackIds(queue.copy(episodeIds = emptyList()), second.id, library))
        assertTrue(queuePlaybackIds(queue.copy(episodeIds = emptyList()), first.id, library).isEmpty())
    }

    @Test fun disabledRuleAndManualRemovalDoNotReAddKnownEpisodes(): Unit {
        store.saveRule(QueueRule("news", show.feedUrl, enabled = false), false)
        store.saveFeed(PodcastFeed(show, listOf(first.copy(id = "new"))))
        assertTrue(store.queueEpisodeIds("news").isEmpty())
        store.saveRule(QueueRule("news", show.feedUrl), false)
        store.saveFeed(PodcastFeed(show, listOf(first.copy(id = "new"))))
        assertTrue(store.queueEpisodeIds("news").isEmpty())
    }
}
