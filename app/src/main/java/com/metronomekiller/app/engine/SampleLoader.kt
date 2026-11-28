package com.metronomekiller.app.engine

import android.content.Context
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Loads the recorded drum hits (the .wav files in app/src/main/assets/sounds) into the engine.
 *
 * LEARN — the WAV format: a small header of "chunks" (each a 4-letter name plus
 * a length), then a "data" chunk holding the raw samples. Ours are 16-bit mono,
 * so each sample is a signed number from −32768 to 32767, stored little-endian
 * (low byte first). We divide by 32768 to get the −1..1 floats the engine uses.
 *
 * The files come from Hydrogen's GPL-3.0 drum kit and the CC0 VCSL library; tools/sounds/prepare_samples.py
 * trims them so each hit starts on its very first sample.
 */
object SampleLoader {
    /** Order must match the kSample* slots in ClickEngine.cpp. */
    private val SAMPLE_FILES = listOf(
        "stick_accent", "stick_normal", "cowbell_accent", "cowbell_normal", "bell_accent", "bell_normal",
        "hat_accent", "hat_normal", "hat_soft", "kick", "rim_accent", "rim_normal",
        "wood_accent", "wood_normal", "wood_soft", "clave_accent", "clave_normal", "clave_soft",
        "agogo_high", "agogo_low", "agogo_soft", "log_high", "log_low", "log_soft",
        "triangle_accent", "triangle_normal", "clap_accent", "clap_normal", "clap_soft",
    )

    /** Call once at app start, before the audio stream opens. A file that fails just falls back to the plain click. */
    fun load(context: Context) {
        SAMPLE_FILES.forEachIndexed { slot, name ->
            runCatching {
                val bytes = context.assets.open("sounds/$name.wav").use { it.readBytes() }
                val (samples, rate) = decodeWav(bytes)
                NativeEngine.nSetSample(slot, samples, rate)
            }
        }
    }

    /** 16-bit PCM mono WAV → (samples −1..1, sample rate). */
    fun decodeWav(bytes: ByteArray): Pair<FloatArray, Int> {
        val b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        require(String(bytes, 0, 4) == "RIFF" && String(bytes, 8, 4) == "WAVE") { "not a WAV file" }
        var pos = 12
        var rate = 0
        var bits = 0
        var channels = 0
        while (pos + 8 <= bytes.size) {
            val id = String(bytes, pos, 4)
            val len = b.getInt(pos + 4)
            val body = pos + 8
            when (id) {
                "fmt " -> { channels = b.getShort(body + 2).toInt(); rate = b.getInt(body + 4); bits = b.getShort(body + 14).toInt() }
                "data" -> {
                    require(bits == 16 && channels == 1) { "expected 16-bit mono" }
                    val n = minOf(len, bytes.size - body) / 2
                    return FloatArray(n) { b.getShort(body + it * 2) / 32768f } to rate
                }
            }
            pos = body + len + (len and 1)  // chunks are padded to an even length
        }
        error("no data chunk")
    }
}
