import Foundation

// A song's tempo map, ported line by line from the Android app's TempoMap.kt.
// The JSON format is the same, so a map made on one device reads on the other.

/** One stretch of a song with a steady (or smoothly changing) tempo. */
struct Section: Codable, Equatable {
    enum Kind: String, Codable { case STEADY, RAMP, FREE }

    var start: Double
    var end: Double
    var kind: Kind
    var bpm: Double          // 0 for free time
    var bpmEnd: Double
    var beatsPerBar: Int
    var beats: Int
    var firstDownbeat: Double
    var uncertain: Bool
    /** Bottom number of the time signature (4 from the AI; tabs can say 8, 16...). BPM counts this note. */
    var noteValue: Int = 4

    var meter: String {
        if kind == .FREE { return "free" }
        if beatsPerBar == 0 { return "mixed" }
        return "\(beatsPerBar)/\(noteValue)"
    }

    enum CodingKeys: String, CodingKey {
        case start, end, kind, bpm, beats, uncertain
        case bpmEnd = "bpm_end", beatsPerBar = "beats_per_bar", firstDownbeat = "first_downbeat", noteValue = "note_value"
    }

    init(_ start: Double, _ end: Double, _ kind: Kind, _ bpm: Double, _ bpmEnd: Double, _ beatsPerBar: Int, _ beats: Int,
         _ firstDownbeat: Double, _ uncertain: Bool, _ noteValue: Int = 4) {
        self.start = start; self.end = end; self.kind = kind; self.bpm = bpm; self.bpmEnd = bpmEnd
        self.beatsPerBar = beatsPerBar; self.beats = beats; self.firstDownbeat = firstDownbeat
        self.uncertain = uncertain; self.noteValue = noteValue
    }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        self.init(try c.decode(Double.self, forKey: .start), try c.decode(Double.self, forKey: .end),
                  try c.decode(Kind.self, forKey: .kind), try c.decode(Double.self, forKey: .bpm),
                  try c.decode(Double.self, forKey: .bpmEnd), try c.decode(Int.self, forKey: .beatsPerBar),
                  try c.decode(Int.self, forKey: .beats), try c.decode(Double.self, forKey: .firstDownbeat),
                  try c.decode(Bool.self, forKey: .uncertain), try c.decodeIfPresent(Int.self, forKey: .noteValue) ?? 4)
    }
}

/**
 * One bar: where it starts and how many beats it has (songs can change every bar).
 * [marker] is a part name starting at this bar ("Verse 1"), from a Songsterr tab.
 */
struct Bar: Codable, Equatable {
    var start: Double
    var beats: Int
    var noteValue: Int = 4
    var marker: String? = nil

    var meter: String { "\(beats)/\(noteValue)" }

    enum CodingKeys: String, CodingKey { case start, beats, marker, noteValue = "note_value" }

    init(_ start: Double, _ beats: Int, _ noteValue: Int = 4, marker: String? = nil) {
        self.start = start; self.beats = beats; self.noteValue = noteValue; self.marker = marker
    }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        start = try c.decode(Double.self, forKey: .start)
        beats = try c.decode(Int.self, forKey: .beats)
        noteValue = try c.decodeIfPresent(Int.self, forKey: .noteValue) ?? 4
        marker = (try c.decodeIfPresent(String.self, forKey: .marker)).flatMap { $0.isEmpty ? nil : $0 }
    }
}

struct TempoMap: Codable, Equatable {
    var version = 1
    var duration: Double
    var mainBpm: Double
    var beats: [Double]
    var downbeats: [Double]
    var bars: [Bar]
    var sections: [Section]

    enum CodingKeys: String, CodingKey { case version, duration, beats, downbeats, bars, sections, mainBpm = "main_bpm" }

    init(_ duration: Double, _ mainBpm: Double, _ beats: [Double], _ downbeats: [Double], _ bars: [Bar], _ sections: [Section]) {
        self.duration = duration; self.mainBpm = mainBpm; self.beats = beats; self.downbeats = downbeats
        self.bars = bars; self.sections = sections
    }

