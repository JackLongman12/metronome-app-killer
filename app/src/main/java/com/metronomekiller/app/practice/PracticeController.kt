package com.metronomekiller.app.practice

import android.content.Context
import androidx.core.content.edit
import com.metronomekiller.app.engine.MetronomeController
import com.metronomekiller.app.engine.NativeEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * Speed trainer plan: start at [from], move [step] BPM toward [to] every [every] bars.
 * At the top it holds, starts over, or (pyramid) comes back down to [from] and holds.
 * (If [to] is below [from], it slows down instead.)
 */
data class TrainerPlan(val from: Int = 90, val to: Int = 130, val step: Int = 4, val every: Int = 4, val top: Top = Top.HOLD) {
    enum class Top(val label: String) { HOLD("Hold"), RESTART("Start over"), BACK_DOWN("Come back down") }

    /** How many different tempos the climb has (the last one is exactly [to]). */
    val steps: Int get() { val s = step.coerceAtLeast(1); return (abs(to - from) + s - 1) / s + 1 }

    /** Bars in one full run (up, or up and down for the pyramid). */
    val totalBars: Int get() = every.coerceAtLeast(1) * if (top == Top.BACK_DOWN) 2 * steps - 1 else steps

    /** Tempo for bar [bar] (0 = the first bar). */
    fun bpmFor(bar: Int): Int {
        val dir = if (to >= from) 1 else -1
        var idx = bar.coerceAtLeast(0) / every.coerceAtLeast(1)
        idx = when (top) {
            Top.HOLD -> minOf(idx, steps - 1)
            Top.RESTART -> idx % steps
            // up 0,1,..,n-1 then down n-2,..,0 and hold
            Top.BACK_DOWN -> if (idx < steps) idx else maxOf(0, 2 * (steps - 1) - idx)
        }
        val bpm = from + dir * idx * step.coerceAtLeast(1)
        return if (dir > 0) minOf(bpm, to) else maxOf(bpm, to)
    }
}

/**
 * Gap click settings: [on] bars of click, then [off] silent ones, repeating.
 * [checkTiming] shows a tap pad to see if you drift while the click is gone.
 */
data class GapPlan(val on: Int = 2, val off: Int = 2, val grow: Boolean = false, val randomMute: Int = 0, val checkTiming: Boolean = false)

/** One timing-check tap: ms early (−) or late (+), and whether it fell in a silent bar. */
data class Tap(val ms: Double, val inGap: Boolean)

/**
 * Follows the gap pattern bar by bar, exactly like the C++ engine does
 * (ClickEngine.cpp, "Gap click"), so the screen can say "silent 2 of 3".
 */
class GapCounter(private val plan: GapPlan, lead: Int = 0) {
    /** Negative while counting in. */
    var barInRound = -1 - lead; private set
    var round = 0; private set
    val offNow get() = minOf(8, plan.off + if (plan.grow) round else 0)
    val silent get() = barInRound >= plan.on
    val countingIn get() = barInRound < 0

    fun nextBar() {
        if (++barInRound >= plan.on + offNow) { barInRound = 0; round++ }
    }
}

/**
 * The Practice tab's brain. It runs on top of the normal metronome (same
 * meter, sound and accents) and only adds:
 *  - speed trainer: queues the next tempo in the engine, which switches exactly on the "1"
 *  - gap click: the engine silences whole bars (and random beats) itself, sample-accurately
 *  - timing check: compares your taps with when each click really came out of the speaker
 *
 * LEARN: anything that must land exactly on a beat (silence, tempo changes) is
 * done inside the audio engine. Kotlin hears about beats a little after they're
 * played, which is fine for the screen but too late to change the sound.
 */
class PracticeController(context: Context, private val metronome: MetronomeController) {
    enum class Tool(val label: String) { SPEED("Speed trainer"), GAPS("Gap click"), TIMING("Timing check"), LADDER("Subdivision ladder"), POLY("Polyrhythm") }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = context.getSharedPreferences("practice", Context.MODE_PRIVATE)

