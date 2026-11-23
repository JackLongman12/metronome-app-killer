package com.metronomekiller.app.analysis

import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

/**
 * Checks the Kotlin tempo-map builder against the Python original.
 *
 * LEARN — unit tests: small programs that run on your PC (no phone needed) and
 * fail loudly if the code's behaviour changes. Run them with `./gradlew test`.
 * The fixtures in src/test/resources/tempo were made by
 * tools/beat-lab/make_test_fixtures.py: the beat model's real output on
 * synthetic tracks, plus the sections tempo_map.py built from it.
 */
class TempoMapBuilderTest {
    private fun check(name: String) {
        val o = JSONObject(javaClass.getResource("/tempo/$name.json")!!.readText())
        fun arr(k: String) = o.getJSONArray(k).let { a -> DoubleArray(a.length()) { a.getDouble(it) } }
        val act = arr("act").let { a -> FloatArray(a.size) { a[it].toFloat() } }
        val map = TempoMapBuilder.build(arr("beats"), arr("downbeats"), o.getDouble("duration"), act)
        val got = map.sections.filter { it.kind != Section.Kind.FREE }
        val exp = o.getJSONArray("expected").let { a -> List(a.length()) { a.getJSONObject(it) } }.filter { it.getString("kind") != "free" }
        assertEquals("$name: number of sections (${got.map { "${it.bpm} ${it.meter}" }})", exp.size, got.size)
        exp.zip(got).forEach { (e, g) ->
            assertEquals("$name kind", e.getString("kind").uppercase(), g.kind.name)
            assert(abs(e.getDouble("bpm") - g.bpm) < 0.6) { "$name bpm ${e.getDouble("bpm")} vs ${g.bpm}" }
            assertEquals("$name beats per bar at ${g.start}", e.getInt("beats_per_bar"), g.beatsPerBar)
            assert(abs(e.getDouble("start") - g.start) < 0.05) { "$name start ${e.getDouble("start")} vs ${g.start}" }
        }
    }

    @Test fun meterChange4to7to4() = check("syn_meter_4_7_4")
    @Test fun mixedTempoAndMeter() = check("syn_mixed")
    @Test fun ramp() = check("syn_ramp90_130")
    @Test fun tempoJump() = check("syn_jump100_140")
}
