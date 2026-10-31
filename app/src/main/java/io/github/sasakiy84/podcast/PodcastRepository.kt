package io.github.sasakiy84.podcast

import android.app.Application
import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File

class PodcastApplication : Application() {
    val repository: PodcastRepository by lazy { PodcastRepository(this) }

}

class PodcastRepository(private val context: Context) {
    val store: PodcastStore = PodcastStore(context)
    val preferences: android.content.SharedPreferences = context.getSharedPreferences("playback", Context.MODE_PRIVATE)
    private val directory = PodcastDirectory()
    private val manager = context.getSystemService(DownloadManager::class.java)
    private val mutex = Mutex()
    private val mutableLibrary = MutableStateFlow(Library())
    val library: StateFlow<Library> = mutableLibrary

    suspend fun load(): Unit = withContext(Dispatchers.IO) { mutex.withLock { publish() } }

    suspend fun search(query: String): List<PodcastCandidate> = withContext(Dispatchers.IO) { directory.search(query) }
    suspend fun resolve(url: String): List<PodcastCandidate> = withContext(Dispatchers.IO) { directory.resolve(url) }

    suspend fun subscribe(candidate: PodcastCandidate): Unit = withContext(Dispatchers.IO) {
        val feed = directory.fetchFeed(candidate.podcast.feedUrl)
        mutex.withLock { store.saveFeed(feed); publish() }
        processAutomaticDownloads()
    }

    suspend fun refresh(): List<String> = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        for (podcast in store.library().podcasts) {
            try {
                val feed = directory.fetchFeed(podcast.feedUrl)
                mutex.withLock { store.saveFeed(feed); publish() }
            } catch (_: java.io.IOException) { failed += podcast.title }
        }
        processAutomaticDownloads()
        failed
    }

    suspend fun unsubscribe(podcast: Podcast): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            val ids = store.downloadIds()
            for (episode in store.library().episodes.filter { it.feedUrl == podcast.feedUrl }) {
                ids[episode.id]?.let { manager.remove(it) }
                downloadFile(episode.id)?.delete()
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
            store.downloadIds()[episode.id]?.let { manager.remove(it) }
            downloadFile(episode.id)?.delete()
            val request = DownloadManager.Request(podcastUrl(episode.audioUrl).toASCIIString().toUri())
                .setTitle(episode.title).setDescription("Podcast Android")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, "${episode.id}.audio")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            store.saveDownload(episode.id, manager.enqueue(request))
            publish()
        }
    }

    suspend fun removeDownload(episode: Episode): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
            store.cancelAutomaticDownload(episode.id)
            store.downloadIds()[episode.id]?.let { manager.remove(it) }
            downloadFile(episode.id)?.delete()
            store.removeDownload(episode.id)
            publish()
        }
    }

    suspend fun updateProgress(id: String, progress: ListeningProgress, resetCompletion: Boolean = false): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { store.saveProgress(id, progress, resetCompletion); publish() }
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

    suspend fun configureBackgroundSync(): Unit = withContext(Dispatchers.IO) { PodcastSyncJob.schedule(context, store.needsBackgroundSync()) }

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

    fun audioUri(episode: Episode): Uri {
        val file = downloadFile(episode.id)
        if (file?.isFile != true) return episode.audioUrl.toUri()
        if (mutableLibrary.value.downloads[episode.id]?.status == DownloadStatus.Complete) return Uri.fromFile(file)
        val managerId = store.downloadId(episode.id) ?: return episode.audioUrl.toUri()
        val complete = manager.query(DownloadManager.Query().setFilterById(managerId)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        return if (complete) Uri.fromFile(file) else episode.audioUrl.toUri()
    }

    private fun publish(): Unit { mutableLibrary.value = store.library().copy(downloads = downloads()) }

    private fun downloadFile(id: String): File? = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)?.let { File(it, "$id.audio") }

    private fun downloads(): Map<String, EpisodeDownload> {
        val ids = store.downloadIds()
        if (ids.isEmpty()) return emptyMap()
        val result = ids.mapValues { EpisodeDownload(it.value, DownloadStatus.Failed) }.toMutableMap()
        manager.query(DownloadManager.Query().setFilterById(*ids.values.toLongArray())).use { cursor ->
            while (cursor.moveToNext()) {
                val managerId = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val id = ids.entries.firstOrNull { it.value == managerId }?.key ?: continue
                val status = cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES))
                val downloaded = cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR))
                val state = when (status) {
                    DownloadManager.STATUS_SUCCESSFUL -> if (downloadFile(id)?.isFile == true) DownloadStatus.Complete else DownloadStatus.Failed
                    DownloadManager.STATUS_RUNNING -> DownloadStatus.Running
                    DownloadManager.STATUS_PENDING, DownloadManager.STATUS_PAUSED -> DownloadStatus.Waiting
                    else -> DownloadStatus.Failed
                }
                result[id] = EpisodeDownload(managerId, state, if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0)
            }
        }
        return result
    }
}
