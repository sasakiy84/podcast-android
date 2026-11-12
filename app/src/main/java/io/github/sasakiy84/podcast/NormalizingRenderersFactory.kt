package io.github.sasakiy84.podcast

import android.content.Context
import androidx.media3.common.Format
import androidx.media3.common.MimeTypes
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.exoplayer.audio.ForwardingAudioSink

/**
 * Software PCM keeps correction active with Bluetooth, wired outputs, and playback speed changes.
 */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NormalizingRenderersFactory(
    context: Context,
    private val profiles: LoudnessProfiles,
) : DefaultRenderersFactory(context) {
    override fun buildAudioSink(
        context: Context,
        enableFloatOutput: Boolean,
        enableAudioOutputPlaybackParameters: Boolean,
    ): AudioSink {
        val processor = NormalizingAudioProcessor({ profiles.options }, profiles::forPlayback)
        val sink =
            DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(false)
                .setAudioProcessorChain(NormalizingAudioProcessorChain(processor))
                .build()
        return object : ForwardingAudioSink(sink) {
            override fun getFormatSupport(format: Format): Int =
                if (format.sampleMimeType == MimeTypes.AUDIO_RAW) super.getFormatSupport(format)
                else AudioSink.SINK_FORMAT_UNSUPPORTED

            override fun supportsFormat(format: Format): Boolean =
                getFormatSupport(format) != AudioSink.SINK_FORMAT_UNSUPPORTED
        }
    }
}
