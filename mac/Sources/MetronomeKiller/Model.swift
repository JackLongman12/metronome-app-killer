import Foundation

// Shared vocabulary for the metronome, the same as the Android app's Types.kt.
//
// LEARN: an `enum` with `Int` raw values is a fixed list where each case has a
// number (0, 1, 2...). We send those numbers to the C++ engine, so the ORDER here
// must match the engine (BeatState in ClickEngine.h, buildAll() in ClickEngine.cpp).
// `Codable` lets Swift save and load the value as JSON automatically.

/** How loud a beat is. The screen shows it as 0–3 filled segments. */
enum BeatState: Int32, Codable, CaseIterable {
    case accent = 0, normal = 1, mute = 2, strong = 3

    var level: Int {
        switch self { case .mute: 0; case .normal: 1; case .accent: 2; case .strong: 3 }
    }
    var isAccent: Bool { self == .accent || self == .strong }

    static func ofLevel(_ level: Int) -> BeatState {
        allCases.first { $0.level == min(max(level, 0), 3) } ?? .normal
    }
}

/**
 * Click sounds. The first eight are made by maths in the engine; the rest are
 * recordings of a real drum kit. Order must match buildAll() in ClickEngine.cpp.
 */
enum ClickSound: Int, Codable, CaseIterable, Identifiable {
    case click, woodblock, beep, clave, cowbell, hihat, rimshot, kickHat
    case sideStick, realCowbell, rideBell, realHihat, realKickHat, realRimshot
    case realWoodblock, realClaves, agogo, logDrum, triangle, clap

    var id: Int { rawValue }
    var recorded: Bool { rawValue >= ClickSound.sideStick.rawValue }
    var label: String {
        switch self {
        case .click: "Click"
        case .woodblock: "Woodblock"
        case .beep: "Beep"
        case .clave: "Clave"
        case .cowbell: "Cowbell"
        case .hihat: "Hi-hat"
        case .rimshot: "Rimshot"
        case .kickHat: "Kick and hat"
        case .sideStick: "Side stick"
        case .realCowbell: "Real cowbell"
        case .rideBell: "Ride bell"
        case .realHihat: "Real hi-hat"
        case .realKickHat: "Real kick and hat"
        case .realRimshot: "Real rimshot"
        case .realWoodblock: "Real woodblock"
        case .realClaves: "Real claves"
        case .agogo: "Agogo bells"
        case .logDrum: "Log drum"
        case .triangle: "Triangle"
        case .clap: "Hand clap"
        }
    }
}

enum Subdivision: Int, Codable, CaseIterable, Identifiable {
    case quarters = 1, eighths = 2, triplets = 3, sixteenths = 4
    var id: Int { rawValue }
    var perBeat: Int { rawValue }
    var label: String {
        switch self { case .quarters: "Quarters"; case .eighths: "Eighths"; case .triplets: "Triplets"; case .sixteenths: "Sixteenths" }
    }
}

/**
 * A time signature, allowing the experimental values the Android app supports:
 * decimal top numbers (3.5/4: the last beat is half length) and "irrational"
 * bottom numbers (4/3: each beat lasts 4 ÷ 3 quarter notes).
 *
 * LEARN — what BPM means: for normal bottom numbers BPM counts the bottom note
 * (6/8 at 120 = 120 eighth notes a minute). For irrational ones it counts quarter
 * notes, or 4/3 would sound exactly like 4/4.
 */
struct Meter: Codable, Equatable, Hashable {
    var numerator: Double
    var denominator: Double

    static let maxBeats = 16
    static let standardDenominators: Set<Double> = [1, 2, 4, 8, 16, 32]

    var beatCount: Int { min(max(Int((numerator - 1e-9).rounded(.up)), 1), Meter.maxBeats) }

    /** Length of the last beat: 0.5 for 3.5/4, 1.0 when the numerator is whole. */
    var lastBeatFraction: Double {
        let f = numerator - numerator.rounded(.down)
        return f < 1e-6 ? 1.0 : f
    }

    var isIrrational: Bool { !Meter.standardDenominators.contains(denominator) }

    /** The rate the engine actually clicks beats at, for a displayed [bpm]. */
    func beatsPerMinute(_ bpm: Int) -> Double { isIrrational ? Double(bpm) * denominator / 4.0 : Double(bpm) }

    var label: String { "\(Meter.fmt(numerator))/\(Meter.fmt(denominator))" }

