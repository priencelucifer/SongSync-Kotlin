package com.songsync.app.session

import android.Manifest
import android.content.Context
import androidx.annotation.RequiresPermission
import com.songsync.app.calibration.CalibrationAnalyzer
import com.songsync.app.calibration.CalibrationSignal
import com.songsync.app.calibration.MicRecorder
import com.songsync.app.net.CalibrationPlan
import com.songsync.app.net.CalibrationResult
import androidx.annotation.StringRes
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.songsync.app.R
import com.songsync.app.data.SettingsStore
import com.songsync.app.data.TrackResolver
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.data.source.ClickTrack
import com.songsync.app.net.DiscoveredHost
import com.songsync.app.net.EndpointInfo
import com.songsync.app.net.NearbyTransport
import com.songsync.app.net.PROTOCOL_VERSION
import com.songsync.app.net.RejectReason
import com.songsync.app.net.TransportEvent
import com.songsync.app.net.differentFile
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
import com.songsync.app.sync.SyncEvent
import com.songsync.app.sync.SyncConfig
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChangedBy
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.roundToInt
import kotlin.random.Random

/** A message for the user, resolved against resources by the UI. */
class UserMessage(@StringRes val text: Int, vararg val args: Any, val action: Action? = null) {
    /** A button on the message that takes the user where they can fix the problem. */
    enum class Action { APP_SETTINGS, LOCATION_SETTINGS }
}

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
    /** Nearby link quality (1 low/Bluetooth .. 3 high), if known. */
    val linkQuality: Int? = null,
    /** True when this phone decodes a different file of the song than the host (constant offset risk). */
    val differentFile: Boolean = false,
)

/** Progress and outcome of an automatic echo calibration (host). */
sealed interface CalibrationUi {
    data object Idle : CalibrationUi
    data class Running(val stage: Stage) : CalibrationUi
    /** With [measureOnly], outcomes hold each phone's lateness vs this phone instead of a correction. */
    data class Done(val outcomes: List<CalibrationOutcome>, val measureOnly: Boolean = false) : CalibrationUi
    data class Failed(@StringRes val reason: Int) : CalibrationUi

    enum class Stage { STARTING, LISTENING, ANALYZING }
}

/** [correctionMs] is what was added to that phone's calibration, or null if it was not heard. */
data class CalibrationOutcome(val name: String, val isSelf: Boolean, val correctionMs: Double?)

