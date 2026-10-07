package com.songsync.app.sync

import kotlin.math.abs

/**
 * Decides how to correct the gap between where this phone *is* and where the shared timeline
 * says it *should be*. Errors are in milliseconds; positive means this phone is ahead.
 *
 * Small errors are ignored, medium ones are bled off with an inaudible playback-speed nudge,
 * and large ones (after a rebuffer or an interruption) are fixed with a seek.
 */
class DriftController(private val config: SyncConfig) {

    sealed interface Decision {
        /** Not enough fresh samples yet. */
        data object Wait : Decision
        data class Speed(val speed: Float) : Decision
        data object HardResync : Decision
    }

    private val window = ArrayDeque<Double>()
    private var correcting = false

    fun reset() {
        window.clear()
        correcting = false
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

    fun decide(): Decision {
        if (window.size < config.minSamplesForDecision) return Decision.Wait
        val error = medianErrorMs() ?: return Decision.Wait
        val magnitude = abs(error)
        if (magnitude > config.hardResyncMs) return Decision.HardResync

        correcting = if (correcting) magnitude > config.correctionDoneMs else magnitude > config.deadbandMs
        if (!correcting) return Decision.Speed(1f)

        // Ahead (positive error) -> slow down; behind -> speed up.
        val nudge = (-error / config.correctionHorizonMs).toFloat()
            .coerceIn(-config.maxSpeedNudge, config.maxSpeedNudge)
        return Decision.Speed(1f + nudge)
    }
}
