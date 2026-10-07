package com.songsync.app.calibration

import com.songsync.app.sync.NANOS_PER_MS
import com.songsync.app.sync.PlaybackState
import kotlin.math.abs

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

    /** Two calibration measurements of one phone must agree this closely (ms) to be applied. */
    const val AGREEMENT_MS = 4.0

    /**
     * Combines the corrections of two runs: phones measured in both, within [toleranceMs], get
     * the average (first map); phones measured in both but further apart get both values (second
     * map) and no correction. Phones heard in only one run are in neither.
     */
    fun agree(
        first: Map<Int, Double>,
        second: Map<Int, Double>,
        toleranceMs: Double = AGREEMENT_MS,
    ): Pair<Map<Int, Double>, Map<Int, Pair<Double, Double>>> {
        val both = first.keys.intersect(second.keys).sorted()
        val (steady, unsteady) = both.partition { abs(first.getValue(it) - second.getValue(it)) <= toleranceMs }
        return steady.associateWith { (first.getValue(it) + second.getValue(it)) / 2 } to
            unsteady.associateWith { first.getValue(it) to second.getValue(it) }
    }

    private const val MIN_SNR = 6.0
    private const val MIN_CHIRPS = 3
    private const val MAX_SPREAD_MS = 5.0
    /**
     * How far from its expected place a phone's chirp pattern is searched. Covers the unknown
     * microphone latency and Bluetooth outputs, and stays below half a slot so a neighbouring
     * phone's (identical) pattern can never be taken for this one.
     */
    private const val PATTERN_SEARCH_MS = 800.0
    /** Tolerance per chirp when lining up the pattern (sample clocks differ by up to ~100 ppm). */
    private const val PATTERN_POOL_MS = 0.2
    /** Window for the precise first-arrival timing of each chirp around the aligned pattern. */
    private const val FINE_WINDOW_MS = 40.0

    /** [timeline] must be the playing state the calibration track ran on (host clock). */
    fun analyze(recording: Recording, timeline: PlaybackState, slots: Int): Result {
        val detector = ChirpDetector(CalibrationSignal.chirp())
        val sr = recording.sampleRate
        fun expectedSample(positionMs: Long): Double {
            val hostNs = timeline.anchorHostNs + (positionMs - timeline.anchorPositionMs) * NANOS_PER_MS
            return (hostNs - recording.startTimeNs).toDouble() * sr / 1e9
        }
        fun msToSamples(ms: Double) = (ms * sr / 1_000).toInt()
        fun expectedChirps(slot: Int) = DoubleArray(CalibrationSignal.CHIRPS_PER_SLOT) {
            expectedSample(CalibrationSignal.chirpStartMs(slot, it))
        }

        /**
         * Shift (samples) at which all of a slot's chirps line up best, searched around [center].
         * Matching the whole uneven pattern at once is what makes it unambiguous.
         */
        fun alignPattern(expected: DoubleArray, center: Int): Int? {
            val search = msToSamples(PATTERN_SEARCH_MS)
            val pool = msToSamples(PATTERN_POOL_MS).coerceAtLeast(1)
            val (start, env) = detector.envelope(
                recording.samples, recording.size,
                (expected.first() + center - search - pool).toInt(), (expected.last() + center + search + pool + 1).toInt(),
            ) ?: return null
            val noise = env.copyOf().also { it.sort() }[env.size / 2].coerceAtLeast(1e-9)
            val pooled = DoubleArray(env.size) { i ->
                var m = 0.0
                for (j in maxOf(0, i - pool)..minOf(env.size - 1, i + pool)) m = maxOf(m, env[j])
                m / noise
            }
            var bestShift: Int? = null
            var bestScore = 0.0
            for (shift in center - search..center + search) {
                var score = 0.0
                for (e in expected) {
                    val i = (e + shift).toInt() - start
                    if (i in pooled.indices) score += minOf(pooled[i], PATTERN_SCORE_CAP)
                }
                if (score > bestScore) {
                    bestScore = score
                    bestShift = shift
                }
            }
            return bestShift
        }

        /** Precise arrival errors (ms vs. expected) of the chirps found near the aligned pattern. */
        fun measure(expected: DoubleArray, shift: Int): List<Double> = expected.toList().mapNotNull { e ->
            val at = e + shift
            detector.find(
                recording.samples, recording.size,
                (at - msToSamples(FINE_WINDOW_MS)).toInt(), (at + msToSamples(FINE_WINDOW_MS)).toInt(),
            )?.takeIf { it.snr >= MIN_SNR }?.let { (it.sample - e) * 1_000 / sr }
        }

        fun reliable(errors: List<Double>) = errors.size >= MIN_CHIRPS && errors.max() - errors.min() <= MAX_SPREAD_MS

        // 1. The host's own chirps give the microphone-path delay (the reference everyone is
        //    measured against). Without them, phones are still measured relative to each other.
        val hostExpected = expectedChirps(0)
        val hostShift = alignPattern(hostExpected, center = 0)
        val hostErrors = hostShift?.let { measure(hostExpected, it) }.orEmpty()
        val micDelayMs = if (reliable(hostErrors)) median(hostErrors) else null

        // 2. Every phone: align its pattern near the host's, then time each chirp precisely.
        val phones = (0 until slots).map { slot ->
            val expected = expectedChirps(slot)
            val center = if (micDelayMs != null) msToSamples(micDelayMs) else 0
            val errors = alignPattern(expected, center)?.let { measure(expected, it) }.orEmpty()
            val late = if (reliable(errors)) median(errors) - (micDelayMs ?: 0.0) else null
            PhoneResult(slot, if (slot == 0 && micDelayMs == null) null else late, errors.size)
        }
        return Result(phones)
    }

    /** One very loud chirp (or click) must not outweigh the other three in the pattern score. */
    private const val PATTERN_SCORE_CAP = 50.0

    private fun median(values: List<Double>): Double {
        val sorted = values.sorted()
        return if (sorted.size % 2 == 1) sorted[sorted.size / 2] else (sorted[sorted.size / 2 - 1] + sorted[sorted.size / 2]) / 2
    }
}
