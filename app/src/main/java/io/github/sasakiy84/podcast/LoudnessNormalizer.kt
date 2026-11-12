package io.github.sasakiy84.podcast

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.pow

internal data class NormalizationOptions(val enabled: Boolean = true, val louder: Boolean = false) {
    val targetLufs: Double
        get() = if (louder) -14.0 else -16.0
}

/** Linked-channel gain rider and zero-latency peak limiter. Never changes frame count or pitch. */
internal class LoudnessNormalizer(
    sampleRate: Int,
    private val channels: Int,
    initialLufs: Double?,
    targetLufs: Double,
) {
    private val meter = LoudnessMeter(sampleRate, channels)
    private val rise = 1 - exp(-1.0 / sampleRate)
    private val fall = 1 - exp(-1.0 / (sampleRate * 0.08))
    private val limiterRelease = 1 - exp(-1.0 / (sampleRate * 0.1))
    private var desiredGain = initialLufs?.let { gain(targetLufs - it) } ?: 1.0
    private var currentGain = desiredGain
    private var limiterGain = 1.0
    private var target = targetLufs

    fun processFrame(samples: FloatArray, targetLufs: Double) {
        for (channel in 0 until channels) {
            samples[channel] =
                if (samples[channel].isFinite()) samples[channel].coerceIn(-1f, 1f) else 0f
        }
        val updated = meter.addFrame(samples)
        if ((updated || target != targetLufs) && meter.ready) {
            target = targetLufs
            val level = meter.shortTermLufs
            if (level != null) {
                val requested = gain(targetLufs - level)
                // Silence must not progressively turn room noise up.
                if ((meter.momentaryLufs ?: -100.0) >= -50 || requested < desiredGain)
                    desiredGain = requested
            }
        }
        currentGain += (desiredGain - currentGain) * if (desiredGain > currentGain) rise else fall
        var peak = 0.0
        for (channel in 0 until channels) peak = maxOf(peak, abs(samples[channel].toDouble()))
        val safe = if (peak * currentGain > CEILING) CEILING / (peak * currentGain) else 1.0
        limiterGain = min(safe, limiterGain + (1 - limiterGain) * limiterRelease)
        val multiplier = currentGain * limiterGain
        for (channel in 0 until channels) samples[channel] =
            (samples[channel] * multiplier).coerceIn(-CEILING, CEILING).toFloat()
    }

    companion object {
        val CEILING: Double = 10.0.pow(-1.0 / 20)

        private fun gain(db: Double): Double = 10.0.pow(db.coerceIn(-60.0, 12.0) / 20)
    }
}
