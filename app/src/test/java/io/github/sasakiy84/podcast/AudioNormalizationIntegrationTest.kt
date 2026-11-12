package io.github.sasakiy84.podcast

import android.app.DownloadManager
import android.app.job.JobScheduler
import android.content.Intent
import android.media.MediaFormat
import android.net.Uri
import android.os.Environment
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.audio.AudioProcessingPipeline
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.exoplayer.source.SinglePeriodTimeline
import com.google.common.collect.ImmutableList
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowDownloadManager
import org.robolectric.shadows.ShadowMediaExtractor
import org.robolectric.shadows.util.DataSource

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class AudioNormalizationIntegrationTest {
    private val context
        get() = RuntimeEnvironment.getApplication()

    @Before
    fun resetPreferences(): Unit {
        context.getSharedPreferences("playback", 0).edit().clear().commit()
        context.getSharedPreferences("audio_loudness", 0).edit().clear().commit()
    }

    @Test
    fun fragmentedPcmIsLosslessWhenDisabledAndLimitedWhenEnabled(): Unit {
        var options = NormalizationOptions(enabled = false)
        val processor = NormalizingAudioProcessor({ options }, { -50.0 })
        val item = MediaItem.fromUri("https://example.org/audio")
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        processor.flush(metadata(item))
        val input = tone(48000, 2, 1, 0.9)
        assertArrayEquals(input, fragmented(processor, input))
        options = NormalizationOptions()
        processor.flush(metadata(item))
        val output = fragmented(processor, input)
        assertEquals(input.size, output.size)
        val pcm = ByteBuffer.wrap(output).order(ByteOrder.nativeOrder())
        while (pcm.hasRemaining()) assertTrue(
            abs(pcm.short.toInt()) / 32768.0 <= LoudnessNormalizer.CEILING
        )
        processor.reset()
    }

    @Test
    fun floatingPointAudioSanitizesInvalidSamplesAndKeepsThePeakCeiling(): Unit {
        val processor = NormalizingAudioProcessor({ NormalizationOptions() }, { -50.0 })
        processor.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))
        processor.flush(metadata(MediaItem.fromUri("https://example.org/audio")))
        val input = ByteBuffer.allocate(16).order(ByteOrder.nativeOrder())
        for (value in listOf(Float.NaN, Float.POSITIVE_INFINITY, 0.9f, -0.9f)) input.putFloat(value)
        val output =
            ByteBuffer.wrap(fragmented(processor, input.array())).order(ByteOrder.nativeOrder())
        assertEquals(16, output.remaining())
        while (output.hasRemaining()) {
            val sample = output.float
            assertTrue(sample.isFinite())
            assertTrue(abs(sample) <= LoudnessNormalizer.CEILING + 1e-7)
        }
        processor.reset()
    }

    @Test
    fun sourceMetadataResetsGainBeforeTheNextEpisodeIsRendered(): Unit {
        val processor =
            NormalizingAudioProcessor(
                { NormalizationOptions() },
                { if (it.mediaId == "quiet") -26.0 else -8.0 },
            )
        processor.configure(AudioProcessor.AudioFormat(48000, 1, C.ENCODING_PCM_16BIT))
        fun output(id: String, value: Float): Float {
            processor.flush(
                metadata(
                    MediaItem.Builder().setMediaId(id).setUri("https://example.org/$id").build()
                )
            )
            processor.queueInput(
                ByteBuffer.allocate(2).order(ByteOrder.nativeOrder()).apply {
                    putShort((value * 32768).toInt().toShort())
                    flip()
                }
            )
            return processor.output.order(ByteOrder.nativeOrder()).short / 32768f
        }
        assertEquals(0.158f, output("quiet", 0.05f), 0.002f)
        assertEquals(0.159f, output("loud", 0.4f), 0.002f)
        processor.reset()
    }

    @Test
    fun speedProcessingKeepsDurationAndFinalPeakLimit(): Unit {
        val processor = NormalizingAudioProcessor({ NormalizationOptions() }, { -50.0 })
        val chain = NormalizingAudioProcessorChain(processor)
        chain.applyPlaybackParameters(PlaybackParameters(1.7f))
        val pipeline = AudioProcessingPipeline(ImmutableList.copyOf(chain.audioProcessors))
        pipeline.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_16BIT))
        pipeline.flush(metadata(MediaItem.fromUri("https://example.org/audio")))
        val input = ByteBuffer.wrap(tone(48000, 2, 3, 0.8)).order(ByteOrder.nativeOrder())
        var bytes = 0
        var max = 0
        fun drain() {
            val output = pipeline.output.order(ByteOrder.nativeOrder())
            bytes += output.remaining()
            while (output.hasRemaining()) max = maxOf(max, abs(output.short.toInt()))
        }
        while (input.hasRemaining()) {
            pipeline.queueInput(input)
            drain()
        }
        pipeline.queueEndOfStream()
        var turns = 0
        while (!pipeline.isEnded) {
            drain()
            check(++turns < 1000)
        }
        assertEquals(48000 * 3 / 1.7, bytes / 4.0, 20.0)
        assertTrue(max / 32768.0 <= LoudnessNormalizer.CEILING)
        pipeline.reset()
    }

    @Test
    fun completedDownloadsAreAnalyzedOnceAndInvalidatedOnRemoval(): Unit = runBlocking {
        context.deleteDatabase("podcasts.db")
        val repo = PodcastRepository(context)
        val show = Podcast("https://example.org/feed", "番組")
        val episode = Episode("analysis", show.feedUrl, "音声", "https://example.org/audio.mp3")
        val file =
            File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "analysis.audio")
        try {
            repo.store.saveFeed(PodcastFeed(show, listOf(episode)))
            repo.download(episode)
            val manager = context.getSystemService(DownloadManager::class.java)
            val request: ShadowDownloadManager.ShadowRequest =
                Shadow.extract(
                    shadowOf(manager).getRequest(requireNotNull(repo.store.downloadId(episode.id)))
                )
            request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
            val pcm = tone(48000, 1, 3, 0.05)
            file.parentFile?.mkdirs()
            file.writeBytes(pcm)
            ShadowMediaExtractor.addTrack(
                DataSource.toDataSource(file.absolutePath),
                MediaFormat.createAudioFormat("audio/raw", 48000, 1),
                pcm,
            )
            repo.load()
            assertNotNull(
                context
                    .getSystemService(JobScheduler::class.java)
                    .getPendingJob(AudioAnalysisJob.JOB_ID)
            )
            assertFalse(repo.analyzeDownloadedAudio())
            val lufs = requireNotNull(repo.loudnessProfiles.forPlayback(episode.mediaItem(repo)))
            assertEquals(-26.0, lufs, 0.2)
            val reopened = LoudnessProfiles(context, repo.settings)
            assertFalse(reopened.needsAnalysis(episode.id, file))
            assertEquals(lufs, requireNotNull(reopened.forPlayback(episode.mediaItem(repo))), 0.001)
            assertFalse(repo.analyzeDownloadedAudio())
            repo.removeDownload(episode)
            assertNull(
                repo.loudnessProfiles.forPlayback(
                    MediaItem.Builder().setMediaId(episode.id).setUri(Uri.fromFile(file)).build()
                )
            )
            assertFalse(file.exists())
        } finally {
            file.delete()
            repo.store.close()
            context.deleteDatabase("podcasts.db")
        }
    }

    @Test
    fun analysisCacheRejectsReplacedFilesAndWorkerNeverNeedsNetwork(): Unit {
        val settings = PlaybackPreferences(context.getSharedPreferences("playback", 0))
        val profiles = LoudnessProfiles(context, settings)
        val file =
            File(context.cacheDir, "analysis-cache.audio").apply { writeBytes(byteArrayOf(1)) }
        try {
            profiles.save("id", file, -25.0)
            file.writeBytes(byteArrayOf(1, 2))
            assertTrue(profiles.needsAnalysis("id", file))
            DownloadCompletionReceiver()
                .onReceive(context, Intent(DownloadManager.ACTION_DOWNLOAD_COMPLETE))
            val job =
                requireNotNull(
                    context
                        .getSystemService(JobScheduler::class.java)
                        .getPendingJob(AudioAnalysisJob.JOB_ID)
                )
            assertTrue(job.isRequireBatteryNotLow)
            assertNull(job.requiredNetwork)
            settings.setNormalizeVolume(false)
            AudioAnalysisJob.schedule(context)
            assertNull(
                context
                    .getSystemService(JobScheduler::class.java)
                    .getPendingJob(AudioAnalysisJob.JOB_ID)
            )
        } finally {
            file.delete()
        }
    }

    @Test
    fun savedAnalysisReadsIntegerAndFloatingPcmAcrossBufferBoundaries(): Unit = runBlocking {
        val formats =
            listOf(
                android.media.AudioFormat.ENCODING_PCM_8BIT,
                android.media.AudioFormat.ENCODING_PCM_16BIT,
                android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED,
                android.media.AudioFormat.ENCODING_PCM_32BIT,
                android.media.AudioFormat.ENCODING_PCM_FLOAT,
            )
        for (encoding in formats) {
            val width =
                when (encoding) {
                    3 -> 1
                    2 -> 2
                    21 -> 3
                    else -> 4
                }
            val pcm = ByteBuffer.allocate(48000 * 3 * width).order(ByteOrder.LITTLE_ENDIAN)
            repeat(48000 * 3) { index ->
                val sample = 0.05 * sin(2 * PI * 1000 * index / 48000)
                when (encoding) {
                    android.media.AudioFormat.ENCODING_PCM_8BIT ->
                        pcm.put((sample * 127 + 128).toInt().toByte())
                    android.media.AudioFormat.ENCODING_PCM_16BIT ->
                        pcm.putShort((sample * 32767).toInt().toShort())
                    android.media.AudioFormat.ENCODING_PCM_24BIT_PACKED -> {
                        val value = (sample * 8388607).toInt()
                        pcm.put(value.toByte())
                            .put((value shr 8).toByte())
                            .put((value shr 16).toByte())
                    }
                    android.media.AudioFormat.ENCODING_PCM_32BIT ->
                        pcm.putInt((sample * 2147483647).toInt())
                    else -> pcm.putFloat(sample.toFloat())
                }
            }
            val file =
                File(context.cacheDir, "pcm-$encoding.audio").apply { writeBytes(pcm.array()) }
            try {
                val format =
                    MediaFormat.createAudioFormat("audio/raw", 48000, 1).apply {
                        setInteger(MediaFormat.KEY_PCM_ENCODING, encoding)
                    }
                ShadowMediaExtractor.addTrack(
                    DataSource.toDataSource(file.absolutePath),
                    format,
                    pcm.array(),
                )
                assertEquals(-26.0, requireNotNull(analyzeSavedAudio(file)), 0.8)
            } finally {
                file.delete()
            }
        }
    }

    @Test
    fun unparseableDownloadIsNotRetriedUntilTheFileChanges(): Unit = runBlocking {
        context.deleteDatabase("podcasts.db")
        val repo = PodcastRepository(context)
        val show = Podcast("https://example.org/feed", "番組")
        val episode =
            Episode("broken-analysis", show.feedUrl, "壊れた音声", "https://example.org/audio.mp3")
        val file =
            File(context.getExternalFilesDir(Environment.DIRECTORY_PODCASTS), "${episode.id}.audio")
        try {
            repo.store.saveFeed(PodcastFeed(show, listOf(episode)))
            repo.download(episode)
            file.parentFile?.mkdirs()
            file.writeBytes(byteArrayOf(1, 2, 3))
            val manager = context.getSystemService(DownloadManager::class.java)
            val request: ShadowDownloadManager.ShadowRequest =
                Shadow.extract(
                    shadowOf(manager).getRequest(requireNotNull(repo.store.downloadId(episode.id)))
                )
            request.setStatus(DownloadManager.STATUS_SUCCESSFUL)
            assertFalse(repo.analyzeDownloadedAudio())
            assertFalse(repo.loudnessProfiles.needsAnalysis(episode.id, file))
            assertNull(repo.loudnessProfiles.forPlayback(episode.mediaItem(repo)))
            assertFalse(repo.analyzeDownloadedAudio())
            assertTrue(file.isFile)
        } finally {
            file.delete()
            repo.store.close()
            context.deleteDatabase("podcasts.db")
        }
    }

    private fun metadata(item: MediaItem): AudioProcessor.StreamMetadata {
        val timeline = SinglePeriodTimeline(120_000_000, true, false, false, null, item)
        return AudioProcessor.StreamMetadata.Builder()
            .setTimeline(timeline)
            .setPeriodUid(timeline.getUidOfPeriod(0))
            .build()
    }

    private fun tone(rate: Int, channels: Int, seconds: Int, amplitude: Double): ByteArray {
        val buffer =
            ByteBuffer.allocate(rate * channels * seconds * 2).order(ByteOrder.nativeOrder())
        repeat(rate * seconds) { index ->
            repeat(channels) {
                buffer.putShort(
                    (amplitude * 32767 * sin(2 * PI * 1000 * index / rate)).toInt().toShort()
                )
            }
        }
        return buffer.array()
    }

    private fun fragmented(processor: NormalizingAudioProcessor, data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        var offset = 0
        while (offset < data.size) {
            val size = minOf(137, data.size - offset)
            processor.queueInput(ByteBuffer.wrap(data, offset, size).slice())
            val bytes = processor.output
            while (bytes.hasRemaining()) output.write(bytes.get().toInt())
            offset += size
        }
        processor.queueEndOfStream()
        assertTrue(processor.isEnded)
        return output.toByteArray()
    }
}
