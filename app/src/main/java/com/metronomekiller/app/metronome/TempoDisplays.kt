package com.metronomekiller.app.metronome

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.engine.MetronomeState
import com.metronomekiller.app.ui.comicPanel
import com.metronomekiller.app.ui.ink
import com.metronomekiller.app.ui.isComic
import com.metronomekiller.app.ui.rememberHalftone
import com.metronomekiller.app.ui.rememberTick
import kotlinx.coroutines.delay
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

private const val MIN = MetronomeController.MIN_BPM
private const val MAX = MetronomeController.MAX_BPM

/**
 * Sets the BPM and gives a haptic tick whenever the number actually changes.
 * (Kept for any control that sets the tempo directly.)
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

private fun angleOf(p: Offset, center: Offset): Float =
    Math.toDegrees(atan2((p.y - center.y).toDouble(), (p.x - center.x).toDouble())).toFloat()

/**
 * The tempo control: a big rotary knob, like a hardware metronome.
 *
 * Turn it with your thumb: every 6 degrees is 1 BPM (60 BPM per full turn), with a
 * haptic tick per step. The grip dot turns with the tempo, and the curved arrow
 * on the side you're turning lights up, then fades when you stop.
 * Tap the BPM number to type a tempo with the number pad.
 *
 * LEARN: `Canvas` gives a blank drawing surface (circles, arcs, lines). Gestures
 * come from `pointerInput`, where raw finger positions become an angle.
 */
