package com.metronomekiller.app.links

import com.metronomekiller.app.analysis.Bar
import com.metronomekiller.app.analysis.Section
import com.metronomekiller.app.analysis.TempoMap
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.zip.GZIPInputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Songsterr tab lookup: a human-written tempo map for songs that have a tab.
 *
 * A Songsterr tab lists every bar's time signature and the tempo. Tabs with a
 * playback track are also synced to one or more YouTube videos: the time each
 * bar starts in that video ("video points"). That's our benchmark's answer key,
 * and far more reliable for bar lines in complex songs than the AI.
 *
 * This is a lookup from your phone for a song you're playing, like opening the
 * tab in a browser: only the song's title and artist are sent. No crawling,
 * nothing re-published. It uses Songsterr's unofficial web API, so it could
 * break if they change it; the AI tempo map is always kept as the fallback.
 */
object Songsterr {
    private const val UA = "Mozilla/5.0 (Android) MetronomeKiller"

    data class Tab(
        val songId: Long,
        val title: String,
        val artist: String,
        /** Bars in playback order (repeats unrolled): numerator, denominator, quarter-note tempo. */
        val bars: List<TabBar>,
        /** Synced YouTube videos: video id → start time of every bar (same order as [bars]). */
        val videos: Map<String, DoubleArray>,
    )

    /** One bar of the tab; [marker] is the part name written at this bar ("Chorus"), if any. */
    data class TabBar(val num: Int, val den: Int, val qpm: Double, val marker: String? = null) {
        val quarters get() = num * 4.0 / den
        val seconds get() = quarters * 60.0 / qpm
    }

    /**
     * Song id from a Songsterr link, e.g.
     * https://www.songsterr.com/a/wsa/nirvana-smells-like-teen-spirit-tab-s269 → 269
     * (a "t3" after the number picks an instrument track; we ignore it). Null if not Songsterr.
     */
    fun songIdFromLink(text: String): Long? {
        if (!text.contains("songsterr.com", ignoreCase = true)) return null
        return Regex("""-s(\d+)(?:t\d+)?(?:[/?#]|$)""").find(text.trim())?.groupValues?.get(1)?.toLongOrNull()
    }

    /** "[A] Intro I (Guitar Riff)" → "Intro I (Guitar Riff)", "Verse 1:" → "Verse 1". */
    fun cleanMarker(text: String): String? =
        text.replace(Regex("""^\s*\[[^\]]*]\s*"""), "").trim().trimEnd(':').trim().take(40).ifEmpty { null }

    /** Messy video titles like "Periphery - Racecar (HQ Audio) [Official]" → "Periphery Racecar". */
    fun cleanQuery(title: String, artist: String): String {
        val t = title.replace(Regex("""[(\[].*?[)\]]"""), " ")
            .replace(Regex("""(?i)\b(official|audio|video|lyrics?|hq|hd|4k|remaster(ed)?|music video|full album)\b"""), " ")
        val a = artist.replace(Regex("""(?i)\s*-\s*topic$"""), "").replace(Regex("""(?i)vevo$"""), "")
        val q = if (t.contains(" - ") || a.isBlank() || t.contains(a, true)) t else "$a $t"
        return q.replace(Regex("""[^\p{L}\p{N}' ]"""), " ").replace(Regex("""\s+"""), " ").trim()
    }

    /** Finds the best-matching tab with a player, or null. Blocking network call. */
    fun find(title: String, artist: String): Tab? {
        val q = cleanQuery(title, artist)
        if (q.length < 3) return null
        val res = JSONArray(getText("https://www.songsterr.com/api/songs?pattern=" + URLEncoder.encode(q, "UTF-8") + "&size=15"))
        val words = tokens(q)
        var best: JSONObject? = null
        var bestScore = 0.0
        for (i in 0 until res.length()) {
            val r = res.getJSONObject(i)
            if (!r.optBoolean("hasPlayer")) continue
            // Every word of the tab's title must appear in the query, and the more of
            // the query the tab explains (title + artist), the better.
            val tTitle = tokens(r.getString("title").replace(Regex("""(?i)\s-\s.*$"""), ""))
            val tAll = tTitle + tokens(r.getString("artist")) + tokens(r.getString("title"))
            if (tTitle.isEmpty() || !words.containsAll(tTitle)) continue
            val score = words.count { it in tAll }.toDouble() / words.size
            if (score > bestScore) { bestScore = score; best = r }
        }
        if (best == null || bestScore < 0.5) return null
        return fetch(best.getLong("songId"))
    }

    private fun tokens(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.length > 1 }.toSet()

