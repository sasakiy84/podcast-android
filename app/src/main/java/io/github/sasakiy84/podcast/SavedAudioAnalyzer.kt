package io.github.sasakiy84.podcast

import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.ensureActive

/**
 * Decodes one local file without playing it or changing it. Buffers and codec lifetime are bounded.
 */
internal suspend fun analyzeSavedAudio(file: File): Double? {
    val extractor = MediaExtractor()
    try {
        extractor.setDataSource(file.absolutePath)
        val track =
            (0 until extractor.trackCount).firstOrNull {
                extractor
                    .getTrackFormat(it)
                    .getString(MediaFormat.KEY_MIME)
                    ?.startsWith("audio/") == true
            } ?: throw IOException("音声トラックがありません。")
        extractor.selectTrack(track)
        val format = extractor.getTrackFormat(track)
        val mime = requireNotNull(format.getString(MediaFormat.KEY_MIME))
        if (mime == "audio/raw") {
            val pcm = PcmLoudnessReader(format)
            val buffer = ByteBuffer.allocateDirect(256 * 1024)
            while (true) {
                coroutineContext.ensureActive()
                buffer.clear()
                val size = extractor.readSampleData(buffer, 0)
                if (size < 0) break
                buffer.position(0).limit(size)
                pcm.consume(buffer)
                extractor.advance()
            }
            return pcm.result()
        }
        val codec = MediaCodec.createDecoderByType(mime)
        try {
            codec.configure(format, null, null, 0)
            codec.start()
            val info = MediaCodec.BufferInfo()
            var reader: PcmLoudnessReader? = null
            var inputEnded = false
            var lastOutput = SystemClock.elapsedRealtime()
            while (true) {
                coroutineContext.ensureActive()
                if (SystemClock.elapsedRealtime() - lastOutput > 15_000)
                    throw IOException("音声の解析が進みませんでした。")
                if (!inputEnded) {
                    val index = codec.dequeueInputBuffer(10_000)
                    if (index >= 0) {
                        val buffer = requireNotNull(codec.getInputBuffer(index))
                        buffer.clear()
                        val size = extractor.readSampleData(buffer, 0)
                        inputEnded = size < 0
                        codec.queueInputBuffer(
                            index,
                            0,
                            size.coerceAtLeast(0),
                            if (inputEnded) 0 else extractor.sampleTime,
                            if (inputEnded) MediaCodec.BUFFER_FLAG_END_OF_STREAM else 0,
                        )
                        if (!inputEnded) extractor.advance()
                    }
                }
                val output = codec.dequeueOutputBuffer(info, 10_000)
                if (output == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    val next = codec.outputFormat
                    if (reader == null) reader = PcmLoudnessReader(next)
                    else if (!reader.accepts(next)) throw IOException("音声形式が途中で変わりました。")
                } else if (output >= 0) {
                    try {
                        lastOutput = SystemClock.elapsedRealtime()
                        if (
                            info.size > 0 && info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG == 0
                        ) {
                            val pcm =
                                reader ?: PcmLoudnessReader(codec.outputFormat).also { reader = it }
                            val buffer = requireNotNull(codec.getOutputBuffer(output))
                            buffer.position(info.offset).limit(info.offset + info.size)
                            pcm.consume(buffer)
                        }
                    } finally {
                        codec.releaseOutputBuffer(output, false)
                    }
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) break
                }
            }
            return reader?.result()
        } finally {
            codec.release()
        }
    } finally {
        extractor.release()
    }
}

private class PcmLoudnessReader(format: MediaFormat) {
    private val rate = format.getInteger(MediaFormat.KEY_SAMPLE_RATE)
    private val channels = format.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
    private val encoding =
        format.getInteger(
            MediaFormat.KEY_PCM_ENCODING,
            android.media.AudioFormat.ENCODING_PCM_16BIT,
        )
    private val bytes =
        when (encoding) {
            android.media.AudioFormat.ENCODING_PCM_16BIT -> 2
            android.media.AudioFormat.ENCODING_PCM_8BIT -> 1
            android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> 3
            android.media.AudioFormat.ENCODING_PCM_32BIT -> 4
            android.media.AudioFormat.ENCODING_PCM_FLOAT -> 4
            else -> throw IOException("この音声形式は事前解析できません。")
        }
    private val meter: LoudnessMeter
    private val frame: FloatArray
    private val partial: ByteBuffer
    private var partialBytes = 0

    init {
        if (rate !in 4000..192000 || channels !in 1..8) throw IOException("この音声形式は事前解析できません。")
        meter = LoudnessMeter(rate, channels, integrated = true)
        frame = FloatArray(channels)
        partial = ByteBuffer.allocate(channels * bytes).order(ByteOrder.LITTLE_ENDIAN)
    }

    fun accepts(format: MediaFormat): Boolean =
        rate == format.getInteger(MediaFormat.KEY_SAMPLE_RATE) &&
            channels == format.getInteger(MediaFormat.KEY_CHANNEL_COUNT) &&
            encoding ==
                format.getInteger(
                    MediaFormat.KEY_PCM_ENCODING,
                    android.media.AudioFormat.ENCODING_PCM_16BIT,
                )

    fun consume(buffer: ByteBuffer) {
        buffer.order(ByteOrder.LITTLE_ENDIAN)
        val frameBytes = channels * bytes
        while (buffer.hasRemaining()) {
            if (partialBytes > 0 || buffer.remaining() < frameBytes) {
                val size = minOf(frameBytes - partialBytes, buffer.remaining())
                buffer.get(partial.array(), partialBytes, size)
                partialBytes += size
                if (partialBytes < frameBytes) break
                partial.position(0)
                addFrame(partial)
                partialBytes = 0
            } else addFrame(buffer)
        }
    }

    private fun addFrame(buffer: ByteBuffer) {
        for (channel in 0 until channels) frame[channel] =
            when (encoding) {
                android.media.AudioFormat.ENCODING_PCM_8BIT ->
                    ((buffer.get().toInt() and 255) - 128) / 128f
                android.media.AudioFormat.ENCODING_PCM_16BIT -> buffer.short / 32768f
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                    val value =
                        (buffer.get().toInt() and 255) or
                            ((buffer.get().toInt() and 255) shl 8) or
                            (buffer.get().toInt() shl 16)
                    value / 8388608f
                }
                android.media.AudioFormat.ENCODING_PCM_32BIT -> buffer.int / 2147483648f
                else -> buffer.float.let { if (it.isFinite()) it.coerceIn(-1f, 1f) else 0f }
            }
        meter.addFrame(frame)
    }

    fun result(): Double? {
        if (partialBytes != 0) throw IOException("音声データが途中で切れています。")
        return meter.integratedLufs()
    }
}
