package com.songsync.app.session

import android.content.Context
import androidx.annotation.StringRes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.songsync.app.R
import com.songsync.app.data.SettingsStore
import com.songsync.app.data.TrackResolver
import com.songsync.app.data.model.Track
import com.songsync.app.data.source.ClickTrack
import com.songsync.app.net.DiscoveredHost
import com.songsync.app.net.EndpointInfo
import com.songsync.app.net.NearbyTransport
import com.songsync.app.net.PROTOCOL_VERSION
import com.songsync.app.net.RejectReason
import com.songsync.app.net.TransportEvent
import com.songsync.app.playback.AudioRouteMonitor
import com.songsync.app.playback.DeviceLocalPlayback
import com.songsync.app.playback.MediaControls
import com.songsync.app.playback.NotificationContent
import com.songsync.app.playback.PlayerEngine
import com.songsync.app.playback.RouteLatencyProfile
import com.songsync.app.playback.SyncPlaybackService
import com.songsync.app.sync.ClientCoordinator
import com.songsync.app.sync.FollowerStatus
import com.songsync.app.sync.HostCoordinator
import com.songsync.app.sync.MonotonicClock
import com.songsync.app.sync.NANOS_PER_MS
import com.songsync.app.sync.PlaybackFollower
import com.songsync.app.sync.Scheduler
import com.songsync.app.sync.SyncConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.random.Random

/** A message for the user, resolved against resources by the UI. */
class UserMessage(@StringRes val text: Int, vararg val args: Any)

data class NowPlaying(
    val track: Track? = null,
    /** The shared timeline is running. */
    val playing: Boolean = false,
    /** Track currently being fetched/buffered, if any. */
    val loading: Track? = null,
)

enum class PeerState { READY, LOADING, FAILED, ON_HOLD }

data class PeerUi(
    val id: String,
    val name: String,
    val isSelf: Boolean,
    val state: PeerState,
    val syncErrorMs: Int?,
    val rttMs: Int?,
)

/** Numbers for the diagnostics panel. */
data class SyncStats(
    val follower: FollowerStatus,
    val medianRttMs: Double?,
    val minRttMs: Double?,
    val clockSamples: Int,
)

