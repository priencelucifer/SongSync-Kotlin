package com.songsync.app.data

import com.google.common.truth.Truth.assertThat
import com.songsync.app.data.source.ClickTrack
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class ClickTrackTest {

    private val bytes by lazy { ByteArrayOutputStream().also(ClickTrack::write).toByteArray() }

    @Test
    fun `is a valid mono 16-bit WAV of the advertised length`() {
        val header = ByteBuffer.wrap(bytes, 0, 44).order(ByteOrder.LITTLE_ENDIAN)
        assertThat(String(bytes, 0, 4)).isEqualTo("RIFF")
        assertThat(String(bytes, 8, 4)).isEqualTo("WAVE")
        assertThat(header.getShort(22).toInt()).isEqualTo(1) // channels
        val sampleRate = header.getInt(24)
        val dataBytes = header.getInt(40)
        assertThat(bytes.size).isEqualTo(44 + dataBytes)
        assertThat(dataBytes / 2 / sampleRate * 1000L).isEqualTo(ClickTrack.TRACK.durationMs)
    }

    @Test
    fun `is byte-for-byte identical every time`() {
        val again = ByteArrayOutputStream().also(ClickTrack::write).toByteArray()
        assertThat(again).isEqualTo(bytes)
    }

    @Test
    fun `clicks land exactly on the beat and are silent between`() {
        val pcm = ByteBuffer.wrap(bytes, 44, bytes.size - 44).order(ByteOrder.LITTLE_ENDIAN).asShortBuffer()
        val beat = 22_050 / 2 // 120 BPM
        assertThat(pcm.get(beat + 5).toInt()).isNotEqualTo(0)
        assertThat(pcm.get(beat - 1).toInt()).isEqualTo(0)
        assertThat(pcm.get(beat + beat / 2).toInt()).isEqualTo(0)
    }
}
