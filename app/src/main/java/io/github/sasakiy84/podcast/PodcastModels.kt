package io.github.sasakiy84.podcast

import java.net.URI
import java.security.MessageDigest

data class Podcast(
    val feedUrl: String,
    val title: String,
    val author: String = "",
    val artwork: String = "",
    val description: String = "",
)

data class Episode(
    val id: String,
    val feedUrl: String,
    val title: String,
    val audioUrl: String,
    val description: String = "",
    val published: Long = 0,
    val durationMs: Long = 0,
    val artwork: String = "",
)

data class PodcastFeed(val podcast: Podcast, val episodes: List<Episode>)
data class PodcastCandidate(val podcast: Podcast, val source: String = "Apple Podcasts")
data class ListeningProgress(val positionMs: Long = 0, val durationMs: Long = 0, val completed: Boolean = false)
enum class DownloadStatus { Waiting, Running, Complete, Failed }
data class EpisodeDownload(val managerId: Long, val status: DownloadStatus, val percent: Int = 0)
data class Library(
    val podcasts: List<Podcast> = emptyList(),
    val episodes: List<Episode> = emptyList(),
    val progress: Map<String, ListeningProgress> = emptyMap(),
    val downloads: Map<String, EpisodeDownload> = emptyMap(),
)

class PodcastException(message: String) : java.io.IOException(message)

fun stableId(value: String): String = MessageDigest.getInstance("SHA-256")
    .digest(value.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

fun podcastUrl(input: String): URI {
    val value = input.trim()
    val uri = try {
        URI(if (value.contains("://")) value else "https://$value")
    } catch (_: java.net.URISyntaxException) {
        throw PodcastException("URL の形式を確認してください。")
    }
    if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank() || uri.userInfo != null) {
        throw PodcastException("http または https の番組URL・RSS URLを入力してください。")
    }
    return uri
}

fun formatTime(milliseconds: Long): String {
    val seconds = milliseconds.coerceAtLeast(0) / 1000
    return if (seconds >= 3600) "%d:%02d:%02d".format(seconds / 3600, seconds / 60 % 60, seconds % 60)
    else "%d:%02d".format(seconds / 60, seconds % 60)
}