    static func fmt(_ d: Double) -> String {
        if abs(d - d.rounded()) < 1e-9 { return String(Int(d.rounded())) }
        var s = String(format: "%.3f", d)
        while s.hasSuffix("0") { s.removeLast() }
        if s.hasSuffix(".") { s.removeLast() }
        return s
    }
}

/** A ready-made time signature with accents on each group's first beat (3+2+2 → beats 1, 4, 6). */
struct TimeSignaturePreset: Hashable, Identifiable {
    let meter: Meter
    let groups: [Int]?

    init(_ n: Double, _ d: Double, _ groups: [Int]? = nil) {
        meter = Meter(numerator: n, denominator: d)
        self.groups = groups
    }

    var id: String { label + "|" + meter.label }

    var label: String {
        if let g = groups, g.count > 1 { return g.map(String.init).joined(separator: "+") + "/" + Meter.fmt(meter.denominator) }
        return meter.label
    }

    func pattern() -> [BeatState] {
        var accents: Set<Int> = [0]
        if let g = groups {
            var at = 0
            for size in g.dropLast() { at += size; accents.insert(at) }
        }
        return (0..<meter.beatCount).map { accents.contains($0) ? .accent : .normal }
    }

    static let common = [
        TimeSignaturePreset(2, 4), TimeSignaturePreset(3, 4), TimeSignaturePreset(4, 4), TimeSignaturePreset(5, 4),
        TimeSignaturePreset(6, 8, [3, 3]), TimeSignaturePreset(7, 8, [2, 2, 3]),
        TimeSignaturePreset(9, 8, [3, 3, 3]), TimeSignaturePreset(12, 8, [3, 3, 3, 3]),
    ]
    static let additive = [
        TimeSignaturePreset(5, 8, [2, 3]), TimeSignaturePreset(5, 8, [3, 2]),
        TimeSignaturePreset(7, 8, [3, 2, 2]), TimeSignaturePreset(7, 8, [2, 2, 3]),
        TimeSignaturePreset(8, 8, [3, 3, 2]), TimeSignaturePreset(9, 8, [2, 2, 2, 3]),
        TimeSignaturePreset(10, 8, [3, 3, 2, 2]), TimeSignaturePreset(11, 8, [3, 3, 3, 2]),
        TimeSignaturePreset(13, 8, [3, 3, 3, 2, 2]), TimeSignaturePreset(15, 16, [4, 4, 4, 3]),
        TimeSignaturePreset(7, 16, [2, 2, 3]), TimeSignaturePreset(11, 16, [3, 3, 3, 2]),
    ]
    static let decimal = [
        TimeSignaturePreset(2.5, 4), TimeSignaturePreset(3.5, 4), TimeSignaturePreset(4.5, 4),
        TimeSignaturePreset(5.5, 4), TimeSignaturePreset(3.25, 4), TimeSignaturePreset(3.75, 4),
        TimeSignaturePreset(6.5, 8), TimeSignaturePreset(7.5, 8),
    ]
    static let irrational = [
        TimeSignaturePreset(4, 3), TimeSignaturePreset(3, 3), TimeSignaturePreset(2, 3), TimeSignaturePreset(5, 6),
        TimeSignaturePreset(3, 5), TimeSignaturePreset(4, 5), TimeSignaturePreset(7, 12), TimeSignaturePreset(5, 12),
        TimeSignaturePreset(4, 7), TimeSignaturePreset(3, 10), TimeSignaturePreset(4, 3.2), TimeSignaturePreset(5, 4.5),
        TimeSignaturePreset(3, 2.5), TimeSignaturePreset(4, 6.4),
    ]
}

extension Comparable {
    /** The value kept between lo and hi: 5.clamped(1, 3) is 3. */
    func clamped(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
}

/** Everything that defines what the metronome plays. Saved between launches. */
struct MetronomeSettings: Codable, Equatable {
    var bpm = 120
    var meter = Meter(numerator: 4, denominator: 4)
    var beats: [BeatState] = TimeSignaturePreset.common[2].pattern()
    var subdivision = Subdivision.quarters
    var sound = ClickSound.click
    var volume: Float = 1.0

    static let minBpm = 20
    static let maxBpm = 500

    static func tempoName(_ bpm: Int) -> String {
        switch bpm {
        case ..<40: "Grave"
        case ..<60: "Largo"
        case ..<76: "Adagio"
        case ..<108: "Andante"
        case ..<120: "Moderato"
        case ..<156: "Allegro"
        case ..<176: "Vivace"
        case ..<200: "Presto"
        default: "Prestissimo"
        }
    }
}
