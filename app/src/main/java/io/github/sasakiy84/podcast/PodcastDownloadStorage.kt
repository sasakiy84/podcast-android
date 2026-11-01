package io.github.sasakiy84.podcast

import android.app.DownloadManager
import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.io.File

/** Owns DownloadManager and private audio files. Repository serializes mutations. */
internal class PodcastDownloadStorage(private val context: Context, private val store: PodcastStore) {
    private val manager = context.getSystemService(DownloadManager::class.java)
    fun enqueue(episode: Episode): Unit {
            store.downloadIds()[episode.id]?.let { manager.remove(it) }
            downloadFile(episode.id)?.delete()
            val request = DownloadManager.Request(podcastUrl(episode.audioUrl).toASCIIString().toUri())
                .setTitle(episode.title).setDescription("Podcast Android")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(context, Environment.DIRECTORY_PODCASTS, "${episode.id}.audio")
                .setAllowedOverMetered(true)
                .setAllowedOverRoaming(false)
            store.saveDownload(episode.id, manager.enqueue(request))
    }
    fun remove(id: String): Unit {
        store.downloadIds()[id]?.let { manager.remove(it) }
        downloadFile(id)?.delete()
        store.removeDownload(id)
    }
    fun audioUri(episode: Episode, library: Library): Uri {
        val file = downloadFile(episode.id)
        if (file?.isFile != true) return episode.audioUrl.toUri()
        if (library.downloads[episode.id]?.status == DownloadStatus.Complete) return Uri.fromFile(file)
        val managerId = store.downloadId(episode.id) ?: return episode.audioUrl.toUri()
        val complete = manager.query(DownloadManager.Query().setFilterById(managerId)).use { cursor ->
            cursor.moveToFirst() && cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) == DownloadManager.STATUS_SUCCESSFUL
        }
        return if (complete) Uri.fromFile(file) else episode.audioUrl.toUri()
    }

    private fun downloadFile(id: String): File? = context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)?.let { File(it, "$id.audio") }

    fun downloads(): Map<String, EpisodeDownload> {
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
