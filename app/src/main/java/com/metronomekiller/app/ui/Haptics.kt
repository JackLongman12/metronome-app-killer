package com.metronomekiller.app.ui

import android.content.Context
import android.media.AudioAttributes
import android.os.Build
import android.os.VibrationAttributes
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import com.metronomekiller.app.App

/**
 * A short, firm vibration "tick", like the detents of a real knob.
 *
 * LEARN — why this is more complicated than it looks:
 * - Compose's LocalHapticFeedback and the predefined EFFECT_TICK are "touch"
 *   feedback. Many phones (Samsung especially) play those very faintly, or not
 *   at all when touch vibration is lowered or the phone is on silent.
 * - So we send our own 12 ms pulse and label it as MEDIA feedback (it belongs
 *   to the app's content, like a game's rumble). Those settings don't mute it.
 * - Calls come many times per second while turning the knob; each new pulse
 *   simply replaces the previous one, so they never pile up.
 * The user can switch it off in Settings ("Haptic ticks").
 */
class Haptics(private val context: Context) {
    private val vibrator: Vibrator =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }

    private val effect: VibrationEffect = VibrationEffect.createOneShot(
        12,
        if (vibrator.hasAmplitudeControl()) 200 else VibrationEffect.DEFAULT_AMPLITUDE,
    )

    fun tick() {
        if (!App.from(context).prefs.hapticTicks.value) return
        if (Build.VERSION.SDK_INT >= 33) {
            vibrator.vibrate(effect, VibrationAttributes.createForUsage(VibrationAttributes.USAGE_MEDIA))
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(effect, AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA).build())
        }
    }
}

/** In a composable: `val tick = rememberTick()` then call `tick()`. */
@Composable
fun rememberTick(): () -> Unit {
    val context = LocalContext.current
    return remember(context) { val h = Haptics(context.applicationContext); { h.tick() } }
}
