package com.songsync.app.net

import android.content.Context
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.BandwidthInfo
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionOptions
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionType
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import com.songsync.app.sync.MonotonicClock
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.tasks.await

/** A host found while discovering. */
data class DiscoveredHost(val endpointId: String, val name: String, val sessionId: String, val protocol: Int)

/** What a host advertises, packed into Nearby's small endpoint-info field. */
data class EndpointInfo(val name: String, val sessionId: String, val protocol: Int = PROTOCOL_VERSION) {
    fun encode(): ByteArray = "$MAGIC|$protocol|$sessionId|${name.truncated()}".toByteArray(Charsets.UTF_8)

    /** At most 24 code points (never splitting an emoji), so the payload stays under 131 bytes. */
    private fun String.truncated(): String =
        if (codePointCount(0, length) <= MAX_NAME_CODE_POINTS) this
        else substring(0, offsetByCodePoints(0, MAX_NAME_CODE_POINTS))

    companion object {
        private const val MAGIC = "SS"
        private const val MAX_NAME_CODE_POINTS = 24

        fun decode(bytes: ByteArray?): EndpointInfo? {
            val parts = bytes?.toString(Charsets.UTF_8)?.split('|', limit = 4) ?: return null
            if (parts.size != 4 || parts[0] != MAGIC) return null
            return EndpointInfo(name = parts[3], sessionId = parts[2], protocol = parts[1].toIntOrNull() ?: return null)
        }
    }
}

/**
 * Google Nearby Connections (P2P_STAR: one host, many clients). Callbacks arrive on the main
 * thread; receive timestamps are taken first thing for clock sync.
 *
 * Connections are NON_DISRUPTIVE: Nearby may otherwise move phones onto a Wi-Fi hotspot,
 * cutting the internet connection every phone needs to stream the music.
 */
class NearbyTransport(context: Context, private val clock: MonotonicClock) : Transport {

    private val client = Nearby.getConnectionsClient(context.applicationContext)

    private val _events = MutableSharedFlow<TransportEvent>(extraBufferCapacity = 512)
    override val events: SharedFlow<TransportEvent> = _events.asSharedFlow()

    private val _hosts = MutableStateFlow<Map<String, DiscoveredHost>>(emptyMap())
    val hosts: StateFlow<Map<String, DiscoveredHost>> = _hosts.asStateFlow()

    /** Host mode: accept incoming connection requests. */
    private var acceptIncoming = false
    private val outgoing = HashSet<String>()
    private val names = HashMap<String, String>()

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            val receivedAt = clock.nowNs()
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { _events.tryEmit(TransportEvent.Received(endpointId, it, receivedAt)) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) = Unit
    }

    private val lifecycle = object : ConnectionLifecycleCallback() {
        override fun onConnectionInitiated(endpointId: String, info: ConnectionInfo) {
            val accept = if (info.isIncomingConnection) acceptIncoming else endpointId in outgoing
            if (accept) {
                names[endpointId] = info.endpointName
                client.acceptConnection(endpointId, payloadCallback)
            } else {
                client.rejectConnection(endpointId)
            }
        }

        override fun onConnectionResult(endpointId: String, resolution: ConnectionResolution) {
            outgoing -= endpointId
            val event = if (resolution.status.isSuccess) {
                TransportEvent.Connected(endpointId, names[endpointId] ?: endpointId)
            } else {
                names -= endpointId
                TransportEvent.ConnectionFailed(endpointId, resolution.status.statusCode)
            }
            _events.tryEmit(event)
        }

        override fun onDisconnected(endpointId: String) {
            names -= endpointId
            _events.tryEmit(TransportEvent.Disconnected(endpointId))
        }

        override fun onBandwidthChanged(endpointId: String, info: BandwidthInfo) {
            _events.tryEmit(TransportEvent.BandwidthChanged(endpointId, info.quality))
        }
    }

    private val discovery = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            if (info.serviceId != SERVICE_ID) return
            val advert = EndpointInfo.decode(info.endpointInfo) ?: return
            _hosts.update { it + (endpointId to DiscoveredHost(endpointId, advert.name, advert.sessionId, advert.protocol)) }
        }

        override fun onEndpointLost(endpointId: String) {
            _hosts.update { it - endpointId }
        }
    }

    suspend fun startAdvertising(info: EndpointInfo) {
        acceptIncoming = true
        val options = AdvertisingOptions.Builder()
            .setStrategy(Strategy.P2P_STAR)
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .setLowPower(false)
            .build()
        client.startAdvertising(info.encode(), SERVICE_ID, lifecycle, options).await()
    }

    fun stopAdvertising() {
        acceptIncoming = false
        client.stopAdvertising()
    }

    suspend fun startDiscovery() {
        _hosts.value = emptyMap()
        val options = DiscoveryOptions.Builder().setStrategy(Strategy.P2P_STAR).setLowPower(false).build()
        client.startDiscovery(SERVICE_ID, discovery, options).await()
    }

    fun stopDiscovery() = client.stopDiscovery()

    suspend fun connect(endpointId: String, localName: String) {
        outgoing += endpointId
        val options = ConnectionOptions.Builder()
            .setConnectionType(ConnectionType.NON_DISRUPTIVE)
            .setLowPower(false)
            .build()
        try {
            client.requestConnection(localName, endpointId, lifecycle, options).await()
        } catch (e: Exception) {
            outgoing -= endpointId
            throw e
        }
    }

    override fun send(endpointId: String, bytes: ByteArray) {
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    override fun send(endpointIds: Collection<String>, bytes: ByteArray) {
        if (endpointIds.isNotEmpty()) client.sendPayload(endpointIds.toList(), Payload.fromBytes(bytes))
    }

    override fun disconnect(endpointId: String) {
        client.disconnectFromEndpoint(endpointId)
        // Nearby does not call onDisconnected for disconnects we initiate.
        if (names.remove(endpointId) != null) _events.tryEmit(TransportEvent.Disconnected(endpointId))
    }

    fun stopAll() {
        acceptIncoming = false
        outgoing.clear()
        names.clear()
        client.stopAdvertising()
        client.stopDiscovery()
        client.stopAllEndpoints()
        _hosts.value = emptyMap()
    }

    companion object {
        const val SERVICE_ID = "com.songsync.app"
    }
}
