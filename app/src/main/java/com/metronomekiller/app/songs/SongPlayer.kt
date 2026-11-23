@file:androidx.annotation.OptIn(markerClass = [androidx.media3.common.util.UnstableApi::class])

package com.metronomekiller.app.songs

import android.content.Context
import android.net.Uri
import android.os.Handler
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import com.metronomekiller.app.analysis.TempoMap
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Plays a song with a click track mixed in, perfectly in time.
 *
 * LEARN — why the click is mixed INTO the song's audio: if the song and the
 * click played as two separate sounds, any small delay difference between them
 * (and Bluetooth adds a lot) would put the click off the beat. Instead the click
 * is added to the song's own sample stream by an "audio processor" inside
 * ExoPlayer, so they reach your ears together, always. Slowing the song down
 * slows the click with it, because the speed change happens after our mixing.
 */
class SongPlayer(context: Context) {
    private val mixer = ClickMixer()
    private val player: ExoPlayer = ExoPlayer.Builder(context, object : DefaultRenderersFactory(context) {
        override fun buildAudioSink(context: Context, enableFloatOutput: Boolean, enableAudioTrackPlaybackParams: Boolean): AudioSink =
            DefaultAudioSink.Builder(context).setAudioProcessors(arrayOf<AudioProcessor>(mixer)).build()
    }).build()

    private val scope = CoroutineScope(Dispatchers.Main)
    private var ticker: Job? = null

    private val _position = MutableStateFlow(0.0)
    /** Current song position in seconds (updated ~30 times a second while playing). */
    val position: StateFlow<Double> = _position
    private val _playing = MutableStateFlow(false)
    val playing: StateFlow<Boolean> = _playing
    private val _clickOn = MutableStateFlow(true)
    val clickOn: StateFlow<Boolean> = _clickOn
    private val _speed = MutableStateFlow(1f)
    val speed: StateFlow<Float> = _speed

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _playing.value = isPlaying
                if (isPlaying) startTicker() else ticker?.cancel()
            }
        })
    }

    private var skips: List<Pair<Double, Double>> = emptyList()

    /** Stretches to jump over while playing (SponsorBlock: sponsor reads, talking...). */
    fun setSkips(list: List<Pair<Double, Double>>) { skips = list }

    fun load(uri: String, map: TempoMap) {
        mixer.setClicks(map)
        mixer.startAt(0.0)
        player.setMediaItem(MediaItem.fromUri(Uri.parse(uri)))
        player.prepare()
        _position.value = 0.0
    }

    fun toggle() = if (player.isPlaying) player.pause() else player.play()
    fun pause() = player.pause()

    fun seekTo(sec: Double) {
        mixer.startAt(sec) // tell the mixer where the audio will resume
        player.seekTo((sec * 1000).toLong())
        _position.value = sec
    }

    fun setClickOn(on: Boolean) { _clickOn.value = on; mixer.enabled = on }

    /** Shift every click by [ms] milliseconds (fixes a song whose clicks sound early or late). */
    fun setOffsetMs(ms: Int) { mixer.offset = ms / 1000.0 }

    /** Mix levels: the song (0..1) and the click (0..2). Applied instantly, in the audio stream. */
    fun setLevels(song: Float, click: Float) { mixer.songGain = song; mixer.clickGain = click }

    /** 0.5x to 1.0x practice speed (pitch stays the same). */
    fun cycleSpeed() {
        val next = when (_speed.value) { 1f -> 0.75f; 0.75f -> 0.5f; else -> 1f }
        _speed.value = next
        player.playbackParameters = PlaybackParameters(next)
    }

    fun release() {
        ticker?.cancel()
        player.release()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                val now = player.currentPosition / 1000.0
                skips.firstOrNull { now >= it.first && now < it.second - 0.25 }?.let { seekTo(it.second) }
                _position.value = player.currentPosition / 1000.0
                delay(33)
            }
        }
    }
}

