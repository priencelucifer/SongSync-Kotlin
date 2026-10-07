package com.songsync.app.sync

import kotlin.math.abs
import kotlin.math.max

/** The follower's view of the local audio player. Implemented over ExoPlayer on Android. */
interface SyncPlayer {
    /** Key of the track that is loaded into the player, or null. */
    val loadedTrackKey: String?
    /** Enough media is buffered to start immediately. */
    val isReady: Boolean
    /** Audio is actually advancing (not paused, not buffering, not suppressed). */
    val isPlaying: Boolean
    /** Temporarily silenced by the system (e.g. a phone call); leave the player alone. */
    val isInterrupted: Boolean
    /** Position of the audio currently being heard. */
    val positionMs: Long
    val speed: Float
    fun play()
    fun pause()
    fun seekTo(positionMs: Long)
    fun setSpeed(speed: Float)
}

/** What this phone knows about its own audio output. Implementations may persist changes. */
interface LatencyProfile {
    /** Learned time from play() until audio is heard. */
    var startLatencyMs: Double
    /** Manual echo calibration; positive makes this phone play earlier. */
    val calibrationMs: Double
}

class InMemoryLatencyProfile(
    override var startLatencyMs: Double = DEFAULT_START_LATENCY_MS,
    override var calibrationMs: Double = 0.0,
) : LatencyProfile

const val DEFAULT_START_LATENCY_MS = 80.0

enum class FollowerPhase {
    IDLE, WAITING_FOR_TRACK, WAITING_FOR_CLOCK, ON_HOLD, INTERRUPTED,
    PAUSED, PAUSE_PENDING, ARMED, BUFFERING, SETTLING, LOCKED,
}

data class FollowerStatus(
    val phase: FollowerPhase,
    /** Median error against the shared timeline (positive = ahead), when measured. */
    val errorMs: Double?,
    val speed: Float,
    val offsetMs: Double?,
    val startLatencyMs: Double,
    val hardResyncs: Int,
)

/**
 * Keeps the local player on the shared [PlaybackState] timeline.
 *
 * Starts, resumes and re-syncs are all scheduled for a moment slightly in the future:
 * the player is paused, pre-positioned, and `play()` is issued early by the learned start
 * latency so that audio emerges exactly on time. Once playing, the position is compared with
 * the timeline every tick and small drifts are corrected with speed nudges ([DriftController]).
 *
 * Not thread-safe: call everything from the player's thread.
 */
