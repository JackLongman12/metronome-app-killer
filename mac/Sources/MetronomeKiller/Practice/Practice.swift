import AppKit
import ClickCore
import Combine
import Foundation

// The Practice page's plans and brain: a port of Plans.kt and PracticeController.kt.

/**
 * Speed trainer: start at [from], move [step] BPM toward [to] every [every] bars.
 * At the top it holds, starts over, or comes back down (a "pyramid").
 * If [to] is below [from], it slows down instead.
 */
struct TrainerPlan: Codable, Equatable {
    enum Top: Int, Codable, CaseIterable {
        case hold, restart, backDown
        var label: String { switch self { case .hold: "Hold"; case .restart: "Start over"; case .backDown: "Come back down" } }
    }

    var from = 90, to = 130, step = 4, every = 4
    var top = Top.hold

    /** How many different tempos the climb has (the last one is exactly [to]). */
    var steps: Int { let s = max(step, 1); return (abs(to - from) + s - 1) / s + 1 }

    /** Bars in one full run (up, or up and down for the pyramid). */
    var totalBars: Int { max(every, 1) * (top == .backDown ? 2 * steps - 1 : steps) }

    /** Tempo for bar [bar] (0 = the first bar). */
    func bpm(forBar bar: Int) -> Int {
        let dir = to >= from ? 1 : -1
        var idx = max(bar, 0) / max(every, 1)
        switch top {
        case .hold: idx = min(idx, steps - 1)
        case .restart: idx %= steps
        case .backDown: idx = idx < steps ? idx : max(0, 2 * (steps - 1) - idx) // up, then down, then hold
        }
        let bpm = from + dir * idx * max(step, 1)
        return dir > 0 ? min(bpm, to) : max(bpm, to)
    }
}

/** Gap click: [on] bars of click, then [off] silent ones, repeating. */
struct GapPlan: Codable, Equatable {
    var on = 2, off = 2
    var grow = false
    var randomMute = 0          // % of beats muted at random
    var checkTiming = false     // show the tap pad
}

/** Subdivision ladder: climb through clicks-per-beat [rungs], [every] bars each, at one tempo. */
struct LadderPlan: Codable, Equatable {
    var rungs = [1, 2, 3, 4]
    var every = 2
    var backDown = true

    /** The climb as one repeating cycle, e.g. 1 2 3 4 3 2 (then 1 again). */
    var cycle: [Int] {
        let r = rungs.isEmpty ? [1] : rungs
        return backDown && r.count > 2 ? r + r[1..<(r.count - 1)].reversed() : r
    }

    func sub(forBar bar: Int) -> Int { cycle[(max(bar, 0) / max(every, 1)) % cycle.count] }

    static func name(_ perBeat: Int) -> String {
        switch perBeat { case 1: "quarters"; case 2: "eighths"; case 3: "triplets"; default: "sixteenths" }
    }
}

/** Polyrhythm: [count] evenly spaced hits per bar against the beats (3 against 4…). */
struct PolyPlan: Codable, Equatable {
    var count = 3
    var mainOnlyOne = false
}

/** One timing-check tap: ms early (−) or late (+), and whether it fell in a silent bar. */
struct Tap: Equatable {
    let ms: Double
    let inGap: Bool
}

/** One finished practice session, for the history list. */
struct LogEntry: Codable, Equatable, Identifiable {
    let startedAt: Date
    let seconds: Int
    let tool: String
    let detail: String
    var id: Date { startedAt }
}

/**
 * Follows the gap pattern bar by bar, exactly like the C++ engine does
 * (ClickEngine.cpp, "Gap click"), so the screen can say "silent 2 of 3".
 */
struct GapCounter {
    let plan: GapPlan
    private(set) var barInRound: Int
    private(set) var round = 0

    init(_ plan: GapPlan, lead: Int) { self.plan = plan; barInRound = -1 - lead }

    var offNow: Int { min(8, plan.off + (plan.grow ? round : 0)) }
    var silent: Bool { barInRound >= plan.on }
    var countingIn: Bool { barInRound < 0 }

    mutating func nextBar() {
        barInRound += 1
        if barInRound >= plan.on + offNow { barInRound = 0; round += 1 }
    }
}

