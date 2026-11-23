package com.metronomekiller.app.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import java.nio.LongBuffer
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The "accurate" beat model: Masked-Diffusion Beat This (Foscarin, Korzeniowski,
 * Vogl, ISMIR 2026; research/non-commercial licence, fine for this hobby app).
 *
 * LEARN — how masked diffusion works: instead of guessing every beat in one go,
 * the model starts with every frame marked "unknown" (masked). Each pass, it
 * predicts everything, but we only COMMIT the frames it's most sure about. The
 * next pass sees those committed answers as hints, so later guesses agree with
 * earlier ones: fewer doubled beats, fewer tempo flips. After [steps] passes
 * everything is decided.
 *
 * This is a line-by-line port of md_pipeline()/unmask() in
 * tools/beat-lab/export_md_phone.py, which was checked against the original.
 */
class MdBeatModel(
    private val env: OrtEnvironment,
    private val spectSession: OrtSession,
    private val netSession: OrtSession,
) : AutoCloseable {

    override fun close() { spectSession.close(); netSession.close() }

    companion object {
        private const val BASE = "https://github.com/JackLongman12/metronome-app-killer/releases/download/model-md-v1"
        val FILES = listOf(
            ModelFiles.Spec("md_spect.onnx", "$BASE/md_spect.onnx", "60570ea80fd290e5d49572858db36f85eb92e876091b96d89b39203448f85050"),
            ModelFiles.Spec("md_net_int8.onnx", "$BASE/md_net_int8.onnx", "c0639cad43ac934c14a0f2002c3e7df7381a81be2c9fd6a05bb803ecdddf24ac"),
        )
        const val SIZE_MB = 29
        const val STEPS = 8

        fun load(context: android.content.Context) =
            MdBeatModel(OrtEnvironment.getEnvironment(), ModelFiles.open(context, FILES[0]), ModelFiles.open(context, FILES[1]))

        /**
         * Masked-Diffusion outputs yes/no decisions, but the tempo-map builder reads a
         * downbeat SCORE per frame to work out bar lengths. A score track that is high on
         * the model's bar lines and low elsewhere lets the same code work unchanged.
         */
        fun pseudoDownbeatScores(downbeats: DoubleArray, durationSec: Double): FloatArray {
            val act = FloatArray((durationSec * FPS).toInt() + 2) { -4f }
            for (d in downbeats) { val i = (d * FPS).roundToInt(); if (i in act.indices) act[i] = 4f }
            return act
        }

        const val SR = 22050
        private const val N_FFT = 1024
        private const val HOP = 441
        const val FPS = 50
        private const val CHUNK = 1500
        private const val OVERLAP = 500
        private const val BORDER = 6
        private const val DISCARD = 100
        private const val TOL = 3
        private const val MASK = 0L
        private const val PAD = 1L
        private const val NO_EVENT = 2L
        private const val EVENT = 3L
    }

    class Result(val beats: DoubleArray, val downbeats: DoubleArray)

    /** Beats and downbeats for a whole song (22050 Hz mono), with progress 0..1. */
    fun analyse(sig: FloatArray, steps: Int, onProgress: (Float) -> Unit): Result {
        // 1. Trim leading/trailing silence (as the original does).
        val (start, end) = rmsTrimBounds(sig)
        val s = sig.copyOfRange(start, end)

        // 2. Spectrogram of the whole song, in pieces to bound memory.
        val spect = spectrogram(s) // frames × 128
        val total = spect.size + BORDER
        val padded = Array(total) { i -> if (i < BORDER) FloatArray(128) else spect[i - BORDER] }
        val n = total - BORDER

        // 3. 30 s chunks overlapping by 10 s; each is conditioned on the previous one.
        val starts = ArrayList<Int>()
        val chunks = ArrayList<Array<FloatArray>>()
        if (total <= CHUNK) {
            starts += 0
            chunks += Array(CHUNK) { i -> if (i < total) padded[i] else FloatArray(128) }
        } else {
            var st = 0
            while (st < total - CHUNK) { starts += st; st += CHUNK - OVERLAP }
            starts += total - CHUNK
            for (x in starts) chunks += Array(CHUNK) { padded[x + it] }
        }
        val preds = ArrayList<Pair<BooleanArray, BooleanArray>>()
        chunks.forEachIndexed { i, ch ->
            val prefix = if (i > 0) {
                val o = starts[i] - starts[i - 1]
                val (pb, pd) = preds.last()
                pb.copyOfRange(o, CHUNK - DISCARD) to pd.copyOfRange(o, CHUNK - DISCARD)
            } else null
            preds += MdUnmask.run(ch, steps, prefix, ::net) { p -> onProgress((i + p) / chunks.size) }
        }

        // 4. Stitch: each chunk provides the middle of its span.
        val hop = CHUNK - OVERLAP
        val context = (CHUNK - 1) / 2 - (hop - 1) / 2 - BORDER
        val beat = BooleanArray(n); val down = BooleanArray(n)
        starts.forEachIndexed { i, st ->
            val b = if (i == 0) 0 else i * hop + context
            val e = if (i == starts.lastIndex) n else (i + 1) * hop + context
            val shift = BORDER - st
            for (k in b until e) {
                val j = k + shift
                if (j in 0 until CHUNK) { beat[k] = preds[i].first[j]; down[k] = preds[i].second[j] }
            }
        }
        val beats = ArrayList<Double>(); val downs = ArrayList<Double>()
        for (k in 0 until n) if (beat[k]) {
            val t = k.toDouble() / FPS + start.toDouble() / SR
            beats += t
            if (down[k]) downs += t
        }
        return Result(beats.toDoubleArray(), downs.toDoubleArray())
    }

    /** log-mel spectrogram via the md_spect model, computed in ~60 s pieces. */
    private fun spectrogram(s: FloatArray): Array<FloatArray> {
        val pad = N_FFT / 2
        val p = FloatArray(s.size + 2 * pad)
        System.arraycopy(s, 0, p, pad, s.size)
        for (i in 0 until pad) {
            p[pad - 1 - i] = s.getOrElse(i + 1) { 0f }
            p[pad + s.size + i] = s.getOrElse(s.size - 2 - i) { 0f }
        }
        val nFrames = 1 + s.size / HOP
        val out = ArrayList<FloatArray>(nFrames)
        val per = 3000 // frames per piece
        var f = 0
        while (f < nFrames) {
            val fEnd = minOf(f + per, nFrames)
            val from = f * HOP
            val len = minOf((fEnd - 1) * HOP + N_FFT, p.size) - from
            OnnxTensor.createTensor(env, FloatBuffer.wrap(p, from, len).slice(), longArrayOf(1, len.toLong())).use { t ->
                spectSession.run(mapOf("audio" to t)).use { r ->
                    @Suppress("UNCHECKED_CAST")
                    val frames = (r[0].value as Array<Array<FloatArray>>)[0]
                    for (k in 0 until minOf(frames.size, fEnd - f)) out += frames[k]
                }
            }
            f = fEnd
        }
        return out.toTypedArray()
    }

    private fun net(spect: Array<FloatArray>, bt: LongArray, dt: LongArray): Pair<FloatArray, FloatArray> {
        val L = spect.size
        val flat = FloatArray(L * 128)
        for (i in 0 until L) System.arraycopy(spect[i], 0, flat, i * 128, 128)
        val ts = OnnxTensor.createTensor(env, FloatBuffer.wrap(flat), longArrayOf(1, L.toLong(), 128))
        val tb = OnnxTensor.createTensor(env, LongBuffer.wrap(bt), longArrayOf(1, L.toLong()))
        val td = OnnxTensor.createTensor(env, LongBuffer.wrap(dt), longArrayOf(1, L.toLong()))
        try {
            netSession.run(mapOf("spect" to ts, "beat_tokens" to tb, "downbeat_tokens" to td)).use { r ->
                @Suppress("UNCHECKED_CAST")
                return (r[0].value as Array<FloatArray>)[0] to (r[1].value as Array<FloatArray>)[0]
            }
        } finally { ts.close(); tb.close(); td.close() }
    }

    /** First to last 0.1 s window with RMS >= 0.0015 (hop 0.05 s), like the original. */
    private fun rmsTrimBounds(sig: FloatArray): Pair<Int, Int> {
        val win = (0.1 * SR).toInt(); val hop = (0.05 * SR).toInt()
        if (sig.size < win) return 0 to sig.size
        val nWin = (sig.size - win) / hop + 1
        val thr = 1.5e-3 * 1.5e-3
        fun active(i: Int): Boolean {
            var sum = 0.0
            for (k in i * hop until i * hop + win) sum += sig[k] * sig[k]
            return sum / win >= thr
        }
        val first = (0 until nWin).firstOrNull { active(it) } ?: return 0 to sig.size
        val last = (nWin - 1 downTo first + 1).firstOrNull { active(it) } ?: first
        return first * hop to minOf(last * hop + win, sig.size)
    }
}