class PlaybackFollower(
    private val player: SyncPlayer,
    private val clock: MonotonicClock,
    private val scheduler: Scheduler,
    private val config: SyncConfig = SyncConfig(),
    var latency: LatencyProfile = InMemoryLatencyProfile(),
) {
    var state: PlaybackState = PlaybackState.Idle
        private set
    var phase: FollowerPhase = FollowerPhase.IDLE
        private set

    private var offsetTargetNs: Long? = null
    private var appliedOffsetNs = 0L
    private var hold = false

    private val drift = DriftController(config)
    private var scheduled: Cancellable? = null
    private var armedSeq = NONE
    private var settleUntilNs = 0L
    private val settleSamples = ArrayList<Double>(SETTLE_SAMPLES)
    private var learnFromStart = false
    private var lastErrorMs: Double? = null
    private var hardResyncs = 0

    val status: FollowerStatus
        get() = FollowerStatus(
            phase = phase,
            errorMs = lastErrorMs?.takeIf { phase == FollowerPhase.LOCKED },
            speed = player.speed,
            offsetMs = offsetTargetNs?.let { it.toDouble() / NANOS_PER_MS },
            startLatencyMs = latency.startLatencyMs,
            hardResyncs = hardResyncs,
        )

    /** Offset to the host clock (hostTime = localTime + offset). The host itself passes 0. */
    fun setClockOffset(offsetNs: Long) {
        val first = offsetTargetNs == null
        offsetTargetNs = offsetNs
        if (offsetNs == appliedOffsetNs && !first) return
        val audible = phase == FollowerPhase.LOCKED || phase == FollowerPhase.SETTLING ||
            phase == FollowerPhase.PAUSE_PENDING
        val bigJump = abs(offsetNs - appliedOffsetNs) > config.offsetSlewThresholdMs * NANOS_PER_MS
        if (first || bigJump || !audible) {
            appliedOffsetNs = offsetNs
            armedSeq = NONE // any scheduled instant was computed with the old offset
            evaluate()
        }
        // While audio is playing, small changes are slewed in by tick() so nothing jumps.
    }

    fun updateState(newState: PlaybackState) {
        if (newState.seq < state.seq || newState == state) return
        val previous = state
        state = newState
        val seamless = (phase == FollowerPhase.LOCKED || phase == FollowerPhase.SETTLING) &&
            newState.isContinuationOf(previous, hostNow())
        if (seamless) return
        armedSeq = NONE
        evaluate()
    }

    /** Pause this phone only (user request or permanent audio-focus loss). */
    fun setHold(onHold: Boolean) {
        if (hold == onHold) return
        hold = onHold
        armedSeq = NONE
        evaluate()
    }

    /** Call when the player's readiness or playing state changes. */
    fun onPlayerChanged() = evaluate()

    /** Call every [SyncConfig.tickMs]. */
    fun tick() {
        slewOffset()
        evaluate()
        measure()
    }

    fun release() {
        cancelScheduled()
    }

    // --- decisions ---------------------------------------------------------------------------

    private fun evaluate() {
        val s = state
        when {
            s.trackKey == null -> stopAll(FollowerPhase.IDLE)
            player.loadedTrackKey != s.trackKey -> stopAll(FollowerPhase.WAITING_FOR_TRACK)
            offsetTargetNs == null -> stopAll(FollowerPhase.WAITING_FOR_CLOCK)
            hold -> stopAll(FollowerPhase.ON_HOLD)
            player.isInterrupted -> {
                // The system silenced us; touching the player would fight the audio-focus logic.
                cancelScheduled()
                drift.reset()
                phase = FollowerPhase.INTERRUPTED
            }
            !s.playing -> evaluatePaused(s)
            else -> evaluatePlaying(s)
        }
    }

    private fun evaluatePaused(s: PlaybackState) {
        val tracking = phase == FollowerPhase.LOCKED || phase == FollowerPhase.SETTLING ||
            phase == FollowerPhase.PAUSE_PENDING
        if (hostNow() < s.anchorHostNs && player.isPlaying && tracking) {
            // Keep playing until the shared pause instant so every phone stops on the same sample.
            if (armedSeq != s.seq) {
                cancelScheduled()
                armedSeq = s.seq
                scheduled = scheduler.schedule(localTimeOf(s.anchorHostNs)) {
                    scheduled = null
                    if (state.seq == s.seq) settlePaused(s) else evaluate()
                }
            }
            phase = FollowerPhase.PAUSE_PENDING
            return
        }
        settlePaused(s)
    }

    private fun settlePaused(s: PlaybackState) {
        cancelScheduled()
        player.pause()
        resetSpeed()
        drift.reset()
        if (abs(player.positionMs - s.anchorPositionMs) > config.positionToleranceMs) {
            player.seekTo(s.anchorPositionMs)
        }
        phase = FollowerPhase.PAUSED
    }

    private fun evaluatePlaying(s: PlaybackState) {
        when (phase) {
            FollowerPhase.LOCKED, FollowerPhase.SETTLING -> {
                if (player.isPlaying) return // the closed loop in measure() owns it
                if (!player.isReady) {
                    // Rebuffering: stop ExoPlayer from resuming at a stale position on its own.
                    player.pause()
                    resetSpeed()
                    drift.reset()
                    phase = FollowerPhase.BUFFERING
                    return
                }
            }
            FollowerPhase.ARMED -> if (armedSeq == s.seq) return
            FollowerPhase.BUFFERING -> if (!player.isReady) return
            else -> Unit
        }
        arm(s)
    }

    /** Pause, pre-position, and schedule `play()` so audio emerges exactly on the timeline. */
    private fun arm(s: PlaybackState) {
        cancelScheduled()
        resetSpeed()
        drift.reset()
        player.pause()

        val hostNowNs = hostNow()
        val startLatencyNs = (latency.startLatencyMs * NANOS_PER_MS).toLong()
        val earliestAudible = hostNowNs + startLatencyNs + config.minPrepareMs * NANOS_PER_MS
        var audibleAtHost = if (s.anchorHostNs >= earliestAudible) {
            s.anchorHostNs
        } else {
            hostNowNs + max(config.joinLeadMs * NANOS_PER_MS, startLatencyNs + config.minPrepareMs * NANOS_PER_MS)
        }
        var target = s.positionAt(audibleAtHost) + latency.calibrationMs.toLong()
        if (target < 0) {
            // Negative calibration at the start of a track: start later instead of before zero.
            audibleAtHost += -target * NANOS_PER_MS
            target = 0
        }
        if (abs(player.positionMs - target) > config.positionToleranceMs) player.seekTo(target)

        armedSeq = s.seq
        phase = FollowerPhase.ARMED
        val seq = s.seq
        scheduled = scheduler.schedule(localTimeOf(audibleAtHost) - startLatencyNs) {
            scheduled = null
            onArmedStart(seq, target)
        }
    }

    private fun onArmedStart(seq: Long, target: Long) {
        if (state.seq != seq || phase != FollowerPhase.ARMED) return
        if (!player.isReady) {
            phase = FollowerPhase.BUFFERING // re-armed once data arrives
            return
        }
        // Only a start from exactly the prepared position says anything about start latency.
        learnFromStart = abs(player.positionMs - target) <= config.positionToleranceMs
        player.play()
        settleUntilNs = clock.nowNs() + config.settleMs * NANOS_PER_MS
        settleSamples.clear()
        phase = FollowerPhase.SETTLING
    }

    // --- closed loop -------------------------------------------------------------------------

    private fun measure() {
        if (phase != FollowerPhase.SETTLING && phase != FollowerPhase.LOCKED) return
        if (!player.isPlaying) return
        val nowLocal = clock.nowNs()
        if (nowLocal < settleUntilNs) return

        val expected = state.positionAt(nowLocal + appliedOffsetNs) + latency.calibrationMs
        val error = player.positionMs - expected

        if (phase == FollowerPhase.SETTLING) {
            settleSamples += error
            if (settleSamples.size < SETTLE_SAMPLES) return
            val startError = settleSamples.sorted()[SETTLE_SAMPLES / 2]
            if (learnFromStart) learnStartLatency(startError)
            learnFromStart = false
            lastErrorMs = startError
            drift.reset()
            phase = FollowerPhase.LOCKED
            if (abs(startError) > config.hardResyncMs) hardResync()
            return
        }

        drift.addSample(error)
        lastErrorMs = drift.medianErrorMs()
        when (val decision = drift.decide()) {
            DriftController.Decision.Wait -> Unit
            is DriftController.Decision.Speed -> applySpeed(decision.speed)
            DriftController.Decision.HardResync -> hardResync()
        }
    }

    /** Ahead by e ms means play() was issued e ms too early: the latency estimate is too high. */
    private fun learnStartLatency(errorMs: Double) {
        val step = (config.latencyLearningRate * errorMs)
            .coerceIn(-config.maxLearningStepMs, config.maxLearningStepMs)
        latency.startLatencyMs = (latency.startLatencyMs - step).coerceIn(0.0, config.maxLearnedLatencyMs)
    }

    private fun hardResync() {
        hardResyncs++
        arm(state)
    }

    // --- helpers -----------------------------------------------------------------------------

    private fun slewOffset() {
        val target = offsetTargetNs ?: return
        val diff = target - appliedOffsetNs
        if (diff != 0L) appliedOffsetNs += diff.coerceIn(-config.offsetSlewPerTickNs, config.offsetSlewPerTickNs)
    }

    private fun stopAll(newPhase: FollowerPhase) {
        cancelScheduled()
        player.pause()
        resetSpeed()
        drift.reset()
        armedSeq = NONE
        phase = newPhase
    }

    private fun applySpeed(speed: Float) {
        val current = player.speed
        if (abs(speed - current) >= config.minSpeedStep || (speed == 1f && current != 1f)) {
            player.setSpeed(speed)
        }
    }

    private fun resetSpeed() {
        if (player.speed != 1f) player.setSpeed(1f)
    }

    private fun cancelScheduled() {
        scheduled?.cancel()
        scheduled = null
    }

    private fun hostNow() = clock.nowNs() + appliedOffsetNs
    private fun localTimeOf(hostNs: Long) = hostNs - appliedOffsetNs

    private companion object {
        const val NONE = -1L
        const val SETTLE_SAMPLES = 3
    }
}
