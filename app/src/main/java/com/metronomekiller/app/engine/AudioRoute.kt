package com.metronomekiller.app.engine

import android.media.AudioDeviceInfo
import android.media.AudioManager

/**
 * Where audio is currently going (speaker, wired headphones, a Bluetooth model).
 * [key] identifies the route for saving its latency; [label] is for display.
 *
 * LEARN: Android doesn't directly say "this is the active output", but it does
 * list connected outputs. Android sends media to Bluetooth when it's connected,
 * then wired headphones, then the speaker — so we pick by that same priority.
 */
data class AudioRoute(val key: String, val label: String) {
    companion object {
        private val bluetooth = setOf(
            AudioDeviceInfo.TYPE_BLUETOOTH_A2DP,
            AudioDeviceInfo.TYPE_BLE_HEADSET,
            AudioDeviceInfo.TYPE_BLE_SPEAKER,
        )
        private val wired = setOf(
            AudioDeviceInfo.TYPE_WIRED_HEADPHONES,
            AudioDeviceInfo.TYPE_WIRED_HEADSET,
            AudioDeviceInfo.TYPE_USB_HEADSET,
        )

        fun current(am: AudioManager): AudioRoute {
            val outputs = am.getDevices(AudioManager.GET_DEVICES_OUTPUTS)
            outputs.firstOrNull { it.type in bluetooth }?.let {
                val name = it.productName?.toString()?.ifBlank { null } ?: "Bluetooth"
                return AudioRoute("bt:$name", name)
            }
            if (outputs.any { it.type in wired }) return AudioRoute("wired", "Wired headphones")
            return AudioRoute("speaker", "Phone speaker")
        }
    }
}
