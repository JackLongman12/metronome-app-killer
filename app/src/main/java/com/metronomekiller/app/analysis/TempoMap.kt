package com.metronomekiller.app.analysis

import org.json.JSONArray
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sign

/** One stretch of a song with a steady (or smoothly changing) tempo. */
data class Section(
    val start: Double,
    val end: Double,
    val kind: Kind,
    val bpm: Double,          // 0 for free time
    val bpmEnd: Double,
    val beatsPerBar: Int,
    val beats: Int,
    val firstDownbeat: Double,
    val uncertain: Boolean,
    /** Bottom number of the time signature (4 from the AI; tabs can say 8, 16...). BPM counts this note. */
    val noteValue: Int = 4,
) {
    enum class Kind { STEADY, RAMP, FREE }

    val meter: String get() = when {
        kind == Kind.FREE -> "free"
        beatsPerBar == 0 -> "mixed" // a part whose bars change meter (from tabs)
        else -> "$beatsPerBar/$noteValue"
    }
}

/**
 * One bar: where it starts and how many beats it has (songs can change every bar).
 * [noteValue] is the bottom number when known (tabs); the AI assumes 4.
 */
data class Bar(val start: Double, val beats: Int, val noteValue: Int = 4) {
    val meter: String get() = "$beats/$noteValue"
}

/**
 * A song's tempo map: beats, bar lines and sections. Stored as JSON per song.
 *
 * Built by [TempoMapBuilder], a line-by-line port of tools/beat-lab/tempo_map.py
 * (scored against Songsterr tabs on a 23-song benchmark).
 */
data class TempoMap(
    val duration: Double,
    val mainBpm: Double,
    val beats: DoubleArray,
    val downbeats: DoubleArray,
    val bars: List<Bar>,
    val sections: List<Section>,
) {
    fun toJson(): JSONObject = JSONObject().apply {
        put("version", 1)
        put("duration", duration)
        put("main_bpm", mainBpm)
        put("beats", JSONArray(beats.map { (it * 10000).roundToInt() / 10000.0 }))
        put("downbeats", JSONArray(downbeats.map { (it * 10000).roundToInt() / 10000.0 }))
        put("bars", JSONArray(bars.map { JSONObject().put("start", it.start).put("beats", it.beats).put("note_value", it.noteValue) }))
        put("sections", JSONArray(sections.map {
            JSONObject().put("start", it.start).put("end", it.end).put("kind", it.kind.name)
                .put("bpm", it.bpm).put("bpm_end", it.bpmEnd).put("beats_per_bar", it.beatsPerBar)
                .put("beats", it.beats).put("first_downbeat", it.firstDownbeat).put("uncertain", it.uncertain)
                .put("note_value", it.noteValue)
        }))
    }

    companion object {
        fun fromJson(o: JSONObject): TempoMap {
            fun arr(a: JSONArray) = DoubleArray(a.length()) { a.getDouble(it) }
            val bars = o.getJSONArray("bars").let { a -> List(a.length()) { a.getJSONObject(it).let { b -> Bar(b.getDouble("start"), b.getInt("beats"), b.optInt("note_value", 4)) } } }
            val secs = o.getJSONArray("sections").let { a ->
                List(a.length()) {
                    val s = a.getJSONObject(it)
                    Section(
                        s.getDouble("start"), s.getDouble("end"), Section.Kind.valueOf(s.getString("kind")),
                        s.getDouble("bpm"), s.getDouble("bpm_end"), s.getInt("beats_per_bar"), s.getInt("beats"),
                        s.getDouble("first_downbeat"), s.getBoolean("uncertain"), s.optInt("note_value", 4),
                    )
                }
            }
            return TempoMap(o.getDouble("duration"), o.getDouble("main_bpm"), arr(o.getJSONArray("beats")),
                arr(o.getJSONArray("downbeats")), bars, secs)
        }
    }
}

/**
 * Beats → sections. See tempo_map.py for the reasoning behind each step:
 *  1. repair missed/extra beats   2. find tempo changes (dynamic programming)
 *  3. fix half/double-time sections   4. fold short "blips" into neighbours
 *  5. merge equal tempos, detect ramps   6. split where the bar length changes
 *  7. bar length per section   8. free time where beats stop
 */
object TempoMapBuilder {
    private const val PENALTY = 0.004
    private const val MIN_SEG = 8
    private const val GAP_FACTOR = 2.5
    private const val RAMP_STEP = 0.07
    private const val OCTAVE_TOL = 0.05
    private const val TRUST_BEATS = 16
    private const val FPS = 50

    private class Seg(
        var start: Double, var end: Double, var kind: Section.Kind, var bpm: Double,
        var t: DoubleArray, var bpmEnd: Double? = null, var uncertain: Boolean = false,
    )

