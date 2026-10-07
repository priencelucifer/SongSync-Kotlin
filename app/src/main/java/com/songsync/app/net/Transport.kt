package com.songsync.app.net

import kotlinx.coroutines.flow.Flow

/** Message link between connected phones. Nearby Connections in the app, a fake in tests. */
interface Transport {
    val events: Flow<TransportEvent>
    fun send(endpointId: String, bytes: ByteArray)
    fun send(endpointIds: Collection<String>, bytes: ByteArray)
    fun disconnect(endpointId: String)
}

fun Transport.send(endpointId: String, message: Message) = send(endpointId, ProtocolCodec.encode(message))
fun Transport.send(endpointIds: Collection<String>, message: Message) {
    if (endpointIds.isNotEmpty()) send(endpointIds, ProtocolCodec.encode(message))
}

sealed interface TransportEvent {
    val endpointId: String

    data class Connected(override val endpointId: String, val name: String) : TransportEvent
    data class ConnectionFailed(override val endpointId: String, val statusCode: Int) : TransportEvent
    data class Disconnected(override val endpointId: String) : TransportEvent

    /** [receivedAtNs] is captured first thing in the callback, before any decoding. */
    class Received(override val endpointId: String, val bytes: ByteArray, val receivedAtNs: Long) : TransportEvent

    /** The link moved to a faster/slower medium (e.g. Bluetooth -> Wi-Fi). */
    data class BandwidthChanged(override val endpointId: String, val quality: Int) : TransportEvent
}