    private val _tool = MutableStateFlow(Tool.entries.getOrElse(store.getInt("tool", 0)) { Tool.SPEED })
    val tool: StateFlow<Tool> = _tool
    private val _trainer = MutableStateFlow(TrainerPlan(
        store.getInt("t_from", 90), store.getInt("t_to", 130), store.getInt("t_step", 4),
        store.getInt("t_every", 4),
        TrainerPlan.Top.entries.getOrElse(store.getInt("t_top", if (store.getBoolean("t_restart", false)) 1 else 0)) { TrainerPlan.Top.HOLD }))
    val trainer: StateFlow<TrainerPlan> = _trainer
    private val _gaps = MutableStateFlow(GapPlan(
        store.getInt("g_on", 2), store.getInt("g_off", 2), store.getBoolean("g_grow", false), store.getInt("g_mute", 0),
        store.getBoolean("g_timing", false)))
    val gaps: StateFlow<GapPlan> = _gaps

    private val _ladder = MutableStateFlow(LadderPlan(
        (store.getString("l_rungs", "1,2,3,4") ?: "1,2,3,4").split(',').mapNotNull { it.toIntOrNull() }.filter { it in 1..4 }.ifEmpty { listOf(1, 2, 3, 4) },
        store.getInt("l_every", 2), store.getBoolean("l_down", true)))
    val ladder: StateFlow<LadderPlan> = _ladder
    private val _poly = MutableStateFlow(PolyPlan(store.getInt("p_count", 3), store.getBoolean("p_one", false)))
    val poly: StateFlow<PolyPlan> = _poly

    // ── practice timer and history ──
    private val log = PracticeLog(java.io.File(context.filesDir, "practice_log.json"))
    private val _history = MutableStateFlow(log.load())
    val history: StateFlow<List<LogEntry>> = _history
    /** Daily goal in minutes (0 = none). */
    private val _goal = MutableStateFlow(store.getInt("goal", 0))
    val goal: StateFlow<Int> = _goal
    /** When the current run started (System.currentTimeMillis), or 0. */
    private val _runStartedAt = MutableStateFlow(0L)
    val runStartedAt: StateFlow<Long> = _runStartedAt
    private var topBpm = 0

    /** Count-in bars (0, 1 or 2) before the speed trainer or gap pattern begins. */
    private val _countIn = MutableStateFlow(store.getInt("count_in", 1))
    val countIn: StateFlow<Int> = _countIn
    /** While counting in: which count-in bar (1-based), else 0. */
    private val _countingIn = MutableStateFlow(0)
    val countingIn: StateFlow<Int> = _countingIn

    /** The tool that's playing right now, or null. */
    private val _running = MutableStateFlow<Tool?>(null)
    val running: StateFlow<Tool?> = _running
    /** Subdivision ladder: clicks per beat right now. */
    private val _ladderSub = MutableStateFlow(1)
    val ladderSub: StateFlow<Int> = _ladderSub
    /** Polyrhythm: when the current bar's "1" was heard (System.nanoTime), for the moving dots. */
    private val _barStartNs = MutableStateFlow(0L)
    val barStartNs: StateFlow<Long> = _barStartNs

    /** Bars since you pressed play (0 = first bar). */
    private val _bar = MutableStateFlow(-1)
    val bar: StateFlow<Int> = _bar
    /** Gap click: (silent?, which bar of that stretch, how long the stretch is). */
    private val _gapState = MutableStateFlow(Triple(false, 0, 0))
    val gapState: StateFlow<Triple<Boolean, Int, Int>> = _gapState
    /** Timing check: your recent taps, ms early (−) or late (+). */
    private val _taps = MutableStateFlow<List<Tap>>(emptyList())
    val taps: StateFlow<List<Tap>> = _taps

    private var follower: Job? = null
    /** Set once this practice run has actually started playing (see init). */
    @Volatile private var sawPlaying = false

    init {
        // If the metronome stops from anywhere (notification, other tab), practice stops too.
        // LEARN: this collector runs on another thread and can see the "stopped" from
        // start()'s own metronome.stop() late, after the new run began. So we only react
        // to a stop that comes after this run was seen playing.
        scope.launch {
            metronome.state.collect {
                if (_running.value == null) return@collect
                if (it.playing) sawPlaying = true else if (sawPlaying) cleanUp()
            }
        }
    }

    fun setTool(t: Tool) {
        if (_running.value != null && _running.value != t) stop()
        _tool.value = t; store.edit { putInt("tool", t.ordinal) }
    }

    fun setTrainer(p: TrainerPlan) {
        _trainer.value = p
        store.edit { putInt("t_from", p.from); putInt("t_to", p.to); putInt("t_step", p.step); putInt("t_every", p.every); putInt("t_top", p.top.ordinal) }
    }

