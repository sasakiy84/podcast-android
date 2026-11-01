package io.github.sasakiy84.podcast

import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import kotlin.math.abs

/** Scoped speed must follow automatic queue transitions without changing the global default. */
class PlaybackSpeedController(private val player: Player, private val settings: PlaybackPreferences) : Player.Listener, AutoCloseable {
    private val handler = Handler(player.applicationLooper)
    private var closed = false
    private val preferenceListener = SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
        if (!closed && (key == "speed" || key?.startsWith("speed_for:") == true)) {
            if (Looper.myLooper() == player.applicationLooper) apply() else handler.post { if (!closed) apply() }
        }
    }
    init { player.addListener(this); settings.listen(preferenceListener); apply() }
    private fun feed(): String? = player.currentMediaItem?.mediaMetadata?.extras?.getString("feed_url")
    private fun apply(): Unit {
        val speed = settings.speed(feed())
        if (abs(player.playbackParameters.speed - speed) > 0.001f) player.setPlaybackSpeed(speed)
    }
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int): Unit = apply()
    override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters): Unit {
        if (closed || abs(player.playbackParameters.speed - playbackParameters.speed) > 0.001f) return
        if (abs(settings.speed(feed()) - playbackParameters.speed) > 0.001f) {
            settings.setSpeed(playbackParameters.speed, feed()?.takeIf { settings.podcastSpeed(it) != null })
        }
    }
    override fun close(): Unit { closed = true; player.removeListener(this); settings.unlisten(preferenceListener); handler.removeCallbacksAndMessages(null) }
}
