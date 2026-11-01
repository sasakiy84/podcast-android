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
    private val playbackCommands = PlaybackCommands(repository, { state.value }, ::launchOperation)
    private var searchJob: Job? = null
    private val podcastLoads = mutableSetOf<String>()
    private var searchGeneration: Long = 0
    private val preferenceListener =
        android.content.SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
            if (
                key in setOf("wifi_only", "refresh_hours", "default_queue") ||
                    key?.startsWith("option:") == true ||
                    key == "speed" ||
                    key == "podcast_sort" ||
                    key?.startsWith("speed_for:") == true
            )
                updatePreferences()
        }
    private val connection =
        MediaController.Builder(
                application,
                SessionToken(application, ComponentName(application, PlaybackService::class.java)),
            )
            .buildAsync()

    init {
        repository.settings.listen(preferenceListener)
        viewModelScope.launch {
            repository.library.collect { library ->
                mutableState.update {
                    it.copy(library = library).withPreferences(repository.settings)
                }
            }
        }
        viewModelScope.launch {
            runOperation {
                repository.load()
                repository.configureBackgroundSync()
            }
            while (true) {
                delay(2000)
                if (
                    repository.library.value.downloads.values.any {
                        it.status == DownloadStatus.Running || it.status == DownloadStatus.Waiting
                    }
                ) {
                    runOperation { repository.load() }
                }
            }
        }
        connection.addListener(
            {
                try {
                    controller = connection.get()
                    controller?.addListener(
                        object : Player.Listener {
                            override fun onEvents(player: Player, events: Player.Events): Unit {
                                updatePlayback()
                            }

                            override fun onPlayerError(error: PlaybackException): Unit {
                                controller?.pause()
                                mutableState.update {
                                    it.copy(message = "音声を再生できませんでした。接続状態や配信元を確認し、再試行してください。")
                                }
                            }
                        }
                    )
                    updatePlayback()
                } catch (_: java.util.concurrent.ExecutionException) {
                    mutableState.update { it.copy(message = "プレーヤーに接続できません。アプリを開き直してください。") }
                } catch (_: CancellationException) {
                    /* The ViewModel has already been cleared. */
                }
            },
            ContextCompat.getMainExecutor(application),
        )
        viewModelScope.launch {
            while (true) {
                delay(1000)
                updatePlayback()
            }
        }
    }

    fun dispatch(action: PodcastAction): Unit {
        when (action) {
            is PodcastAction.QuickQueue ->
                launchOperation {
                    repository.quickQueue(action.episodeId)
                    val name =
                        repository.library.value.queues
                            .firstOrNull { it.id == repository.settings.defaultQueue }
                            ?.name
                            .orEmpty()
                    mutableState.update { it.copy(message = "「$name」に追加しました。") }
                }
            is PodcastAction.DefaultQueue -> {
                repository.settings.setDefaultQueue(action.id)
                updatePreferences()
            }
            is PodcastAction.WifiOnly ->
                launchOperation {
                    repository.setWifiOnly(action.enabled)
                    updatePreferences()
                }
            is PodcastAction.RefreshInterval ->
                launchOperation {
                    repository.settings.setRefreshHours(action.hours)
                    repository.configureBackgroundSync()
                    updatePreferences()
                }
            PodcastAction.PreviousEpisode ->
                controller?.let { player ->
                    if (player.hasPreviousMediaItem()) {
                        val target = player.getMediaItemAt(player.previousMediaItemIndex).mediaId
                        viewModelScope.launch {
                            runOperation {
                                repository.updateProgress(
                                    target,
                                    ListeningProgress(),
                                    resetCompletion = true,
                                )
                                player.seekToPreviousMediaItem()
                            }
                        }
                    }
                    Unit
                } ?: Unit
            PodcastAction.NextEpisode ->
                controller?.let { if (it.hasNextMediaItem()) it.seekToNextMediaItem() } ?: Unit
            is PodcastAction.Search -> search(action.query, false)
            is PodcastAction.Resolve -> search(action.url, true)
            is PodcastAction.Subscribe -> {
                val feed = action.candidate.podcast.feedUrl
                if (feed in state.value.busyFeeds) return
                mutableState.update { it.copy(busyFeeds = it.busyFeeds + feed) }
                viewModelScope.launch {
                    try {
                        runOperation {
                            repository.subscribe(action.candidate)
                            mutableState.update { it.copy(message = "ポッドキャストを登録しました。") }
                        }
                    } finally {
                        mutableState.update { it.copy(busyFeeds = it.busyFeeds - feed) }
                    }
                }
            }
            is PodcastAction.Unsubscribe ->
                viewModelScope
                    .launch {
                        runOperation {
                            if (
                                state.value.library.episodes.any {
                                    it.feedUrl == action.podcast.feedUrl &&
                                        it.id == controller?.currentMediaItem?.mediaId
                                }
                            )
                                controller?.clearMediaItems()
                            repository.unsubscribe(action.podcast)
                        }
                    }
                    .let {}
            is PodcastAction.SaveQueue ->
                launchOperation { repository.saveQueue(action.id, action.name, action.episodeId) }
            is PodcastAction.DeleteQueue -> launchOperation { repository.deleteQueue(action.id) }
            is PodcastAction.QueueMembership ->
                launchOperation {
                    repository.setQueueMembership(action.queueId, action.episodeId, action.included)
                }
            is PodcastAction.MoveQueueEpisode ->
                launchOperation {
                    repository.moveInQueue(action.queueId, action.episodeId, action.direction)
                }
            is PodcastAction.SaveQueueRule ->
                launchOperation { repository.saveRule(action.rule, action.applyExisting) }
            is PodcastAction.DeleteQueueRule ->
                launchOperation { repository.deleteRule(action.queueId, action.feedUrl) }
            is PodcastAction.AutoDownloadFeed ->
                launchOperation { repository.setAutoDownload(action.feedUrl, action.enabled) }
            is PodcastAction.PlayQueue ->
                controller?.let { playbackCommands.playQueue(it, action.queueId, action.episodeId) }
                    ?: Unit
            is PodcastAction.Play ->
                controller?.let { playbackCommands.play(it, action.episode) } ?: Unit
            is PodcastAction.Seek ->
                controller?.let {
                    seekPlayback(it, action.positionMs)
                    updatePlayback()
                } ?: Unit
            is PodcastAction.Skip ->
                controller?.let {
                    seekPlayback(it, it.currentPosition + action.milliseconds)
                    updatePlayback()
                } ?: Unit
            is PodcastAction.Speed -> {
                repository.settings.setSpeed(action.speed, action.feedUrl)
                updatePreferences()
            }
            is PodcastAction.UseGlobalSpeed -> {
                repository.settings.useGlobal(action.feedUrl)
                updatePreferences()
            }
            is PodcastAction.SortPodcasts -> {
                repository.settings.setSort(action.sort)
                updatePreferences()
            }
            is PodcastAction.Favorite ->
                launchOperation { repository.setFavorite(action.episodeId, action.favorite) }
            is PodcastAction.LoadPodcast -> loadPodcast(action.feedUrl, action.more)
            is PodcastAction.LoadDescription ->
                if (action.episodeId !in state.value.descriptions)
                    viewModelScope
                        .launch {
                            runOperation {
                                val description = repository.episodeDescription(action.episodeId)
                                mutableState.update {
                                    it.copy(
                                        descriptions =
                                            (it.descriptions - action.episodeId +
                                                    (action.episodeId to description))
                                                .entries
                                                .toList()
                                                .takeLast(24)
                                                .associate { entry -> entry.toPair() }
                                    )
                                }
                            }
                        }
                        .let {}
            is PodcastAction.SetPodcastOptions ->
                launchOperation {
                    repository.setPodcastOptions(action.feedUrl, action.options)
                    updatePreferences()
                }
            PodcastAction.LoadMoreHistory -> launchOperation { repository.loadMoreHistory() }
            is PodcastAction.LoadChapters -> loadChapters(action.episodeId, action.retry)
            is PodcastAction.PlayChapter -> {
                val player = controller ?: return
                viewModelScope.launch {
                    runOperation {
                        if (state.value.library.progress[action.episode.id]?.completed == true)
                            repository.updateProgress(
                                action.episode.id,
                                ListeningProgress(action.positionMs, action.episode.durationMs),
                                resetCompletion = true,
                            )
                        if (player.currentMediaItem?.mediaId == action.episode.id) {
                            seekPlayback(player, action.positionMs)
                            updatePlayback()
                        } else {
                            repository.preferences.edit { remove("active_queue") }
                            player.setMediaItem(
                                action.episode.mediaItem(repository, applyStartSkip = false),
                                action.positionMs,
                            )
                            player.prepare()
                            player.play()
                        }
                    }
                }
            }
            PodcastAction.LoadMoreEpisodes -> launchOperation { repository.loadMoreEpisodes() }
            is PodcastAction.Download -> launchOperation { repository.download(action.episode) }
            is PodcastAction.RemoveDownload ->
                viewModelScope
                    .launch {
                        runOperation {
                            val player = controller
                            val replaceSource =
                                player?.currentMediaItem?.mediaId == action.episode.id &&
                                    state.value.library.downloads[action.episode.id]?.status ==
                                        DownloadStatus.Complete
                            val position = player?.currentPosition ?: 0
                            val playing = player?.playWhenReady == true
                            repository.removeDownload(action.episode)
                            if (replaceSource) {
                                player.setMediaItem(action.episode.mediaItem(repository), position)
                                player.prepare()
                                player.playWhenReady = playing
                            }
                        }
                    }
                    .let {}
            is PodcastAction.MarkPlayed ->
                viewModelScope
                    .launch {
                        val old =
                            state.value.library.progress[action.episode.id] ?: ListeningProgress()
                        runOperation {
                            repository.updateProgress(
                                action.episode.id,
                                old.copy(
                                    completed = action.completed,
                                    positionMs = if (action.completed) old.durationMs else 0,
                                ),
                                resetCompletion = true,
                            )
                        }
                    }
                    .let {}
            PodcastAction.Refresh ->
                if (!state.value.refreshing) viewModelScope.launch { refresh() }.let {}
            PodcastAction.TogglePlayback -> controller?.let(playbackCommands::toggle) ?: Unit
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
                    val message =
                        when {
                            added == 0 && skipped == 0 -> "OPML に番組が含まれていません。"
                            skipped > 0 -> "${added}番組をインポートしました（${skipped}件スキップ）。"
                            else -> "${added}番組をインポートしました。"
                        }
                    mutableState.update { it.copy(message = message) }
                }
            } finally {
                mutableState.update { it.copy(opmlBusy = false) }
            }
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
            } finally {
                mutableState.update { it.copy(opmlBusy = false) }
            }
        }
    }

    private fun search(input: String, url: Boolean): Unit {
        val generation = ++searchGeneration
        searchJob?.cancel()
        if (input.isBlank()) return
        searchJob =
            viewModelScope.launch {
                mutableState.update {
                    it.copy(
                        searching = true,
                        searched = true,
                        results = emptyList(),
                        searchError = null,
                    )
                }
                try {
                    val failure = runOperation {
                        val results =
                            if (url) repository.resolve(input) else repository.search(input)
                        mutableState.update { it.copy(results = results) }
                    }
                    if (failure != null) mutableState.update { it.copy(searchError = failure) }
                } finally {
                    if (generation == searchGeneration)
                        mutableState.update { it.copy(searching = false) }
                }
            }
    }

    private suspend fun refresh(): Unit {
        mutableState.update { it.copy(refreshing = true, refreshCompleted = 0, refreshTotal = 0) }
        try {
            runOperation {
                val failed =
                    repository.refresh { done, total ->
                        mutableState.update {
                            it.copy(
                                refreshCompleted = maxOf(it.refreshCompleted, done),
                                refreshTotal = total,
                            )
                        }
                    }
                if (failed.isNotEmpty())
                    mutableState.update { it.copy(message = "${failed.size}番組を更新できませんでした。") }
            }
        } finally {
            mutableState.update { it.copy(refreshing = false) }
        }
    }

    private fun updatePreferences(): Unit {
        mutableState.update { it.withPreferences(repository.settings) }
    }

    private fun launchOperation(block: suspend () -> Unit): Unit {
        viewModelScope.launch { runOperation(block) }
    }

    private fun loadChapters(id: String, retry: Boolean): Unit {
        if (
            id in state.value.chaptersLoading ||
                (!retry && (id in state.value.chapters || id in state.value.chapterErrors))
        )
            return
        mutableState.update {
            it.copy(
                chaptersLoading = it.chaptersLoading + id,
                chapterErrors = it.chapterErrors - id,
            )
        }
        viewModelScope.launch {
            try {
                val failure = runOperation {
                    val chapters = repository.loadChapters(id)
                    mutableState.update {
                        it.copy(
                            chapters =
                                (it.chapters + (id to chapters))
                                    .entries
                                    .toList()
                                    .takeLast(24)
                                    .associate { e -> e.toPair() }
                        )
                    }
                }
                if (failure != null)
                    mutableState.update {
                        it.copy(chapterErrors = it.chapterErrors + (id to failure))
                    }
            } finally {
                mutableState.update { it.copy(chaptersLoading = it.chaptersLoading - id) }
            }
        }
    }

    private fun loadPodcast(feed: String, more: Boolean): Unit {
        if (!podcastLoads.add(feed)) return
        viewModelScope.launch {
            try {
                runOperation {
                    val old = state.value.podcastPages[feed]
                    val limit =
                        if (more) (old?.episodes?.size ?: 0) + 150
                        else maxOf(old?.episodes?.size ?: 0, 150)
                    val page = repository.podcastEpisodes(feed, limit)
                    mutableState.update { it.copy(podcastPages = it.podcastPages + (feed to page)) }
                }
            } finally {
                podcastLoads.remove(feed)
            }
        }
    }

    private fun updatePlayback(): Unit {
        val player = controller ?: return
        val id = player.currentMediaItem?.mediaId
        mutableState.update {
            it.copy(
                playback =
                    PlaybackUi(
                        id,
                        player.playWhenReady && player.playbackState != Player.STATE_ENDED,
                        player.playbackState == Player.STATE_BUFFERING,
                        player.currentPosition.coerceAtLeast(0),
                        player.duration.coerceAtLeast(0),
                        player.playbackParameters.speed,
                        true,
                        player.hasPreviousMediaItem(),
                        player.hasNextMediaItem(),
                    )
            )
        }
    }

    private suspend fun runOperation(block: suspend () -> Unit): String? {
        val failure =
            try {
                block()
                null
            } catch (error: CancellationException) {
                throw error
            } catch (error: java.io.IOException) {
                error.message ?: "通信できませんでした。接続状態を確認してください。"
            } catch (_: org.json.JSONException) {
                "配信元の情報を読み取れませんでした。時間を置いて再試行してください。"
            } catch (_: android.database.SQLException) {
                "端末への保存に失敗しました。空き容量を確認してください。"
            } catch (error: IllegalArgumentException) {
                error.message ?: "入力内容を確認してください。"
            } catch (_: SecurityException) {
                "ダウンロードを開始できません。端末のストレージ設定を確認してください。"
            }
        if (failure != null) mutableState.update { it.copy(message = failure) }
        return failure
    }

    override fun onCleared(): Unit {
        repository.settings.unlisten(preferenceListener)
        MediaController.releaseFuture(connection)
        controller = null
        super.onCleared()
    }
}