    init(from d: Decoder) throws {
        let c = try d.container(keyedBy: CodingKeys.self)
        version = try c.decodeIfPresent(Int.self, forKey: .version) ?? 1
        duration = try c.decode(Double.self, forKey: .duration)
        mainBpm = try c.decode(Double.self, forKey: .mainBpm)
        beats = try c.decode([Double].self, forKey: .beats)
        downbeats = try c.decode([Double].self, forKey: .downbeats)
        bars = try c.decode([Bar].self, forKey: .bars)
        sections = try c.decode([Section].self, forKey: .sections)
    }

    func save(to url: URL) throws { try JSONEncoder().encode(self).write(to: url, options: .atomic) }
    static func load(_ url: URL) -> TempoMap? { (try? Data(contentsOf: url)).flatMap { try? JSONDecoder().decode(TempoMap.self, from: $0) } }
}

// ── Small helpers used by the ports ─────────────────────────────────────────

func median(_ a: [Double], _ from: Int, _ to: Int) -> Double {
    if to <= from { return 0 }
    let s = a[from..<to].sorted()
    let m = s.count / 2
    return s.count % 2 == 1 ? s[m] : (s[m - 1] + s[m]) / 2
}

func round1(_ x: Double) -> Double { (x * 10).rounded() / 10 }

extension Comparable {
    func clamped(_ lo: Self, _ hi: Self) -> Self { min(max(self, lo), hi) }
}

/**
 * Beats → sections. A port of TempoMapBuilder.kt (itself a port of
 * tools/beat-lab/tempo_map.py; see there for the reasoning behind each step):
 *  1. repair missed/extra beats   2. find tempo changes (dynamic programming)
 *  3. fix half/double-time sections   4. fold short "blips" into neighbours
 *  5. merge equal tempos, detect ramps   6. split where the bar length changes
 *  7. bar length per section   8. free time where beats stop
 */
enum TempoMapBuilder {
    private static let PENALTY = 0.004
    private static let MIN_SEG = 8
    private static let GAP_FACTOR = 2.5
    private static let RAMP_STEP = 0.07
    private static let OCTAVE_TOL = 0.05
    private static let TRUST_BEATS = 16
    private static let FPS = 50

    private final class Seg {
        var start: Double, end: Double, kind: Section.Kind, bpm: Double, t: [Double], bpmEnd: Double?, uncertain: Bool
        init(_ start: Double, _ end: Double, _ kind: Section.Kind, _ bpm: Double, _ t: [Double], _ bpmEnd: Double? = nil, _ uncertain: Bool = false) {
            self.start = start; self.end = end; self.kind = kind; self.bpm = bpm; self.t = t; self.bpmEnd = bpmEnd; self.uncertain = uncertain
        }
    }

    static func build(rawBeats: [Double], downbeats: [Double], duration: Double, dbAct: [Float]?) -> TempoMap {
        let beats = regularize(rawBeats)
        let mainBpm = beats.count > 8 ? dominantBpm(beats) : 0

        var runs: [(Int, Int)] = []
        var start = 0
        if beats.count > 2 {
            let ibi: [Double] = (0..<(beats.count - 1)).map { (k: Int) -> Double in beats[k + 1] - beats[k] }
            for i in ibi.indices {
                let local = median(ibi, max(0, i - 4), min(ibi.count, i + 5))
                if ibi[i] > GAP_FACTOR * local { runs.append((start, i + 1)); start = i + 1 }
            }
        }
        runs.append((start, beats.count))

        var segs: [Seg] = []
        for (ra, rb) in runs {
            let t = Array(beats[ra..<rb])
            if t.count < MIN_SEG { continue }
            for (a, b) in segment(t) {
                let s = Array(t[a..<b])
                segs.append(Seg(s.first!, s.last! + (s.last! - s[s.count - 2]), .STEADY, fitBpm(s), s))
            }
        }
        fixOctaves(segs, mainBpm)
        segs = absorbBlips(segs)
        segs = merge(segs)
        segs = splitMeters(segs, dbAct)

        var secs = segs.map { s -> Section in
            let (n, phase) = barLength(s.t, dbAct)
            return Section(s.start, s.end, s.kind, round1(s.bpm), round1(s.bpmEnd ?? s.bpm), n, s.t.count,
                           s.t[min(phase, s.t.count - 1)], s.uncertain)
        }
        secs = mergeSame(secs)

        var final: [Section] = []
        var cursor = 0.0
        for s in secs {
            if s.start - cursor > 1.0 { final.append(free(cursor, s.start)) }
            final.append(s)
            cursor = s.end
        }
        if duration - cursor > 1.0 { final.append(free(cursor, duration)) }
        return TempoMap(duration, round1(mainBpm), beats, downbeats, bars(beats, downbeats), final)
    }

