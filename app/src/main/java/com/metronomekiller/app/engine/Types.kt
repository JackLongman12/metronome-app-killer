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
 * A time signature, allowing experimental values most apps don't support.
 *
 * - Decimal numerator, e.g. 3.5/4: three full beats plus a half-length beat.
 * - "Irrational" bottom number, e.g. 4/3, 5/6, 4/3.2: each beat lasts
 *   4 ÷ bottom quarter notes (in 4/3 a beat is a quarter-note triplet × 2 =
 *   1.333 quarters; in 4/3.2 it's 1.25 quarters).
 *
 * LEARN — what BPM means: for normal bottom numbers (2, 4, 8, 16) BPM counts
 * the bottom note, like every metronome (6/8 at 120 = 120 eighth notes a
 * minute). For irrational ones it counts QUARTER notes, because "120 third-notes
 * a minute" would sound identical to 4/4 — the point of 4/3 is that its beats
 * are a different length than the quarter-note pulse around it.
 */
data class Meter(val numerator: Double, val denominator: Double) {
    val beatCount: Int get() = kotlin.math.ceil(numerator - 1e-9).toInt().coerceIn(1, MAX_BEATS)

    /** Length of the last beat: 0.5 for 3.5/4, 1.0 when the numerator is whole. */
    val lastBeatFraction: Double get() {
        val f = numerator - kotlin.math.floor(numerator)
        return if (f < 1e-6) 1.0 else f
    }

    val isIrrational: Boolean get() = denominator !in STANDARD_DENOMINATORS

    /** The rate the engine actually clicks beats at, for a displayed [bpm]. */
    fun beatsPerMinute(bpm: Int): Double = if (isIrrational) bpm * denominator / 4.0 else bpm.toDouble()

    val label: String get() = "${fmt(numerator)}/${fmt(denominator)}"

    companion object {
        const val MAX_BEATS = 16 // engine limit (4 bits per beat index)
        val STANDARD_DENOMINATORS = setOf(1.0, 2.0, 4.0, 8.0, 16.0, 32.0)
        fun fmt(d: Double): String =
            if (kotlin.math.abs(d - kotlin.math.round(d)) < 1e-9) kotlin.math.round(d).toLong().toString()
            else "%.3f".format(java.util.Locale.US, d).trimEnd('0').trimEnd('.')
    }
}

/**
 * A ready-made time signature with sensible accents. [groups] lists beat
 * groupings (3+2+2 → accents on beats 1, 4, 6); null = accent only beat 1.
 * The BPM always counts the bottom note for standard meters.
 */
data class TimeSignaturePreset(val meter: Meter, val groups: List<Int>? = null) {
    constructor(n: Number, d: Number, groups: List<Int>? = null) : this(Meter(n.toDouble(), d.toDouble()), groups)

    val label: String get() =
        if (groups != null && groups.size > 1) groups.joinToString("+") + "/" + Meter.fmt(meter.denominator)
        else meter.label

    fun pattern(): List<BeatState> {
        val accents = mutableSetOf(0)
        groups?.runningFold(0) { acc, g -> acc + g }?.dropLast(1)?.let { accents += it }
        return List(meter.beatCount) { if (it in accents) BeatState.ACCENT else BeatState.NORMAL }
    }

    companion object {
        /** The common grid at the top of the sheet. */
        val common = listOf(
            TimeSignaturePreset(2, 4), TimeSignaturePreset(3, 4), TimeSignaturePreset(4, 4), TimeSignaturePreset(5, 4),
            TimeSignaturePreset(6, 8, listOf(3, 3)), TimeSignaturePreset(7, 8, listOf(2, 2, 3)),
            TimeSignaturePreset(9, 8, listOf(3, 3, 3)), TimeSignaturePreset(12, 8, listOf(3, 3, 3, 3)),
        )

        /** Odd groupings used in prog, Balkan and math rock. */
        val additive = listOf(
            TimeSignaturePreset(5, 8, listOf(2, 3)), TimeSignaturePreset(5, 8, listOf(3, 2)),
            TimeSignaturePreset(7, 8, listOf(3, 2, 2)), TimeSignaturePreset(7, 8, listOf(2, 2, 3)),
            TimeSignaturePreset(8, 8, listOf(3, 3, 2)), TimeSignaturePreset(9, 8, listOf(2, 2, 2, 3)),
            TimeSignaturePreset(10, 8, listOf(3, 3, 2, 2)), TimeSignaturePreset(11, 8, listOf(3, 3, 3, 2)),
            TimeSignaturePreset(13, 8, listOf(3, 3, 3, 2, 2)), TimeSignaturePreset(15, 16, listOf(4, 4, 4, 3)),
            TimeSignaturePreset(7, 16, listOf(2, 2, 3)), TimeSignaturePreset(11, 16, listOf(3, 3, 3, 2)),
        )

        /** Decimal numerators: the last beat is cut short. */
        val decimal = listOf(
            TimeSignaturePreset(2.5, 4), TimeSignaturePreset(3.5, 4), TimeSignaturePreset(4.5, 4),
            TimeSignaturePreset(5.5, 4), TimeSignaturePreset(3.25, 4), TimeSignaturePreset(3.75, 4),
            TimeSignaturePreset(6.5, 8), TimeSignaturePreset(7.5, 8),
        )

        /** Irrational bottom numbers: beats of unusual length. */
        val irrational = listOf(
            TimeSignaturePreset(4, 3), TimeSignaturePreset(3, 3), TimeSignaturePreset(2, 3), TimeSignaturePreset(5, 6),
            TimeSignaturePreset(3, 5), TimeSignaturePreset(4, 5), TimeSignaturePreset(7, 12), TimeSignaturePreset(5, 12),
            TimeSignaturePreset(4, 7), TimeSignaturePreset(3, 10), TimeSignaturePreset(4, 3.2), TimeSignaturePreset(5, 4.5),
            TimeSignaturePreset(3, 2.5), TimeSignaturePreset(4, 6.4),
        )
    }
}
