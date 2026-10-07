package com.songsync.app.calibration

import com.google.common.truth.Truth.assertThat
import com.songsync.app.sync.NANOS_PER_MS
import com.songsync.app.sync.PlaybackState
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.random.Random

class CalibrationAnalyzerTest {

    private val sr = CalibrationSignal.SAMPLE_RATE
    private val chirp = CalibrationSignal.chirp()

    /** A phone as heard by the host's microphone. */
    private data class Phone(val lateMs: Double, val loudness: Double, val reflectionMs: Double = 3.5, val reflectionGain: Double = 0.6)

    /**
     * Synthesises what the host microphone records: every phone's chirps, only in its own slot,
     * delayed by its lateness plus a common microphone-path delay, with one reflection and noise.
     */
    private fun record(phones: List<Phone>, micDelayMs: Double, noise: Double = 0.01, seed: Int = 1): Pair<Recording, PlaybackState> {
        val anchorNs = 1_000_000_000_000L
        val timeline = PlaybackState(seq = 5, trackKey = "k", playing = true, anchorHostNs = anchorNs, anchorPositionMs = 0)
        val recordingStartNs = anchorNs - 700 * NANOS_PER_MS // recording began before the track
        val length = ((CalibrationSignal.durationMs(phones.size) + 1_500) * sr / 1_000).toInt()
        val signal = DoubleArray(length)
        val random = Random(seed)
        for (i in signal.indices) signal[i] = random.nextDouble(-noise, noise)

        fun addChirp(atSample: Double, gain: Double) {
            val base = atSample.toInt()
            val fraction = atSample - base
            for (i in chirp.indices) {
                // Linear interpolation gives a true fractional-sample delay.
                val idx = base + i
                if (idx + 1 < length) {
                    signal[idx] += chirp[i] * gain * (1 - fraction)
                    signal[idx + 1] += chirp[i] * gain * fraction
                }
            }
        }

        phones.forEachIndexed { slot, phone ->
            for (index in 0 until CalibrationSignal.CHIRPS_PER_SLOT) {
                val arrivalNs = anchorNs + CalibrationSignal.chirpStartMs(slot, index) * NANOS_PER_MS +
                    ((phone.lateMs + micDelayMs) * NANOS_PER_MS).toLong()
                val sample = (arrivalNs - recordingStartNs).toDouble() * sr / 1e9
                addChirp(sample, phone.loudness)
                addChirp(sample + phone.reflectionMs * sr / 1_000, phone.loudness * phone.reflectionGain)
            }
        }
        val pcm = ShortArray(length) { (signal[it].coerceIn(-1.0, 1.0) * Short.MAX_VALUE).roundToInt().toShort() }
        return Recording(pcm, length, sr, recordingStartNs) to timeline
    }

    @Test
    fun `measures every phone's offset to a fraction of a millisecond`() {
        val phones = listOf(Phone(0.0, 1.0), Phone(23.4, 0.25), Phone(-11.7, 0.4), Phone(4.2, 0.15))
        val (recording, timeline) = record(phones, micDelayMs = 37.0)
        val result = CalibrationAnalyzer.analyze(recording, timeline, phones.size)

        val corrections = result.corrections()
        val trueOffsets = phones.map { it.lateMs }
        val median = trueOffsets.sorted().let { (it[1] + it[2]) / 2 }
        phones.indices.forEach { slot ->
            assertThat(corrections[slot]).isWithin(0.3).of(trueOffsets[slot] - median)
        }
    }

    @Test
    fun `a reflection louder than the direct sound does not fool it`() {
        val phones = listOf(Phone(0.0, 1.0), Phone(15.0, 0.3, reflectionMs = 2.0, reflectionGain = 1.3))
        val (recording, timeline) = record(phones, micDelayMs = 20.0)
        val corrections = CalibrationAnalyzer.analyze(recording, timeline, phones.size).corrections()
        assertThat(corrections.getValue(1) - corrections.getValue(0)).isWithin(0.3).of(15.0)
    }

    @Test
    fun `a phone that cannot be heard is reported, not guessed`() {
        val phones = listOf(Phone(0.0, 1.0), Phone(10.0, 0.0), Phone(-5.0, 0.3))
        val (recording, timeline) = record(phones, micDelayMs = 30.0)
        val result = CalibrationAnalyzer.analyze(recording, timeline, phones.size)
        assertThat(result.phones[1].lateMs).isNull()
        assertThat(result.corrections().keys).containsExactly(0, 2)
    }

