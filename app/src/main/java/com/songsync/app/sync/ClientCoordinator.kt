package com.songsync.app.sync

import com.songsync.app.data.model.Track
import com.songsync.app.net.Bye
import com.songsync.app.net.ClientStatus
import com.songsync.app.net.Hello
import com.songsync.app.net.LoadTrack
import com.songsync.app.net.Message
import com.songsync.app.net.PROTOCOL_VERSION
import com.songsync.app.net.ProtocolCodec
import com.songsync.app.net.Reject
import com.songsync.app.net.RejectReason
import com.songsync.app.net.StateUpdate
import com.songsync.app.net.TimeRequest
import com.songsync.app.net.TimeResponse
import com.songsync.app.net.TrackFailed
import com.songsync.app.net.TrackReady
import com.songsync.app.net.Transport
import com.songsync.app.net.TransportEvent
import com.songsync.app.net.Welcome
import com.songsync.app.net.send
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * A joined phone's side of a session. Survives reconnects: while the link is down the
 * follower keeps playing on the last known timeline, and [attach] picks up where it left off.
 */
class ClientCoordinator(
    private val scope: CoroutineScope,
    private val link: Transport,
    private val clock: MonotonicClock,
    private val local: LocalPlayback,
    private val deviceName: String,
    private val appVersion: String,
    private val config: SessionConfig = SessionConfig(),
) {
    data class HostInfo(val name: String, val sessionId: String)

    sealed interface LoadStatus {
        data object Idle : LoadStatus
        data class Loading(val trackKey: String) : LoadStatus
        data class Ready(val trackKey: String) : LoadStatus
        data class Failed(val trackKey: String, val reason: String?) : LoadStatus
    }

    sealed interface Event {
        data class Rejected(val reason: RejectReason) : Event
        /** The host ended the session on purpose. */
        data object HostLeft : Event
    }

    val clockSync = ClockSync()

    private val _host = MutableStateFlow<HostInfo?>(null)
    val host: StateFlow<HostInfo?> = _host.asStateFlow()

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _loadStatus = MutableStateFlow<LoadStatus>(LoadStatus.Idle)
    val loadStatus: StateFlow<LoadStatus> = _loadStatus.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 4)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** Set by the UI/session when this phone is paused locally. Reported to the host. */
    var onHold = false

    private var endpointId: String? = null
    private var linkJob: Job? = null
    private var loadJob: Job? = null
    private var nextTimeId = 0
    private val pendingTimeRequests = LinkedHashMap<Int, Long>()

    fun attach(endpointId: String) {
        detach()
        this.endpointId = endpointId
        linkJob = scope.launch {
            launch(start = CoroutineStart.UNDISPATCHED) {
                link.events.collect { if (it.endpointId == endpointId) onEvent(it) }
            }
            link.send(endpointId, Hello(PROTOCOL_VERSION, deviceName, appVersion))
            launch {
                timeSyncBurst()
                while (isActive) {
                    delay(config.timeSyncIntervalMs)
                    sendTimeRequest()
                }
            }
            launch {
                while (isActive) {
                    delay(config.statusIntervalMs)
                    sendStatus()
                }
            }
        }
    }

    /** The link dropped; keep playing on the last timeline until [attach] is called again. */
    fun detach() {
        linkJob?.cancel()
        linkJob = null
        endpointId = null
        pendingTimeRequests.clear()
    }

    fun leave() {
        endpointId?.let { link.send(it, Bye) }
        detach()
        loadJob?.cancel()
    }

    /** Position on the shared timeline right now, as far as this phone knows. */
    fun positionMs(): Long {
        val offset = clockSync.estimate?.offsetNs ?: return _state.value.anchorPositionMs
        return _state.value.positionAt(clock.nowNs() + offset)
    }

    // --- protocol ----------------------------------------------------------------------------

    private fun onEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.Received -> ProtocolCodec.decode(event.bytes)?.let { handle(it, event.receivedAtNs) }
            is TransportEvent.BandwidthChanged -> scope.launch { timeSyncBurst() }
            else -> Unit
        }
    }

    private fun handle(message: Message, receivedAtNs: Long) {
        when (message) {
            is TimeResponse -> onTimeResponse(message, receivedAtNs)
            is Welcome -> {
                _host.value = HostInfo(message.hostName, message.sessionId)
                message.track?.let(::load)
                applyState(message.state)
            }
            is LoadTrack -> load(message.track)
            is StateUpdate -> applyState(message.state)
            is Reject -> _events.tryEmit(Event.Rejected(message.reason))
            Bye -> _events.tryEmit(Event.HostLeft)
            else -> Unit
        }
    }

    private fun onTimeResponse(response: TimeResponse, t3: Long) {
        val t0 = pendingTimeRequests.remove(response.id) ?: return
        if (t0 != response.t0) return
        clockSync.addSample(t0, response.t1, response.t2, t3)
        pushClockOffset()
    }

    private fun pushClockOffset() {
        val estimate = clockSync.estimate ?: return
        if (estimate.samples >= config.minClockSamples) local.follower.setClockOffset(estimate.offsetNs)
    }

    private fun applyState(newState: PlaybackState) {
        if (newState.seq < _state.value.seq) return
        _state.value = newState
        local.follower.updateState(newState)
    }

    private fun load(track: Track) {
        val status = _loadStatus.value
        val sameTrack = _track.value?.key == track.key
        if (sameTrack && status is LoadStatus.Ready) {
            endpointId?.let { link.send(it, TrackReady(track.key)) } // e.g. after a reconnect
            return
        }
        if (sameTrack && status is LoadStatus.Loading) return

        _track.value = track
        loadJob?.cancel()
        loadJob = scope.launch {
            _loadStatus.value = LoadStatus.Loading(track.key)
            try {
                // Clients must play the exact stream the host chose, so no alternatives here.
                local.prepare(local.resolve(track, allowAlternatives = false))
                _loadStatus.value = LoadStatus.Ready(track.key)
                endpointId?.let { link.send(it, TrackReady(track.key)) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _loadStatus.value = LoadStatus.Failed(track.key, e.message)
                endpointId?.let { link.send(it, TrackFailed(track.key, e.message ?: e.javaClass.simpleName)) }
            }
        }
    }

    private suspend fun timeSyncBurst() {
        repeat(config.timeSyncBurst) {
            sendTimeRequest()
            delay(config.timeSyncBurstIntervalMs)
        }
    }

    private fun sendTimeRequest() {
        val id = endpointId ?: return
        val requestId = nextTimeId++
        while (pendingTimeRequests.size >= MAX_PENDING_TIME_REQUESTS) {
            pendingTimeRequests.remove(pendingTimeRequests.keys.first())
        }
        val t0 = clock.nowNs()
        pendingTimeRequests[requestId] = t0
        link.send(id, TimeRequest(requestId, t0))
    }

    private fun sendStatus() {
        val id = endpointId ?: return
        val status = local.follower.status
        val ready = _loadStatus.value
        link.send(
            id,
            ClientStatus(
                trackKey = _track.value?.key,
                ready = ready is LoadStatus.Ready && ready.trackKey == _track.value?.key,
                syncErrorMs = status.errorMs?.roundToInt(),
                rttMs = clockSync.estimate?.let { (it.p90RttNs / NANOS_PER_MS).toInt() },
                onHold = onHold,
            ),
        )
    }

    private companion object {
        const val MAX_PENDING_TIME_REQUESTS = 64
    }
}
