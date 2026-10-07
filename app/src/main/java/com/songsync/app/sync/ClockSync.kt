package com.songsync.app.sync

import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min

/**
 * NTP-style estimate of the offset between this phone's clock and the host's clock
 * (hostTime = localTime + offset).
 *
 * Each exchange yields t0 (client send), t1 (host receive), t2 (host send) and t3 (client
 * receive). Radio retries, Bluetooth scheduling and a busy main thread only ever *add* delay,
 * so the samples with the smallest round-trip time are the most trustworthy: the estimate is
 * the median offset of the fastest quarter of a sliding window.
 *
 * The window adapts to the link. On a clean (Wi-Fi) link it is short, ~20 s at one sample per
 * second: phone clocks drift by up to ~100 ppm relative to each other, and a short window bounds
 * the resulting lag to about 1 ms. On a jittery (Bluetooth) link offset noise dominates instead,
 * so up to [maxWindow] samples are kept to find enough fast exchanges.
 */
class ClockSync(
    private val minWindow: Int = 20,
    private val maxWindow: Int = 60,
    private val jitteryRttSpreadNs: Long = 10_000_000,
) {

    data class Estimate(
        val offsetNs: Long,
        val minRttNs: Long,
        val medianRttNs: Long,
        val p90RttNs: Long,
        val samples: Int,
    )

    private data class Sample(val offsetNs: Long, val rttNs: Long)

    private val samples = ArrayDeque<Sample>()

    var estimate: Estimate? = null
        private set

    /** Returns false for impossible samples (negative round trip), which are dropped. */
    fun addSample(t0: Long, t1: Long, t2: Long, t3: Long): Boolean {
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt < 0 || t3 < t0 || t2 < t1) return false
        val offset = ((t1 - t0) + (t2 - t3)) / 2
        samples.addLast(Sample(offset, rtt))
        while (samples.size > maxWindow) samples.removeFirst()
        recompute()
        return true
    }

    /** Short window on clean links, long window when round trips are erratic. */
    private fun activeWindow(): List<Sample> {
        val recent = samples.takeLast(minWindow)
        if (samples.size <= minWindow) return recent
        val rtts = recent.map { it.rttNs }.sorted()
        val spread = rtts[rtts.size / 2] - rtts.first()
        return if (spread > jitteryRttSpreadNs) samples.toList() else recent
    }

    fun reset() {
        samples.clear()
        estimate = null
    }

    private fun recompute() {
        val window = activeWindow()
        val n = window.size
        val byRtt = window.sortedBy { it.rttNs }
        val best = max(1, ceil(n * BEST_FRACTION).toInt())
        val offsets = byRtt.take(best).map { it.offsetNs }.sorted()
        val rtts = byRtt.map { it.rttNs }
        estimate = Estimate(
            offsetNs = offsets.median(),
            minRttNs = rtts.first(),
            medianRttNs = rtts.median(),
            p90RttNs = rtts[min(n - 1, (n * 0.9).toInt())],
            samples = n,
        )
    }

    private fun List<Long>.median(): Long =
        if (size % 2 == 1) this[size / 2] else (this[size / 2 - 1] + this[size / 2]) / 2

    private companion object {
        const val BEST_FRACTION = 0.25
    }
}
