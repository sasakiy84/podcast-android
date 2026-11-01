package io.github.sasakiy84.podcast

import android.content.Context
import android.net.Uri
import androidx.core.content.edit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class PodcastRepository(private val context: Context, private val directory: PodcastDirectory = PodcastDirectory(), private val fetchFeed: (String) -> PodcastFeed = directory::fetchFeed) {
    val store: PodcastStore = PodcastStore(context)
    val preferences: android.content.SharedPreferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    val settings = PlaybackPreferences(preferences)
    private var episodeLimit = 1000
    private var historyLimit = 150
    private val notifications = PodcastNotifications(context)
    private val refreshPermits = kotlinx.coroutines.sync.Semaphore(3)
    private val downloadStorage = PodcastDownloadStorage(context, store)
    private val mutex = Mutex()
    private val mutableLibrary = MutableStateFlow(Library())
    val library: StateFlow<Library> = mutableLibrary

    suspend fun load(): Unit = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    suspend fun search(query: String): List<PodcastCandidate> = withContext(Dispatchers.IO) { directory.search(query) }
    suspend fun resolve(url: String): List<PodcastCandidate> = withContext(Dispatchers.IO) { directory.resolve(url) }

    suspend fun importOpml(uri: Uri): OpmlImportResult = withContext(Dispatchers.IO) {
        val subscriptions = try {
            val input = context.contentResolver.openInputStream(uri)
                ?: throw PodcastException("OPML ファイルを開けませんでした。")
            input.use(PodcastOpml::read)
        } catch (error: PodcastException) { throw error }
        catch (_: java.io.IOException) { throw PodcastException("OPML ファイルを開けませんでした。") }
        catch (_: SecurityException) { throw PodcastException("OPML ファイルにアクセスできませんでした。") }
        mutex.withLock {
            val added = store.importSubscriptions(subscriptions.podcasts).size
            publish()
            OpmlImportResult(added, subscriptions.skipped + subscriptions.podcasts.size - added)
        }
    }

    suspend fun exportOpml(uri: Uri): Unit = withContext(Dispatchers.IO) {
        val podcasts = mutex.withLock { store.library().podcasts }
        try {
            val output = context.contentResolver.openOutputStream(uri, "wt")
                ?: throw PodcastException("OPML ファイルに保存できませんでした。")
            output.use { PodcastOpml.write(podcasts, it) }
        } catch (_: java.io.IOException) { throw PodcastException("OPML ファイルに保存できませんでした。") }
        catch (_: SecurityException) { throw PodcastException("OPML ファイルにアクセスできませんでした。") }
    }

    suspend fun subscribe(candidate: PodcastCandidate): Unit = withContext(Dispatchers.IO) {
        val feed = fetchFeed(candidate.podcast.feedUrl)
        mutex.withLock { store.saveFeed(feed); publish() }
        processAutomaticDownloads()
    }

    suspend fun refresh(onProgress: (Int, Int) -> Unit = { _, _ -> }): List<String> = withContext(Dispatchers.IO) {
        val podcasts = mutex.withLock { store.library().podcasts }
        val completed = java.util.concurrent.atomic.AtomicInteger()
        val permits = refreshPermits
        var lastPublish = 0L
        val failed = kotlinx.coroutines.coroutineScope {
            podcasts.map { podcast ->
                async {
                    permits.withPermit {
                        try {
                            val feed = fetchFeed(podcast.feedUrl)
                            mutex.withLock {
                                if (store.podcast(podcast.feedUrl) != null) {
                                    val existing = store.episodeIds(podcast.feedUrl)
                                    store.saveFeed(feed)
                                    if (settings.options(podcast.feedUrl).notifications && (existing.isNotEmpty() || preferences.getBoolean("seen_feed:${stableId(podcast.feedUrl)}", false))) notifications.newEpisodes(feed.podcast, feed.episodes.filter { it.id !in existing })
                                    preferences.edit { putBoolean("seen_feed:${stableId(podcast.feedUrl)}", true) }
                                    val now = android.os.SystemClock.elapsedRealtime()
                                    if (now - lastPublish >= 750) { publish(); lastPublish = now }
                                }
                            }
                            null
                        } catch (error: kotlinx.coroutines.CancellationException) { throw error }
                        catch (_: java.io.IOException) { podcast.title }
                        catch (_: IllegalArgumentException) { podcast.title }
                        catch (_: android.database.SQLException) { podcast.title }
                        finally { onProgress(completed.incrementAndGet(), podcasts.size) }
                    }
                }
            }.awaitAll().filterNotNull()
        }
        mutex.withLock { publish() }
        processAutomaticDownloads()
        failed
    }

    suspend fun loadMoreHistory(): Unit = withContext(Dispatchers.IO) { mutex.withLock { historyLimit += 150; publish() } }
    suspend fun loadChapters(id: String): List<EpisodeChapter> = withContext(Dispatchers.IO) {
        val episode = mutex.withLock { store.episode(id) } ?: return@withContext emptyList()
        if (episode.chapters.isNotEmpty() || episode.chaptersUrl.isBlank()) episode.chapters
        else EpisodeChapters.decode(PodcastHttp().get(episode.chaptersUrl).body, episode.chaptersUrl)
    }
    suspend fun setPodcastOptions(feed: String, value: PodcastOptions): Unit { settings.setOptions(feed, value); configureBackgroundSync() }

    suspend fun loadMoreEpisodes(): Unit = withContext(Dispatchers.IO) { mutex.withLock { episodeLimit += 500; publish() } }
    suspend fun podcastEpisodes(feed: String, limit: Int): PodcastEpisodePage = withContext(Dispatchers.IO) { mutex.withLock { store.podcastEpisodes(feed, limit) } }
    suspend fun episodeDescription(id: String): String = withContext(Dispatchers.IO) { mutex.withLock { store.episode(id)?.description.orEmpty() } }
    suspend fun setFavorite(id: String, favorite: Boolean): Unit = withContext(Dispatchers.IO) { mutex.withLock { store.setFavorite(id, favorite); publish() } }

    suspend fun unsubscribe(podcast: Podcast): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            for (episode in store.library().episodes.filter { it.feedUrl == podcast.feedUrl }) {
                downloadStorage.remove(episode.id)
            }
            store.unsubscribe(podcast.feedUrl)
            publish()
        }
        configureBackgroundSync()
    }

    suspend fun download(episode: Episode, automatic: Boolean = false): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (automatic && (episode.id !in store.automaticDownloadIds() || store.progress(episode.id).completed)) return@withLock
            val existing = downloads()[episode.id]
            if (existing != null && existing.status != DownloadStatus.Failed) return@withLock
            downloadStorage.enqueue(episode)
            publish()
        }
    }

    suspend fun removeDownload(episode: Episode): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.cancelAutomaticDownload(episode.id)
            downloadStorage.remove(episode.id)
            publish()
        }
    }

    suspend fun updateProgress(id: String, progress: ListeningProgress, resetCompletion: Boolean = false, listened: Boolean = false, finished: Boolean = false): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.saveProgress(id, progress, resetCompletion, listened)
            if (finished && progress.completed) downloadStorage.remove(id)
            publish()
        }
    }

    suspend fun saveQueue(id: String, name: String, episodeId: String? = null): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (episodeId != null) require(!store.progress(episodeId).completed) { "再生済みのエピソードは、未再生に戻してから追加してください。" }
            store.saveQueue(id, name)
            if (episodeId != null) store.addToQueue(id, episodeId)
            publish()
        }
    }
    suspend fun deleteQueue(id: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { store.deleteQueue(id); publish() }
        configureBackgroundSync()
    }
    suspend fun setQueueMembership(queueId: String, episodeId: String, included: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (included) {
                require(!store.progress(episodeId).completed) { "再生済みのエピソードは、未再生に戻してから追加してください。" }
                store.addToQueue(queueId, episodeId)
            } else store.removeFromQueue(queueId, episodeId)
            publish()
        }
    }
    suspend fun moveInQueue(queueId: String, episodeId: String, direction: Int): Unit = withContext(Dispatchers.IO) { mutex.withLock { store.moveInQueue(queueId, episodeId, direction); publish() } }
    suspend fun saveRule(rule: QueueRule, applyExisting: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { store.saveRule(rule, applyExisting); publish() }
        configureBackgroundSync()
        processAutomaticDownloads()
    }
    suspend fun deleteRule(queueId: String, feedUrl: String): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { store.deleteRule(queueId, feedUrl); publish() }
        configureBackgroundSync()
    }
    suspend fun setAutoDownload(feedUrl: String, enabled: Boolean): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { store.setAutoDownload(feedUrl, enabled); publish() }
        configureBackgroundSync()
    }

    suspend fun configureBackgroundSync(): Unit = withContext(Dispatchers.IO) { PodcastSyncJob.schedule(context, store.needsBackgroundSync() || store.library().podcasts.any { settings.options(it.feedUrl).notifications }) }

    suspend fun processAutomaticDownloads(): Unit = withContext(Dispatchers.IO) {
        for (id in store.automaticDownloadIds()) {
            val episode = store.episode(id)
            val library = store.library()
            val eligible = episode != null && !store.progress(id).completed &&
                (episode.feedUrl in library.autoDownloadFeeds || library.queueRules.any { rule -> rule.autoDownload && rule.matches(episode) && library.queues.any { it.id == rule.queueId && id in it.episodeIds } })
            if (!eligible || downloads()[id]?.status == DownloadStatus.Complete) {
                mutex.withLock { store.cancelAutomaticDownload(id) }
                continue
            }
            try { download(requireNotNull(episode), automatic = true) }
            catch (_: IllegalArgumentException) { /* Keep the durable request for a later retry. */ }
            catch (_: SecurityException) { /* Storage can become available again later. */ }
        }
        configureBackgroundSync()
    }

    fun audioUri(episode: Episode): Uri = downloadStorage.audioUri(episode, mutableLibrary.value)

    private fun publish(): Unit { mutableLibrary.value = store.library(episodeLimit, preferences.getString("current", null), historyLimit).copy(downloads = downloads()) }

    private fun downloads(): Map<String, EpisodeDownload> = downloadStorage.downloads()
}
