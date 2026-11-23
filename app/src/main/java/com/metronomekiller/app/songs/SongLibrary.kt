package com.metronomekiller.app.songs

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.metronomekiller.app.analysis.AudioDecoder
import com.metronomekiller.app.analysis.BeatModel
import com.metronomekiller.app.analysis.BeatPipeline
import com.metronomekiller.app.analysis.MdBeatModel
import com.metronomekiller.app.analysis.ModelFiles
import com.metronomekiller.app.analysis.TempoMap
import com.metronomekiller.app.analysis.TempoMapBuilder
import com.metronomekiller.app.App
import com.metronomekiller.app.links.Links
import com.metronomekiller.app.links.Spotify
import com.metronomekiller.app.links.Songsterr
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.UUID

/** Where a song is in the analysis process. */
sealed interface SongStatus {
    data object Ready : SongStatus
    data object Queued : SongStatus
    data class Working(val step: String, val progress: Float) : SongStatus
    data class Failed(val message: String) : SongStatus
}

data class Song(
    val id: String,
    val title: String,
    val artist: String,
    val uri: String,
    val addedAt: Long,
    val map: TempoMap? = null,
    val status: SongStatus = SongStatus.Queued,
    /** YouTube/SoundCloud page this came from (null for files on the phone). */
    val link: String? = null,
    /** Playlist it was imported with, if any. */
    val playlist: String? = null,
    /** Non-music stretches to skip during playback (SponsorBlock), in seconds. */
    val skips: List<Pair<Double, Double>> = emptyList(),
    /** Timing nudge for this song's clicks, in ms (+ later, - earlier). */
    val offsetMs: Int = 0,
    /** Set when a Songsterr tab was found, e.g. "Periphery – Racecar · synced". */
    val tabInfo: String? = null,
    /** Whether the tab's tempo map is in use (else the AI's). */
    val usingTab: Boolean = false,
    /** You've hand-fixed this song's map (Edit mode); [usingEdit] = your version is playing. */
    val hasEdit: Boolean = false,
    val usingEdit: Boolean = false,
    /** Songsterr song id you picked yourself (pasted link): used instead of searching. */
    val tabId: Long? = null,
    /** Your own names for rows in the part-rows view, keyed by the row's first bar (0-based). */
    val rowNames: Map<Int, String> = emptyMap(),
    /** Length the link reported (Spotify), in seconds: helps find the same recording on YouTube. */
    val spotifySeconds: Long = 0,
)

/**
 * Your songs and their tempo maps.
 *
 * - Nothing is copied: we keep a permanent permission to read the file where it
 *   already lives (a "persistable URI permission"), plus the analysis result.
 * - Analysis runs one song at a time in the background, on an app-wide scope,
 *   so leaving the screen doesn't cancel it.
 * - Storage: files/songs/index.json (the list) and files/songs/<id>.json (tempo maps).
 *
 * LEARN — the analysis steps:
 *   download model (first time only) → decode + resample → neural network →
 *   tempo map → save.
 */
