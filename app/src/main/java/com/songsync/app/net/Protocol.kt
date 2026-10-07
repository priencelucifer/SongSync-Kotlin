package com.songsync.app.net

import com.songsync.app.data.model.Track
import com.songsync.app.sync.PlaybackState
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json

/** Bump when messages change incompatibly; mismatched phones are told to update. */
const val PROTOCOL_VERSION = 1

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
) : Message

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
