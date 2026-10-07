package com.songsync.app.data

import android.content.Context
import android.os.Build
import android.provider.Settings
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.doublePreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.songsync.app.data.model.TrackSource
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Learned and manual latency for one audio output (speaker, wired, a Bluetooth device...). */
data class RouteLatency(val startLatencyMs: Double?, val calibrationMs: Double)

class SettingsStore(context: Context) {
    private val store = context.applicationContext.dataStore

    /** What other phones see: the user-set device name when there is one, else the model. */
    private val defaultName: String = runCatching {
        Settings.Global.getString(context.contentResolver, Settings.Global.DEVICE_NAME)
    }.getOrNull()?.takeIf { it.isNotBlank() } ?: Build.MODEL

    val deviceName: Flow<String> = store.data.map { it[DEVICE_NAME]?.takeIf(String::isNotBlank) ?: defaultName }
    val maxBitrateKbps: Flow<Int> = store.data.map { it[MAX_BITRATE] ?: 320 }
    val diagnostics: Flow<Boolean> = store.data.map { it[DIAGNOSTICS] ?: false }
    val searchSource: Flow<TrackSource> = store.data.map { prefs ->
        prefs[SEARCH_SOURCE]?.let { runCatching { TrackSource.valueOf(it) }.getOrNull() } ?: TrackSource.JIOSAAVN
    }

    suspend fun setDeviceName(name: String) = store.edit { it[DEVICE_NAME] = name.trim().take(MAX_NAME_LENGTH) }
    suspend fun setMaxBitrateKbps(kbps: Int) = store.edit { it[MAX_BITRATE] = kbps }
    suspend fun setDiagnostics(enabled: Boolean) = store.edit { it[DIAGNOSTICS] = enabled }
    suspend fun setSearchSource(source: TrackSource) = store.edit { it[SEARCH_SOURCE] = source.name }

    suspend fun routeLatency(routeKey: String): RouteLatency {
        val prefs = store.data.first()
        return RouteLatency(prefs[startKey(routeKey)], prefs[calibrationKey(routeKey)] ?: 0.0)
    }

    suspend fun saveRouteLatency(routeKey: String, latency: RouteLatency) = store.edit { prefs ->
        latency.startLatencyMs?.let { prefs[startKey(routeKey)] = it }
        prefs[calibrationKey(routeKey)] = latency.calibrationMs
    }

    /** Forget everything learned about every audio output (e.g. after a system update). */
    suspend fun resetLatencies() = store.edit { prefs ->
        prefs.asMap().keys
            .filter { it.name.startsWith(LEGACY_START_PREFIX) || it.name.startsWith(CALIBRATION_PREFIX) }
            .forEach { prefs.remove(it) }
    }

    private fun startKey(route: String) = doublePreferencesKey("$START_PREFIX$route")
    private fun calibrationKey(route: String) = doublePreferencesKey("$CALIBRATION_PREFIX$route")

    companion object {
        const val MAX_NAME_LENGTH = 24
        // v2: values learned by 2.0.0-2.0.2 could be skewed by a re-sync loop, so start fresh.
        private const val START_PREFIX = "start_latency_v2:"
        private const val LEGACY_START_PREFIX = "start_latency"
        private const val CALIBRATION_PREFIX = "calibration:"
        private val DEVICE_NAME = stringPreferencesKey("device_name")
        private val MAX_BITRATE = intPreferencesKey("max_bitrate_kbps")
        private val DIAGNOSTICS = booleanPreferencesKey("diagnostics")
        private val SEARCH_SOURCE = stringPreferencesKey("search_source")
    }
}
