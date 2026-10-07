package com.songsync.app.calibration

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Finds when a known chirp arrives in a microphone recording, to a fraction of a sample.
 *
 * Matched filter (cross-correlation via FFT) followed by the analytic-signal envelope, so the
 * peak does not depend on the speaker/microphone phase. Rooms add reflections that can be
 * louder than the direct sound, so the *first* strong peak is taken, not the largest.
 */
class ChirpDetector(private val template: FloatArray) {

    data class Peak(
        /** Arrival of the chirp's first sample, as a (fractional) index into the recording. */
        val sample: Double,
        /** Peak height relative to the window's median envelope. */
        val snr: Double,
    )

    /**
     * Matched-filter envelope for chirp starts at samples [from] until [to] (clipped to the
     * recording): element i belongs to sample `start + i`, where start is returned with it.
     */
    fun envelope(recording: ShortArray, size: Int, from: Int, to: Int): Pair<Int, DoubleArray>? {
        val start = from.coerceAtLeast(0)
        val lags = (to.coerceAtMost(size) - start)
        if (lags <= 2) return null
        val segmentLength = minOf(lags + template.size, size - start)
        val n = Integer.highestOneBit(maxOf(segmentLength + template.size, 2) - 1) shl 1

        val xr = DoubleArray(n)
        val xi = DoubleArray(n)
        for (i in 0 until segmentLength) xr[i] = recording[start + i].toDouble()
        val hr = DoubleArray(n)
        val hi = DoubleArray(n)
        for (i in template.indices) hr[i] = template[i].toDouble()

        fft(xr, xi, inverse = false)
        fft(hr, hi, inverse = false)
        // Cross-correlation spectrum X * conj(H), made analytic (negative frequencies removed).
        for (k in 0 until n) {
            val re = xr[k] * hr[k] + xi[k] * hi[k]
            val im = xi[k] * hr[k] - xr[k] * hi[k]
            val gain = when {
                k == 0 || k == n / 2 -> 1.0
                k < n / 2 -> 2.0
                else -> 0.0
            }
            xr[k] = re * gain
            xi[k] = im * gain
        }
        fft(xr, xi, inverse = true)

        return start to DoubleArray(lags) { hypot(xr[it], xi[it]) }
    }

    fun find(recording: ShortArray, size: Int, from: Int, to: Int): Peak? {
        val (start, envelope) = envelope(recording, size, from, to) ?: return null
        val lags = envelope.size
        val max = envelope.max()
        if (max <= 0.0) return null
        val noise = envelope.sorted()[lags / 2].coerceAtLeast(1e-9)

        // First local maximum reaching half of the strongest one = the direct path.
        var best = envelope.indices.first { envelope[it] == max }
        for (i in 1 until lags - 1) {
            if (envelope[i] >= FIRST_ARRIVAL_FRACTION * max && envelope[i] >= envelope[i - 1] && envelope[i] >= envelope[i + 1]) {
                best = i
                break
            }
        }
        val offset = if (best in 1 until lags - 1) {
            val a = envelope[best - 1]
            val b = envelope[best]
            val c = envelope[best + 1]
            val denominator = a - 2 * b + c
            if (denominator != 0.0) (0.5 * (a - c) / denominator).coerceIn(-0.5, 0.5) else 0.0
        } else {
            0.0
        }
        return Peak(start + best + offset, envelope[best] / noise)
    }

    private companion object {
        const val FIRST_ARRIVAL_FRACTION = 0.5

        /** In-place iterative radix-2 FFT; [re].size must be a power of two. */
        fun fft(re: DoubleArray, im: DoubleArray, inverse: Boolean) {
            val n = re.size
            var j = 0
            for (i in 1 until n) {
                var bit = n shr 1
                while (j and bit != 0) {
                    j = j xor bit
                    bit = bit shr 1
                }
                j = j xor bit
                if (i < j) {
                    var t = re[i]; re[i] = re[j]; re[j] = t
                    t = im[i]; im[i] = im[j]; im[j] = t
                }
            }
            var length = 2
            while (length <= n) {
                val angle = (if (inverse) 2 else -2) * PI / length
                val wr = cos(angle)
                val wi = sin(angle)
                for (i in 0 until n step length) {
                    var cr = 1.0
                    var ci = 0.0
                    for (k in 0 until length / 2) {
                        val ar = re[i + k]
                        val ai = im[i + k]
                        val br = re[i + k + length / 2] * cr - im[i + k + length / 2] * ci
                        val bi = re[i + k + length / 2] * ci + im[i + k + length / 2] * cr
                        re[i + k] = ar + br
                        im[i + k] = ai + bi
                        re[i + k + length / 2] = ar - br
                        im[i + k + length / 2] = ai - bi
                        val next = cr * wr - ci * wi
                        ci = cr * wi + ci * wr
                        cr = next
                    }
                }
                length = length shl 1
            }
            if (inverse) {
                for (i in 0 until n) {
                    re[i] /= n
                    im[i] /= n
                }
            }
        }
    }
}
