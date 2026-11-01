package io.github.sasakiy84.podcast

import android.app.Application
import android.content.ComponentName
import androidx.core.content.ContextCompat
import androidx.core.content.edit
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

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PodcastsViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as PodcastApplication).repository
    private val mutableState = MutableStateFlow(PodcastUiState())
    val state: StateFlow<PodcastUiState> = mutableState
    private var controller: MediaController? = null
    private var searchJob: Job? = null
    private val podcastLoads = mutableSetOf<String>()
    private var searchGeneration: Long = 0
    private val preferenceListener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key in setOf("wifi_only", "refresh_hours", "default_queue") || key?.startsWith("option:") == true || key == "speed" || key == "podcast_sort" || key?.startsWith("speed_for:") == true) updatePreferences()
    }
    private val connection = MediaController.Builder(application, SessionToken(application, ComponentName(application, PlaybackService::class.java))).buildAsync()

    init {
        repository.settings.listen(preferenceListener)
        viewModelScope.launch { repository.library.collect { library -> mutableState.update { it.copy(library = library, defaultQueueId = repository.settings.defaultQueue, wifiOnly = repository.settings.wifiOnly, refreshHours = repository.settings.refreshHours, podcastOptions = library.podcasts.associate { p -> p.feedUrl to repository.settings.options(p.feedUrl) }, podcastSort = repository.settings.sort, globalSpeed = repository.settings.globalSpeed, podcastSpeeds = library.podcasts.mapNotNull { p -> repository.settings.podcastSpeed(p.feedUrl)?.let { speed -> p.feedUrl to speed } }.toMap()) } } }
        viewModelScope.launch {
            runOperation { repository.load(); repository.configureBackgroundSync() }
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
            is PodcastAction.QuickQueue -> viewModelScope.launch { runOperation { repository.quickQueue(action.episodeId); val name = repository.library.value.queues.firstOrNull { it.id == repository.settings.defaultQueue }?.name.orEmpty(); mutableState.update { it.copy(message = "「$name」に追加しました。") } } }.let { }
            is PodcastAction.DefaultQueue -> { repository.settings.setDefaultQueue(action.id); updatePreferences() }
            is PodcastAction.WifiOnly -> viewModelScope.launch { runOperation { repository.setWifiOnly(action.enabled); updatePreferences() } }.let { }
            is PodcastAction.RefreshInterval -> viewModelScope.launch { runOperation { repository.settings.setRefreshHours(action.hours); repository.configureBackgroundSync(); updatePreferences() } }.let { }
            PodcastAction.PreviousEpisode -> controller?.let { player -> if (player.hasPreviousMediaItem()) { val target = player.getMediaItemAt(player.previousMediaItemIndex).mediaId; viewModelScope.launch { runOperation { repository.updateProgress(target, ListeningProgress(), resetCompletion = true); player.seekToPreviousMediaItem() } } }; Unit } ?: Unit
            PodcastAction.NextEpisode -> controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } ?: Unit
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
            is PodcastAction.SaveQueue -> viewModelScope.launch { runOperation { repository.saveQueue(action.id, action.name, action.episodeId) } }.let { }
            is PodcastAction.DeleteQueue -> viewModelScope.launch { runOperation { repository.deleteQueue(action.id) } }.let { }
            is PodcastAction.QueueMembership -> viewModelScope.launch { runOperation { repository.setQueueMembership(action.queueId, action.episodeId, action.included) } }.let { }
            is PodcastAction.MoveQueueEpisode -> viewModelScope.launch { runOperation { repository.moveInQueue(action.queueId, action.episodeId, action.direction) } }.let { }
            is PodcastAction.SaveQueueRule -> viewModelScope.launch { runOperation { repository.saveRule(action.rule, action.applyExisting) } }.let { }
            is PodcastAction.DeleteQueueRule -> viewModelScope.launch { runOperation { repository.deleteRule(action.queueId, action.feedUrl) } }.let { }
            is PodcastAction.AutoDownloadFeed -> viewModelScope.launch { runOperation { repository.setAutoDownload(action.feedUrl, action.enabled) } }.let { }
            is PodcastAction.PlayQueue -> playQueue(action.queueId, action.episodeId)
            is PodcastAction.Play -> play(action.episode)
            is PodcastAction.Seek -> controller?.let { seekPlayback(it, action.positionMs); updatePlayback() } ?: Unit
            is PodcastAction.Skip -> controller?.let { seekPlayback(it, it.currentPosition + action.milliseconds); updatePlayback() } ?: Unit
            is PodcastAction.Speed -> { repository.settings.setSpeed(action.speed, action.feedUrl); updatePreferences() }
            is PodcastAction.UseGlobalSpeed -> { repository.settings.useGlobal(action.feedUrl); updatePreferences() }
            is PodcastAction.SortPodcasts -> { repository.settings.setSort(action.sort); updatePreferences() }
            is PodcastAction.Favorite -> viewModelScope.launch { runOperation { repository.setFavorite(action.episodeId, action.favorite) } }.let { }
            is PodcastAction.LoadPodcast -> loadPodcast(action.feedUrl, action.more)
            is PodcastAction.LoadDescription -> if (action.episodeId !in state.value.descriptions) viewModelScope.launch {
                runOperation { val description = repository.episodeDescription(action.episodeId); mutableState.update { it.copy(descriptions = (it.descriptions - action.episodeId + (action.episodeId to description)).entries.toList().takeLast(24).associate { entry -> entry.toPair() }) } }
            }.let { }
            is PodcastAction.SetPodcastOptions -> viewModelScope.launch { runOperation { repository.setPodcastOptions(action.feedUrl, action.options); updatePreferences() } }.let { }
            PodcastAction.LoadMoreHistory -> viewModelScope.launch { runOperation { repository.loadMoreHistory() } }.let { }
            is PodcastAction.LoadChapters -> loadChapters(action.episodeId, action.retry)
            is PodcastAction.PlayChapter -> {
                val player = controller ?: return
                viewModelScope.launch { runOperation {
                    if (state.value.library.progress[action.episode.id]?.completed == true) repository.updateProgress(action.episode.id, ListeningProgress(action.positionMs, action.episode.durationMs), resetCompletion = true)
                    if (player.currentMediaItem?.mediaId == action.episode.id) { seekPlayback(player, action.positionMs); updatePlayback() }
                    else {
                        repository.preferences.edit { remove("active_queue") }
                        player.setMediaItem(action.episode.mediaItem(repository, applyStartSkip = false), action.positionMs); player.prepare(); player.play()
                    }
                } }
            }
            PodcastAction.LoadMoreEpisodes -> viewModelScope.launch { runOperation { repository.loadMoreEpisodes() } }.let { }
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
                runOperation { repository.updateProgress(action.episode.id, old.copy(completed = action.completed, positionMs = if (action.completed) old.durationMs else 0), resetCompletion = true) }
            }.let { }
            PodcastAction.Refresh -> if (!state.value.refreshing) viewModelScope.launch { refresh() }.let { }
            PodcastAction.TogglePlayback -> togglePlayback()
            PodcastAction.ClearMessage -> mutableState.update { it.copy(message = null) }
        }
    }

    fun importOpml(uri: android.net.Uri): Unit {
        if (state.value.opmlBusy) return
        mutableState.update { it.copy(opmlBusy = true) }
        viewModelScope.launch {
            var added = 0
            try {
                runOperation {
                    val result = repository.importOpml(uri)
                    added = result.added
                    val skipped = result.skipped
                    val message = when {
                        added == 0 && skipped == 0 -> "OPML に番組が含まれていません。"
                        skipped > 0 -> "${added}番組をインポートしました（${skipped}件スキップ）。"
                        else -> "${added}番組をインポートしました。"
                    }
                    mutableState.update { it.copy(message = message) }
                }
            } finally { mutableState.update { it.copy(opmlBusy = false) } }
            if (added > 0 && !state.value.refreshing) refresh()
        }
    }

    fun exportOpml(uri: android.net.Uri): Unit {
        if (state.value.opmlBusy) return
        mutableState.update { it.copy(opmlBusy = true) }
        viewModelScope.launch {
            try {
                runOperation {
                    repository.exportOpml(uri)
                    mutableState.update { it.copy(message = "OPML をエクスポートしました。") }
                }
            } finally { mutableState.update { it.copy(opmlBusy = false) } }
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
        mutableState.update { it.copy(refreshing = true, refreshCompleted = 0, refreshTotal = 0) }
        try { runOperation {
            val failed = repository.refresh { done, total -> mutableState.update { it.copy(refreshCompleted = maxOf(it.refreshCompleted, done), refreshTotal = total) } }
            if (failed.isNotEmpty()) mutableState.update { it.copy(message = "${failed.size}番組を更新できませんでした。") }
        } } finally { mutableState.update { it.copy(refreshing = false) } }
    }

    private fun updatePreferences(): Unit {
        mutableState.update { current -> current.copy(defaultQueueId = repository.settings.defaultQueue, wifiOnly = repository.settings.wifiOnly, refreshHours = repository.settings.refreshHours, podcastOptions = current.library.podcasts.associate { it.feedUrl to repository.settings.options(it.feedUrl) }, podcastSort = repository.settings.sort, globalSpeed = repository.settings.globalSpeed,
            podcastSpeeds = current.library.podcasts.mapNotNull { p -> repository.settings.podcastSpeed(p.feedUrl)?.let { p.feedUrl to it } }.toMap()) }
    }

    private fun loadChapters(id: String, retry: Boolean): Unit {
        if (id in state.value.chaptersLoading || (!retry && (id in state.value.chapters || id in state.value.chapterErrors))) return
        mutableState.update { it.copy(chaptersLoading = it.chaptersLoading + id, chapterErrors = it.chapterErrors - id) }
        viewModelScope.launch {
            try {
                val failure = runOperation { val chapters = repository.loadChapters(id); mutableState.update { it.copy(chapters = (it.chapters + (id to chapters)).entries.toList().takeLast(24).associate { e -> e.toPair() }) } }
                if (failure != null) mutableState.update { it.copy(chapterErrors = it.chapterErrors + (id to failure)) }
            } finally { mutableState.update { it.copy(chaptersLoading = it.chaptersLoading - id) } }
        }
    }

    private fun loadPodcast(feed: String, more: Boolean): Unit {
        if (!podcastLoads.add(feed)) return
        viewModelScope.launch {
            try { runOperation {
                val old = state.value.podcastPages[feed]
                val limit = if (more) (old?.episodes?.size ?: 0) + 150 else maxOf(old?.episodes?.size ?: 0, 150)
                val page = repository.podcastEpisodes(feed, limit)
                mutableState.update { it.copy(podcastPages = it.podcastPages + (feed to page)) }
            } } finally { podcastLoads.remove(feed) }
        }
    }

    private fun togglePlayback(): Unit {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId
        if (player.playbackState == Player.STATE_ENDED || (id != null && state.value.library.progress[id]?.completed == true && !player.isPlaying)) {
            val duration = player.duration.takeIf { it > 0 } ?: id?.let { state.value.library.progress[it]?.durationMs } ?: 0
            val ended = player.playbackState == Player.STATE_ENDED || (duration > 0 && player.currentPosition >= duration)
            val episode = state.value.library.episodes.firstOrNull { it.id == id }
            val position = if (ended || player.currentPosition == 0L) episode?.let { playbackStart(it, ListeningProgress(), repository.settings.options(it.feedUrl)) } ?: 0 else player.currentPosition.coerceAtLeast(0)
            viewModelScope.launch { runOperation {
                id?.let { repository.updateProgress(it, ListeningProgress(position, player.duration.coerceAtLeast(0)), resetCompletion = true) }
                seekPlayback(player, position); player.play()
            } }
        } else if (player.playWhenReady) player.pause() else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    private fun play(episode: Episode): Unit {
        val player = controller ?: return
        if (player.currentMediaItem?.mediaId == episode.id) { togglePlayback(); return }
        viewModelScope.launch { runOperation {
            val progress = state.value.library.progress[episode.id] ?: ListeningProgress()
            if (progress.completed) repository.updateProgress(episode.id, ListeningProgress(), resetCompletion = true)
            repository.preferences.edit { remove("active_queue") }
            player.setMediaItem(episode.mediaItem(repository), playbackStart(episode, progress, repository.settings.options(episode.feedUrl)))
            player.prepare(); player.play()
        } }
    }

    private fun playQueue(queueId: String, episodeId: String?): Unit {
        val player = controller ?: return
        val library = state.value.library
        val queue = library.queues.firstOrNull { it.id == queueId } ?: return
        val episodes = queue.episodeIds.mapNotNull { id -> library.episodes.firstOrNull { it.id == id } }.filter { library.progress[it.id]?.completed != true }
        if (episodes.isEmpty()) return
        val current = player.currentMediaItem?.mediaId
        val sameQueue = repository.preferences.getString("active_queue", null) == queueId
        if (episodeId != null && current == episodeId && (sameQueue || player.playWhenReady)) { togglePlayback(); return }
        val target = episodeId ?: current.takeIf { sameQueue }
        val index = episodes.indexOfFirst { it.id == target }.coerceAtLeast(0)
        repository.preferences.edit { putString("active_queue", queueId) }
        player.setMediaItems(episodes.map { it.mediaItem(repository) }, index, if (episodes[index].id == current) player.currentPosition.coerceAtLeast(0) else playbackStart(episodes[index], library.progress[episodes[index].id] ?: ListeningProgress(), repository.settings.options(episodes[index].feedUrl)))
        player.prepare(); player.play()
    }

    private fun updatePlayback(): Unit {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId
        mutableState.update { it.copy(playback = PlaybackUi(id, player.playWhenReady && player.playbackState != Player.STATE_ENDED, player.playbackState == Player.STATE_BUFFERING,
            player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), player.playbackParameters.speed, true, player.hasPreviousMediaItem(), player.hasNextMediaItem())) }
    }

    private suspend fun runOperation(block: suspend () -> Unit): String? {
        val failure = try { block(); null }
        catch (error: CancellationException) { throw error }
        catch (error: java.io.IOException) { error.message ?: "通信できませんでした。接続状態を確認してください。" }
        catch (_: org.json.JSONException) { "配信元の情報を読み取れませんでした。時間を置いて再試行してください。" }
        catch (_: android.database.SQLException) { "端末への保存に失敗しました。空き容量を確認してください。" }
        catch (error: IllegalArgumentException) { error.message ?: "入力内容を確認してください。" }
        catch (_: SecurityException) { "ダウンロードを開始できません。端末のストレージ設定を確認してください。" }
        if (failure != null) mutableState.update { it.copy(message = failure) }
        return failure
    }

    override fun onCleared(): Unit { repository.settings.unlisten(preferenceListener); MediaController.releaseFuture(connection); controller = null; super.onCleared() }
}
