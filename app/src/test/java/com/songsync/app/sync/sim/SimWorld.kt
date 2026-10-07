package com.songsync.app.sync.sim

import com.songsync.app.data.model.ResolvedTrack
import com.songsync.app.data.model.Track
import com.songsync.app.net.Transport
import com.songsync.app.net.TransportEvent
import com.songsync.app.sync.Cancellable
import com.songsync.app.sync.InMemoryLatencyProfile
import com.songsync.app.sync.LocalPlayback
import com.songsync.app.sync.MonotonicClock
import com.songsync.app.sync.PlaybackFollower
import com.songsync.app.sync.Scheduler
import com.songsync.app.sync.SyncConfig
import com.songsync.app.sync.SyncPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlin.math.ceil
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong
import kotlin.random.Random

/*
 * A deterministic virtual world for exercising the whole sync stack: every phone has its own
 * clock (random offset and drift), its own audio hardware (start latency, DAC clock drift,
 * position-report noise) and talks over a lossy-latency, in-order network. "True" time is the
 * coroutine test scheduler's virtual time in milliseconds.
 */

/** A phone's monotonic clock: offset and drift relative to true time. */
class DeviceClock(private val trueMs: () -> Long, val offsetNs: Long, ppm: Double) : MonotonicClock {
    val rate = 1.0 + ppm / 1e6
    override fun nowNs(): Long = (trueMs() * 1e6 * rate).toLong() + offsetNs
}

class VirtualScheduler(private val scope: CoroutineScope, private val clock: DeviceClock) : Scheduler {
    override fun schedule(atNs: Long, action: () -> Unit): Cancellable {
        val job = scope.launch {
            val waitNs = atNs - clock.nowNs()
            if (waitNs > 0) delay(ceil(waitNs / 1e6 / clock.rate).toLong())
            action()
        }
        return Cancellable { job.cancel() }
    }
}

/** Models ExoPlayer + AudioTrack closely enough for sync purposes. */
class SimulatedPlayer(
    private val trueMs: () -> Long,
    val startLatencyMs: Long,
    audioPpm: Double,
    private val seekReadyMs: Long,
    private val noiseMs: () -> Double,
) : SyncPlayer {
    private val audioRate = 1.0 + audioPpm / 1e6
    var durationMs = 600_000L
    override var loadedTrackKey: String? = null
        private set
    override var speed = 1f
        private set
    override val isInterrupted = false

    private var playWhenReady = false
    private var readyAtMs = Long.MAX_VALUE
    private var basePos = 0.0
    private var audibleFromMs = 0L
    var plays = 0
        private set
    var seeks = 0
        private set

    fun load(key: String) {
        loadedTrackKey = key
        readyAtMs = trueMs()
        basePos = 0.0
        playWhenReady = false
    }

    override val isReady get() = loadedTrackKey != null && trueMs() >= readyAtMs
    override val isPlaying get() = playWhenReady && isReady

    /** What a listener actually hears (no reporting noise). */
    fun truePositionMs(): Double {
        if (!playWhenReady) return basePos
        val now = trueMs()
        return if (now <= audibleFromMs) basePos
        else min(durationMs.toDouble(), basePos + (now - audibleFromMs) * speed * audioRate)
    }

    override val positionMs: Long
        get() = if (isPlaying) (truePositionMs() + noiseMs()).roundToLong() else truePositionMs().roundToLong()

    override fun play() {
        if (playWhenReady) return
        playWhenReady = true
        plays++
        audibleFromMs = max(trueMs(), readyAtMs) + startLatencyMs
    }

    override fun pause() {
        if (!playWhenReady) return
        basePos = truePositionMs()
        playWhenReady = false
    }

    override fun seekTo(positionMs: Long) {
        seeks++
        basePos = positionMs.toDouble()
        readyAtMs = trueMs() + seekReadyMs
        if (playWhenReady) audibleFromMs = readyAtMs + startLatencyMs
    }

    override fun setSpeed(speed: Float) {
        val now = trueMs()
        if (playWhenReady && now > audibleFromMs) {
            basePos = truePositionMs()
            audibleFromMs = now
        }
        this.speed = speed
    }
}

