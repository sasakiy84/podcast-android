package io.github.sasakiy84.podcast

import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastLibraryFeaturesTest {
    private val show = Podcast("https://example.org/rss", "番組", website = "https://example.org/")
    private fun episode(i: Int, feed: String = show.feedUrl): Episode = Episode("$feed:$i", feed, "第${i}回", "https://example.org/$i.mp3", "説明".repeat(800), i.toLong(), 60_000)

    @Test fun largeCatalogKeepsHistoryOnDiskAndPagesTheUi(): Unit {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("large-library.db")
        PodcastStore(context, "large-library.db").use { store ->
            val episodes = (1..2500).map { episode(it) }
            store.saveFeed(PodcastFeed(show, episodes))
            val old = episodes.first()
            store.setFavorite(old.id, true)
            store.saveQueue("q", "ニュース"); store.addToQueue("q", old.id)
            store.saveProgress(old.id, ListeningProgress(60_000, 60_000, true))
            val library = store.library()
            assertEquals(2500, library.episodeCounts[show.feedUrl])
            assertEquals(1001, library.episodes.size)
            assertTrue(library.hasMoreEpisodes)
            assertTrue(library.episodes.all { it.description.length <= 512 })
            assertTrue(old.id in library.favorites)
            assertTrue(library.queues.single().episodeIds.isEmpty())
            assertEquals(old.description, store.episode(old.id)?.description)
            val page = store.podcastEpisodes(show.feedUrl, 150)
            assertEquals(2500, page.total)
            assertEquals(150, page.episodes.size)
            assertEquals(300, store.podcastEpisodes(show.feedUrl, 300).episodes.size)
            assertEquals(show.website, store.podcast(show.feedUrl)?.website)
            store.unsubscribe(show.feedUrl)
            assertTrue(store.library().favorites.isEmpty())
        }
        context.deleteDatabase("large-library.db")
    }

    @Test fun backlogRulesSeeOldEpisodesAndTextBeyondThePreview(): Unit {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("backlog-rule.db")
        PodcastStore(context, "backlog-rule.db").use { store ->
            val episodes = (1..1200).map { episode(it).copy(description = "x".repeat(600) + if (it == 1) "重要なキーワード" else "") }
            store.saveFeed(PodcastFeed(show, episodes))
            assertFalse(store.library().episodes.any { it.id == episodes.first().id })
            store.saveQueue("q", "技術")
            store.saveRule(QueueRule("q", show.feedUrl, includeWords = "重要なキーワード"), true)
            assertEquals(listOf(episodes.first().id), store.queues().single().episodeIds)
        }
        context.deleteDatabase("backlog-rule.db")
    }

    @Test fun updateOrderUsesTheEntireCatalogRatherThanOnlyTheVisiblePage(): Unit {
        val a = show.copy(title = "Alpha")
        val b = show.copy(feedUrl = "https://example.org/b", title = "Beta")
        val library = Library(podcasts = listOf(b, a), latestEpisodes = mapOf(a.feedUrl to 10, b.feedUrl to 20))
        assertEquals(listOf(a, b), sortedPodcasts(library, PodcastSort.Title))
        assertEquals(listOf(b, a), sortedPodcasts(library, PodcastSort.Updated))
    }

    @Test fun speedOverridesPersistAndFollowAutomaticQueueTransitions(): Unit {
        val context = RuntimeEnvironment.getApplication()
        val prefs = context.getSharedPreferences("speed-test", 0)
        prefs.edit().clear().commit()
        val settings = PlaybackPreferences(prefs)
        settings.setSpeed(1.7f)
        settings.setSpeed(1.2f, "podcast-a")
        val player = ExoPlayer.Builder(context).build()
        val controller = PlaybackSpeedController(player, settings)
        fun item(id: String): MediaItem = MediaItem.Builder().setMediaId(id).setUri("https://example.org/$id.mp3")
            .setMediaMetadata(MediaMetadata.Builder().setExtras(android.os.Bundle().apply { putString("feed_url", id) }).build()).build()
        fun idle(): Unit = Shadows.shadowOf(Looper.getMainLooper()).idle()
        try {
            idle()
            player.setMediaItems(listOf(item("podcast-a"), item("podcast-b"))); idle()
            assertEquals(1.2f, player.playbackParameters.speed, 0.001f)
            assertEquals(1.7f, settings.globalSpeed, 0.001f)
            player.seekToDefaultPosition(1); idle()
            assertEquals(1.7f, player.playbackParameters.speed, 0.001f)
            settings.setSpeed(2f); idle()
            assertEquals(2f, player.playbackParameters.speed, 0.001f)
            player.seekToDefaultPosition(0); idle()
            assertEquals(1.2f, player.playbackParameters.speed, 0.001f)
            assertEquals(2f, settings.globalSpeed, 0.001f)
            repeat(5) { player.seekToDefaultPosition(1); player.seekToDefaultPosition(0) }
            idle()
            assertEquals(2f, settings.globalSpeed, 0.001f)
            assertEquals(1.2f, settings.podcastSpeed("podcast-a") ?: 0f, 0.001f)
            assertEquals(1.2f, player.playbackParameters.speed, 0.001f)
            settings.useGlobal("podcast-a"); idle()
            assertEquals(2f, player.playbackParameters.speed, 0.001f)
            settings.setSort(PodcastSort.Updated)
            assertEquals(PodcastSort.Updated, PlaybackPreferences(prefs).sort)
        } finally { controller.close(); player.release(); prefs.edit().clear().commit() }
    }

    @Test fun refreshIsBoundedAndOneBadFeedDoesNotAbortTheOtherSubscriptions(): Unit = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        context.deleteDatabase("podcasts.db")
        val active = AtomicInteger()
        val peak = AtomicInteger()
        val start = CountDownLatch(3)
        val directory = PodcastDirectory { url ->
            val n = active.incrementAndGet(); peak.accumulateAndGet(n, ::maxOf)
            try {
                start.countDown(); check(start.await(3, TimeUnit.SECONDS)); Thread.sleep(20)
                val id = url.substringAfterLast('/').toInt()
                if (id == 5) throw IllegalArgumentException("bad feed")
                WebResponse(url, if (id == 2) "<html>wrong response</html>" else "<rss><channel><title>番組$id</title><item><title>回</title><guid>$id</guid><enclosure url='https://example.org/$id.mp3'/></item></channel></rss>", "application/rss+xml")
            } finally { active.decrementAndGet() }
        }
        val repository = PodcastRepository(context, directory)
        try {
            repository.store.importSubscriptions((0..6).map { show.copy(feedUrl = "https://example.org/$it", title = "番組$it") })
            repository.load()
            val done = AtomicInteger()
            val failures = kotlinx.coroutines.coroutineScope {
                listOf(async { repository.refresh { complete, total -> assertEquals(7, total); done.accumulateAndGet(complete, ::maxOf) } }, async { repository.refresh() }).awaitAll()
            }
            assertTrue(failures.all { it.size == 2 })
            assertEquals(3, peak.get())
            assertEquals(7, done.get())
            assertEquals(7, repository.library.value.podcasts.size)
            assertEquals(5, repository.library.value.episodes.size)
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }
}
