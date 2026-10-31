package io.github.sasakiy84.podcast

import android.app.PendingIntent
import android.content.Intent
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
class PlaybackService : MediaSessionService() {
    private var session: MediaSession? = null
    private var ticker: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: PodcastRepository get() = (application as PodcastApplication).repository

    override fun onCreate(): Unit {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
            setPlaybackSpeed(repository.preferences.getFloat("speed", 1f).coerceIn(0.5f, 3f))
        }
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean): Unit { persist(player) }
            override fun onPlaybackStateChanged(playbackState: Int): Unit { persist(player) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int): Unit {
                repository.preferences.edit { putString("current", mediaItem?.mediaId) }
            }
            override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters): Unit {
                repository.preferences.edit { putFloat("speed", playbackParameters.speed) }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int): Unit {
                val id = oldPosition.mediaItem?.mediaId ?: return
                if (id != newPosition.mediaItem?.mediaId) {
                    scope.launch { repository.updateProgress(id, repository.store.progress(id).copy(positionMs = oldPosition.positionMs)) }
                }
            }
        })
        repository.preferences.getString("current", null)?.let { id ->
            repository.store.episode(id)?.let { episode ->
                player.setMediaItem(episode.mediaItem(repository), repository.store.progress(id).let { if (it.completed) 0 else it.positionMs })
            }
        }
        ticker = scope.launch {
            while (true) {
                delay(5000)
                persist(player)
            }
        }
    }

    private fun persist(player: Player): Unit {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val progress = ListeningProgress(player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), player.playbackState == Player.STATE_ENDED)
        scope.launch { repository.updateProgress(id, progress) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?): Unit {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy(): Unit {
        ticker?.cancel()
        val player = session?.player
        val id = player?.currentMediaItem?.mediaId
        val progress = player?.let { ListeningProgress(it.currentPosition.coerceAtLeast(0), it.duration.coerceAtLeast(0), it.playbackState == Player.STATE_ENDED) }
        session?.run { this.player.release(); release() }
        session = null
        if (id != null && progress != null) {
            scope.launch { try { repository.updateProgress(id, progress) } finally { scope.cancel() } }
        } else scope.cancel()
        super.onDestroy()
    }
}

fun Episode.mediaItem(repository: PodcastRepository): MediaItem {
    val podcast = repository.library.value.podcasts.firstOrNull { it.feedUrl == feedUrl } ?: repository.store.podcast(feedUrl)
    return MediaItem.Builder().setMediaId(id).setUri(repository.audioUri(this))
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(podcast?.title)
            .setArtworkUri((artwork.ifBlank { podcast?.artwork.orEmpty() }).takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build())
        .build()
}
