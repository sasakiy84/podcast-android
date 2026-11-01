package io.github.sasakiy84.podcast

import android.app.DownloadManager
import android.net.Uri
import android.os.Environment
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastDownloadsTest {
    private lateinit var repository: PodcastRepository
    private val context get() = RuntimeEnvironment.getApplication()
    private val podcast = Podcast("https://example.org/feed", "Offline show")
    private val episode = Episode(stableId("download-test"), podcast.feedUrl, "Episode", "https://example.org/episode.mp3")
    private val manager get() = context.getSystemService(DownloadManager::class.java)
    private val file get() = File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${episode.id}.audio")

    @Before fun open(): Unit {
        context.deleteDatabase("podcasts.db")
        repository = PodcastRepository(context)
        repository.store.saveFeed(PodcastFeed(podcast, listOf(episode)))
    }
    @After fun close(): Unit { repository.store.close(); context.deleteDatabase("podcasts.db"); file.delete() }

    @Test
    fun restoredPlaybackHasShowMetadataBeforeTheUiLibraryLoads(): Unit {
        val item = episode.mediaItem(repository)
        assertEquals("Offline show", item.mediaMetadata.artist)
        assertEquals(episode.title, item.mediaMetadata.title)
        assertEquals(Uri.parse(episode.audioUrl), item.localConfiguration?.uri)
    }

    @Test
    fun automaticDownloadsUseOneSharedRequestAndManualRemovalPreventsRedownloading(): Unit = runBlocking {
        repository.store.saveQueue("news", "ニュース")
        repository.store.saveQueue("tech", "技術")
        repository.saveRule(QueueRule("news", podcast.feedUrl, autoDownload = true), true)
        val id = requireNotNull(repository.store.downloadIds()[episode.id])
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_PENDING)
        repository.saveRule(QueueRule("tech", podcast.feedUrl, autoDownload = true), true)
        assertEquals(id, repository.store.downloadIds()[episode.id])
        repository.load()
        repository.download(episode)
        assertEquals(1, shadowOf(manager).requestCount)
        assertEquals(DownloadStatus.Waiting, repository.library.value.downloads[episode.id]?.status)
        file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(1))
        repository.removeDownload(episode)
        assertFalse(file.exists())
        repository.processAutomaticDownloads()
        assertTrue(repository.store.downloadIds().isEmpty())
        assertTrue(repository.store.automaticDownloadIds().isEmpty())
    }

    @Test
    fun manuallyMarkedEpisodeLeavesAllQueuesButKeepsDownloadedAudio(): Unit = runBlocking {
        repository.store.saveQueue("news", "ニュース")
        repository.store.saveQueue("tech", "技術")
        repository.store.addToQueue("news", episode.id); repository.store.addToQueue("tech", episode.id)
        repository.download(episode)
        file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
        repository.updateProgress(episode.id, ListeningProgress(100, 100, true))
        assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
        assertTrue(repository.store.queueEpisodeIds("tech").isEmpty())
        assertTrue(file.exists())
        assertNotNull(repository.store.downloadIds()[episode.id])
    }

    @Test
    fun finishedDownloadUsesTheLocalAudioAndSurvivesReloading(): Unit = runBlocking {
        repository.download(episode)
        val id = requireNotNull(repository.store.downloadIds()[episode.id])
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.load()
        assertEquals(DownloadStatus.Complete, repository.library.value.downloads[episode.id]?.status)
        assertEquals(Uri.fromFile(file), repository.audioUri(episode))
    }

    @Test
    fun missingFileFallsBackToStreamingInsteadOfClaimingOfflineAvailability(): Unit = runBlocking {
        repository.download(episode)
        val id = requireNotNull(repository.store.downloadIds()[episode.id])
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.delete()
        repository.load()
        assertEquals(DownloadStatus.Failed, repository.library.value.downloads[episode.id]?.status)
        assertEquals(Uri.parse(episode.audioUrl), repository.audioUri(episode))
    }

    @Test fun finishingPlaybackRemovesAudioAndDownloadRecordButKeepsHistoryAndFavorite(): Unit = runBlocking {
        repository.store.saveQueue("news", "ニュース")
        repository.store.saveQueue("tech", "技術")
        repository.store.addToQueue("news", episode.id); repository.store.addToQueue("tech", episode.id)
        repository.setFavorite(episode.id, true)
        repository.download(episode)
        file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
        repository.updateProgress(episode.id, ListeningProgress(50, 100), listened = true)
        assertTrue(file.exists())
        repository.updateProgress(episode.id, ListeningProgress(100, 100, true), listened = true, finished = true)
        assertFalse(file.exists())
        assertTrue(repository.store.downloadIds().isEmpty())
        assertTrue(repository.library.value.downloads.isEmpty())
        assertEquals(0, shadowOf(manager).requestCount)
        assertTrue(repository.store.queues().all { it.episodeIds.isEmpty() })
        assertTrue(episode.id in repository.library.value.favorites)
        assertEquals(listOf(episode.id), repository.library.value.historyIds)
        assertTrue(repository.store.progress(episode.id).completed)
        assertEquals(Uri.parse(episode.audioUrl), repository.audioUri(episode))
        repository.load()
        assertTrue(repository.library.value.downloads.isEmpty())
    }

    @Test fun finishedLocalAudioCanBeReplayedFromTheNetworkWithoutAnActiveQueue(): Unit = runBlocking {
        repository.download(episode)
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(requireNotNull(repository.store.downloadId(episode.id))))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(1, 2, 3))
        repository.load()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            player.setMediaItem(episode.mediaItem(repository, applyStartSkip = false))
            assertEquals(Uri.fromFile(file), player.currentMediaItem!!.localConfiguration!!.uri)
            repository.updateProgress(episode.id, ListeningProgress(100, 100, true), finished = true)
            synchronizePlaybackQueue(player, repository, repository.library.value)
            assertEquals(Uri.parse(episode.audioUrl), player.currentMediaItem!!.localConfiguration!!.uri)
            assertFalse(player.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("apply_start_skip", true))
            assertFalse(player.playWhenReady)
            assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
        } finally { player.release() }
    }

    @Test fun wifiRestrictionRecreatesOutstandingRequestsAndPreservesCompletedFiles(): Unit = runBlocking {
        repository.download(episode)
        val old = requireNotNull(repository.store.downloadId(episode.id))
        repository.setWifiOnly(true)
        val restricted = requireNotNull(repository.store.downloadId(episode.id))
        assertNotEquals(old, restricted)
        val request = shadowOf(manager).getRequest(restricted)
        assertEquals(DownloadManager.Request.NETWORK_WIFI, org.robolectric.util.ReflectionHelpers.getField<Int>(request, "mAllowedNetworkTypes"))
        assertFalse(org.robolectric.util.ReflectionHelpers.getField<Boolean>(request, "mMeteredAllowed"))
        val shadow: ShadowDownloadManager.ShadowRequest = Shadow.extract(request)
        shadow.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs(); file.writeBytes(byteArrayOf(1))
        repository.load(); repository.setWifiOnly(false)
        assertEquals(restricted, repository.store.downloadId(episode.id))
        assertTrue(file.exists())
    }

}