    // 1 ──────────────────────────────────────────────────────────────────────
    private static func regularize(_ beats: [Double]) -> [Double] {
        if beats.count < 8 { return beats }
        let ibi: [Double] = (0..<(beats.count - 1)).map { (k: Int) -> Double in beats[k + 1] - beats[k] }
        var out = [beats[0]]
        for i in ibi.indices {
            let r = ibi[i] / median(ibi, max(0, i - 8), min(ibi.count, i + 9))
            if r > 1.7 && r < 3.3 {
                let k = Int(r.rounded())
                for j in 1..<k { out.append(beats[i] + ibi[i] * Double(j) / Double(k)) }
            } else if r < 0.6 && i + 1 < ibi.count {
                continue
            }
            out.append(beats[i + 1])
        }
        return out
    }

    private static func dominantBpm(_ beats: [Double]) -> Double {
        var bpm: [Double] = []
        for i in 0..<(beats.count - 1) {
            let b: Double = 60.0 / (beats[i + 1] - beats[i])
            if b > 40 && b < 320 { bpm.append(b) }
        }
        if bpm.isEmpty { return 0 }
        var hist = [Int](repeating: 0, count: 280)
        for b in bpm { hist[Int(b - 40).clamped(0, 279)] += 1 }
        let k = hist.indices.max { hist[$0] < hist[$1] }!
        let lo = 40.0 + Double(k) - 2
        let hi = 40.0 + Double(k) + 1 + 2
        let near = bpm.filter { $0 >= lo && $0 <= hi }
        return median(near, 0, near.count)
    }

    // 2 ──────────────────────────────────────────────────────────────────────
    /** Best split into constant-tempo pieces: each piece should be a straight line of beat times. */
    private static func segment(_ t: [Double]) -> [(Int, Int)] {
        let n = t.count
        if n < 2 * MIN_SEG { return [(0, n)] }
        var sx = [Double](repeating: 0, count: n + 1), sy = sx, sxx = sx, sxy = sx, syy = sx
        for i in 0..<n {
            let x = Double(i), y = t[i]
            sx[i + 1] = sx[i] + x; sy[i + 1] = sy[i] + y; sxx[i + 1] = sxx[i] + x * x
            sxy[i + 1] = sxy[i] + x * y; syy[i + 1] = syy[i] + y * y
        }
        func sse(_ a: Int, _ b: Int) -> Double {
            let m = Double(b - a)
            let X = sx[b] - sx[a], Y = sy[b] - sy[a]
            let vx = sxx[b] - sxx[a] - X * X / m
            let cov = sxy[b] - sxy[a] - X * Y / m
            let vy = syy[b] - syy[a] - Y * Y / m
            return max(vy - cov * cov / max(vx, 1e-12), 0)
        }
        var best = [Double](repeating: .infinity, count: n + 1)
        best[0] = 0
        var prev = [Int](repeating: 0, count: n + 1)
        for b in MIN_SEG...n {
            var bc = Double.infinity, ba = 0
            for a in 0...(b - MIN_SEG) {
                if best[a].isInfinite { continue }
                let c = best[a] + sse(a, b) + PENALTY
                if c < bc { bc = c; ba = a }
            }
            best[b] = bc; prev[b] = ba
        }
        var cuts: [(Int, Int)] = []
        var b = n
        while b > 0 { let a = prev[b]; cuts.append((a, b)); b = a }
        return cuts.reversed()
    }

