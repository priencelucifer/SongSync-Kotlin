package com.songsync.app.calibration

import com.songsync.app.sync.NANOS_PER_MS
import com.songsync.app.sync.PlaybackState

/**
 * A microphone recording with the local (host) monotonic time of its first sample. [source] and
 * [timestamped] (audio timestamps vs read timing) describe how it was made, for the sync report.
 */
class Recording(
    val samples: ShortArray,
    val size: Int,
    val sampleRate: Int,
    val startTimeNs: Long,
    val source: String = "",
    val timestamped: Boolean = true,
)

/**
 * Turns the host's recording of a calibration run into per-phone corrections.
 *
 * Every chirp's expected arrival is known from the shared timeline. The microphone path adds an
 * unknown but constant delay, measured from the host's own chirps (slot 0); after removing it,
 * whatever is left for each phone is how early or late its speaker really is.
 */
object CalibrationAnalyzer {

    data class PhoneResult(
        val slot: Int,
        /** How late this phone's sound arrived relative to the timeline (ms), or null if not heard reliably. */
        val lateMs: Double?,
        val chirpsHeard: Int,
    )

    data class Result(val phones: List<PhoneResult>) {
        /**
         * Correction to add to each phone's calibration (positive = play earlier), relative to
         * the group's median so everyone moves as little as possible. Unreliable phones are absent.
         */
        fun corrections(maxMs: Double = 300.0): Map<Int, Double> {
            val heard = phones.filter { it.lateMs != null }
            if (heard.isEmpty()) return emptyMap()
            val sorted = heard.map { it.lateMs!! }.sorted()
            val reference = if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
            return heard.associate { it.slot to (it.lateMs!! - reference).coerceIn(-maxMs, maxMs) }
        }

        /**
         * For "Check sync": how late each phone is heard compared with the host (slot 0), in ms,
         * positive = late. Null for phones not heard reliably; empty if the host itself was not heard.
         */
        fun latenessVsHost(): Map<Int, Double?> {
            val host = phones.firstOrNull { it.slot == 0 }?.lateMs ?: return emptyMap()
            return phones.associate { it.slot to it.lateMs?.minus(host) }
        }
    }

    private const val MIN_SNR = 6.0
    private const val MIN_CHIRPS = 3
    private const val MAX_SPREAD_MS = 5.0
    private const val COARSE_WINDOW_MS = 600.0
    private const val FINE_WINDOW_MS = 150.0

    /** [timeline] must be the playing state the calibration track ran on (host clock). */
    fun analyze(recording: Recording, timeline: PlaybackState, slots: Int): Result {
        val detector = ChirpDetector(CalibrationSignal.chirp())
        val sr = recording.sampleRate
        fun expectedSample(positionMs: Long): Double {
            val hostNs = timeline.anchorHostNs + (positionMs - timeline.anchorPositionMs) * NANOS_PER_MS
            return (hostNs - recording.startTimeNs).toDouble() * sr / 1e9
        }
        fun msToSamples(ms: Double) = (ms * sr / 1_000).toInt()

        // 1. Microphone-path delay: find the host's first chirp (nothing precedes it but silence).
        val first = expectedSample(CalibrationSignal.chirpStartMs(0, 0))
        val coarse = detector.find(
            recording.samples, recording.size,
            (first - msToSamples(COARSE_WINDOW_MS)).toInt(), (first + msToSamples(COARSE_WINDOW_MS)).toInt(),
        )?.takeIf { it.snr >= MIN_SNR } ?: return Result((0 until slots).map { PhoneResult(it, null, 0) })
        var micDelay = coarse.sample - first

        fun measure(slot: Int): List<Double> = (0 until CalibrationSignal.CHIRPS_PER_SLOT).mapNotNull { index ->
            val expected = expectedSample(CalibrationSignal.chirpStartMs(slot, index)) + micDelay
            detector.find(
                recording.samples, recording.size,
                (expected - msToSamples(FINE_WINDOW_MS)).toInt(), (expected + msToSamples(FINE_WINDOW_MS)).toInt(),
            )?.takeIf { it.snr >= MIN_SNR }?.let { (it.sample - expected) * 1_000 / sr }
        }

        // 2. Refine the microphone delay with all of the host's chirps.
        measure(0).takeIf { it.isNotEmpty() }?.let { micDelay += median(it) * sr / 1_000 }

        // 3. Each phone's residual lateness.
        val phones = (0 until slots).map { slot ->
            val errors = measure(slot)
            val reliable = errors.size >= MIN_CHIRPS && errors.max() - errors.min() <= MAX_SPREAD_MS
            PhoneResult(slot, if (reliable) median(errors) else null, errors.size)
        }
        return Result(phones)
    }

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
