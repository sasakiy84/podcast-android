package io.github.sasakiy84.podcast

import androidx.core.content.edit
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
        val previous = player?.currentMediaItem
        val replaceSource =
            previous?.mediaId == episode.id &&
                state().library.downloads[episode.id]?.status == DownloadStatus.Complete
        val position = player?.currentPosition?.coerceAtLeast(0) ?: 0
        val playing = player?.playWhenReady == true
        val prepared =
            player?.playbackState?.let { it != Player.STATE_IDLE && it != Player.STATE_ENDED } ==
                true
        repository.removeDownload(episode)
        if (player == null) return
        if (replaceSource && player.currentMediaItem?.mediaId == episode.id) {
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
        repository.preferences.edit { putString("active_queue", queueId) }
        player.setMediaItems(
            episodes.map { it.mediaItem(repository) },
            index,
            if (episodes[index].id == current) player.currentPosition.coerceAtLeast(0)
            else
                playbackStart(
                    episodes[index],
                    library.progress[episodes[index].id] ?: ListeningProgress(),
                    repository.settings.options(episodes[index].feedUrl),
                ),
        )
        player.prepare()
        player.play()
    }
}
