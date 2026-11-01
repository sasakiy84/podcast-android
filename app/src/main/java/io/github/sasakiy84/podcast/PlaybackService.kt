package io.github.sasakiy84.podcast

import android.app.PendingIntent
import android.content.Intent
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
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
    private var queueSync: kotlinx.coroutines.Job? = null
    private var speedController: PlaybackSpeedController? = null
    private var skipController: PlaybackSkipController? = null
    private var ticker: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: PodcastRepository get() = (application as PodcastApplication).repository

    override fun onCreate(): Unit {
        super.onCreate()
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_podcast) })
        val player = ExoPlayer.Builder(this).setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        skipController = PlaybackSkipController(player, repository.settings)
        speedController = PlaybackSpeedController(player, repository.settings)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean): Unit { persist(player) }
            override fun onPlaybackStateChanged(playbackState: Int): Unit { persist(player) }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int): Unit {
                repository.preferences.edit { putString("current", mediaItem?.mediaId) }
            }
            override fun onPositionDiscontinuity(oldPosition: Player.PositionInfo, newPosition: Player.PositionInfo, reason: Int): Unit {
                val id = oldPosition.mediaItem?.mediaId ?: return
                if (id != newPosition.mediaItem?.mediaId) {
                    scope.launch {
                        val old = repository.store.progress(id)
                        repository.updateProgress(id, old.copy(positionMs = oldPosition.positionMs, completed = old.completed || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION), listened = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION, finished = reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION)
                    }
                } else if (reason == Player.DISCONTINUITY_REASON_SEEK) persist(player)
            }
        })
        repository.preferences.getString("current", null)?.let { id ->
            repository.store.episode(id)?.let { episode ->
                val active = repository.preferences.getString("active_queue", null)?.let { queueId -> repository.store.queues().firstOrNull { it.id == queueId } }
                val queued = active?.episodeIds?.mapNotNull(repository.store::episode).orEmpty()
                if (queued.isNotEmpty()) {
                    val index = queued.indexOfFirst { it.id == id }.coerceAtLeast(0)
                    val progress = repository.store.progress(queued[index].id)
                    player.setMediaItems(queued.map { it.mediaItem(repository) }, index, if (progress.completed) 0 else progress.positionMs)
                } else player.setMediaItem(episode.mediaItem(repository), repository.store.progress(id).let { if (it.completed) 0 else it.positionMs })
            }
        }
        queueSync = scope.launch {
            repository.load()
            repository.library.collect { library -> synchronizePlaybackQueue(player, repository, library) }
        }
        ticker = scope.launch {
            var ticks = 0
            while (true) {
                delay(1000)
                ticks++
                if (skipController?.endReached() == true) {
                    finishSkippedEpisode(player) { id, duration -> repository.updateProgress(id, ListeningProgress(duration, duration, true), listened = true, finished = true) }
                }
                if (player.playerError != null && repository.preferences.contains("active_queue")) repository.load()
                if (ticks % 5 == 0 && player.isPlaying) persist(player)
            }
        }
    }

    private fun persist(player: Player): Unit {
        val id = player.currentMediaItem?.mediaId ?: return
        if (player.playbackState == Player.STATE_IDLE) return
        val progress = ListeningProgress(player.currentPosition.coerceAtLeast(0), player.duration.coerceAtLeast(0), player.playbackState == Player.STATE_ENDED)
        val listened = player.isPlaying
        val finished = player.playbackState == Player.STATE_ENDED
        scope.launch { repository.updateProgress(id, progress, listened = listened, finished = finished) }
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = session

    override fun onTaskRemoved(rootIntent: Intent?): Unit {
        if (session?.player?.playWhenReady != true) stopSelf()
    }

    override fun onDestroy(): Unit {
        skipController?.close()
        skipController = null
        speedController?.close()
        speedController = null
        ticker?.cancel()
        queueSync?.cancel()
        val player = session?.player
        val id = player?.currentMediaItem?.mediaId
        val progress = player?.let { ListeningProgress(it.currentPosition.coerceAtLeast(0), it.duration.coerceAtLeast(0), id?.let { key -> repository.store.progress(key).completed } == true) }
        val finished = player?.playbackState == Player.STATE_ENDED
        session?.run { this.player.release(); release() }
        session = null
        if (id != null && progress != null) {
            scope.launch { try { repository.updateProgress(id, progress, finished = finished) } finally { scope.cancel() } }
        } else scope.cancel()
        super.onDestroy()
    }
}
