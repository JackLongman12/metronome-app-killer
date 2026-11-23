package com.metronomekiller.app.songs

import android.content.Context
import android.content.Intent
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.OpenableColumns
import com.metronomekiller.app.analysis.AudioDecoder
import com.metronomekiller.app.analysis.BeatModel
import com.metronomekiller.app.analysis.BeatPipeline
import com.metronomekiller.app.analysis.TempoMap
import com.metronomekiller.app.analysis.TempoMapBuilder
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

    private val _songs = MutableStateFlow(load())
    val songs: StateFlow<List<Song>> = _songs

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

    fun remove(id: String) {
        _songs.update { list -> list.filterNot { it.id == id } }
        File(dir, "$id.json").delete()
        save()
    }

    fun retry(id: String) = analyse(id)

    private fun setStatus(id: String, status: SongStatus, map: TempoMap? = null) {
        _songs.update { list -> list.map { if (it.id == id) it.copy(status = status, map = map ?: it.map) else it } }
    }

    private fun analyse(id: String) {
        setStatus(id, SongStatus.Queued)
        scope.launch {
            queue.withLock {
                val song = song(id) ?: return@withLock
                try {
                    if (!BeatModel.isDownloaded(context)) {
                        BeatModel.ensureDownloaded(context) { p ->
                            setStatus(id, SongStatus.Working("Downloading the beat model (${BeatModel.SIZE_MB} MB)", p))
                        }
                    }
                    setStatus(id, SongStatus.Working("Reading the song", 0f))
                    val audio = AudioDecoder.decodeToMono22k(context, Uri.parse(song.uri)) { p ->
                        setStatus(id, SongStatus.Working("Reading the song", p))
                    }
                    setStatus(id, SongStatus.Working("Finding the beats", 0f))
                    val result = BeatModel.load(context).use { model ->
                        BeatPipeline.analyse(model, audio.samples) { p -> setStatus(id, SongStatus.Working("Finding the beats", p)) }
                    }
                    val map = TempoMapBuilder.build(result.beats, result.downbeats, audio.durationSec, result.downbeatScores)
                    File(dir, "$id.json").writeText(map.toJson().toString())
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
            Song(id, o.getString("title"), o.optString("artist"), o.getString("uri"), o.getLong("addedAt"),
                map, if (map != null) SongStatus.Ready else SongStatus.Queued)
        }
    }.getOrDefault(emptyList())

    private fun save() {
        val arr = JSONArray(_songs.value.map {
            JSONObject().put("id", it.id).put("title", it.title).put("artist", it.artist)
                .put("uri", it.uri).put("addedAt", it.addedAt)
        })
        index.writeText(arr.toString())
    }
}