    private static func fitBpm(_ t: [Double]) -> Double {
        let n = t.count
        if n < 2 { return 0 }
        let mx = Double(n - 1) / 2
        let my = t.reduce(0, +) / Double(n)
        var num = 0.0, den = 0.0
        for i in 0..<n { let d = Double(i) - mx; num += d * (t[i] - my); den += d * d }
        return 60 / (num / den)
    }

    // 3 ──────────────────────────────────────────────────────────────────────
    private static func fixOctaves(_ secs: [Seg], _ mainBpm: Double) {
        if secs.count < 2 { return }
        let w: [Double] = secs.map { (x: Seg) -> Double in Double(x.t.count) * 60.0 / x.bpm }
        let total = w.reduce(0, +)
        for (i, s) in secs.enumerated() {
            let neigh = [i > 0 ? secs[i - 1].bpm : nil, i + 1 < secs.count ? secs[i + 1].bpm : nil].compactMap { $0 }
            let ref = mainBpm > 0 ? mainBpm : neigh.reduce(0, +) / Double(max(neigh.count, 1))
            for f in [2.0, 0.5] {
                if abs(s.bpm * f - ref) / ref < OCTAVE_TOL && w[i] < total * 0.5 {
                    if f == 2.0 {
                        var mids: [Double] = []
                        for j in 0..<(s.t.count - 1) { mids.append((s.t[j] + s.t[j + 1]) / 2.0) }
                        s.t = (s.t + mids).sorted()
                    } else {
                        s.t = (0..<(s.t.count + 1) / 2).map { s.t[$0 * 2] }
                    }
                    s.bpm *= f
                    break
                }
            }
        }
    }

    // 4 ──────────────────────────────────────────────────────────────────────
    private static func absorbBlips(_ secs: [Seg]) -> [Seg] {
        var out: [Seg] = []
        for (i, s) in secs.enumerated() {
            let neigh = [i > 0 ? secs[i - 1].bpm : nil, i + 1 < secs.count ? secs[i + 1].bpm : nil].compactMap { $0 }
            let short = s.t.count < TRUST_BEATS
            let far = !neigh.isEmpty && neigh.allSatisfy { abs(s.bpm - $0) / $0 > 0.10 }
            if let last = out.last, short && far {
                last.end = s.end
                last.uncertain = true
            } else { out.append(s) }
        }
        return out
    }

    // 5 ──────────────────────────────────────────────────────────────────────
    private static func merge(_ secs: [Seg]) -> [Seg] {
        var merged: [Seg] = []
        for s in secs {
            if let p = merged.last {
                let pe = p.bpmEnd ?? p.bpm
                let same = abs(s.bpm - pe) / pe < 0.02
                let step = abs(s.bpm - pe) / pe < RAMP_STEP
                let direction = (s.bpm - pe).signum
                let pDir = p.kind == .RAMP ? (pe - p.bpm).signum : direction
                if same && p.kind == .STEADY {
                    p.t += s.t; p.bpm = fitBpm(p.t); p.end = s.end
                    continue
                }
                if step && !same && direction == pDir {
                    if p.kind == .STEADY { p.kind = .RAMP }
                    p.t += s.t; p.bpmEnd = s.bpm; p.end = s.end
                    continue
                }
            }
            merged.append(s)
        }
        for s in merged {
            if s.kind == .RAMP, let e = s.bpmEnd, abs(e - s.bpm) / s.bpm < 0.04 {
                s.kind = .STEADY
                s.bpm = fitBpm(s.t); s.bpmEnd = s.bpm
            }
        }
        return merged
    }

    // 6 ──────────────────────────────────────────────────────────────────────
    private static func strength(_ t: [Double], _ act: [Float]) -> [Double] {
        t.map { x in
            let idx = Int((x * Double(FPS)).rounded()).clamped(0, act.count - 1)
            var m = -Float.infinity
            for d in -1...1 { m = max(m, act[(idx + d).clamped(0, act.count - 1)]) }
            return 1 / (1 + exp(-Double(m)))
        }
    }

