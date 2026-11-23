package com.metronomekiller.app.engine

/**
 * Shared vocabulary for the metronome.
 *
 * LEARN: an `enum class` is a fixed list of named values. `ordinal` is each
 * value's position (0, 1, 2...). We send ordinals to C++, so the ORDER here must
 * match the C++ side (BeatState in ClickEngine.h, buildAll() in ClickEngine.cpp).
 */
enum class BeatState { ACCENT, NORMAL, MUTE }

enum class ClickSound(val label: String) {
    CLICK("Click"),
    WOODBLOCK("Woodblock"),
    BEEP("Beep"),
    CLAVE("Clave"),
    COWBELL("Cowbell"),
    HIHAT("Hi-hat"),
    RIMSHOT("Rimshot"),
    KICK_HAT("Kick and hat"),
}

enum class Subdivision(val perBeat: Int, val label: String) {
    QUARTERS(1, "Quarters"),
    EIGHTHS(2, "Eighths"),
    TRIPLETS(3, "Triplets"),
    SIXTEENTHS(4, "Sixteenths"),
}

/** A beat that just became audible; drives the beat highlight, flash and vibration. */
data class BeatEvent(val index: Int, val state: BeatState, val sequence: Long)

/**
 * Common time signatures with sensible accents. For x/8 meters the accents
 * follow the usual groupings (6/8 = 3+3, 7/8 = 2+2+3, ...).
 * The BPM always counts the bottom note (in 6/8, BPM = eighth notes).
 */
data class TimeSignaturePreset(val beats: Int, val noteValue: Int, val accents: Set<Int>) {
    val label get() = "$beats/$noteValue"

    fun pattern(): List<BeatState> =
        List(beats) { if (it in accents) BeatState.ACCENT else BeatState.NORMAL }

    companion object {
        val all = listOf(
            TimeSignaturePreset(2, 4, setOf(0)),
            TimeSignaturePreset(3, 4, setOf(0)),
            TimeSignaturePreset(4, 4, setOf(0)),
            TimeSignaturePreset(5, 4, setOf(0)),
            TimeSignaturePreset(6, 8, setOf(0, 3)),
            TimeSignaturePreset(7, 8, setOf(0, 2, 4)),
            TimeSignaturePreset(9, 8, setOf(0, 3, 6)),
            TimeSignaturePreset(12, 8, setOf(0, 3, 6, 9)),
        )
    }
}