/**
 * The masked-diffusion unmasking loop, separate from ONNX Runtime so a PC unit
 * test can run it with a stand-in network and compare it with the Python
 * reference (tools/beat-lab/export_md_phone.py).
 */
internal object MdUnmask {
    private const val TOL = 3
    private const val MASK = 0L
    private const val PAD = 1L
    private const val NO_EVENT = 2L
    private const val EVENT = 3L

    // ── the unmasking loop ──────────────────────────────────────────────────

    private fun maxpool(x: FloatArray, k: Int): FloatArray {
        val h = k / 2
        return FloatArray(x.size) { i ->
            var m = Float.NEGATIVE_INFINITY
            for (j in maxOf(0, i - h)..minOf(x.lastIndex, i + h)) if (x[j] > m) m = x[j]
            m
        }
    }

    /** Candidates within TOL frames of an event, excluding the events themselves. */
    private fun nearEvents(events: BooleanArray, candidates: BooleanArray): BooleanArray {
        val out = BooleanArray(events.size)
        for (i in events.indices) {
            if (!candidates[i] || events[i]) continue
            for (j in maxOf(0, i - TOL)..minOf(events.lastIndex, i + TOL)) if (events[j]) { out[i] = true; break }
        }
        return out
    }

    /** The (at most) k candidates with the highest values; ties keep the earlier frame (stable). */
    private fun topK(values: FloatArray, candidates: BooleanArray, k: Int): BooleanArray {
        val sel = BooleanArray(candidates.size)
        val idx = candidates.indices.filter { candidates[it] }
        if (k <= 0 || idx.isEmpty()) return sel
        idx.sortedWith(compareByDescending<Int> { values[it] }.thenBy { it }).take(k).forEach { sel[it] = true }
        return sel
    }

