package com.metronomekiller.app.links

import org.json.JSONObject
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.search.SearchInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeSearchQueryHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Spotify links: tracks, albums and playlists.
 *
 * LEARN — why we can't just download from Spotify: Spotify's audio is encrypted
 * (DRM) and only its own app can play it. So we only read the song's NAME,
 * ARTIST and LENGTH from Spotify, then find the same recording on YouTube and
 * download that, exactly like a pasted YouTube link.
 *
 * Where the names come from: the small "embed" player Spotify gives websites
 * (open.spotify.com/embed/...) is a public page with the details built in as
 * JSON, inside a <script id="__NEXT_DATA__"> tag. No account or API key needed.
 * The embed shows at most about 50 songs of a playlist, so that's our limit too.
 */
object Spotify {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"

    data class Ref(val type: String, val id: String)

    /** "open.spotify.com/(intl-xx/)track/ID", or a "spotify:track:ID" link. Null if it isn't Spotify. */
    fun parse(url: String): Ref? {
        Regex("""open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]+)""").find(url)
            ?.let { return Ref(it.groupValues[1], it.groupValues[2]) }
        Regex("""spotify:(track|album|playlist):([A-Za-z0-9]+)""").find(url)
            ?.let { return Ref(it.groupValues[1], it.groupValues[2]) }
        return null
    }

    fun isSpotify(url: String) = parse(url) != null
    fun trackUrl(id: String) = "https://open.spotify.com/track/$id"

    /** Reads a Spotify link into songs (with Spotify track URLs; matched to YouTube later). Blocking. */
    fun resolve(url: String): Links.Resolved {
        val ref = parse(url) ?: throw IOException("That isn't a Spotify link.")
        val html = getText("https://open.spotify.com/embed/${ref.type}/${ref.id}")
        val json = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("Spotify didn't send the song details.")
        return fromEntity(JSONObject(json).getJSONObject("props").getJSONObject("pageProps")
            .getJSONObject("state").getJSONObject("data").getJSONObject("entity"))
    }

    /** The embed page's "entity" JSON → songs. Split out so it can be unit-tested. */
    fun fromEntity(e: JSONObject): Links.Resolved {
        if (e.optString("type") == "track") {
            val artists = e.optJSONArray("artists")
            val artist = if (artists == null) "" else (0 until artists.length()).joinToString(", ") { artists.getJSONObject(it).optString("name") }
            return Links.Resolved.Single(Links.Item(trackUrl(e.getString("id")), e.optString("name", e.optString("title")), artist, e.optLong("duration") / 1000))
        }
        val list = e.optJSONArray("trackList") ?: throw IOException("That Spotify link has no songs.")
        val items = (0 until list.length()).map { list.getJSONObject(it) }
            .filter { it.optString("uri").startsWith("spotify:track:") }
            .map {
                Links.Item(
                    trackUrl(it.getString("uri").removePrefix("spotify:track:")),
                    it.optString("title"),
                    // Artists come joined by "," plus a no-break space.
                    it.optString("subtitle").replace(' ', ' ').replace(Regex(""",\s*"""), ", "),
                    it.optLong("duration") / 1000,
                )
            }
        if (items.isEmpty()) throw IOException("That Spotify link has no songs.")
        return Links.Resolved.Playlist(e.optString("name", e.optString("title", "Spotify")), items)
    }

    // ── Matching on YouTube ──

    /** One YouTube search result, reduced to what the picker needs. */
    data class Candidate(val url: String, val title: String, val channel: String, val durationSec: Long)

    private val VERSION_WORDS = listOf("live", "cover", "remix", "karaoke", "instrumental", "acoustic", "sped up", "slowed", "nightcore", "8d", "reaction", "lesson", "tutorial")

    private fun words(s: String) = s.lowercase().split(Regex("""[^\p{L}\p{N}]+""")).filter { it.isNotEmpty() }.toSet()

    /**
     * Picks the YouTube result that is most likely the same recording, or null.
     *
     * LEARN — how: the LENGTH is the strongest clue. The same recording is the
     * same length to within a few seconds; a live version, music video with a
     * skit, or a different song almost never is. So anything more than 8 s (or
     * 5% for long songs) off is thrown out. Among what's left we want the title
     * words to match, prefer "Artist - Topic" channels (YouTube's official audio
     * uploads, no video intros), and avoid other versions (live, cover, remix…)
     * unless the Spotify title itself says so.
     */
    fun pick(candidates: List<Candidate>, title: String, artist: String, durationSec: Long): Candidate? {
        val want = words(title)
        val artistWords = words(artist)
        val tolerance = maxOf(8L, durationSec / 20)
        return candidates
            .filter { durationSec <= 0 || (it.durationSec > 0 && kotlin.math.abs(it.durationSec - durationSec) <= tolerance) }
            .map { c ->
                val have = words(c.title)
                val channel = c.channel.lowercase()
                var score = 0.0
                score += if (want.isEmpty()) 0.0 else 4.0 * want.count { it in have } / want.size
                if (artistWords.any { it in have || it in words(channel) }) score += 1.5
                if (channel.endsWith(" - topic")) score += 1.5
                VERSION_WORDS.forEach { w -> if (w in c.title.lowercase() && w !in title.lowercase()) score -= 3.0 }
                if (durationSec > 0) score -= kotlin.math.abs(c.durationSec - durationSec) / tolerance.toDouble()
                c to score
            }
            .filter { it.second > 1.0 }  // at least some of the title has to match
            .maxByOrNull { it.second }?.first
    }

    /**
     * Finds the song on YouTube: first among YouTube Music "songs" (official
     * audio), then among normal videos. Returns the video URL. Blocking.
     */
    fun matchOnYouTube(title: String, artist: String, durationSec: Long): String {
        Links.ensureInit()
        val yt = ServiceList.YouTube
        val query = "$artist $title".trim()
        for (filter in listOf(YoutubeSearchQueryHandlerFactory.MUSIC_SONGS, YoutubeSearchQueryHandlerFactory.VIDEOS)) {
            val results = runCatching {
                SearchInfo.getInfo(yt, yt.searchQHFactory.fromQuery(query, listOf(filter), ""))
                    .relatedItems.filterIsInstance<StreamInfoItem>()
                    .map { Candidate(it.url, it.name, it.uploaderName ?: "", it.duration) }
            }.getOrDefault(emptyList())
            // Song results are music.youtube.com links; the normal form is what the
            // rest of the app (SponsorBlock, Songsterr video sync) expects.
            pick(results, title, artist, durationSec)?.let { return it.url.replace("://music.youtube.com/", "://www.youtube.com/") }
        }
        throw IOException("Couldn't find \"$title\" on YouTube.")
    }

    private fun getText(url: String): String {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            setRequestProperty("User-Agent", UA)
            connectTimeout = 15_000; readTimeout = 20_000
        }
        try {
            if (conn.responseCode != 200) throw IOException("Spotify HTTP ${conn.responseCode}")
            return conn.inputStream.bufferedReader(Charsets.UTF_8).readText()
        } finally {
            conn.disconnect()
        }
    }
}
