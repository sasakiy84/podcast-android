package io.github.sasakiy84.podcast

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.sqrt
import org.junit.Assert.*
import org.junit.Test

class LoudnessNormalizerTest {
    @Test
    fun kWeightingCalibratesKnownToneAndTreatsMonoAsDualMono(): Unit {
        for (rate in listOf(44100, 48000)) {
            for (channels in listOf(1, 2)) {
                val meter = LoudnessMeter(rate, channels, integrated = true)
                val frame = FloatArray(channels)
                repeat(rate * 3) { index ->
                    frame.fill((0.1 * sin(2 * PI * 1000 * index / rate)).toFloat())
                    meter.addFrame(frame)
                }
                // A 1kHz sine at -20dBFS peak is approximately -20 LUFS in stereo.
                assertEquals(-20.0, requireNotNull(meter.integratedLufs()), 0.15)
            }
        }
    }

    @Test
    fun integratedGatesExcludeLongSilenceAndVeryQuietBackground(): Unit {
        val rate = 16000
        val meter = LoudnessMeter(rate, 1, integrated = true)
        val frame = FloatArray(1)
        repeat(rate * 16) { index ->
            val amplitude =
                when (index / rate) {
                    in 2..5 -> 0.1
                    in 8..11 -> 0.0001
                    else -> 0.0
                }
            frame[0] = (amplitude * sin(2 * PI * 1000 * index / rate)).toFloat()
            meter.addFrame(frame)
        }
        assertEquals(-20.0, requireNotNull(meter.integratedLufs()), 0.5)
    }

    @Test
    fun liveCorrectionConvergesForQuietAndLoudRecordings(): Unit {
        for (amplitude in listOf(0.05, 0.4)) {
            val rate = 16000
            val engine = LoudnessNormalizer(rate, 1, null, -16.0)
            val frame = FloatArray(1)
            var square = 0.0
            repeat(rate * 9) { index ->
                frame[0] = (amplitude * sin(2 * PI * 1000 * index / rate)).toFloat()
                engine.processFrame(frame, -16.0)
                if (index >= rate * 7) square += frame[0].toDouble() * frame[0]
            }
            // Independent RMS check: a dual-mono -16 LUFS tone has about 0.112 RMS per channel.
            assertEquals(0.112, sqrt(square / (rate * 2)), 0.009)
        }
    }

    @Test
    fun analyzedLoudnessAppliesFromTheFirstFrameAndBrighterTargetAddsTwoDb(): Unit {
        val standard = LoudnessNormalizer(48000, 1, -26.0, -16.0)
        val louder = LoudnessNormalizer(48000, 1, -26.0, -14.0)
        val a = floatArrayOf(0.05f)
        val b = a.copyOf()
        standard.processFrame(a, -16.0)
        louder.processFrame(b, -14.0)
        assertEquals(0.1581f, a[0], 0.001f)
        assertEquals(1.2589f, b[0] / a[0], 0.002f)
    }

    @Test
    fun limiterLinksChannelsAndBoostIsBoundedWithoutSilencePumping(): Unit {
        val limiter = LoudnessNormalizer(16000, 2, -50.0, -16.0)
        val peak = floatArrayOf(0.9f, 0.45f)
        limiter.processFrame(peak, -16.0)
        assertTrue(abs(peak[0]) <= LoudnessNormalizer.CEILING + 1e-7)
        assertEquals(2f, peak[0] / peak[1], 0.001f)
        val capped = LoudnessNormalizer(16000, 1, -60.0, -16.0)
        val tiny = floatArrayOf(0.001f)
        capped.processFrame(tiny, -16.0)
        assertEquals(0.003981f, tiny[0], 0.00001f)
        val live = LoudnessNormalizer(16000, 1, null, -16.0)
        val silence = floatArrayOf(0f)
        repeat(16000 * 8) { live.processFrame(silence, -16.0) }
        silence[0] = 0.05f
        live.processFrame(silence, -16.0)
        assertEquals(0.05f, silence[0], 0.0001f)
    }
}
