package com.metronomekiller.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Small persistent settings store.
 *
 * LEARN: SharedPreferences is Android's simplest key-value storage — a tiny
 * XML file in the app's private folder. Each setting is exposed as a StateFlow:
 * a value that UI code can "collect" (watch), so the screen redraws
 * automatically whenever the setting changes.
 */
class AppPrefs(context: Context) {
    private val prefs: SharedPreferences = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    private val _flash = MutableStateFlow(prefs.getBoolean("flash", false))
    val flash: StateFlow<Boolean> = _flash
    fun setFlash(on: Boolean) { _flash.value = on; prefs.edit { putBoolean("flash", on) } }

    private val _vibrate = MutableStateFlow(prefs.getBoolean("vibrate", false))
    val vibrate: StateFlow<Boolean> = _vibrate
    fun setVibrate(on: Boolean) { _vibrate.value = on; prefs.edit { putBoolean("vibrate", on) } }

    var askedNotifications: Boolean
        get() = prefs.getBoolean("asked_notifications", false)
        set(v) = prefs.edit { putBoolean("asked_notifications", v) }

    /**
     * Extra output latency, per audio route. Bluetooth headphones are typically
     * 100–300 ms behind the phone speaker, and every model is different, so we
     * remember one value per device (key comes from [AudioRoute]).
     */
    fun latencyMs(routeKey: String): Int = prefs.getInt("latency_$routeKey", 0)
    fun setLatencyMs(routeKey: String, ms: Int) = prefs.edit { putInt("latency_$routeKey", ms) }
}