/**
 * Adds click sounds to 16-bit PCM audio at the tempo map's beat times.
 * Runs on ExoPlayer's audio thread; keeps its own sample counter so every
 * click lands on the exact sample of its beat.
 */
private class ClickMixer : BaseAudioProcessor() {
    @Volatile var enabled = true
    /** Song level 0..1 and click level 0..2 (above 1 = louder than the default). */
    @Volatile var songGain = 1f
    @Volatile var clickGain = 1f
    @Volatile private var times = DoubleArray(0)
    @Volatile private var accents = BooleanArray(0)
    @Volatile private var pendingStart = 0.0
    /** Per-song timing correction: positive = clicks later, negative = earlier (seconds). */
    @Volatile var offset = 0.0

    private var rate = 44100
    private var channels = 2
    private var frame = 0L          // absolute sample frame of the next input frame
    private var next = 0            // index of the next click to play
    private var voice: FloatArray? = null
    private var voicePos = 0
    private var accentVoice = FloatArray(0)
    private var normalVoice = FloatArray(0)

    fun setClicks(map: TempoMap) {
        val down = map.downbeats.toHashSet()
        times = map.beats
        accents = BooleanArray(map.beats.size) { map.beats[it] in down }
    }

    fun startAt(sec: Double) { pendingStart = sec }

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) throw AudioProcessor.UnhandledAudioFormatException(inputAudioFormat)
        rate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        accentVoice = synth(1760.0, 0.55f)
        normalVoice = synth(1320.0, 0.42f)
        return inputAudioFormat
    }

    private fun synth(freq: Double, gain: Float): FloatArray {
        val n = (rate * 0.06).toInt()
        return FloatArray(n) { i ->
            val t = i.toDouble() / rate
            (sin(2 * PI * freq * t) * exp(-t / 0.022) * gain).toFloat()
        }
    }

    override fun onFlush(streamMetadata: AudioProcessor.StreamMetadata) {
        // A flush happens on every seek. ExoPlayer tells us where the audio
        // really resumes (MP3 seeks can land a little off the requested spot);
        // if that value looks unusable we fall back to the position we asked for.
        val actual = streamMetadata.positionOffsetUs / 1e6
        val start = if (streamMetadata !== AudioProcessor.StreamMetadata.DEFAULT &&
            streamMetadata.positionOffsetUs != C.TIME_UNSET && kotlin.math.abs(actual - pendingStart) < 2.0
        ) actual else pendingStart
        restartAt(start)
    }

    override fun onFlush() = restartAt(pendingStart)

    private fun restartAt(pos: Double) {
        frame = (pos * rate).toLong()
        val t = pos - offset
        next = times.indexOfFirst { it >= t - 0.001 }.let { if (it < 0) times.size else it }
        voice = null
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val bytes = inputBuffer.remaining()
        if (bytes == 0) return
        val out = replaceOutputBuffer(bytes)
        val inS = inputBuffer.order(ByteOrder.nativeOrder()).asShortBuffer()
        val outS = out.order(ByteOrder.nativeOrder()).asShortBuffer()
        val frames = bytes / 2 / channels
        val ts = times
        val ac = accents
        val sg = songGain
        val cg = clickGain
        for (f in 0 until frames) {
            // Start a click when we reach its sample.
            val off = offset
            while (next < ts.size && ((ts[next] + off) * rate).toLong() <= frame) {
                if (enabled) { voice = if (ac[next]) accentVoice else normalVoice; voicePos = 0 }
                next++
            }
            val v = voice
            val add = if (v != null && voicePos < v.size) v[voicePos++] * 32767f * cg else 0f
            for (c in 0 until channels) {
                val s = inS.get() * sg + add
                outS.put(s.coerceIn(-32768f, 32767f).toInt().toShort())
            }
            frame++
        }
        inputBuffer.position(inputBuffer.position() + bytes)
        out.position(0)
        out.limit(bytes)
    }
}