/** Numbers for the diagnostics panel. */
data class SyncStats(
    val follower: FollowerStatus,
    val medianRttMs: Double?,
    val minRttMs: Double?,
    val clockSamples: Int,
    val linkQuality: Int? = null,
    val p90RttMs: Double? = null,
    /** How much the best clock samples disagree (MAD). */
    val clockSpreadMs: Double? = null,
    /** Seconds since the newest clock sample arrived. */
    val clockAgeS: Double? = null,
    val clockSkewPpm: Double? = null,
    /** What this phone is decoding ([AudioFormatInfo.summary]). */
    val format: String? = null,
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
    private val recorder: MicRecorder,
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

    private val _syncLog = MutableStateFlow<List<String>>(emptyList())
    /** Recent sync events, newest first, for the diagnostics panel. */
    val syncLog: StateFlow<List<String>> = _syncLog.asStateFlow()
    private val logTime = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.US)

    private val _calibration = MutableStateFlow<CalibrationUi>(CalibrationUi.Idle)
    val calibration: StateFlow<CalibrationUi> = _calibration.asStateFlow()
    private var calibrationJob: Job? = null
    /** Track key of the calibration the host is running (the host's own slot is 0). */
    private var hostCalibrationKey: String? = null
    private val calibrating get() = calibrationJob?.isActive == true

    private var sessionJob: Job? = null
    private var discoveryJob: Job? = null
    private var reconnectJob: Job? = null
    private var teardownJob: Job? = null
    private var reconnectTarget: String? = null
    private var deviceName = ""
    private var leaving = false

    init {
        engine.onStateChanged = { follower.onPlayerChanged() }
        follower.onEvent = { event -> log(describe(event)) }
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
            else combine(h.peers, h.track, onHold, _stats, engine.audioFormat) { peers, track, hold, stats, hostFormat ->
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
                        linkQuality = p.linkQuality,
                        differentFile = differentFile(hostFormat, p.status?.format, key),
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
        startForegroundService()
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
        if (blockedByCalibration()) return
        resumeLocally()
        _host.value?.playNow(track)
    }

    /**
     * Automatic echo calibration (host). Every phone plays the calibration track in sync but is
     * only audible during its own slot; this phone's microphone records the whole run and the
     * measured lateness of each phone becomes its correction. The UI checks RECORD_AUDIO first.
     *
     * With [measureOnly] ("Check sync") nothing is corrected: the result is how late each phone
     * is heard relative to this one, with the current calibration in effect. Afterwards the song
     * that was loaded is put back, paused where it was.
     */
    @RequiresPermission(Manifest.permission.RECORD_AUDIO)
    fun autoCalibrate(measureOnly: Boolean = false) {
        val host = _host.value ?: return
        if (calibrating) return
        val previousTrack = host.track.value?.takeIf { it.source != TrackSource.CLICK_TEST || it == ClickTrack.TRACK }
        val previousPositionMs = host.positionMs()
        calibrationJob = scope.launch {
            _calibration.value = CalibrationUi.Running(CalibrationUi.Stage.STARTING)
            val peers = host.peers.value.values.sortedBy { it.name }.take(CalibrationSignal.MAX_SLOTS - 1)
            val slots = peers.size + 1
            val track = CalibrationSignal.track(slots)
            hostCalibrationKey = track.key
            peers.forEachIndexed { index, peer -> host.sendTo(peer.endpointId, CalibrationPlan(track.key, index + 1)) }

            val recording = try {
                recorder.start(CalibrationSignal.durationMs(slots) + CALIBRATION_START_TIMEOUT_MS + 2_000)
            } catch (e: CancellationException) {
                throw e
            } catch (_: Exception) {
                _calibration.value = CalibrationUi.Failed(R.string.calibration_failed_mic)
                return@launch
            }
            var finished = false
            try {
                resumeLocally()
                host.playNow(track)
                val timeline = withTimeoutOrNull(CALIBRATION_START_TIMEOUT_MS) {
                    host.state.first { it.trackKey == track.key && it.playing }
                }
                if (timeline == null) {
                    _calibration.value = CalibrationUi.Failed(R.string.calibration_failed_start)
                    return@launch
                }
                _calibration.value = CalibrationUi.Running(CalibrationUi.Stage.LISTENING)
                val lastSoundMs = CalibrationSignal.chirpStartMs(slots - 1, CalibrationSignal.CHIRPS_PER_SLOT - 1) +
                    CalibrationSignal.CHIRP_MS + CALIBRATION_ECHO_MARGIN_MS
                val endNs = timeline.anchorHostNs + (lastSoundMs - timeline.anchorPositionMs) * NANOS_PER_MS
                delay(((endNs - clock.nowNs()) / NANOS_PER_MS).coerceAtLeast(0))
                val audio = withContext(Dispatchers.IO) { recording.stop() }
                finished = true

                _calibration.value = CalibrationUi.Running(CalibrationUi.Stage.ANALYZING)
                if (measureOnly) {
                    val measured = withContext(Dispatchers.Default) { CalibrationAnalyzer.analyze(audio, timeline, slots) }
                    val lateness = measured.latenessVsHost()
                    val outcomes = listOf(CalibrationOutcome(deviceName, isSelf = true, lateness[0])) +
                        peers.mapIndexed { index, peer -> CalibrationOutcome(peer.name, isSelf = false, lateness[index + 1]) }
                    val heard = outcomes.mapNotNull { it.correctionMs }
                    if (heard.size < 2) {
                        _calibration.value = CalibrationUi.Failed(R.string.calibration_failed_nothing)
                        return@launch
                    }
                    log(
                        "sync check: " + outcomes.joinToString { o -> "${o.name} ${o.correctionMs?.let { "%+.1f ms".format(it) } ?: "not heard"}" } +
                            "; spread %.1f ms".format(heard.max() - heard.min()),
                    )
                    _calibration.value = CalibrationUi.Done(outcomes, measureOnly = true)
                    return@launch
                }
                // A phone still mid-way through a timing correction would have that baked in;
                // its own reported sync error (positive = ahead, i.e. sounding early) is removed.
                val trackingErrorMs = mapOf(0 to (follower.status.errorMs ?: 0.0)) +
                    peers.mapIndexed { index, peer ->
                        (index + 1) to (host.peers.value[peer.endpointId]?.status?.syncErrorMs?.toDouble() ?: 0.0)
                    }
                val measured = withContext(Dispatchers.Default) { CalibrationAnalyzer.analyze(audio, timeline, slots) }
                val result = measured.copy(
                    phones = measured.phones.map { it.copy(lateMs = it.lateMs?.plus(trackingErrorMs[it.slot] ?: 0.0)) },
                )
                val corrections = result.corrections(RouteLatencyProfile.MAX_CALIBRATION_MS)
                if (corrections.isEmpty()) {
                    _calibration.value = CalibrationUi.Failed(R.string.calibration_failed_nothing)
                    return@launch
                }
                corrections[0]?.let { latency.setCalibration(latency.calibrationMs + it) }
                peers.forEachIndexed { index, peer -> host.sendTo(peer.endpointId, CalibrationResult(corrections[index + 1])) }
                val outcomes = listOf(CalibrationOutcome(deviceName, isSelf = true, corrections[0])) +
                    peers.mapIndexed { index, peer -> CalibrationOutcome(peer.name, isSelf = false, corrections[index + 1]) }
                log("echo calibration: " + outcomes.joinToString { o -> "${o.name} ${o.correctionMs?.let { "%+.1f ms".format(it) } ?: "not heard"}" })
                _calibration.value = CalibrationUi.Done(outcomes)
            } finally {
                withContext(NonCancellable) {
                    if (!finished) withContext(Dispatchers.IO) { recording.stop() }
                    if (host.state.value.trackKey == track.key) {
                        if (previousTrack != null) {
                            host.playNow(previousTrack, previousPositionMs, autoPlay = false)
                        } else {
                            host.pause()
                        }
                    }
                }
            }
        }
    }

    fun cancelCalibration() {
        calibrationJob?.cancel()
        _calibration.value = CalibrationUi.Idle
    }

    fun dismissCalibration() {
        if (!calibrating) _calibration.value = CalibrationUi.Idle
    }

    private fun blockedByCalibration(): Boolean {
        if (calibrating) report(R.string.calibration_busy)
        return calibrating
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
        if (_state.value is State.Connecting) SyncPlaybackService.stop(context)
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
        startForegroundService()
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
        calibrationJob?.cancel()
        _calibration.value = CalibrationUi.Idle
        hostCalibrationKey = null
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
        _syncLog.value = emptyList()
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
            if (blockedByCalibration()) return
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
        if (blockedByCalibration()) return
        _host.value?.seekTo(positionMs)
    }

    override fun skipNext() {
        if (blockedByCalibration()) return
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
                        linkQuality = _client.value?.linkQuality?.value,
                        p90RttMs = estimate?.let { it.p90RttNs.toDouble() / NANOS_PER_MS },
                        clockSpreadMs = estimate?.let { it.offsetSpreadNs.toDouble() / NANOS_PER_MS },
                        clockAgeS = estimate?.let { (clock.nowNs() - it.newestSampleAtNs) / 1e9 },
                        clockSkewPpm = estimate?.skewPpm,
                        format = engine.audioFormat.value?.summary(),
                    )
                    delay(STATS_INTERVAL_MS)
                }
            }
            launch { recoverFromPlaybackErrors() }
            launch { gateCalibrationPlayback() }
            launch {
                engine.audioFormat.filterNotNull().distinctUntilChangedBy { it.copy(decoder = null) }
                    .collect { log("format ${it.summary()}") }
            }
        }
    }

    /**
     * While a calibration track plays, this phone is only audible during its own slot, and timing
     * corrections freeze once the measured part begins so nothing moves under the microphone.
     */
    private suspend fun gateCalibrationPlayback() {
        var gated = false
        while (true) {
            val key = engine.loadedTrackKey
            if (CalibrationSignal.isCalibrationKey(key)) {
                gated = true
                val slot = if (_host.value != null) {
                    0.takeIf { key == hostCalibrationKey }
                } else {
                    _client.value?.calibrationSlot?.value?.takeIf { it.first == key }?.second
                }
                val position = engine.positionMs
                engine.setVolume(if (slot != null && CalibrationSignal.isInSlot(slot, position)) 1f else 0f)
                follower.correctionsFrozen = position >= CalibrationSignal.LEAD_IN_MS - CALIBRATION_FREEZE_LEAD_MS
                delay(CALIBRATION_GATE_INTERVAL_MS)
            } else {
                if (gated) {
                    engine.setVolume(1f)
                    follower.correctionsFrozen = false
                    gated = false
                }
                delay(STATS_INTERVAL_MS)
            }
        }
    }

    /**
     * Must run while handling the user's tap: Android 12+ refuses to start foreground services
     * from the background, and a client only finishes connecting a few seconds later.
     */
    private fun startForegroundService() {
        try {
            SyncPlaybackService.start(context)
        } catch (_: IllegalStateException) { // ForegroundServiceStartNotAllowedException
            report(R.string.error_background_start)
        }
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
        if (event is TransportEvent.BandwidthChanged) log("link quality ${event.quality} (1 = Bluetooth, 3 = fast Wi-Fi)")
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
                        is ClientCoordinator.Event.Rejected -> {
                            report(
                                if (event.reason == RejectReason.GROUP_LOCKED) R.string.error_group_locked else R.string.error_version_mismatch,
                                host.name,
                            )
                            leave()
                        }
                        ClientCoordinator.Event.HostLeft -> {
                            report(R.string.message_host_left, host.name)
                            leave()
                        }
                        is ClientCoordinator.Event.Calibrated -> {
                            val correction = event.correctionMs
                            if (correction != null) {
                                latency.setCalibration(latency.calibrationMs + correction)
                                log("echo calibration %+.1f ms (from host)".format(correction))
                                report(R.string.calibration_applied, correction.roundToInt())
                            } else {
                                report(R.string.calibration_not_heard_here)
                            }
                        }
                    }
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
        SyncPlaybackService.stop(context)
        _state.value = State.Idle
        transport.stopAll()
        startDiscovery()
    }

    private fun log(line: String) {
        _syncLog.value = (listOf("${logTime.format(java.util.Date())}  $line") + _syncLog.value).take(MAX_LOG_LINES)
    }

    private fun describe(event: SyncEvent): String = when (event.type) {
        SyncEvent.Type.START -> "start (latency %.0f ms)".format(event.value)
        SyncEvent.Type.START_ERROR -> "start error %+.1f ms".format(event.value)
        SyncEvent.Type.HARD_RESYNC -> "re-sync (error %+.0f ms)".format(event.value)
        SyncEvent.Type.REBUFFER -> "stalled: waiting for data"
        SyncEvent.Type.CATCHING_UP -> "waiting for download to get ahead"
        SyncEvent.Type.SPEED -> "speed %.4f".format(event.value)
        SyncEvent.Type.OFFSET_JUMP -> "clock offset jump %+.0f ms".format(event.value)
        SyncEvent.Type.INTERRUPTED -> "interrupted by another app"
    }

    private fun report(@StringRes text: Int, vararg args: Any) {
        _messages.tryEmit(UserMessage(text, *args))
    }

    private fun reportWithAction(action: UserMessage.Action, @StringRes text: Int, vararg args: Any) {
        _messages.tryEmit(UserMessage(text, *args, action = action))
    }

    private fun reportNearbyError(e: Exception) {
        val code = (e as? ApiException)?.statusCode
        when (code) {
            @Suppress("DEPRECATION") // still returned by older Play services versions
            ConnectionsStatusCodes.MISSING_SETTING_LOCATION_MUST_BE_ON,
            -> reportWithAction(UserMessage.Action.LOCATION_SETTINGS, R.string.error_location_off)
            // The code is included so a report from a user pinpoints exactly what Nearby rejected.
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_COARSE_LOCATION,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_FINE_LOCATION,
            -> reportWithAction(UserMessage.Action.APP_SETTINGS, R.string.error_permission_location, code)
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADMIN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_SCAN,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_ADVERTISE,
            ConnectionsStatusCodes.MISSING_PERMISSION_BLUETOOTH_CONNECT,
            ConnectionsStatusCodes.MISSING_PERMISSION_NEARBY_WIFI_DEVICES,
            ConnectionsStatusCodes.MISSING_PERMISSION_ACCESS_WIFI_STATE,
            ConnectionsStatusCodes.MISSING_PERMISSION_CHANGE_WIFI_STATE,
            -> reportWithAction(UserMessage.Action.APP_SETTINGS, R.string.error_permissions, code)
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
        const val CALIBRATION_START_TIMEOUT_MS = 30_000L
        const val CALIBRATION_ECHO_MARGIN_MS = 400L
        const val CALIBRATION_FREEZE_LEAD_MS = 1_000L
        const val CALIBRATION_GATE_INTERVAL_MS = 20L
        const val MAX_LOG_LINES = 12
        const val STATS_INTERVAL_MS = 500L
        const val RECONNECT_TIMEOUT_MS = 60_000L
        const val CONNECT_ATTEMPT_TIMEOUT_MS = 10_000L
        const val RECONNECT_RETRY_DELAY_MS = 2_000L
        const val MAX_RECOVERY_ATTEMPTS = 3
        const val RECOVERY_BASE_DELAY_MS = 2_000L
        const val ERROR_RESET_NS = 60_000_000_000L
    }
}
