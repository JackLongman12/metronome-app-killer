package com.metronomekiller.app.songs

import com.metronomekiller.app.analysis.Bar
import com.metronomekiller.app.analysis.Section
import com.metronomekiller.app.analysis.TempoMap
import org.junit.Assert.assertEquals
import org.junit.Test

class PartRowsTest {
    private fun map(n: Int, markers: Map<Int, String> = emptyMap(), sectionStarts: List<Int> = listOf(0)): TempoMap {
        val bars = List(n) { Bar(it * 2.0, 4, 4, markers[it]) }
        val secs = sectionStarts.mapIndexed { k, s ->
            val e = sectionStarts.getOrNull(k + 1) ?: n
            Section(s * 2.0, e * 2.0, Section.Kind.STEADY, 120.0, 120.0, 4, (e - s) * 4, s * 2.0, false)
        }
        return TempoMap(n * 2.0, 120.0, DoubleArray(0), DoubleArray(0), bars, secs)
    }

    @Test fun tabNamesBreakRowsAndLongPartsWrapEvery8() {
        val rows = PartRows.build(map(30, mapOf(0 to "Intro", 4 to "Verse 1", 24 to "Chorus")))
        assertEquals(listOf(0 to 4, 4 to 12, 12 to 20, 20 to 24, 24 to 30), rows.map { it.first to it.end })
        assertEquals(listOf("Intro", "Verse 1", "Verse 1", "Verse 1", "Chorus"), rows.map { it.name })
        assertEquals(listOf(false, false, true, true, false), rows.map { it.continued })
        assert(rows.all { it.source == NameSource.TAB })
    }

    @Test fun noNamesUsesTempoSectionsAndBarRanges() {
        val rows = PartRows.build(map(20, sectionStarts = listOf(0, 6)))
        assertEquals(listOf(0 to 6, 6 to 14, 14 to 20), rows.map { it.first to it.end })
        assert(rows.all { it.name == null })
        assertEquals("Bars 7–14", rows[1].range)
    }

    @Test fun yourNamesWin() {
        val rows = PartRows.build(map(16, mapOf(0 to "Intro")), mapOf(8 to "Solo"))
        assertEquals(listOf("Intro" to NameSource.TAB, "Solo" to NameSource.YOU), rows.map { it.name to it.source })
    }
}