/**
 * Process-wide owner of the group session: Nearby discovery/advertising, the host or client
 * coordinator, reconnection, the foreground service, local holds and playback recovery.
 * Everything runs on the main thread.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SessionManager(
    private val context: Context,
    private val scope: CoroutineScope,
    private val transport: NearbyTransport,
    private val engine: PlayerEngine,
    resolver: TrackResolver,
    private val settings: SettingsStore,
    private val latency: RouteLatencyProfile,
    routes: AudioRouteMonitor,
    private val clock: MonotonicClock,
    scheduler: Scheduler,
    private val appVersion: String,
    private val syncConfig: SyncConfig = SyncConfig(),
) : MediaControls {

    sealed interface State {
        data object Idle : State
        data class Discovering(val hosts: List<DiscoveredHost>) : State
        data class Connecting(val host: DiscoveredHost) : State
        data class Hosting(val sessionId: String, val name: String) : State
        data class Joined(val host: DiscoveredHost) : State
        data class Reconnecting(val host: DiscoveredHost) : State
    }

    private val _state = MutableStateFlow<State>(State.Idle)
    val state: StateFlow<State> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<UserMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UserMessage> = _messages.asSharedFlow()

    private val follower = PlaybackFollower(engine, clock, scheduler, syncConfig, latency)
    private val local = DeviceLocalPlayback(engine, resolver, follower)

    private val _host = MutableStateFlow<HostCoordinator?>(null)
    val host: StateFlow<HostCoordinator?> = _host.asStateFlow()
    private val _client = MutableStateFlow<ClientCoordinator?>(null)

    private val _localHold = MutableStateFlow(false)
    /** This phone is paused on its own (user choice or audio focus), the group plays on. */
    val onHold: StateFlow<Boolean> = combine(_localHold, engine.systemHold) { user, system -> user || system }
        .stateIn(scope, SharingStarted.Eagerly, false)

    private val _stats = MutableStateFlow(SyncStats(follower.status, null, null, 0))
    val stats: StateFlow<SyncStats> = _stats.asStateFlow()

    private var sessionJob: Job? = null
    private var discoveryJob: Job? = null
    private var reconnectJob: Job? = null
    private var teardownJob: Job? = null
    private var reconnectTarget: String? = null
    private var deviceName = ""
    private var leaving = false

    init {
        engine.onStateChanged = { follower.onPlayerChanged() }
        scope.launch { routes.route.collect { latency.switchTo(it) } }
        scope.launch {
            onHold.collect { hold ->
                follower.setHold(hold)
                _client.value?.onHold = hold
            }
        }
        scope.launch { transport.events.collect(::onTransportEvent) }
    }

    // --- UI state ----------------------------------------------------------------------------

    val nowPlaying: StateFlow<NowPlaying> = combine(_host, _client) { h, c -> h to c }
        .flatMapLatest { (h, c) ->
            when {
                h != null -> combine(h.track, h.state, h.loading) { track, s, loading ->
                    NowPlaying(track, s.playing && s.trackKey != null, loading)
                }
                c != null -> combine(c.track, c.state, c.loadStatus) { track, s, load ->
                    NowPlaying(track, s.playing && s.trackKey != null, track.takeIf { load is ClientCoordinator.LoadStatus.Loading })
                }
                else -> flowOf(NowPlaying())
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, NowPlaying())

    val queue: StateFlow<List<Track>> = _host
        .flatMapLatest { it?.queue ?: flowOf(emptyList()) }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val peers: StateFlow<List<PeerUi>> = _host
        .flatMapLatest { h ->
            if (h == null) flowOf(emptyList())
            else combine(h.peers, h.track, onHold, _stats) { peers, track, hold, stats ->
                val self = PeerUi(
                    id = "self",
                    name = deviceName,
                    isSelf = true,
                    state = if (hold) PeerState.ON_HOLD else if (track != null && engine.loadedTrackKey != track.key) PeerState.LOADING else PeerState.READY,
                    syncErrorMs = stats.follower.errorMs?.toInt(),
                    rttMs = null,
                )
                listOf(self) + peers.values.sortedBy { it.name }.map { p ->
                    val key = track?.key
                    PeerUi(
                        id = p.endpointId,
                        name = p.name,
                        isSelf = false,
                        state = when {
                            p.status?.onHold == true -> PeerState.ON_HOLD
                            key != null && p.failedTrackKey == key -> PeerState.FAILED
                            key == null || p.readyTrackKey == key -> PeerState.READY
                            else -> PeerState.LOADING
                        },
                        syncErrorMs = p.status?.syncErrorMs,
                        rttMs = p.status?.rttMs,
                    )
                }
            }
        }
        .stateIn(scope, SharingStarted.Eagerly, emptyList())

    val notification: StateFlow<NotificationContent> = combine(_state, nowPlaying, queue, onHold) { state, playing, queue, hold ->
        val track = playing.track
        val status = when (state) {
            is State.Hosting -> context.getString(R.string.notification_hosting)
            is State.Joined -> context.getString(R.string.notification_joined, state.host.name)
            is State.Reconnecting -> context.getString(R.string.notification_reconnecting, state.host.name)
            else -> context.getString(R.string.app_name)
        }
        NotificationContent(
            title = track?.title ?: status,
            text = if (track != null) listOf(track.artist, status).filter { it.isNotBlank() }.joinToString(" · ") else "",
            artworkUrl = track?.artworkUrl?.takeIf { it.isNotBlank() },
            isPlaying = playing.playing && (state is State.Hosting || !hold),
            canSkip = state is State.Hosting && queue.isNotEmpty(),
        )
    }.stateIn(scope, SharingStarted.Eagerly, NotificationContent("", "", null, false, false))

    /** Where the shared timeline is right now. */
    fun positionMs(): Long = _host.value?.positionMs() ?: _client.value?.positionMs() ?: 0

    fun durationMs(): Long = engine.durationMs ?: nowPlaying.value.track?.durationMs ?: 0

    // --- host --------------------------------------------------------------------------------

    fun startHosting() {
        if (_state.value != State.Idle) return
        scope.launch {
            teardownJob?.join()
            leaving = false
            deviceName = settings.deviceName.first()
            val sessionId = randomSessionId()
            val coordinator = HostCoordinator(scope, transport, clock, local, deviceName, sessionId)
            _host.value = coordinator
            coordinator.start()
            _state.value = State.Hosting(sessionId, deviceName)
            startSession {
                coordinator.events.collect { event ->
                    when (event) {
                        is HostCoordinator.Event.LoadFailed ->
                            report(R.string.error_load_failed, event.track.title, event.reason.orEmpty())
                        is HostCoordinator.Event.PeerFailed -> report(R.string.error_peer_failed, event.peerName)
                    }
                }
            }
            try {
                transport.startAdvertising(EndpointInfo(deviceName, sessionId))
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportNearbyError(e)
                leave()
            }
        }
    }

    fun playNow(track: Track) {
        resumeLocally()
        _host.value?.playNow(track)
    }

    fun enqueue(track: Track) = _host.value?.enqueue(track)
    fun removeFromQueue(index: Int) = _host.value?.removeFromQueue(index)
    fun playSyncTest() = playNow(ClickTrack.TRACK)

    fun setGroupLocked(locked: Boolean) {
        _host.value?.acceptNewPeers = !locked
    }

    // --- client ------------------------------------------------------------------------------

    fun startDiscovery() {
        if (_state.value != State.Idle) return
        _state.value = State.Discovering(emptyList())
        discoveryJob = scope.launch {
            teardownJob?.join()
            launch {
                transport.hosts.collect { hosts ->
                    if (_state.value is State.Discovering) _state.value = State.Discovering(hosts.values.sortedBy { it.name })
                }
            }
            try {
                transport.startDiscovery()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportNearbyError(e)
                stopDiscovery()
            }
        }
    }

    fun stopDiscovery() {
        discoveryJob?.cancel()
        discoveryJob = null
        transport.stopAll()
        if (_state.value is State.Discovering || _state.value is State.Connecting) _state.value = State.Idle
    }

    fun join(host: DiscoveredHost) {
        if (_state.value !is State.Discovering) return
        if (host.protocol != PROTOCOL_VERSION) {
            report(R.string.error_version_mismatch, host.name)
            return
        }
        leaving = false
        discoveryJob?.cancel()
        transport.stopDiscovery() // discovering while connecting makes Nearby much less reliable
        _state.value = State.Connecting(host)
        scope.launch {
            deviceName = settings.deviceName.first()
            try {
                transport.connect(host.endpointId, deviceName)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                reportNearbyError(e)
                backToDiscovery()
            }
        }
    }

    // --- both --------------------------------------------------------------------------------

    fun leave() {
        if (_state.value == State.Idle) return
        leaving = true
        reconnectJob?.cancel()
        discoveryJob?.cancel()
        _host.value?.stop()
        _client.value?.leave()
        _host.value = null
        _client.value = null
        sessionJob?.cancel()
        sessionJob = null
        follower.reset()
        engine.unload()
        _localHold.value = false
        engine.clearSystemHold()
        SyncPlaybackService.stop(context)
        _state.value = State.Idle
        // Give the goodbye message a moment to leave before the links are torn down.
        teardownJob = scope.launch {
            delay(BYE_FLUSH_MS)
            transport.stopAll()
        }
    }

    /** Pause or resume only this phone (clients); the group keeps playing. */
    fun setLocalHold(hold: Boolean) {
        if (!hold) engine.clearSystemHold()
        _localHold.value = hold
    }

    private fun resumeLocally() {
        engine.clearSystemHold()
        _localHold.value = false
    }

    // --- MediaControls (notification, lock screen, headset buttons) --------------------------

    override val isPlaying: Boolean get() = notification.value.isPlaying
    override val canSeek: Boolean get() = _host.value?.track?.value != null
    override val canSkip: Boolean get() = _host.value?.queue?.value?.isNotEmpty() == true

    override fun setPlaying(playing: Boolean) {
        val host = _host.value
        if (host != null) {
            if (playing) {
                resumeLocally()
                host.play()
            } else {
                host.pause()
            }
        } else {
            setLocalHold(!playing)
        }
    }

    override fun seekTo(positionMs: Long) {
        _host.value?.seekTo(positionMs)
    }

    override fun skipNext() {
        resumeLocally()
        _host.value?.skipNext()
    }

    // --- internals ---------------------------------------------------------------------------

    /** Starts the per-session background work; [extra] runs alongside and stops with it. */
    private fun startSession(extra: suspend () -> Unit) {
        sessionJob?.cancel()
        sessionJob = scope.launch {
            launch { extra() }
            launch {
                while (isActive) {
                    follower.tick()
                    delay(syncConfig.tickMs)
                }
            }
            launch {
                while (isActive) {
                    val estimate = _client.value?.clockSync?.estimate
                    _stats.value = SyncStats(
                        follower = follower.status,
                        medianRttMs = estimate?.let { it.medianRttNs.toDouble() / NANOS_PER_MS },
                        minRttMs = estimate?.let { it.minRttNs.toDouble() / NANOS_PER_MS },
                        clockSamples = estimate?.samples ?: 0,
                    )
                    delay(STATS_INTERVAL_MS)
                }
            }
            launch { recoverFromPlaybackErrors() }
        }
        SyncPlaybackService.start(context)
    }

    /** A dropped connection mid-song should not end the party: retry with backoff. */
    private suspend fun recoverFromPlaybackErrors() {
        var attempts = 0
        var lastErrorAt = 0L
        engine.lastError.filterNotNull().collect { error ->
            if (engine.loadedTrackKey == null) return@collect // load failures are reported by the coordinators
            val now = clock.nowNs()
            if (now - lastErrorAt > ERROR_RESET_NS) attempts = 0
            lastErrorAt = now
            attempts++
            if (attempts > MAX_RECOVERY_ATTEMPTS) {
                report(R.string.error_playback, error.errorCodeName)
                return@collect
            }
            report(R.string.error_playback_retrying)
            delay(RECOVERY_BASE_DELAY_MS * attempts)
            engine.recover()
        }
    }

    private fun onTransportEvent(event: TransportEvent) {
        when (val state = _state.value) {
            is State.Connecting -> when {
                event is TransportEvent.Connected && event.endpointId == state.host.endpointId -> onJoined(state.host)
                event is TransportEvent.ConnectionFailed && event.endpointId == state.host.endpointId -> {
                    report(R.string.error_connect_failed, state.host.name)
                    backToDiscovery()
                }
            }
            is State.Reconnecting ->
                if (event is TransportEvent.Connected && event.endpointId == reconnectTarget) {
                    onJoined(state.host.copy(endpointId = event.endpointId))
                }
            is State.Joined ->
                if (event is TransportEvent.Disconnected && event.endpointId == state.host.endpointId && !leaving) {
                    startReconnect(state.host)
                }
            else -> Unit
        }
    }

    private fun onJoined(host: DiscoveredHost) {
        reconnectJob?.cancel()
        reconnectJob = null
        val client = _client.value ?: ClientCoordinator(scope, transport, clock, local, deviceName, appVersion).also { c ->
            _client.value = c
            c.onHold = onHold.value
        }
        if (sessionJob == null) {
            startSession {
                client.events.collect { event ->
                    when (event) {
                        is ClientCoordinator.Event.Rejected -> report(
                            if (event.reason == RejectReason.GROUP_LOCKED) R.string.error_group_locked else R.string.error_version_mismatch,
                            host.name,
                        )
                        ClientCoordinator.Event.HostLeft -> report(R.string.message_host_left, host.name)
                    }
                    leave()
                }
            }
        }
        client.attach(host.endpointId)
        _state.value = State.Joined(host)
    }

    private fun startReconnect(host: DiscoveredHost) {
        _client.value?.detach() // the follower keeps playing on the last known timeline
        _state.value = State.Reconnecting(host)
        reconnectJob?.cancel()
        reconnectJob = scope.launch {
            val reconnected = withTimeoutOrNull(RECONNECT_TIMEOUT_MS) {
                var joined = false
                while (!joined) {
                    runCatching { transport.startDiscovery() }
                    val found = transport.hosts.first { hosts -> hosts.values.any { it.sessionId == host.sessionId } }
                        .values.first { it.sessionId == host.sessionId }
                    transport.stopDiscovery()
                    reconnectTarget = found.endpointId
                    joined = runCatching { transport.connect(found.endpointId, deviceName) }.isSuccess &&
                        withTimeoutOrNull(CONNECT_ATTEMPT_TIMEOUT_MS) { _state.first { it is State.Joined } } != null
                    if (!joined) delay(RECONNECT_RETRY_DELAY_MS)
                }
                true
            }
            if (reconnected != true && _state.value is State.Reconnecting) {
                report(R.string.error_lost_host, host.name)
                leave()
            }
        }
    }

    private fun backToDiscovery() {
        _state.value = State.Idle
        transport.stopAll()
        startDiscovery()
    }

    private fun report(@StringRes text: Int, vararg args: Any) {
        _messages.tryEmit(UserMessage(text, *args))
    }

    private fun reportNearbyError(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        when (code) {
            @Suppress("DEPRECATION") // still returned by older Play services versions
            ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON,
            -> report(R.string.error_location_off)
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
            -> report(R.string.error_permissions)
            ConnectionsStatusCodes.STATUS_RADIO_ERROR -> report(R.string.error_radio)
            ConnectionsStatusCodes.API_CONNECTION_FAILED_ALREADY_IN_USE -> report(R.string.error_nearby_busy)
            null -> report(R.string.error_nearby, e.message ?: e.javaClass.simpleName)
            else -> report(R.string.error_nearby, ConnectionsStatusCodes.getStatusCodeString(code))
        }
    }

    private fun randomSessionId(): String {
        val alphabet = "abcdefghjkmnpqrstuvwxyz23456789"
        return String(CharArray(6) { alphabet[Random.nextInt(alphabet.length)] })
    }

    private companion object {
        const val BYE_FLUSH_MS = 300L
        const val STATS_INTERVAL_MS = 500L
        const val RECONNECT_TIMEOUT_MS = 60_000L
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 10_000L
        const val RECONNECT_RETRY_DELAY_MS = 2_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val RECOVERY_BASE_DELAY_MS = 2_000L
        const val ERROR_RESET_NS = 60_000_000_000L
    }
}
