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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * The host's side of a session: owns the shared timeline, the queue and the list of phones.
 * The host's own player is driven by the same [PlaybackFollower] as everyone else's.
 */
class HostCoordinator(
    private val scope: CoroutineScope,
    private val link: Transport,
    private val clock: MonotonicClock,
    private val local: LocalPlayback,
    private val hostName: String,
    val sessionId: String,
    private val config: SessionConfig = SessionConfig(),
) {
    data class Peer(
        val endpointId: String,
        val name: String,
        val readyTrackKey: String? = null,
        val failedTrackKey: String? = null,
        val status: ClientStatus? = null,
        /** Nearby BandwidthInfo.Quality (1 low/Bluetooth .. 3 high), if reported. */
        val linkQuality: Int? = null,
    )

    sealed interface Event {
        data class LoadFailed(val track: Track, val reason: String?) : Event
        data class PeerFailed(val peerName: String, val track: Track?, val reason: String) : Event
    }

    private val _peers = MutableStateFlow<Map<String, Peer>>(emptyMap())
    val peers: StateFlow<Map<String, Peer>> = _peers.asStateFlow()

    private val _state = MutableStateFlow(PlaybackState.Idle)
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val _track = MutableStateFlow<Track?>(null)
    val track: StateFlow<Track?> = _track.asStateFlow()

    private val _queue = MutableStateFlow<List<Track>>(emptyList())
    val queue: StateFlow<List<Track>> = _queue.asStateFlow()

    /** The track currently being resolved/buffered before it starts, if any. */
    private val _loading = MutableStateFlow<Track?>(null)
    val loading: StateFlow<Track?> = _loading.asStateFlow()

    private val _events = MutableSharedFlow<Event>(extraBufferCapacity = 8)
    val events: SharedFlow<Event> = _events.asSharedFlow()

    /** When false, new phones are turned away (already-joined phones may still reconnect). */
    var acceptNewPeers = true

    private val knownPeerNames = HashSet<String>()
    private val linkQualities = HashMap<String, Int>()
    private var seq = 0L
    private var loadJob: Job? = null
    private var endHandledSeq = -1L
    private var job: Job? = null

    fun start() {
        check(job == null) { "already started" }
        local.follower.setClockOffset(0)
        job = scope.launch {
            launch(start = CoroutineStart.UNDISPATCHED) { link.events.collect(::onEvent) }
            launch {
                var elapsed = 0L
                while (isActive) {
                    delay(TICK_MS)
                    elapsed += TICK_MS
                    checkTrackEnd()
                    if (elapsed >= config.heartbeatMs) {
                        elapsed = 0
                        broadcast(StateUpdate(_state.value))
                    }
                }
            }
        }
    }

    fun stop() {
        broadcast(Bye)
        loadJob?.cancel()
        job?.cancel()
        job = null
    }

    // --- commands from the UI ----------------------------------------------------------------

    fun playNow(track: Track) {
        loadJob?.cancel()
        loadJob = scope.launch {
            _loading.value = track
            try {
                val resolved = local.resolve(track, allowAlternatives = true)
                val t = resolved.track
                _track.value = t
                val expected = _peers.value.keys.toSet()
                broadcast(LoadTrack(t))
                // Everyone holds at 0 until the ready barrier releases.
                publish(PlaybackState(++seq, t.key, playing = false, anchorHostNs = clock.nowNs(), anchorPositionMs = 0))
                local.prepare(resolved)
                withTimeoutOrNull(config.readyTimeoutMs) {
                    _peers.first { peers ->
                        expected.all { id ->
                            val p = peers[id]
                            p == null || p.readyTrackKey == t.key || p.failedTrackKey == t.key
                        }
                    }
                }
                // Phones that missed the barrier simply join the running timeline once ready.
                publish(PlaybackState(++seq, t.key, playing = true, anchorHostNs = clock.nowNs() + leadNs(), anchorPositionMs = 0))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _events.tryEmit(Event.LoadFailed(track, e.message))
            } finally {
                if (_loading.value == track) _loading.value = null
            }
        }
    }

    fun play() {
        val s = _state.value
        if (s.trackKey == null || s.playing || _loading.value != null) return
        publish(s.copy(seq = ++seq, playing = true, anchorHostNs = clock.nowNs() + leadNs()))
    }

    fun pause() {
        val s = _state.value
        if (!s.playing) return
        val at = clock.nowNs() + leadNs()
        publish(PlaybackState(++seq, s.trackKey, playing = false, anchorHostNs = at, anchorPositionMs = s.positionAt(at)))
    }

    fun seekTo(positionMs: Long) {
        val s = _state.value
        if (s.trackKey == null) return
        val duration = local.durationMs ?: _track.value?.durationMs?.takeIf { it > 0 } ?: Long.MAX_VALUE
        val pos = positionMs.coerceIn(0, duration)
        val now = clock.nowNs()
        publish(
            if (s.playing) {
                PlaybackState(++seq, s.trackKey, playing = true, anchorHostNs = now + leadNs(), anchorPositionMs = pos)
            } else {
                PlaybackState(++seq, s.trackKey, playing = false, anchorHostNs = now, anchorPositionMs = pos)
            },
        )
    }

    fun enqueue(track: Track) = _queue.update { it + track }

    fun removeFromQueue(index: Int) = _queue.update { q -> q.filterIndexed { i, _ -> i != index } }

    fun skipNext() {
        val next = _queue.value.firstOrNull() ?: return
        _queue.update { it.drop(1) }
        playNow(next)
    }

    /** Position on the shared timeline right now. */
    fun positionMs(): Long = _state.value.positionAt(clock.nowNs())

    /** Sends [message] to one phone (e.g. calibration plans and results). */
    fun sendTo(endpointId: String, message: Message) = link.send(endpointId, message)

    // --- protocol ----------------------------------------------------------------------------

    private fun onEvent(event: TransportEvent) {
        when (event) {
            is TransportEvent.Received -> ProtocolCodec.decode(event.bytes)?.let { handle(event.endpointId, it, event.receivedAtNs) }
            is TransportEvent.Disconnected -> {
                linkQualities -= event.endpointId
                _peers.update { it - event.endpointId }
            }
            is TransportEvent.BandwidthChanged -> {
                linkQualities[event.endpointId] = event.quality
                updatePeer(event.endpointId) { it.copy(linkQuality = event.quality) }
            }
            else -> Unit // peers are only added once they introduce themselves with Hello
        }
    }

    private fun handle(endpointId: String, message: Message, receivedAtNs: Long) {
        when (message) {
            // Answer time requests before anything else so t1..t2 stays tight.
            is TimeRequest -> link.send(endpointId, TimeResponse(message.id, message.t0, receivedAtNs, clock.nowNs()))
            is Hello -> onHello(endpointId, message)
            is TrackReady -> updatePeer(endpointId) { it.copy(readyTrackKey = message.trackKey) }
            is TrackFailed -> {
                updatePeer(endpointId) { it.copy(failedTrackKey = message.trackKey) }
                val name = _peers.value[endpointId]?.name ?: return
                _events.tryEmit(Event.PeerFailed(name, _track.value?.takeIf { it.key == message.trackKey }, message.reason))
            }
            is ClientStatus -> updatePeer(endpointId) {
                it.copy(status = message, readyTrackKey = if (message.ready) message.trackKey else it.readyTrackKey)
            }
            Bye -> {
                _peers.update { it - endpointId }
                link.disconnect(endpointId)
            }
            else -> Unit
        }
    }

    private fun onHello(endpointId: String, hello: Hello) {
        val reject = when {
            hello.protocol != PROTOCOL_VERSION -> RejectReason.PROTOCOL_MISMATCH
            !acceptNewPeers && hello.deviceName !in knownPeerNames -> RejectReason.GROUP_LOCKED
            else -> null
        }
        if (reject != null) {
            link.send(endpointId, Reject(reject))
            scope.launch {
                delay(REJECT_GRACE_MS) // let the Reject arrive before the link drops
                link.disconnect(endpointId)
            }
            return
        }
        knownPeerNames += hello.deviceName
        _peers.update { it + (endpointId to Peer(endpointId, hello.deviceName, linkQuality = linkQualities[endpointId])) }
        link.send(endpointId, Welcome(PROTOCOL_VERSION, hostName, sessionId, _track.value, _state.value))
    }

    private fun updatePeer(endpointId: String, change: (Peer) -> Peer) =
        _peers.update { peers -> peers[endpointId]?.let { peers + (endpointId to change(it)) } ?: peers }

    private fun publish(newState: PlaybackState) {
        _state.value = newState
        local.follower.updateState(newState)
        broadcast(StateUpdate(newState))
    }

    private fun broadcast(message: Message) = link.send(_peers.value.keys, message)

    private fun leadNs(): Long {
        val worstRtt = _peers.value.values.maxOfOrNull { it.status?.rttMs ?: 0 } ?: 0
        val ms = (2L * worstRtt + config.leadBaseMs).coerceIn(config.minLeadMs, config.maxLeadMs)
        return ms * NANOS_PER_MS
    }

    private fun checkTrackEnd() {
        val s = _state.value
        if (!s.playing || s.trackKey == null || endHandledSeq == s.seq || _loading.value != null) return
        val duration = local.durationMs ?: _track.value?.durationMs?.takeIf { it > 0 } ?: return
        if (s.positionAt(clock.nowNs()) < duration) return
        endHandledSeq = s.seq
        if (_queue.value.isNotEmpty()) {
            skipNext()
        } else {
            publish(PlaybackState(++seq, s.trackKey, playing = false, anchorHostNs = clock.nowNs(), anchorPositionMs = 0))
        }
    }

    private companion object {
        const val TICK_MS = 250L
        const val REJECT_GRACE_MS = 500L
    }
}
