package com.metronomekiller.app.metronome

import android.os.SystemClock
import androidx.lifecycle.ViewModel
import com.metronomekiller.app.engine.BeatState
import com.metronomekiller.app.engine.ClickEngine
import com.metronomekiller.app.engine.ClickSound
import com.metronomekiller.app.engine.Pattern
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update

enum class Subdivision(val perBeat: Int, val label: String) {
    QUARTERS(1, "Quarters"), EIGHTHS(2, "Eighths"), TRIPLETS(3, "Triplets"), SIXTEENTHS(4, "Sixteenths")
}

data class MetronomeUi(
    val bpm: Int = 120,
    val beats: List<BeatState> = listOf(BeatState.ACCENT, BeatState.NORMAL, BeatState.NORMAL, BeatState.NORMAL),
    val noteValue: Int = 4,
    val subdivision: Subdivision = Subdivision.QUARTERS,
    val sound: ClickSound = ClickSound.CLICK,
    val playing: Boolean = false,
)

class MetronomeViewModel : ViewModel() {
    private val engine = ClickEngine()
    private val _ui = MutableStateFlow(MetronomeUi())
    val ui: StateFlow<MetronomeUi> = _ui
    val currentBeat: StateFlow<Int> = engine.beat

    private val taps = ArrayDeque<Long>()

    init { push { it } } // sync the engine with the initial UI state

    private fun push(change: (MetronomeUi) -> MetronomeUi) {
        _ui.update(change)
        val s = _ui.value
        engine.update(Pattern(s.bpm.toDouble(), s.beats, s.subdivision.perBeat, s.sound))
    }

    fun setBpm(bpm: Int) = push { it.copy(bpm = bpm.coerceIn(MIN_BPM, MAX_BPM)) }
    fun nudge(delta: Int) = setBpm(_ui.value.bpm + delta)

    fun setTimeSignature(beats: Int, noteValue: Int) = push { s ->
        val list = List(beats) { i -> s.beats.getOrNull(i) ?: if (i == 0) BeatState.ACCENT else BeatState.NORMAL }
        s.copy(beats = list, noteValue = noteValue)
    }

    fun cycleBeat(index: Int) = push { s ->
        val next = when (s.beats[index]) {
            BeatState.ACCENT -> BeatState.NORMAL
            BeatState.NORMAL -> BeatState.MUTE
            BeatState.MUTE -> BeatState.ACCENT
        }
        s.copy(beats = s.beats.toMutableList().also { it[index] = next })
    }

    fun setSubdivision(sub: Subdivision) = push { it.copy(subdivision = sub) }

    fun cycleSound() = push {
        val all = ClickSound.entries
        it.copy(sound = all[(it.sound.ordinal + 1) % all.size])
    }

    fun togglePlay() {
        val playing = !_ui.value.playing
        push { it.copy(playing = playing) }
        if (playing) engine.start() else engine.stop()
    }

    /** Tap tempo: averages the last few intervals; a pause over 2 s starts fresh. */
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

    override fun onCleared() = engine.stop()

    companion object {
        const val MIN_BPM = 20
        const val MAX_BPM = 300

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
