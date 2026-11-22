package com.metronomekiller.app.engine

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Process
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

enum class BeatState { ACCENT, NORMAL, MUTE }

enum class ClickSound(val label: String) { CLICK("Click"), WOOD("Wood") }

/** Everything the engine needs to know to place clicks. Immutable; swap atomically. */
data class Pattern(
    val bpm: Double,
    val beats: List<BeatState>,
    val subdivision: Int, // clicks per beat: 1, 2, 3 or 4
    val sound: ClickSound,
)

/**
 * Interim click engine: a dedicated audio thread synthesises PCM into an AudioTrack
 * stream and counts samples between clicks, so timing is sample-accurate and never
 * drifts. Phase 1 replaces this with a native Oboe engine for lower output latency.
 */
class ClickEngine {
    private val sampleRate = 48_000

    @Volatile private var pattern = Pattern(120.0, List(4) { BeatState.NORMAL }, 1, ClickSound.CLICK)
    @Volatile private var running = false
    private var thread: Thread? = null

    /** Index of the beat that most recently sounded (-1 when stopped). */
    private val _beat = MutableStateFlow(-1)
    val beat: StateFlow<Int> = _beat

    fun update(p: Pattern) { pattern = p }

    fun start() {
        if (running) return
        running = true
        thread = Thread(::run, "click-engine").also { it.start() }
    }

    fun stop() {
        running = false
        thread?.join(500)
        thread = null
        _beat.value = -1
    }

    private fun synth(freq: Double, decayMs: Double, gain: Float): FloatArray {
        val n = (sampleRate * 0.06).toInt()
        val tau = decayMs / 1000.0 * sampleRate
        return FloatArray(n) { i -> (sin(2 * PI * freq * i / sampleRate) * exp(-i / tau) * gain).toFloat() }
    }

    private class Voices(val accent: FloatArray, val normal: FloatArray, val sub: FloatArray)

    private fun voicesFor(sound: ClickSound) = when (sound) {
        ClickSound.CLICK -> Voices(synth(1760.0, 12.0, 0.9f), synth(1320.0, 12.0, 0.7f), synth(1320.0, 8.0, 0.3f))
        ClickSound.WOOD -> Voices(synth(1050.0, 18.0, 0.9f), synth(800.0, 18.0, 0.7f), synth(800.0, 12.0, 0.3f))
    }

    private fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(sampleRate, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(minBuf * 2)
            .setPerformanceMode(AudioTrack.PERFORMANCE_MODE_LOW_LATENCY)
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()

        var sound = pattern.sound
        var voices = voicesFor(sound)
        var voice: FloatArray? = null
        var voicePos = 0
        var tick = 0L                 // click index since start
        var framesToNext = 0.0        // fractional frames until the next click
        val block = FloatArray(256)

        track.play()
        try {
            while (running) {
                val p = pattern
                if (p.sound != sound) { sound = p.sound; voices = voicesFor(sound) }
                val sub = p.subdivision.coerceIn(1, 4)
                val interval = sampleRate * 60.0 / (p.bpm * sub)

                for (i in block.indices) {
                    if (framesToNext <= 0.0) {
                        val clicksPerBar = p.beats.size * sub
                        val pos = (tick % clicksPerBar).toInt()
                        val beatIdx = pos / sub
                        val state = p.beats[beatIdx]
                        voice = when {
                            state == BeatState.MUTE -> null
                            pos % sub != 0 -> voices.sub
                            state == BeatState.ACCENT -> voices.accent
                            else -> voices.normal
                        }
                        voicePos = 0
                        if (pos % sub == 0) _beat.value = beatIdx
                        tick++
                        framesToNext += interval
                    }
                    val v = voice
                    block[i] = if (v != null && voicePos < v.size) v[voicePos++] else 0f
                    framesToNext -= 1.0
                }
                track.write(block, 0, block.size, AudioTrack.WRITE_BLOCKING)
            }
        } finally {
            track.stop()
            track.release()
        }
    }
}
