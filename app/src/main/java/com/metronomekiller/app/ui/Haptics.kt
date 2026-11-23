package com.metronomekiller.app.ui

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext

/**
 * A short, crisp vibration "tick", like the detents of a real knob.
 *
 * LEARN: Compose's built-in haptics (LocalHapticFeedback) map to effects that
 * many phones, Samsung especially, silently ignore. Talking to the Vibrator
 * directly with the predefined TICK effect works on practically every device
 * (Android 10+), with a tiny one-shot buzz as the fallback on older phones.
 */
class Haptics(context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val effect: VibrationEffect =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) VibrationEffect.createPredefined(VibrationEffect.EFFECT_TICK)
        else VibrationEffect.createOneShot(8, 120)

    fun tick() = vibrator.vibrate(effect)
}

/** In a composable: `val tick = rememberTick()` then call `tick()`. */
@Composable
fun rememberTick(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { val h = Haptics(context); { h.tick() } }
}
