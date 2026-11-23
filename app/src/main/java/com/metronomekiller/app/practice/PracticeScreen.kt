package com.metronomekiller.app.practice

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.metronome.BeatEditor
import com.metronomekiller.app.metronome.SquareButton
import com.metronomekiller.app.ui.AppIcons
import com.metronomekiller.app.ui.rememberTick
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Practice tab (approved design): speed trainer, gap click and timing check.
 * Everything plays through the normal metronome, so its meter, accents and
 * sound apply here too; the beat blocks at the top are the same ones.
 */
@Composable
fun PracticeScreen(practice: PracticeController, metronome: MetronomeController, onPlay: () -> Unit) {
    val tool by practice.tool.collectAsStateWithLifecycle()
    val running by practice.running.collectAsStateWithLifecycle()
    val state by metronome.state.collectAsStateWithLifecycle()
    val current by metronome.currentBeat.collectAsStateWithLifecycle()
    val tick = rememberTick()

    Column(Modifier.fillMaxSize().padding(horizontal = 20.dp)) {
        Spacer(Modifier.height(12.dp))
        Text("Practice", style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(10.dp))
        Segmented(
            listOf("Speed" to PracticeController.Tool.SPEED, "Gaps" to PracticeController.Tool.GAPS, "Timing" to PracticeController.Tool.TIMING),
            tool,
        ) { practice.setTool(it); tick() }
        Spacer(Modifier.height(12.dp))
        BeatEditor(
            beats = state.beats, lastFraction = state.meter.lastBeatFraction.toFloat(),
            current = if (running != null) current else -1,
            onCycle = metronome::cycleBeat, onSet = metronome::setBeat,
            modifier = Modifier.height(64.dp),
        )
        Spacer(Modifier.height(10.dp))

        when (tool) {
            PracticeController.Tool.SPEED -> SpeedTrainer(practice, state.bpm, running == tool, tick)
            PracticeController.Tool.GAPS -> GapClick(practice, running == tool, tick)
            PracticeController.Tool.TIMING -> TimingCheck(practice, running == tool)
        }

        Row(Modifier.fillMaxWidth().padding(vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            SquareButton(onClick = { onPlay(); practice.toggle() }, size = 68, dark = true) {
                Icon(if (running != null) AppIcons.Pause else AppIcons.Play, if (running != null) "Stop" else "Start",
                    modifier = Modifier.size(34.dp))
            }
            Spacer(Modifier.width(14.dp))
            Text(
                "${state.meter.label} · ${state.sound.label}. Uses the metronome's meter, accents and sound.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// ── Speed trainer ──────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.SpeedTrainer(practice: PracticeController, bpm: Int, on: Boolean, tick: () -> Unit) {
    val plan by practice.trainer.collectAsStateWithLifecycle()
    val bar by practice.bar.collectAsStateWithLifecycle()
    val countingIn by practice.countingIn.collectAsStateWithLifecycle()
    val countIn by practice.countIn.collectAsStateWithLifecycle()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(if (on) "now" else "starts at", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("${if (on) bpm else plan.from}", fontSize = 52.sp, fontWeight = FontWeight.Bold, lineHeight = 54.sp)
            Text(
                when {
                    on && countingIn > 0 -> "BPM · count-in $countingIn of $countIn"
                    on && bar >= 0 -> "BPM · bar ${bar % plan.every + 1} of ${plan.every} at this tempo"
                    else -> "BPM"
                },
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        StepGraph(plan, if (on) bar else -1, Modifier.fillMaxWidth().height(64.dp).padding(vertical = 8.dp))
        Stepper("From", "${plan.from} BPM", tick, { practice.setTrainer(plan.copy(from = (plan.from - 1).coerceAtLeast(MetronomeController.MIN_BPM))) }) {
            practice.setTrainer(plan.copy(from = (plan.from + 1).coerceAtMost(MetronomeController.MAX_BPM)))
        }
        Stepper("To", "${plan.to} BPM", tick, { practice.setTrainer(plan.copy(to = (plan.to - 1).coerceAtLeast(MetronomeController.MIN_BPM))) }) {
            practice.setTrainer(plan.copy(to = (plan.to + 1).coerceAtMost(MetronomeController.MAX_BPM)))
        }
        Stepper("Step", "${if (plan.to >= plan.from) "+" else "−"}${plan.step} BPM", tick,
            { practice.setTrainer(plan.copy(step = (plan.step - 1).coerceAtLeast(1))) }) {
            practice.setTrainer(plan.copy(step = (plan.step + 1).coerceAtMost(50)))
        }
        Stepper("Every", "${plan.every} bar${if (plan.every > 1) "s" else ""}", tick,
            { practice.setTrainer(plan.copy(every = (plan.every - 1).coerceAtLeast(1))) }) {
            practice.setTrainer(plan.copy(every = (plan.every + 1).coerceAtMost(64)))
        }
        Toggle("At the top", if (plan.restart) "Start over" else "Hold", tick) { practice.setTrainer(plan.copy(restart = !plan.restart)) }
        CountIn(practice, tick)
    }
}

/** The tempo staircase, with the part you've played drawn in the accent colour. */
@Composable
private fun StepGraph(plan: TrainerPlan, bar: Int, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val s = plan.step.coerceAtLeast(1)
    val steps = ((abs(plan.to - plan.from) + s - 1) / s + 1).coerceIn(1, 40)
    val totalBars = steps * plan.every
    Canvas(modifier) {
        val lo = minOf(plan.from, plan.to).toFloat(); val hi = maxOf(plan.from, plan.to).toFloat().coerceAtLeast(lo + 1)
        fun x(b: Int) = size.width * b / totalBars
        fun y(bpm: Int) = size.height - 4f - (size.height - 8f) * (bpm - lo) / (hi - lo)
        for (b in 0 until totalBars) {
            val now = plan.bpmFor(b)
            val color = if (bar >= 0 && b <= bar % totalBars) accent else track
            drawLine(color, Offset(x(b), y(now)), Offset(x(b + 1), y(now)), strokeWidth = 6f, cap = StrokeCap.Round)
            val next = plan.bpmFor(b + 1)
            if (b + 1 < totalBars && next != now) drawLine(color, Offset(x(b + 1), y(now)), Offset(x(b + 1), y(next)), strokeWidth = 6f)
        }
    }
}

// ── Gap click ──────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.GapClick(practice: PracticeController, on: Boolean, tick: () -> Unit) {
    val plan by practice.gaps.collectAsStateWithLifecycle()
    val gap by practice.gapState.collectAsStateWithLifecycle()
    val (silent, pos, len) = gap
    val countingIn by practice.countingIn.collectAsStateWithLifecycle()
    val countIn by practice.countIn.collectAsStateWithLifecycle()
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        // One round of the pattern: filled = click, dashed = silent
        Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            val c = MaterialTheme.colorScheme
            for (i in 0 until plan.on + plan.off) {
                val isOn = i < plan.on
                val here = on && pos > 0 && ((isOn && !silent && i == pos - 1) || (!isOn && silent && i - plan.on == pos - 1))
                Box(
                    Modifier.weight(1f).height(28.dp).clip(RoundedCornerShape(5.dp))
                        .then(if (isOn) Modifier.background(c.primary) else Modifier.border(1.5.dp, c.outline, RoundedCornerShape(5.dp)))
                        .then(if (here) Modifier.border(2.5.dp, c.onSurface, RoundedCornerShape(5.dp)) else Modifier)
                )
            }
        }
        Text(
            when {
                !on -> "${plan.on} bar${if (plan.on > 1) "s" else ""} on · ${plan.off} silent"
                countingIn > 0 -> "count-in $countingIn of $countIn"
                pos == 0 -> "starting…"
                silent -> "silent $pos of $len"
                else -> "click $pos of $len"
            },
            fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            color = if (on && silent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        )
        Stepper("Click plays", "${plan.on} bar${if (plan.on > 1) "s" else ""}", tick,
            { practice.setGaps(plan.copy(on = (plan.on - 1).coerceAtLeast(1))) }) { practice.setGaps(plan.copy(on = (plan.on + 1).coerceAtMost(16))) }
        Stepper("Then silent", "${plan.off} bar${if (plan.off > 1) "s" else ""}", tick,
            { practice.setGaps(plan.copy(off = (plan.off - 1).coerceAtLeast(1))) }) { practice.setGaps(plan.copy(off = (plan.off + 1).coerceAtMost(8))) }
        Stepper("Random mutes", if (plan.randomMute == 0) "Off" else "${plan.randomMute}% of beats", tick,
            { practice.setGaps(plan.copy(randomMute = (plan.randomMute - 10).coerceAtLeast(0))) }) {
            practice.setGaps(plan.copy(randomMute = (plan.randomMute + 10).coerceAtMost(70)))
        }
        Toggle("Silent bars grow", if (plan.grow) "+1 each round" else "Off", tick) { practice.setGaps(plan.copy(grow = !plan.grow)) }
        CountIn(practice, tick)
        Toggle("Check my timing", if (plan.checkTiming) "On" else "Off", tick) { practice.setGaps(plan.copy(checkTiming = !plan.checkTiming)) }
        if (plan.checkTiming) GapTiming(practice, on)
        Text("The beat blocks keep moving through the gaps, so you can check yourself.",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 6.dp))
    }
}

// ── Timing check ───────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.TimingCheck(practice: PracticeController, on: Boolean) {
    val taps by practice.taps.collectAsStateWithLifecycle()
    DotPlot(taps, Modifier.fillMaxWidth().height(110.dp))
    val stats = PracticeController.stats(taps.map { it.ms })
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("Average", avgText(stats), avgNote(stats), Modifier.weight(1f))
        StatCard("Spread", stats?.let { "±${it.second.roundToInt()} ms" } ?: "–", spreadNote(stats), Modifier.weight(1f))
    }
    TapPad(practice, on, if (on) "Tap here on each beat" else "Press play, then tap along here", Modifier.fillMaxWidth().weight(1f))
}