/**
 * The Practice page's brain. It runs on top of the normal metronome (same
 * meter, sound and accents) and only adds:
 *  - speed trainer: queues the next tempo in the engine, which switches exactly on the "1"
 *  - gap click: the engine silences whole bars (and random beats) itself, to the sample
 *  - timing check: compares your taps with when each click really came out of the speaker
 *
 * LEARN: anything that must land exactly on a beat is done inside the audio
 * engine. Swift hears about each beat a moment after it's played: fine for the
 * screen, too late to change the sound.
 */
@MainActor
final class Practice: ObservableObject {
    enum Tool: Int, Codable, CaseIterable, Identifiable {
        case speed, gaps, timing, ladder, poly
        var id: Int { rawValue }
        var label: String {
            switch self { case .speed: "Speed trainer"; case .gaps: "Gap click"; case .timing: "Timing check"; case .ladder: "Subdivision ladder"; case .poly: "Polyrhythm" }
        }
        var short: String {
            switch self { case .speed: "Speed"; case .gaps: "Gaps"; case .timing: "Timing"; case .ladder: "Ladder"; case .poly: "Poly" }
        }
    }

    /** Everything you set on the page, saved between launches. */
    struct Saved: Codable {
        var tool = Tool.speed
        var trainer = TrainerPlan()
        var gaps = GapPlan()
        var ladder = LadderPlan()
        var poly = PolyPlan()
        var goalMinutes = 0
        var countIn = 1
    }

    @Published private(set) var saved: Saved
    @Published private(set) var history: [LogEntry]
    /** The tool that's playing, or nil. */
    @Published private(set) var running: Tool?
    @Published private(set) var runStartedAt: Date?
    /** Bars since Start (0 = first bar; negative while counting in). */
    @Published private(set) var bar = -1
    /** While counting in: which count-in bar (1-based), else 0. */
    @Published private(set) var countingIn = 0
    /** Gap click: silent now?, which bar of that stretch, how long the stretch is. */
    @Published private(set) var gapState = (silent: false, pos: 0, len: 0)
    @Published private(set) var taps: [Tap] = []
    @Published private(set) var ladderSub = 1
    /** Polyrhythm: when this bar's "1" was heard (mk_now_ns), for the moving dots. */
    @Published private(set) var barStartNs: Int64 = 0

    static let maxTaps = 24
    static let minLogSeconds = 20

    private let metronome: Metronome
    private var gapCounter: GapCounter?
    private var lead = 0
    private var counted = -1
    private var topBpm = 0
    private var watch: AnyCancellable?

    private static var savedFile: URL { AppFiles.support.appendingPathComponent("practice.json") }
    private static var logFile: URL { AppFiles.support.appendingPathComponent("practice_log.json") }

    init(metronome: Metronome) {
        self.metronome = metronome
        saved = (try? JSONDecoder().decode(Saved.self, from: Data(contentsOf: Self.savedFile))) ?? Saved()
        history = (try? JSONDecoder().decode([LogEntry].self, from: Data(contentsOf: Self.logFile))) ?? []
        // If the metronome stops from anywhere (Space, the menu bar, MIDI), practice stops too.
        // LEARN: the check runs a moment later, so a stop that start() itself makes
        // (stop the old run, start the new one) is never mistaken for "you pressed Stop".
        watch = metronome.$playing.dropFirst().sink { [weak self] playing in
            guard !playing else { return }
            Task { @MainActor in
                guard let self, self.running != nil, !self.metronome.playing else { return }
                self.cleanUp()
            }
        }
    }

    // ── Settings ────────────────────────────────────────────────────────────

    private func change(_ f: (inout Saved) -> Void) {
        f(&saved)
        if let d = try? JSONEncoder().encode(saved) { try? d.write(to: Self.savedFile, options: .atomic) }
    }

    func setTool(_ t: Tool) {
        if let r = running, r != t { stop() }
        change { $0.tool = t }
    }

    func setTrainer(_ p: TrainerPlan) {
        var p = p
        p.from = p.from.clamped(MetronomeSettings.minBpm, MetronomeSettings.maxBpm)
        p.to = p.to.clamped(MetronomeSettings.minBpm, MetronomeSettings.maxBpm)
        p.step = p.step.clamped(1, 50)
        p.every = p.every.clamped(1, 64)
        change { $0.trainer = p }
    }

