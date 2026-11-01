package io.github.sasakiy84.podcast

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.lang.reflect.Proxy

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class EpisodeFeaturesTest {
    private val feed = "https://example.org/feed"
    private val show = Podcast(feed, "番組")
    private val episode = Episode("one", feed, "第一回", "https://example.org/one.mp3", durationMs = 600_000)

    @Test fun podloveAndJsonChaptersRetainOrderLinksAndFractionalTimes(): Unit {
        val parsed = FeedParser.parse("""<rss xmlns:psc="http://podlove.org/simple-chapters" xmlns:podcast="https://podcastindex.org/namespace/1.0"><channel><title>Rebuild</title><item><title>回</title><enclosure url="/one.mp3"/><psc:chapters><psc:chapter start="03:42.5" title="話題2" href="/notes"/><psc:chapter start="00:00" title="はじめに"/><psc:chapter start="bad" title="不正"/></psc:chapters><podcast:chapters url="/chapters.json" type="application/json+chapters"/></item></channel></rss>""", feed).episodes.single()
        assertEquals(listOf(0L, 222_500L), parsed.chapters.map { it.startMs })
        assertEquals("https://example.org/notes", parsed.chapters[1].url)
        assertEquals("https://example.org/chapters.json", parsed.chaptersUrl)
        assertEquals(parsed.chapters, EpisodeChapters.decode(EpisodeChapters.encode(parsed.chapters)))
        assertEquals(listOf(EpisodeChapter(1250, "One")), EpisodeChapters.decode("""{"chapters":[{"startTime":1.25,"title":"One"},{"startTime":-1},{"title":"bad"}]}"""))
    }

    @Test fun notesKeepParagraphsListItemsAndLinkTargetsWithoutSummaryOverwriting(): Unit {
        val xml = """<rss xmlns:itunes="http://www.itunes.com/dtds/podcast-1.0.dtd"><channel><title>番組</title><item><description><![CDATA[<p>最初<br>次の行</p><p>続き</p><ul><li><a href="/notes">ノート</a></li></ul><script>悪いコード</script>]]></description><itunes:summary>短い要約</itunes:summary><enclosure url="/one.mp3"/></item></channel></rss>"""
        val text = FeedParser.parse(xml, feed).episodes.single().description
        assertTrue(text.contains("最初\n次の行"))
        assertTrue(text.contains("\n\n続き"))
        assertTrue(text.contains("• ノート (https://example.org/notes)"))
        assertFalse(text.contains("悪いコード"))
        assertFalse(text.contains("短い要約"))
        assertEquals("a\nb", descriptionText("a\nb"))
        val nested = FeedParser.parse("<rss><channel><title>番組</title><item><description><p>一</p><p><a href='/notes'>二</a></p></description><enclosure url='/a.mp3'/></item></channel></rss>", feed).episodes.single().description
        assertTrue(nested.contains("一\n\n二 (https://example.org/notes)"))
    }

    @Test fun historyIsRecordedOnlyForListeningAndOldEntriesRemainAccessible(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        PodcastStore(context, "history-${System.nanoTime()}.db").use { store ->
            val old = episode.copy(published = 1, chapters = listOf(EpisodeChapter(0, "Intro")))
            store.saveFeed(PodcastFeed(show, listOf(old, episode.copy(id = "new", published = 2))))
            store.saveProgress("one", ListeningProgress(50_000, 600_000))
            assertTrue(store.library(limit = 1).historyIds.isEmpty())
            store.saveProgress("one", ListeningProgress(51_000, 600_000), listened = true)
            val library = store.library(limit = 1)
            assertEquals(listOf("one"), library.historyIds)
            assertTrue(library.episodes.any { it.id == "one" })
            assertEquals(old.chapters, store.episode("one")!!.chapters)
            store.saveProgress("one", ListeningProgress(52_000, 0))
            assertEquals(600_000L, store.progress("one").durationMs)
            store.unsubscribe(feed)
            assertTrue(store.library().historyIds.isEmpty())
        }
    }

    @Test fun versionThreeMigrationKeepsSubscriptionsQueuesProgressAndDownloads(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val name = "migration-v3-${System.nanoTime()}.db"
        PodcastStore(context, name).use { store ->
            store.saveFeed(PodcastFeed(show, listOf(episode)))
            store.saveQueue("q", "ニュース"); store.addToQueue("q", episode.id)
            store.saveProgress(episode.id, ListeningProgress(12_000, 600_000))
            store.saveFavoriteForTest()
            store.saveDownload(episode.id, 42)
            store.writableDatabase.apply { execSQL("DROP TABLE listening_history"); execSQL("ALTER TABLE episodes DROP COLUMN chapters"); execSQL("ALTER TABLE episodes DROP COLUMN chapters_url"); version = 3 }
        }
        PodcastStore(context, name).use { store ->
            assertEquals(show, store.podcast(feed))
            assertEquals(listOf(episode.id), store.queues().single().episodeIds)
            assertEquals(12_000L, store.progress(episode.id).positionMs)
            assertEquals(42L, store.downloadId(episode.id))
            assertTrue(episode.id in store.library().favorites)
            assertEquals(listOf(episode.id), store.library().historyIds)
            assertTrue(store.episode(episode.id)!!.chapters.isEmpty())
        }
    }
    private fun PodcastStore.saveFavoriteForTest(): Unit = setFavorite(episode.id, true)

    @Test fun seekingRestoredIdleAndPausedPlayersPreparesWithoutStartingPlayback(): Unit {
        var position = 0L
        var ready = false
        var prepared = 0
        val fake = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "getDuration" -> 600_000L
                "getCurrentPosition" -> position
                "getPlaybackState" -> if (ready) Player.STATE_READY else Player.STATE_IDLE
                "getPlayWhenReady" -> false
                "seekTo" -> { position = args!!.last() as Long; null }
                "prepare" -> { prepared++; ready = true; null }
                "setPlayWhenReady" -> { assertEquals(false, args!![0]); null }
                else -> null
            }
        } as Player
        seekPlayback(fake, 123_000)
        assertEquals(123_000L, position); assertEquals(1, prepared)
        seekPlayback(fake, 800_000)
        assertEquals(600_000L, position); assertEquals(1, prepared)
    }

    @Test fun skipOptionsPersistIndependentlyAndDoNotOverwriteResumedPositions(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val prefs = context.getSharedPreferences("options-test", Context.MODE_PRIVATE).apply { edit().clear().commit() }
        val settings = PlaybackPreferences(prefs)
        settings.setOptions(feed, PodcastOptions(true, 45, 60))
        assertEquals(PodcastOptions(true, 45, 60), PlaybackPreferences(prefs).options(feed))
        assertEquals(PodcastOptions(), settings.options("other"))
        assertEquals(45_000L, playbackStart(episode, ListeningProgress(), settings.options(feed)))
        assertEquals(90_000L, playbackStart(episode, ListeningProgress(90_000), settings.options(feed)))
        assertEquals(45_000L, playbackStart(episode, ListeningProgress(600_000, completed = true), settings.options(feed)))
    }
    @Test fun endSkipOnlyAdvancesOnceBeforeQueueCompletionIsPublished(): Unit = kotlinx.coroutines.runBlocking {
        var index = 0
        val items = (0..2).map { MediaItem.Builder().setMediaId("$it").build() }
        val fake = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, _ -> when (method.name) {
            "getCurrentMediaItem" -> items[index]
            "getDuration" -> 600_000L
            "hasNextMediaItem" -> index < 2
            "seekToNextMediaItem" -> { index++; null }
            else -> null
        } } as Player
        finishSkippedEpisode(fake) { id, duration ->
            assertEquals("0", id); assertEquals(600_000L, duration)
            assertEquals("1", fake.currentMediaItem!!.mediaId)
        }
        assertEquals(1, index)
    }

    @Test fun notificationOnlySubscriptionSchedulesSyncAndDoesNotAlertInitialBackfill(): Unit = kotlinx.coroutines.runBlocking {
        val context = org.robolectric.RuntimeEnvironment.getApplication()
        org.robolectric.Shadows.shadowOf(context).grantPermissions(android.Manifest.permission.POST_NOTIFICATIONS)
        context.deleteDatabase("podcasts.db")
        context.getSharedPreferences("playback", Context.MODE_PRIVATE).edit().clear().commit()
        var feedEpisodes = listOf(episode)
        val repository = PodcastRepository(context, fetchFeed = { PodcastFeed(show, feedEpisodes) })
        try {
            repository.store.saveFeed(PodcastFeed(show, emptyList()))
            repository.setPodcastOptions(feed, PodcastOptions(notifications = true))
            assertNotNull(context.getSystemService(android.app.job.JobScheduler::class.java).allPendingJobs.firstOrNull())
            val manager = context.getSystemService(android.app.NotificationManager::class.java)
            repository.refresh()
            assertTrue(org.robolectric.Shadows.shadowOf(manager).allNotifications.isEmpty())
            feedEpisodes = listOf(episode.copy(id = "two", title = "新しい回"), episode)
            repository.refresh()
            assertEquals("新しい回", org.robolectric.Shadows.shadowOf(manager).allNotifications.single().extras.getCharSequence(android.app.Notification.EXTRA_TEXT).toString())
            manager.cancelAll()
            repository.refresh()
            assertTrue(org.robolectric.Shadows.shadowOf(manager).allNotifications.isEmpty())
        } finally { repository.store.close(); context.deleteDatabase("podcasts.db") }
    }

    @Test fun automaticSkipsFollowTheCurrentPodcastAndRespectPausedOrExplicitPositions(): Unit {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = PlaybackPreferences(context.getSharedPreferences("automatic-skips", Context.MODE_PRIVATE))
        settings.setOptions(feed, PodcastOptions(skipStartSeconds = 45, skipEndSeconds = 30))
        fun item(url: String, explicit: Boolean = false) = MediaItem.Builder().setMediaId(url).setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setExtras(android.os.Bundle().apply { putString("feed_url", url); putBoolean("apply_start_skip", !explicit) }).build()).build()
        var current = item(feed)
        var position = 0L
        var playing = true
        val fake = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args -> when (method.name) {
            "getCurrentMediaItem" -> current
            "getDuration" -> 600_000L
            "getCurrentPosition" -> position
            "getPlaybackState" -> Player.STATE_READY
            "isPlaying" -> playing
            "seekTo" -> { position = args!!.last() as Long; null }
            else -> null
        } } as Player
        val policy = PlaybackSkipController(fake, settings)
        policy.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(45_000L, position)
        position = 569_999; assertFalse(policy.endReached())
        position = 570_000; assertTrue(policy.endReached())
        playing = false; assertFalse(policy.endReached())
        current = item("https://other.example/rss"); position = 0; playing = true
        policy.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_AUTO)
        assertEquals(0L, position); position = 599_999; assertFalse(policy.endReached())
        current = item(feed, explicit = true); position = 0
        policy.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(0L, position)
        current = item(feed); position = 120_000
        policy.onMediaItemTransition(current, Player.MEDIA_ITEM_TRANSITION_REASON_PLAYLIST_CHANGED)
        assertEquals(120_000L, position)
        policy.close()
    }

}