    fun setGaps(p: GapPlan) {
        _gaps.value = p
        val old = _gaps.value
        store.edit { putInt("g_on", p.on); putInt("g_off", p.off); putBoolean("g_grow", p.grow); putInt("g_mute", p.randomMute); putBoolean("g_timing", p.checkTiming) }
        // restart the pattern with the new numbers (showing/hiding the tap pad doesn't need that)
        if (_running.value == Tool.GAPS && old.copy(checkTiming = p.checkTiming) != p) start(Tool.GAPS)
    }

    fun setLadder(p: LadderPlan) {
        _ladder.value = p
        store.edit { putString("l_rungs", p.rungs.joinToString(",")); putInt("l_every", p.every); putBoolean("l_down", p.backDown) }
    }

    fun setPoly(p: PolyPlan) {
        _poly.value = p
        store.edit { putInt("p_count", p.count); putBoolean("p_one", p.mainOnlyOne) }
        if (_running.value == Tool.POLY) NativeEngine.nSetPoly(p.count, polySound(), p.mainOnlyOne) // takes effect next bar
    }

    /** A sound that stands out from the metronome's own: woodblock, or clave if you already use woodblock. */
    private fun polySound(): Int {
        val main = metronome.state.value.sound
        return if (main == com.metronomekiller.app.engine.ClickSound.WOODBLOCK) com.metronomekiller.app.engine.ClickSound.CLAVE.ordinal
        else com.metronomekiller.app.engine.ClickSound.WOODBLOCK.ordinal
    }

    fun setGoal(minutes: Int) { _goal.value = minutes; store.edit { putInt("goal", minutes) } }
    fun clearHistory() { log.clear(); _history.value = emptyList() }

    fun setCountIn(bars: Int) { _countIn.value = bars.coerceIn(0, 2); store.edit { putInt("count_in", _countIn.value) } }

    fun toggle() = if (_running.value != null) stop() else start(_tool.value)

    @Synchronized // start/stop come from the screen, cleanUp also from a background watcher
    fun start(t: Tool) {
        if (metronome.state.value.playing) metronome.stop()
        cleanUp()
        sawPlaying = false
        _running.value = t
        _taps.value = emptyList()
        // LEARN: the count-in is just bars "before bar 0": the trainer holds its
        // first tempo and the engine's gap counter starts below zero.
        val lead = if (t == Tool.SPEED || t == Tool.GAPS) _countIn.value else 0
        val gapCounter = if (t == Tool.GAPS) GapCounter(_gaps.value, lead) else null
        when (t) {
            Tool.SPEED -> metronome.setBpm(_trainer.value.bpmFor(0))
            Tool.GAPS -> {
                val g = _gaps.value
                NativeEngine.nSetLeadBars(lead)
                NativeEngine.nSetGap(g.on, g.off, if (g.grow) 1 else 0)
                NativeEngine.nSetRandomMute(g.randomMute)
            }
            Tool.TIMING -> Unit
            Tool.LADDER -> NativeEngine.nSetSubdivision(_ladder.value.subFor(0))
            Tool.POLY -> NativeEngine.nSetPoly(_poly.value.count, polySound(), _poly.value.mainOnlyOne)
        }
        _runStartedAt.value = System.currentTimeMillis()
        topBpm = metronome.state.value.bpm
        _bar.value = -1 - lead
        _countingIn.value = 0
        follower = scope.launch {
            var counted = -1                         // bars since play, count-in included
            metronome.beatEvents.collect { e ->
                if (e.index != 0) return@collect
                counted++
                val bar = counted - lead             // negative while counting in
                _bar.value = bar
                _countingIn.value = if (bar < 0) counted + 1 else 0
                if (t == Tool.SPEED) {
                    val plan = _trainer.value
                    val now = plan.bpmFor(bar.coerceAtLeast(0))
                    if (metronome.state.value.bpm != now) metronome.setBpm(now) // engine already switched; this updates the screen
                    val next = plan.bpmFor((bar + 1).coerceAtLeast(0))
                    if (next != now) NativeEngine.nSetTempoAtNextBar(metronome.state.value.meter.beatsPerMinute(next))
                    topBpm = maxOf(topBpm, now)
                }
                if (t == Tool.LADDER) {
                    val plan = _ladder.value
                    _ladderSub.value = plan.subFor(bar)
                    val next = plan.subFor(bar + 1)
                    if (next != plan.subFor(bar)) NativeEngine.nSetSubdivisionAtNextBar(next)
                }
                if (t == Tool.POLY) _barStartNs.value = System.nanoTime()
                gapCounter?.let { c ->
                    c.nextBar()
                    if (c.countingIn) { _gapState.value = Triple(false, 0, 0); return@let }
                    val len = if (c.silent) c.offNow else _gaps.value.on
                    val pos = if (c.silent) c.barInRound - _gaps.value.on + 1 else c.barInRound + 1
                    _gapState.value = Triple(c.silent, pos, len)
                }
            }
        }
        metronome.start()
    }