class SimLocalPlayback(
    private val player: SimulatedPlayer,
    override val follower: PlaybackFollower,
    private val loadDelayMs: Long,
) : LocalPlayback {
    override val durationMs: Long get() = player.durationMs
    override suspend fun resolve(track: Track, allowAlternatives: Boolean) = ResolvedTrack(track, "sim://${track.key}")
    override suspend fun prepare(resolved: ResolvedTrack) {
        delay(loadDelayMs)
        player.load(resolved.track.key)
        follower.onPlayerChanged()
    }
}

/** One-way delay: symmetric base + exponential jitter + occasional radio spikes. */
class LatencyModel(
    val baseMs: Long = 8,
    val jitterMeanMs: Double = 8.0,
    val spikeChance: Double = 0.05,
    val spikeMaxMs: Long = 80,
) {
    fun sample(random: Random): Long {
        val jitter = -jitterMeanMs * ln(1 - random.nextDouble())
        val spike = if (random.nextDouble() < spikeChance) random.nextLong(20, spikeMaxMs) else 0
        return baseMs + jitter.roundToLong() + spike
    }
}

class FakeNetwork(private val scope: TestScope, private val random: Random, private val latency: LatencyModel) {
    private val endpoints = HashMap<String, Endpoint>()
    private val links = HashSet<Pair<String, String>>()
    private val lastArrival = HashMap<Pair<String, String>, Long>()

    fun endpoint(id: String, clock: MonotonicClock) = Endpoint(id, clock).also { endpoints[id] = it }

    fun connect(a: String, b: String) {
        links += a to b
        links += b to a
        endpoints.getValue(a).events.tryEmit(TransportEvent.Connected(b, b))
        endpoints.getValue(b).events.tryEmit(TransportEvent.Connected(a, a))
    }

    fun cut(a: String, b: String) {
        if (links.remove(a to b) or links.remove(b to a)) {
            endpoints.getValue(a).events.tryEmit(TransportEvent.Disconnected(b))
            endpoints.getValue(b).events.tryEmit(TransportEvent.Disconnected(a))
        }
    }

    private fun deliver(from: String, to: String, bytes: ByteArray) {
        if ((from to to) !in links) return
        val now = scope.testScheduler.currentTime
        val arrival = max(now + latency.sample(random), lastArrival[from to to] ?: 0) // in order, like Nearby
        lastArrival[from to to] = arrival
        scope.backgroundScope.launch {
            delay(arrival - now)
            if ((from to to) in links) {
                val dst = endpoints.getValue(to)
                dst.events.emit(TransportEvent.Received(from, bytes, dst.clock.nowNs()))
            }
        }
    }

    inner class Endpoint(val id: String, val clock: MonotonicClock) : Transport {
        override val events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 4096)
        override fun send(endpointId: String, bytes: ByteArray) = deliver(id, endpointId, bytes)
        override fun send(endpointIds: Collection<String>, bytes: ByteArray) = endpointIds.forEach { deliver(id, it, bytes) }
        override fun disconnect(endpointId: String) = cut(id, endpointId)
    }
}

/** A simulated phone: clock, audio hardware, follower and network endpoint. */
class SimPhone(
    val name: String,
    scope: TestScope,
    network: FakeNetwork,
    random: Random,
    config: SyncConfig,
    clockOffsetMs: Long = random.nextLong(-200, 200),
    clockPpm: Double = random.nextDouble(-50.0, 50.0),
    startLatencyMs: Long = random.nextLong(40, 150),
    audioPpm: Double = random.nextDouble(-30.0, 30.0),
    loadDelayMs: Long = random.nextLong(300, 2_500),
) {
    private val trueMs = { scope.testScheduler.currentTime }
    val clock = DeviceClock(trueMs, clockOffsetMs * 1_000_000, clockPpm)
    val player = SimulatedPlayer(trueMs, startLatencyMs, audioPpm, seekReadyMs = random.nextLong(20, 80)) {
        random.nextDouble(-1.5, 1.5)
    }
    val latency = InMemoryLatencyProfile()
    val follower = PlaybackFollower(player, clock, VirtualScheduler(scope.backgroundScope, clock), config, latency)
    val local = SimLocalPlayback(player, follower, loadDelayMs)
    val endpoint = network.endpoint(name, clock)

    init {
        val phase = random.nextLong(0, config.tickMs)
        scope.backgroundScope.launch {
            delay(phase) // phones do not tick in lockstep
            while (isActive) {
                follower.tick()
                delay(config.tickMs)
            }
        }
    }
}
