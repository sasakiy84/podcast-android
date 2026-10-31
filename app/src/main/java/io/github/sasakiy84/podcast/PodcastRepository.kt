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
    }

    suspend fun refresh(): List<String> = withContext(Dispatchers.IO) {
        val failed = mutableListOf<String>()
        for (podcast in store.library().podcasts) {
            try {
                val feed = directory.fetchFeed(podcast.feedUrl)
                mutex.withLock { store.saveFeed(feed); publish() }
            } catch (_: java.io.IOException) { failed += podcast.title }
        }
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
    }

    suspend fun download(episode: Episode): Unit = withContext(Dispatchers.IO) {
        mutex.withLock {
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
            store.downloadIds()[episode.id]?.let { manager.remove(it) }
            downloadFile(episode.id)?.delete()
            store.removeDownload(episode.id)
            publish()
        }
    }

    suspend fun updateProgress(id: String, progress: ListeningProgress): Unit = withContext(Dispatchers.IO) {
        mutex.withLock { store.saveProgress(id, progress); publish() }
    }

    fun audioUri(episode: Episode): Uri {
        val file = downloadFile(episode.id)
        val state = downloads()[episode.id]
        return if (state?.status == DownloadStatus.Complete && file?.isFile == true) Uri.fromFile(file) else episode.audioUrl.toUri()
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
