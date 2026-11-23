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
 * At the top it holds, or with [restart] goes back to [from] and climbs again.
 * (If [to] is below [from], it slows down instead.)
 */
data class TrainerPlan(val from: Int = 90, val to: Int = 130, val step: Int = 4, val every: Int = 4, val restart: Boolean = false) {
    /** Tempo for bar [bar] (0 = the first bar). */
    fun bpmFor(bar: Int): Int {
        val dir = if (to >= from) 1 else -1
        val s = step.coerceAtLeast(1)
        val steps = (abs(to - from) + s - 1) / s + 1 // how many tempos the climb has (the last one is exactly [to])
        var idx = bar / every.coerceAtLeast(1)
        idx = if (restart) idx % steps else minOf(idx, steps - 1)
        val bpm = from + dir * idx * s
        return if (dir > 0) minOf(bpm, to) else maxOf(bpm, to)
    }
}

/** Gap click settings: [on] bars of click, then [off] silent ones, repeating. */
data class GapPlan(val on: Int = 2, val off: Int = 2, val grow: Boolean = false, val randomMute: Int = 0)

/**
 * Follows the gap pattern bar by bar, exactly like the C++ engine does
 * (ClickEngine.cpp, "Gap click"), so the screen can say "silent 2 of 3".
 */
class GapCounter(private val plan: GapPlan) {
    var barInRound = -1; private set
    var round = 0; private set
    val offNow get() = minOf(8, plan.off + if (plan.grow) round else 0)
    val silent get() = barInRound >= plan.on

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
    enum class Tool { SPEED, GAPS, TIMING }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val store = context.getSharedPreferences("practice", Context.MODE_PRIVATE)

    private val _tool = MutableStateFlow(Tool.entries.getOrElse(store.getInt("tool", 0)) { Tool.SPEED })
    val tool: StateFlow<Tool> = _tool
    private val _trainer = MutableStateFlow(TrainerPlan(
        store.getInt("t_from", 90), store.getInt("t_to", 130), store.getInt("t_step", 4),
        store.getInt("t_every", 4), store.getBoolean("t_restart", false)))
    val trainer: StateFlow<TrainerPlan> = _trainer
    private val _gaps = MutableStateFlow(GapPlan(
        store.getInt("g_on", 2), store.getInt("g_off", 2), store.getBoolean("g_grow", false), store.getInt("g_mute", 0)))
    val gaps: StateFlow<GapPlan> = _gaps

    /** The tool that's playing right now, or null. */
    private val _running = MutableStateFlow<Tool?>(null)
    val running: StateFlow<Tool?> = _running
    /** Bars since you pressed play (0 = first bar). */
    private val _bar = MutableStateFlow(-1)
    val bar: StateFlow<Int> = _bar
    /** Gap click: (silent?, which bar of that stretch, how long the stretch is). */
    private val _gapState = MutableStateFlow(Triple(false, 0, 0))
    val gapState: StateFlow<Triple<Boolean, Int, Int>> = _gapState
    /** Timing check: your recent taps, ms early (−) or late (+). */
    private val _taps = MutableStateFlow<List<Double>>(emptyList())
    val taps: StateFlow<List<Double>> = _taps

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
        store.edit { putInt("t_from", p.from); putInt("t_to", p.to); putInt("t_step", p.step); putInt("t_every", p.every); putBoolean("t_restart", p.restart) }
    }

    fun setGaps(p: GapPlan) {
        _gaps.value = p
        store.edit { putInt("g_on", p.on); putInt("g_off", p.off); putBoolean("g_grow", p.grow); putInt("g_mute", p.randomMute) }
        if (_running.value == Tool.GAPS) start(Tool.GAPS) // restart the pattern with the new numbers
    }

    fun toggle() = if (_running.value != null) stop() else start(_tool.value)

    fun start(t: Tool) {
        if (metronome.state.value.playing) metronome.stop()
        cleanUp()
        sawPlaying = false
        _running.value = t
        _bar.value = -1
        _taps.value = emptyList()
        val gapCounter = if (t == Tool.GAPS) GapCounter(_gaps.value) else null
        when (t) {
            Tool.SPEED -> metronome.setBpm(_trainer.value.bpmFor(0))
            Tool.GAPS -> {
                val g = _gaps.value
                NativeEngine.nSetGap(g.on, g.off, if (g.grow) 1 else 0)
                NativeEngine.nSetRandomMute(g.randomMute)
            }
            Tool.TIMING -> Unit
        }
        follower = scope.launch {
            metronome.beatEvents.collect { e ->
                if (e.index != 0) return@collect
                val bar = _bar.value + 1
                _bar.value = bar
                if (t == Tool.SPEED) {
                    val plan = _trainer.value
                    val now = plan.bpmFor(bar)
                    if (metronome.state.value.bpm != now) metronome.setBpm(now) // engine already switched; this updates the screen
                    val next = plan.bpmFor(bar + 1)
                    if (next != now) NativeEngine.nSetTempoAtNextBar(metronome.state.value.meter.beatsPerMinute(next))
                }
                gapCounter?.let { c ->
                    c.nextBar()
                    val len = if (c.silent) c.offNow else _gaps.value.on
                    val pos = if (c.silent) c.barInRound - _gaps.value.on + 1 else c.barInRound + 1
                    _gapState.value = Triple(c.silent, pos, len)
                }
            }
        }
        metronome.start()
    }

    fun stop() {
        metronome.stop()
        cleanUp()
    }

    private fun cleanUp() {
        follower?.cancel(); follower = null
        NativeEngine.nSetGap(0, 0, 0)
        NativeEngine.nSetRandomMute(0)
        NativeEngine.nSetTempoAtNextBar(-1.0)
        _running.value = null
        _gapState.value = Triple(false, 0, 0)
    }

    /**
     * Timing check: a tap at [tapNs] (the touch's own timestamp). Taps more than
     * half a beat from any click are ignored (you were between beats).
     */
    fun tap(tapNs: Long) {
        if (_running.value != Tool.TIMING) return
        val raw = metronome.tapOffsetMs(tapNs) ?: return
        // The engine knows when the sound left the phone; your calibrated offset
        // adds the part it can't see (Bluetooth), same as Settings → Audio sync.
        val d = raw - metronome.prefs.latencyMs(metronome.route.value.key)
        val s = metronome.state.value
        val beatMs = 60_000.0 / s.meter.beatsPerMinute(s.bpm)
        if (abs(d) > beatMs / 2) return
        _taps.value = (_taps.value + d).takeLast(MAX_TAPS)
    }

    companion object {
        const val MAX_TAPS = 24

        /** Average and spread (standard deviation) of tap offsets, in ms. */
        fun stats(taps: List<Double>): Pair<Double, Double>? {
            if (taps.size < 2) return null
            val mean = taps.average()
            val sd = sqrt(taps.sumOf { (it - mean) * (it - mean) } / (taps.size - 1))
            return mean to sd
        }
    }
}
