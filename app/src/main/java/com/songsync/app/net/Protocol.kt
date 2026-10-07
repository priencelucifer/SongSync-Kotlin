package com.songsync.app.net

import com.songsync.app.data.model.Track
import com.songsync.app.sync.PlaybackState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Bump when messages change incompatibly; mismatched phones are told to update. */
// 3: Track.itag pins the YouTube stream; older phones would ignore it.
// 4: calibration track v2 (longer lead-in); older phones could not play it.
const val PROTOCOL_VERSION = 4

@Serializable
sealed interface Message

/** Client -> host, first message after connecting. */
@Serializable
@SerialName("hello")
data class Hello(val protocol: Int, val deviceName: String, val appVersion: String) : Message

/** Host -> client: who the host is and what is playing right now. */
@Serializable
@SerialName("welcome")
data class Welcome(
    val protocol: Int,
    val hostName: String,
    val sessionId: String,
    val track: Track?,
    val state: PlaybackState,
) : Message

@Serializable
@SerialName("reject")
data class Reject(val reason: RejectReason) : Message

@Serializable
enum class RejectReason { PROTOCOL_MISMATCH, GROUP_LOCKED }

/** Clock sync request; [t0] is the client's monotonic send time. */
@Serializable
@SerialName("tq")
data class TimeRequest(val id: Int, val t0: Long) : Message

/** Clock sync reply; [t1]/[t2] are the host's receive/send times. */
@Serializable
@SerialName("tr")
data class TimeResponse(val id: Int, val t0: Long, val t1: Long, val t2: Long) : Message

/** Host -> clients: start preparing this track (each phone resolves the stream itself). */
@Serializable
@SerialName("load")
data class LoadTrack(val track: Track) : Message

@Serializable
@SerialName("ready")
data class TrackReady(val trackKey: String) : Message

@Serializable
@SerialName("failed")
data class TrackFailed(val trackKey: String, val reason: String) : Message

/** Host -> clients: the shared timeline, on every change and as a heartbeat. */
@Serializable
@SerialName("state")
data class StateUpdate(val state: PlaybackState) : Message

/** Client -> host, periodically, for the host's device list and lead-time choice. */
@Serializable
@SerialName("status")
data class ClientStatus(
    val trackKey: String?,
    val ready: Boolean,
    val syncErrorMs: Int?,
    val rttMs: Int?,
    val onHold: Boolean,
    /** What this phone is actually decoding (optional: older clients do not send it). */
    val format: AudioFormatInfo? = null,
    /** Device and sync details for the host's sync report (optional). */
    val diag: DeviceDiag? = null,
) : Message

/** One phone's device and sync details, as shown in the host's sync report. */
@Serializable
data class DeviceDiag(
    val model: String,
    val sdk: Int,
    val appVersion: String,
    /** Audio output, e.g. "SPEAKER" or "BLUETOOTH JBL Flip 5". */
    val route: String,
    val startLatencyMs: Double,
    val calibrationMs: Double,
    val clockSpreadMs: Double? = null,
    val clockSkewPpm: Double? = null,
    val hardResyncs: Int = 0,
    val phase: String = "",
    /** Wi-Fi lock held for the session ("low latency" / "high perf"), or null. */
    val wifiLock: String? = null,
)

/**
 * The audio a phone decodes for [trackKey]. Two phones playing different files of the same song
 * (another YouTube itag, another bitrate, different encoder priming) can be offset by a constant
 * 6-120 ms that position-based sync cannot see, so the host compares these.
 */
@Serializable
data class AudioFormatInfo(
    val trackKey: String,
    val mime: String? = null,
    val sampleRate: Int = 0,
    val channels: Int = 0,
    /** Priming samples trimmed at the start (0 when the file does not say). */
    val encoderDelay: Int = 0,
    val encoderPadding: Int = 0,
    val bitrateKbps: Int? = null,
    /** YouTube stream format id, when the stream came from YouTube. */
    val itag: Int? = null,
    /** Decoder name; legitimately differs between phone models, so not part of [sameContentAs]. */
    val decoder: String? = null,
) {
    fun sameContentAs(other: AudioFormatInfo): Boolean = copy(decoder = null, bitrateKbps = null) ==
        other.copy(decoder = null, bitrateKbps = null) && (bitrateKbps == null || other.bitrateKbps == null || bitrateKbps == other.bitrateKbps)

    /** Short form for the diagnostics panel, e.g. "mp4a-latm 44.1 kHz 2ch d2112 itag140 128k". */
    fun summary(): String = listOfNotNull(
        mime?.substringAfter('/'),
        if (sampleRate > 0) "%.1f kHz".format(sampleRate / 1000.0) else null,
        if (channels > 0) "${channels}ch" else null,
        "d$encoderDelay",
        itag?.let { "itag$it" },
        bitrateKbps?.let { "${it}k" },
    ).joinToString(" ")
}

/** True when both formats are known for [trackKey] and describe different files. */
fun differentFile(host: AudioFormatInfo?, peer: AudioFormatInfo?, trackKey: String?): Boolean =
    trackKey != null && host != null && peer != null &&
        host.trackKey == trackKey && peer.trackKey == trackKey && !host.sameContentAs(peer)

/** Host -> one client: your slot in the upcoming echo-calibration track (only audible then). */
@Serializable
@SerialName("calib")
data class CalibrationPlan(val trackKey: String, val slot: Int) : Message

/** Host -> one client: add this to your echo calibration (ms), or null if you were not heard. */
@Serializable
@SerialName("calibrated")
data class CalibrationResult(val correctionMs: Double?) : Message

/** Either direction: leaving on purpose (so the other side does not try to reconnect). */
@Serializable
@SerialName("bye")
data object Bye : Message

object ProtocolCodec {
    private val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun encode(message: Message): ByteArray =
        json.encodeToString(Message.serializer(), message).encodeToByteArray()

    /** Returns null for malformed or unknown messages (e.g. from a newer app version). */
    fun decode(bytes: ByteArray): Message? = try {
        json.decodeFromString(Message.serializer(), bytes.decodeToString())
    } catch (_: SerializationException) {
        null
    } catch (_: IllegalArgumentException) {
        null
    }
}
