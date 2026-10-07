package com.songsync.app.util

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.LocationManager
import android.os.Build
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat

/** Runtime permissions Nearby Connections needs, which differ per Android version. */
object NearbyPermissions {

    /**
     * What must be granted before advertising/discovering.
     *
     * Android 13+: Google documents only the Bluetooth + Nearby Wi-Fi permissions, but Play
     * services' discovery fails with MISSING_PERMISSION_ACCESS_FINE_LOCATION (8036) or
     * ..._COARSE_LOCATION (8034) unless precise location is granted (advertising works without it).
     * SongSync never reads the location; it is only there so "Join a group" can find hosts.
     */
    @SuppressLint("InlinedApi") // gated on [sdk]; permission names are just strings on older versions
    fun requiredFor(sdk: Int): List<String> = when {
        sdk >= Build.VERSION_CODES.TIRAMISU -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.NEARBY_WIFI_DEVICES,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        sdk >= Build.VERSION_CODES.S -> listOf(
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.BLUETOOTH_ADVERTISE,
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.ACCESS_FINE_LOCATION,
        )
        else -> listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    /**
     * What to ask the system for. Android 12+ ignores a request for precise location unless
     * approximate location is requested in the same call; the optional notification permission
     * rides along.
     */
    @SuppressLint("InlinedApi") // gated on [sdk]
    fun toRequestFor(sdk: Int): List<String> {
        val required = requiredFor(sdk)
        val coarseWithFine =
            if (Manifest.permission.ACCESS_FINE_LOCATION in required) listOf(Manifest.permission.ACCESS_COARSE_LOCATION) else emptyList()
        val notifications =
            if (sdk >= Build.VERSION_CODES.TIRAMISU) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList()
        return (required + coarseWithFine + notifications).distinct()
    }

    val required: List<String> get() = requiredFor(Build.VERSION.SDK_INT)
    val toRequest: List<String> get() = toRequestFor(Build.VERSION.SDK_INT)

    fun missing(context: Context): List<String> =
        required.filter { ContextCompat.checkSelfPermission(context, it) != PackageManager.PERMISSION_GRANTED }

    /** Up to Android 12L, Nearby only finds phones while Location is switched on. */
    fun locationServicesOff(context: Context): Boolean {
        if (Build.VERSION.SDK_INT > Build.VERSION_CODES.S_V2) return false
        val manager = context.getSystemService(LocationManager::class.java) ?: return false
        return !LocationManagerCompat.isLocationEnabled(manager)
    }
}
