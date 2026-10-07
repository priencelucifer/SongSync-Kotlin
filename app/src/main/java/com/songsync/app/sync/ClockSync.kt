package com.songsync.app.sync

import kotlin.math.abs
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
        /** Median absolute deviation of the fastest-quarter offsets: how much they disagree. */
        val offsetSpreadNs: Long = 0,
        /** Local receive time of the newest sample, to tell a stale estimate from a fresh one. */
        val newestSampleAtNs: Long = 0,
        /**
         * How fast the estimate itself moves (host clock rate vs ours), in ppm, once there is
         * at least [SKEW_MIN_SPAN_NS] of history; null before.
         */
        val skewPpm: Double? = null,
    )

    private data class Sample(val offsetNs: Long, val rttNs: Long, val atNs: Long, val sentNs: Long)

    /** Set by [markEpoch]: samples sent before this belong to the previous link. */
    private var epochStartNs: Long? = null
    private var epochMinSamples = 0

    private val samples = ArrayDeque<Sample>()
    /** (local time, estimated offset) after each sample, for the skew readout. */
    private val history = ArrayDeque<Pair<Long, Long>>()

    var estimate: Estimate? = null
        private set

    /** Returns false for impossible samples (negative round trip), which are dropped. */
    fun addSample(t0: Long, t1: Long, t2: Long, t3: Long): Boolean {
        val rtt = (t3 - t0) - (t2 - t1)
        if (rtt < 0 || t3 < t0 || t2 < t1) return false
        val offset = ((t1 - t0) + (t2 - t3)) / 2
        samples.addLast(Sample(offset, rtt, t3, t0))
        while (samples.size > maxWindow) samples.removeFirst()
        epochStartNs?.let { start ->
            if (samples.count { it.sentNs >= start } >= epochMinSamples) {
                samples.removeAll { it.sentNs < start }
                history.clear() // the old link's offsets would show up as fake drift
                epochStartNs = null
            }
        }
        recompute(t3)
        return true
    }

    /**
     * The link changed (e.g. Nearby upgraded from Bluetooth to Wi-Fi), so delays and their
     * asymmetry changed too. The current estimate stays until [minNewSamples] exchanges sent
     * after [atNs] have arrived; then every older sample is dropped, instead of lingering in the
     * window (up to a minute on a jittery link).
     */
    fun markEpoch(atNs: Long, minNewSamples: Int) {
        epochStartNs = atNs
        epochMinSamples = max(1, minNewSamples)
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
        history.clear()
        epochStartNs = null
        estimate = null
    }

    private fun recompute(nowNs: Long) {
        val window = activeWindow()
        val n = window.size
        val byRtt = window.sortedBy { it.rttNs }
        val best = max(1, ceil(n * BEST_FRACTION).toInt())
        val offsets = byRtt.take(best).map { it.offsetNs }.sorted()
        val rtts = byRtt.map { it.rttNs }
        val offset = offsets.median()

        history.addLast(nowNs to offset)
        while (history.size > 1 && nowNs - history.first().first > SKEW_MAX_SPAN_NS) history.removeFirst()
        val (oldAt, oldOffset) = history.first()
        val skewPpm = if (nowNs - oldAt >= SKEW_MIN_SPAN_NS) (offset - oldOffset).toDouble() / (nowNs - oldAt) * 1e6 else null

        estimate = Estimate(
            offsetNs = offset,
            minRttNs = rtts.first(),
            medianRttNs = rtts.median(),
            p90RttNs = rtts[min(n - 1, (n * 0.9).toInt())],
            samples = n,
            offsetSpreadNs = offsets.map { abs(it - offset) }.sorted().median(),
            newestSampleAtNs = window.last().atNs,
            skewPpm = skewPpm,
        )
    }

    private fun List<Long>.median(): Long =
        if (size % 2 == 1) this[size / 2] else (this[size / 2 - 1] + this[size / 2]) / 2

    companion object {
        private const val BEST_FRACTION = 0.25
        /** Skew is measured over the last 60–180 s of estimates. */
        const val SKEW_MIN_SPAN_NS = 60_000_000_000L
        private const val SKEW_MAX_SPAN_NS = 180_000_000_000L
    }
}
