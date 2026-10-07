package com.songsync.app.playback

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Where audio is going right now. Latency is learned and calibrated per route. */
data class AudioRoute(val key: String, val type: Type, val name: String) {
    enum class Type { SPEAKER, WIRED, USB, BLUETOOTH }
}

class AudioRouteMonitor(context: Context) {
    private val audioManager = context.getSystemService(AudioManager::class.java)
    private val _route = MutableStateFlow(currentRoute())
    val route: StateFlow<AudioRoute> = _route.asStateFlow()

    init {
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(addedDevices: Array<out AudioDeviceInfo>) {
                _route.value = currentRoute()
            }

            override fun onAudioDevicesRemoved(removedDevices: Array<out AudioDeviceInfo>) {
                _route.value = currentRoute()
            }
        }, Handler(Looper.getMainLooper()))
    }

    private fun currentRoute(): AudioRoute {
        val devices = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // The devices media would actually play on right now.
            val media = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build()
            audioManager.getAudioDevicesForAttributes(media).ifEmpty { outputs() }
        } else {
            outputs() // best guess: media prefers Bluetooth, then wired/USB, then the speaker
        }
        devices.firstOrNull { it.type in BLUETOOTH }?.let {
            return AudioRoute("bt:${it.productName}", AudioRoute.Type.BLUETOOTH, it.productName.toString())
        }
        devices.firstOrNull { it.type in USB }?.let {
            return AudioRoute("usb:${it.productName}", AudioRoute.Type.USB, it.productName.toString())
        }
        devices.firstOrNull { it.type in WIRED }?.let { return AudioRoute("wired", AudioRoute.Type.WIRED, "") }
        return AudioRoute("speaker", AudioRoute.Type.SPEAKER, "")
    }

    private fun outputs(): List<AudioDeviceInfo> = audioManager.getDevices(AudioManager.GET_DEVICES_OUTPUTS).toList()

    private companion object {
        val BLUETOOTH = buildSet {
            add(AudioDeviceInfo.TYPE_BLUETOOTH_A2DP)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) add(AudioDeviceInfo.TYPE_HEARING_AID)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                add(AudioDeviceInfo.TYPE_BLE_HEADSET)
                add(AudioDeviceInfo.TYPE_BLE_SPEAKER)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(AudioDeviceInfo.TYPE_BLE_BROADCAST)
        }
        val USB = setOf(AudioDeviceInfo.TYPE_USB_HEADSET, AudioDeviceInfo.TYPE_USB_DEVICE)
        val WIRED = setOf(AudioDeviceInfo.TYPE_WIRED_HEADSET, AudioDeviceInfo.TYPE_WIRED_HEADPHONES, AudioDeviceInfo.TYPE_LINE_ANALOG)
    }
}
