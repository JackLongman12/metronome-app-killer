package com.metronomekiller.app.update

import com.metronomekiller.app.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * A build published by CI. Tags are `b<versionCode>` (see .github/workflows/build.yml),
 * so the versionCode can be read straight from the tag.
 */
data class ReleaseInfo(
    val versionCode: Int,
    val versionName: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
)

/**
 * LEARN — coroutines and threads:
 * Network calls are slow, and Android crashes an app that does networking on the
 * main (UI) thread. `suspend fun` + `withContext(Dispatchers.IO)` moves the work
 * to a background thread pool and pauses (not blocks) the caller until it's done.
 *
 * We call GitHub's public REST API — no login needed for public repos. Its
 * limit is 60 requests/hour per IP address, and on mobile data many phones
 * share one address, so the API can answer 403 ("rate limited"). Then we fall
 * back to the normal web page github.com/<repo>/releases/latest, which isn't
 * limited: it redirects to the newest tag (".../tag/b15"), and CI names the
 * APK after that tag, so we can build the download link ourselves.
 */
object UpdateChecker {
    private const val TIMEOUT_MS = 15_000

    /** Returns the latest release, or null if the repo has no usable release yet. */
    suspend fun fetchLatest(): ReleaseInfo? = withContext(Dispatchers.IO) {
        try {
            fromApi()
        } catch (e: RateLimited) {
            fromWebPage()
        }
    }

    private class RateLimited : IOException()

    private fun fromWebPage(): ReleaseInfo? {
        val repo = BuildConfig.GITHUB_REPO
        val conn = (URL("https://github.com/$repo/releases/latest").openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            instanceFollowRedirects = false // we want the redirect's address, not the page
        }
        try {
            val location = conn.getHeaderField("Location") ?: throw IOException("GitHub returned HTTP ${conn.responseCode}")
            val tag = location.substringAfterLast("/tag/", "").ifEmpty { return null }
            val code = tag.removePrefix("b").toIntOrNull() ?: return null
            return ReleaseInfo(
                versionCode = code,
                versionName = "Build $code",
                notes = "",
                apkUrl = "https://github.com/$repo/releases/download/$tag/metronome-killer-$tag.apk",
                apkSize = -1,
            )
        } finally {
            conn.disconnect()
        }
    }

    private fun fromApi(): ReleaseInfo? {
        val url = URL("https://api.github.com/repos/${BuildConfig.GITHUB_REPO}/releases/latest")
        val conn = (url.openConnection() as HttpURLConnection).apply {
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", "metronome-killer-updater") // GitHub's API rejects requests without one
        }
        try {
            when (conn.responseCode) {
                200 -> Unit
                404 -> return null
                403, 429 -> throw RateLimited()
                else -> throw IOException("GitHub returned HTTP ${conn.responseCode}")
            }
            val json = JSONObject(conn.inputStream.bufferedReader().use { it.readText() })
            val code = json.getString("tag_name").removePrefix("b").toIntOrNull()
                ?: return null
            val assets = json.getJSONArray("assets")
            val apk = (0 until assets.length())
                .map { assets.getJSONObject(it) }
                .firstOrNull { it.getString("name").endsWith(".apk") }
                ?: return null
            return ReleaseInfo(
                versionCode = code,
                versionName = json.optString("name", "Build $code"),
                notes = json.optString("body", ""),
                apkUrl = apk.getString("browser_download_url"),
                apkSize = apk.optLong("size", -1),
            )
        } finally {
            conn.disconnect()
        }
    }

    /** Streams the APK to [dest], reporting progress in 0..1 (or -1 if size unknown). */
    suspend fun download(info: ReleaseInfo, dest: File, onProgress: (Float) -> Unit) =
        withContext(Dispatchers.IO) {
            dest.parentFile?.mkdirs()
            val conn = (URL(info.apkUrl).openConnection() as HttpURLConnection).apply {
                connectTimeout = TIMEOUT_MS
                readTimeout = TIMEOUT_MS
                instanceFollowRedirects = true // GitHub redirects assets to its CDN
            }
            try {
                if (conn.responseCode != 200) throw IOException("Download failed: HTTP ${conn.responseCode}")
                val total = conn.contentLengthLong.takeIf { it > 0 } ?: info.apkSize
                conn.inputStream.use { input ->
                    dest.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            done += n
                            onProgress(if (total > 0) done.toFloat() / total else -1f)
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
        }
}