    func setGaps(_ p: GapPlan) {
        let old = saved.gaps
        var p = p
        p.on = p.on.clamped(1, 16); p.off = p.off.clamped(1, 8); p.randomMute = p.randomMute.clamped(0, 70)
        change { $0.gaps = p }
        // Restart the pattern with the new numbers (showing the tap pad doesn't need that).
        var o = old; o.checkTiming = p.checkTiming
        if running == .gaps && o != p { start(.gaps) }
    }

    func setLadder(_ p: LadderPlan) {
        var p = p
        p.every = p.every.clamped(1, 16)
        if p.rungs.isEmpty { return }
        change { $0.ladder = p }
    }

    func setPoly(_ p: PolyPlan) {
        var p = p
        p.count = p.count.clamped(2, 12)
        change { $0.poly = p }
        if running == .poly { mk_set_poly(Int32(p.count), polySound, p.mainOnlyOne) } // takes effect next bar
    }

    func setGoal(_ minutes: Int) { change { $0.goalMinutes = minutes } }
    func setCountIn(_ bars: Int) { change { $0.countIn = bars.clamped(0, 2) } }

    func clearHistory() {
        history = []
        try? FileManager.default.removeItem(at: Self.logFile)
    }

    /** A sound that stands out from the metronome's own: woodblock, or clave if you already use woodblock. */
    private var polySound: Int32 {
        Int32(metronome.settings.sound == .woodblock ? ClickSound.clave.rawValue : ClickSound.woodblock.rawValue)
    }

    // ── Start / stop ────────────────────────────────────────────────────────

    func toggle() { running != nil ? stop() : start(saved.tool) }

    func start(_ t: Tool) {
        if metronome.playing { metronome.stop() }
        cleanUp()
        running = t
        taps = []
        // LEARN: the count-in is just bars "before bar 0": the trainer holds its
        // first tempo and the engine's gap counter starts below zero.
        lead = (t == .speed || t == .gaps) ? saved.countIn : 0
        gapCounter = t == .gaps ? GapCounter(saved.gaps, lead: lead) : nil
        switch t {
        case .speed: metronome.setBpm(saved.trainer.bpm(forBar: 0))
        case .gaps:
            let g = saved.gaps
            mk_set_lead_bars(Int32(lead))
            mk_set_gap(Int32(g.on), Int32(g.off), g.grow ? 1 : 0)
            mk_set_random_mute(Int32(g.randomMute))
        case .timing: break
        case .ladder: mk_set_subdivision(Int32(saved.ladder.sub(forBar: 0)))
        case .poly: mk_set_poly(Int32(saved.poly.count), polySound, saved.poly.mainOnlyOne)
        }
        runStartedAt = Date()
        topBpm = metronome.settings.bpm
        bar = -1 - lead
        counted = -1
        countingIn = 0
        metronome.onBar = { [weak self] in self?.onBar() }
        metronome.start()
    }

    func stop() {
        metronome.stop()
        cleanUp()
    }

    /** Each new bar's "1", as heard. */
    private func onBar() {
        guard let t = running else { return }
        counted += 1
        let b = counted - lead             // negative while counting in
        bar = b
        countingIn = b < 0 ? counted + 1 : 0
        switch t {
        case .speed:
            let plan = saved.trainer
            let now = plan.bpm(forBar: max(b, 0))
            if metronome.settings.bpm != now { metronome.setBpm(now) } // the engine already switched; this updates the screen
            let next = plan.bpm(forBar: max(b + 1, 0))
            if next != now { mk_set_tempo_at_next_bar(metronome.settings.meter.beatsPerMinute(next)) }
            topBpm = max(topBpm, now)
        case .ladder:
            let plan = saved.ladder
            ladderSub = plan.sub(forBar: b)
            let next = plan.sub(forBar: b + 1)
            if next != ladderSub { mk_set_subdivision_at_next_bar(Int32(next)) }
        case .poly:
            barStartNs = mk_now_ns()
        default: break
        }
        if var c = gapCounter {
            c.nextBar()
            gapCounter = c
            if c.countingIn { gapState = (false, 0, 0); return }
            let len = c.silent ? c.offNow : saved.gaps.on
            let pos = c.silent ? c.barInRound - saved.gaps.on + 1 : c.barInRound + 1
            gapState = (c.silent, pos, len)
        }
    }

