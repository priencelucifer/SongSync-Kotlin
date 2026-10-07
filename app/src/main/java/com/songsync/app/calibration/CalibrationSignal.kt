package com.songsync.app.calibration

import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * The automatic echo calibration "song": silence with short chirps, played in sync by every
 * phone like any other track. Each phone is only audible during its own slot, so the host's
 * microphone hears every phone separately and can measure when its sound really arrives.
 *
 * Layout: a lead-in (phones settle into sync), then one slot per phone with [CHIRPS_PER_SLOT]
 * chirps each, then a short tail. Slot boundaries fall in silence, so muting is click-free.
 *
 * The lead-in must cover a whole fresh start: some phones (seen on Android 8) start tens of ms
 * off and report unreliable positions for the first seconds, and the closed loop needs several
 * seconds after that. With a 5 s lead-in (v1) a real phone measured +42, -12 and -31 ms in three
 * identical checks, i.e. the check caught it mid-correction.
 */
object CalibrationSignal {
    const val SAMPLE_RATE = 48_000
    const val LEAD_IN_MS = 12_000L
    const val SLOT_MS = 2_000L
    /**
     * Chirp starts within a slot. Deliberately uneven: with even spacing (v1/v2: every 400 ms) a
     * phone ~300 ms off matched its neighbouring chirps and was measured 400 ms wrong, which
     * calibration then "corrected" into a runaway. With these gaps (300/450/350 ms) any wrong
     * alignment lines up at most one of the four chirps.
     */
    private val CHIRP_OFFSETS_MS = longArrayOf(250, 550, 1_000, 1_350)
    val CHIRPS_PER_SLOT = CHIRP_OFFSETS_MS.size
    const val TAIL_MS = 1_000L
    const val CHIRP_MS = 30
    /** Phones measured per run. */
    const val MAX_SLOTS = 8

    /** Bump with any layout change: every phone must generate the identical track. */
    private const val ID_PREFIX = "calib-v3-"
    private const val F_START = 2_000.0
    private const val F_END = 8_000.0
    private const val AMPLITUDE = 0.7

    fun track(slots: Int): Track {
        require(slots in 1..MAX_SLOTS)
        return Track(
            source = TrackSource.CLICK_TEST,
            id = "$ID_PREFIX$slots",
            title = "Echo calibration",
            artist = "SongSync",
            durationMs = durationMs(slots),
        )
    }

    /** Number of slots if [track] is a calibration track, else null. */
    fun slotsOf(track: Track?): Int? =
        track?.takeIf { it.source == TrackSource.CLICK_TEST && it.id.startsWith(ID_PREFIX) }
            ?.id?.removePrefix(ID_PREFIX)?.toIntOrNull()?.takeIf { it in 1..MAX_SLOTS }

    fun isCalibrationKey(trackKey: String?): Boolean = trackKey?.contains(":$ID_PREFIX") == true

    fun durationMs(slots: Int) = LEAD_IN_MS + slots * SLOT_MS + TAIL_MS

    fun chirpStartMs(slot: Int, index: Int) = LEAD_IN_MS + slot * SLOT_MS + CHIRP_OFFSETS_MS[index]

    /** Track positions during which the phone with [slot] is audible. */
    fun isInSlot(slot: Int, positionMs: Long): Boolean {
        val start = LEAD_IN_MS + slot * SLOT_MS
        return positionMs >= start && positionMs < start + SLOT_MS
    }

    /** One chirp: a Hann-windowed linear sweep, which correlates to a sharp, unambiguous peak. */
    fun chirp(): FloatArray {
        val n = SAMPLE_RATE * CHIRP_MS / 1_000
        val duration = n.toDouble() / SAMPLE_RATE
        val sweepRate = (F_END - F_START) / duration
        return FloatArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val window = 0.5 - 0.5 * cos(2 * PI * i / (n - 1))
            (sin(2 * PI * (F_START * t + 0.5 * sweepRate * t * t)) * window * AMPLITUDE).toFloat()
        }
    }

    /** Returns the cached WAV for [slots] phones, generating it on first use. Call off the main thread. */
    @Synchronized
    fun file(cacheDir: File, slots: Int): File {
        val file = File(cacheDir, "$ID_PREFIX$slots.wav")
        val expectedBytes = 44L + (durationMs(slots) * SAMPLE_RATE / 1_000) * 2
        if (file.length() == expectedBytes) return file
        val tmp = File(cacheDir, "$ID_PREFIX$slots.tmp")
        tmp.outputStream().use { writeWav(it, slots) }
        check(tmp.renameTo(file)) { "Could not save the calibration track" }
        return file
    }

    /** Writes the calibration track for [slots] phones as a 16-bit mono WAV. Identical on every phone. */
    fun writeWav(output: OutputStream, slots: Int) {
        val totalSamples = (durationMs(slots) * SAMPLE_RATE / 1_000).toInt()
        val pcm = ShortArray(totalSamples)
        val chirp = chirp()
        for (slot in 0 until slots) {
            for (index in 0 until CHIRPS_PER_SLOT) {
                val start = (chirpStartMs(slot, index) * SAMPLE_RATE / 1_000).toInt()
                for (i in chirp.indices) pcm[start + i] = (chirp[i] * Short.MAX_VALUE).roundToInt().toShort()
            }
        }
        val out = BufferedOutputStream(output, 64 * 1024)
        val dataBytes = totalSamples * 2
        out.write(
            ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
                put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
                put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
                putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
                put("data".toByteArray()); putInt(dataBytes)
            }.array(),
        )
        val bytes = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach(bytes::putShort)
        out.write(bytes.array())
        out.flush()
    }
}
