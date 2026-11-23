package com.metronomekiller.app.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.Meter
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.engine.Subdivision
import com.metronomekiller.app.ui.AppIcons
import kotlin.math.abs
import kotlin.math.roundToInt

private const val CAL_BPM = 100
private const val WARMUP_TAPS = 4
private const val NEEDED_TAPS = 12

/**
 * Lines up the beat highlight, flash and vibration with what you actually hear.
 *
 * Why this is needed: Bluetooth headphones play sound 100–300 ms after the
 * phone sends it. The engine already knows the delay the phone reports, but
 * many headphones report it wrong. So you tap along to the click; the average
 * gap between your taps and the beats is the extra delay, saved per device.
 *
 * (Audio-only use doesn't need this: a steady click that's late by a constant
 * amount still sounds perfectly steady. It matters for anything you SEE or FEEL.)
 */
@Composable
fun CalibrationScreen(controller: MetronomeController, onBack: () -> Unit) {
    val route by controller.route.collectAsStateWithLifecycle()
    var saved by remember(route.key) { mutableIntStateOf(controller.prefs.latencyMs(route.key)) }
    var running by remember { mutableStateOf(false) }
    var result by remember { mutableStateOf<Int?>(null) }
    val offsets = remember { mutableStateListOf<Double>() }
    var taps by remember { mutableIntStateOf(0) }

    // Remember the user's metronome settings so we can put them back afterwards.
    val before = remember { controller.state.value }

    fun finish() {
        controller.stop()
        controller.restore(before)
        running = false
    }

    fun startTest() {
        offsets.clear(); taps = 0; result = null
        controller.restore(before.copy(bpm = CAL_BPM, meter = Meter(4.0, 4.0), beats = List(4) { BeatState.NORMAL }, subdivision = Subdivision.QUARTERS))
        controller.start()
        running = true
    }

    // LEARN: DisposableEffect's onDispose runs when this screen leaves, so the
    // test click never keeps going if you press back mid-test.
    DisposableEffect(Unit) { onDispose { if (controller.state.value.playing && running) finish() } }

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = { if (running) finish(); onBack() }) { Icon(AppIcons.Back, "Back") }
            Text("Audio sync", style = MaterialTheme.typography.titleLarge)
        }

        Column(Modifier.padding(horizontal = 20.dp).fillMaxSize()) {
            Text("Output: ${route.label}", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Connect the headphones you practise with, start the test, and tap the pad exactly when you hear each click.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Spacer(Modifier.height(20.dp))

            // Current offset with fine adjustment
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center) {
                OutlinedIconButton(onClick = {
                    saved = (saved - 5).coerceAtLeast(-200); controller.prefs.setLatencyMs(route.key, saved)
                }) { Icon(AppIcons.Remove, "Less delay") }
                Column(Modifier.padding(horizontal = 20.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("$saved ms", fontSize = 36.sp, fontWeight = FontWeight.Medium)
                    Text("visual delay", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                OutlinedIconButton(onClick = {
                    saved = (saved + 5).coerceAtMost(600); controller.prefs.setLatencyMs(route.key, saved)
                }) { Icon(AppIcons.Add, "More delay") }
            }

            Spacer(Modifier.height(20.dp))

            // The tap pad. We use the touch event's own timestamp (when your finger
            // landed), not "now", so app slowness doesn't skew the measurement.
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .clip(RoundedCornerShape(16.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .border(1.dp, MaterialTheme.colorScheme.outline, RoundedCornerShape(16.dp))
                    .pointerInput(running) {
                        if (!running) return@pointerInput
                        awaitEachGesture {
                            val down = awaitFirstDown()
                            // uptimeMillis and System.nanoTime share the same clock on Android.
                            val tapNs = down.uptimeMillis * 1_000_000L
                            taps++
                            val d = controller.tapOffsetMs(tapNs)
                            val halfBeat = 60_000.0 / CAL_BPM / 2
                            if (taps > WARMUP_TAPS && d != null && abs(d) < halfBeat) offsets.add(d)
                            if (offsets.size >= NEEDED_TAPS) {
                                val sorted = offsets.sorted()
                                result = sorted[sorted.size / 2].roundToInt() // median ignores stray taps
                                finish()
                            }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    when {
                        running && taps < WARMUP_TAPS -> "Listen… tap along with the click"
                        running -> "${offsets.size} of $NEEDED_TAPS"
                        result != null -> "Measured ${result} ms"
                        else -> "Tap pad"
                    },
                    style = MaterialTheme.typography.titleMedium,
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            Spacer(Modifier.height(16.dp))
            val r = result
            if (r != null && !running) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth()) {
                    TextButton(onClick = { startTest() }, modifier = Modifier.weight(1f)) { Text("Try again") }
                    Button(
                        onClick = { saved = r; controller.prefs.setLatencyMs(route.key, r); result = null },
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = MaterialTheme.colorScheme.onSurface,
                            contentColor = MaterialTheme.colorScheme.surface,
                        ),
                    ) { Text("Use $r ms") }
                }
            } else {
                Button(
                    onClick = { if (running) finish() else startTest() },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = MaterialTheme.colorScheme.onSurface,
                        contentColor = MaterialTheme.colorScheme.surface,
                    ),
                ) { Text(if (running) "Stop test" else "Start test") }
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}
