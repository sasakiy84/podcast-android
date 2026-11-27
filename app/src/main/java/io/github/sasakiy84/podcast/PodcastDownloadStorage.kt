package io.github.sasakiy84.podcast

import android.app.DownloadManager
import android.content.Context
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import java.io.File

/** Owns DownloadManager and private audio files. Repository serializes mutations. */
internal class PodcastDownloadStorage(
    private val context: Context,
    private val store: PodcastStore,
    private val loudnessProfiles: LoudnessProfiles,
) {
    private val manager = context.getSystemService(DownloadManager::class.java)

    fun enqueue(episode: Episode, unmetered: Boolean = false): Unit {
        val error = try {
            val request = DownloadManager.Request(podcastUrl(episode.audioUrl).toASCIIString().toUri())
                .setTitle(episode.title)
                .setDescription(context.getString(R.string.app_name))
                .setNotificationVisibility(
                    DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED
                )
                .setDestinationInExternalFilesDir(
                    context,
                    Environment.DIRECTORY_PODCASTS,
                    "${episode.id}.audio",
                )
                .setAllowedOverMetered(!unmetered)
                .setAllowedOverRoaming(false)
            remove(episode.id)
            val managerId = manager.enqueue(request)
            if (managerId >= 0) {
                try {
                    store.saveDownload(episode.id, managerId, unmetered)
                } catch (error: android.database.SQLException) {
                    manager.remove(managerId)
                    throw error
                }
                return
            }
            "ダウンロードを開始できませんでした"
        } catch (_: PodcastException) {
            "音声 URL を利用できません"
        } catch (_: IllegalArgumentException) {
            "音声 URL を利用できません"
        } catch (_: IllegalStateException) {
            "保存先を利用できません"
        } catch (_: SecurityException) {
            "保存先へのアクセスが拒否されました"
        }
        if (store.downloadId(episode.id) == null) {
            store.saveDownload(episode.id, NO_DOWNLOAD_REQUEST, error = error)
        }
    }

    fun remove(id: String): Unit {
        loudnessProfiles.remove(id)
        store.downloadId(id)?.let { manager.remove(it) }
        downloadFile(id)?.delete()
        store.removeDownload(id)
    }

    fun audioUri(episode: Episode, library: Library): Uri {
        val file = downloadFile(episode.id)
        if (file?.isFile != true) return episode.audioUrl.toUri()
        if (library.downloads[episode.id]?.status == DownloadStatus.Complete)
            return Uri.fromFile(file)
        val managerId = store.downloadId(episode.id) ?: return episode.audioUrl.toUri()
        val complete =
            manager.query(DownloadManager.Query().setFilterById(managerId)).use { cursor ->
                cursor.moveToFirst() &&
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS)) ==
                        DownloadManager.STATUS_SUCCESSFUL
            }
        return if (complete) Uri.fromFile(file) else episode.audioUrl.toUri()
    }

    fun downloadFile(id: String): File? =
        context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS)?.let { File(it, "$id.audio") }

    fun downloads(): Map<String, EpisodeDownload> {
        val records = store.downloadRecords()
        if (records.isEmpty()) return emptyMap()
        val reverse = records.entries.filter { it.value.managerId >= 0 }
            .associate { it.value.managerId to it.key }
        val onMeteredNetwork by lazy {
            context.getSystemService(ConnectivityManager::class.java).isActiveNetworkMetered
        }
        // Our database insertion order survives restarts and does not depend on
        // publication dates, progress timestamps, or opaque DownloadManager IDs.
        val result = mutableMapOf<String, EpisodeDownload>()
        records.entries.forEachIndexed { order, (id, record) ->
            result[id] =
                EpisodeDownload(record.managerId, DownloadStatus.Failed, reason = record.error,
                    downloadOrder = order.toLong())
        }
        if (reverse.isEmpty()) return result
        manager.query(DownloadManager.Query().setFilterById(*reverse.keys.toLongArray())).use { cursor
            ->
            while (cursor.moveToNext()) {
                val managerId =
                    cursor.getLong(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_ID))
                val id = reverse[managerId] ?: continue
                val status =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))
                val total =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)
                    )
                val downloaded =
                    cursor.getLong(
                        cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)
                    )
                val state =
                    when (status) {
                        DownloadManager.STATUS_SUCCESSFUL ->
                            if (downloadFile(id)?.isFile == true) DownloadStatus.Complete
                            else DownloadStatus.Failed
                        DownloadManager.STATUS_RUNNING -> DownloadStatus.Running
                        DownloadManager.STATUS_PENDING,
                        DownloadManager.STATUS_PAUSED -> DownloadStatus.Waiting
                        else -> DownloadStatus.Failed
                    }
                val code =
                    cursor.getInt(cursor.getColumnIndexOrThrow(DownloadManager.COLUMN_REASON))
                val waitingForWifi = state == DownloadStatus.Waiting &&
                    (code == DownloadManager.PAUSED_QUEUED_FOR_WIFI ||
                        (records.getValue(id).unmetered && onMeteredNetwork))
                val reason =
                    when {
                        state == DownloadStatus.Complete || state == DownloadStatus.Running -> ""
                        state == DownloadStatus.Failed &&
                            status == DownloadManager.STATUS_SUCCESSFUL -> "保存ファイルが見つかりません"
                        state == DownloadStatus.Failed ->
                            when (code) {
                                DownloadManager.ERROR_INSUFFICIENT_SPACE -> "空き容量が不足しています"
                                DownloadManager.ERROR_DEVICE_NOT_FOUND -> "保存先を利用できません"
                                DownloadManager.ERROR_FILE_ERROR -> "ファイルを保存できませんでした"
                                in 400..599 -> "配信元がエラーを返しました（HTTP $code）"
                                else -> "ダウンロードに失敗しました"
                            }
                        waitingForWifi -> "Wi-Fi 接続待ち"
                        code == DownloadManager.PAUSED_WAITING_FOR_NETWORK -> "ネットワーク接続待ち"
                        code == DownloadManager.PAUSED_WAITING_TO_RETRY -> "再試行待ち"
                        else -> "開始待ち"
                    }
                val fileSize = if (state == DownloadStatus.Complete) downloadFile(id)?.length() else null
                result[id] =
                    EpisodeDownload(
                        managerId,
                        state,
                        if (total > 0) (downloaded * 100 / total).toInt().coerceIn(0, 100) else 0,
                        fileSize ?: downloaded.coerceAtLeast(0),
                        fileSize ?: total,
                        reason,
                        result.getValue(id).downloadOrder,
                        canStartManually = waitingForWifi,
                    )
            }
        }
        return result
    }
}

internal const val NO_DOWNLOAD_REQUEST = -1L
