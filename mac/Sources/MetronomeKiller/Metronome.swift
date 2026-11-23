import ClickCore
import Foundation
import SwiftUI

/**
 * The one owner of metronome settings and the audio engine (like Android's
 * MetronomeController).
 *
 * LEARN — ObservableObject: SwiftUI screens watch this object. Whenever a
 * `@Published` property changes, every screen showing it redraws by itself.
 * `@MainActor` means all of this runs on the main (UI) thread, so screens never
 * see half-changed values. The audio itself runs in C++ on its own thread.
 */
@MainActor
final class Metronome: ObservableObject {
    @Published private(set) var settings: MetronomeSettings
    @Published private(set) var playing = false
    /** Index of the beat being heard right now, -1 when stopped. */
    @Published private(set) var currentBeat = -1
    @Published private(set) var latencyMs: Double = 0

    /**
     * Called on the main thread when a new bar's "1" is heard (count-in bars
     * included). Practice uses it to queue the next tempo or subdivision.
     */
    var onBar: (() -> Void)?

    private var poller: Timer?
    private var lastSeq: Int64 = -1
    private var taps: [Int64] = []
    private var saveWork: DispatchWorkItem?

    init() {
        settings = (try? JSONDecoder().decode(MetronomeSettings.self, from: Data(contentsOf: AppFiles.settingsFile))) ?? MetronomeSettings()
        Samples.load()
        push()
    }

    // ── Editing ─────────────────────────────────────────────────────────────

    private func edit(_ change: (inout MetronomeSettings) -> Void) {
        change(&settings)
        push()
        scheduleSave()
    }

    /** Sends every setting to the C++ engine (cheap: just a few atomic writes). */
    private func push() {
        let s = settings
        mk_set_tempo(s.meter.beatsPerMinute(s.bpm))
        mk_set_last_beat_fraction(s.meter.lastBeatFraction)
        s.beats.map(\.rawValue).withUnsafeBufferPointer { mk_set_beats($0.baseAddress, Int32($0.count)) }
        mk_set_subdivision(Int32(s.subdivision.perBeat))
        mk_set_sound(Int32(s.sound.rawValue))
        mk_set_volume(s.volume)
    }

    /** Saves half a second after the last change, so dragging the slider doesn't write the file 60 times a second. */
    private func scheduleSave() {
        saveWork?.cancel()
        let s = settings
        let work = DispatchWorkItem {
            if let data = try? JSONEncoder().encode(s) { try? data.write(to: AppFiles.settingsFile, options: .atomic) }
        }
        saveWork = work
        DispatchQueue.global(qos: .utility).asyncAfter(deadline: .now() + 0.5, execute: work)
    }

    func setBpm(_ bpm: Int) { edit { $0.bpm = min(max(bpm, MetronomeSettings.minBpm), MetronomeSettings.maxBpm) } }
    func nudge(_ delta: Int) { setBpm(settings.bpm + delta) }

    /** Click a beat: one level up (normal → accent → strong → mute → normal). */
    func cycleBeat(_ index: Int) {
        guard settings.beats.indices.contains(index) else { return }
        edit { $0.beats[index] = .ofLevel(($0.beats[index].level + 1) % 4) }
    }

    /** Set the time signature; keeps your existing accents unless [pattern] is given. */
    func setTimeSignature(_ meter: Meter, pattern: [BeatState]? = nil) {
        edit { s in
            s.beats = pattern ?? (0..<meter.beatCount).map { i in i < s.beats.count ? s.beats[i] : (i == 0 ? .accent : .normal) }
            s.meter = meter
        }
    }

    func setSubdivision(_ sub: Subdivision) { edit { $0.subdivision = sub } }
    func setSound(_ sound: ClickSound) { edit { $0.sound = sound } }
    func setVolume(_ v: Float) { edit { $0.volume = v } }

    /** Load saved settings (a setlist item). Your volume stays as it is. */
    func apply(_ s: MetronomeSettings) {
        edit { cur in
            let vol = cur.volume
            cur = s
            cur.volume = vol
        }
    }

    /**
     * Timing check: how far a tap at [tapNs] was from the nearest click as you
     * heard it, in ms (− early, + late), or nil if no click is known.
     */
    func tapOffsetMs(_ tapNs: Int64) -> Double? {
        let d = mk_nearest_beat_offset(tapNs)
        return d == Int64.min ? nil : Double(d) / 1e6
    }

    /** Plays one hit so you can hear a sound before picking it. */
    func preview(_ sound: ClickSound) {
        if !playing { _ = mk_start(false) }
        mk_preview(Int32(sound.rawValue))
    }

    // ── Tap tempo ───────────────────────────────────────────────────────────

    /** Averages the last few tap intervals; a pause over 2 s starts fresh. */
    func tap() {
        let now = mk_now_ns()
        if let last = taps.last, now - last > 2_000_000_000 { taps.removeAll() }
        taps.append(now)
        if taps.count > 5 { taps.removeFirst() }
        if taps.count >= 2 {
            let avg = Double(taps.last! - taps.first!) / Double(taps.count - 1)
            setBpm(Int(60e9 / avg))
        }
    }

    // ── Play / stop ─────────────────────────────────────────────────────────

    func togglePlay() { playing ? stop() : start() }

    func start() {
        guard !playing else { return }
        push()
        guard mk_start(true) else { return }
        playing = true
        latencyMs = mk_output_latency_ms()
        lastSeq = -1
        startPoller()
    }

    func stop() {
        // Keep the output open but silent ("warm") so the next start is instant.
        _ = mk_start(false)
        poller?.invalidate()
        poller = nil
        currentBeat = -1
        playing = false
    }

    /**
     * LEARN — warm output: opening the audio device takes a few tens of ms. While
     * the window is in front we keep a silent output open so Start is instant.
     * When you switch away and nothing is playing, we close it to save power.
     */
    func setActive(_ active: Bool) {
        if playing { return }
        if active { _ = mk_start(false); latencyMs = mk_output_latency_ms() } else { mk_stop() }
    }

    /**
     * Checks 120 times a second (as often as the fastest Mac screens redraw)
     * which beat is coming out of the speakers right now — the engine knows
     * exactly when each beat reaches the output, so the highlight matches what
     * you hear. It only runs while playing; a stopped metronome uses no CPU.
     */
    private func startPoller() {
        poller?.invalidate()
        let t = Timer(timeInterval: 1.0 / 120.0, repeats: true) { [weak self] _ in
            // Reading the engine is a cheap C call; only touch the screen when the beat changes.
            let packed = mk_audible_beat(mk_now_ns(), 0)
            guard packed >= 0 else { return }
            let index = Int(packed & 0xF)
            let seq = packed >> 8
            Task { @MainActor in
                guard let self, self.playing else { return }
                if index != self.currentBeat { self.currentBeat = index }
                // A new beat that is a "1": tell whoever listens (Practice).
                if seq != self.lastSeq {
                    self.lastSeq = seq
                    if index == 0 { self.onBar?() }
                }
            }
        }
        // .common keeps it ticking while you drag a slider or hold a menu open.
        RunLoop.main.add(t, forMode: .common)
        poller = t
    }
}
