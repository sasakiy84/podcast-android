package io.github.sasakiy84.podcast

import androidx.core.content.edit
import androidx.media3.common.MediaItem
import androidx.media3.common.Player

/** User playback commands. The ViewModel owns connection lifetime and coroutine/error handling. */
internal class PlaybackCommands(
    private val repository: PodcastRepository,
    private val state: () -> PodcastUiState,
    private val launchOperation: (suspend () -> Unit) -> Unit,
) {
    fun toggle(player: Player): Unit {
        val id = player.currentMediaItem?.mediaId
        if (
            player.playbackState == Player.STATE_ENDED ||
                (id != null && state().library.progress[id]?.completed == true && !player.isPlaying)
        ) {
            val duration =
                player.duration.takeIf { it > 0 }
                    ?: id?.let { state().library.progress[it]?.durationMs }
                    ?: 0
            val ended =
                player.playbackState == Player.STATE_ENDED ||
                    (duration > 0 && player.currentPosition >= duration)
            val episode = state().library.episodes.firstOrNull { it.id == id }
            val position =
                if (ended || player.currentPosition == 0L)
                    episode?.let {
                        playbackStart(
                            it,
                            ListeningProgress(),
                            repository.settings.options(it.feedUrl),
                        )
                    } ?: 0
                else player.currentPosition.coerceAtLeast(0)
            launchOperation {
                id?.let {
                    repository.updateProgress(
                        it,
                        ListeningProgress(position, player.duration.coerceAtLeast(0)),
                        resetCompletion = true,
                    )
                }
                seekPlayback(player, position)
                player.play()
            }
        } else if (player.playWhenReady) player.pause()
        else {
            if (player.playbackState == Player.STATE_IDLE) player.prepare()
            player.play()
        }
    }

    fun play(player: Player, episode: Episode): Unit {
        if (player.currentMediaItem?.mediaId == episode.id) {
            toggle(player)
            return
        }
        launchOperation {
            val progress = state().library.progress[episode.id] ?: ListeningProgress()
            if (progress.completed)
                repository.updateProgress(episode.id, ListeningProgress(), resetCompletion = true)
            startStandalone(
                player,
                episode,
                playbackStart(episode, progress, repository.settings.options(episode.feedUrl)),
            )
        }
    }

    fun playDownloaded(player: Player, episode: Episode, chapterPositionMs: Long? = null): Unit {
        launchOperation {
            val progress = repository.library.value.progress[episode.id] ?: ListeningProgress()
            if (progress.completed) {
                repository.updateProgress(
                    episode.id,
                    chapterPositionMs?.let { ListeningProgress(it, episode.durationMs) } ?: ListeningProgress(),
                    resetCompletion = true,
                )
            }
            val library = repository.library.value
            val activeQueueId = repository.preferences.getString("active_queue", null)
            val queue = downloadedEpisodeQueue(episode, library, activeQueueId)
            val sameEpisode = player.currentMediaItem?.mediaId == episode.id
            val resumable =
                sameEpisode && !progress.completed && player.playbackState != Player.STATE_ENDED
            if (chapterPositionMs == null && resumable &&
                (player.playWhenReady || activeQueueId == queue.id)
            ) {
                toggle(player)
                return@launchOperation
            }
            val position =
                chapterPositionMs ?: player.currentPosition.takeIf { resumable && it > 0 }
                    ?: playbackStart(episode, progress, repository.settings.options(episode.feedUrl))
            val episodesById = library.episodes.associateBy { it.id }
            val episodes = queuePlaybackIds(queue, episode.id, library).mapNotNull(episodesById::get)
            val index = episodes.indexOfFirst { it.id == episode.id }
            if (index >= 0)
                startQueue(player, queue.id, episodes, index, position, applyStartSkip = chapterPositionMs == null)
        }
    }

    private fun downloadedEpisodeQueue(episode: Episode, library: Library, activeQueueId: String?): PlaybackQueue {
        val memberships = library.queues.filter { episode.id in it.episodeIds }
        memberships.firstOrNull { it.id == activeQueueId }?.let { return it }
        memberships.firstOrNull()?.let { return it }

        val defaultQueue = library.queues.firstOrNull { it.id == repository.settings.defaultQueue }
            ?: library.queues.firstOrNull { it.id == "default" }
            ?: PlaybackQueue("default", "あとで聴く")
        val feedQueueIds = library.queueRules
            .filter { it.enabled && it.feedUrl == episode.feedUrl }
            .map { it.queueId }
            .toSet()
        return library.queues.firstOrNull { it.id != defaultQueue.id && it.id in feedQueueIds }
            ?: defaultQueue
    }

    fun previous(player: Player): Unit {
        if (!player.hasPreviousMediaItem()) return
        val target = player.getMediaItemAt(player.previousMediaItemIndex).mediaId
        launchOperation {
            repository.updateProgress(target, ListeningProgress(), resetCompletion = true)
            player.seekToPreviousMediaItem()
        }
    }

    fun next(player: Player): Unit {
        if (player.hasNextMediaItem()) player.seekToNextMediaItem()
    }

    fun playChapter(player: Player, episode: Episode, positionMs: Long, onSeek: () -> Unit): Unit {
        launchOperation {
            if (state().library.progress[episode.id]?.completed == true)
                repository.updateProgress(
                    episode.id,
                    ListeningProgress(positionMs, episode.durationMs),
                    resetCompletion = true,
                )
            if (player.currentMediaItem?.mediaId == episode.id) {
                seekPlayback(player, positionMs)
                onSeek()
            } else startStandalone(player, episode, positionMs, applyStartSkip = false)
        }
    }

    private suspend fun startStandalone(
        player: Player,
        episode: Episode,
        positionMs: Long,
        applyStartSkip: Boolean = true,
    ): Unit {
        repository.preferences.edit { remove("active_queue") }
        player.setMediaItem(episode.mediaItem(repository, applyStartSkip), positionMs)
        player.prepare()
        player.play()
        repository.preferences.edit { putString("current", episode.id) }
        repository.load()
    }

    suspend fun removeDownload(player: Player?, episode: Episode): Unit {
        updateSourceAfterRemovingDownloads(player, episode) {
            repository.removeDownload(episode)
            setOf(episode.id)
        }
    }

    suspend fun clearQueueAndDownloads(player: Player?, queueId: String): Unit {
        if (repository.preferences.getString("active_queue", null) == queueId)
            stopPlaybackAndSavePosition(player)
        val episode = state().library.episodes.firstOrNull { it.id == player?.currentMediaItem?.mediaId }
        updateSourceAfterRemovingDownloads(player, episode) { repository.clearQueueAndDownloads(queueId) }
    }

    suspend fun clearFeedAndDownloads(player: Player?, feedUrl: String): Unit {
        fun belongsToFeed(item: MediaItem?): Boolean =
            item?.mediaMetadata?.extras?.getString("feed_url") == feedUrl

        if (belongsToFeed(player?.currentMediaItem)) stopPlaybackAndSavePosition(player)
        repository.clearFeedAndDownloads(feedUrl)
        if (player == null) return
        if (belongsToFeed(player.currentMediaItem)) {
            stopPlaybackAndSavePosition(player)
        } else {
            for (index in player.mediaItemCount - 1 downTo 0) {
                if (belongsToFeed(player.getMediaItemAt(index))) player.removeMediaItem(index)
            }
        }
    }

    private suspend fun stopPlaybackAndSavePosition(player: Player?): Unit {
        val id = player?.currentMediaItem?.mediaId
        val progress = player?.let {
            ListeningProgress(it.currentPosition.coerceAtLeast(0), it.duration.coerceAtLeast(0))
        }
        repository.preferences.edit { remove("active_queue"); remove("current") }
        player?.pause()
        player?.stop()
        player?.clearMediaItems()
        if (id != null && progress != null) repository.updateProgress(id, progress)
    }

    private suspend fun updateSourceAfterRemovingDownloads(
        player: Player?,
        episode: Episode?,
        remove: suspend () -> Set<String>,
    ): Unit {
        val previous = player?.currentMediaItem
        val replaceSource =
            episode != null && previous?.mediaId == episode.id &&
                state().library.downloads[episode.id]?.status == DownloadStatus.Complete
        val position = player?.currentPosition?.coerceAtLeast(0) ?: 0
        val playing = player?.playWhenReady == true
        val prepared =
            player?.playbackState?.let { it != Player.STATE_IDLE && it != Player.STATE_ENDED } ==
                true
        val removedIds = remove()
        if (player == null) return
        if (replaceSource && episode != null && episode.id in removedIds && player.currentMediaItem?.mediaId == episode.id) {
            val updated =
                episode.mediaItem(
                    repository,
                    applyStartSkip =
                        previous?.mediaMetadata?.extras?.getBoolean("apply_start_skip", true) !=
                            false,
                )
            // The service may already have replaced a paused item's source on the library update.
            if (
                player.currentMediaItem?.localConfiguration?.uri != updated.localConfiguration?.uri
            ) {
                val index = player.currentMediaItemIndex
                player.replaceMediaItem(index, updated)
                player.seekTo(index, position)
                if (prepared) player.prepare()
                player.playWhenReady = playing
            }
        }
    }

    fun playQueue(player: Player, queueId: String, episodeId: String?): Unit {
        val library = state().library
        val queue = library.queues.firstOrNull { it.id == queueId } ?: return
        val episodes =
            queue.episodeIds
                .mapNotNull { id -> library.episodes.firstOrNull { it.id == id } }
                .filter { library.progress[it.id]?.completed != true }
        if (episodes.isEmpty()) return
        val current = player.currentMediaItem?.mediaId
        val sameQueue = repository.preferences.getString("active_queue", null) == queueId
        if (episodeId != null && current == episodeId && (sameQueue || player.playWhenReady)) {
            toggle(player)
            return
        }
        val target = episodeId ?: current.takeIf { sameQueue }
        val index = episodes.indexOfFirst { it.id == target }.coerceAtLeast(0)
        val episode = episodes[index]
        val position =
            if (episode.id == current) player.currentPosition.coerceAtLeast(0)
            else playbackStart(
                episode,
                library.progress[episode.id] ?: ListeningProgress(),
                repository.settings.options(episode.feedUrl),
            )
        startQueue(player, queueId, episodes, index, position)
    }

    private fun startQueue(
        player: Player,
        queueId: String,
        episodes: List<Episode>,
        index: Int,
        positionMs: Long,
        applyStartSkip: Boolean = true,
    ): Unit {
        repository.preferences.edit {
            putString("active_queue", queueId)
            putString("current", episodes[index].id)
        }
        val items = episodes.mapIndexed { itemIndex, episode ->
            episode.mediaItem(repository, applyStartSkip = itemIndex != index || applyStartSkip)
        }
        player.setMediaItems(items, index, positionMs)
        player.prepare()
        player.play()
    }
}
