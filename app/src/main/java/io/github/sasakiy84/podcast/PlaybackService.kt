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
    private var ticker: kotlinx.coroutines.Job? = null
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val repository: PodcastRepository get() = (application as PodcastApplication).repository

    override fun onCreate(): Unit {
        super.onCreate()
        val player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
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
                        repository.updateProgress(id, old.copy(positionMs = oldPosition.positionMs, completed = old.completed || reason == Player.DISCONTINUITY_REASON_AUTO_TRANSITION))
                    }
                }
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
            repository.library.collect { library ->
                val active = repository.preferences.getString("active_queue", null) ?: return@collect
                val queue = library.queues.firstOrNull { it.id == active }
                val current = player.currentMediaItem?.mediaId
                val desired = queuePlaybackIds(queue, current, library)
                if (queue == null || desired.isEmpty()) repository.preferences.edit { remove("active_queue") }
                val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
                if (current != null && current in existing && current in desired) {
                    // Keep the current audio buffer while removing finished items or changing order.
                    for (index in player.mediaItemCount - 1 downTo 0) if (player.getMediaItemAt(index).mediaId !in desired) player.removeMediaItem(index)
                    desired.forEachIndexed { target, id ->
                        val from = (0 until player.mediaItemCount).firstOrNull { player.getMediaItemAt(it).mediaId == id }
                        if (from == null) library.episodes.firstOrNull { it.id == id }?.let { player.addMediaItem(target, it.mediaItem(repository)) }
                        else if (from != target) player.moveMediaItem(from, target)
                    }
                } else if (existing != desired) {
                    val position = player.currentPosition.coerceAtLeast(0)
                    val playing = player.playWhenReady
                    val index = desired.indexOf(current).takeIf { it >= 0 } ?: 0
                    val start = if (desired.getOrNull(index) == current) position else desired.getOrNull(index)?.let { library.progress[it]?.positionMs } ?: 0
                    player.setMediaItems(desired.mapNotNull { id -> library.episodes.firstOrNull { it.id == id }?.mediaItem(repository) }, index, start)
                    if (desired.isNotEmpty()) { player.prepare(); player.playWhenReady = playing }
                }
                // Downloads can finish after playback started. Future items must use the local file.
                for (index in 0 until player.mediaItemCount) {
                    if (index == player.currentMediaItemIndex && player.isPlaying) continue
                    val old = player.getMediaItemAt(index)
                    val updated = library.episodes.firstOrNull { it.id == old.mediaId }?.mediaItem(repository) ?: continue
                    if (old.localConfiguration?.uri != updated.localConfiguration?.uri) {
                        val isCurrent = index == player.currentMediaItemIndex
                        val position = player.currentPosition.coerceAtLeast(0)
                        player.replaceMediaItem(index, updated)
                        if (isCurrent) { player.seekTo(index, position); player.prepare() }
                    }
                }
            }
        }
        ticker = scope.launch {
            while (true) {
                delay(5000)
                if (player.playerError != null && repository.preferences.contains("active_queue")) repository.load()
                if (player.playbackState != Player.STATE_ENDED) persist(player)
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
        speedController?.close()
        speedController = null
        ticker?.cancel()
        queueSync?.cancel()
        val player = session?.player
        val id = player?.currentMediaItem?.mediaId
        val progress = player?.let { ListeningProgress(it.currentPosition.coerceAtLeast(0), it.duration.coerceAtLeast(0), id?.let { key -> repository.store.progress(key).completed } == true) }
        session?.run { this.player.release(); release() }
        session = null
        if (id != null && progress != null) {
            scope.launch { try { repository.updateProgress(id, progress) } finally { scope.cancel() } }
        } else scope.cancel()
        super.onDestroy()
    }
}

fun queuePlaybackIds(queue: PlaybackQueue?, currentId: String?, library: Library): List<String> {
    val valid = library.episodes.map { it.id }.toSet()
    val ids = queue?.episodeIds.orEmpty().filter { it in valid && library.progress[it]?.completed != true }
    return if (currentId != null && currentId in valid && library.progress[currentId]?.completed != true && currentId !in ids) listOf(currentId) + ids else ids
}

fun Episode.mediaItem(repository: PodcastRepository): MediaItem {
    val podcast = repository.library.value.podcasts.firstOrNull { it.feedUrl == feedUrl } ?: repository.store.podcast(feedUrl)
    return MediaItem.Builder().setMediaId(id).setUri(repository.audioUri(this))
        .setMediaMetadata(androidx.media3.common.MediaMetadata.Builder().setTitle(title).setArtist(podcast?.title)
            .setExtras(android.os.Bundle().apply { putString("feed_url", feedUrl) })
            .setArtworkUri((artwork.ifBlank { podcast?.artwork.orEmpty() }).takeIf { it.isNotBlank() }?.let(android.net.Uri::parse)).build())
        .build()
}
