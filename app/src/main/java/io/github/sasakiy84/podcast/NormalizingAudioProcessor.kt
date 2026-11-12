package io.github.sasakiy84.podcast

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Timeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.audio.DefaultAudioSink
import java.nio.ByteBuffer
import java.nio.ByteOrder

@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NormalizingAudioProcessor(
    private val options: () -> NormalizationOptions,
    private val initialLoudness: (MediaItem) -> Double?,
) : BaseAudioProcessor() {
    private var normalizer: LoudnessNormalizer? = null
    private var samples = FloatArray(0)
    private var partial = ByteBuffer.allocate(0)
    private var partialBytes = 0
    private var item: MediaItem? = null

    override fun onConfigure(
        inputAudioFormat: AudioProcessor.AudioFormat
    ): AudioProcessor.AudioFormat {
        return if (
            inputAudioFormat.encoding in listOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT) &&
                inputAudioFormat.sampleRate in 4000..192000 &&
                inputAudioFormat.channelCount in 1..8
        )
            inputAudioFormat
        else AudioProcessor.AudioFormat.NOT_SET
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        normalizer = null
        partialBytes = 0
        samples = FloatArray(inputAudioFormat.channelCount.coerceAtLeast(0))
        partial =
            ByteBuffer.allocate(inputAudioFormat.bytesPerFrame.coerceAtLeast(0))
                .order(ByteOrder.nativeOrder())
        val timeline = streamMetadata.timeline
        val index = streamMetadata.periodUid?.let { timeline.getIndexOfPeriod(it) } ?: C.INDEX_UNSET
        item =
            if (index == C.INDEX_UNSET) null
            else {
                val period = timeline.getPeriod(index, Timeline.Period())
                timeline.getWindow(period.windowIndex, Timeline.Window()).mediaItem
            }
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        if (!inputBuffer.hasRemaining()) return
        val config = options()
        if (!config.enabled) {
            normalizer = null
            val output = replaceOutputBuffer(partialBytes + inputBuffer.remaining())
            if (partialBytes > 0) output.put(partial.array(), 0, partialBytes)
            partialBytes = 0
            output.put(inputBuffer).flip()
            return
        }
        val engine =
            normalizer
                ?: LoudnessNormalizer(
                        inputAudioFormat.sampleRate,
                        samples.size,
                        item?.let(initialLoudness),
                        config.targetLufs,
                    )
                    .also { normalizer = it }
        val frameBytes = inputAudioFormat.bytesPerFrame
        val output =
            replaceOutputBuffer((partialBytes + inputBuffer.remaining()) / frameBytes * frameBytes)
        inputBuffer.order(ByteOrder.nativeOrder())
        while (inputBuffer.hasRemaining()) {
            if (partialBytes > 0 || inputBuffer.remaining() < frameBytes) {
                val count = minOf(frameBytes - partialBytes, inputBuffer.remaining())
                inputBuffer.get(partial.array(), partialBytes, count)
                partialBytes += count
                if (partialBytes < frameBytes) break
                partial.position(0)
                processFrame(partial, output, engine, config.targetLufs)
                partialBytes = 0
            } else processFrame(inputBuffer, output, engine, config.targetLufs)
        }
        output.flip()
    }

    private fun processFrame(
        input: ByteBuffer,
        output: ByteBuffer,
        engine: LoudnessNormalizer,
        target: Double,
    ) {
        val floating = inputAudioFormat.encoding == C.ENCODING_PCM_FLOAT
        for (channel in samples.indices) samples[channel] =
            if (floating) input.float else input.short / 32768f
        engine.processFrame(samples, target)
        for (value in samples) {
            if (floating) output.putFloat(value)
            else output.putShort((value * 32768).toInt().coerceIn(-32768, 32767).toShort())
        }
    }

    override fun onReset() {
        normalizer = null
        item = null
        partialBytes = 0
        samples = FloatArray(0)
        partial = ByteBuffer.allocate(0)
    }
}

/** Keep Media3's speed/duration accounting, then limit the final time-stretched PCM. */
@androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])
internal class NormalizingAudioProcessorChain(processor: NormalizingAudioProcessor) :
    DefaultAudioSink.DefaultAudioProcessorChain() {
    private val processors = super.getAudioProcessors() + processor

    override fun getAudioProcessors(): Array<AudioProcessor> = processors
}
