package io.github.sasakiy84.podcast

import androidx.media3.common.Player

/** Seeking also works for restored, unprepared media, without starting playback. */
fun seekPlayback(player: Player, positionMs: Long): Unit {
    val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
    val wasPlaying = player.playWhenReady && player.playbackState != Player.STATE_ENDED
    player.playWhenReady = wasPlaying
    player.seekTo(positionMs.coerceIn(0, duration))
    if (player.playbackState == Player.STATE_IDLE) player.prepare()
}

fun playbackStart(episode: Episode, progress: ListeningProgress, options: PodcastOptions): Long {
    val position = if (progress.completed) 0 else progress.positionMs
    return if (position > 0) position else (options.skipStartSeconds * 1000L).coerceAtMost((episode.durationMs - 1000).coerceAtLeast(0).takeIf { episode.durationMs > 0 } ?: Long.MAX_VALUE)
}

/** End skipping is a playback policy, independent of the screen or media controller. */
class PlaybackSkipController(private val player: Player, private val settings: PlaybackPreferences) : Player.Listener {
    private var pendingStart = false
    init { player.addListener(this) }
    override fun onMediaItemTransition(mediaItem: androidx.media3.common.MediaItem?, reason: Int): Unit { pendingStart = true; applyStart() }
    override fun onPlaybackStateChanged(playbackState: Int): Unit { if (playbackState == Player.STATE_READY) applyStart() }
    private fun applyStart(): Unit {
        if (!pendingStart || player.currentMediaItem == null) return
        val feed = player.currentMediaItem?.mediaMetadata?.extras?.getString("feed_url") ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        pendingStart = false
        if (player.currentMediaItem?.mediaMetadata?.extras?.getBoolean("apply_start_skip", true) == false) return
        val start = settings.options(feed).skipStartSeconds * 1000L
        val duration = player.duration.takeIf { it > 0 } ?: Long.MAX_VALUE
        if (player.currentPosition == 0L && start > 0) player.seekTo(start.coerceAtMost((duration - 1000).coerceAtLeast(0)))
    }
    fun endReached(): Boolean {
        if (!player.isPlaying) return false
        val feed = player.currentMediaItem?.mediaMetadata?.extras?.getString("feed_url") ?: return false
        val end = settings.options(feed).skipEndSeconds * 1000L
        return end > 0 && player.duration > end && player.currentPosition >= player.duration - end
    }
    fun close(): Unit { player.removeListener(this) }
}

/** Advance before publishing completion, so queue synchronization cannot advance twice. */
internal suspend fun finishSkippedEpisode(player: Player, complete: suspend (String, Long) -> Unit): Unit {
    val id = player.currentMediaItem?.mediaId ?: return
    val duration = player.duration
    if (player.hasNextMediaItem()) player.seekToNextMediaItem() else { player.pause(); player.seekTo(duration) }
    complete(id, duration)
}
