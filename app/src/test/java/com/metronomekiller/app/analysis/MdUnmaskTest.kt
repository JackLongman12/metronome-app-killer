package com.metronomekiller.app.analysis

import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

/**
 * The app's masked-diffusion loop must make exactly the same decisions as the
 * Python reference. Both run with the same deterministic stand-in "network"
 * (fixtures from tools/beat-lab/make_md_fixtures.py), so any difference is a
 * porting bug in the loop itself.
 */
class MdUnmaskTest {
    private fun spect(L: Int, pad: Int) = Array(L) { i ->
        FloatArray(128).also { if (i < L - pad) { it[0] = ((i * 7919) % 100) / 100f; it[1] = 1f } }
    }

    private val fakeNet: (Array<FloatArray>, LongArray, LongArray) -> Pair<FloatArray, FloatArray> = { sp, bt, dt ->
        val L = bt.size
        FloatArray(L) { i ->
            val tb = if (bt[i] == 3L) 0.8 else if (bt[i] == 2L) -0.3 else 0.0
            (3 * sin(0.37 * i) + 0.5 * sp[i][0].toDouble() + tb).toFloat()
        } to FloatArray(L) { i ->
            val td = if (dt[i] == 3L) 0.5 else 0.0
            (2 * cos(0.11 * i) - 1 + td).toFloat()
        }
    }

    @Test
    fun matchesPythonReference() {
        val cases = JSONArray(javaClass.getResource("/md/unmask_cases.json")!!.readText())
        for (c in 0 until cases.length()) {
            val o = cases.getJSONObject(c)
            val L = o.getInt("L"); val pad = o.getInt("pad"); val steps = o.getInt("steps"); val pl = o.getInt("prefix_len")
            val prefix = if (pl > 0) BooleanArray(pl) { it % 9 == 0 } to BooleanArray(pl) { it % 36 == 0 } else null
            val (beat, down) = MdUnmask.run(spect(L, pad), steps, prefix, fakeNet)
            fun idx(a: BooleanArray) = a.indices.filter { a[it] }
            fun exp(k: String) = o.getJSONArray(k).let { a -> List(a.length()) { a.getInt(it) } }
            assertEquals("case $c beats", exp("beats"), idx(beat))
            assertEquals("case $c downbeats", exp("downbeats"), idx(down))
        }
    }
}
