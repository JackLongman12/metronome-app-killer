package com.metronomekiller.app.analysis

import kotlin.math.abs
import kotlin.math.roundToInt


/**
 * Hand fixes for a tempo map (the Edit mode on the song screen).
 *
 * Every fix works on a stretch of bars [first, end) and follows one rule:
 * the beats you hear stay locked to the music. We only add beats in between
 * (×2), drop every other one (½), or regroup them into bars differently
 * (shift bar 1, meter). Afterwards the parts (sections) are worked out again
 * from the bars, so the BPM readout and the part rows match the fix.
 *
 * LEARN: each function returns a NEW TempoMap instead of changing the old one
 * ("immutable data"). That makes Undo trivial: keep a list of earlier maps.
 */
object MapEditor {

    /** ½: the AI heard eighth notes as beats. Keep every other beat, same meter. */
    fun halfTempo(map: TempoMap, first: Int, end: Int): TempoMap = edit(map, first, end) { beats, bars ->
        regroup(beats.filterIndexed { i, _ -> i % 2 == 0 }, usual(bars), 0, bars.first().noteValue)
    }

    /** ×2: the AI heard half notes as beats. Add a beat between each pair, same meter. */
    fun doubleTempo(map: TempoMap, first: Int, end: Int): TempoMap = edit(map, first, end) { beats, bars ->
        val stop = stretchEnd(map, end, beats)
        val out = ArrayList<Double>()
        for (i in beats.indices) {
            val next = beats.getOrElse(i + 1) { stop }
            out += beats[i]; out += (beats[i] + next) / 2
        }
        regroup(out, usual(bars), 0, bars.first().noteValue)
    }

    /**
     * Move the "1" by one beat (+1 later, -1 earlier). The first bar of the
     * stretch becomes a short "pickup" bar, like a song that starts before
     * beat 1. Pressing again keeps moving it.
     */
    fun shiftDownbeat(map: TempoMap, first: Int, end: Int, by: Int): TempoMap = edit(map, first, end) { beats, bars ->
        val n = usual(bars)
        val pickup = if (bars.size > 1 && bars.first().beats < n) bars.first().beats else 0 // made by an earlier shift
        regroup(beats, n, ((pickup + by) % n + n) % n, bars.first().noteValue)
    }

    /** Regroup the stretch into bars of [beats]/[noteValue] (e.g. 7/8). Beats don't move. */
    fun setMeter(map: TempoMap, first: Int, end: Int, beats: Int, noteValue: Int): TempoMap = edit(map, first, end) { b, _ ->
        regroup(b, beats, 0, noteValue)
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    /** A new bar plus the exact times of its beats. */
    private class NewBar(val bar: Bar, val beats: List<Double>)

    /** The bar length most of these bars have. */
    private fun usual(bars: List<Bar>) = bars.groupingBy { it.beats }.eachCount().maxByOrNull { it.value }!!.key

    /** Where the stretch ends: the next bar, or one beat after the last beat. */
    private fun stretchEnd(map: TempoMap, end: Int, beats: List<Double>): Double =
        map.bars.getOrNull(end)?.start ?: run {
            val last = beats.last()
            last + if (beats.size > 1) last - beats[beats.size - 2] else 0.5
        }

    /**
     * Cuts out bars [first, end) and their beats, lets [fix] regroup the beats
     * into new bars, then splices everything back together.
     */
    private fun edit(map: TempoMap, first: Int, end: Int, fix: (List<Double>, List<Bar>) -> List<NewBar>): TempoMap {
        val a = first.coerceIn(0, map.bars.size)
        val b = end.coerceIn(a, map.bars.size)
        if (a == b) return map
        val t0 = map.bars[a].start
        val t1 = map.bars.getOrNull(b)?.start ?: Double.MAX_VALUE
        val inside = map.beats.filter { it >= t0 - 1e-6 && it < t1 - 1e-6 }
        if (inside.isEmpty()) return map
        val oldBars = map.bars.subList(a, b)
        val made = fix(inside, oldBars)
        // Part names ("Chorus") move to the first new bar at or after where they were.
        val names = HashMap<Int, String>()
        for (ob in oldBars) {
            val m = ob.marker ?: continue
            val k = made.indexOfFirst { it.bar.start >= ob.start - 1e-6 }.let { if (it < 0) made.size - 1 else it }
            names.putIfAbsent(k, m)
        }
        val newBars = made.mapIndexed { k, nb -> nb.bar.copy(marker = names[k]) }
        val beats = (map.beats.filter { it < t0 - 1e-6 } + made.flatMap { it.beats } + map.beats.filter { it >= t1 - 1e-6 }).toDoubleArray()
        val bars = map.bars.subList(0, a) + newBars + map.bars.subList(b, map.bars.size)
        return rebuild(map.duration, beats, bars)
    }

    /** Beats → bars of [n] beats; with [phase] > 0 the first bar holds only that many (a pickup). */
    private fun regroup(beats: List<Double>, n: Int, phase: Int, noteValue: Int): List<NewBar> {
        val out = ArrayList<NewBar>()
        var i = 0
        var len = if (phase > 0) phase else n
        while (i < beats.size) {
            val chunk = beats.subList(i, minOf(beats.size, i + len)).toList()
            out += NewBar(Bar(chunk.first(), chunk.size, noteValue), chunk)
            i += len; len = n
        }
        return out
    }

    /**
     * Sections from bars: consecutive bars with the same meter and a steady beat
     * (within 4 % of the stretch's median) form one part. Gaps before the first
     * and after the last beat are "free time", as in the AI's maps.
     */
    fun rebuild(duration: Double, beats: DoubleArray, bars: List<Bar>): TempoMap {
        val sections = ArrayList<Section>()
        val lastIbi = if (beats.size > 1) beats.last() - beats[beats.size - 2] else 0.5
        fun barEnd(i: Int): Double = bars.getOrNull(i + 1)?.start ?: (beats.last() + lastIbi)
        fun ibi(i: Int) = (barEnd(i) - bars[i].start) / bars[i].beats
        var a = 0
        while (a < bars.size) {
            var b = a
            val ref = ArrayList<Double>().apply { add(ibi(a)) }
            while (b + 1 < bars.size && bars[b + 1].meter == bars[a].meter &&
                abs(ibi(b + 1) / ref.sorted()[ref.size / 2] - 1) < 0.04) { b++; ref += ibi(b) }
            val s = bars[a].start; val e = barEnd(b)
            val nBeats = (a..b).sumOf { bars[it].beats }
            val bpm = (nBeats * 60.0 / (e - s) * 10).roundToInt() / 10.0
            sections += Section(s, e, Section.Kind.STEADY, bpm, bpm, bars[a].beats, nBeats, s, false, bars[a].noteValue)
            a = b + 1
        }
        if (sections.isNotEmpty() && sections.first().start > 1.0)
            sections.add(0, Section(0.0, sections.first().start, Section.Kind.FREE, 0.0, 0.0, 0, 0, 0.0, false))
        if (sections.isNotEmpty() && duration - sections.last().end > 1.0)
            sections += Section(sections.last().end, duration, Section.Kind.FREE, 0.0, 0.0, 0, 0, sections.last().end, false)
        val main = sections.filter { it.kind == Section.Kind.STEADY }.maxByOrNull { it.end - it.start }?.bpm ?: 0.0
        return TempoMap(duration, main, beats, bars.map { it.start }.toDoubleArray(), bars, sections)
    }
}
