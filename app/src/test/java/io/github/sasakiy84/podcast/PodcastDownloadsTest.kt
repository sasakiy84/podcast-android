package io.github.sasakiy84.podcast

import android.app.DownloadManager
import android.app.job.JobScheduler
import android.content.ContextWrapper
import android.net.Uri
import android.os.BatteryManager
import android.os.Environment
import java.io.File
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class PodcastDownloadsTest {
    private lateinit var repository: PodcastRepository
    private val context
        get() = RuntimeEnvironment.getApplication()

    private val podcast = Podcast("https://example.org/feed", "Offline show")
    private val episode =
        Episode(
            stableId("download-test"),
            podcast.feedUrl,
            "Episode",
            "https://example.org/episode.mp3",
        )
    private val manager
        get() = context.getSystemService(DownloadManager::class.java)
    private var fetchedFeed = PodcastFeed(podcast, listOf(episode))

    private val file
        get() =
            File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${episode.id}.audio")

    @Before
    fun open(): Unit {
        context.deleteDatabase("podcasts.db")
        repository = PodcastRepository(context, fetchFeed = { fetchedFeed })
        repository.store.saveFeed(PodcastFeed(podcast, listOf(episode)))
    }

    @After
    fun close(): Unit {
        repository.store.close()
        context.deleteDatabase("podcasts.db")
        file.delete()
    }

    @Test
    fun restoredPlaybackHasShowMetadataBeforeTheUiLibraryLoads(): Unit {
        val item = episode.mediaItem(repository)
        assertEquals("Offline show", item.mediaMetadata.artist)
        assertEquals(episode.title, item.mediaMetadata.title)
        assertEquals(Uri.parse(episode.audioUrl), item.localConfiguration?.uri)
    }

    @Test
    fun refreshAutomatesNewReleasesButStillListsPreviouslyUnseenArchiveEpisodes(): Unit =
        runBlocking {
            val known = episode.copy(published = 2_000L)
            val archive = episode.copy(id = "archive", published = 1_000L)
            val new = episode.copy(id = "new", published = 3_000L)
            val sameTime = episode.copy(id = "same-time", published = known.published)
            repository.store.saveFeed(PodcastFeed(podcast, listOf(known)))
            repository.setAutoDownload(podcast.feedUrl, true)
            repository.saveQueue("news", "ニュース")
            repository.saveRule(QueueRule("news", podcast.feedUrl), false)
            fetchedFeed = PodcastFeed(podcast, listOf(new, sameTime, known, archive))

            repository.refresh()

            assertEquals(setOf(new.id, sameTime.id), repository.store.downloadIds().keys)
            assertEquals(listOf(new.id, sameTime.id), repository.store.queueEpisodeIds("news"))
            assertEquals(archive, repository.store.episode(archive.id))
        }

    @Test
    fun firstRefreshOfAnImportedFeedEstablishesTheBaselineWithoutAutomatingItsArchive(): Unit =
        runBlocking {
            val imported = podcast.copy(feedUrl = "https://example.org/imported")
            val archive = episode.copy(id = "archive", feedUrl = imported.feedUrl, published = 1_000L)
            val new = archive.copy(id = "new", published = 2_000L)
            repository.store.importSubscriptions(listOf(imported))
            repository.setAutoDownload(imported.feedUrl, true)
            repository.saveQueue("news", "ニュース")
            repository.saveRule(QueueRule("news", imported.feedUrl, autoDownload = true), false)
            fetchedFeed = PodcastFeed(imported, listOf(archive))

            repository.refresh(imported.feedUrl)

            assertEquals(archive, repository.store.episode(archive.id))
            assertTrue(repository.store.downloadIds().isEmpty())
            assertTrue(repository.store.queueEpisodeIds("news").isEmpty())

            fetchedFeed = PodcastFeed(imported, listOf(new, archive))
            repository.refresh(imported.feedUrl)

            assertEquals(setOf(new.id), repository.store.downloadIds().keys)
            assertEquals(listOf(new.id), repository.store.queueEpisodeIds("news"))
        }

    @Test
    fun rotatingAudioUrlWithoutGuidUpdatesTheSavedEpisodeAndKeepsItsProgress(): Unit =
        runBlocking {
            fun feed(token: Int): PodcastFeed = FeedParser.parse(
                """
                <rss version="2.0"><channel><title>Offline show</title>
                <item><title>Archive</title><pubDate>Wed, 01 Jan 2020 00:00:00 GMT</pubDate>
                <enclosure url="https://example.org/archive.mp3?token=$token" type="audio/mpeg"/>
                </item></channel></rss>
                """.trimIndent(),
                podcast.feedUrl,
            )
            val original = feed(1).episodes.single()
            repository.store.saveFeed(PodcastFeed(podcast, listOf(original)))
            repository.updateProgress(original.id, ListeningProgress(1_000, 2_000))
            repository.setAutoDownload(podcast.feedUrl, true)
            repository.saveQueue("news", "ニュース")
            repository.saveRule(QueueRule("news", podcast.feedUrl, autoDownload = true), false)

            for (token in 2..3) {
                fetchedFeed = feed(token)
                repository.refresh()
            }

            assertEquals(2, repository.store.podcastEpisodes(podcast.feedUrl).total)
            assertEquals(fetchedFeed.episodes.single().audioUrl, repository.store.episode(original.id)?.audioUrl)
            assertEquals(1_000L, repository.store.progress(original.id).positionMs)
            assertTrue(repository.store.downloadIds().isEmpty())
            assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
        }

    @Test
    fun failedAutomaticDownloadSurvivesRefreshAndWifiChangesUntilManuallyRetried(): Unit =
        runBlocking {
            val new = episode.copy(id = "failed-download")
            repository.setAutoDownload(podcast.feedUrl, true)
            fetchedFeed = PodcastFeed(podcast, listOf(episode, new))
            repository.refresh()
            val firstId = requireNotNull(repository.store.downloadId(new.id))
            assertTrue(repository.store.automaticDownloadIds().isEmpty())
            val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(firstId))
            request.setStatus(DownloadManager.STATUS_FAILED)

            // Earlier versions kept the automatic reservation after submitting the request.
            repository.store.writableDatabase.execSQL(
                "INSERT INTO automatic_downloads (episode_id) VALUES (?)",
                arrayOf(new.id),
            )
            repeat(2) { repository.refresh() }
            repository.setWifiOnly(!repository.settings.wifiOnly)

            assertTrue(repository.store.automaticDownloadIds().isEmpty())
            assertEquals(firstId, repository.store.downloadId(new.id))
            assertEquals(DownloadStatus.Failed, repository.library.value.downloads[new.id]?.status)

            repository.download(new)
            assertNotEquals(firstId, repository.store.downloadId(new.id))
        }

    @Test
    fun perPodcastLimitTakesTheTwoNewestInTheBatchWithoutLimitingQueueRules(): Unit = runBlocking {
        val older = episode.copy(id = "older", published = 1_000)
        val middle = episode.copy(id = "middle", published = 2_000)
        val newest = episode.copy(id = "newest", published = 3_000)
        val queued = episode.copy(id = "queued", title = "ニュース", published = 500)
        repository.setAutoDownload(podcast.feedUrl, true)
        repository.saveRule(QueueRule("default", podcast.feedUrl, includeWords = "ニュース", autoDownload = true), false)
        fetchedFeed = PodcastFeed(podcast, listOf(older, queued, middle, newest))

        repository.refresh()

        assertEquals(setOf(newest.id, middle.id, queued.id), repository.store.downloadIds().keys)
        assertEquals(older, repository.store.episode(older.id))
    }

    @Test
    fun aChargingReservationAppearsInStorageAndManualStartClearsTheJob(): Unit = runBlocking {
        repository.settings.setAutoDownloadChargingOnly(true)
        shadowOf(context.getSystemService(BatteryManager::class.java)).setIsCharging(false)
        repository.saveRule(QueueRule("default", podcast.feedUrl, autoDownload = true), true)

        val jobs = context.getSystemService(JobScheduler::class.java)
        val job = jobs.allPendingJobs.single { it.service.className == AutomaticDownloadJob::class.java.name }
        assertTrue(job.isRequireCharging)
        assertTrue(job.isRequireStorageNotLow)
        assertNull(repository.store.downloadId(episode.id))
        val waiting = repository.management().downloads.single().download
        assertEquals("充電待ち", waiting.reason)
        assertTrue(waiting.canStartManually)

        repository.download(episode)

        val id = requireNotNull(repository.store.downloadId(episode.id))
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
        assertTrue(request.allowedOverMetered)
        assertTrue(repository.store.automaticDownloadIds().isEmpty())
        assertNull(jobs.getPendingJob(job.id))
    }

    @Test
    @Config(shadows = [RejectedDownloadManager::class])
    fun rejectedRegistrationShowsAReasonAndCanBeRetriedManually(): Unit = runBlocking {
        val new = episode.copy(id = "rejected")
        repository.setAutoDownload(podcast.feedUrl, true)
        fetchedFeed = PodcastFeed(podcast, listOf(episode, new))
        val shadow: RejectedDownloadManager = Shadow.extract(manager)
        shadow.reject = true

        repository.refresh()

        assertNull(repository.store.downloadId(new.id))
        assertTrue(repository.store.automaticDownloadIds().isEmpty())
        val failure = repository.management().downloads.single().download
        assertEquals(DownloadStatus.Failed, failure.status)
        assertEquals("ダウンロードを開始できませんでした", failure.reason)

        shadow.reject = false
        repository.refresh()
        assertNull(repository.store.downloadId(new.id))
        repository.download(new)
        assertNotNull(repository.store.downloadId(new.id))
    }

    @Test
    fun anUnavailableDestinationDoesNotStopOtherAutomaticDownloads(): Unit = runBlocking {
        var failNext = false
        val wrapped = object : ContextWrapper(context) {
            override fun getExternalFilesDir(type: String?): File? {
                if (failNext) {
                    failNext = false
                    return null
                }
                return super.getExternalFilesDir(type)
            }
        }
        val first = episode.copy(id = "first")
        val second = episode.copy(id = "second")
        val repo = PodcastRepository(wrapped, fetchFeed = { PodcastFeed(podcast, listOf(first, second)) })
        try {
            repo.setAutoDownload(podcast.feedUrl, true)
            failNext = true
            repo.refresh()

            assertEquals("保存先を利用できません", repo.library.value.downloads[first.id]?.reason)
            assertNotNull(repo.store.downloadId(second.id))
        } finally {
            repo.store.close()
        }
    }

    @Implements(DownloadManager::class)
    class RejectedDownloadManager : ShadowDownloadManager() {
        var reject = false

        @Implementation
        override fun enqueue(request: DownloadManager.Request): Long =
            if (reject) -1L else super.enqueue(request)
    }

    @Test
    fun automaticDownloadsUseOneSharedRequestAndManualRemovalPreventsRedownloading(): Unit =
        runBlocking {
            repository.setWifiOnly(false)
            repository.store.saveQueue("news", "ニュース")
            repository.store.saveQueue("tech", "技術")
            repository.saveRule(QueueRule("news", podcast.feedUrl, autoDownload = true), true)
            val id = requireNotNull(repository.store.downloadIds()[episode.id])
            val request: ShadowDownloadManager.ShadowRequest =
                Shadow.extract(shadowOf(manager).getRequest(id))
            request.setStatus(DownloadManager.STATUS_PENDING)
            repository.saveRule(QueueRule("tech", podcast.feedUrl, autoDownload = true), true)
            assertEquals(id, repository.store.downloadIds()[episode.id])
            repository.load()
            repository.download(episode)
            assertEquals(1, shadowOf(manager).requestCount)
            assertEquals(
                DownloadStatus.Waiting,
                repository.library.value.downloads[episode.id]?.status,
            )
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1))
            repository.removeDownload(episode)
            assertFalse(file.exists())
            repository.processAutomaticDownloads()
            assertTrue(repository.store.downloadIds().isEmpty())
            assertTrue(repository.store.automaticDownloadIds().isEmpty())
        }

    @Test
    fun clearingAQueueRemovesItsAudioAndReservationsButKeepsRulesHistoryAndOtherDownloads(): Unit = runBlocking {
        val other = episode.copy(id = "other-download", title = "別の回")
        val pending = episode.copy(id = "pending-download", title = "待機中の回")
        val otherFile = File(file.parentFile, "${other.id}.audio")
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            repository.store.saveFeed(PodcastFeed(podcast, listOf(episode, other)))
            repository.store.saveQueue("news", "ニュース")
            repository.store.saveQueue("other", "技術")
            val rule = QueueRule("news", podcast.feedUrl, autoDownload = true)
            repository.store.saveRule(rule, false)
            repository.store.addToQueue("news", episode.id)
            repository.store.addToQueue("other", episode.id)
            repository.store.addToQueue("other", other.id)
            repository.store.setFavorite(episode.id, true)
            repository.store.saveFeed(PodcastFeed(podcast, listOf(pending)))
            assertTrue(pending.id in repository.store.automaticDownloadIds())
            for ((item, local) in listOf(episode to file, other to otherFile)) {
                repository.download(item)
                val id = requireNotNull(repository.store.downloadId(item.id))
                val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
                request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
                local.parentFile?.mkdirs()
                local.writeBytes(byteArrayOf(1, 2, 3))
            }
            repository.processAutomaticDownloads()
            val pendingId = requireNotNull(repository.store.downloadId(pending.id))
            val otherId = requireNotNull(repository.store.downloadId(other.id))
            repository.updateProgress(episode.id, ListeningProgress(12_000, 60_000), listened = true)
            assertTrue(file.isFile)
            assertTrue(repository.store.automaticDownloadIds().isEmpty())
            repository.preferences.edit().putString("active_queue", "other").commit()
            player.setMediaItems(listOf(episode.mediaItem(repository, applyStartSkip = false), other.mediaItem(repository)), 0, 12_000)
            assertEquals(Uri.fromFile(file), player.currentMediaItem!!.localConfiguration!!.uri)
            val commands = PlaybackCommands(repository, { PodcastUiState(library = repository.library.value) }) { operation -> runBlocking { operation() } }
            commands.clearQueueAndDownloads(player, "news")
            assertEquals(Uri.parse(episode.audioUrl), player.currentMediaItem!!.localConfiguration!!.uri)
            assertEquals(12_000L, player.currentPosition)
            assertFalse(player.playWhenReady)
            assertFalse(player.currentMediaItem!!.mediaMetadata.extras!!.getBoolean("apply_start_skip"))
            assertEquals(2, player.mediaItemCount)
            assertEquals("other", repository.preferences.getString("active_queue", null))
            assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
            assertEquals(listOf(episode.id, other.id), repository.store.queueEpisodeIds("other"))
            assertEquals("ニュース", repository.store.queues().first { it.id == "news" }.name)
            assertEquals(listOf(rule), repository.store.rules())
            assertEquals(12_000L, repository.store.progress(episode.id).positionMs)
            assertTrue(episode.id in repository.library.value.historyIds)
            assertTrue(episode.id in repository.library.value.favorites)
            assertFalse(file.exists())
            assertTrue(otherFile.isFile)
            assertNull(shadowOf(manager).getRequest(pendingId))
            repository.processAutomaticDownloads()
            assertEquals(mapOf(other.id to otherId), repository.store.downloadIds())
            assertTrue(repository.store.automaticDownloadIds().isEmpty())
            repository.store.saveFeed(PodcastFeed(podcast, listOf(pending)))
            assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
            val fresh = episode.copy(id = "new-after-clear")
            repository.store.saveFeed(PodcastFeed(podcast, listOf(fresh)))
            assertEquals(listOf(fresh.id), repository.store.queueEpisodeIds("news"))
        } finally {
            player.release()
            otherFile.delete()
        }
    }

    @Test
    fun feedCleanupRemovesAllMembershipsAndDownloadsWithoutUnsubscribing(): Unit = runBlocking {
        val secondPodcast = Podcast("https://example.org/other", "別の番組")
        val other = episode.copy(id = "other-feed", feedUrl = secondPodcast.feedUrl)
        val downloadOnly = episode.copy(id = "download-only", published = 1)
        val pending = episode.copy(id = "pending", published = 3, title = "待機中の回")
        val rule = QueueRule("news", podcast.feedUrl, includeWords = episode.title)
        repository.store.saveFeed(PodcastFeed(podcast, listOf(episode.copy(published = 2), downloadOnly)))
        repository.store.saveFeed(PodcastFeed(secondPodcast, listOf(other)))
        repository.store.saveQueue("news", "ニュース")
        repository.store.saveQueue("mixed", "番組いろいろ")
        repository.store.saveRule(rule, false)
        repository.store.addToQueue("news", episode.id)
        repository.store.addToQueue("mixed", episode.id)
        repository.store.addToQueue("mixed", other.id)
        repository.store.setAutoDownload(podcast.feedUrl, true)
        repository.store.saveFeed(PodcastFeed(podcast, listOf(pending)))
        assertEquals(listOf(pending.id), repository.store.automaticDownloadIds())
        assertEquals(listOf(pending.id), repository.store.podcastEpisodes(podcast.feedUrl, 1).episodes.map { it.id })
        repository.store.setFavorite(episode.id, true)
        repository.updateProgress(episode.id, ListeningProgress(12_000, 60_000), listened = true)
        val audioFiles = listOf(episode, downloadOnly, other).associateWith { File(file.parentFile, "${it.id}.audio") }
        try {
            for ((item, audio) in audioFiles) {
                repository.download(item)
                val requestId = requireNotNull(repository.store.downloadId(item.id))
                val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(requestId))
                request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
                audio.writeBytes(byteArrayOf(1, 2, 3))
            }

            repository.clearFeedAndDownloads(podcast.feedUrl)
            repository.processAutomaticDownloads()

            assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
            assertEquals(listOf(other.id), repository.store.queueEpisodeIds("mixed"))
            assertEquals(setOf(other.id), repository.store.downloadIds().keys)
            assertEquals(1, shadowOf(manager).requestCount)
            assertTrue(repository.store.automaticDownloadIds().isEmpty())
            assertFalse(audioFiles.getValue(episode).exists())
            assertFalse(audioFiles.getValue(downloadOnly).exists())
            assertTrue(audioFiles.getValue(other).exists())
            assertEquals(listOf(rule), repository.store.rules())
            assertTrue(podcast.feedUrl in repository.library.value.autoDownloadFeeds)
            assertEquals(podcast, repository.store.podcast(podcast.feedUrl))
            assertEquals(3, repository.store.podcastEpisodes(podcast.feedUrl).total)
            assertEquals(12_000L, repository.store.progress(episode.id).positionMs)
            assertTrue(episode.id in repository.library.value.historyIds)
            assertTrue(episode.id in repository.library.value.favorites)
        } finally {
            audioFiles.values.forEach { it.delete() }
        }
    }

    @Test
    fun manuallyMarkedEpisodeLeavesAllQueuesButKeepsDownloadedAudio(): Unit = runBlocking {
        repository.store.saveQueue("news", "ニュース")
        repository.store.saveQueue("tech", "技術")
        repository.store.addToQueue("news", episode.id)
        repository.store.addToQueue("tech", episode.id)
        repository.download(episode)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.updateProgress(episode.id, ListeningProgress(100, 100, true))
        assertTrue(repository.store.queueEpisodeIds("news").isEmpty())
        assertTrue(repository.store.queueEpisodeIds("tech").isEmpty())
        assertTrue(file.exists())
        assertNotNull(repository.store.downloadIds()[episode.id])
    }

    @Test
    fun downloadCompletionMakesLocalAudioAvailableWithoutReloadingEpisodes(): Unit = runBlocking {
        repository.download(episode)
        val id = requireNotNull(repository.store.downloadId(episode.id))
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        val episodes = repository.library.value.episodes

        repository.refreshDownloadCompletion(id)

        assertSame(episodes, repository.library.value.episodes)
        assertEquals(DownloadStatus.Complete, repository.library.value.downloads[episode.id]?.status)
        assertEquals(Uri.fromFile(file), repository.audioUri(episode))
    }

    @Test
    fun finishedDownloadUsesTheLocalAudioAndSurvivesReloading(): Unit = runBlocking {
        repository.download(episode)
        val id = requireNotNull(repository.store.downloadIds()[episode.id])
        val request: ShadowDownloadManager.ShadowRequest =
            Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.load()
        assertEquals(
            DownloadStatus.Complete,
            repository.library.value.downloads[episode.id]?.status,
        )
        assertEquals(Uri.fromFile(file), repository.audioUri(episode))
    }

    @Test
    fun missingFileFallsBackToStreamingInsteadOfClaimingOfflineAvailability(): Unit = runBlocking {
        repository.download(episode)
        val id = requireNotNull(repository.store.downloadIds()[episode.id])
        val request: ShadowDownloadManager.ShadowRequest =
            Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.delete()
        repository.load()
        assertEquals(DownloadStatus.Failed, repository.library.value.downloads[episode.id]?.status)
        assertEquals(Uri.parse(episode.audioUrl), repository.audioUri(episode))
    }

    @Test
    fun finishingPlaybackRemovesUnstarredAudioButKeepsHistory(): Unit =
        runBlocking {
            repository.store.saveQueue("news", "ニュース")
            repository.store.saveQueue("tech", "技術")
            repository.store.addToQueue("news", episode.id)
            repository.store.addToQueue("tech", episode.id)
            repository.download(episode)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            repository.updateProgress(episode.id, ListeningProgress(50, 100), listened = true)
            assertTrue(file.exists())
            repository.updateProgress(
                episode.id,
                ListeningProgress(100, 100, true),
                listened = true,
                finished = true,
            )
            assertFalse(file.exists())
            assertTrue(repository.store.downloadIds().isEmpty())
            assertTrue(repository.library.value.downloads.isEmpty())
            assertEquals(0, shadowOf(manager).requestCount)
            assertTrue(repository.store.queues().all { it.episodeIds.isEmpty() })
            assertEquals(listOf(episode.id), repository.library.value.historyIds)
            assertTrue(repository.store.progress(episode.id).completed)
            assertEquals(Uri.parse(episode.audioUrl), repository.audioUri(episode))
            repository.load()
            assertTrue(repository.library.value.downloads.isEmpty())
        }

    @Test
    fun favoriteAudioRemainsLocalAfterCompletionAndReloading(): Unit = runBlocking {
        repository.setFavorite(episode.id, true)
        repository.download(episode)
        val id = requireNotNull(repository.store.downloadId(episode.id))
        val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(id))
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.updateProgress(episode.id, ListeningProgress(100, 100, true), listened = true, finished = true)
        repository.store.close()
        repository = PodcastRepository(context)
        repository.load()
        assertTrue(file.isFile)
        assertEquals(id, repository.store.downloadId(episode.id))
        assertTrue(episode.id in repository.library.value.favorites)
        assertTrue(repository.store.progress(episode.id).completed)
        assertEquals(Uri.fromFile(file), repository.audioUri(episode))
    }

    @Test
    fun downloadOrderSurvivesReloadingAndRedownloadMovesTheEpisodeToTheFront(): Unit = runBlocking {
        val newer = episode.copy(id = "newer", title = "Episode newer", published = 100)
        val newerFile = File(file.parentFile, "${newer.id}.audio")
        repository.store.saveFeed(PodcastFeed(podcast, listOf(newer)))
        try {
            for ((item, local) in listOf(newer to newerFile, episode to file)) {
                repository.download(item)
                val request: ShadowDownloadManager.ShadowRequest = Shadow.extract(shadowOf(manager).getRequest(requireNotNull(repository.store.downloadId(item.id))))
                request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
                local.parentFile?.mkdirs()
                local.writeBytes(byteArrayOf(1))
            }
            repository.store.close()
            repository = PodcastRepository(context)
            repository.load()
            assertEquals(listOf(episode.id, newer.id), repository.management().downloads.map { it.episode.id })
            val found = repository.store.searchEpisodes("Episode", EpisodeFilter.Downloaded, limit = 1)
            assertEquals(listOf(episode.id), found.episodes.map { it.id })
            assertTrue(found.hasMore)
            assertTrue(repository.library.value.downloads.getValue(episode.id).downloadOrder > repository.library.value.downloads.getValue(newer.id).downloadOrder)
            repository.removeDownload(newer)
            repository.download(newer)
            val foundAgain = repository.store.searchEpisodes("Episode", EpisodeFilter.Downloaded, limit = 1)
            assertEquals(listOf(newer.id), foundAgain.episodes.map { it.id })
        } finally { newerFile.delete() }
    }

    @Test
    fun finishedLocalAudioCanBeReplayedFromTheNetworkWithoutAnActiveQueue(): Unit = runBlocking {
        repository.download(episode)
        val request: ShadowDownloadManager.ShadowRequest =
            Shadow.extract(
                shadowOf(manager)
                    .getRequest(requireNotNull(repository.store.downloadId(episode.id)))
            )
        request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
        file.parentFile?.mkdirs()
        file.writeBytes(byteArrayOf(1, 2, 3))
        repository.load()
        val player = androidx.media3.exoplayer.ExoPlayer.Builder(context).build()
        try {
            player.setMediaItem(episode.mediaItem(repository, applyStartSkip = false))
            assertEquals(Uri.fromFile(file), player.currentMediaItem!!.localConfiguration!!.uri)
            repository.updateProgress(
                episode.id,
                ListeningProgress(100, 100, true),
                finished = true,
            )
            synchronizePlaybackQueue(player, repository, repository.library.value)
            assertEquals(
                Uri.parse(episode.audioUrl),
                player.currentMediaItem!!.localConfiguration!!.uri,
            )
            assertFalse(
                player.currentMediaItem!!
                    .mediaMetadata
                    .extras!!
                    .getBoolean("apply_start_skip", true)
            )
            assertFalse(player.playWhenReady)
            assertEquals(androidx.media3.common.Player.STATE_IDLE, player.playbackState)
        } finally {
            player.release()
        }
    }

    @Test
    fun wifiRestrictionAppliesToAutomaticDownloadsAndManualStartCanOverrideIt(): Unit =
        runBlocking {
            repository.setWifiOnly(true)
            repository.saveRule(QueueRule("default", podcast.feedUrl, autoDownload = true), true)
            val old = requireNotNull(repository.store.downloadId(episode.id))
            val request = shadowOf(manager).getRequest(old)
            val shadow: ShadowDownloadManager.ShadowRequest = Shadow.extract(request)
            assertFalse(shadow.allowedOverMetered)
            shadow.setStatus(DownloadManager.STATUS_PAUSED)
            assertEquals("Wi-Fi 接続待ち", repository.management().downloads.single().download.reason)
            repository.download(episode)
            val manual = requireNotNull(repository.store.downloadId(episode.id))
            assertNotEquals(old, manual)
            val manualRequest = shadowOf(manager).getRequest(manual)
            val manualShadow: ShadowDownloadManager.ShadowRequest = Shadow.extract(manualRequest)
            assertTrue(manualShadow.allowedOverMetered)
            manualShadow.setStatus(DownloadManager.STATUS_SUCCESSFUL)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1))
            repository.load()
            repository.setWifiOnly(false)
            assertEquals(manual, repository.store.downloadId(episode.id))
            assertTrue(file.exists())
            val management = repository.management()
            assertEquals(1L, management.storage.audioBytes)
            assertEquals(1L, management.downloads.single().download.totalBytes)
            assertEquals(DownloadStatus.Complete, management.downloads.single().download.status)
        }
}
