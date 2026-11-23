package com.metronomekiller.app.engine

import android.content.Context
import android.content.Intent
import android.media.AudioDeviceCallback
import android.media.AudioDeviceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.core.content.ContextCompat
import com.metronomekiller.app.AppPrefs
import com.metronomekiller.app.PlaybackService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/** Everything the metronome screen shows. Immutable: every change makes a new copy. */
data class MetronomeState(
    val bpm: Int = 120,
    val beats: List<BeatState> = TimeSignaturePreset.all[2].pattern(), // 4/4
    val noteValue: Int = 4,
    val subdivision: Subdivision = Subdivision.QUARTERS,
    val sound: ClickSound = ClickSound.CLICK,
    val playing: Boolean = false,
)

/**
 * The single owner of metronome state and the audio engine.
 *
 * LEARN — why a singleton and not a ViewModel?
 * A ViewModel dies with its screen. But the click must keep going when you lock
 * the phone or switch apps, and the notification's Stop button must reach the
 * same engine. So state lives here, at the app level, created once in [App].
 * Screens and [PlaybackService] all read and write this one object.
 *
 * LEARN — StateFlow vs SharedFlow:
 * - [state] is a StateFlow: it always HAS a current value (the BPM is always something).
 * - [beatEvents] is a SharedFlow: a stream of one-off events (a beat happened),
 *   with no "current" value. Flash and vibration react to these.
 */
class MetronomeController(private val context: Context, val prefs: AppPrefs) {
    // LEARN: a CoroutineScope is a container for background tasks. SupervisorJob
    // means one failing task doesn't cancel the others.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val audioManager = context.getSystemService(AudioManager::class.java)

    private val _state = MutableStateFlow(MetronomeState())
    val state: StateFlow<MetronomeState> = _state.asStateFlow()

    private val _beatEvents = MutableSharedFlow<BeatEvent>(extraBufferCapacity = 8)
    val beatEvents: SharedFlow<BeatEvent> = _beatEvents

    /** Index of the beat currently sounding, -1 when stopped. */
    private val _currentBeat = MutableStateFlow(-1)
    val currentBeat: StateFlow<Int> = _currentBeat

    private val _route = MutableStateFlow(AudioRoute.current(audioManager))
    val route: StateFlow<AudioRoute> = _route

    private var poller: Job? = null
    private var previewStopper: Job? = null

    /** True while a screen of the app is visible. */
    private var foreground = false

    init {
        pushAll()
        // Re-detect the output when headphones/Bluetooth connect or disconnect,
        // so the right latency offset is used immediately.
        audioManager.registerAudioDeviceCallback(object : AudioDeviceCallback() {
            override fun onAudioDevicesAdded(added: Array<out AudioDeviceInfo>) = refreshRoute()
            override fun onAudioDevicesRemoved(removed: Array<out AudioDeviceInfo>) = refreshRoute()
        }, Handler(Looper.getMainLooper()))
    }

    private fun refreshRoute() { _route.value = AudioRoute.current(audioManager) }

    // ── Editing ─────────────────────────────────────────────────────────────

    /** Apply a change to the state, then send the new values to the C++ engine. */
    private fun edit(change: (MetronomeState) -> MetronomeState) {
        _state.update(change)
        pushAll()
    }

    private fun pushAll() {
        val s = _state.value
        NativeEngine.nSetTempo(s.bpm.toDouble())
        NativeEngine.nSetBeats(IntArray(s.beats.size) { s.beats[it].ordinal })
        NativeEngine.nSetSubdivision(s.subdivision.perBeat)
        NativeEngine.nSetSound(s.sound.ordinal)
        NativeEngine.nSetVolume(prefs.volume.value)
    }

    fun setVolume(v: Float) {
        prefs.setVolume(v)
        NativeEngine.nSetVolume(v)
    }

    /**
     * Called when the app comes to the front / goes to the back.
     *
     * LEARN: opening an audio stream talks to the audio hardware and can take
     * 100–300 ms. Doing that when you press play makes the first click late,
     * which feels unresponsive. So while the app is on screen we keep a silent
     * stream open ("warm"); play just switches the clicks on — instant.
     * When the app leaves the screen (and isn't playing) we close it to save battery.
     */
    fun setForeground(visible: Boolean) {
        foreground = visible
        if (_state.value.playing) return
        if (visible) NativeEngine.nStart(clicks = false) else NativeEngine.nStop()
    }

