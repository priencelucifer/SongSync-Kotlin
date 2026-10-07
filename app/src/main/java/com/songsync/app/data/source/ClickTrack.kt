package com.songsync.app.data.source

import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import java.io.BufferedOutputStream
import java.io.File
import java.io.OutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * A metronome generated identically on every phone, so sync can be checked (or calibrated) by
 * ear or with a recording, with no internet needed. Sharp clicks make even a few milliseconds
 * of offset audible as a "flam".
 */
object ClickTrack {
    private const val SAMPLE_RATE = 22_050
    private const val DURATION_S = 180
    private const val BPM = 120
    private const val CLICK_MS = 25

    val TRACK = Track(
        source = TrackSource.CLICK_TEST,
        id = "click-${BPM}bpm-v1",
        title = "Sync test",
        artist = "Click track · $BPM BPM",
        durationMs = DURATION_S * 1_000L,
    )

    private val dataBytes = SAMPLE_RATE * DURATION_S * 2

    /** Returns the cached WAV file, generating it on first use. Call off the main thread. */
    @Synchronized
    fun file(cacheDir: File): File {
        val file = File(cacheDir, "${TRACK.id}.wav")
        if (file.length() == (HEADER_BYTES + dataBytes).toLong()) return file
        val tmp = File(cacheDir, "${TRACK.id}.tmp")
        tmp.outputStream().use(::write)
        check(tmp.renameTo(file)) { "Could not save the click track" }
        return file
    }

    internal fun write(output: OutputStream) {
        val out = BufferedOutputStream(output, 64 * 1024)
        out.write(header())
        val beatSamples = SAMPLE_RATE * 60 / BPM
        val clickSamples = SAMPLE_RATE * CLICK_MS / 1_000
        val sample = ByteArray(2)
        for (i in 0 until SAMPLE_RATE * DURATION_S) {
            val beat = i / beatSamples
            val offset = i % beatSamples
            val value = if (offset < clickSamples) {
                val frequency = if (beat % 4 == 0) 2_000.0 else 1_000.0 // accent each bar
                val t = offset.toDouble() / SAMPLE_RATE
                sin(2 * PI * frequency * t) * exp(-t * 180) * 0.9
            } else {
                0.0
            }
            val pcm = (value * Short.MAX_VALUE).toInt()
            sample[0] = pcm.toByte()
            sample[1] = (pcm shr 8).toByte()
            out.write(sample)
        }
        out.flush()
    }

    private fun header(): ByteArray = ByteBuffer.allocate(HEADER_BYTES).order(ByteOrder.LITTLE_ENDIAN).apply {
        put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
        put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1) // PCM, mono
        putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
        put("data".toByteArray()); putInt(dataBytes)
    }.array()

    private const val HEADER_BYTES = 44
}