/** Timing taps while the gap click runs: with the click vs. in the silent bars. */
@Composable
private fun GapTiming(practice: PracticeController, on: Boolean) {
    val taps by practice.taps.collectAsStateWithLifecycle()
    DotPlot(taps, Modifier.fillMaxWidth().height(90.dp).padding(top = 6.dp))
    val withClick = PracticeController.stats(taps.filter { !it.inGap }.map { it.ms })
    val inGaps = PracticeController.stats(taps.filter { it.inGap }.map { it.ms })
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatCard("With the click", avgText(withClick), avgNote(withClick), Modifier.weight(1f))
        StatCard("In the gaps", avgText(inGaps), avgNote(inGaps), Modifier.weight(1f))
    }
    TapPad(practice, on, if (on) "Tap on each beat, gaps included" else "Press play, then tap along here",
        Modifier.fillMaxWidth().height(120.dp))
}

private fun avgText(st: Pair<Double, Double>?) = st?.let { "${if (it.first >= 0) "+" else ""}${it.first.roundToInt()} ms" } ?: "–"
private fun avgNote(st: Pair<Double, Double>?) = st?.let { m ->
    when { abs(m.first) < 8 -> "right on"; m.first < 0 -> if (m.first < -25) "rushing" else "a bit early"; else -> if (m.first > 25) "dragging" else "a bit late" }
} ?: "tap a few beats"
private fun spreadNote(st: Pair<Double, Double>?) = st?.let { when { it.second < 12 -> "very steady"; it.second < 25 -> "steady"; else -> "uneven" } } ?: ""

