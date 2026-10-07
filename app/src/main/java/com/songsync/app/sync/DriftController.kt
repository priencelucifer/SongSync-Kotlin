package com.songsync.app.sync

import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Decides how to correct the gap between where this phone *is* and where the shared timeline
 * says it *should be*. Errors are in milliseconds; positive means this phone is ahead.
 *
 * Small errors are ignored, medium ones are bled off with an inaudible playback-speed nudge
 * (quantised, so measurement noise does not cause constant changes), and only errors that stay
 * large for a while ask for a re-sync.
 */
class DriftController(private val config: SyncConfig) {

    sealed interface Decision {
        /** Not enough fresh samples yet. */
        data object Wait : Decision
        data class Speed(val speed: Float) : Decision
        /** The error has been large for [SyncConfig.hardResyncSustainMs]; [fallback] if a re-sync is not allowed now. */
        data class HardResync(val fallback: Speed) : Decision
    }

    private val window = ArrayDeque<Double>()
    private var correcting = false
    private var largeSinceNs: Long? = null

    fun reset() {
        window.clear()
        correcting = false
        largeSinceNs = null
    }

    fun addSample(errorMs: Double) {
        window.addLast(errorMs)
        while (window.size > config.windowSamples) window.removeFirst()
    }

    fun medianErrorMs(): Double? {
        if (window.isEmpty()) return null
        val sorted = window.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2
    }

    fun decide(nowNs: Long): Decision {
        if (window.size < config.minSamplesForDecision) return Decision.Wait
        val error = medianErrorMs() ?: return Decision.Wait
        val magnitude = abs(error)

        if (magnitude > config.hardResyncMs) {
            val since = largeSinceNs ?: nowNs.also { largeSinceNs = it }
            val strongest = Decision.Speed(1f - error.sign.toFloat() * config.maxSpeedNudge)
            correcting = true
            return if (nowNs - since >= config.hardResyncSustainMs * NANOS_PER_MS) Decision.HardResync(strongest) else strongest
        }
        largeSinceNs = null

        correcting = if (correcting) magnitude > config.correctionDoneMs else magnitude > config.deadbandMs
        if (!correcting) return Decision.Speed(1f)

        // Ahead (positive error) -> slow down; behind -> speed up. Never round a needed nudge to 0.
        val limit = if (magnitude < config.smallErrorMs) config.smallErrorMaxNudge else config.maxSpeedNudge
        val raw = (-error / config.correctionHorizonMs).toFloat().coerceIn(-limit, limit)
        val steps = (raw / config.speedStep).roundToInt().let { if (it == 0) raw.sign.toInt() else it }
        return Decision.Speed(1f + (steps * config.speedStep).coerceIn(-limit, limit))
    }
}
