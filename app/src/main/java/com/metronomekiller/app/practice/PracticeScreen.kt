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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
        var showHistory by remember { mutableStateOf(false) }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Practice", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.weight(1f))
            TodayChip(practice) { showHistory = true }
        }
        if (showHistory) HistorySheet(practice) { showHistory = false }
        Spacer(Modifier.height(10.dp))
        Segmented(
            listOf("Speed" to PracticeController.Tool.SPEED, "Gaps" to PracticeController.Tool.GAPS, "Timing" to PracticeController.Tool.TIMING,
                "Ladder" to PracticeController.Tool.LADDER, "Poly" to PracticeController.Tool.POLY),
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
            PracticeController.Tool.LADDER -> SubdivisionLadder(practice, running == tool, tick)
            PracticeController.Tool.POLY -> Polyrhythm(practice, metronome, running == tool, tick)
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
        Toggle("At the top", plan.top.label, tick) {
            practice.setTrainer(plan.copy(top = TrainerPlan.Top.entries[(plan.top.ordinal + 1) % TrainerPlan.Top.entries.size]))
        }
        CountIn(practice, tick)
    }
}

/** The tempo staircase, with the part you've played drawn in the accent colour. */
@Composable
private fun StepGraph(plan: TrainerPlan, bar: Int, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.surfaceVariant
    val totalBars = plan.totalBars.coerceIn(1, 400)
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

// ── Subdivision ladder ─────────────────────────────────────────────────────

@Composable
private fun ColumnScope.SubdivisionLadder(practice: PracticeController, on: Boolean, tick: () -> Unit) {
    val plan by practice.ladder.collectAsStateWithLifecycle()
    val now by practice.ladderSub.collectAsStateWithLifecycle()
    val bar by practice.bar.collectAsStateWithLifecycle()
    val c = MaterialTheme.colorScheme
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text(if (on) LadderPlan.name(now) else "Same tempo, more notes",
            fontSize = 30.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        // The cycle, with the current rung highlighted
        val cycle = plan.cycle
        val at = if (on && bar >= 0) (bar / plan.every.coerceAtLeast(1)) % cycle.size else -1
        Row(Modifier.fillMaxWidth().padding(bottom = 10.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            cycle.forEachIndexed { i, n ->
                Box(
                    Modifier.weight(1f).height((14 + n * 10).dp).align(Alignment.Bottom).clip(RoundedCornerShape(5.dp))
                        .background(if (i == at) c.primary else c.surfaceVariant),
                    contentAlignment = Alignment.Center,
                ) { Text("$n", style = MaterialTheme.typography.labelSmall, color = if (i == at) c.onPrimary else c.onSurfaceVariant) }
            }
        }
        Text("Rungs", style = MaterialTheme.typography.labelLarge, color = c.primary, modifier = Modifier.padding(bottom = 4.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            listOf(1, 2, 3, 4).forEach { n ->
                val inIt = n in plan.rungs
                Text(
                    LadderPlan.name(n), textAlign = TextAlign.Center, style = MaterialTheme.typography.labelMedium,
                    color = if (inIt) c.onPrimary else c.onSurface,
                    modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp))
                        .background(if (inIt) c.primary else c.surfaceVariant)
                        .clickable {
                            val next = if (inIt) plan.rungs - n else (plan.rungs + n).sorted()
                            if (next.isNotEmpty()) practice.setLadder(plan.copy(rungs = next))
                            tick()
                        }.padding(vertical = 10.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Stepper("Each rung", "${plan.every} bar${if (plan.every > 1) "s" else ""}", tick,
            { practice.setLadder(plan.copy(every = (plan.every - 1).coerceAtLeast(1))) }) { practice.setLadder(plan.copy(every = (plan.every + 1).coerceAtMost(16))) }
        Toggle("At the top", if (plan.backDown) "Come back down" else "Start over", tick) { practice.setLadder(plan.copy(backDown = !plan.backDown)) }
        Text("Changes land exactly on the next bar's 1. Keep the tempo; only the notes get denser.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

// ── Polyrhythm ─────────────────────────────────────────────────────────────

@Composable
private fun ColumnScope.Polyrhythm(practice: PracticeController, metronome: MetronomeController, on: Boolean, tick: () -> Unit) {
    val plan by practice.poly.collectAsStateWithLifecycle()
    val state by metronome.state.collectAsStateWithLifecycle()
    val beat by metronome.currentBeat.collectAsStateWithLifecycle()
    val barStart by practice.barStartNs.collectAsStateWithLifecycle()
    val m = state.meter.beatCount
    val c = MaterialTheme.colorScheme
    // LEARN: withFrameNanos runs once per screen frame, so the cross-rhythm dot
    // moves smoothly; we work out where in the bar we are from when the "1" was heard.
    var hit by remember { mutableStateOf(-1) }
    LaunchedEffect(on, barStart, plan.count, state.bpm, m) {
        if (!on || barStart == 0L) { hit = -1; return@LaunchedEffect }
        val barNs = m * 60e9 / state.meter.beatsPerMinute(state.bpm)
        while (true) {
            androidx.compose.runtime.withFrameNanos { }
            val f = ((System.nanoTime() - barStart) / barNs).coerceIn(0.0, 0.999)
            hit = (f * plan.count).toInt()
        }
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        Text("${plan.count} against $m", fontSize = 34.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp))
        PolyRow("Beats", m, if (on) beat else -1, c.onSurface)
        Spacer(Modifier.height(8.dp))
        PolyRow("${plan.count}s", plan.count, hit, c.primary)
        Text("Both rows start together on the 1. The second sound plays the ${plan.count}.",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 8.dp))
        Stepper("Cross-rhythm", "${plan.count} per bar", tick,
            { practice.setPoly(plan.copy(count = (plan.count - 1).coerceAtLeast(2))) }) { practice.setPoly(plan.copy(count = (plan.count + 1).coerceAtMost(12))) }
        Toggle("Normal click", if (plan.mainOnlyOne) "Only the 1" else "Every beat", tick) { practice.setPoly(plan.copy(mainOnlyOne = !plan.mainOnlyOne)) }
        Text("Change the beats per bar with the metronome's time signature (4/4 gives 3 against 4).",
            style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
    }
}

/** One row of evenly spaced dots across the bar; [lit] is the one sounding. */
@Composable
private fun PolyRow(label: String, n: Int, lit: Int, color: Color) {
    val c = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = c.onSurfaceVariant, modifier = Modifier.width(52.dp))
        Canvas(Modifier.weight(1f).height(36.dp)) {
            drawLine(c.outlineVariant, Offset(0f, size.height / 2), Offset(size.width, size.height / 2), strokeWidth = 2f)
            for (i in 0 until n) {
                val x = 12f + (size.width - 24f) * i / n
                drawCircle(if (i == lit) color else c.outline, radius = if (i == lit) 13f else 8f, center = Offset(x, size.height / 2))
            }
        }
    }
}

// ── Practice timer and history ─────────────────────────────────────────────

private fun dayStart(): Long = java.util.Calendar.getInstance().apply {
    set(java.util.Calendar.HOUR_OF_DAY, 0); set(java.util.Calendar.MINUTE, 0)
    set(java.util.Calendar.SECOND, 0); set(java.util.Calendar.MILLISECOND, 0)
}.timeInMillis

/** Seconds practised today, ticking up live while a tool runs. */
@Composable
private fun todaySeconds(practice: PracticeController): Int {
    val history by practice.history.collectAsStateWithLifecycle()
    val started by practice.runStartedAt.collectAsStateWithLifecycle()
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(started) {
        while (started > 0) { now = System.currentTimeMillis(); kotlinx.coroutines.delay(1000) }
    }
    val live = if (started > 0) ((now - started) / 1000).toInt().coerceAtLeast(0) else 0
    return PracticeLog.secondsSince(history, dayStart()) + live
}

private fun fmtDuration(sec: Int): String = if (sec >= 3600) "%d:%02d:%02d".format(sec / 3600, sec / 60 % 60, sec % 60) else "%d:%02d".format(sec / 60, sec % 60)

@Composable
private fun TodayChip(practice: PracticeController, onClick: () -> Unit) {
    val goal by practice.goal.collectAsStateWithLifecycle()
    val sec = todaySeconds(practice)
    val c = MaterialTheme.colorScheme
    Column(Modifier.clip(RoundedCornerShape(8.dp)).background(c.surfaceVariant).clickable(onClick = onClick)
        .padding(horizontal = 10.dp, vertical = 5.dp), horizontalAlignment = Alignment.End) {
        Text("Today ${fmtDuration(sec)}${if (goal > 0) " / $goal min" else ""}", style = MaterialTheme.typography.labelLarge)
        if (goal > 0) {
            val f = (sec / (goal * 60f)).coerceIn(0f, 1f)
            Box(Modifier.width(110.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.outlineVariant)) {
                Box(Modifier.fillMaxWidth(f).height(4.dp).background(c.primary))
            }
        } else Text("History", style = MaterialTheme.typography.labelSmall, color = c.onSurfaceVariant)
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun HistorySheet(practice: PracticeController, onDismiss: () -> Unit) {
    val history by practice.history.collectAsStateWithLifecycle()
    val goal by practice.goal.collectAsStateWithLifecycle()
    val today = todaySeconds(practice)
    val week = PracticeLog.secondsSince(history, dayStart() - 6 * 86_400_000L)
    var confirmClear by remember { mutableStateOf(false) }
    val c = MaterialTheme.colorScheme
    androidx.compose.material3.ModalBottomSheet(onDismissRequest = onDismiss) {
        Column(Modifier.padding(horizontal = 20.dp).padding(bottom = 24.dp)) {
            Text("Practice history", style = MaterialTheme.typography.titleMedium)
            Row(Modifier.fillMaxWidth().padding(vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                StatCard("Today", fmtDuration(today), if (goal > 0) "goal $goal min" else "", Modifier.weight(1f))
                StatCard("Last 7 days", fmtDuration(week), "${history.count { it.startedAt >= dayStart() - 6 * 86_400_000L }} sessions", Modifier.weight(1f))
            }
            Text("Daily goal", style = MaterialTheme.typography.labelLarge, color = c.primary)
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                listOf(0, 10, 20, 30, 45, 60).forEach { m ->
                    val sel = m == goal
                    Text(if (m == 0) "Off" else "$m", textAlign = TextAlign.Center, style = MaterialTheme.typography.labelLarge,
                        color = if (sel) c.onPrimary else c.onSurface,
                        modifier = Modifier.weight(1f).clip(RoundedCornerShape(8.dp)).background(if (sel) c.primary else c.surfaceVariant)
                            .clickable { practice.setGoal(m) }.padding(vertical = 8.dp))
                }
            }
            val fmt = remember { java.text.SimpleDateFormat("EEE d MMM, HH:mm", java.util.Locale.getDefault()) }
            androidx.compose.foundation.lazy.LazyColumn(Modifier.fillMaxWidth().height(320.dp)) {
                if (history.isEmpty()) item {
                    Text("Sessions show up here when you stop a practice tool (anything over 20 seconds).",
                        style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant, modifier = Modifier.padding(vertical = 12.dp))
                }
                items(history.reversed().size) { i ->
                    val e = history.reversed()[i]
                    Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                        Row {
                            Text(e.tool, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium, modifier = Modifier.weight(1f))
                            Text(fmtDuration(e.seconds), style = MaterialTheme.typography.bodyMedium)
                        }
                        Text("${fmt.format(java.util.Date(e.startedAt))} · ${e.detail}", style = MaterialTheme.typography.bodySmall, color = c.onSurfaceVariant)
                    }
                }
            }
            if (history.isNotEmpty()) Text(
                if (confirmClear) "Tap again to delete all history" else "Clear history",
                style = MaterialTheme.typography.labelLarge, color = if (confirmClear) c.error else c.primary,
                modifier = Modifier.clickable { if (confirmClear) { practice.clearHistory(); confirmClear = false } else confirmClear = true }
                    .padding(vertical = 10.dp),
            )
        }
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
