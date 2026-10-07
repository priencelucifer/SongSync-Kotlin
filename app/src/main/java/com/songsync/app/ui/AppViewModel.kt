package com.songsync.app.ui

import android.Manifest
import android.app.Application
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.songsync.app.SongSyncApp
import com.songsync.app.data.model.Track
import com.songsync.app.data.model.TrackSource
import com.songsync.app.net.DiscoveredHost
import com.songsync.app.session.SessionManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class SearchUiState(
    val results: List<Track> = emptyList(),
    val loading: Boolean = false,
    val failed: Boolean = false,
)

@OptIn(FlowPreview::class)
class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val graph = (app as SongSyncApp).graph
    private val session: SessionManager = graph.sessionManager
    private val settings = graph.settings

    val sessionState = session.state
    val nowPlaying = session.nowPlaying
    val queue = session.queue
    val peers = session.peers
    val onHold = session.onHold
    val stats = session.stats
    val calibrationRun = session.calibration
    val syncLog = session.syncLog
    val messages = session.messages
    val calibration = graph.latency.calibration
    val audioRoute = graph.audioRoute

    val deviceName: StateFlow<String> = settings.deviceName.stateIn(viewModelScope, SharingStarted.Eagerly, "")
    val diagnostics: StateFlow<Boolean> = settings.diagnostics.stateIn(viewModelScope, SharingStarted.Eagerly, false)
    val maxBitrateKbps: StateFlow<Int> = settings.maxBitrateKbps.stateIn(viewModelScope, SharingStarted.Eagerly, 320)
    val searchSource: StateFlow<TrackSource> =
        settings.searchSource.stateIn(viewModelScope, SharingStarted.Eagerly, TrackSource.JIOSAAVN)

    private val _groupLocked = MutableStateFlow(false)
    val groupLocked: StateFlow<Boolean> = _groupLocked.asStateFlow()

    private val query = MutableStateFlow("")
    private val retries = MutableStateFlow(0)
    private val _search = MutableStateFlow(SearchUiState())
    val search: StateFlow<SearchUiState> = _search.asStateFlow()

    init {
        viewModelScope.launch {
            combine(query, searchSource, retries) { q, source, retry -> Triple(q.trim(), source, retry) }
                .debounce { (q, _, _) -> if (q.isEmpty()) 0 else SEARCH_DEBOUNCE_MS }
                .distinctUntilChanged()
                .collectLatest { (q, source, _) ->
                    if (q.isEmpty()) {
                        _search.value = SearchUiState()
                        return@collectLatest
                    }
                    // Keep showing the previous results while the new ones load (no flashing).
                    _search.update { it.copy(loading = true, failed = false) }
                    try {
                        val results = graph.search.search(source, q)
                        _search.value = SearchUiState(results = results)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (_: Exception) {
                        _search.update { it.copy(loading = false, failed = true) }
                    }
                }
        }
    }

    // --- search ------------------------------------------------------------------------------

    fun onQueryChange(text: String) {
        query.value = text
    }

    fun setSearchSource(source: TrackSource) {
        viewModelScope.launch { settings.setSearchSource(source) }
    }

    fun retrySearch() = retries.update { it + 1 }

    // --- session -----------------------------------------------------------------------------

    fun startHosting() {
        _groupLocked.value = false
        session.startHosting()
    }

    fun startDiscovery() = session.startDiscovery()
    fun stopDiscovery() = session.stopDiscovery()
    fun join(host: DiscoveredHost) = session.join(host)
    fun leave() = session.leave()

    fun playNow(track: Track) = session.playNow(track)
    fun enqueue(track: Track) = session.enqueue(track)
    fun removeFromQueue(index: Int) = session.removeFromQueue(index)
    fun playSyncTest() = session.playSyncTest()
    fun setPlaying(playing: Boolean) = session.setPlaying(playing)
    fun seekTo(positionMs: Long) = session.seekTo(positionMs)
    fun skipNext() = session.skipNext()
    fun setLocalHold(hold: Boolean) = session.setLocalHold(hold)

    fun setGroupLocked(locked: Boolean) {
        _groupLocked.value = locked
        session.setGroupLocked(locked)
    }

    fun positionMs(): Long = session.positionMs()
    fun durationMs(): Long = session.durationMs()

    // --- echo calibration --------------------------------------------------------------------

    /**
     * Starts automatic echo calibration, or with [measureOnly] a sync check that changes nothing,
     * if the microphone permission is granted (the UI asks for it).
     */
    fun autoCalibrate(measureOnly: Boolean = false) {
        val granted = ContextCompat.checkSelfPermission(getApplication(), Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED
        if (granted) session.autoCalibrate(measureOnly)
    }

    fun cancelCalibration() = session.cancelCalibration()
    fun dismissCalibration() = session.dismissCalibration()

    // --- settings ----------------------------------------------------------------------------

    fun setCalibration(ms: Double) = graph.latency.setCalibration(ms)
    fun setDeviceName(name: String) = viewModelScope.launch { settings.setDeviceName(name) }
    fun setMaxBitrate(kbps: Int) = viewModelScope.launch { settings.setMaxBitrateKbps(kbps) }
    fun setDiagnostics(enabled: Boolean) = viewModelScope.launch { settings.setDiagnostics(enabled) }
    fun resetLearnedLatency() = viewModelScope.launch { graph.latency.resetAll() }

    private companion object {
        const val SEARCH_DEBOUNCE_MS = 350L
    }
}
