package com.metronomekiller.app.practice

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * Subdivision ladder: climb through [rungs] (clicks per beat: 1 = quarters,
 * 2 = eighths, 3 = triplets, 4 = sixteenths), [every] bars on each, at one tempo.
 * With [backDown] it climbs back down before starting over.
 */
data class LadderPlan(val rungs: List<Int> = listOf(1, 2, 3, 4), val every: Int = 2, val backDown: Boolean = true) {
    /** The climb as one repeating cycle, e.g. 1 2 3 4 3 2 (then 1 again). */
    val cycle: List<Int> get() {
        val r = rungs.ifEmpty { listOf(1) }
        return if (backDown && r.size > 2) r + r.subList(1, r.size - 1).reversed() else r
    }

    /** Clicks per beat for bar [bar] (0 = first bar). */
    fun subFor(bar: Int): Int = cycle[(bar.coerceAtLeast(0) / every.coerceAtLeast(1)) % cycle.size]

    companion object {
        fun name(perBeat: Int) = when (perBeat) { 1 -> "quarters"; 2 -> "eighths"; 3 -> "triplets"; else -> "sixteenths" }
    }
}

/**
 * Polyrhythm: [count] evenly spaced hits per bar against the metronome's beats
 * (3 against 4, 5 against 4...). [mainOnlyOne] keeps only beat 1 of the normal
 * click so the cross-rhythm is easier to hear.
 */
data class PolyPlan(val count: Int = 3, val mainOnlyOne: Boolean = false)

/** One finished practice session, for the history list. */
data class LogEntry(val startedAt: Long, val seconds: Int, val tool: String, val detail: String) {
    fun toJson(): JSONObject = JSONObject().put("at", startedAt).put("s", seconds).put("tool", tool).put("detail", detail)

    companion object {
        fun fromJson(o: JSONObject) = LogEntry(o.getLong("at"), o.getInt("s"), o.getString("tool"), o.optString("detail"))
    }
}

/**
 * Practice history, kept in a small JSON file in the app's private storage.
 * Sessions shorter than 20 seconds aren't worth a line, so they're skipped.
 */
class PracticeLog(private val file: File) {
    fun load(): List<LogEntry> = runCatching {
        val a = JSONArray(file.readText())
        List(a.length()) { LogEntry.fromJson(a.getJSONObject(it)) }
    }.getOrDefault(emptyList())

    fun add(e: LogEntry): List<LogEntry> {
        val list = load()
        if (e.seconds < MIN_SECONDS) return list
        val next = (list + e).takeLast(MAX_ENTRIES)
        file.writeText(JSONArray(next.map { it.toJson() }).toString())
        return next
    }

    fun clear() { file.delete() }

    companion object {
        const val MIN_SECONDS = 20
        const val MAX_ENTRIES = 1000

        /** Total seconds practised since local midnight [dayStartMs] (inclusive). */
        fun secondsSince(entries: List<LogEntry>, dayStartMs: Long) = entries.filter { it.startedAt >= dayStartMs }.sumOf { it.seconds }
    }
}
