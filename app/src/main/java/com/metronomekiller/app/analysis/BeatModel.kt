package com.metronomekiller.app.analysis

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.FloatBuffer
import java.security.MessageDigest

/**
 * The beat-tracking neural network (beat_this by CPJKU, MIT licence), run with
 * ONNX Runtime.
 *
 * LEARN — why it's downloaded instead of bundled: the model is ~28 MB. Most
 * people open a metronome for the metronome, so the app stays small and fetches
 * the model the first time you analyse a song. It's checked with a SHA-256
 * fingerprint so a broken download is never used.
 *
 * LEARN — what the model file contains: audio in → spectrogram → transformer →
 * a "beat score" and "downbeat score" for every 20 ms of audio (50 per second).
 * Scores above 0 mean "probably a beat here". The spectrogram is built into the
 * file (see tools/beat-lab/export_phone_model.py), so we only feed raw audio.
 */
class BeatModel private constructor(private val session: OrtSession, private val env: OrtEnvironment) : AutoCloseable {

    /** Runs one chunk of 22050 Hz audio; returns (beat, downbeat) scores per frame. */
    fun run(audio: FloatArray, offset: Int, length: Int): Pair<FloatArray, FloatArray> {
        val input = OnnxTensor.createTensor(env, FloatBuffer.wrap(audio, offset, length).slice(), longArrayOf(1, length.toLong()))
        input.use {
            session.run(mapOf("audio" to it)).use { result ->
                @Suppress("UNCHECKED_CAST")
                val beat = (result[0].value as Array<FloatArray>)[0]
                @Suppress("UNCHECKED_CAST")
                val down = (result[1].value as Array<FloatArray>)[0]
                return beat to down
            }
        }
    }

    override fun close() = session.close()

    companion object {
        private const val FILE = "beat_this_v1_int8.onnx"
        private const val URL_ = "https://github.com/JackLongman12/metronome-app-killer/releases/download/model-v1/$FILE"
        private const val SHA256 = "e600b1ab4b968ba3ff59854b9473370f0c6450d845ef06f7de34a3e0f735970f"
        const val SIZE_MB = 28

        fun file(context: Context) = File(context.filesDir, "models/$FILE")
        fun isDownloaded(context: Context) = file(context).exists()

        /** Downloads the model if needed (progress 0..1). Blocking: call from a background thread. */
        fun ensureDownloaded(context: Context, onProgress: (Float) -> Unit) {
            val dest = file(context)
            if (dest.exists()) return
            dest.parentFile?.mkdirs()
            val tmp = File(dest.path + ".part")
            val conn = (URL(URL_).openConnection() as HttpURLConnection).apply {
                connectTimeout = 20_000
                readTimeout = 30_000
                instanceFollowRedirects = true
            }
            try {
                if (conn.responseCode != 200) throw IOException("Model download failed (HTTP ${conn.responseCode})")
                val total = conn.contentLengthLong
                val digest = MessageDigest.getInstance("SHA-256")
                conn.inputStream.use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var done = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            done += n
                            if (total > 0) onProgress(done.toFloat() / total)
                        }
                    }
                }
                val hex = digest.digest().joinToString("") { "%02x".format(it) }
                if (hex != SHA256) {
                    tmp.delete()
                    throw IOException("Model download was corrupted. Try again.")
                }
                tmp.renameTo(dest)
            } finally {
                conn.disconnect()
            }
        }

        fun load(context: Context): BeatModel {
            val env = OrtEnvironment.getEnvironment()
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(Runtime.getRuntime().availableProcessors().coerceIn(2, 4))
                setOptimizationLevel(OrtSession.SessionOptions.OptLevel.ALL_OPT)
            }
            return BeatModel(env.createSession(file(context).path, opts), env)
        }
    }
}

/**
 * Turns a whole song into beat and downbeat times, chunk by chunk.
 * A line-by-line port of phone_pipeline() + peak picking in
 * tools/beat-lab/export_phone_model.py, which was checked against the original
 * model (100 % agreement).
 */
object BeatPipeline {
    private const val HOP = 441          // samples per frame at 22050 Hz → 50 frames/s
    private const val N_FFT = 1024
    private const val CHUNK = 1500       // frames per chunk (30 s), what the model was trained on
    private const val BORDER = 6         // frames dropped at chunk edges (less reliable there)
    const val FPS = 50

    class Result(val beats: DoubleArray, val downbeats: DoubleArray, val downbeatScores: FloatArray)

    fun analyse(model: BeatModel, sig: FloatArray, onProgress: (Float) -> Unit): Result {
        val nFrames = 1 + sig.size / HOP
        // Reflect-pad 512 samples at both ends (what torchaudio's center=True does).
        val pad = N_FFT / 2
        val padded = FloatArray(sig.size + 2 * pad)
        System.arraycopy(sig, 0, padded, pad, sig.size)
        for (i in 0 until pad) {
            padded[pad - 1 - i] = sig.getOrElse(i + 1) { 0f }
            padded[pad + sig.size + i] = sig.getOrElse(sig.size - 2 - i) { 0f }
        }

        val starts = generateSequence(-BORDER) { it + CHUNK - 2 * BORDER }.takeWhile { it < nFrames - BORDER }.toMutableList()
        if (nFrames > CHUNK - 2 * BORDER) starts[starts.lastIndex] = nFrames - (CHUNK - BORDER)
        val beat = FloatArray(nFrames) { -1000f }
        val down = FloatArray(nFrames) { -1000f }

        // keep_first: process from the last chunk to the first so earlier chunks win overlaps
        starts.reversed().forEachIndexed { done, st ->
            val lo = maxOf(st, 0)
            val hi = minOf(st + CHUNK, nFrames)
            val from = lo * HOP
            val len = minOf((hi - 1) * HOP + N_FFT, padded.size) - from
            val (b, d) = model.run(padded, from, len)
            val k0 = maxOf(st + BORDER, 0)
            val k1 = minOf(st + CHUNK - BORDER, nFrames)
            for (k in k0 until k1) {
                val j = k - lo
                if (j in b.indices) { beat[k] = b[j]; down[k] = d[j] }
            }
            onProgress((done + 1f) / starts.size)
        }

        val b = peaks(beat)
        var d = peaks(down)
        if (b.isNotEmpty()) {
            // snap each downbeat to the nearest beat, then de-duplicate
            d = d.map { x -> b.minBy { kotlin.math.abs(it - x) } }.distinct().sorted().toDoubleArray()
        }
        return Result(b, d, down)
    }

    /** Local maximum within ±3 frames and score > 0; neighbouring peak frames merged. */
    private fun peaks(s: FloatArray): DoubleArray {
        val idx = ArrayList<Int>()
        for (i in s.indices) {
            if (s[i] <= 0f) continue
            var isMax = true
            for (k in maxOf(0, i - 3)..minOf(s.lastIndex, i + 3)) if (s[k] > s[i]) { isMax = false; break }
            if (isMax) idx += i
        }
        val out = ArrayList<Double>()
        var run = ArrayList<Int>()
        for (i in idx) {
            if (run.isNotEmpty() && i - run.last() > 1) { out += run.average() / FPS; run = ArrayList() }
            run += i
        }
        if (run.isNotEmpty()) out += run.average() / FPS
        return out.toDoubleArray()
    }
}
