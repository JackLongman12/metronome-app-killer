package com.metronomekiller.app.analysis

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Downloads model files from this repo's GitHub releases (once), checks their
 * SHA-256 fingerprint, and opens them with ONNX Runtime.
 */
object ModelFiles {
    data class Spec(val file: String, val url: String, val sha256: String)

    private fun dest(context: Context, spec: Spec) = File(context.filesDir, "models/${spec.file}")

    fun isDownloaded(context: Context, specs: List<Spec>) = specs.all { dest(context, it).exists() }

    /** Downloads every missing file; progress 0..1 across all of them. Blocking. */
    fun ensure(context: Context, specs: List<Spec>, onProgress: (Float) -> Unit) {
        specs.forEachIndexed { i, spec ->
            download(context, spec) { p -> onProgress((i + p) / specs.size) }
        }
    }

    private fun download(context: Context, spec: Spec, onProgress: (Float) -> Unit) {
        val out = dest(context, spec)
        if (out.exists()) return
        out.parentFile?.mkdirs()
        val tmp = File(out.path + ".part")
        val conn = (URL(spec.url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 20_000; readTimeout = 30_000; instanceFollowRedirects = true
        }
        try {
            if (conn.responseCode != 200) throw IOException("Model download failed (HTTP ${conn.responseCode})")
            val total = conn.contentLengthLong
            val digest = MessageDigest.getInstance("SHA-256")
            conn.inputStream.use { input ->
                tmp.outputStream().use { o ->
                    val buf = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        o.write(buf, 0, n); digest.update(buf, 0, n); done += n
                        if (total > 0) onProgress(done.toFloat() / total)
                    }
                }
            }
            if (digest.digest().joinToString("") { "%02x".format(it) } != spec.sha256) {
                tmp.delete(); throw IOException("Model download was corrupted. Try again.")
            }
            tmp.renameTo(out)
        } finally { conn.disconnect() }
    }

    fun open(context: Context, spec: Spec): OrtSession {
        val opts = OrtSession.SessionOptions().apply {
            setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
            setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
        }
        return OrtEnvironment.getEnvironment().createSession(dest(context, spec).path, opts)
    }
}
