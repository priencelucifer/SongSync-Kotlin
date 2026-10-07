package com.songsync.app.sync.sim

import com.google.common.truth.Truth.assertThat
import com.songsync.app.calibration.CalibrationSignal
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.sync.ClientCoordinator
import com.songsync.app.sync.FollowerPhase
import com.songsync.app.sync.HostCoordinator
import com.songsync.app.sync.NANOS_PER_MS
import com.songsync.app.sync.SyncConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Test
import kotlin.math.abs
import kotlin.random.Random

/**
 * Whole-stack simulation: one host and several clients with different clocks, audio hardware
 * and network conditions. Asserts on what a listener would actually hear.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SyncSimulationTest {

    private val config = SyncConfig()
    private val track = Track(TrackSource.CLICK_TEST, "sim", "Simulated", "Test", durationMs = 600_000)

    private class Group(
        val scope: TestScope,
        seed: Int,
        clientCount: Int,
        val config: SyncConfig,
        latency: LatencyModel = LatencyModel(),
        private val glitchMs: Double = 0.0,
        private val glitchDurationMs: Long = 800,
        private val noiseSmoothing: Double = 0.0,
    ) {
        val random = Random(seed)
        val network = FakeNetwork(scope, random, latency)
        val hostPhone = SimPhone(
            "host", scope, network, random, config,
            startGlitchMs = glitchMs, glitchDurationMs = glitchDurationMs, noiseSmoothing = noiseSmoothing,
        )
        val host = HostCoordinator(scope.backgroundScope, hostPhone.endpoint, hostPhone.clock, hostPhone.local, "Host", "session")
        val clients = mutableListOf<Pair<SimPhone, ClientCoordinator>>()

        init {
            host.start()
            repeat(clientCount) { addClient("client${it + 1}") }
        }

        fun addClient(
            name: String,
            downloadRateX: Double = Double.POSITIVE_INFINITY,
            reportBiasMs: Double = 0.0,
            startJitterMs: Long = 0,
            startGlitchMs: Double = glitchMs,
        ): Pair<SimPhone, ClientCoordinator> {
            val phone = SimPhone(
                name, scope, network, random, config,
                startGlitchMs = startGlitchMs, glitchDurationMs = glitchDurationMs,
                reportBiasMs = reportBiasMs, noiseSmoothing = noiseSmoothing, startJitterMs = startJitterMs,
            )
            phone.player.downloadRateX = downloadRateX
            val client = ClientCoordinator(scope.backgroundScope, phone.endpoint, phone.clock, phone.local, name, "test")
            network.connect("host", name)
            client.attach("host")
            return (phone to client).also { clients += it }
        }

        val phones get() = listOf(hostPhone) + clients.map { it.first }

        /** Where the shared timeline is right now, at sub-millisecond precision. */
        fun timelineMs(): Double {
            val s = host.state.value
            val hostNs = hostPhone.clock.nowNs()
            return if (s.playing && hostNs > s.anchorHostNs) {
                s.anchorPositionMs + (hostNs - s.anchorHostNs).toDouble() / NANOS_PER_MS
            } else {
                s.anchorPositionMs.toDouble()
            }
        }

        /** Heard position minus timeline, per phone. */
        fun errorsMs(phones: List<SimPhone> = this.phones): List<Double> {
            val expected = timelineMs()
            return phones.map { it.player.truePositionMs() - expected }
        }
    }

    private fun TestScope.startPlaying(group: Group) {
        group.host.playNow(track)
        var waited = 0L
        while (!group.host.state.value.playing && waited < 15_000) {
            advanceTimeBy(100)
            waited += 100
        }
        assertThat(group.host.state.value.playing).isTrue()
    }

    /** Samples the worst error over [durationMs] of virtual time. */
    private fun TestScope.worstErrorOver(group: Group, durationMs: Long, phones: List<SimPhone> = group.phones): Double {
        var worst = 0.0
        var worstAt = ""
        var t = 0L
        while (t < durationMs) {
            advanceTimeBy(250)
            runCurrent()
            t += 250
            group.errorsMs(phones).forEachIndexed { i, e ->
                if (abs(e) > worst) {
                    worst = abs(e)
                    val p = phones[i]
                    worstAt = "t=${t}ms ${p.name} err=${"%.2f".format(e)} speed=${p.player.speed} " +
                        "phase=${p.follower.phase} resyncs=${p.follower.status.hardResyncs}"
                }
            }
        }
        if (worst > 3.0) println("  worst: $worstAt")
        return worst
    }

    /** Mean heard error of one phone over [durationMs] of virtual time. */
    private fun TestScope.meanErrorOver(group: Group, phone: SimPhone, durationMs: Long): Double {
        var sum = 0.0
        var n = 0
        var t = 0L
        while (t < durationMs) {
            advanceTimeBy(250)
            runCurrent()
            t += 250
            sum += group.errorsMs(listOf(phone)).single()
            n++
        }
        return sum / n
    }

    @Test
    fun `every phone converges on the shared timeline and stays there`() {
        for (seed in 1..6) runTest {
            val group = Group(this, seed, clientCount = 3, config)
            startPlaying(group)
            advanceTimeBy(15_000) // first-ever start (latency not learned yet) + convergence
            val changesBefore = group.phones.sumOf { it.player.speedChanges }
            val worst = worstErrorOver(group, 90_000)
            val changesPerMin = (group.phones.sumOf { it.player.speedChanges } - changesBefore) / 1.5 / group.phones.size
            println("seed=$seed worst error over 90 s: ${"%.2f".format(worst)} ms; speed changes/min/phone " +
                "%.1f; start latency learned=".format(changesPerMin) +
                group.phones.joinToString { "%.0f/%d".format(it.latency.startLatencyMs, it.player.startLatencyMs) })
            assertThat(worst).isAtMost(5.0)
            group.phones.forEach { assertThat(it.follower.phase).isEqualTo(FollowerPhase.LOCKED) }
        }
    }

    @Test
    fun `first audible sample is already close after the learned latency warms up`() = runTest {
        val group = Group(this, seed = 42, clientCount = 3, config)
        startPlaying(group)
        advanceTimeBy(10_000)
        // Each start teaches the phone its start latency (persisted per audio output in the app).
        // After a few, the very first audible sample of a resume is already on time.
        repeat(3) {
            group.host.pause()
            advanceTimeBy(3_000)
            group.host.play()
            advanceTimeBy(4_000) // long enough for the start to settle and be learned from
        }
        group.host.pause()
        advanceTimeBy(3_000)
        group.host.play()
        advanceTimeBy(1_500) // lead time + start, before closed-loop corrections act
        val worst = group.errorsMs().maxOf { abs(it) }
        println("error ~1 s after resume: ${group.errorsMs().joinToString { "%.1f".format(it) }}; latency learned/true: " +
            group.phones.joinToString { "%.1f/%d".format(it.latency.startLatencyMs, it.player.startLatencyMs) } +
            "; phases: " + group.phones.joinToString { "${it.follower.phase}" })
        assertThat(worst).isAtMost(5.0)
    }

    @Test
    fun `starting the song after echo calibration is on time from the first sample`() = runTest {
        val group = Group(this, seed = 42, clientCount = 2, config)
        val calibration = CalibrationSignal.track(3)
        // The calibration WAV starts faster than a streamed song, by a different amount per phone.
        group.phones.forEachIndexed { i, phone ->
            phone.follower.learnsFromTrack = { !CalibrationSignal.isCalibrationKey(it) }
            val fast = (phone.player.startLatencyMs - 40 - 15 * i).coerceAtLeast(5)
            phone.player.trackStartLatencyMs = { key -> fast.takeIf { CalibrationSignal.isCalibrationKey(key) } }
        }
        startPlaying(group)
        advanceTimeBy(10_000)
        repeat(3) { // learn the song's start latency
            group.host.pause()
            advanceTimeBy(3_000)
            group.host.play()
            advanceTimeBy(4_000)
        }

        // Auto-calibrate: two runs of the calibration track, then the song is put back, paused.
        val songPositionMs = group.host.positionMs()
        repeat(2) {
            group.host.playNow(calibration)
            advanceTimeBy(8_000)
            group.host.pause()
            advanceTimeBy(1_500)
        }
        group.host.playNow(track, songPositionMs, autoPlay = false)
        advanceTimeBy(4_000)
        group.host.play()
        advanceTimeBy(1_500) // lead time + start, before closed-loop corrections act

        val errors = group.errorsMs()
        println("error ~1 s after the first start after calibration: ${errors.joinToString { "%.1f".format(it) }}; " +
            "latency learned/true: " + group.phones.joinToString { "%.1f/%d".format(it.latency.startLatencyMs, it.player.startLatencyMs) })
        assertThat(errors.maxOf { abs(it) }).isAtMost(5.0)
    }

    @Test
    fun `pause stops every phone on the same sample and seeks stay in sync`() = runTest {
        val group = Group(this, seed = 7, clientCount = 3, config)
        startPlaying(group)
        advanceTimeBy(8_000)

        group.host.pause()
        advanceTimeBy(3_000)
        val paused = group.phones.map { it.player.truePositionMs() }
        assertThat(paused.max() - paused.min()).isAtMost(5.0)
        group.phones.forEach { assertThat(it.player.isPlaying).isFalse() }

        group.host.seekTo(90_000)
        group.host.play()
        advanceTimeBy(5_000)
        assertThat(worstErrorOver(group, 10_000)).isAtMost(5.0)

        group.host.seekTo(30_000) // while playing
        advanceTimeBy(5_000)
        assertThat(worstErrorOver(group, 10_000)).isAtMost(5.0)
        assertThat(group.timelineMs()).isWithin(1_000.0).of(30_000.0 + 15_000)
    }

    @Test
    fun `a phone joining mid-song locks on`() = runTest {
        val group = Group(this, seed = 11, clientCount = 2, config)
        startPlaying(group)
        advanceTimeBy(20_000)
        val (late, _) = group.addClient("late")
        advanceTimeBy(15_000) // load, first (unlearned) start, convergence
        assertThat(late.player.loadedTrackKey).isEqualTo(track.key)
        assertThat(worstErrorOver(group, 20_000, listOf(late))).isAtMost(5.0)
    }

    @Test
    fun `a phone keeps playing through a dropped link and resyncs after reconnecting`() = runTest {
        val group = Group(this, seed = 23, clientCount = 2, config)
        startPlaying(group)
        advanceTimeBy(10_000)
        val (phone, client) = group.clients.first()

        group.network.cut("host", phone.name)
        client.detach()
        advanceTimeBy(15_000)
        assertThat(phone.player.isPlaying).isTrue() // dead-reckoning on the last timeline
        group.host.pause() // the host changes the timeline while the phone is away

        advanceTimeBy(2_000)
        group.network.connect("host", phone.name)
        client.attach("host")
        advanceTimeBy(3_000)
        assertThat(phone.player.isPlaying).isFalse()
        group.host.play()
        advanceTimeBy(10_000)
        assertThat(worstErrorOver(group, 10_000)).isAtMost(5.0)
    }

    @Test
    fun `a wrong position right after each start does not cause a re-sync loop`() = runTest {
        // Some phones report a position 100+ ms off until AudioTrack timestamps settle.
        val group = Group(this, seed = 31, clientCount = 3, config, glitchMs = 180.0, glitchDurationMs = 2_500)
        startPlaying(group)
        advanceTimeBy(60_000)
        group.phones.forEach {
            assertThat(it.follower.status.hardResyncs).isAtMost(1)
            assertThat(it.follower.phase).isEqualTo(FollowerPhase.LOCKED)
        }
        assertThat(worstErrorOver(group, 30_000)).isAtMost(5.0)
    }

    @Test
    fun `a network outage on one phone recovers without chasing the timeline`() {
        for ((rate, recoverWithinMs) in listOf(5.0 to 10_000L, 1.5 to 25_000L)) runTest {
            val group = Group(this, seed = 13, clientCount = 2, config)
            startPlaying(group)
            advanceTimeBy(12_000)
            val (phone, _) = group.clients.first()
            val seeksBefore = phone.player.seeks
            phone.player.networkOutage(durationMs = 3_000, recoveryRateX = rate)
            advanceTimeBy(recoverWithinMs)
            assertThat(phone.player.isPlaying).isTrue()
            assertThat(phone.player.seeks - seeksBefore).isAtMost(2) // no seek-stall-seek chase
            advanceTimeBy(5_000)
            assertThat(worstErrorOver(group, 10_000, listOf(phone))).isAtMost(5.0)
            println("outage, recovery at ${rate}x: seeks=${phone.player.seeks - seeksBefore}")
        }
    }

    @Test
    fun `joining mid-song with a real download speed jumps ahead instead of waiting forever`() = runTest {
        val group = Group(this, seed = 17, clientCount = 1, config)
        startPlaying(group)
        advanceTimeBy(90_000) // far past what a fresh download from 0 would reach quickly
        val (late, _) = group.addClient("late", downloadRateX = 5.0)
        advanceTimeBy(8_000)
        assertThat(late.player.isPlaying).isTrue()
        advanceTimeBy(10_000)
        assertThat(worstErrorOver(group, 15_000, listOf(late))).isAtMost(5.0)
    }

    @Test
    fun `a jittery Bluetooth-like link stays echo-free and never re-syncs`() = runTest {
        val bluetooth = LatencyModel(baseMs = 30, jitterMeanMs = 25.0, spikeChance = 0.15, spikeMaxMs = 200)
        val group = Group(this, seed = 19, clientCount = 3, config, latency = bluetooth)
        startPlaying(group)
        advanceTimeBy(12_000)
        val worst = worstErrorOver(group, 90_000)
        println("bluetooth-like link: worst error ${"%.1f".format(worst)} ms")
        assertThat(worst).isAtMost(20.0)
        group.phones.forEach { assertThat(it.follower.status.hardResyncs).isEqualTo(0) }
    }

    @Test
    fun `while calibration measures, timing is frozen and nothing moves`() = runTest {
        val group = Group(this, seed = 29, clientCount = 2, config)
        startPlaying(group)
        advanceTimeBy(15_000)
        val before = group.errorsMs()
        group.phones.forEach { it.follower.correctionsFrozen = true }
        advanceTimeBy(20_000)
        group.phones.forEachIndexed { i, phone ->
            assertThat(phone.player.speed).isEqualTo(1f)
            assertThat(phone.follower.status.hardResyncs).isEqualTo(0)
            // Only crystal drift moves a frozen phone: a few ms over 20 s at most.
            assertThat(group.errorsMs()[i] - before[i]).isWithin(3.0).of(0.0)
        }
    }

    @Test
    fun `unreported output delay is invisible to the loop until calibration cancels it`() = runTest {
        // A phone whose sound leaves the speaker 12 ms after its reported position (speaker DSP).
        val group = Group(this, seed = 3, clientCount = 1, config)
        val (biased, _) = group.addClient("biased", reportBiasMs = 12.0)
        startPlaying(group)
        advanceTimeBy(15_000)

        val heardBefore = meanErrorOver(group, biased, 10_000)
        val reported = biased.follower.status.errorMs!!
        println("uncalibrated: heard ${"%.1f".format(heardBefore)} ms, follower reports ${"%.1f".format(reported)} ms")
        // Listeners hear it late (bias plus whatever the loop leaves inside its dead-band)...
        assertThat(heardBefore).isWithin(config.deadbandMs).of(-12.0)
        assertThat(abs(reported)).isAtMost(config.deadbandMs) // ...while the loop believes it is in sync

        biased.latency.calibrationMs = 12.0 // what echo calibration measures for this phone
        advanceTimeBy(10_000)
        assertThat(worstErrorOver(group, 20_000)).isAtMost(5.0)
    }

    @Test
    fun `slowly wandering position reports still stay echo-free`() = runTest {
        // Reported positions that drift around instead of jittering independently (like a
        // smoothed currentPosition): the window median can no longer average the noise away.
        val group = Group(this, seed = 37, clientCount = 3, config, noiseSmoothing = 0.95)
        startPlaying(group)
        advanceTimeBy(15_000)
        val worst = worstErrorOver(group, 90_000)
        println("correlated report noise: worst error ${"%.2f".format(worst)} ms")
        assertThat(worst).isAtMost(5.0)
    }

    @Test
    fun `a song can be put back paused where it was, then resumed in sync`() = runTest {
        // What happens after a calibration or sync check: the previous song returns, paused.
        val group = Group(this, seed = 41, clientCount = 2, config)
        group.host.playNow(track, positionMs = 42_000, autoPlay = false)
        advanceTimeBy(10_000)
        assertThat(group.host.state.value.playing).isFalse()
        group.phones.forEach {
            assertThat(it.player.isPlaying).isFalse()
            assertThat(it.player.truePositionMs()).isWithin(5.0).of(42_000.0)
        }
        group.host.play()
        advanceTimeBy(8_000)
        assertThat(worstErrorOver(group, 10_000)).isAtMost(5.0)
    }

    @Test
    fun `an inconsistent old phone has settled before calibration chirps start`() = runTest {
        // Like the Android 8 phone in a real report: every start is up to ±40 ms off, and the
        // reported position is wrong for a moment after it. Calibration freezes corrections
        // shortly before its first chirp, so each fresh start must be settled by then.
        val freezeAtMs = com.songsync.app.calibration.CalibrationSignal.LEAD_IN_MS - 1_000
        val group = Group(this, seed = 43, clientCount = 1, config)
        val (old, _) = group.addClient("old", startJitterMs = 40, startGlitchMs = 60.0)
        startPlaying(group)
        advanceTimeBy(20_000)
        val atFreeze = mutableListOf<Double>()
        repeat(6) {
            group.host.seekTo(0) // a fresh scheduled start (re-armed at once, like each calibration pass)
            advanceTimeBy(freezeAtMs)
            atFreeze += group.errorsMs(listOf(old)).single()
            advanceTimeBy(5_000)
        }
        println("old phone error at calibration freeze: ${atFreeze.joinToString { "%+.1f".format(it) }} ms")
        // Was ±40 ms with a 5 s lead-in; now within the dead-band plus clock error on every run.
        atFreeze.forEach { assertThat(abs(it)).isAtMost(5.0) }
        assertThat(old.follower.status.hardResyncs).isEqualTo(0) // seeks re-arm, no re-sync needed
    }

    @Test
    fun `host advances to the next queued track when one ends`() = runTest {
        val group = Group(this, seed = 5, clientCount = 1, config)
        group.phones.forEach { it.player.durationMs = 20_000 }
        val next = track.copy(id = "next", title = "Next")
        group.host.enqueue(next)
        startPlaying(group)
        advanceTimeBy(25_000)
        assertThat(group.host.track.value?.key).isEqualTo(next.key)
        assertThat(group.host.queue.value).isEmpty()
    }
}
