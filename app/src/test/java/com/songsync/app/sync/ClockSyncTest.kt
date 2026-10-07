package com.songsync.app.sync

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import kotlin.math.ln
import kotlin.random.Random

class ClockSyncTest {

    private val trueOffsetNs = 123_456_789L // host clock is ~123 ms ahead

    /** Simulates one exchange with independent jitter in each direction. */
    private fun ClockSync.exchange(localNow: Long, up: Long, hostProcessing: Long, down: Long) {
        val t0 = localNow
        val t1 = t0 + up + trueOffsetNs
        val t2 = t1 + hostProcessing
        val t3 = t2 - trueOffsetNs + down
        addSample(t0, t1, t2, t3)
    }

    @Test
    fun `symmetric link gives the exact offset`() {
        val sync = ClockSync()
        sync.exchange(localNow = 1_000_000_000, up = 5_000_000, hostProcessing = 200_000, down = 5_000_000)
        val e = sync.estimate!!
        assertThat(e.offsetNs).isEqualTo(trueOffsetNs)
        assertThat(e.minRttNs).isEqualTo(10_000_000)
    }

    @Test
    fun `fastest samples win over jittery and spiky ones`() {
        for (seed in 1..20) {
            val random = Random(seed)
            val sync = ClockSync()
            val naiveOffsets = ArrayList<Long>()
            var now = 0L
            repeat(60) {
                // Bluetooth-like: independent jitter each way plus frequent 100 ms radio spikes.
                fun delay(): Long {
                    val jitter = (-12.0 * ln(1 - random.nextDouble())).toLong()
                    val spike = if (random.nextDouble() < 0.2) 100L else 0L
                    return (10 + jitter + spike) * 1_000_000
                }
                val up = delay()
                val down = delay()
                sync.exchange(now, up = up, hostProcessing = 300_000, down = down)
                naiveOffsets += trueOffsetNs + (up - down) / 2
                now += 1_000_000_000
            }
            val errorMs = kotlin.math.abs(sync.estimate!!.offsetNs - trueOffsetNs) / 1e6
            val naiveErrorMs = kotlin.math.abs(naiveOffsets.average() - trueOffsetNs) / 1e6
            assertThat(errorMs).isAtMost(4.0)
            assertThat(errorMs).isAtMost(maxOf(1.0, naiveErrorMs))
        }
    }

    @Test
    fun `clock drift lags by at most about a millisecond`() {
        val random = Random(3)
        val sync = ClockSync()
        val skew = 100e-6 // host clock runs 100 ppm fast relative to ours
        fun offsetAt(localNs: Long) = trueOffsetNs + (localNs * skew).toLong()
        var now = 0L
        repeat(20) {
            val up = (8 + (-8.0 * ln(1 - random.nextDouble()))).toLong() * 1_000_000
            val down = (8 + (-8.0 * ln(1 - random.nextDouble()))).toLong() * 1_000_000
            val t0 = now
            val t1 = t0 + up + offsetAt(t0 + up)
            val t2 = t1 + 300_000
            val t3 = t0 + up + 300_000 + down
            sync.addSample(t0, t1, t2, t3)
            now += 1_000_000_000
        }
        // A 20 s window at 100 ppm lags by ~1 ms at most.
        val errorMs = (sync.estimate!!.offsetNs - offsetAt(now)) / 1e6
        assertThat(errorMs).isWithin(1.5).of(0.0)
    }

    @Test
    fun `quality readouts report spread, newest sample and drift`() {
        val random = Random(5)
        val sync = ClockSync()
        val skew = 40e-6 // host clock runs 40 ppm fast relative to ours
        var now = 0L
        repeat(120) {
            val up = (5 + (-3.0 * ln(1 - random.nextDouble()))).toLong() * 1_000_000
            val down = (5 + (-3.0 * ln(1 - random.nextDouble()))).toLong() * 1_000_000
            val t0 = now
            val t1 = t0 + up + trueOffsetNs + ((t0 + up) * skew).toLong()
            val t2 = t1 + 300_000
            val t3 = t0 + up + 300_000 + down
            sync.addSample(t0, t1, t2, t3)
            if (it == 30) assertThat(sync.estimate!!.skewPpm).isNull() // not enough history yet
            now += 1_000_000_000
        }
        val e = sync.estimate!!
        assertThat(e.newestSampleAtNs).isGreaterThan(now - 1_000_000_000)
        // Jitter of a few ms each way: the best samples agree to within a couple of ms.
        assertThat(e.offsetSpreadNs / 1e6).isAtMost(2.0)
        assertThat(e.skewPpm!!).isWithin(10.0).of(40.0)
    }

    @Test
    fun `impossible samples are rejected`() {
        val sync = ClockSync()
        assertThat(sync.addSample(t0 = 100, t1 = 50, t2 = 40, t3 = 120)).isFalse() // t2 < t1
        assertThat(sync.addSample(t0 = 100, t1 = 500, t2 = 600, t3 = 150)).isFalse() // negative RTT
        assertThat(sync.estimate).isNull()
    }

    @Test
    fun `clean links use the short window and jittery links the long one`() {
        val clean = ClockSync(minWindow = 4, maxWindow = 12)
        repeat(30) { clean.exchange(it * 1_000_000_000L, 1_000_000, 0, 1_000_000) }
        assertThat(clean.estimate!!.samples).isEqualTo(4)

        val jittery = ClockSync(minWindow = 4, maxWindow = 12)
        repeat(30) { i -> jittery.exchange(i * 1_000_000_000L, (5 + (i % 4) * 30) * 1_000_000L, 0, 5_000_000) }
        assertThat(jittery.estimate!!.samples).isEqualTo(12)
    }
}