@Composable
fun TempoKnob(state: MetronomeState, controller: MetronomeController, onEditBpm: () -> Unit, knobSize: Dp = 300.dp) {
    val c = MaterialTheme.colorScheme
    val comic = isComic()
    val tick = rememberTick()
    val halftone = rememberHalftone()

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
        // Tempo readout — a speech-bubble-like pill in the comic theme.
        val readout = Modifier.clip(RoundedCornerShape(40.dp)).clickable(onClick = onEditBpm)
        Column(
            (if (comic) Modifier.comicPanel(RoundedCornerShape(40.dp)).then(readout) else readout)
                .padding(horizontal = 28.dp, vertical = 4.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(MetronomeController.tempoName(state.bpm), style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant)
            Text(
                "${state.bpm}",
                style = if (comic) MaterialTheme.typography.displayMedium else MaterialTheme.typography.displayMedium.copy(fontWeight = FontWeight.Bold),
                fontSize = if (comic) 48.sp else 56.sp,
                lineHeight = if (comic) 48.sp else 56.sp,
            )
            Text("BPM", style = MaterialTheme.typography.labelLarge, color = c.onSurfaceVariant)
        }

        Box(
            Modifier.size(knobSize).pointerInput(Unit) {
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
            Canvas(Modifier.size(knobSize)) {
                val leftOn = if (turnDir < 0) lit.value else 0f
                val rightOn = if (turnDir > 0) lit.value else 0f
                if (comic) drawComicKnob(state.bpm, leftOn, rightOn, halftone)
                else drawStudioKnob(state.bpm, leftOn, rightOn, c.outline, c.primary, c.ink, c.surfaceVariant, c.surface)
            }
        }
    }
}

/** Grip dot position: turns 6 degrees per BPM, the same as the gesture. */
private fun DrawScope.gripDot(bpm: Int, r: Float, radius: Float, color: Color) {
    val a = Math.toRadians(bpm * 6.0)
    drawCircle(color, radius, Offset(center.x + r * cos(a).toFloat(), center.y + r * sin(a).toFloat()))
}

private fun DrawScope.tickMarks(outer: Float, color: Color, count: Int, majorEvery: Int, minorLen: Float, majorLen: Float) {
    for (i in 0 until count) {
        val r = Math.toRadians(i * 360.0 / count)
        val len = if (i % majorEvery == 0) majorLen else minorLen
        drawLine(
            color,
            Offset(center.x + outer * cos(r).toFloat(), center.y + outer * sin(r).toFloat()),
            Offset(center.x + (outer - len) * cos(r).toFloat(), center.y + (outer - len) * sin(r).toFloat()),
            if (i % majorEvery == 0) 2.2.dp.toPx() else 1.4.dp.toPx(),
        )
    }
}

/** Studio look: soft dark knob, thin curved arrows that light up in the accent colour. */
private fun DrawScope.drawStudioKnob(
    bpm: Int, leftOn: Float, rightOn: Float,
    outline: Color, accent: Color, ink: Color, face: Color, surface: Color,
) {
    val outerR = size.minDimension / 2 - 26.dp.toPx()
    val arcR = outerR + 16.dp.toPx()
    val arcTl = Offset(center.x - arcR, center.y - arcR)
    val stroke = Stroke(2.5.dp.toPx())
    val left = lerp(outline, accent, leftOn)
    val right = lerp(outline, accent, rightOn)
    drawArc(left, 200f, 45f, false, arcTl, Size(arcR * 2, arcR * 2), style = stroke)
    drawArc(right, 295f, 45f, false, arcTl, Size(arcR * 2, arcR * 2), style = stroke)
    fun head(deg: Float, dir: Float, color: Color) {
        val r = Math.toRadians(deg.toDouble())
        val tip = Offset(center.x + arcR * cos(r).toFloat(), center.y + arcR * sin(r).toFloat())
        val back = Math.toRadians((deg - dir * 6f).toDouble())
        val l = 8.dp.toPx()
        drawLine(color, tip, Offset(center.x + (arcR - l) * cos(back).toFloat(), center.y + (arcR - l) * sin(back).toFloat()), 2.5.dp.toPx())
        drawLine(color, tip, Offset(center.x + (arcR + l) * cos(back).toFloat(), center.y + (arcR + l) * sin(back).toFloat()), 2.5.dp.toPx())
    }
    head(200f, -1f, left)
    head(340f, 1f, right)
    drawCircle(ink, outerR)
    drawCircle(face, outerR - 10.dp.toPx())
    val tickOuter = outerR - 16.dp.toPx()
    tickMarks(tickOuter, outline, 60, 5, 6.dp.toPx(), 10.dp.toPx())
    drawCircle(surface, tickOuter - 14.dp.toPx())
    gripDot(bpm, tickOuter - 36.dp.toPx(), 10.dp.toPx(), face)
}

/**
 * Comic look: ink outlines, a hard offset shadow, halftone shading on the lower
 * half, and two thick curved arrows that wrap around the top of the knob. The
 * arrow you're turning toward fills in solid black.
 */
private fun DrawScope.drawComicKnob(bpm: Int, leftOn: Float, rightOn: Float, halftone: Brush) {
    val black = Color.Black
    val white = Color.White
    val r = size.minDimension / 2 - 30.dp.toPx()
    val arrowR = r + 18.dp.toPx()
    bandArrow(arrowR, 6.dp.toPx(), 258f, 205f, if (leftOn > 0.5f) black else white)
    bandArrow(arrowR, 6.dp.toPx(), 282f, 335f, if (rightOn > 0.5f) black else white)
    val shadow = 4.dp.toPx()
    drawCircle(black, r, Offset(center.x + shadow, center.y + shadow))
    drawCircle(white, r)
    // halftone on the lower half, like printed shading
    drawArc(halftone, 0f, 180f, true, Offset(center.x - r, center.y - r), Size(r * 2, r * 2))
    drawCircle(black, r, style = Stroke(3.5.dp.toPx()))
    val inner = r - 18.dp.toPx()
    drawCircle(white, inner)
    drawCircle(black, inner, style = Stroke(2.5.dp.toPx()))
    tickMarks(inner, black, 48, 4, 5.dp.toPx(), 10.dp.toPx())
    gripDot(bpm, inner - 32.dp.toPx(), 9.dp.toPx(), black)
}

/**
 * A thick curved arrow following a circle of radius [r]: a band [w] wide from
 * angle [from] to [to] (degrees, clockwise), with an arrowhead at [to].
 */
private fun DrawScope.bandArrow(r: Float, w: Float, from: Float, to: Float, fill: Color) {
    val sweep = to - from
    val dir = if (sweep > 0) 1f else -1f
    fun pt(rr: Float, deg: Float): Offset {
        val a = Math.toRadians(deg.toDouble())
        return Offset(center.x + rr * cos(a).toFloat(), center.y + rr * sin(a).toFloat())
    }
    fun rect(rr: Float) = Rect(center.x - rr, center.y - rr, center.x + rr, center.y + rr)
    val path = Path().apply {
        val start = pt(r + w, from)
        moveTo(start.x, start.y)
        arcTo(rect(r + w), from, sweep, false)
        val ow = pt(r + w * 2.4f, to); lineTo(ow.x, ow.y)
        val tip = pt(r, to + dir * 11f); lineTo(tip.x, tip.y)
        val iw = pt(r - w * 2.4f, to); lineTo(iw.x, iw.y)
        val i1 = pt(r - w, to); lineTo(i1.x, i1.y)
        arcTo(rect(r - w), to, -sweep, false)
        close()
    }
    drawPath(path, fill)
    drawPath(path, Color.Black, style = Stroke(2.5.dp.toPx()))
}
