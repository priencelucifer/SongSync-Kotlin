package com.songsync.app.playback

import com.songsync.app.data.RouteLatency
import com.songsync.app.data.SettingsStore
import com.songsync.app.sync.DEFAULT_START_LATENCY_MS
import com.songsync.app.sync.LatencyProfile
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * [LatencyProfile] for the current audio output, persisted so a phone is accurate from its
 * very first start in the next session (and per speaker / Bluetooth device).
 */
class RouteLatencyProfile(
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) : LatencyProfile {

    private var routeKey = "speaker"
    private var loadedKey: String? = null
    private var saveJob: Job? = null

    private val _calibration = MutableStateFlow(0.0)
    /** For the UI slider. */
    val calibration: StateFlow<Double> = _calibration.asStateFlow()

    override var startLatencyMs: Double = DEFAULT_START_LATENCY_MS
        set(value) {
            field = value
            scheduleSave()
        }

    override val calibrationMs: Double get() = _calibration.value

    fun setCalibration(ms: Double) {
        _calibration.value = ms.coerceIn(-MAX_CALIBRATION_MS, MAX_CALIBRATION_MS)
        scheduleSave()
    }

    suspend fun switchTo(route: AudioRoute) {
        if (route.key == loadedKey) return
        // Flush pending changes for the previous output before its values are replaced.
        if (loadedKey != null && saveJob != null) {
            saveJob?.cancel()
            save(routeKey)
        }
        routeKey = route.key
        val saved = settings.routeLatency(route.key)
        startLatencyMs = saved.startLatencyMs ?: DEFAULT_START_LATENCY_MS
        _calibration.value = saved.calibrationMs
        saveJob?.cancel() // loading values is not a change worth saving
        saveJob = null
        loadedKey = route.key
    }

    suspend fun resetAll() {
        settings.resetLatencies()
        startLatencyMs = DEFAULT_START_LATENCY_MS
        _calibration.value = 0.0
        saveJob?.cancel()
        saveJob = null
    }

    private fun scheduleSave() {
        val key = routeKey
        saveJob?.cancel()
        saveJob = scope.launch {
            delay(SAVE_DEBOUNCE_MS)
            save(key)
            saveJob = null
        }
    }

    private suspend fun save(key: String) =
        settings.saveRouteLatency(key, RouteLatency(startLatencyMs, _calibration.value))

    companion object {
        const val MAX_CALIBRATION_MS = 300.0
        private const val SAVE_DEBOUNCE_MS = 2_000L
    }
}
