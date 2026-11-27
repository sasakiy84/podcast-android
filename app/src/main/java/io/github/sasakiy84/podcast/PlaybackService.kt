package io.github.sasakiy84.podcast

import android.app.PendingIntent
import android.content.Intent
import android.content.SharedPreferences
import androidx.core.content.edit
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters.AudioOffloadPreferences
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
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
    private val podcastApplication: PodcastApplication get() = application as PodcastApplication
    private val repository: PodcastRepository get() = podcastApplication.repository
    private val mobileDataPlayback: MobileDataPlayback get() = podcastApplication.mobileDataPlayback
    private val normalizationListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (key == "normalize_volume") session?.player?.let(::updateAudioOffload)
    }

    override fun onCreate(): Unit {
        super.onCreate()
        setMediaNotificationProvider(androidx.media3.session.DefaultMediaNotificationProvider(this).apply { setSmallIcon(R.drawable.ic_podcast) })
        val renderers = NormalizingRenderersFactory(this, repository.loudnessProfiles)
        val audioDataSource = ResolvingDataSource.Factory(DefaultDataSource.Factory(this)) { spec ->
            if (mobileDataPlayback.requiresConfirmation(spec.uri)) throw MobileDataPlaybackBlocked(spec.uri)
            spec
        }
        val mediaSource = DefaultMediaSourceFactory(this).setDataSourceFactory(audioDataSource)
            .setLoadErrorHandlingPolicy(object : DefaultLoadErrorHandlingPolicy() {
                override fun getRetryDelayMsFor(info: LoadErrorHandlingPolicy.LoadErrorInfo): Long =
                    if (info.exception is MobileDataPlaybackBlocked) C.TIME_UNSET
                    else super.getRetryDelayMsFor(info)
            })
        val player = ExoPlayer.Builder(this, renderers).setMediaSourceFactory(mediaSource)
            .setSeekBackIncrementMs(15_000).setSeekForwardIncrementMs(30_000).build().apply {
            updateAudioOffload(this)
            setAudioAttributes(AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_SPEECH).build(), true)
            setHandleAudioBecomingNoisy(true)
            setWakeMode(C.WAKE_MODE_LOCAL)
        }
        skipController = PlaybackSkipController(player, repository.settings)
        speedController = PlaybackSpeedController(player, repository.settings)
        val activity = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP), PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        session = MediaSession.Builder(this, player).setSessionActivity(activity).build()
        repository.settings.listen(normalizationListener)
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean): Unit {
                persist(player)
                if (isPlaying && ticker?.isActive != true) startPlaybackTicker(player)
            }
            override fun onPlaybackStateChanged(playbackState: Int): Unit { persist(player) }
            override fun onPlayerError(error: PlaybackException): Unit {
                val blocked = generateSequence(error.cause) { it.cause }
                    .filterIsInstance<MobileDataPlaybackBlocked>().firstOrNull()
                if (blocked != null) {
                    val item = player.currentMediaItem
                    if (item?.localConfiguration?.uri != blocked.uri || !player.playWhenReady) return
                    // A queue item may have been prepared before the device switched networks.
                    if (mobileDataPlayback.requiresConfirmation(blocked.uri)) {
                        player.pause()
                        mobileDataPlayback.ask(item.mediaId, item.mediaMetadata.title?.toString().orEmpty(), blocked.uri)
                    } else player.prepare()
                    return
                }
                player.pause()
                podcastApplication.playbackErrors.tryEmit(Unit)
                if (repository.preferences.contains("active_queue")) scope.launch { repository.load() }
            }
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int): Unit {
                mobileDataPlayback.onEpisodeChanged(mediaItem?.mediaId, mediaItem?.localConfiguration?.uri)
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
                val queueIndex = queued.indexOfFirst { it.id == id }
                val restored = if (queueIndex >= 0) queued else listOf(episode) + queued
                val progress = repository.store.progress(id)
                player.setMediaItems(
                    restored.map { it.mediaItem(repository) },
                    queueIndex.coerceAtLeast(0),
                    if (progress.completed) 0 else progress.positionMs,
                )
            }
        }
        queueSync = scope.launch {
            repository.load()
            repository.library.collect { library -> synchronizePlaybackQueue(player, repository, library) }
        }
    }

    private fun updateAudioOffload(player: Player): Unit {
        val preferences =
            if (repository.settings.normalizeVolume) AudioOffloadPreferences.DEFAULT
            else AudioOffloadPreferences.Builder()
                .setAudioOffloadMode(AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED)
                .setIsSpeedChangeSupportRequired(true)
                .setIsGaplessSupportRequired(true)
                .build()
        player.trackSelectionParameters =
            player.trackSelectionParameters.buildUpon().setAudioOffloadPreferences(preferences).build()
    }

    private fun startPlaybackTicker(player: Player): Unit {
        ticker = scope.launch {
            var ticks = 0
            while (player.isPlaying) {
                delay(1000)
                if (!player.isPlaying) break
                if (skipController?.endReached() == true) {
                    finishSkippedEpisode(
                        player,
                        continueQueue = repository.preferences.contains("active_queue"),
                    ) { id, duration ->
                        repository.updateProgress(id, ListeningProgress(duration, duration, true), listened = true, finished = true)
                    }
                }
                if (++ticks % 30 == 0 && player.isPlaying) {
                    val id = player.currentMediaItem?.mediaId ?: continue
                    repository.savePlaybackPosition(id, player.currentPosition, player.duration)
                }
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

    override fun onDestroy(): Unit {
        mobileDataPlayback.reset()
        repository.settings.unlisten(normalizationListener)
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
