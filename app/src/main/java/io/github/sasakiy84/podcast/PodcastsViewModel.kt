package io.github.sasakiy84.podcast

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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
    val busyFeeds: Set<String> = emptySet(),
    val message: String? = null,
    val playback: PlaybackUi = PlaybackUi(),
)

sealed interface PodcastAction {
    data class Search(val query: String) : PodcastAction
    data class Resolve(val url: String) : PodcastAction
    data class Subscribe(val candidate: PodcastCandidate) : PodcastAction
    data class Unsubscribe(val podcast: Podcast) : PodcastAction
    data class Play(val episode: Episode) : PodcastAction
    data class Seek(val positionMs: Long) : PodcastAction
    data class Skip(val milliseconds: Long) : PodcastAction
    data class Speed(val speed: Float) : PodcastAction
    data class Download(val episode: Episode) : PodcastAction
    data class RemoveDownload(val episode: Episode) : PodcastAction
    data class MarkPlayed(val episode: Episode, val completed: Boolean) : PodcastAction
    data object Refresh : PodcastAction
    data object TogglePlayback : PodcastAction
    data object ClearMessage : PodcastAction
}

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PodcastsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as PodcastApplication).repository
    private val mutableState = MutableStateFlow(PodcastUiState())
    val state: StateFlow<PodcastUiState> = mutableState
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private var searchGeneration: Long = 0
    private val connection = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()

    init {
        viewModelScope.launch { repository.library.collect { library -> mutableState.update { it.copy(library = library) } } }
        viewModelScope.launch {
            runOperation { repository.load() }
            if (repository.library.value.podcasts.isNotEmpty()) refresh()
            while (true) {
                delay(2000)
                if (repository.library.value.downloads.values.any { it.status == DownloadStatus.Running || it.status == DownloadStatus.Waiting }) {
                    runOperation { repository.load() }
                }
            }
        }
        connection.addListener({
            try {
                controller = connection.get()
                controller?.addListener(object : Player.Listener {
                    override fun onEvents(player: Player, events: Player.Events): Unit { updatePlayback() }
                    override fun onPlayerError(error: PlaybackException): Unit {
                        controller?.pause()
                        mutableState.update { it.copy(message = "音声を再生できませんでした。接続状態や配信元を確認し、再試行してください。") }
                    }
                })
                updatePlayback()
            } catch (_: java.util.concurrent.ExecutionException) {
                mutableState.update { it.copy(message = "プレーヤーに接続できません。アプリを開き直してください。") }
            } catch (_: CancellationException) { /* The ViewModel has already been cleared. */ }
        }, ContextCompat.getMainExecutor(application))
        viewModelScope.launch { while (true) { delay(1000); updatePlayback() } }
    }

    fun dispatch(action: PodcastAction): Unit {
        when (action) {
            is PodcastAction.Search -> search(action.query, false)
            is PodcastAction.Resolve -> search(action.url, true)
            is PodcastAction.Subscribe -> {
                val feed = action.candidate.podcast.feedUrl
                if (feed in state.value.busyFeeds) return
                mutableState.update { it.copy(busyFeeds = it.busyFeeds + feed) }
                viewModelScope.launch {
                    try { runOperation { repository.subscribe(action.candidate); mutableState.update { it.copy(message = "ポッドキャストを登録しました。") } } }
                    finally { mutableState.update { it.copy(busyFeeds = it.busyFeeds - feed) } }
                }
            }
            is PodcastAction.Unsubscribe -> viewModelScope.launch {
                runOperation {
                    if (state.value.library.episodes.any { it.feedUrl == action.podcast.feedUrl && it.id == controller?.currentMediaItem?.mediaId }) controller?.clearMediaItems()
                    repository.unsubscribe(action.podcast)
                }
            }.let { }
            is PodcastAction.Play -> play(action.episode)
            is PodcastAction.Seek -> controller?.seekTo(action.positionMs.coerceAtLeast(0)) ?: Unit
            is PodcastAction.Skip -> controller?.let { it.seekTo((it.currentPosition + action.milliseconds).coerceIn(0, it.duration.takeIf { d -> d > 0 } ?: Long.MAX_VALUE)) } ?: Unit
            is PodcastAction.Speed -> controller?.setPlaybackSpeed(action.speed.coerceIn(0.5f, 3f)) ?: Unit
            is PodcastAction.Download -> viewModelScope.launch { runOperation { repository.download(action.episode) } }.let { }
            is PodcastAction.RemoveDownload -> viewModelScope.launch { runOperation {
                val player = controller
                val replaceSource = player?.currentMediaItem?.mediaId == action.episode.id && state.value.library.downloads[action.episode.id]?.status == DownloadStatus.Complete
                val position = player?.currentPosition ?: 0
                val playing = player?.playWhenReady == true
                repository.removeDownload(action.episode)
                if (replaceSource) {
                    player.setMediaItem(action.episode.mediaItem(repository), position)
                    player.prepare()
                    player.playWhenReady = playing
                }
            } }.let { }
            is PodcastAction.MarkPlayed -> viewModelScope.launch {
                val old = state.value.library.progress[action.episode.id] ?: ListeningProgress()
                runOperation { repository.updateProgress(action.episode.id, old.copy(completed = action.completed, positionMs = if (action.completed) old.durationMs else 0)) }
            }.let { }
            PodcastAction.Refresh -> if (!state.value.refreshing) viewModelScope.launch { refresh() }.let { }
            PodcastAction.TogglePlayback -> controller?.let { player ->
                if (player.playbackState == Player.STATE_ENDED) {
                    player.seekTo(0)
                    player.play()
                } else if (player.playWhenReady) player.pause() else {
                    if (player.playbackState == Player.STATE_IDLE) player.prepare()
                    player.play()
                }
            } ?: Unit
            PodcastAction.ClearMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    private fun search(input: String, url: Boolean): Unit {
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (input.isBlank()) return
        searchJob = viewModelScope.launch {
            mutableState.update { it.copy(searching = true, searched = true, results = emptyList(), searchError = null) }
            try {
                val failure = runOperation {
                    val results = if (url) repository.resolve(input) else repository.search(input)
                    mutableState.update { it.copy(results = results) }
                }
                if (failure != null) mutableState.update { it.copy(searchError = failure) }
            } finally { if (generation == searchGeneration) mutableState.update { it.copy(searching = false) } }
        }
    }

    private suspend fun refresh(): Unit {
        mutableState.update { it.copy(refreshing = true) }
        try { runOperation {
            val failed = repository.refresh()
            if (failed.isNotEmpty()) mutableState.update { it.copy(message = "更新できなかった番組: ${failed.joinToString("、")}") }
        } } finally { mutableState.update { it.copy(refreshing = false) } }
    }

    private fun play(episode: Episode): Unit {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == episode.id) {
            dispatch(PodcastAction.TogglePlayback)
            return
        }
        val progress = state.value.library.progress[episode.id] ?: ListeningProgress()
        player.setMediaItem(episode.mediaItem(repository), if (progress.completed) 0 else progress.positionMs)
        player.prepare()
        player.play()
    }

    private fun updatePlayback(): Unit {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId
        mutableState.update { it.copy(playback = PlaybackUi(id, player.playWhenReady && player.playbackState != Player.STATE_ENDED, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), player.playbackParameters.speed, true)) }
    }

    private suspend fun runOperation(block: suspend () -> Unit): String? {
        val failure = try { block(); null }
        catch (error: CancellationException) { throw error }
        catch (error: java.io.IOException) { error.message ?: "通信できませんでした。接続状態を確認してください。" }
        catch (_: org.json.JSONException) { "配信元の情報を読み取れませんでした。時間を置いて再試行してください。" }
        catch (_: android.database.SQLException) { "端末への保存に失敗しました。空き容量を確認してください。" }
        catch (_: IllegalArgumentException) { "URL またはダウンロード先を確認してください。" }
        catch (_: SecurityException) { "ダウンロードを開始できません。端末のストレージ設定を確認してください。" }
        if (failure != null) mutableState.update { it.copy(message = failure) }
        return failure
    }

    override fun onCleared(): Unit { MediaController.releaseFuture(connection); controller = null; super.onCleared() }
}
