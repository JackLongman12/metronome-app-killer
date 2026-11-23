package com.metronomekiller.app

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.metronomekiller.app.ui.AppColors
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

    /** Flash only on accented beats instead of every beat. */
    private val _flashAccentsOnly = MutableStateFlow(prefs.getBoolean("flash_accents_only", false))
    val flashAccentsOnly: StateFlow<Boolean> = _flashAccentsOnly
    fun setFlashAccentsOnly(on: Boolean) { _flashAccentsOnly.value = on; prefs.edit { putBoolean("flash_accents_only", on) } }

    /** Click volume, 0.0–2.0 (above 1.0 is a boost through the engine's soft limiter). */
    private val _volume = MutableStateFlow(prefs.getFloat("volume", 1f))
    val volume: StateFlow<Float> = _volume
    fun setVolume(v: Float) { _volume.value = v; prefs.edit { putFloat("volume", v) } }

    /** Colour scheme (see AppColors). Studio is the default. */
    private val _colors = MutableStateFlow(
        runCatching { AppColors.valueOf(prefs.getString("colors", null) ?: "") }.getOrDefault(AppColors.STUDIO)
    )
    val colors: StateFlow<AppColors> = _colors
    fun setColors(c: AppColors) { _colors.value = c; prefs.edit { putString("colors", c.name) } }

    /** Small vibration per BPM step on the knob, sliders and beat blocks. */
    private val _hapticTicks = MutableStateFlow(prefs.getBoolean("haptic_ticks", true))
    val hapticTicks: StateFlow<Boolean> = _hapticTicks
    fun setHapticTicks(on: Boolean) { _hapticTicks.value = on; prefs.edit { putBoolean("haptic_ticks", on) } }

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
