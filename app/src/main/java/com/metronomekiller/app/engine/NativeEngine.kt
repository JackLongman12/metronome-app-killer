package com.metronomekiller.app.engine

/**
 * Kotlin's doorway into the C++ audio engine (app/src/main/cpp).
 *
 * LEARN: `external fun` means "this function's body is written in native code".
 * `System.loadLibrary("metronome")` loads libmetronome.so, which Gradle builds
 * from CMakeLists.txt and packs into the APK. The C++ side defines a function
 * named Java_com_metronomekiller_app_engine_NativeEngine_nStart (etc.) for each
 * of these — see jni.cpp.
 *
 * Why C++ at all? Audio runs on a real-time thread that must never pause.
 * Kotlin/Java has a garbage collector that can freeze threads for a few ms,
 * which you'd hear as a glitch. C++ with Google's Oboe library gives us the
 * lowest-latency audio path Android has.
 *
 * Don't call these directly from UI code — go through [MetronomeController].
 */
object NativeEngine {
    init {
        System.loadLibrary("metronome")
    }

    external fun nStart(clicks: Boolean): Boolean
    external fun nStop()
    external fun nIsRunning(): Boolean
    external fun nSetClicksEnabled(on: Boolean)
    external fun nPreview(sound: Int)
    external fun nSetTempo(bpm: Double)
    external fun nSetSubdivision(perBeat: Int)
    external fun nSetSound(sound: Int)
    external fun nSetVolume(volume: Float)
    external fun nSetBeats(states: IntArray)

    /**
     * Latest beat that has actually come out of the speaker by [nowNs]
     * (System.nanoTime clock), shifted by [offsetNs] of extra latency.
     * Packed as (sequence shl 8) or (state shl 4) or beatIndex; -1 if none.
     */
    external fun nAudibleBeat(nowNs: Long, offsetNs: Long): Long

    /** tapNs minus the nearest beat's output time, or Long.MIN_VALUE if unknown. */
    external fun nNearestBeatOffset(tapNs: Long): Long
}