    fun build(rawBeats: DoubleArray, downbeats: DoubleArray, duration: Double, dbAct: FloatArray?): TempoMap {
        val beats = regularize(rawBeats)
        val mainBpm = if (beats.size > 8) dominantBpm(beats) else 0.0

        // split at gaps, then segment each continuous run
        val runs = ArrayList<Pair<Int, Int>>()
        var start = 0
        if (beats.size > 2) {
            val ibi = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
            for (i in ibi.indices) {
                val local = median(ibi, max(0, i - 4), min(ibi.size, i + 5))
                if (ibi[i] > GAP_FACTOR * local) { runs += start to i + 1; start = i + 1 }
            }
        }
        runs += start to beats.size

        var segs = ArrayList<Seg>()
        for ((ra, rb) in runs) {
            val t = beats.copyOfRange(ra, rb)
            if (t.size < MIN_SEG) continue
            for ((a, b) in segment(t)) {
                val s = t.copyOfRange(a, b)
                segs += Seg(s.first(), s.last() + (s.last() - s[s.size - 2]), Section.Kind.STEADY, fitBpm(s), s)
            }
        }
        fixOctaves(segs, mainBpm)
        segs = absorbBlips(segs)
        segs = merge(segs)
        segs = splitMeters(segs, dbAct)

        var secs = segs.map { s ->
            val (n, phase) = barLength(s.t, dbAct)
            Section(
                start = s.start, end = s.end, kind = s.kind, bpm = round1(s.bpm), bpmEnd = round1(s.bpmEnd ?: s.bpm),
                beatsPerBar = n, beats = s.t.size, firstDownbeat = s.t[phase.coerceAtMost(s.t.lastIndex)], uncertain = s.uncertain,
            )
        }
        secs = mergeSame(secs)

        val final = ArrayList<Section>()
        var cursor = 0.0
        for (s in secs) {
            if (s.start - cursor > 1.0) final += free(cursor, s.start)
            final += s
            cursor = s.end
        }
        if (duration - cursor > 1.0) final += free(cursor, duration)
        return TempoMap(duration, round1(mainBpm), beats, downbeats, bars(beats, downbeats), final)
    }

    // 1 ──────────────────────────────────────────────────────────────────────
    private fun regularize(beats: DoubleArray): DoubleArray {
        if (beats.size < 8) return beats
        val ibi = DoubleArray(beats.size - 1) { beats[it + 1] - beats[it] }
        val out = ArrayList<Double>().apply { add(beats[0]) }
        for (i in ibi.indices) {
            val r = ibi[i] / median(ibi, max(0, i - 8), min(ibi.size, i + 9))
            if (r > 1.7 && r < 3.3) {
                val k = r.roundToInt()
                for (j in 1 until k) out += beats[i] + ibi[i] * j / k
            } else if (r < 0.6 && i + 1 < ibi.size) {
                continue
            }
            out += beats[i + 1]
        }
        return out.toDoubleArray()
    }

    private fun dominantBpm(beats: DoubleArray): Double {
        val bpm = (0 until beats.size - 1).map { 60 / (beats[it + 1] - beats[it]) }.filter { it > 40 && it < 320 }
        if (bpm.isEmpty()) return 0.0
        val hist = IntArray(280)
        for (b in bpm) hist[(b - 40).toInt().coerceIn(0, 279)]++
        val k = hist.indices.maxBy { hist[it] }
        val lo = 40.0 + k - 2
        val hi = 40.0 + k + 1 + 2
        val near = bpm.filter { it in lo..hi }.toDoubleArray()
        return median(near, 0, near.size)
    }

    // 2 ──────────────────────────────────────────────────────────────────────
    /** Best split into constant-tempo pieces: each piece should be a straight line of beat times. */
    private fun segment(t: DoubleArray): List<Pair<Int, Int>> {
        val n = t.size
        if (n < 2 * MIN_SEG) return listOf(0 to n)
        val sx = DoubleArray(n + 1); val sy = DoubleArray(n + 1); val sxx = DoubleArray(n + 1)
        val sxy = DoubleArray(n + 1); val syy = DoubleArray(n + 1)
        for (i in 0 until n) {
            val x = i.toDouble(); val y = t[i]
            sx[i + 1] = sx[i] + x; sy[i + 1] = sy[i] + y; sxx[i + 1] = sxx[i] + x * x
            sxy[i + 1] = sxy[i] + x * y; syy[i + 1] = syy[i] + y * y
        }
        fun sse(a: Int, b: Int): Double {
            val m = (b - a).toDouble()
            val X = sx[b] - sx[a]; val Y = sy[b] - sy[a]
            val vx = sxx[b] - sxx[a] - X * X / m
            val cov = sxy[b] - sxy[a] - X * Y / m
            val vy = syy[b] - syy[a] - Y * Y / m
            return max(vy - cov * cov / max(vx, 1e-12), 0.0)
        }
        val best = DoubleArray(n + 1) { Double.POSITIVE_INFINITY }
        best[0] = 0.0
        val prev = IntArray(n + 1)
        for (b in MIN_SEG..n) {
            var bc = Double.POSITIVE_INFINITY; var ba = 0
            for (a in 0..(b - MIN_SEG)) {
                if (best[a].isInfinite()) continue
                val c = best[a] + sse(a, b) + PENALTY
                if (c < bc) { bc = c; ba = a }
            }
            best[b] = bc; prev[b] = ba
        }
        val cuts = ArrayList<Pair<Int, Int>>()
        var b = n
        while (b > 0) { val a = prev[b]; cuts += a to b; b = a }
        return cuts.reversed()
    }