    @Synchronized // start/stop come from the screen, cleanUp also from a background watcher
    fun stop() {
        metronome.stop()
        cleanUp()
    }

    @Synchronized // start/stop come from the screen, cleanUp also from a background watcher
    private fun cleanUp() {
        follower?.cancel(); follower = null
        // Write the finished run to the history
        val ran = _running.value
        val started = _runStartedAt.value
        if (ran != null && started > 0) {
            val secs = ((System.currentTimeMillis() - started) / 1000).toInt()
            _history.value = log.add(LogEntry(started, secs, ran.label, describe(ran)))
        }
        _runStartedAt.value = 0L
        // Put the metronome's own subdivision back (the ladder changed it), and switch off the extra voice.
        NativeEngine.nSetSubdivisionAtNextBar(-1)
        NativeEngine.nSetSubdivision(metronome.state.value.subdivision.perBeat)
        NativeEngine.nSetPoly(0, 0, false)
        _barStartNs.value = 0L
        NativeEngine.nSetGap(0, 0, 0)
        NativeEngine.nSetLeadBars(0)
        NativeEngine.nSetRandomMute(0)
        NativeEngine.nSetTempoAtNextBar(-1.0)
        _running.value = null
        _gapState.value = Triple(false, 0, 0)
        _countingIn.value = 0
    }

    /** One line for the history, e.g. "90 → 130 BPM, reached 118". */
    private fun describe(t: Tool): String {
        val s = metronome.state.value
        return when (t) {
            Tool.SPEED -> _trainer.value.let { "${it.from} → ${it.to} BPM, reached $topBpm" }
            Tool.GAPS -> _gaps.value.let { "${it.on} on / ${it.off} silent at ${s.bpm} BPM" + (stats(_taps.value.filter { x -> x.inGap }.map { x -> x.ms })?.let { st -> ", gaps ${fmtMs(st.first)}" } ?: "") }
            Tool.TIMING -> stats(_taps.value.map { it.ms })?.let { "${s.bpm} BPM, average ${fmtMs(it.first)}, spread ±${it.second.toInt()} ms" } ?: "${s.bpm} BPM"
            Tool.LADDER -> "${_ladder.value.rungs.joinToString("→") { LadderPlan.name(it) }} at ${s.bpm} BPM"
            Tool.POLY -> "${_poly.value.count} against ${s.meter.beatCount} at ${s.bpm} BPM"
        }
    }

    /**
     * Timing check: a tap at [tapNs] (the touch's own timestamp). Taps more than
     * half a beat from any click are ignored (you were between beats).
     */
    fun tap(tapNs: Long) {
        val r = _running.value
        if (r != Tool.TIMING && !(r == Tool.GAPS && _gaps.value.checkTiming)) return
        val raw = metronome.tapOffsetMs(tapNs) ?: return
        // The engine knows when the sound left the phone; your calibrated offset
        // adds the part it can't see (Bluetooth), same as Settings → Audio sync.
        val d = raw - metronome.prefs.latencyMs(metronome.route.value.key)
        val s = metronome.state.value
        val beatMs = 60_000.0 / s.meter.beatsPerMinute(s.bpm)
        if (abs(d) > beatMs / 2) return
        _taps.value = (_taps.value + Tap(d, r == Tool.GAPS && _gapState.value.first)).takeLast(MAX_TAPS)
    }

    companion object {
        const val MAX_TAPS = 24

        fun fmtMs(ms: Double) = "${if (ms >= 0) "+" else ""}${ms.toInt()} ms"

        /** Average and spread (standard deviation) of tap offsets, in ms. */
        fun stats(taps: List<Double>): Pair<Double, Double>? {
            if (taps.size < 2) return null
            val mean = taps.average()
            val sd = sqrt(taps.sumOf { (it - mean) * (it - mean) } / (taps.size - 1))
            return mean to sd
        }
    }
}
