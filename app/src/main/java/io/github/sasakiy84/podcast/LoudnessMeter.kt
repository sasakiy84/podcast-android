package io.github.sasakiy84.podcast

import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.tan

/** BS.1770 K weighting and R128 gates. Mono is measured as dual mono for headphone playback. */
internal class LoudnessMeter(
    private val sampleRate: Int,
    private val channels: Int,
    private val integrated: Boolean = false,
) {
    private val shelves = Array(channels) { shelf(sampleRate) }
    private val highPasses = Array(channels) { highPass(sampleRate) }
    private val weights =
        DoubleArray(channels) { channel ->
            when {
                channels == 1 -> 2.0
                channels >= 6 && channel == 3 -> 0.0 // LFE
                channels >= 6 && channel >= 4 -> 1.41
                channels == 5 && channel >= 3 -> 1.41
                channels == 4 && channel >= 2 -> 1.41
                else -> 1.0
            }
        }
    private val bucketFrames = (sampleRate / 10).coerceAtLeast(1)
    private val buckets = DoubleArray(30)
    private val histogramEnergy = if (integrated) DoubleArray(2001) else DoubleArray(0)
    private val histogramCount = if (integrated) LongArray(2001) else LongArray(0)
    private var bucketEnergy = 0.0
    private var frames = 0
    private var bucketIndex = 0
    private var bucketCount = 0
    private var totalEnergy = 0.0
    private var totalFrames = 0L

    var shortTermLufs: Double? = null
        private set

    var momentaryLufs: Double? = null
        private set

    val ready: Boolean
        get() = totalFrames >= sampleRate * 0.4

    /** Returns true every 100ms. The hot path does not allocate or compute logarithms. */
    fun addFrame(samples: FloatArray): Boolean {
        var energy = 0.0
        for (channel in 0 until channels) {
            val sample = samples[channel]
            val value = if (sample.isFinite()) sample.toDouble() else 0.0
            val filtered = highPasses[channel].filter(shelves[channel].filter(value))
            energy += weights[channel] * filtered * filtered
        }
        bucketEnergy += energy
        totalEnergy += energy
        totalFrames++
        if (++frames < bucketFrames) return false
        buckets[bucketIndex] = bucketEnergy / frames
        bucketIndex = (bucketIndex + 1) % buckets.size
        bucketCount = (bucketCount + 1).coerceAtMost(buckets.size)
        frames = 0
        bucketEnergy = 0.0
        shortTermLufs = loudness(recent(bucketCount))
        val blockEnergy = recent(minOf(4, bucketCount))
        momentaryLufs = loudness(blockEnergy)
        if (integrated && bucketCount >= 4) {
            val level = loudness(blockEnergy)
            if (level != null && level >= -70) {
                val index = ((level + 100) * 10).toInt().coerceIn(histogramEnergy.indices)
                histogramEnergy[index] += blockEnergy
                histogramCount[index]++
            }
        }
        return true
    }

    /** A 0.1-LU histogram keeps memory bounded even for very long episodes. */
    fun integratedLufs(): Double? {
        check(integrated)
        val count = histogramCount.sum()
        if (count == 0L)
            return loudness(totalEnergy / totalFrames.coerceAtLeast(1))?.takeIf { it >= -70 }
        val relativeGate = requireNotNull(loudness(histogramEnergy.sum() / count)) - 10
        var energy = 0.0
        var retained = 0L
        for (index in histogramEnergy.indices) {
            if (index / 10.0 - 100 + 0.05 >= relativeGate) {
                energy += histogramEnergy[index]
                retained += histogramCount[index]
            }
        }
        return if (retained == 0L) null else loudness(energy / retained)
    }

    private fun recent(count: Int): Double {
        var sum = 0.0
        for (offset in 1..count) sum +=
            buckets[(bucketIndex - offset + buckets.size) % buckets.size]
        return sum / count.coerceAtLeast(1)
    }

    private class Biquad(
        private val b0: Double,
        private val b1: Double,
        private val b2: Double,
        private val a1: Double,
        private val a2: Double,
    ) {
        private var z1 = 0.0
        private var z2 = 0.0

        fun filter(value: Double): Double {
            val output = b0 * value + z1
            z1 = b1 * value - a1 * output + z2
            z2 = b2 * value - a2 * output
            return output
        }
    }

    companion object {
        private fun loudness(energy: Double): Double? =
            if (energy > 1e-15 && energy.isFinite()) -0.691 + 10 * log10(energy) else null

        private fun shelf(rate: Int): Biquad {
            val k = tan(Math.PI * 1681.974450955533 / rate)
            val q = 0.7071752369554196
            val vh = 10.0.pow(3.999843853973347 / 20)
            val vb = vh.pow(0.4996667741545416)
            val a0 = 1 + k / q + k * k
            return Biquad(
                (vh + vb * k / q + k * k) / a0,
                2 * (k * k - vh) / a0,
                (vh - vb * k / q + k * k) / a0,
                2 * (k * k - 1) / a0,
                (1 - k / q + k * k) / a0,
            )
        }

        private fun highPass(rate: Int): Biquad {
            val k = tan(Math.PI * 38.13547087602444 / rate)
            val q = 0.5003270373238773
            val a0 = 1 + k / q + k * k
            return Biquad(1.0, -2.0, 1.0, 2 * (k * k - 1) / a0, (1 - k / q + k * k) / a0)
        }
    }
}
