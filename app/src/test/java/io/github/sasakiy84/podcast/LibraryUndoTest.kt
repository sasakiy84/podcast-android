package io.github.sasakiy84.podcast

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class LibraryUndoTest {
    private val show = Podcast("https://example.org/feed", "番組")
    private val episodes =
        (1..4).map { Episode("$it", show.feedUrl, "回$it", "https://example.org/$it.mp3") }

    private fun withStore(block: (PodcastStore) -> Unit): Unit {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("undo.db")
        try {
            PodcastStore(context, "undo.db").use { store ->
                store.saveFeed(PodcastFeed(show, episodes))
                store.saveQueue("a", "A")
                store.saveQueue("b", "B")
                episodes.take(3).forEach { store.addToQueue("a", it.id) }
                store.addToQueue("b", "2")
                store.addToQueue("b", "3")
                block(store)
            }
        } finally {
            context.deleteDatabase("undo.db")
        }
    }

    @Test
    fun undoPlayedRestoresEveryQueuePositionWithoutRemovingLaterAdditions(): Unit =
        withStore { store ->
            store.saveProgress("2", ListeningProgress(123, 1000), listened = true)
            store.setFavorite("2", true)
            store.saveRule(QueueRule("b", show.feedUrl, autoDownload = true), true)
            val before = store.progress("2")
            val history = store.library().historyIds
            val undo = requireNotNull(store.markPlayedWithUndo("2", true))
            assertTrue(store.queues().none { "2" in it.episodeIds })
            store.addToQueue("a", "4")
            assertTrue(store.undo(undo))
            assertEquals(listOf("1", "2", "3", "4"), store.queueEpisodeIds("a"))
            assertEquals(listOf("2", "3", "1", "4"), store.queueEpisodeIds("b"))
            assertEquals(before, store.progress("2"))
            assertEquals(history, store.library().historyIds)
            assertTrue("2" in store.library().favorites)
            assertTrue("2" in store.automaticDownloadIds())
            val next = requireNotNull(store.markPlayedWithUndo("2", true))
            org.robolectric.shadows.ShadowSystemClock.advanceBy(java.time.Duration.ofSeconds(1))
            store.saveProgress("2", next.after, listened = true)
            assertFalse(store.undo(next))
            assertTrue(store.progress("2").completed)
        }

    @Test
    fun removingFromOneQueueCanBeUndoneAroundConcurrentEditsAndCompletedEpisodesStayRemoved():
        Unit = withStore { store ->
        val undo = requireNotNull(store.removeFromQueueWithUndo("a", "2"))
        assertEquals(listOf("1", "3"), store.queueEpisodeIds("a"))
        assertEquals(listOf("2", "3"), store.queueEpisodeIds("b"))
        store.addToQueue("a", "4")
        store.placeQueueEpisode("a", "4", "3")
        assertTrue(store.undo(undo))
        assertEquals(listOf("1", "4", "2", "3"), store.queueEpisodeIds("a"))
        val again = requireNotNull(store.removeFromQueueWithUndo("a", "2"))
        store.saveProgress("2", ListeningProgress(completed = true))
        assertFalse(store.undo(again))
        assertTrue(store.queues().none { "2" in it.episodeIds })
        val noProgress = requireNotNull(store.markPlayedWithUndo("1", true))
        assertTrue(store.undo(noProgress))
        assertFalse(store.library().progress.containsKey("1"))
    }
}
