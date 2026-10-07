package com.songsync.app.util

import android.Manifest.permission.ACCESS_COARSE_LOCATION
import android.Manifest.permission.ACCESS_FINE_LOCATION
import android.Manifest.permission.BLUETOOTH_SCAN
import android.Manifest.permission.NEARBY_WIFI_DEVICES
import android.Manifest.permission.POST_NOTIFICATIONS
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class NearbyPermissionsTest {

    @Test
    fun `Android 13 and later need approximate location for discovery`() {
        for (sdk in 33..37) {
            assertThat(NearbyPermissions.requiredFor(sdk)).containsAtLeast(BLUETOOTH_SCAN, NEARBY_WIFI_DEVICES, ACCESS_COARSE_LOCATION)
            assertThat(NearbyPermissions.requiredFor(sdk)).doesNotContain(ACCESS_FINE_LOCATION)
            assertThat(NearbyPermissions.toRequestFor(sdk)).contains(POST_NOTIFICATIONS)
        }
    }

    @Test
    fun `precise location is always requested together with approximate`() {
        for (sdk in 26..32) {
            assertThat(NearbyPermissions.requiredFor(sdk)).contains(ACCESS_FINE_LOCATION)
            assertThat(NearbyPermissions.toRequestFor(sdk)).containsAtLeast(ACCESS_FINE_LOCATION, ACCESS_COARSE_LOCATION)
        }
    }
}