    fun run(
        spect: Array<FloatArray>, steps: Int, prefix: Pair<BooleanArray, BooleanArray>?,
        net: (Array<FloatArray>, LongArray, LongArray) -> Pair<FloatArray, FloatArray>,
        onStep: (Float) -> Unit = {},
    ): Pair<BooleanArray, BooleanArray> {
        val L = spect.size
        val tok = arrayOf(LongArray(L) { MASK }, LongArray(L) { MASK }) // 0 = beat, 1 = downbeat
        val masked = arrayOf(BooleanArray(L) { true }, BooleanArray(L) { true })
        prefix?.let { (pb, pd) ->
            val fixed = arrayOf(pb, pd)
            for (c in 0..1) for (i in fixed[c].indices) { tok[c][i] = if (fixed[c][i]) EVENT else NO_EVENT; masked[c][i] = false }
        }
        // trailing all-zero frames are padding
        var nv = 0
        for (i in 0 until L) if (spect[i].any { it != 0f }) nv = i + 1
        if (nv > 0) for (c in 0..1) for (i in nv until L) { tok[c][i] = PAD; masked[c][i] = false }

        var pred = arrayOf(LongArray(L), LongArray(L))
        for (step in 0 until steps) {
            val (b, d) = net(spect, tok[0], tok[1])
            val logits = arrayOf(b, d)
            val mp = maxpool(b, 2 * TOL + 1)
            pred = arrayOf(
                LongArray(L) { if (b[it] == mp[it] && b[it] > 0) EVENT else NO_EVENT },
                LongArray(L) { if (b[it] == mp[it] && b[it] > 0 && d[it] > 0) EVENT else NO_EVENT },
            )
            val sel = Array(2) { c ->
                val lg = logits[c]; val m = masked[c]
                val pos = BooleanArray(L) { m[it] && lg[it] > 0 }
                val neg = BooleanArray(L) { m[it] && lg[it] <= 0 }
                val nPos = pos.count { it }; val nNeg = neg.count { it }
                val ratio = if (nPos > 0 && nNeg > 0) nNeg.toDouble() / nPos else 1.0
                val budget = ceil((nPos + nNeg).toDouble() / (steps - step)).toInt()
                val kPos = minOf(maxOf(1, roundHalfEven(budget / (1 + ratio))), nPos)
                val conf = FloatArray(L) { abs(lg[it]) }
                var s = topK(conf, pos, kPos).let { a -> val bb = topK(conf, neg, budget - kPos); BooleanArray(L) { a[it] || bb[it] } }
                // spare_suppressed_frames
                val newEv = BooleanArray(L) { s[it] && pred[c][it] == EVENT }
                val nSup = nearEvents(newEv, BooleanArray(L) { m[it] && !s[it] }).count { it }
                val nearNew = nearEvents(newEv, m)
                val removable = BooleanArray(L) { s[it] && pred[c][it] != EVENT && !nearNew[it] }
                val nRem = minOf(nSup, removable.count { it })
                if (nRem > 0) {
                    s = s.copyOf()
                    removable.indices.filter { removable[it] }.sortedWith(compareBy<Int> { conf[it] }.thenBy { it })
                        .take(nRem).forEach { s[it] = false }
                }
                s
            }
            for (c in 0..1) for (i in 0 until L) if (sel[c][i]) { tok[c][i] = pred[c][i]; masked[c][i] = false }
            // a committed downbeat forces a beat there, which wins over nearby new beats
            val forced = BooleanArray(L) { sel[1][it] && tok[1][it] == EVENT && masked[0][it] }
            if (forced.any { it }) {
                for (i in 0 until L) if (forced[i]) { tok[0][i] = EVENT; masked[0][i] = false; sel[0][i] = true }
                val other = BooleanArray(L) { sel[0][it] && !forced[it] && tok[0][it] == EVENT }
                val conflict = nearEvents(forced, other)
                for (i in 0 until L) if (conflict[i]) { tok[0][i] = NO_EVENT; sel[0][i] = false }
            }
            // neighbours of new events become non-events
            for (c in 0..1) {
                val newEv = BooleanArray(L) { sel[c][it] && tok[c][it] == EVENT }
                val sup = nearEvents(newEv, masked[c])
                for (i in 0 until L) if (sup[i]) { tok[c][i] = NO_EVENT; masked[c][i] = false }
            }
            onStep((step + 1f) / steps)
            if (masked.none { m -> m.any { it } } || sel.none { s -> s.any { it } }) break
        }
        for (c in 0..1) for (i in 0 until L) if (masked[c][i]) tok[c][i] = pred[c][i]
        val down = BooleanArray(L) { tok[1][it] == EVENT }
        return BooleanArray(L) { tok[0][it] == EVENT || down[it] } to down
    }

    /** Python's round(): halves go to the even number. */
    private fun roundHalfEven(x: Double): Int {
        val r = x.roundToInt()
        return if (abs(x - kotlin.math.floor(x) - 0.5) < 1e-12 && r % 2 != 0) r - 1 else r
    }

}