    private fun fitBpm(t: DoubleArray): Double {
        val n = t.size
        if (n < 2) return 0.0
        val mx = (n - 1) / 2.0
        val my = t.average()
        var num = 0.0; var den = 0.0
        for (i in 0 until n) { num += (i - mx) * (t[i] - my); den += (i - mx) * (i - mx) }
        return 60.0 / (num / den)
    }

    // 3 ──────────────────────────────────────────────────────────────────────
    private fun fixOctaves(secs: List<Seg>, mainBpm: Double) {
        if (secs.size < 2) return
        val w = secs.map { it.t.size * 60 / it.bpm }
        val total = w.sum()
        secs.forEachIndexed { i, s ->
            val neigh = listOfNotNull(secs.getOrNull(i - 1)?.bpm, secs.getOrNull(i + 1)?.bpm)
            val ref = if (mainBpm > 0) mainBpm else neigh.average()
            for (f in doubleArrayOf(2.0, 0.5)) {
                if (abs(s.bpm * f - ref) / ref < OCTAVE_TOL && w[i] < total * 0.5) {
                    s.t = if (f == 2.0) {
                        val mids = DoubleArray(s.t.size - 1) { (s.t[it] + s.t[it + 1]) / 2 }
                        (s.t + mids).sortedArray()
                    } else DoubleArray((s.t.size + 1) / 2) { s.t[it * 2] }
                    s.bpm *= f
                    break
                }
            }
        }
    }

    // 4 ──────────────────────────────────────────────────────────────────────
    private fun absorbBlips(secs: List<Seg>): ArrayList<Seg> {
        val out = ArrayList<Seg>()
        secs.forEachIndexed { i, s ->
            val neigh = listOfNotNull(secs.getOrNull(i - 1)?.bpm, secs.getOrNull(i + 1)?.bpm)
            val short = s.t.size < TRUST_BEATS
            val far = neigh.isNotEmpty() && neigh.all { abs(s.bpm - it) / it > 0.10 }
            if (out.isNotEmpty() && short && far) {
                out.last().end = s.end
                out.last().uncertain = true
            } else out += s
        }
        return out
    }

    // 5 ──────────────────────────────────────────────────────────────────────
    private fun merge(secs: List<Seg>): ArrayList<Seg> {
        val merged = ArrayList<Seg>()
        for (s in secs) {
            val p = merged.lastOrNull()
            if (p != null) {
                val pe = p.bpmEnd ?: p.bpm
                val same = abs(s.bpm - pe) / pe < 0.02
                val step = abs(s.bpm - pe) / pe < RAMP_STEP
                val direction = sign(s.bpm - pe)
                val pDir = if (p.kind == Section.Kind.RAMP) sign(pe - p.bpm) else direction
                if (same && p.kind == Section.Kind.STEADY) {
                    p.t = p.t + s.t; p.bpm = fitBpm(p.t); p.end = s.end
                    continue
                }
                if (step && !same && direction == pDir) {
                    if (p.kind == Section.Kind.STEADY) p.kind = Section.Kind.RAMP
                    p.t = p.t + s.t; p.bpmEnd = s.bpm; p.end = s.end
                    continue
                }
            }
            merged += s
        }
        for (s in merged) {
            val e = s.bpmEnd
            if (s.kind == Section.Kind.RAMP && e != null && abs(e - s.bpm) / s.bpm < 0.04) {
                s.kind = Section.Kind.STEADY
                s.bpm = fitBpm(s.t); s.bpmEnd = s.bpm
            }
        }
        return merged
    }

    // 6 ──────────────────────────────────────────────────────────────────────
    private fun strength(t: DoubleArray, act: FloatArray): DoubleArray = DoubleArray(t.size) { i ->
        val idx = (t[i] * FPS).roundToInt().coerceIn(0, act.lastIndex)
        var m = Float.NEGATIVE_INFINITY
        for (d in -1..1) m = max(m, act[(idx + d).coerceIn(0, act.lastIndex)])
        1.0 / (1.0 + exp(-m.toDouble()))
    }

