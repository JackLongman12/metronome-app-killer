package com.metronomekiller.app.metronome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedIconButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.graphics.lerp
import androidx.compose.animation.core.tween
import kotlinx.coroutines.delay
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronomekiller.app.ui.rememberTick
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.engine.MetronomeState
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.ui.ink
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** The four ways to show and change the tempo (chosen in Settings → Appearance). */
enum class BpmDisplay(val label: String, val description: String) {
    KNOB("Rotary knob", "Turn the big knob, like a real metronome"),
    NUMBER("Number", "Big number with a slider"),
    DIAL("Tempo ring", "A ring that fills up with the tempo"),
    RING("Beat ring", "Beats around a circle, tap one to change it"),
    RULER("Ruler", "Fling the ruler for big jumps"),
}

private const val MIN = MetronomeController.MIN_BPM
private const val MAX = MetronomeController.MAX_BPM

/**
 * Draws the chosen tempo display.
 *
 * LEARN: `Canvas` gives you a blank drawing surface (circles, arcs, lines).
 * Gestures come from `pointerInput`, where you read raw finger positions and
 * turn them into meaning (an angle, a distance) yourself.
 */
@Composable
fun TempoDisplay(
    style: BpmDisplay,
    state: MetronomeState,
    currentBeat: Int,
    controller: MetronomeController,
    onEditBpm: () -> Unit,
) {
    when (style) {
        BpmDisplay.KNOB -> KnobDisplay(state, controller, onEditBpm)
        BpmDisplay.NUMBER -> NumberDisplay(state, controller, onEditBpm)
        BpmDisplay.DIAL -> DialDisplay(state, controller, onEditBpm)
        BpmDisplay.RING -> RingDisplay(state, currentBeat, controller, onEditBpm)
        BpmDisplay.RULER -> RulerDisplay(state, controller, onEditBpm)
    }
}