    /** Downloads one tab by its Songsterr song id (from a search or a pasted link). */
    fun fetch(songId: Long): Tab? {
        val meta = JSONObject(getText("https://www.songsterr.com/api/meta/$songId"))
        val title = meta.optString("title")
        val artist = meta.opt("artist").let { if (it is JSONObject) it.optString("name") else it?.toString() ?: "" }
        val rev = meta.getLong("revisionId")
        val image = meta.getString("image")
        val part = meta.optInt("popularTrack", meta.optInt("defaultTrack", 0)).coerceAtLeast(0)
        fun track(n: Int) = JSONObject(getText("https://dqsljvtekg760.cloudfront.net/$songId/$rev/$image/$n.json"))
        val tab = track(part)
        val measures = tab.getJSONArray("measures")
        val order = unroll(measures)

        // Part names ("Intro", "Verse 1") are written on one track, usually the
        // popular one; if it has none, the default track or the first may.
        fun markersOf(ms: JSONArray) = (0 until ms.length()).associateWith { i ->
            ms.getJSONObject(i).optJSONObject("marker")?.optString("text")?.let(::cleanMarker)
        }.filterValues { it != null }
        var markers = markersOf(measures)
        if (markers.isEmpty()) for (n in listOf(meta.optInt("defaultTrack", 0), 0).distinct().filter { it != part && it >= 0 }) {
            val other = runCatching { track(n).getJSONArray("measures") }.getOrNull() ?: continue
            if (other.length() == measures.length()) markers = markersOf(other)
            if (markers.isNotEmpty()) break
        }

        val tempoList = tab.optJSONObject("automations")?.optJSONArray("tempo")
        val tempos = HashMap<Int, Double>()
        if (tempoList != null) for (i in 0 until tempoList.length()) {
            val t = tempoList.getJSONObject(i)
            tempos[t.getInt("measure")] = t.getDouble("bpm") * 4.0 / t.optInt("type", 4)
        }
        val sigs = ArrayList<Pair<Int, Int>>()
        var sig = 4 to 4
        for (i in 0 until measures.length()) {
            measures.getJSONObject(i).optJSONArray("signature")?.let { sig = it.getInt(0) to it.getInt(1) }
            sigs += sig
        }
        var qpm = 120.0
        val bars = order.map { mi -> qpm = tempos[mi] ?: qpm; TabBar(sigs[mi].first, sigs[mi].second, qpm, markers[mi]) }

        val videos = LinkedHashMap<String, DoubleArray>() // keeps Songsterr's order: its main video first
        runCatching {
            val arr = JSONArray(getText("https://www.songsterr.com/api/video-points/$songId/$rev/list"))
            for (i in 0 until arr.length()) {
                val v = arr.getJSONObject(i)
                val pts = v.optJSONArray("points") ?: continue
                if (v.optString("status") != "done" || pts.length() < bars.size - 5) continue
                videos[v.getString("videoId")] = DoubleArray(pts.length()) { pts.getDouble(it) }
            }
        }
        return Tab(songId, title, artist, bars, videos)
    }

    /** Expands repeat signs and voltas into playback order. */
    private fun unroll(measures: JSONArray): List<Int> {
        val order = ArrayList<Int>()
        var i = 0
        var start = 0
        val passes = HashMap<Int, Int>()
        var guard = 0
        while (i < measures.length() && guard++ < 20000) {
            val m = measures.getJSONObject(i)
            if (m.optBoolean("repeatStart")) start = i
            val p = passes[start] ?: 1
            val volta = m.optJSONArray("alternateEnding")
            if (volta != null && (0 until volta.length()).none { volta.getInt(it) == p }) { i++; continue }
            order += i
            val close = m.opt("repeatClose")
            if (close != null && close != false) {
                val times = (close as? Int) ?: 2
                if (p < times) { passes[start] = p + 1; i = start; continue }
                passes[start] = 1
            }
            i++
        }
        return order
    }

    // ── tab → tempo map ─────────────────────────────────────────────────────

    /**
     * Exact case: the song IS one of the tab's synced videos. Bar starts come
     * from the sync points, gently smoothed (they were tapped by a person):
     * within a stretch of one tab tempo, each bar start is moved onto a straight
     * line fitted through its ±4 neighbours.
     */
    fun mapFromSync(tab: Tab, points: DoubleArray, duration: Double): TempoMap {
        val n = minOf(tab.bars.size, points.size)
        val q = DoubleArray(n + 1)
        for (i in 0 until n) q[i + 1] = q[i] + tab.bars[i].quarters
        val t = DoubleArray(n + 1) { if (it < n) points[it] else points[n - 1] + tab.bars[n - 1].seconds }
        val smooth = t.copyOf()
        for (k in 0..n) {
            val idx = (maxOf(0, k - 4)..minOf(n, k + 4)).filter { tab.bars[minOf(it, n - 1)].qpm == tab.bars[minOf(k, n - 1)].qpm }
            if (idx.size >= 3) {
                val mx = idx.map { q[it] }.average(); val my = idx.map { t[it] }.average()
                var num = 0.0; var den = 0.0
                for (i in idx) { num += (q[i] - mx) * (t[i] - my); den += (q[i] - mx) * (q[i] - mx) }
                if (den > 0) smooth[k] = my + num / den * (q[k] - mx)
            }
        }
        return build(tab.bars.take(n), DoubleArray(n) { smooth[it] }, smooth[n], duration)
    }