    /** Best (bar length, first downbeat) for a run of beat strengths. */
    private fun bestBar(w: DoubleArray, from: Int, len: Int, maxN: Int): Pair<Int, Int> {
        var best = Double.NEGATIVE_INFINITY; var bn = 4; var bp = 0
        for (n in 2..maxN) {
            if (len < 2 * n && maxN == 16) break
            for (p in 0 until n) {
                var on = 0.0; var onN = 0; var off = 0.0; var offN = 0
                for (k in 0 until len) {
                    if ((k - p) % n == 0 && k >= p) { on += w[from + k]; onN++ } else { off += w[from + k]; offN++ }
                }
                if (onN == 0 || offN == 0) continue
                val sc = on / onN - off / offN + (if (n == 3 || n == 4) 0.02 else 0.0) - 0.002 * n
                if (sc > best) { best = sc; bn = n; bp = p }
            }
        }
        return bn to bp
    }

    private fun splitMeters(secs: List<Seg>, act: FloatArray?, window: Int = 28): ArrayList<Seg> {
        val out = ArrayList<Seg>()
        if (act == null) { out += secs; return out }
        for (s in secs) {
            val t = s.t
            if (t.size < 2 * window) { out += s; continue }
            val st = strength(t, act)
            val labels = IntArray(t.size)
            for (k in 0..(t.size - window)) labels[k + window / 2] = bestBar(st, k, window, 12).first
            for (i in 0 until window / 2) labels[i] = labels[window / 2]
            val tail = t.size - window + window / 2
            for (i in tail + 1 until t.size) labels[i] = labels[tail]
            val sm = IntArray(t.size) { i ->
                val counts = HashMap<Int, Int>()
                for (j in max(0, i - window / 2) until min(t.size, i + window / 2)) counts.merge(labels[j], 1, Int::plus)
                counts.entries.sortedWith(compareBy({ -it.value }, { it.key })).first().key
            }
            val cuts = arrayListOf(0)
            for (i in 1 until t.size) {
                if (sm[i] != sm[cuts.last()]) {
                    var j = i
                    while (j < t.size && sm[j] == sm[i]) j++
                    if (j - i >= 2 * sm[i]) cuts += i
                }
            }
            if (cuts.size == 1) { out += s; continue }
            cuts += t.size
            for (c in 0 until cuts.size - 1) {
                val a = cuts[c]; val b = cuts[c + 1]
                val pt = t.copyOfRange(a, b)
                out += Seg(t[a], if (b < t.size) t[b] else s.end, s.kind,
                    if (s.kind == Section.Kind.STEADY) fitBpm(pt) else s.bpm, pt, s.bpmEnd, s.uncertain)
            }
        }
        return out
    }

    // 7 ──────────────────────────────────────────────────────────────────────
    private fun barLength(t: DoubleArray, act: FloatArray?): Pair<Int, Int> {
        if (act == null || t.size < 8) return 4 to 0
        return bestBar(strength(t, act), 0, t.size, 16)
    }

    private fun mergeSame(secs: List<Section>): List<Section> {
        val merged = ArrayList<Section>()
        for (s in secs) {
            val p = merged.lastOrNull()
            if (p != null && p.kind == Section.Kind.STEADY && s.kind == Section.Kind.STEADY &&
                p.beatsPerBar == s.beatsPerBar && abs(p.bpm - s.bpm) / p.bpm < 0.02
            ) {
                val w = p.beats + s.beats
                val bpm = round1((p.bpm * p.beats + s.bpm * s.beats) / w)
                merged[merged.lastIndex] = p.copy(end = s.end, bpm = bpm, bpmEnd = bpm, beats = w, uncertain = p.uncertain || s.uncertain)
            } else merged += s
        }
        return merged
    }

    private fun bars(beats: DoubleArray, downbeats: DoubleArray): List<Bar> {
        if (downbeats.size < 2) return emptyList()
        return (0 until downbeats.size - 1).mapNotNull { i ->
            val a = downbeats[i]; val b = downbeats[i + 1]
            val n = beats.count { it >= a - 0.07 && it < b - 0.07 }
            if (n >= 1) Bar(a, n) else null
        }
    }

    private fun free(start: Double, end: Double) =
        Section(start, end, Section.Kind.FREE, 0.0, 0.0, 0, 0, start, false)

    private fun median(a: DoubleArray, from: Int, to: Int): Double {
        if (to <= from) return 0.0
        val s = a.copyOfRange(from, to).sortedArray()
        val m = s.size / 2
        return if (s.size % 2 == 1) s[m] else (s[m - 1] + s[m]) / 2
    }

    private fun round1(x: Double) = (x * 10).roundToInt() / 10.0
}
