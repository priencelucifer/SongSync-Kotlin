package com.songsync.app.net

import com.google.common.truth.Truth.assertThat
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.sync.PlaybackState
import org.junit.Test

class ProtocolTest {

    private val track = Track(
        source = TrackSource.JIOSAAVN,
        id = "BeXBcbVK",
        title = "Believer",
        artist = "Imagine Dragons",
        artworkUrl = "https://c.saavncdn.com/248/x-500x500.jpg",
        durationMs = 204_000,
        encryptedMediaUrl = "ID2ieOjCrwfgWvL5sXl4B1ImC5QfbsDy",
        highQualityAvailable = true,
        bitrateKbps = 320,
    )
    private val state = PlaybackState(7, track.key, playing = true, anchorHostNs = 123_456_789_000, anchorPositionMs = 42)

    @Test
    fun `every message survives a round trip`() {
        val messages = listOf(
            Hello(PROTOCOL_VERSION, "Pixel 8", "2.0.0"),
            Welcome(PROTOCOL_VERSION, "Host", "abc123", track, state),
            Welcome(PROTOCOL_VERSION, "Host", "abc123", null, PlaybackState.Idle),
            Reject(RejectReason.GROUP_LOCKED),
            TimeRequest(1, 1_000),
            TimeResponse(1, 1_000, 2_000, 2_100),
            LoadTrack(track),
            TrackReady(track.key),
            TrackFailed(track.key, "HTTP 403"),
            StateUpdate(state),
            ClientStatus(track.key, ready = true, syncErrorMs = -3, rttMs = 18, onHold = false),
            ClientStatus(null, ready = false, syncErrorMs = null, rttMs = null, onHold = true),
            ClientStatus(track.key, ready = true, syncErrorMs = 1, rttMs = 9, onHold = false, format = aac),
            CalibrationPlan(track.key, slot = 2),
            CalibrationResult(correctionMs = -12.5),
            CalibrationResult(correctionMs = null),
            CalibrationReset,
            Bye,
        )
        messages.forEach { assertThat(ProtocolCodec.decode(ProtocolCodec.encode(it))).isEqualTo(it) }
    }

    @Test
    fun `messages fit comfortably in one Nearby bytes payload`() {
        val size = ProtocolCodec.encode(Welcome(PROTOCOL_VERSION, "Host".repeat(10), "abc123", track, state)).size
        assertThat(size).isLessThan(32 * 1024)
    }

    @Test
    fun `unknown message types and fields from newer versions are tolerated`() {
        assertThat(ProtocolCodec.decode("""{"t":"future_thing","x":1}""".encodeToByteArray())).isNull()
        assertThat(ProtocolCodec.decode("not json".encodeToByteArray())).isNull()
        val withExtra = """{"t":"ready","trackKey":"k","addedInV2":true}""".encodeToByteArray()
        assertThat(ProtocolCodec.decode(withExtra)).isEqualTo(TrackReady("k"))
    }

    @Test
    fun `status from an older client without a format still decodes`() {
        val old = """{"t":"status","trackKey":"k","ready":true,"syncErrorMs":2,"rttMs":12,"onHold":false}"""
        assertThat(ProtocolCodec.decode(old.encodeToByteArray()))
            .isEqualTo(ClientStatus("k", ready = true, syncErrorMs = 2, rttMs = 12, onHold = false, format = null))
    }

    @Test
    fun `phones playing different files of the same song are told apart`() {
        val key = track.key
        val sameOtherDecoder = aac.copy(decoder = "OMX.qcom.audio.decoder.aac")
        val otherItag = aac.copy(itag = 251, mime = "audio/opus", sampleRate = 48_000, encoderDelay = 312)
        val otherPriming = aac.copy(encoderDelay = 1024)

        assertThat(differentFile(aac, sameOtherDecoder, key)).isFalse() // decoders differ by phone model
        assertThat(differentFile(aac, aac.copy(bitrateKbps = null), key)).isFalse() // unknown bitrate
        assertThat(differentFile(aac, otherItag, key)).isTrue()
        assertThat(differentFile(aac, otherPriming, key)).isTrue()
        assertThat(differentFile(aac, aac.copy(bitrateKbps = 96), key)).isTrue()
        assertThat(differentFile(aac, otherItag.copy(trackKey = "other"), key)).isFalse() // stale report
        assertThat(differentFile(aac, null, key)).isFalse()
    }

    private val aac = AudioFormatInfo(
        trackKey = track.key, mime = "audio/mp4a-latm", sampleRate = 44_100, channels = 2,
        encoderDelay = 2112, encoderPadding = 1000, bitrateKbps = 128, itag = 140, decoder = "c2.android.aac.decoder",
    )
}