    private func cleanUp() {
        metronome.onBar = nil
        // Write the finished run to the history.
        if let ran = running, let started = runStartedAt {
            let secs = Int(Date().timeIntervalSince(started))
            if secs >= Self.minLogSeconds {
                history = Array((history + [LogEntry(startedAt: started, seconds: secs, tool: ran.label, detail: describe(ran))]).suffix(1000))
                if let d = try? JSONEncoder().encode(history) { try? d.write(to: Self.logFile, options: .atomic) }
            }
        }
        runStartedAt = nil
        // Put the metronome's own subdivision back (the ladder changed it) and switch the extras off.
        mk_set_subdivision_at_next_bar(-1)
        mk_set_subdivision(Int32(metronome.settings.subdivision.perBeat))
        mk_set_poly(0, 0, false)
        barStartNs = 0
        mk_set_gap(0, 0, 0)
        mk_set_lead_bars(0)
        mk_set_random_mute(0)
        mk_set_tempo_at_next_bar(-1)
        running = nil
        gapCounter = nil
        gapState = (false, 0, 0)
        countingIn = 0
    }

    /** One line for the history, e.g. "90 → 130 BPM, reached 118". */
    private func describe(_ t: Tool) -> String {
        let s = metronome.settings
        switch t {
        case .speed: return "\(saved.trainer.from) → \(saved.trainer.to) BPM, reached \(topBpm)"
        case .gaps:
            let g = saved.gaps
            let inGaps = Self.stats(taps.filter(\.inGap).map(\.ms)).map { ", gaps \(Self.fmtMs($0.mean))" } ?? ""
            return "\(g.on) on / \(g.off) silent at \(s.bpm) BPM" + inGaps
        case .timing:
            if let st = Self.stats(taps.map(\.ms)) { return "\(s.bpm) BPM, average \(Self.fmtMs(st.mean)), spread ±\(Int(st.sd)) ms" }
            return "\(s.bpm) BPM"
        case .ladder: return saved.ladder.rungs.map(LadderPlan.name).joined(separator: "→") + " at \(s.bpm) BPM"
        case .poly: return "\(saved.poly.count) against \(s.meter.beatCount) at \(s.bpm) BPM"
        }
    }

    // ── Timing check ────────────────────────────────────────────────────────

    var tapsWanted: Bool { running == .timing || (running == .gaps && saved.gaps.checkTiming) }

    /**
     * A tap at [tapNs] (when the key or mouse button went down). Taps more than
     * half a beat from any click are ignored: you were between beats.
     */
    func tap(_ tapNs: Int64) {
        guard tapsWanted, let d = metronome.tapOffsetMs(tapNs) else { return }
        let s = metronome.settings
        let beatMs = 60_000 / s.meter.beatsPerMinute(s.bpm)
        guard abs(d) <= beatMs / 2 else { return }
        taps = Array((taps + [Tap(ms: d, inGap: running == .gaps && gapState.silent)]).suffix(Self.maxTaps))
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    static func fmtMs(_ ms: Double) -> String { "\(ms >= 0 ? "+" : "")\(Int(ms)) ms" }

    /** Average and spread (standard deviation) of tap offsets, in ms. */
    static func stats(_ t: [Double]) -> (mean: Double, sd: Double)? {
        guard t.count >= 2 else { return nil }
        let mean = t.reduce(0, +) / Double(t.count)
        var sq = 0.0
        for x in t { sq += (x - mean) * (x - mean) }
        return (mean, (sq / Double(t.count - 1)).squareRoot())
    }

    /** Seconds practised since [since], plus the run going on now. */
    func seconds(since: Date) -> Int {
        var total = 0
        for e in history where e.startedAt >= since { total += e.seconds }
        if let r = runStartedAt { total += max(Int(Date().timeIntervalSince(r)), 0) }
        return total
    }
}

/**
 * The time a key or mouse press happened, on the engine's clock.
 * LEARN: NSEvent.timestamp is seconds since the Mac started, on the same clock
 * the audio uses. Using it (not "now") means a busy moment doesn't make you look late.
 */
@MainActor
func eventTimeNs(_ event: NSEvent? = nil) -> Int64 {
    let now = mk_now_ns()
    guard let e = event ?? NSApp.currentEvent else { return now }
    let t = Int64(e.timestamp * 1e9)
    return abs(t - now) < 1_000_000_000 ? t : now
}