    /**
     * Other audio (a file, another upload): slide the tab's bar grid along the
     * song until its bar lines land on the AI's beats, then nudge each bar line
     * onto the nearest AI beat (within 60 ms) to follow a live band's drift.
     * Returns null if the tab doesn't fit this recording (< 55 % of bar lines on beats).
     */
    fun mapAligned(tab: Tab, aiBeats: DoubleArray, duration: Double): TempoMap? {
        if (aiBeats.size < 16) return null
        val nominal = DoubleArray(tab.bars.size + 1)
        for (i in tab.bars.indices) nominal[i + 1] = nominal[i] + tab.bars[i].seconds
        val tabLen = nominal.last()
        if (tabLen > duration * 1.15 || tabLen < duration * 0.6) return null // clearly a different version
        fun nearest(x: Double): Double {
            var lo = 0; var hi = aiBeats.size - 1
            while (lo < hi) { val m = (lo + hi) / 2; if (aiBeats[m] < x) lo = m + 1 else hi = m }
            val a = aiBeats[lo]; val b = if (lo > 0) aiBeats[lo - 1] else a
            return if (abs(a - x) < abs(b - x)) a else b
        }
        var bestOff = 0.0; var bestHits = -1
        var off = -5.0
        while (off <= duration - tabLen * 0.6 && off <= 60.0) {
            var hits = 0
            for (i in tab.bars.indices) if (abs(nearest(nominal[i] + off) - (nominal[i] + off)) < 0.07) hits++
            if (hits > bestHits) { bestHits = hits; bestOff = off }
            off += 0.01
        }
        if (bestHits < tab.bars.size * 0.55) return null
        val starts = DoubleArray(tab.bars.size) { i ->
            val x = nominal[i] + bestOff
            val b = nearest(x)
            if (abs(b - x) < 0.06) b else x
        }
        return build(tab.bars, starts, nominal.last() + bestOff, duration)
    }

    /** Bars → beats (numerator clicks per bar), bar list and sections. */
    private fun build(bars: List<TabBar>, starts: DoubleArray, end: Double, duration: Double): TempoMap {
        val beats = ArrayList<Double>()
        val outBars = ArrayList<Bar>()
        for (i in bars.indices) {
            val s = starts[i]
            val e = if (i + 1 < starts.size) starts[i + 1] else end
            if (e <= s) continue
            outBars += Bar(s, bars[i].num, bars[i].den, bars[i].marker)
            for (k in 0 until bars[i].num) beats += s + (e - s) * k / bars[i].num
        }
        // Parts: about 16 bars each, breaking where the tab's tempo changes, so a
        // song that changes meter every bar still has a usable parts strip. A part
        // whose bars differ is labelled "mixed"; the bar grid shows each bar's meter.
        val sections = ArrayList<Section>()
        var a = 0
        while (a < bars.size) {
            var b = a
            while (b + 1 < bars.size && bars[b + 1].qpm == bars[a].qpm && b + 1 - a < 16) b++
            // don't leave a tiny tail: absorb it if fewer than 8 bars of this tempo remain
            var tail = b + 1
            while (tail < bars.size && bars[tail].qpm == bars[a].qpm) tail++
            if (tail - (b + 1) in 1..7) b = tail - 1
            val group = bars.subList(a, b + 1)
            val uniform = group.all { it.num == group[0].num && it.den == group[0].den }
            val s = starts[a]
            val e = if (b + 1 < starts.size) starts[b + 1] else end
            val noteValue = if (uniform) group[0].den else 4
            val units = group.sumOf { if (uniform) it.num.toDouble() else it.quarters }
            val bpm = ((units * 60.0 / (e - s)) * 10).roundToInt() / 10.0 // in the bottom note
            sections += Section(s, e, Section.Kind.STEADY, bpm, bpm, if (uniform) group[0].num else 0,
                group.sumOf { it.num }, s, false, noteValue)
            a = b + 1
        }
        if (sections.isNotEmpty() && sections.first().start > 1.0) sections.add(0, Section(0.0, sections.first().start, Section.Kind.FREE, 0.0, 0.0, 0, 0, 0.0, false))
        if (sections.isNotEmpty() && duration - sections.last().end > 1.0) sections += Section(sections.last().end, duration, Section.Kind.FREE, 0.0, 0.0, 0, 0, sections.last().end, false)
        val main = sections.filter { it.kind == Section.Kind.STEADY }.maxByOrNull { it.end - it.start }?.bpm ?: 0.0
        return TempoMap(duration, main, beats.toDoubleArray(), outBars.map { it.start }.toDoubleArray(), outBars, sections)
    }

    private fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            setRequestProperty("Accept-Encoding", "gzip")
            connectTimeout = 15_000; readTimeout = 20_000
        }
        try {
            if (conn.responseCode != 200) throw IOException("Songsterr HTTP ${conn.responseCode}")
            val raw = conn.inputStream.use { it.readBytes() }
            // The tab files come gzip-compressed even when we don't ask; check the magic bytes.
            val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte())
                GZIPInputStream(raw.inputStream()).readBytes() else raw
            return String(bytes, Charsets.UTF_8)
        } finally {
            conn.disconnect()
        }
    }
}
