package com.metronomekiller.app.links

import org.json.JSONArray
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.StreamingService
import org.schabi.newpipe.extractor.downloader.Downloader
import org.schabi.newpipe.extractor.downloader.Request
import org.schabi.newpipe.extractor.downloader.Response
import org.schabi.newpipe.extractor.exceptions.ReCaptchaException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * YouTube and SoundCloud links, via NewPipe Extractor (GPL-3.0, the engine
 * behind the NewPipe app).
 *
 * LEARN — why there are no ads: we never use YouTube's player. NewPipe Extractor
 * reads the video page the way a browser would and finds the direct address of
 * the AUDIO file. Ads are separate videos that only YouTube's player inserts,
 * so they never reach us. We download that audio once and keep it, so the song
 * plays offline afterwards.
 *
 * Sponsor segments read out inside the video itself are part of the audio, so
 * those we skip using SponsorBlock (a free, community database of timestamps).
 */
object Links {
    private const val UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64; rv:128.0) Gecko/20100101 Firefox/128.0"
    @Volatile private var ready = false

    private fun init() {
        if (!ready) synchronized(this) {
            if (!ready) { NewPipe.init(HttpDownloader); ready = true }
        }
    }

    /** Pulls the first http(s) link out of shared text like "Check this out https://youtu.be/..." */
    fun findUrl(text: String): String? = Regex("""https?://\S+""").find(text)?.value?.trimEnd('.', ',', ')')

    sealed interface Resolved {
        data class Single(val item: Item) : Resolved
        data class Playlist(val name: String, val items: List<Item>) : Resolved
    }

    data class Item(val url: String, val title: String, val artist: String, val durationSec: Long)

    /** Works out what a link is. Blocking network call: run on a background thread. */
    fun resolve(url: String): Resolved {
        init()
        val service = NewPipe.getServiceByUrl(url)
        return when (service.getLinkTypeByUrl(url)) {
            StreamingService.LinkType.STREAM -> {
                val info = StreamInfo.getInfo(service, url)
                Resolved.Single(Item(url, info.name, info.uploaderName ?: "", info.duration))
            }
            StreamingService.LinkType.PLAYLIST -> {
                val info = PlaylistInfo.getInfo(service, url)
                val items = info.relatedItems.toMutableList()
                var page: Page? = info.nextPage
                // Playlists arrive in pages; keep fetching (capped so a 5,000-song mix can't run away).
                while (page != null && Page.isValid(page) && items.size < 300) {
                    val more = PlaylistInfo.getMoreItems(service, url, page)
                    items += more.items
                    page = more.nextPage
                }
                Resolved.Playlist(info.name, items.map { Item(it.url, it.name, it.uploaderName ?: "", it.duration) })
            }
            else -> throw IOException("That link isn't a song or a playlist.")
        }
    }

    /**
     * Downloads the best audio-only stream of [url] into [dir]; returns the file.
     * Uses 1 MB range requests: YouTube slows down single huge downloads, but
     * serves chunked ones (like a video player makes) at full speed.
     */
    /**
     * LEARN — picking the download: YouTube offers the same audio in several
     * codecs and qualities. Opus at ~70 kbps sounds about as good as AAC at 128
     * kbps, so it's about half the storage (≈2 MB per 4 minutes). The beat model
     * only listens to 22 kHz mono anyway. So: prefer Opus, and the stream closest
     * to 70 kbps (below 48 kbps starts to sound thin). AAC/m4a is the fallback.
     */
    private fun sizeScore(bitrate: Int): Int {
        val kbps = if (bitrate > 1000) bitrate / 1000 else bitrate // NewPipe reports kbps, guard against bps
        return when {
            kbps <= 0 -> 1000          // unknown: last resort
            kbps < 48 -> 500 + (48 - kbps)
            else -> kotlin.math.abs(kbps - 70)
        }
    }

    fun downloadAudio(url: String, dir: File, baseName: String, onProgress: (Float) -> Unit): File {
        init()
        val service = NewPipe.getServiceByUrl(url)
        val info = StreamInfo.getInfo(service, url)
        val stream = info.audioStreams
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isUrl }
            .sortedWith(compareBy({ it.format != org.schabi.newpipe.extractor.MediaFormat.WEBMA_OPUS }, { sizeScore(it.averageBitrate) }))
            .firstOrNull() ?: throw IOException("No downloadable audio for this video.")
        // Opus streams come in a WebM container; ".webm" is what Android's extractor expects.
        val ext = stream.format?.suffix ?: "audio"
        dir.mkdirs()
        val out = File(dir, "$baseName.$ext")
        val tmp = File(dir, "$baseName.part")
        val total = contentLength(stream.content)
        tmp.outputStream().use { file ->
            var done = 0L
            val chunk = 1L shl 20
            while (total <= 0 || done < total) {
                val end = if (total > 0) minOf(done + chunk, total) - 1 else done + chunk - 1
                val conn = (URL(stream.content).openConnection() as HttpURLConnection).apply {
                    setRequestProperty("User-Agent", UA)
                    setRequestProperty("Range", "bytes=$done-$end")
                    connectTimeout = 20_000; readTimeout = 30_000
                }
                try {
                    if (conn.responseCode == 416) break // past the end (unknown length)
                    if (conn.responseCode !in 200..299) throw IOException("Download failed (HTTP ${conn.responseCode})")
                    val n = conn.inputStream.use { it.copyTo(file) }
                    if (n == 0L) break
                    done += n
                    if (total > 0) onProgress(done.toFloat() / total)
                    if (conn.responseCode == 200) break // server ignored Range and sent everything
                } finally {
                    conn.disconnect()
                }
            }
        }
        tmp.renameTo(out)
        return out
    }

    private fun contentLength(url: String): Long {
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "HEAD"; setRequestProperty("User-Agent", UA); connectTimeout = 20_000
        }
        return try { conn.contentLengthLong } catch (_: Exception) { -1 } finally { conn.disconnect() }
    }

    /** YouTube video id, or null for other sites. */
    fun youtubeId(url: String): String? = runCatching {
        init()
        val s = NewPipe.getServiceByUrl(url)
        if (s.serviceInfo.name.equals("YouTube", true)) s.streamLHFactory.getId(url) else null
    }.getOrNull()

    /**
     * SponsorBlock: stretches of a YouTube video that aren't the song
     * (sponsor reads, intros, outros, talking). Returns [start, end] pairs in seconds.
     * Sends only the video id. Missing data (HTTP 404) just means "nothing to skip".
     */
    fun sponsorSegments(videoId: String): List<Pair<Double, Double>> = runCatching {
        val cats = """["sponsor","selfpromo","interaction","intro","outro","music_offtopic"]"""
        val u = "https://sponsor.ajay.app/api/skipSegments?videoID=$videoId&categories=" + java.net.URLEncoder.encode(cats, "UTF-8")
        val conn = (URL(u).openConnection() as HttpURLConnection).apply { connectTimeout = 10_000; readTimeout = 10_000 }
        try {
            if (conn.responseCode != 200) return@runCatching emptyList()
            val arr = JSONArray(conn.inputStream.bufferedReader().readText())
            List(arr.length()) { i -> arr.getJSONObject(i).getJSONArray("segment").let { it.getDouble(0) to it.getDouble(1) } }
        } finally { conn.disconnect() }
    }.getOrDefault(emptyList())

    /** NewPipe Extractor asks the app to do its HTTP requests; this does them with plain HttpURLConnection. */
    private object HttpDownloader : Downloader() {
        override fun execute(request: Request): Response {
            val conn = (URL(request.url()).openConnection() as HttpURLConnection).apply {
                requestMethod = request.httpMethod()
                instanceFollowRedirects = true
                connectTimeout = 20_000; readTimeout = 30_000
                setRequestProperty("User-Agent", UA)
                request.headers().forEach { (k, vs) -> vs.forEach { addRequestProperty(k, it) } }
            }
            request.dataToSend()?.let { body ->
                conn.doOutput = true
                conn.outputStream.use { it.write(body) }
            }
            val code = conn.responseCode
            if (code == 429) throw ReCaptchaException("Too many requests, try again later", request.url())
            val body = runCatching {
                (if (code >= 400) conn.errorStream else conn.inputStream)?.bufferedReader()?.readText()
            }.getOrNull() ?: ""
            val headers = conn.headerFields.filterKeys { it != null }
            return Response(code, conn.responseMessage ?: "", headers, body, conn.url.toString())
        }
    }
}
