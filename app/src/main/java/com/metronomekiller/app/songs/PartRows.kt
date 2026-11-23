package com.metronomekiller.app.songs

import com.metronomekiller.app.analysis.TempoMap

/** Where a row's name came from: the Songsterr tab, or you (renamed it). */
enum class NameSource { TAB, YOU }

/**
 * One row of the "part rows" view: bars [first] until [end] (exclusive), 0-based.
 * [continued] = this row carries on the part named in the row above (shown dimmer).
 */
data class PartRow(val first: Int, val end: Int, val name: String?, val source: NameSource?, val continued: Boolean = false) {
    val size get() = end - first
    /** "Bars 17–24" (1-based, like the bar numbers on screen). */
    val range get() = if (size == 1) "Bar ${first + 1}" else "Bars ${first + 1}–$end"
}

/**
 * Cuts a song into rows, like a chord chart.
 *
 * Where rows break:
 *  - a tab with part names: at every part name ("Intro", "Verse 1", "Chorus"...)
 *  - otherwise: where the AI found a tempo change (its sections)
 *  - and inside those, every 8 bars, so a row always fits one line of the phone.
 *
 * We do NOT guess names like "verse" from the audio: tested on the benchmark
 * (tools/beat-lab/sections_lab.py) only ~40 % of guesses were right, which
 * would mislead more than help. Unnamed rows just show their bar range, and
 * you can name any row yourself (saved per song, keyed by the row's first bar).
 */
object PartRows {
    const val MAX_BARS = 8

    fun build(map: TempoMap, yourNames: Map<Int, String> = emptyMap()): List<PartRow> {
        val bars = map.bars
        if (bars.isEmpty()) return emptyList()
        val hasNames = bars.any { it.marker != null }
        val breaks = sortedSetOf(0, bars.size)
        if (hasNames) {
            bars.forEachIndexed { i, b -> if (b.marker != null) breaks += i }
        } else {
            // first bar of each AI section (sections are stretches of one tempo)
            for (s in map.sections.drop(1)) {
                val i = bars.indexOfFirst { it.start >= s.start - 0.05 }
                if (i > 0) breaks += i
            }
        }
        val rows = ArrayList<PartRow>()
        val cuts = breaks.toList()
        for (k in 0 until cuts.size - 1) {
            val a = cuts[k]; val b = cuts[k + 1]
            val name = bars[a].marker
            var i = a
            while (i < b) {
                val e = minOf(b, i + MAX_BARS)
                val mine = yourNames[i]
                rows += when {
                    mine != null -> PartRow(i, e, mine, NameSource.YOU)
                    name != null -> PartRow(i, e, name, NameSource.TAB, continued = i != a)
                    else -> PartRow(i, e, null, null)
                }
                i = e
            }
        }
        return rows
    }

    /** The meter most bars use; bars in any other meter get marked on screen. */
    fun usualMeter(map: TempoMap): String? = map.bars.groupingBy { it.meter }.eachCount().maxByOrNull { it.value }?.key
}