/**
 * Dot plot: newest tap at the bottom, early to the left, late to the right
 * (±60 ms wide). Taps in a silent bar are drawn hollow.
 */
@Composable
private fun DotPlot(taps: List<Tap>, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Box(modifier.clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant)) {
        Canvas(Modifier.fillMaxSize().padding(horizontal = 8.dp, vertical = 6.dp)) {
            val mid = size.width / 2
            drawLine(c.outline, Offset(mid, 0f), Offset(mid, size.height - 14f), strokeWidth = 3f,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(8f, 8f)))
            val rowH = (size.height - 18f) / PracticeController.MAX_TAPS
            taps.forEachIndexed { i, t ->
                val x = (mid + (t.ms.coerceIn(-60.0, 60.0) / 60.0) * (mid - 8f)).toFloat()
                val color = if (abs(t.ms) < 15) c.primary else c.tertiary
                if (t.inGap) drawCircle(color, radius = 6f, center = Offset(x, 6f + i * rowH), style = Stroke(width = 3f))
                else drawCircle(color, radius = 7f, center = Offset(x, 6f + i * rowH))
            }
        }
        Text("early", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomStart).padding(8.dp, 2.dp))
        Text("late", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant,
            modifier = Modifier.align(Alignment.BottomEnd).padding(8.dp, 2.dp))
    }
}

