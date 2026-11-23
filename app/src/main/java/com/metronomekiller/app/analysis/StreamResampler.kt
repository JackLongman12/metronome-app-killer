package com.metronomekiller.app.analysis

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin

/**
 * Changes the sample rate of a stream of audio (e.g. 44100 or 48000 → 22050 Hz).
 *
 * LEARN — windowed-sinc resampling: each output sample sits "between" input
 * samples. The ideal way to estimate it is a weighted sum of nearby inputs,
 * weighted by the sinc function sin(x)/x, which also removes frequencies the new
 * rate can't represent (otherwise they'd fold back as noise, called aliasing).
 * We use 32 neighbours, smoothed by a Hann window. That's plenty for beat
 * tracking, and fast enough to resample a whole song in a second or two.
 *
 * It works on a stream: push chunks in with [process], call [flush] at the end.
 */
class StreamResampler(private val inRate: Int, private val outRate: Int) {
    private val halfTaps = 16
    private val step = inRate.toDouble() / outRate           // input samples per output sample
    private val cutoff = min(1.0, outRate.toDouble() / inRate) * 0.95

    // Input history: samples [bufStart, bufStart + bufLen) of the whole stream.
    private var buf = FloatArray(8192)
    private var bufLen = 0
    private var bufStart = 0L
    private var t = 0.0 // position of the next output sample, in input samples

    fun process(input: FloatArray, count: Int, out: GrowableFloats) {
        if (inRate == outRate) {
            for (i in 0 until count) out.add(input[i])
            return
        }
        ensure(bufLen + count)
        System.arraycopy(input, 0, buf, bufLen, count)
        bufLen += count
        produce(out, final = false)
    }

    fun flush(out: GrowableFloats) {
        if (inRate != outRate) produce(out, final = true)
    }

    private fun produce(out: GrowableFloats, final: Boolean) {
        val end = bufStart + bufLen
        while (true) {
            val center = floor(t).toLong()
            if (!final && center + halfTaps >= end) break
            if (final && center >= end) break
            var acc = 0.0
            for (k in (center - halfTaps + 1)..(center + halfTaps)) {
                val idx = (k - bufStart).toInt()
                if (idx < 0 || idx >= bufLen) continue // outside the song = silence
                val x = (t - k)
                val window = 0.5 + 0.5 * cos(PI * x / halfTaps)
                val arg = PI * x * cutoff
                val sinc = if (arg == 0.0) 1.0 else sin(arg) / arg
                acc += buf[idx] * sinc * window * cutoff
            }
            out.add(acc.toFloat())
            t += step
        }
        // Drop history we'll never need again.
        val keepFrom = (floor(t).toLong() - halfTaps - 1).coerceAtLeast(bufStart)
        val drop = (keepFrom - bufStart).toInt()
        if (drop > 0) {
            System.arraycopy(buf, drop, buf, 0, bufLen - drop)
            bufLen -= drop
            bufStart += drop
        }
    }

    private fun ensure(n: Int) {
        if (n > buf.size) buf = buf.copyOf(maxOf(n, buf.size * 2))
    }
}