    fun setBpm(bpm: Int) = edit { it.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM)) }
    fun nudge(delta: Int) = setBpm(_state.value.bpm + delta)

    fun setBeat(index: Int, value: BeatState) = edit { s ->
        if (index !in s.beats.indices) s
        else s.copy(beats = s.beats.toMutableList().also { it[index] = value })
    }

    /** Tap on a beat: accent → normal → mute → accent. */
    fun cycleBeat(index: Int) {
        val cur = _state.value.beats.getOrNull(index) ?: return
        setBeat(index, when (cur) {
            BeatState.ACCENT -> BeatState.NORMAL
            BeatState.NORMAL -> BeatState.MUTE
            BeatState.MUTE -> BeatState.ACCENT
        })
    }

    /** Set beats/note value; keeps the existing accents when only the count changes. */
    fun setTimeSignature(beats: Int, noteValue: Int, pattern: List<BeatState>? = null) = edit { s ->
        val list = pattern ?: List(beats) { i ->
            s.beats.getOrNull(i) ?: if (i == 0) BeatState.ACCENT else BeatState.NORMAL
        }
        s.copy(beats = list, noteValue = noteValue)
    }

    fun setSubdivision(sub: Subdivision) = edit { it.copy(subdivision = sub) }

    fun setSound(sound: ClickSound) = edit { it.copy(sound = sound) }

    /** Replace all settings at once (keeps play/stop as is). Used to restore after calibration. */
    fun restore(saved: MetronomeState) = edit { saved.copy(playing = it.playing) }

    /** Plays one hit of [sound] so you can hear it in the sound picker. */
    fun preview(sound: ClickSound) {
        if (!_state.value.playing) {
            // Open a silent stream just for the preview, and close it shortly after.
            NativeEngine.nStart(clicks = false)
            previewStopper?.cancel()
            if (!foreground) previewStopper = scope.launch {
                delay(600)
                if (!_state.value.playing && !foreground) NativeEngine.nStop()
            }
        }
        NativeEngine.nPreview(sound.ordinal)
    }

    // ── Tap tempo ───────────────────────────────────────────────────────────

    private val taps = ArrayDeque<Long>()

    /** Averages the last few tap intervals; a pause over 2 s starts fresh. */
    fun tap() {
        val now = SystemClock.elapsedRealtime()
        if (taps.isNotEmpty() && now - taps.last() > 2000) taps.clear()
        taps.addLast(now)
        while (taps.size > 5) taps.removeFirst()
        if (taps.size >= 2) {
            val avg = (taps.last() - taps.first()).toDouble() / (taps.size - 1)
            setBpm((60_000.0 / avg).toInt())
        }
    }

    // ── Play / stop ─────────────────────────────────────────────────────────

    fun togglePlay() = if (_state.value.playing) stop() else start()

    fun start() {
        if (_state.value.playing) return
        previewStopper?.cancel()
        pushAll()
        if (!NativeEngine.nStart(clicks = true)) return
        _state.update { it.copy(playing = true) }
        startPoller()
        // A foreground service keeps the app alive (and audible) with the screen off.
        ContextCompat.startForegroundService(context, Intent(context, PlaybackService::class.java))
    }

    fun stop() {
        // Keep the stream warm while the app is visible so the next play is instant.
        if (foreground) NativeEngine.nSetClicksEnabled(false) else NativeEngine.nStop()
        poller?.cancel()
        _currentBeat.value = -1
        _state.update { it.copy(playing = false) }
    }

    /**
     * Watches the engine ~250 times a second and announces each beat at the
     * moment it actually comes out of the speaker/headphones.
     *
     * LEARN: the C++ engine logs which audio frame each beat starts on. Using the
     * stream's timestamp (which frame is playing right now) it can tell us when a
     * beat is really heard. We add the user's calibrated offset for Bluetooth,
     * whose delay the phone can't always measure.
     */
    private fun startPoller() {
        poller?.cancel()
        poller = scope.launch {
            var lastSeq = -1L
            while (isActive) {
                val offsetNs = prefs.latencyMs(_route.value.key) * 1_000_000L
                val packed = NativeEngine.nAudibleBeat(System.nanoTime(), offsetNs)
                if (packed >= 0) {
                    val seq = packed shr 8
                    if (seq != lastSeq) {
                        lastSeq = seq
                        val index = (packed and 0xF).toInt()
                        val state = BeatState.entries[((packed shr 4) and 0xF).toInt()]
                        _currentBeat.value = index
                        _beatEvents.tryEmit(BeatEvent(index, state, seq))
                        if (prefs.vibrate.value && state != BeatState.MUTE) vibrate(state == BeatState.ACCENT)
                    }
                }
                delay(4)
            }
        }
    }

    private val vibrator: Vibrator by lazy {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            context.getSystemService(VibratorManager::class.java).defaultVibrator
        } else {
            @Suppress("DEPRECATION")
            context.getSystemService(Vibrator::class.java)
        }
    }

    private fun vibrate(accent: Boolean) {
        val effect = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            VibrationEffect.createPredefined(if (accent) VibrationEffect.EFFECT_HEAVY_CLICK else VibrationEffect.EFFECT_CLICK)
        } else {
            VibrationEffect.createOneShot(if (accent) 35 else 18, VibrationEffect.DEFAULT_AMPLITUDE)
        }
        vibrator.vibrate(effect)
    }

    // ── Latency calibration ─────────────────────────────────────────────────

    /** How far (ms) a tap at [tapNs] was from the nearest beat's output time, or null. */
    fun tapOffsetMs(tapNs: Long): Double? {
        val d = NativeEngine.nNearestBeatOffset(tapNs)
        return if (d == Long.MIN_VALUE) null else d / 1_000_000.0
    }

    companion object {
        const val MIN_BPM = 20
        const val MAX_BPM = 500

        fun tempoName(bpm: Int) = when {
            bpm < 40 -> "Grave"
            bpm < 60 -> "Largo"
            bpm < 76 -> "Adagio"
            bpm < 108 -> "Andante"
            bpm < 120 -> "Moderato"
            bpm < 156 -> "Allegro"
            bpm < 176 -> "Vivace"
            bpm < 200 -> "Presto"
            else -> "Prestissimo"
        }
    }
}
