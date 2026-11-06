package io.github.sasakiy84.podcast

import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.runBlocking
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
class PlaybackCommandsTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val show = Podcast("https://example.org/feed", "番組")
    private val first =
        Episode("one", show.feedUrl, "第一回", "https://example.org/one.mp3", durationMs = 600_000)
    private val second = first.copy(id = "two", title = "第二回")
    private lateinit var repository: PodcastRepository
    private lateinit var player: ExoPlayer
    private lateinit var commands: PlaybackCommands

    @Before
    fun open(): Unit = runBlocking {
        context.deleteDatabase("podcasts.db")
        context.getSharedPreferences("playback", 0).edit().clear().commit()
        repository = PodcastRepository(context)
        repository.store.saveFeed(PodcastFeed(show, listOf(first, second)))
        repository.load()
        player = ExoPlayer.Builder(context).build()
        commands =
            PlaybackCommands(repository, { PodcastUiState(library = repository.library.value) }) {
                operation ->
                runBlocking { operation() }
            }
    }

    @After
    fun close(): Unit {
        player.release()
        repository.store.close()
        context.deleteDatabase("podcasts.db")
    }

    @Test
    fun standaloneReplayResetsCompletionAppliesStartSkipAndLeavesTheQueue(): Unit = runBlocking {
        repository.store.saveFeed(
            PodcastFeed(
                show,
                (1..1005).map { first.copy(id = "recent-$it", published = it.toLong()) },
            )
        )
        repository.load()
        assertFalse(repository.library.value.episodes.any { it.id == first.id })
        repository.settings.setOptions(show.feedUrl, PodcastOptions(skipStartSeconds = 45))
        repository.updateProgress(first.id, ListeningProgress(600_000, 600_000, true))
        repository.preferences.edit().putString("active_queue", "news").commit()
        commands.play(player, first)
        assertEquals(first.id, player.currentMediaItem?.mediaId)
        assertEquals(45_000L, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertFalse(repository.store.progress(first.id).completed)
        assertFalse(repository.preferences.contains("active_queue"))
        assertTrue(repository.library.value.episodes.any { it.id == first.id })
    }

    @Test
    fun queueResumePreservesPositionAndSelectingItsCurrentEpisodeTogglesPause(): Unit =
        runBlocking {
            repository.store.saveQueue("news", "ニュース")
            repository.store.addToQueue("news", first.id)
            repository.store.addToQueue("news", second.id)
            repository.load()
            player.setMediaItem(first.mediaItem(repository), 90_000)
            repository.preferences.edit().putString("active_queue", "news").commit()
            commands.playQueue(player, "news", null)
            assertEquals(
                listOf(first.id, second.id),
                (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId },
            )
            assertEquals(90_000L, player.currentPosition)
            assertTrue(player.playWhenReady)
            commands.playQueue(player, "news", first.id)
            assertFalse(player.playWhenReady)
            commands.playQueue(player, "news", second.id)
            assertEquals(second.id, player.currentMediaItem?.mediaId)
            assertEquals(0L, player.currentPosition)
            assertTrue(player.playWhenReady)
            repository.updateProgress(first.id, ListeningProgress(90_000, first.durationMs))
            commands.previous(player)
            assertEquals(first.id, player.currentMediaItem?.mediaId)
            assertEquals(0L, player.currentPosition)
            assertEquals(0L, repository.store.progress(first.id).positionMs)
            assertTrue(player.playWhenReady)
        }

    @Test
    fun explicitChaptersPreservePauseAndOverrideStartSkipWhenChangingEpisode(): Unit = runBlocking {
        repository.settings.setOptions(show.feedUrl, PodcastOptions(skipStartSeconds = 45))
        player.setMediaItem(first.mediaItem(repository), 90_000)
        var observedPosition = -1L
        commands.playChapter(player, first, 12_000) { observedPosition = player.currentPosition }
        assertEquals(12_000L, observedPosition)
        assertEquals(12_000L, player.currentPosition)
        assertFalse(player.playWhenReady)

        repository.updateProgress(second.id, ListeningProgress(600_000, 600_000, true))
        repository.preferences.edit().putString("active_queue", "news").commit()
        commands.playChapter(player, second, 10_000) {}
        assertEquals(second.id, player.currentMediaItem?.mediaId)
        assertEquals(10_000L, player.currentPosition)
        assertTrue(player.playWhenReady)
        assertFalse(repository.store.progress(second.id).completed)
        assertFalse(repository.preferences.contains("active_queue"))
        assertFalse(player.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("apply_start_skip"))
        assertEquals(second.id, repository.preferences.getString("current", null))
    }

    @Test
    fun clearingTheActiveQueueStopsPlaybackWithoutLosingProgressOrOtherMemberships(): Unit = runBlocking {
        repository.store.saveQueue("news", "ニュース")
        repository.store.saveQueue("other", "技術")
        repository.store.addToQueue("news", first.id)
        repository.store.addToQueue("other", first.id)
        repository.load()
        commands.playQueue(player, "news", null)
        player.seekTo(12_000)
        commands.clearQueueAndDownloads(player, "news")
        synchronizePlaybackQueue(player, repository, repository.library.value)
        assertEquals(0, player.mediaItemCount)
        assertFalse(player.playWhenReady)
        assertFalse(repository.preferences.contains("active_queue"))
        assertFalse(repository.preferences.contains("current"))
        assertEquals(12_000L, repository.store.progress(first.id).positionMs)
        assertFalse(repository.store.progress(first.id).completed)
        assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
        assertEquals(listOf(first.id), repository.store.queueEpisodeIds("other"))
        assertEquals("ニュース", repository.store.queues().first { it.id == "news" }.name)
    }

    @Test
    fun removingCurrentAudioKeepsTheQueuePositionAndExplicitChapterPolicy(): Unit = runBlocking {
        repository.download(first)
        val manager = context.getSystemService(android.app.DownloadManager::class.java)
        val request =
            org.robolectric.Shadows.shadowOf(manager)
                .getRequest(requireNotNull(repository.store.downloadId(first.id)))
        val shadow =
            org.robolectric.shadow.api.Shadow.extract<
                org.robolectric.shadows.ShadowDownloadManager.ShadowRequest
            >(
                request
            )
        shadow.setStatus(android.app.DownloadManager.STATUS_SUCCESSFUL)
        val file =
            java.io.File(
                context.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS),
                "${first.id}.audio",
            )
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.load()
        player.setMediaItems(
            listOf(
                first.mediaItem(repository, applyStartSkip = false),
                second.mediaItem(repository),
            ),
            0,
            123_000,
        )
        commands.removeDownload(player, first)
        assertEquals(2, player.mediaItemCount)
        assertEquals(first.audioUrl, player.currentMediaItem!!.localConfiguration!!.uri.toString())
        assertEquals(123_000L, player.currentPosition)
        assertFalse(player.playWhenReady)
        assertFalse(player.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("apply_start_skip"))
        assertFalse(file.exists())
        assertTrue(repository.store.downloadIds().isEmpty())
    }
}
