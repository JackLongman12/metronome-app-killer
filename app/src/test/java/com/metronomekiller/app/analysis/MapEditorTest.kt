package com.metronomekiller.app.analysis

import org.junit.Assert.assertEquals
import org.junit.Test
import kotlin.math.abs

class MapEditorTest {
    /** [bars] bars of 4 beats at [bpm], starting at 1 s, in a 60 s song. */
    private fun steady(bpm: Double, bars: Int, beatsPerBar: Int = 4): TempoMap {
        val ibi = 60.0 / bpm
        val beats = DoubleArray(bars * beatsPerBar) { 1.0 + it * ibi }
        val b = List(bars) { Bar(beats[it * beatsPerBar], beatsPerBar) }
        return MapEditor.rebuild(60.0, beats, b)
    }

    private fun near(a: Double, b: Double) = abs(a - b) < 0.2

    @Test fun halfTempoFixesDoubleTime() {
        val m = MapEditor.halfTempo(steady(240.0, 16), 0, 16)
        assertEquals(32, m.beats.size)
        assertEquals(8, m.bars.size)
        assert(near(m.mainBpm, 120.0)) { m.mainBpm }
        assertEquals(1.0, m.beats[0], 1e-9) // beats stay on the music
    }

    @Test fun doubleTempoOnlyInsideTheSelection() {
        val m = MapEditor.doubleTempo(steady(100.0, 12), 4, 8)
        assertEquals(12 * 4 + 16, m.beats.size)
        assertEquals(16, m.bars.size)
        val steadyParts = m.sections.filter { it.kind == Section.Kind.STEADY }
        assertEquals(3, steadyParts.size)
        assert(near(steadyParts[1].bpm, 200.0)) { steadyParts.map { it.bpm } }
        assert(m.beats.toList().zipWithNext().all { (x, y) -> y > x }) { "beats in order" }
    }

    @Test fun shiftDownbeatMakesAPickupAndKeepsMoving() {
        val base = steady(120.0, 8)
        val once = MapEditor.shiftDownbeat(base, 0, 8, +1)
        assertEquals(listOf(1, 4, 4), once.bars.take(3).map { it.beats })
        assertEquals(base.beats[1], once.bars[1].start, 1e-9)
        val twice = MapEditor.shiftDownbeat(once, 0, once.bars.size, +1)
        assertEquals(listOf(2, 4), twice.bars.take(2).map { it.beats })
        val back = MapEditor.shiftDownbeat(MapEditor.shiftDownbeat(twice, 0, twice.bars.size, -1), 0, twice.bars.size, -1)
        assertEquals(4, back.bars.first().beats)
        assertEquals(base.beats.size, back.beats.size)
    }

    @Test fun meterRegroupsWithoutMovingBeats() {
        val base = steady(120.0, 7) // 28 beats
        val m = MapEditor.setMeter(base, 0, 7, 7, 8)
        assertEquals(4, m.bars.size)
        assert(m.bars.all { it.meter == "7/8" })
        assert(base.beats.contentEquals(m.beats))
    }

    @Test fun partNamesSurviveAnEdit() {
        val base = steady(240.0, 8).let { it.copy(bars = it.bars.mapIndexed { i, b -> if (i == 4) b.copy(marker = "Chorus") else b }) }
        val m = MapEditor.halfTempo(base, 0, 8)
        assertEquals("Chorus", m.bars[2].marker)
    }
}
