package com.songsync.app.sync.sim

import com.google.common.truth.Truth.assertThat
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

    private class Group(val scope: TestScope, seed: Int, clientCount: Int, val config: SyncConfig) {
        val random = Random(seed)
        val network = FakeNetwork(scope, random, LatencyModel())
        val hostPhone = SimPhone("host", scope, network, random, config)
        val host = HostCoordinator(scope.backgroundScope, hostPhone.endpoint, hostPhone.clock, hostPhone.local, "Host", "session")
        val clients = mutableListOf<Pair<SimPhone, ClientCoordinator>>()

        init {
            host.start()
            repeat(clientCount) { addClient("client${it + 1}") }
        }

        fun addClient(name: String): Pair<SimPhone, ClientCoordinator> {
            val phone = SimPhone(name, scope, network, random, config)
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

    @Test
    fun `every phone converges on the shared timeline and stays there`() {
        for (seed in 1..6) runTest {
            val group = Group(this, seed, clientCount = 3, config)
            startPlaying(group)
            advanceTimeBy(6_000) // start + first corrections
            val worst = worstErrorOver(group, 90_000)
            println("seed=$seed worst error over 90 s: ${"%.2f".format(worst)} ms; start latency learned=" +
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
        // After three, the very first audible sample of a resume is already on time.
        repeat(3) {
            group.host.pause()
            advanceTimeBy(3_000)
            group.host.play()
            advanceTimeBy(1_500) // lead time + start, before closed-loop corrections matter
        }
        val worst = group.errorsMs().maxOf { abs(it) }
        println("error ~1 s after resume: ${group.errorsMs().joinToString { "%.1f".format(it) }}; latency learned/true: " +
            group.phones.joinToString { "%.1f/%d".format(it.latency.startLatencyMs, it.player.startLatencyMs) } +
            "; phases: " + group.phones.joinToString { "${it.follower.phase}" })
        assertThat(worst).isAtMost(5.0)
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
        advanceTimeBy(8_000)
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
        advanceTimeBy(5_000)
        assertThat(worstErrorOver(group, 10_000)).isAtMost(5.0)
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
