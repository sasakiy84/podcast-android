package io.github.sasakiy84.podcast

data class FeedValidators(
    val etag: String = "",
    val lastModified: String = "",
    val responseUrl: String = "",
)

data class FeedFetchResult(val feed: PodcastFeed?, val validators: FeedValidators)

data class FeedUpdateStatus(
    val checkedMs: Long = 0,
    val updatedMs: Long = 0,
    val error: String = "",
)

enum class EpisodeFilter(val label: String) {
    All("すべて"),
    Unplayed("未再生"),
    Downloaded("ダウンロード"),
    Favorites("お気に入り"),
    History("再生履歴"),
}

data class EpisodeSearchResult(
    val episodes: List<Episode> = emptyList(),
    val hasMore: Boolean = false,
)

data class EpisodeSearchState(
    val query: String = "",
    val filter: EpisodeFilter = EpisodeFilter.All,
    val result: EpisodeSearchResult = EpisodeSearchResult(),
    val loading: Boolean = false,
    val error: String? = null,
)

data class StorageUsage(
    val audioBytes: Long = 0,
    val artworkBytes: Long = 0,
    val freeBytes: Long = 0,
)

data class ManagedDownload(
    val episode: Episode,
    val podcastTitle: String,
    val download: EpisodeDownload,
)

data class LibraryManagement(
    val storage: StorageUsage = StorageUsage(),
    val downloads: List<ManagedDownload> = emptyList(),
)

data class UndoNotice(val id: String, val message: String, val expiresAt: Long)

internal data class QueuePlacement(
    val queueId: String,
    val index: Int,
    val previousId: String?,
    val nextId: String?,
)

internal sealed interface LibraryUndo {
    data class Played(
        val episodeId: String,
        val before: ListeningProgress?,
        val after: ListeningProgress,
        val placements: List<QueuePlacement>,
        val pendingDownload: Boolean,
        val listenedAt: Long?,
    ) : LibraryUndo

    data class Removed(val episodeId: String, val placement: QueuePlacement) : LibraryUndo
}