    /** Best (bar length, first downbeat) for a run of beat strengths. */
    private static func bestBar(_ w: [Double], _ from: Int, _ len: Int, _ maxN: Int) -> (Int, Int) {
        var best = -Double.infinity, bn = 4, bp = 0
        for n in 2...maxN {
            if len < 2 * n && maxN == 16 { break }
            for p in 0..<n {
                var on = 0.0, onN = 0, off = 0.0, offN = 0
                for k in 0..<len {
                    if k >= p && (k - p) % n == 0 { on += w[from + k]; onN += 1 } else { off += w[from + k]; offN += 1 }
                }
                if onN == 0 || offN == 0 { continue }
                let sc = on / Double(onN) - off / Double(offN) + ((n == 3 || n == 4) ? 0.02 : 0) - 0.002 * Double(n)
                if sc > best { best = sc; bn = n; bp = p }
            }
        }
        return (bn, bp)
    }

    private static func splitMeters(_ secs: [Seg], _ act: [Float]?, window: Int = 28) -> [Seg] {
        guard let act else { return secs }
        var out: [Seg] = []
        for s in secs {
            let t = s.t
            if t.count < 2 * window { out.append(s); continue }
            let st = strength(t, act)
            var labels = [Int](repeating: 0, count: t.count)
            for k in 0...(t.count - window) { labels[k + window / 2] = bestBar(st, k, window, 12).0 }
            for i in 0..<window / 2 { labels[i] = labels[window / 2] }
            let tail = t.count - window + window / 2
            if tail + 1 < t.count { for i in (tail + 1)..<t.count { labels[i] = labels[tail] } }
            let sm = (0..<t.count).map { i -> Int in
                var counts: [Int: Int] = [:]
                for j in max(0, i - window / 2)..<min(t.count, i + window / 2) { counts[labels[j], default: 0] += 1 }
                return counts.sorted { $0.value != $1.value ? $0.value > $1.value : $0.key < $1.key }.first!.key
            }
            var cuts = [0]
            for i in 1..<t.count where sm[i] != sm[cuts.last!] {
                var j = i
                while j < t.count && sm[j] == sm[i] { j += 1 }
                if j - i >= 2 * sm[i] { cuts.append(i) }
            }
            if cuts.count == 1 { out.append(s); continue }
            cuts.append(t.count)
            for c in 0..<cuts.count - 1 {
                let a = cuts[c], b = cuts[c + 1]
                let pt = Array(t[a..<b])
                out.append(Seg(t[a], b < t.count ? t[b] : s.end, s.kind, s.kind == .STEADY ? fitBpm(pt) : s.bpm, pt, s.bpmEnd, s.uncertain))
            }
        }
        return out
    }

    // 7 ──────────────────────────────────────────────────────────────────────
    private static func barLength(_ t: [Double], _ act: [Float]?) -> (Int, Int) {
        guard let act, t.count >= 8 else { return (4, 0) }
        return bestBar(strength(t, act), 0, t.count, 16)
    }

    private static func mergeSame(_ secs: [Section]) -> [Section] {
        var merged: [Section] = []
        for s in secs {
            if let p = merged.last, p.kind == .STEADY, s.kind == .STEADY, p.beatsPerBar == s.beatsPerBar, abs(p.bpm - s.bpm) / p.bpm < 0.02 {
                let w = p.beats + s.beats
                let bpm = round1((p.bpm * Double(p.beats) + s.bpm * Double(s.beats)) / Double(w))
                var q = p
                q.end = s.end; q.bpm = bpm; q.bpmEnd = bpm; q.beats = w; q.uncertain = p.uncertain || s.uncertain
                merged[merged.count - 1] = q
            } else { merged.append(s) }
        }
        return merged
    }

    private static func bars(_ beats: [Double], _ downbeats: [Double]) -> [Bar] {
        if downbeats.count < 2 { return [] }
        return (0..<downbeats.count - 1).compactMap { i in
            let a = downbeats[i], b = downbeats[i + 1]
            let n = beats.filter { $0 >= a - 0.07 && $0 < b - 0.07 }.count
            return n >= 1 ? Bar(a, n) : nil
        }
    }

    private static func free(_ start: Double, _ end: Double) -> Section { Section(start, end, .FREE, 0, 0, 0, 0, start, false) }
}

private extension Double {
    var signum: Double { self > 0 ? 1 : (self < 0 ? -1 : 0) }
}