/**
 * The tap pad. LEARN: we use the touch's own timestamp (when your finger landed),
 * not "now", so a busy phone doesn't make you look late.
 */
@Composable
private fun TapPad(practice: PracticeController, on: Boolean, hint: String, modifier: Modifier) {
    val c = MaterialTheme.colorScheme
    Box(
        modifier.clip(RoundedCornerShape(14.dp))
            .border(1.5.dp, c.outline, RoundedCornerShape(14.dp))
            .pointerInput(on) {
                if (!on) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    practice.tap(down.uptimeMillis * 1_000_000L)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(hint, style = MaterialTheme.typography.titleMedium, color = c.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun StatCard(label: String, value: String, note: String, modifier: Modifier) {
    Column(modifier.clip(RoundedCornerShape(10.dp)).background(MaterialTheme.colorScheme.surfaceVariant).padding(10.dp)) {
        Text(label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 20.sp, fontWeight = FontWeight.Bold)
        Text(note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

// ── small parts ────────────────────────────────────────────────────────────

@Composable
private fun <T> Segmented(options: List<Pair<String, T>>, selected: T, onPick: (T) -> Unit) {
    val c = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(c.surfaceVariant).padding(3.dp),
        horizontalArrangement = Arrangement.spacedBy(3.dp)) {
        options.forEach { (label, value) ->
            val on = value == selected
            Text(
                label, textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                color = if (on) c.onPrimary else c.onSurface,
                modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                    .background(if (on) c.primary else Color.Transparent)
                    .clickable { onPick(value) }.padding(vertical = 8.dp),
            )
        }
    }
}

/** Count-in: Off, 1 bar or 2 bars of click before the exercise starts. */
@Composable
private fun CountIn(practice: PracticeController, tick: () -> Unit) {
    val n by practice.countIn.collectAsStateWithLifecycle()
    Toggle("Count-in", when (n) { 0 -> "Off"; 1 -> "1 bar"; else -> "2 bars" }, tick) { practice.setCountIn((n + 1) % 3) }
}

/** A setting row with − and + buttons (each press gives a haptic tick). */
@Composable
private fun Stepper(label: String, value: String, tick: () -> Unit, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant).padding(start = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium,
            textAlign = TextAlign.End, modifier = Modifier.weight(1f).padding(end = 8.dp))
        StepButton("−") { onMinus(); tick() }
        StepButton("+") { onPlus(); tick() }
    }
}

@Composable
private fun StepButton(text: String, onClick: () -> Unit) {
    Box(Modifier.size(46.dp).clickable(onClick = onClick), contentAlignment = Alignment.Center) {
        Text(text, fontSize = 22.sp, color = MaterialTheme.colorScheme.primary)
    }
}

@Composable
private fun Toggle(label: String, value: String, tick: () -> Unit, onToggle: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 3.dp).clip(RoundedCornerShape(10.dp))
            .background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable { onToggle(); tick() }.padding(horizontal = 12.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f))
        Text(value, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.primary)
    }
}
