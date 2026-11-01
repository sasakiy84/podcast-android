package io.github.sasakiy84.podcast

data class PlaybackUi(
    val episodeId: String? = null,
    val playing: Boolean = false,
    val buffering: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val speed: Float = 1f,
    val connected: Boolean = false,
)

data class PodcastUiState(
    val library: Library = Library(),
    val results: List<PodcastCandidate> = emptyList(),
    val searching: Boolean = false,
    val searchError: String? = null,
    val searched: Boolean = false,
    val refreshing: Boolean = false,
    val opmlBusy: Boolean = false,
    val refreshCompleted: Int = 0,
    val refreshTotal: Int = 0,
    val podcastSort: PodcastSort = PodcastSort.Title,
    val globalSpeed: Float = 1f,
    val podcastOptions: Map<String, PodcastOptions> = emptyMap(),
    val chapters: Map<String, List<EpisodeChapter>> = emptyMap(),
    val chaptersLoading: Set<String> = emptySet(),
    val chapterErrors: Map<String, String> = emptyMap(),
    val podcastSpeeds: Map<String, Float> = emptyMap(),
    val podcastPages: Map<String, PodcastEpisodePage> = emptyMap(),
    val descriptions: Map<String, String> = emptyMap(),
    val busyFeeds: Set<String> = emptySet(),
    val message: String? = null,
    val playback: PlaybackUi = PlaybackUi(),
)

sealed interface PodcastAction {
    data class Search(val query: String) : PodcastAction
    data class Resolve(val url: String) : PodcastAction
    data class Subscribe(val candidate: PodcastCandidate) : PodcastAction
    data class Unsubscribe(val podcast: Podcast) : PodcastAction
    data class SaveQueue(val id: String, val name: String, val episodeId: String? = null) : PodcastAction
    data class DeleteQueue(val id: String) : PodcastAction
    data class QueueMembership(val queueId: String, val episodeId: String, val included: Boolean) : PodcastAction
    data class MoveQueueEpisode(val queueId: String, val episodeId: String, val direction: Int) : PodcastAction
    data class SaveQueueRule(val rule: QueueRule, val applyExisting: Boolean) : PodcastAction
    data class DeleteQueueRule(val queueId: String, val feedUrl: String) : PodcastAction
    data class AutoDownloadFeed(val feedUrl: String, val enabled: Boolean) : PodcastAction
    data class PlayQueue(val queueId: String, val episodeId: String? = null) : PodcastAction
    data class Play(val episode: Episode) : PodcastAction
    data class Seek(val positionMs: Long) : PodcastAction
    data class Skip(val milliseconds: Long) : PodcastAction
    data class Speed(val speed: Float, val feedUrl: String? = null) : PodcastAction
    data class UseGlobalSpeed(val feedUrl: String) : PodcastAction
    data class SortPodcasts(val sort: PodcastSort) : PodcastAction
    data class Favorite(val episodeId: String, val favorite: Boolean) : PodcastAction
    data class LoadPodcast(val feedUrl: String, val more: Boolean = false) : PodcastAction
    data class LoadDescription(val episodeId: String) : PodcastAction
    data class SetPodcastOptions(val feedUrl: String, val options: PodcastOptions) : PodcastAction
    data class LoadChapters(val episodeId: String, val retry: Boolean = false) : PodcastAction
    data class PlayChapter(val episode: Episode, val positionMs: Long) : PodcastAction
    data object LoadMoreHistory : PodcastAction
    data object LoadMoreEpisodes : PodcastAction
    data class Download(val episode: Episode) : PodcastAction
    data class RemoveDownload(val episode: Episode) : PodcastAction
    data class MarkPlayed(val episode: Episode, val completed: Boolean) : PodcastAction
    data object Refresh : PodcastAction
    data object TogglePlayback : PodcastAction
    data object ClearMessage : PodcastAction
}