    @Test
    fun `sync check reports each phone's lateness against the host`() {
        val phones = listOf(Phone(0.0, 1.0), Phone(6.0, 0.3), Phone(-2.5, 0.4), Phone(0.0, 0.0))
        val (recording, timeline) = record(phones, micDelayMs = 25.0)
        val lateness = CalibrationAnalyzer.analyze(recording, timeline, phones.size).latenessVsHost()
        assertThat(lateness.getValue(0)).isWithin(1e-9).of(0.0)
        assertThat(lateness.getValue(1)).isWithin(0.3).of(6.0)
        assertThat(lateness.getValue(2)).isWithin(0.3).of(-2.5)
        assertThat(lateness[3]).isNull() // not heard: reported, not guessed
    }

    @Test
    fun `sync check needs the host's own chirps as the reference`() {
        val phones = listOf(Phone(0.0, 0.0), Phone(5.0, 0.5))
        val (recording, timeline) = record(phones, micDelayMs = 25.0)
        assertThat(CalibrationAnalyzer.analyze(recording, timeline, phones.size).latenessVsHost()).isEmpty()
    }

    @Test
    fun `a host far off the timeline is not mistaken for its neighbouring chirp`() {
        // The real report: the host played ~300 ms early (runaway calibration), its chirps were
        // matched to their neighbours 400 ms away and both guests were "measured" ~100-150 ms early.
        val phones = listOf(Phone(-300.0, 1.0), Phone(0.0, 0.4), Phone(4.0, 0.3))
        val (recording, timeline) = record(phones, micDelayMs = 60.0)
        val lateness = CalibrationAnalyzer.analyze(recording, timeline, phones.size).latenessVsHost()
        assertThat(lateness.getValue(1)).isWithin(0.3).of(300.0)
        assertThat(lateness.getValue(2)).isWithin(0.3).of(304.0)
    }

    @Test
    fun `large offsets either way are measured, not aliased`() {
        for (offset in listOf(-650.0, -420.0, -210.0, 190.0, 260.0, 410.0, 700.0)) {
            val phones = listOf(Phone(0.0, 1.0), Phone(offset, 0.4))
            val (recording, timeline) = record(phones, micDelayMs = 90.0, seed = offset.toInt())
            val lateness = CalibrationAnalyzer.analyze(recording, timeline, phones.size).latenessVsHost()
            assertThat(lateness.getValue(1)).isWithin(0.3).of(offset)
        }
    }

    @Test
    fun `nothing heard at all yields no corrections`() {
        val phones = listOf(Phone(0.0, 0.0), Phone(0.0, 0.0))
        val (recording, timeline) = record(phones, micDelayMs = 30.0)
        assertThat(CalibrationAnalyzer.analyze(recording, timeline, phones.size).corrections()).isEmpty()
    }

    @Test
    fun `calibration track has chirps exactly where the analyzer looks`() {
        val bytes = ByteArrayOutputStream().also { CalibrationSignal.writeWav(it, slots = 2) }.toByteArray()
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val start = (CalibrationSignal.chirpStartMs(1, 2) * sr / 1_000).toInt()
        assertThat(pcm.get(start - 10).toInt()).isEqualTo(0)
        assertThat((start until start + chirp.size).maxOf { kotlin.math.abs(pcm.get(it).toInt()) }).isGreaterThan(10_000)
        assertThat(bytes.size - 44).isEqualTo((CalibrationSignal.durationMs(2) * sr / 1_000).toInt() * 2)
    }

    @Test
    fun `slots and track ids round trip`() {
        val track = CalibrationSignal.track(3)
        assertThat(CalibrationSignal.slotsOf(track)).isEqualTo(3)
        assertThat(CalibrationSignal.isCalibrationKey(track.key)).isTrue()
        assertThat(CalibrationSignal.isInSlot(1, CalibrationSignal.chirpStartMs(1, 0))).isTrue()
        assertThat(CalibrationSignal.isInSlot(0, CalibrationSignal.chirpStartMs(1, 0))).isFalse()
    }
}