@Composable
private fun BigNumber(state: MetronomeState, onEditBpm: () -> Unit, size: Int = 84, caption: String? = null) {
    Column(
        Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onEditBpm).padding(8.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("${state.bpm}", fontSize = size.sp, fontWeight = FontWeight.Medium, lineHeight = size.sp)
        Text(
            caption ?: "BPM · ${MetronomeController.tempoName(state.bpm)}",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/**
 * Sets the BPM and gives a light haptic tick whenever the number actually
 * changes, so every control feels like it clicks into place (Soundbrenner-style).
 */
@Composable
fun rememberBpmSetter(controller: MetronomeController): (Int) -> Unit {
    val tick = rememberTick()
    return remember(controller) {
        { bpm: Int ->
            val b = bpm.coerceIn(MIN, MAX)
            if (b != controller.state.value.bpm) {
                controller.setBpm(b)
                tick()
            }
        }
    }
}

@Composable
private fun PlusMinus(controller: MetronomeController, content: @Composable () -> Unit = { Spacer(Modifier.width(48.dp)) }) {
    val set = rememberBpmSetter(controller)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.Center, modifier = Modifier.fillMaxWidth()) {
        OutlinedIconButton(onClick = { set(controller.state.value.bpm - 1) }) { Icon(AppIcons.Remove, "Slower") }
        content()
        OutlinedIconButton(onClick = { set(controller.state.value.bpm + 1) }) { Icon(AppIcons.Add, "Faster") }
    }
}

// ── A · Number ──────────────────────────────────────────────────────────────

@Composable
private fun NumberDisplay(state: MetronomeState, controller: MetronomeController, onEditBpm: () -> Unit) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigNumber(state, onEditBpm)
        Spacer(Modifier.height(16.dp))
        PlusMinus(controller) {
            val set = rememberBpmSetter(controller)
            Slider(
                value = state.bpm.toFloat(),
                onValueChange = { set(it.roundToInt()) },
                valueRange = MIN.toFloat()..MAX.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
    }
}

// ── Rotary knob (default) ──────────────────────────────────────────────────

/**
 * A big rotary knob with tick marks, like a hardware metronome. Turn it with your
 * thumb: every 6 degrees is 1 BPM (60 BPM per full turn) with a haptic tick per
 * step. The grip dot on the knob turns with the tempo, so you see it move.
 */
@Composable
private fun KnobDisplay(state: MetronomeState, controller: MetronomeController, onEditBpm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tick = rememberTick()
    // Which way the knob was last turned (-1 slower, +1 faster) and how lit the
    // matching arrow is (1 = fully lit, fades to 0 shortly after you stop).
    var turnDir by remember { mutableIntStateOf(0) }
    var pulse by remember { mutableIntStateOf(0) }
    val lit = remember { Animatable(0f) }
    LaunchedEffect(pulse) {
        if (pulse == 0) return@LaunchedEffect
        lit.snapTo(1f)
        delay(250)
        lit.animateTo(0f, tween(450))
    }
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Column(
            Modifier.clip(RoundedCornerShape(12.dp)).clickable(onClick = onEditBpm).padding(horizontal = 16.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(MetronomeController.tempoName(state.bpm), style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
            Text("${state.bpm}", fontSize = 56.sp, fontWeight = FontWeight.Bold, lineHeight = 56.sp)
            Text("BPM", style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
        }
        Box(
            Modifier.size(280.dp).pointerInput(Unit) {
                val center = Offset(size.width / 2f, size.height / 2f)
                var accum = 0f
                var lastAngle = 0f
                detectDragGestures(onDragStart = { p -> lastAngle = angleOf(p, center); accum = 0f }) { change, _ ->
                    val a = angleOf(change.position, center)
                    var d = a - lastAngle
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f // crossing the 180 degree line
                    lastAngle = a
                    accum += d
                    val steps = (accum / 6f).toInt()
                    if (steps != 0) {
                        accum -= steps * 6f
                        controller.nudge(steps)
                        tick()
                        turnDir = if (steps > 0) 1 else -1
                        pulse++
                    }
                    change.consume()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(280.dp)) {
                val leftColor = if (turnDir < 0) lerp(c.outline, c.primary, lit.value) else c.outline
                val rightColor = if (turnDir > 0) lerp(c.outline, c.primary, lit.value) else c.outline
                val outerR = size.minDimension / 2 - 22.dp.toPx()
                // curved arrows above the knob: turn left / turn right
                val arcR = outerR + 14.dp.toPx()
                val arcTl = Offset(center.x - arcR, center.y - arcR)
                val arrowStroke = Stroke(2.dp.toPx())
                drawArc(leftColor, 200f, 40f, false, arcTl, Size(arcR * 2, arcR * 2), style = arrowStroke)
                drawArc(rightColor, 300f, 40f, false, arcTl, Size(arcR * 2, arcR * 2), style = arrowStroke)
                fun arrowHead(deg: Float, dir: Float, color: androidx.compose.ui.graphics.Color) {
                    val r = Math.toRadians(deg.toDouble())
                    val tip = Offset(center.x + arcR * cos(r).toFloat(), center.y + arcR * sin(r).toFloat())
                    val back = Math.toRadians((deg - dir * 6f).toDouble())
                    val l = 8.dp.toPx()
                    val b1 = Offset(center.x + (arcR - l) * cos(back).toFloat(), center.y + (arcR - l) * sin(back).toFloat())
                    val b2 = Offset(center.x + (arcR + l) * cos(back).toFloat(), center.y + (arcR + l) * sin(back).toFloat())
                    drawLine(color, tip, b1, 2.dp.toPx())
                    drawLine(color, tip, b2, 2.dp.toPx())
                }
                arrowHead(200f, -1f, leftColor)
                arrowHead(340f, 1f, rightColor)
                // knob body: dark rim, then the face
                drawCircle(c.ink, outerR)
                drawCircle(c.surfaceVariant, outerR - 10.dp.toPx())
                // tick marks around the face
                val tickOuter = outerR - 16.dp.toPx()
                for (i in 0 until 60) {
                    val r = Math.toRadians(i * 6.0)
                    val len = (if (i % 5 == 0) 10 else 6).dp.toPx()
                    drawLine(
                        c.outline,
                        Offset(center.x + tickOuter * cos(r).toFloat(), center.y + tickOuter * sin(r).toFloat()),
                        Offset(center.x + (tickOuter - len) * cos(r).toFloat(), center.y + (tickOuter - len) * sin(r).toFloat()),
                        1.5.dp.toPx(),
                    )
                }
                drawCircle(c.surface, tickOuter - 14.dp.toPx())
                // the grip dot turns with the tempo (6 degrees per BPM, same as the gesture)
                val dotAngle = Math.toRadians(state.bpm * 6.0)
                val dotR = tickOuter - 36.dp.toPx()
                drawCircle(
                    c.surfaceVariant, 10.dp.toPx(),
                    Offset(center.x + dotR * cos(dotAngle).toFloat(), center.y + dotR * sin(dotAngle).toFloat()),
                )
            }
        }
    }
}

// ── B · Tempo dial ──────────────────────────────────────────────────────────

/**
 * Drag your thumb around the ring: every 6° is 1 BPM (60 BPM per full turn),
 * with a haptic tick per step, like turning a real knob.
 */
@Composable
private fun DialDisplay(state: MetronomeState, controller: MetronomeController, onEditBpm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tick = rememberTick()
    val fraction = (state.bpm - MIN).toFloat() / (MAX - MIN)
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(240.dp).pointerInput(Unit) {
                val center = Offset(size.width / 2f, size.height / 2f)
                var accum = 0f
                var lastAngle = 0f
                detectDragGestures(
                    onDragStart = { p -> lastAngle = angleOf(p, center); accum = 0f },
                ) { change, _ ->
                    val a = angleOf(change.position, center)
                    var d = a - lastAngle
                    if (d > 180f) d -= 360f
                    if (d < -180f) d += 360f // crossing the top of the circle
                    lastAngle = a
                    accum += d
                    val steps = (accum / 6f).toInt()
                    if (steps != 0) {
                        accum -= steps * 6f
                        controller.nudge(steps)
                        tick()
                    }
                    change.consume()
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(240.dp)) {
                val stroke = 14.dp.toPx()
                val r = size.minDimension / 2 - stroke
                val tl = Offset(center.x - r, center.y - r)
                drawCircle(c.surfaceVariant, radius = r, style = Stroke(stroke))
                drawArc(c.primary, -90f, 360f * fraction, false, tl, Size(r * 2, r * 2), style = Stroke(stroke))
                val ang = Math.toRadians((-90f + 360f * fraction).toDouble())
                val knob = Offset(center.x + r * cos(ang).toFloat(), center.y + r * sin(ang).toFloat())
                drawCircle(c.surface, radius = stroke * 0.8f, center = knob)
                drawCircle(c.primary, radius = stroke * 0.8f, center = knob, style = Stroke(3.dp.toPx()))
            }
            BigNumber(state, onEditBpm, size = 64, caption = "BPM")
        }
        Text(
            MetronomeController.tempoName(state.bpm),
            style = MaterialTheme.typography.bodyMedium,
            color = c.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PlusMinus(controller)
    }
}

private fun angleOf(p: Offset, center: Offset): Float =
    Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()

// ── C · Beat ring ───────────────────────────────────────────────────────────

/** Beats sit around a circle; the playing one gets an outline. Tap a dot to change it. */
@Composable
private fun RingDisplay(state: MetronomeState, current: Int, controller: MetronomeController, onEditBpm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tick = rememberTick()
    val beats = state.beats
    val beatsNow by rememberUpdatedState(beats)
    val density = LocalDensity.current
    val dotPositions = remember { mutableStateOf(emptyList<Offset>()) }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier.size(260.dp).pointerInput(Unit) {
                detectTapGestures { p ->
                    val hit = with(density) { 30.dp.toPx() }
                    val i = dotPositions.value.indexOfFirst { hypot(it.x - p.x, it.y - p.y) < hit }
                    if (i in beatsNow.indices) {
                        controller.cycleBeat(i)
                        tick()
                    } else if (hypot(p.x - size.width / 2f, p.y - size.height / 2f) < size.width / 4f) {
                        onEditBpm()
                    }
                }
            },
            contentAlignment = Alignment.Center,
        ) {
            Canvas(Modifier.size(260.dp)) {
                val r = size.minDimension / 2 - 22.dp.toPx()
                drawCircle(c.outlineVariant, radius = r, style = Stroke(1.dp.toPx()))
                val pts = beats.indices.map { i ->
                    val a = Math.toRadians(-90.0 + 360.0 * i / beats.size)
                    Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat())
                }
                dotPositions.value = pts
                beats.forEachIndexed { i, s ->
                    val p = pts[i]
                    val rad = (if (s.isAccent) 15 else 11).dp.toPx() * if (beats.size > 10) 0.75f else 1f
                    when (s) {
                        BeatState.STRONG -> drawCircle(c.primary, rad + 3.dp.toPx(), p)
                        BeatState.ACCENT -> drawCircle(c.primary, rad, p)
                        BeatState.NORMAL -> drawCircle(c.primary.copy(alpha = 0.45f), rad, p)
                        BeatState.MUTE -> drawCircle(c.outline, rad, p, style = Stroke(1.5.dp.toPx()))
                    }
                    if (i == current) drawCircle(c.onSurface, rad + 5.dp.toPx(), p, style = Stroke(2.5.dp.toPx()))
                }
            }
            BigNumber(state, onEditBpm, size = 60, caption = state.meter.label)
        }
        Text(
            "Tap a beat on the ring to change it",
            style = MaterialTheme.typography.labelSmall,
            color = c.onSurfaceVariant,
        )
        Spacer(Modifier.height(8.dp))
        PlusMinus(controller) {
            val set = rememberBpmSetter(controller)
            Slider(
                value = state.bpm.toFloat(),
                onValueChange = { set(it.roundToInt()) },
                valueRange = MIN.toFloat()..MAX.toFloat(),
                modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            )
        }
    }
}

// ── D · Ruler ───────────────────────────────────────────────────────────────

/**
 * A ruler of tick marks, one per BPM. Drag it, or fling it and it glides to a
 * stop (that's `animateDecay`: it slows down like a real object with friction).
 */
@Composable
private fun RulerDisplay(state: MetronomeState, controller: MetronomeController, onEditBpm: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val tick = rememberTick()
    val scope = rememberCoroutineScope()
    val measurer = rememberTextMeasurer()
    val spacingPx = with(LocalDensity.current) { 10.dp.toPx() }
    val pos = remember { Animatable(state.bpm.toFloat()).apply { updateBounds(MIN.toFloat(), MAX.toFloat()) } }
    var dragging by remember { mutableStateOf(false) }

    // Follow changes made elsewhere (± buttons, tap tempo) when not being dragged.
    LaunchedEffect(state.bpm) { if (!dragging && !pos.isRunning) pos.snapTo(state.bpm.toFloat()) }

    fun commit(v: Float) {
        val b = v.roundToInt()
        if (b != controller.state.value.bpm) {
            controller.setBpm(b)
            tick()
        }
    }

    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        BigNumber(state, onEditBpm)
        Spacer(Modifier.height(12.dp))
        val textStyle = TextStyle(fontSize = 11.sp, color = c.onSurfaceVariant)
        Canvas(
            Modifier.fillMaxWidth().height(64.dp).draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { dx ->
                    scope.launch { pos.snapTo(pos.value - dx / spacingPx); commit(pos.value) }
                },
                onDragStarted = { dragging = true; pos.stop() },
                onDragStopped = { v ->
                    dragging = false
                    scope.launch {
                        pos.animateDecay(-v / spacingPx, exponentialDecay(frictionMultiplier = 2f)) { commit(value) }
                        pos.animateTo(pos.value.roundToInt().toFloat()) // settle exactly on a tick
                        commit(pos.value)
                    }
                },
            )
        ) {
            val mid = size.width / 2
            val v = pos.value
            val half = (mid / spacingPx).toInt() + 2
            for (k in (v.toInt() - half)..(v.toInt() + half)) {
                if (k < MIN || k > MAX) continue
                val x = mid + (k - v) * spacingPx
                val major = k % 10 == 0
                val tall = if (major) 30.dp.toPx() else if (k % 5 == 0) 20.dp.toPx() else 12.dp.toPx()
                drawLine(c.outline, Offset(x, size.height - tall), Offset(x, size.height), strokeWidth = 1.5.dp.toPx())
                if (major) {
                    val layout = measurer.measure("$k", textStyle)
                    drawText(layout, topLeft = Offset(x - layout.size.width / 2f, 0f))
                }
            }
            drawLine(c.primary, Offset(mid, size.height - 44.dp.toPx()), Offset(mid, size.height), strokeWidth = 3.dp.toPx())
        }
        Spacer(Modifier.height(12.dp))
        PlusMinus(controller)
    }
}