class SongLibrary(private val context: Context) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val dir = File(context.filesDir, "songs").apply { mkdirs() }
    private val index = File(dir, "index.json")
    private val queue = Mutex()
    private val audioDir = File(dir, "audio")

    private val _songs = MutableStateFlow(load())
    val songs: StateFlow<List<Song>> = _songs

    /** Message while a pasted link is being looked up (null when idle). */
    private val _linkStatus = MutableStateFlow<String?>(null)
    val linkStatus: StateFlow<String?> = _linkStatus

    init {
        // Anything that was mid-analysis when the app closed gets another go.
        _songs.value.filter { it.map == null }.forEach { analyse(it.id) }
    }

    fun song(id: String): Song? = _songs.value.firstOrNull { it.id == id }

    /** Add a song picked from the phone and start analysing it. */
    fun add(uri: Uri) {
        runCatching {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        val (title, artist) = describe(uri)
        val song = Song(UUID.randomUUID().toString(), title, artist, uri.toString(), System.currentTimeMillis())
        _songs.update { listOf(song) + it }
        save()
        analyse(song.id)
    }

    /**
     * Add a YouTube/SoundCloud video or playlist link, or a Spotify track,
     * album or playlist. A playlist adds every song in playlist order, each
     * analysed in turn. Spotify songs are matched to YouTube when they're
     * downloaded (see analyse), one at a time, so a 50-song playlist adds instantly.
     */
    fun addLink(text: String) {
        val url = Links.findUrl(text) ?: text.trim()
        Songsterr.songIdFromLink(url)?.let { addSongsterr(it); return }
        _linkStatus.value = "Looking up the link…"
        scope.launch {
            try {
                val resolved = if (Spotify.isSpotify(url)) Spotify.resolve(url) else Links.resolve(url)
                when (val r = resolved) {
                    is Links.Resolved.Single -> {
                        val s = Song(UUID.randomUUID().toString(), r.item.title, r.item.artist, "", System.currentTimeMillis(), link = r.item.url, spotifySeconds = r.item.durationSec)
                        _songs.update { listOf(s) + it }
                        save(); analyse(s.id)
                    }
                    is Links.Resolved.Playlist -> {
                        val now = System.currentTimeMillis()
                        val added = r.items.mapIndexed { i, it ->
                            Song(UUID.randomUUID().toString(), it.title, it.artist, "", now - i, link = it.url, playlist = r.name, spotifySeconds = it.durationSec)
                        }
                        _songs.update { added + it }
                        save(); added.forEach { analyse(it.id) }
                    }
                }
                _linkStatus.value = null
            } catch (e: Throwable) {
                _linkStatus.value = "Couldn't open that link: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * A Songsterr link on its own: tabs with a player are synced to a YouTube
     * video, so we add that video as the song, and its tab lines up exactly.
     */
    private fun addSongsterr(songId: Long) {
        _linkStatus.value = "Looking up the Songsterr tab…"
        scope.launch {
            try {
                val tab = Songsterr.fetch(songId) ?: throw IllegalStateException("tab not found")
                val video = tab.videos.keys.firstOrNull()
                if (video == null) {
                    _linkStatus.value = "Couldn't use that tab on its own: it isn't synced to a video. " +
                        "Add the song from your phone or a YouTube link, then use ••• → Use a Songsterr tab."
                    return@launch
                }
                val s = Song(UUID.randomUUID().toString(), tab.title, tab.artist, "", System.currentTimeMillis(),
                    link = "https://www.youtube.com/watch?v=$video", tabId = songId)
                _songs.update { listOf(s) + it }
                save(); analyse(s.id)
                _linkStatus.value = null
            } catch (e: Throwable) {
                _linkStatus.value = "Couldn't open that link: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    /**
     * Use a specific Songsterr tab for a song you already have (your pick beats
     * the automatic search, e.g. when a better tab exists). The tab still has to
     * line up with the recording, or we keep what you had.
     */
    fun attachTab(id: String, link: String) {
        val songId = Songsterr.songIdFromLink(link)
        if (songId == null) { _linkStatus.value = "Couldn't read that link. Copy it from the tab's page on songsterr.com."; return }
        _linkStatus.value = "Fetching the tab…"
        scope.launch {
            queue.withLock {
                try {
                    val song = song(id) ?: return@withLock
                    val ai = TempoMap.fromJson(JSONObject(File(dir, "$id.ai.json").readText()))
                    val tab = Songsterr.fetch(songId) ?: throw IllegalStateException("tab not found")
                    val points = song.link?.let { Links.youtubeId(it) }?.let { tab.videos[it] }
                    val tabMap = if (points != null) Songsterr.mapFromSync(tab, points, ai.duration)
                    else Songsterr.mapAligned(tab, ai.beats, ai.duration)
                    if (tabMap == null) {
                        _linkStatus.value = "That tab doesn't line up with this recording (maybe a different version), so nothing changed."
                        return@withLock
                    }
                    File(dir, "$id.tab.json").writeText(tabMap.toJson().toString())
                    File(dir, "$id.json").writeText(tabMap.toJson().toString())
                    val info = "${tab.artist} – ${tab.title} · ${if (points != null) "synced to this video" else "lined up with the audio"}"
                    _songs.update { list -> list.map { if (it.id == id) it.copy(map = tabMap, tabInfo = info, usingTab = true, usingEdit = false, tabId = songId, rowNames = emptyMap()) else it } }
                    save()
                    _linkStatus.value = "Using the tab: $info"
                } catch (e: Throwable) {
                    _linkStatus.value = "Couldn't use that tab: ${e.message ?: e.javaClass.simpleName}"
                }
            }
        }
    }

    /** Name a row of the part-rows view (blank = back to the default). */
    fun renameRow(id: String, firstBar: Int, name: String) {
        _songs.update { list -> list.map {
            if (it.id != id) it else it.copy(rowNames = if (name.isBlank()) it.rowNames - firstBar else it.rowNames + (firstBar to name.trim().take(30)))
        } }
        save()
    }

    fun setOffset(id: String, ms: Int) {
        _songs.update { list -> list.map { if (it.id == id) it.copy(offsetMs = ms) else it } }
        save()
    }

    fun clearLinkStatus() { _linkStatus.value = null }

    fun remove(id: String) {
        val song = song(id)
        _songs.update { list -> list.filterNot { it.id == id } }
        listOf("$id.json", "$id.ai.json", "$id.tab.json", "$id.edit.json").forEach { File(dir, it).delete() }
        // Downloaded audio belongs to us; files from the phone are left alone.
        if (song?.link != null) audioDir.listFiles { f -> f.name.startsWith(id) }?.forEach { it.delete() }
        save()
    }

    fun retry(id: String) = analyse(id)

    /** Which of a song's tempo maps to play: the AI's, the Songsterr tab's, or your edited one. */
    enum class Source { AI, TAB, EDIT }

    /** Switch a song between its tempo maps (each is kept in its own file). */
    fun useMap(id: String, source: Source) {
        val file = File(dir, when (source) { Source.AI -> "$id.ai.json"; Source.TAB -> "$id.tab.json"; Source.EDIT -> "$id.edit.json" })
        if (!file.exists()) return
        val map = runCatching { TempoMap.fromJson(JSONObject(file.readText())) }.getOrNull() ?: return
        File(dir, "$id.json").writeText(file.readText())
        _songs.update { list -> list.map { if (it.id == id) it.copy(map = map, usingTab = source == Source.TAB, usingEdit = source == Source.EDIT) else it } }
        save()
    }

    /**
     * Save a hand-fixed map as this song's "yours" map and play it. The AI and
     * tab maps are untouched, so you can always switch back.
     */
    fun saveEdit(id: String, map: TempoMap) {
        val json = map.toJson().toString()
        File(dir, "$id.edit.json").writeText(json)
        File(dir, "$id.json").writeText(json)
        _songs.update { list -> list.map {
            if (it.id != id) it
            // row names are keyed by bar number; if the bars were renumbered they'd land on the wrong rows
            else it.copy(map = map, hasEdit = true, usingEdit = true, usingTab = false,
                rowNames = if (it.map?.bars?.size == map.bars.size) it.rowNames else emptyMap())
        } }
        save()
    }

    /** Throw away your edits and go back to the tab map (if there is one) or the AI's. */
    fun discardEdit(id: String) {
        File(dir, "$id.edit.json").delete()
        _songs.update { list -> list.map { if (it.id == id) it.copy(hasEdit = false, usingEdit = false) else it } }
        useMap(id, if (song(id)?.tabInfo != null) Source.TAB else Source.AI)
    }

    private fun setStatus(id: String, status: SongStatus, map: TempoMap? = null) {
        _songs.update { list -> list.map { if (it.id == id) it.copy(status = status, map = map ?: it.map) else it } }
    }

    private fun analyse(id: String) {
        setStatus(id, SongStatus.Queued)
        scope.launch {
            queue.withLock {
                var song = song(id) ?: return@withLock
                try {
                    // Spotify songs: find the same recording on YouTube, then carry on as a YouTube link.
                    if (song.link != null && Spotify.isSpotify(song.link!!)) {
                        setStatus(id, SongStatus.Working("Finding it on YouTube", 0f))
                        val yt = Spotify.matchOnYouTube(song.title, song.artist, song.spotifySeconds)
                        _songs.update { list -> list.map { if (it.id == id) it.copy(link = yt) else it } }
                        save()
                        song = song(id) ?: return@withLock
                    }
                    // Links: download the audio first (once), and look up parts to skip.
                    if (song.link != null && (song.uri.isEmpty() || !File(Uri.parse(song.uri).path ?: "").exists())) {
                        setStatus(id, SongStatus.Working("Downloading audio", 0f))
                        val file = Links.downloadAudio(song.link!!, audioDir, id) { p ->
                            setStatus(id, SongStatus.Working("Downloading audio", p))
                        }
                        val skips = Links.youtubeId(song.link!!)?.let { Links.sponsorSegments(it) } ?: emptyList()
                        _songs.update { list -> list.map { if (it.id == id) it.copy(uri = Uri.fromFile(file).toString(), skips = skips) else it } }
                        save()
                        song = song(id) ?: return@withLock
                    }
                    val accurate = App.from(context).prefs.accurateModel.value
                    if (accurate && !ModelFiles.isDownloaded(context, MdBeatModel.FILES)) {
                        ModelFiles.ensure(context, MdBeatModel.FILES) { p ->
                            setStatus(id, SongStatus.Working("Downloading the accurate beat model (${MdBeatModel.SIZE_MB} MB)", p))
                        }
                    } else if (!accurate && !BeatModel.isDownloaded(context)) {
                        BeatModel.ensureDownloaded(context) { p ->
                            setStatus(id, SongStatus.Working("Downloading the beat model (${BeatModel.SIZE_MB} MB)", p))
                        }
                    }
                    setStatus(id, SongStatus.Working("Reading the song", 0f))
                    val audio = AudioDecoder.decodeToMono22k(context, Uri.parse(song.uri)) { p ->
                        setStatus(id, SongStatus.Working("Reading the song", p))
                    }
                    setStatus(id, SongStatus.Working("Finding the beats", 0f))
                    val aiMap = if (accurate) {
                        val r = MdBeatModel.load(context).use { model ->
                            // 8 refinement passes (as in the paper) up to 6 min; 4 for longer songs so a phone finishes in reasonable time
                            model.analyse(audio.samples, if (audio.durationSec <= 360) 8 else 4) { p -> setStatus(id, SongStatus.Working("Finding the beats", p)) }
                        }
                        TempoMapBuilder.build(r.beats, r.downbeats, audio.durationSec,
                            MdBeatModel.pseudoDownbeatScores(r.downbeats, audio.durationSec))
                    } else {
                        val r = BeatModel.load(context).use { model ->
                            BeatPipeline.analyse(model, audio.samples) { p -> setStatus(id, SongStatus.Working("Finding the beats", p)) }
                        }
                        TempoMapBuilder.build(r.beats, r.downbeats, audio.durationSec, r.downbeatScores)
                    }
                    File(dir, "$id.ai.json").writeText(aiMap.toJson().toString())
                    File(dir, "$id.edit.json").delete() // a fresh analysis replaces old hand fixes

                    // Songsterr: a human-written tempo map, if this song has a synced tab.
                    var map = aiMap
                    var tabInfo: String? = null
                    if (song.tabId != null || App.from(context).prefs.songsterr.value) {
                        setStatus(id, SongStatus.Working("Looking for a Songsterr tab", 0.5f))
                        runCatching { song.tabId?.let { Songsterr.fetch(it) } ?: Songsterr.find(song.title, song.artist) }.getOrNull()?.let { tab ->
                            val video = song.link?.let { Links.youtubeId(it) }
                            val points = video?.let { tab.videos[it] }
                            val tabMap = if (points != null) Songsterr.mapFromSync(tab, points, audio.durationSec)
                            else Songsterr.mapAligned(tab, aiMap.beats, audio.durationSec)
                            if (tabMap != null) {
                                File(dir, "$id.tab.json").writeText(tabMap.toJson().toString())
                                map = tabMap
                                tabInfo = "${tab.artist} – ${tab.title} · ${if (points != null) "synced to this video" else "lined up with the audio"}"
                            }
                        }
                    }
                    File(dir, "$id.json").writeText(map.toJson().toString())
                    _songs.update { list -> list.map { if (it.id == id) it.copy(tabInfo = tabInfo, usingTab = tabInfo != null, rowNames = emptyMap(), hasEdit = false, usingEdit = false) else it } }
                    setStatus(id, SongStatus.Ready, map)
                    save()
                } catch (e: Throwable) {
                    setStatus(id, SongStatus.Failed(e.message ?: "Analysis failed"))
                }
            }
        }
    }

    private fun describe(uri: Uri): Pair<String, String> {
        var title: String? = null
        var artist: String? = null
        runCatching {
            MediaMetadataRetriever().use { r ->
                r.setDataSource(context, uri)
                title = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_TITLE)
                artist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
            }
        }
        if (title.isNullOrBlank()) {
            context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) title = c.getString(0)?.substringBeforeLast('.')
            }
        }
        return (title ?: "Untitled") to (artist ?: "")
    }

    private fun load(): List<Song> = runCatching {
        val arr = JSONArray(index.readText())
        List(arr.length()) { i ->
            val o = arr.getJSONObject(i)
            val id = o.getString("id")
            val mapFile = File(dir, "$id.json")
            val map = if (mapFile.exists()) runCatching { TempoMap.fromJson(JSONObject(mapFile.readText())) }.getOrNull() else null
            val skips = o.optJSONArray("skips")?.let { a -> List(a.length()) { a.getJSONArray(it).let { p -> p.getDouble(0) to p.getDouble(1) } } }
            Song(id, o.getString("title"), o.optString("artist"), o.getString("uri"), o.getLong("addedAt"),
                map, if (map != null) SongStatus.Ready else SongStatus.Queued,
                link = o.optString("link").ifEmpty { null }, playlist = o.optString("playlist").ifEmpty { null },
                skips = skips ?: emptyList(), offsetMs = o.optInt("offsetMs", 0),
                tabInfo = o.optString("tabInfo").ifEmpty { null }, usingTab = o.optBoolean("usingTab"),
                tabId = o.optLong("tabId", 0L).takeIf { it > 0 },
                spotifySeconds = o.optLong("spotifySeconds", 0L),
                hasEdit = File(dir, "$id.edit.json").exists(), usingEdit = o.optBoolean("usingEdit"),
                rowNames = o.optJSONObject("rowNames")?.let { r -> r.keys().asSequence().associate { k -> k.toInt() to r.getString(k) } } ?: emptyMap())
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray(_songs.value.map {
            JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("uri", it.uri).put("addedAt", it.addedAt)
                .put("offsetMs", it.offsetMs)
                .put("tabInfo", it.tabInfo ?: "").put("usingTab", it.usingTab)
                .put("tabId", it.tabId ?: 0L).put("spotifySeconds", it.spotifySeconds).put("usingEdit", it.usingEdit)
                .put("rowNames", JSONObject().apply { it.rowNames.forEach { (k, v) -> put(k.toString(), v) } })
                .put("link", it.link ?: "").put("playlist", it.playlist ?: "")
                .put("skips", JSONArray(it.skips.map { (a, b) -> JSONArray(listOf(a, b)) }))
        })
        index.writeText(arr.toString())
    }
}
