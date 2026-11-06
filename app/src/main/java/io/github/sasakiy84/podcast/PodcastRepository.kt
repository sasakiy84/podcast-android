package io.github.sasakiy84.podcast

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import androidx.core.database.sqlite.transaction
import coil.imageLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext

class PodcastRepository(
    private val context: Context,
    private val directory: PodcastDirectory = PodcastDirectory(),
    private val fetchFeed: ((String) -> PodcastFeed)? = null,
) {
    val store: PodcastStore = PodcastStore(context)
    val preferences: android.content.SharedPreferences =
        context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    val settings = PlaybackPreferences(preferences)
    private var episodeLimit = 1000
    private var historyLimit = 150
    private val notifications = PodcastNotifications(context)
    private val refreshPermits = kotlinx.coroutines.sync.Semaphore(3)
    private val feedRefreshLocks = java.util.concurrent.ConcurrentHashMap<String, Mutex>()
    private val downloadStorage = PodcastDownloadStorage(context, store)
    private val mutex = Mutex()
    private val mutableLibrary = MutableStateFlow(Library())
    val library: StateFlow<Library> = mutableLibrary

    suspend fun load(): Unit = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    suspend fun search(query: String): List<PodcastCandidate> =
        withContext(Dispatchers.IO) { directory.search(query) }

    suspend fun resolve(url: String): List<PodcastCandidate> =
        withContext(Dispatchers.IO) { directory.resolve(url) }

    suspend fun importOpml(uri: Uri): OpmlImportResult =
        withContext(Dispatchers.IO) {
            val subscriptions =
                try {
                    val input =
                        context.contentResolver.openInputStream(uri)
                            ?: throw PodcastException("OPML ファイルを開けませんでした。")
                    input.use(PodcastOpml::read)
                } catch (error: PodcastException) {
                    throw error
                } catch (_: java.io.IOException) {
                    throw PodcastException("OPML ファイルを開けませんでした。")
                } catch (_: SecurityException) {
                    throw PodcastException("OPML ファイルにアクセスできませんでした。")
                }
            mutex.withLock {
                val added = store.importSubscriptions(subscriptions.podcasts).size
                publish()
                OpmlImportResult(added, subscriptions.skipped + subscriptions.podcasts.size - added)
            }
        }

    suspend fun exportOpml(uri: Uri): Unit =
        withContext(Dispatchers.IO) {
            val podcasts = mutex.withLock { store.library().podcasts }
            try {
                val output =
                    context.contentResolver.openOutputStream(uri, "wt")
                        ?: throw PodcastException("OPML ファイルに保存できませんでした。")
                output.use { PodcastOpml.write(podcasts, it) }
            } catch (_: java.io.IOException) {
                throw PodcastException("OPML ファイルに保存できませんでした。")
            } catch (_: SecurityException) {
                throw PodcastException("OPML ファイルにアクセスできませんでした。")
            }
        }

    suspend fun subscribe(candidate: PodcastCandidate): Unit =
        withContext(Dispatchers.IO) {
            val result = fetch(candidate.podcast.feedUrl, FeedValidators())
            val feed = requireNotNull(result.feed)
            mutex.withLock {
                store.writableDatabase.transaction {
                    store.saveFeed(feed)
                    store.recordFeedUpdate(
                        feed.podcast.feedUrl,
                        result.validators,
                        System.currentTimeMillis(),
                    )
                }
                publish()
            }
            processAutomaticDownloads()
        }

    suspend fun refresh(
        feedUrl: String? = null,
        onProgress: (Int, Int) -> Unit = { _, _ -> },
    ): List<String> =
        withContext(Dispatchers.IO) {
            val podcasts =
                mutex.withLock {
                    store.library().podcasts.filter { feedUrl == null || it.feedUrl == feedUrl }
                }
            val completed = java.util.concurrent.atomic.AtomicInteger()
            val permits = refreshPermits
            var lastPublish = 0L
            val failed =
                kotlinx.coroutines.coroutineScope {
                    podcasts
                        .map { podcast ->
                            async {
                                feedRefreshLocks
                                    .getOrPut(podcast.feedUrl) { Mutex() }
                                    .withLock {
                                        permits.withPermit {
                                            try {
                                                val validators =
                                                    mutex.withLock {
                                                        store.feedValidators(podcast.feedUrl)
                                                    }
                                                val result = fetch(podcast.feedUrl, validators)
                                                val feed = result.feed
                                                mutex.withLock {
                                                    if (store.podcast(podcast.feedUrl) != null) {
                                                        val existing =
                                                            store.episodeIds(podcast.feedUrl)
                                                        store.writableDatabase.transaction {
                                                            if (feed != null) store.saveFeed(feed)
                                                            store.recordFeedUpdate(
                                                                podcast.feedUrl,
                                                                result.validators,
                                                                System.currentTimeMillis(),
                                                            )
                                                        }
                                                        if (
                                                            feed != null &&
                                                                settings
                                                                    .options(podcast.feedUrl)
                                                                    .notifications &&
                                                                (existing.isNotEmpty() ||
                                                                    preferences.getBoolean(
                                                                        "seen_feed:${stableId(podcast.feedUrl)}",
                                                                        false,
                                                                    ))
                                                        )
                                                            notifications.newEpisodes(
                                                                feed.podcast,
                                                                feed.episodes.filter {
                                                                    it.id !in existing
                                                                },
                                                            )
                                                        preferences.edit {
                                                            putBoolean(
                                                                "seen_feed:${stableId(podcast.feedUrl)}",
                                                                true,
                                                            )
                                                        }
                                                        val now =
                                                            android.os.SystemClock.elapsedRealtime()
                                                        if (now - lastPublish >= 750) {
                                                            publish()
                                                            lastPublish = now
                                                        }
                                                    }
                                                }
                                                null
                                            } catch (
                                                error: kotlinx.coroutines.CancellationException) {
                                                throw error
                                            } catch (error: java.io.IOException) {
                                                recordRefreshFailure(podcast, error)
                                            } catch (error: IllegalArgumentException) {
                                                recordRefreshFailure(podcast, error)
                                            } catch (error: android.database.SQLException) {
                                                recordRefreshFailure(podcast, error)
                                            } finally {
                                                onProgress(
                                                    completed.incrementAndGet(),
                                                    podcasts.size,
                                                )
                                            }
                                        }
                                    }
                            }
                        }
                        .awaitAll()
                        .filterNotNull()
                }
            mutex.withLock { publish() }
            processAutomaticDownloads()
            failed
        }

    private fun fetch(url: String, validators: FeedValidators): FeedFetchResult =
        fetchFeed?.let { FeedFetchResult(it(url), FeedValidators()) }
            ?: directory.fetchFeed(url, validators)

    private suspend fun recordRefreshFailure(podcast: Podcast, error: Exception): String {
        mutex.withLock {
            try {
                store.recordFeedUpdate(
                    podcast.feedUrl,
                    null,
                    System.currentTimeMillis(),
                    error.message ?: "更新できませんでした。",
                )
            } catch (_: android.database.SQLException) {
                /* A storage failure must not abort other subscriptions. */
            }
        }
        return podcast.title
    }

    suspend fun searchEpisodes(
        query: String,
        filter: EpisodeFilter,
        limit: Int,
    ): EpisodeSearchResult =
        withContext(Dispatchers.IO) { store.searchEpisodes(query, filter, limit) }

    internal suspend fun markPlayedWithUndo(id: String, completed: Boolean): LibraryUndo? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val change = store.markPlayedWithUndo(id, completed)
                publish()
                change
            }
        }

    internal suspend fun removeFromQueueWithUndo(queueId: String, id: String): LibraryUndo? =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val change = store.removeFromQueueWithUndo(queueId, id)
                publish()
                change
            }
        }

    internal suspend fun undo(change: LibraryUndo): Boolean =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val changed = store.undo(change)
                publish()
                changed
            }
        }

    suspend fun placeQueueEpisode(queueId: String, id: String, beforeId: String?): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.placeQueueEpisode(queueId, id, beforeId)
                publish()
            }
        }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun management(): LibraryManagement =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                publish()
                val library = mutableLibrary.value
                fun size(file: java.io.File?): Long =
                    file
                        ?.takeIf { it.exists() }
                        ?.walkTopDown()
                        ?.filter { it.isFile }
                        ?.sumOf { it.length() } ?: 0
                LibraryManagement(
                    StorageUsage(
                        size(
                            context.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS)
                        ),
                        (context.imageLoader.diskCache?.size ?: 0),
                        availableAudioBytes(),
                    ),
                    library.downloads
                        .mapNotNull { (id, download) ->
                            store.episode(id)?.let {
                                ManagedDownload(
                                    it,
                                    library.podcasts
                                        .firstOrNull { p -> p.feedUrl == it.feedUrl }
                                        ?.title
                                        .orEmpty(),
                                    download,
                                )
                            }
                        }
                        .sortedWith(
                            compareBy<ManagedDownload> {
                                    it.download.status == DownloadStatus.Complete
                                }
                                .thenByDescending { it.episode.published }
                        ),
                )
            }
        }

    private fun availableAudioBytes(): Long {
        val directory =
            context.getExternalFilesDir(android.os.Environment.DIRECTORY_PODCASTS)
                ?: context.filesDir
        // Report the actual volume's free space. DownloadManager owns allocation/reclaim policy.
        return android.os.StatFs(directory.path).availableBytes
    }

    @OptIn(coil.annotation.ExperimentalCoilApi::class)
    suspend fun clearArtworkCache(): Unit =
        withContext(Dispatchers.IO) {
            context.imageLoader.memoryCache?.clear()
            context.imageLoader.diskCache?.clear()
        }

    suspend fun quickQueue(id: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val queueId =
                    settings.defaultQueue.takeIf { id -> store.queues().any { it.id == id } }
                        ?: "default"
                settings.setDefaultQueue(queueId)
                if (store.queues().none { it.id == queueId }) store.saveQueue(queueId, "あとで聴く")
                require(!store.progress(id).completed) { "未再生に戻してから追加してください。" }
                store.addToQueue(queueId, id)
                publish()
            }
        }

    suspend fun setWifiOnly(enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                settings.setWifiOnly(enabled)
                // Recreate outstanding requests so a newly enabled restriction takes effect
                // immediately.
                val pending =
                    downloads()
                        .filterValues { it.status != DownloadStatus.Complete }
                        .keys
                        .mapNotNull(store::episode)
                pending.forEach {
                    downloadStorage.remove(it.id)
                    downloadStorage.enqueue(it)
                }
                publish()
            }
        }

    suspend fun loadMoreHistory(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                historyLimit += 150
                publish()
            }
        }

    suspend fun loadChapters(id: String): List<EpisodeChapter> =
        withContext(Dispatchers.IO) {
            val episode = mutex.withLock { store.episode(id) } ?: return@withContext emptyList()
            if (episode.chapters.isNotEmpty() || episode.chaptersUrl.isBlank()) episode.chapters
            else
                EpisodeChapters.decode(
                    PodcastHttp().get(episode.chaptersUrl).body,
                    episode.chaptersUrl,
                )
        }

    suspend fun setPodcastOptions(feed: String, value: PodcastOptions): Unit {
        settings.setOptions(feed, value)
        configureBackgroundSync()
    }

    suspend fun loadMoreEpisodes(): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                episodeLimit += 500
                publish()
            }
        }

    suspend fun podcastEpisodes(feed: String, limit: Int): PodcastEpisodePage =
        withContext(Dispatchers.IO) { mutex.withLock { store.podcastEpisodes(feed, limit) } }

    suspend fun episodeDescription(id: String): String =
        withContext(Dispatchers.IO) { mutex.withLock { store.episode(id)?.description.orEmpty() } }

    suspend fun setFavorite(id: String, favorite: Boolean): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.setFavorite(id, favorite)
                publish()
            }
        }

    suspend fun unsubscribe(podcast: Podcast): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                for (episode in store.library().episodes.filter { it.feedUrl == podcast.feedUrl }) {
                    downloadStorage.remove(episode.id)
                }
                store.unsubscribe(podcast.feedUrl)
                publish()
            }
            configureBackgroundSync()
        }

    suspend fun download(episode: Episode, automatic: Boolean = false): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (
                    automatic &&
                        (episode.id !in store.automaticDownloadIds() ||
                            store.progress(episode.id).completed)
                )
                    return@withLock
                val existing = downloads()[episode.id]
                if (existing != null && existing.status != DownloadStatus.Failed) return@withLock
                downloadStorage.enqueue(episode)
                publish()
            }
        }

    suspend fun retryDownload(episode: Episode): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (downloads()[episode.id]?.status == DownloadStatus.Complete) return@withLock
                downloadStorage.enqueue(episode)
                publish()
            }
        }

    suspend fun removeDownload(episode: Episode): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.cancelAutomaticDownload(episode.id)
                downloadStorage.remove(episode.id)
                publish()
            }
        }

    suspend fun updateProgress(
        id: String,
        progress: ListeningProgress,
        resetCompletion: Boolean = false,
        listened: Boolean = false,
        finished: Boolean = false,
    ): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.saveProgress(id, progress, resetCompletion, listened)
                if (finished && progress.completed) downloadStorage.remove(id)
                publish()
            }
        }

    suspend fun saveQueue(id: String, name: String, episodeId: String? = null): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (episodeId != null)
                    require(!store.progress(episodeId).completed) {
                        "再生済みのエピソードは、未再生に戻してから追加してください。"
                    }
                store.saveQueue(id, name)
                if (episodeId != null) store.addToQueue(id, episodeId)
                publish()
            }
        }

    suspend fun deleteQueue(id: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.deleteQueue(id)
                publish()
            }
            configureBackgroundSync()
        }

    suspend fun clearQueueAndDownloads(id: String): Set<String> =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                val episodes = store.queueEpisodeIds(id).toSet()
                for (episodeId in episodes) {
                    store.cancelAutomaticDownload(episodeId)
                    downloadStorage.remove(episodeId)
                }
                store.clearQueue(id)
                publish()
                episodes
            }
        }

    suspend fun setQueueMembership(queueId: String, episodeId: String, included: Boolean): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (included) {
                    require(!store.progress(episodeId).completed) {
                        "再生済みのエピソードは、未再生に戻してから追加してください。"
                    }
                    store.addToQueue(queueId, episodeId)
                } else store.removeFromQueue(queueId, episodeId)
                publish()
            }
        }

    suspend fun moveInQueue(queueId: String, episodeId: String, direction: Int): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.moveInQueue(queueId, episodeId, direction)
                publish()
            }
        }

    suspend fun saveRule(rule: QueueRule, applyExisting: Boolean): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (rule.queueId == "default" && store.queues().none { it.id == "default" })
                    store.saveQueue("default", "あとで聴く")
                store.saveRule(rule, applyExisting)
                publish()
            }
            configureBackgroundSync()
            processAutomaticDownloads()
        }

    suspend fun deleteRule(queueId: String, feedUrl: String): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.deleteRule(queueId, feedUrl)
                publish()
            }
            configureBackgroundSync()
        }

    suspend fun setAutoDownload(feedUrl: String, enabled: Boolean): Unit =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                store.setAutoDownload(feedUrl, enabled)
                publish()
            }
            configureBackgroundSync()
        }

    suspend fun configureBackgroundSync(): Unit =
        withContext(Dispatchers.IO) {
            PodcastSyncJob.schedule(
                context,
                store.library().podcasts.isNotEmpty(),
                settings.refreshHours,
            )
        }

    suspend fun processAutomaticDownloads(): Unit =
        withContext(Dispatchers.IO) {
            for (id in store.automaticDownloadIds()) {
                val episode = store.episode(id)
                val library = store.library()
                val eligible =
                    episode != null &&
                        !store.progress(id).completed &&
                        (episode.feedUrl in library.autoDownloadFeeds ||
                            library.queueRules.any { rule ->
                                rule.autoDownload &&
                                    rule.matches(episode) &&
                                    library.queues.any {
                                        it.id == rule.queueId && id in it.episodeIds
                                    }
                            })
                if (!eligible || downloads()[id]?.status == DownloadStatus.Complete) {
                    mutex.withLock { store.cancelAutomaticDownload(id) }
                    continue
                }
                try {
                    download(requireNotNull(episode), automatic = true)
                } catch (_: IllegalArgumentException) {
                    /* Keep the durable request for a later retry. */
                } catch (_: SecurityException) {
                    /* Storage can become available again later. */
                }
            }
            configureBackgroundSync()
        }

    fun audioUri(episode: Episode): Uri = downloadStorage.audioUri(episode, mutableLibrary.value)

    private fun publish(): Unit {
        mutableLibrary.value =
            store
                .library(episodeLimit, preferences.getString("current", null), historyLimit)
                .copy(downloads = downloads())
    }

    private fun downloads(): Map<String, EpisodeDownload> = downloadStorage.downloads()
}
