package com.songsync.app.sync

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
    /** How far media has been downloaded (track position). */
    val bufferedPositionMs: Long
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

/** Notable things the follower did, for the diagnostics log. */
data class SyncEvent(val atNs: Long, val type: Type, val value: Double = 0.0) {
    enum class Type {
        /** Scheduled start; value = start latency used (ms). */
        START,
        /** First settled measurement after a start; value = error (ms). */
        START_ERROR,
        /** value = error that triggered it (ms). */
        HARD_RESYNC,
        /** Playback stalled waiting for data. */
        REBUFFER,
        /** Waiting for the download to get ahead of the timeline before restarting. */
        CATCHING_UP,
        /** value = new speed. */
        SPEED,
        /** value = jump (ms). */
        OFFSET_JUMP,
        INTERRUPTED,
    }
}

/**
 * Keeps the local player on the shared [PlaybackState] timeline.
 *
 * Starts, resumes and re-syncs are all scheduled for a moment slightly in the future:
 * the player is paused, pre-positioned, and `play()` is issued early by the learned start
 * latency so that audio emerges exactly on time. Once playing, the position is compared with
 * the timeline every tick and drift is corrected with small, infrequent speed nudges
 * ([DriftController]). Re-syncs (which pause briefly) are a last resort with back-off.
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

    /** Receives [SyncEvent]s; optional. */
    var onEvent: ((SyncEvent) -> Unit)? = null

    /**
     * While echo calibration measures the phones, timing must not move: no speed nudges and no
     * re-syncs (errors are still measured). Speed returns to normal when frozen.
     */
    var correctionsFrozen: Boolean = false
        set(value) {
            if (field == value) return
            field = value
            if (value) resetSpeed()
        }

    /**
     * Whether starts of a track teach the learned start latency. The echo-calibration track (a
     * local 48 kHz mono WAV) starts on a different audio path than streamed songs; learning from
     * it put the first song start after a calibration tens of ms off for several seconds.
     */
    var learnsFromTrack: (trackKey: String?) -> Boolean = { true }

    private var offsetTargetNs: Long? = null
    private var appliedOffsetNs = 0L
    private var hold = false

    private val drift = DriftController(config)
    private var scheduled: Cancellable? = null
    private var deferredEvaluation: Cancellable? = null
    private var armedSeq = NONE
    private var settleUntilNs = 0L
    private val settleSamples = ArrayList<Double>()
    private var learnFromStart = false
    private var lastErrorMs: Double? = null
    private var hardResyncs = 0

    private var lastStartNs = 0L
    private var lastSpeedChangeNs = Long.MIN_VALUE / 2
    private var consecutiveResyncs = 0
    private var nextResyncAllowedNs = 0L
    private var calmSinceNs: Long? = null

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
        val jump = offsetNs - appliedOffsetNs
        val bigJump = abs(jump) > config.offsetSlewThresholdMs * NANOS_PER_MS
        if (first || bigJump || !audible) {
            if (!first && audible) emit(SyncEvent.Type.OFFSET_JUMP, jump.toDouble() / NANOS_PER_MS)
            appliedOffsetNs = offsetNs
            armedSeq = NONE // any scheduled instant was computed with the old offset
            evaluate()
        }
        // While audio is playing, smaller changes are slewed in by tick() so nothing jumps.
    }

    fun updateState(newState: PlaybackState) {
        if (newState.seq < state.seq || newState == state) return
        val previous = state
        state = newState
        val seamless = (phase == FollowerPhase.LOCKED || phase == FollowerPhase.SETTLING) &&
            newState.isContinuationOf(previous, hostNow())
        if (seamless) {
            if (armedSeq == previous.seq) armedSeq = newState.seq // same motion: keep playing as is
            return
        }
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

    /**
     * Call when the player's readiness or playing state changes. Players (ExoPlayer included)
     * report changes synchronously from inside play()/pause(), so re-evaluating right away would
     * re-enter the follower mid-decision; the evaluation is deferred and coalesced instead.
     */
    fun onPlayerChanged() {
        if (deferredEvaluation != null) return
        deferredEvaluation = scheduler.schedule(clock.nowNs()) {
            deferredEvaluation = null
            evaluate()
        }
    }

    /** Call every [SyncConfig.tickMs]. */
    fun tick() {
        slewOffset()
        evaluate()
        measure()
    }

    /** Forget the session (timeline and clock); used when leaving a group. */
    fun reset() {
        deferredEvaluation?.cancel()
        deferredEvaluation = null
        stopAll(FollowerPhase.IDLE)
        state = PlaybackState.Idle
        offsetTargetNs = null
        appliedOffsetNs = 0
        lastErrorMs = null
        hardResyncs = 0
        consecutiveResyncs = 0
        nextResyncAllowedNs = 0
        calmSinceNs = null
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
                if (phase != FollowerPhase.INTERRUPTED) emit(SyncEvent.Type.INTERRUPTED)
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
                // Still on the timeline it started for: the closed loop in measure() owns it. A
                // new timeline (a seek while playing, a big clock jump) re-arms right away instead
                // of playing the old position until a re-sync, which back-off can delay for long.
                if (player.isPlaying && armedSeq == s.seq) return
                if (!player.isReady) {
                    // Rebuffering: stop ExoPlayer from resuming at a stale position on its own.
                    emit(SyncEvent.Type.REBUFFER)
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

        val position = player.positionMs
        val buffered = player.bufferedPositionMs
        when {
            target < position && position - target <= config.maxWaitAheadMs -> {
                // Ahead of the timeline: seeking back would throw the buffer away. Wait for the
                // timeline to reach this position and start from here instead.
                audibleAtHost += (position - target) * NANOS_PER_MS
                target = position
            }
            target > position && target + config.startHeadroomMs > buffered -> {
                // The audio at the target is not downloaded yet; seeking there would stall again
                // and chase the moving timeline forever.
                if (phase != FollowerPhase.BUFFERING) emit(SyncEvent.Type.CATCHING_UP)
                armedSeq = NONE
                phase = FollowerPhase.BUFFERING
                if (target - buffered > config.catchUpWindowMs) {
                    // Too far for the download to catch up from here (e.g. joining mid-song):
                    // jump a little past the timeline and let it arrive (handled by the branch above).
                    player.seekTo(s.positionAt(hostNowNs + config.leapLeadMs * NANOS_PER_MS) + latency.calibrationMs.toLong())
                }
                // Otherwise stay paused: the download continues and the next tick starts once it is ahead.
                return
            }
        }
        if (abs(position - target) > config.positionToleranceMs) player.seekTo(target)

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
        learnFromStart = abs(player.positionMs - target) <= config.positionToleranceMs && learnsFromTrack(state.trackKey)
        emit(SyncEvent.Type.START, latency.startLatencyMs)
        player.play()
        lastStartNs = clock.nowNs()
        settleUntilNs = lastStartNs + config.settleMs * NANOS_PER_MS
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
            if (settleSamples.size < config.settleSamples) return
            val startError = settleSamples.sorted()[settleSamples.size / 2]
            if (learnFromStart) learnStartLatency(startError)
            learnFromStart = false
            lastErrorMs = startError
            emit(SyncEvent.Type.START_ERROR, startError)
            drift.reset()
            phase = FollowerPhase.LOCKED
            return
        }

        drift.addSample(error)
        val median = drift.medianErrorMs()
        lastErrorMs = median
        trackCalm(median, nowLocal)
        if (correctionsFrozen) return
        when (val decision = drift.decide(nowLocal, player.speed)) {
            DriftController.Decision.Wait -> Unit
            is DriftController.Decision.Speed -> applySpeed(decision.speed)
            is DriftController.Decision.HardResync ->
                if (nowLocal >= nextResyncAllowedNs && nowLocal - lastStartNs >= config.minLockBeforeResyncMs * NANOS_PER_MS) {
                    hardResync(median ?: 0.0, nowLocal)
                } else {
                    applySpeed(decision.fallback.speed) // shrink the gap meanwhile
                }
        }
    }

    /** Ahead by e ms means play() was issued e ms too early: the latency estimate is too high. */
    private fun learnStartLatency(errorMs: Double) {
        val step = (config.latencyLearningRate * errorMs)
            .coerceIn(-config.maxLearningStepMs, config.maxLearningStepMs)
        latency.startLatencyMs = (latency.startLatencyMs - step).coerceIn(0.0, config.maxLearnedLatencyMs)
    }

    private fun hardResync(errorMs: Double, nowNs: Long) {
        hardResyncs++
        consecutiveResyncs++
        val backoff = min(config.resyncBackoffMaxMs, config.resyncBackoffBaseMs shl (consecutiveResyncs - 1).coerceAtMost(10))
        nextResyncAllowedNs = nowNs + backoff * NANOS_PER_MS
        calmSinceNs = null
        emit(SyncEvent.Type.HARD_RESYNC, errorMs)
        arm(state)
    }

    private fun trackCalm(median: Double?, nowNs: Long) {
        if (median != null && abs(median) <= config.deadbandMs) {
            val since = calmSinceNs ?: nowNs.also { calmSinceNs = it }
            if (nowNs - since >= config.resyncCalmResetMs * NANOS_PER_MS) consecutiveResyncs = 0
        } else {
            calmSinceNs = null
        }
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

    /** Speed changes are rationed: each one can cost a tiny glitch on some audio paths. */
    private fun applySpeed(speed: Float) {
        if (abs(speed - player.speed) < config.speedStep / 2) return
        val now = clock.nowNs()
        if (now - lastSpeedChangeNs < config.minSpeedDwellMs * NANOS_PER_MS) return
        player.setSpeed(speed)
        lastSpeedChangeNs = now
        emit(SyncEvent.Type.SPEED, speed.toDouble())
    }

    private fun resetSpeed() {
        if (player.speed != 1f) {
            player.setSpeed(1f)
            lastSpeedChangeNs = clock.nowNs()
        }
    }

    private fun cancelScheduled() {
        scheduled?.cancel()
        scheduled = null
    }

    private fun emit(type: SyncEvent.Type, value: Double = 0.0) {
        onEvent?.invoke(SyncEvent(clock.nowNs(), type, value))
    }

    private fun hostNow() = clock.nowNs() + appliedOffsetNs
    private fun localTimeOf(hostNs: Long) = hostNs - appliedOffsetNs

    private companion object {
        const val NONE = -1L
    }
}
