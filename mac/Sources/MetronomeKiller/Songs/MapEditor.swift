import Foundation

/**
 * Hand fixes for a tempo map (Edit mode on the song page). A port of MapEditor.kt.
 *
 * Every fix works on a stretch of bars [first, end) and follows one rule: the
 * beats you hear stay locked to the music. We only add beats in between (×2),
 * drop every other one (½), or regroup them into bars differently (shift the
 * "1", meter). Afterwards the parts are worked out again from the bars.
 *
 * LEARN: each function returns a NEW TempoMap instead of changing the old one.
 * That makes Undo trivial: keep a list of the earlier maps.
 */
enum MapEditor {
    /** ½: the AI heard eighth notes as beats. Keep every other beat, same meter. */
    static func halfTempo(_ map: TempoMap, _ first: Int, _ end: Int) -> TempoMap {
        edit(map, first, end) { beats, bars in
            regroup(beats.enumerated().filter { $0.offset % 2 == 0 }.map(\.element), usual(bars), 0, bars[0].noteValue)
        }
    }

    /** ×2: the AI heard half notes as beats. Add a beat between each pair, same meter. */
    static func doubleTempo(_ map: TempoMap, _ first: Int, _ end: Int) -> TempoMap {
        edit(map, first, end) { beats, bars in
            let stop = stretchEnd(map, end, beats)
            var out: [Double] = []
            for i in beats.indices {
                let next = i + 1 < beats.count ? beats[i + 1] : stop
                out.append(beats[i]); out.append((beats[i] + next) / 2)
            }
            return regroup(out, usual(bars), 0, bars[0].noteValue)
        }
    }

    /** Move the "1" by one beat (+1 later, -1 earlier). The first bar becomes a short pickup bar. */
    static func shiftDownbeat(_ map: TempoMap, _ first: Int, _ end: Int, by: Int) -> TempoMap {
        edit(map, first, end) { beats, bars in
            let n = usual(bars)
            let pickup = bars.count > 1 && bars[0].beats < n ? bars[0].beats : 0
            return regroup(beats, n, ((pickup + by) % n + n) % n, bars[0].noteValue)
        }
    }

    /** Regroup the stretch into bars of [beats]/[noteValue] (e.g. 7/8). Beats don't move. */
    static func setMeter(_ map: TempoMap, _ first: Int, _ end: Int, beats: Int, noteValue: Int) -> TempoMap {
        edit(map, first, end) { b, _ in regroup(b, beats, 0, noteValue) }
    }

    // ── plumbing ────────────────────────────────────────────────────────────

    private struct NewBar { var bar: Bar; var beats: [Double] }

    private static func usual(_ bars: [Bar]) -> Int {
        Dictionary(grouping: bars, by: \.beats).max { $0.value.count < $1.value.count }!.key
    }

    private static func stretchEnd(_ map: TempoMap, _ end: Int, _ beats: [Double]) -> Double {
        if end < map.bars.count { return map.bars[end].start }
        let last = beats.last!
        return last + (beats.count > 1 ? last - beats[beats.count - 2] : 0.5)
    }

    private static func edit(_ map: TempoMap, _ first: Int, _ end: Int, _ fix: ([Double], [Bar]) -> [NewBar]) -> TempoMap {
        let a = first.clamped(0, map.bars.count)
        let b = end.clamped(a, map.bars.count)
        if a == b { return map }
        let t0 = map.bars[a].start
        let t1 = b < map.bars.count ? map.bars[b].start : Double.greatestFiniteMagnitude
        let inside = map.beats.filter { $0 >= t0 - 1e-6 && $0 < t1 - 1e-6 }
        if inside.isEmpty { return map }
        let oldBars = Array(map.bars[a..<b])
        let made = fix(inside, oldBars)
        // Part names ("Chorus") move to the first new bar at or after where they were.
        var names: [Int: String] = [:]
        for ob in oldBars {
            guard let m = ob.marker else { continue }
            let k = made.firstIndex { $0.bar.start >= ob.start - 1e-6 } ?? (made.count - 1)
            if names[k] == nil { names[k] = m }
        }
        let newBars = made.enumerated().map { k, nb -> Bar in var bar = nb.bar; bar.marker = names[k]; return bar }
        let beats = map.beats.filter { $0 < t0 - 1e-6 } + made.flatMap(\.beats) + map.beats.filter { $0 >= t1 - 1e-6 }
        let bars = Array(map.bars[0..<a]) + newBars + Array(map.bars[b...])
        return rebuild(map.duration, beats, bars)
    }

    private static func regroup(_ beats: [Double], _ n: Int, _ phase: Int, _ noteValue: Int) -> [NewBar] {
        var out: [NewBar] = []
        var i = 0
        var len = phase > 0 ? phase : n
        while i < beats.count {
            let chunk = Array(beats[i..<min(beats.count, i + len)])
            out.append(NewBar(bar: Bar(chunk[0], chunk.count, noteValue), beats: chunk))
            i += len; len = n
        }
        return out
    }

    /** Sections from bars: consecutive bars with the same meter and a steady beat (within 4 %) form one part. */
    static func rebuild(_ duration: Double, _ beats: [Double], _ bars: [Bar]) -> TempoMap {
        var sections: [Section] = []
        let lastIbi = beats.count > 1 ? beats[beats.count - 1] - beats[beats.count - 2] : 0.5
        func barEnd(_ i: Int) -> Double { i + 1 < bars.count ? bars[i + 1].start : (beats.last ?? 0) + lastIbi }
        func ibi(_ i: Int) -> Double { (barEnd(i) - bars[i].start) / Double(bars[i].beats) }
        var a = 0
        while a < bars.count {
            var b = a
            var ref = [ibi(a)]
            while b + 1 < bars.count && bars[b + 1].meter == bars[a].meter && abs(ibi(b + 1) / ref.sorted()[ref.count / 2] - 1) < 0.04 {
                b += 1; ref.append(ibi(b))
            }
            let s = bars[a].start, e = barEnd(b)
            let nBeats = (a...b).reduce(0) { $0 + bars[$1].beats }
            let bpm = round1(Double(nBeats) * 60 / (e - s))
            sections.append(Section(s, e, .STEADY, bpm, bpm, bars[a].beats, nBeats, s, false, bars[a].noteValue))
            a = b + 1
        }
        if let f = sections.first, f.start > 1.0 { sections.insert(Section(0, f.start, .FREE, 0, 0, 0, 0, 0, false), at: 0) }
        if let l = sections.last, duration - l.end > 1.0 { sections.append(Section(l.end, duration, .FREE, 0, 0, 0, 0, l.end, false)) }
        let main = sections.filter { $0.kind == .STEADY }.max { ($0.end - $0.start) < ($1.end - $1.start) }?.bpm ?? 0
        return TempoMap(duration, main, beats, bars.map(\.start), bars, sections)
    }
}

/** Where a row's name came from: the Songsterr tab, or you. */
enum NameSource: String, Codable { case tab, you }

/** One row of the "part rows" view: bars [first] until [end] (exclusive), 0-based. */
struct PartRow: Identifiable, Equatable {
    let first: Int, end: Int
    let name: String?
    let source: NameSource?
    var continued = false

    var id: Int { first }
    var size: Int { end - first }
    var range: String { size == 1 ? "Bar \(first + 1)" : "Bars \(first + 1)–\(end)" }
}

/**
 * Cuts a song into rows, like a chord chart (a port of PartRows.kt). Rows break
 * at every part name from a tab, otherwise where the AI found a tempo change,
 * and every 8 bars inside those. We don't guess "verse"/"chorus" from audio:
 * on the benchmark only ~40 % of guesses were right.
 */
enum PartRows {
    static let maxBars = 8

    static func build(_ map: TempoMap, yourNames: [Int: String] = [:]) -> [PartRow] {
        let bars = map.bars
        if bars.isEmpty { return [] }
        var breaks: Set<Int> = [0, bars.count]
        if bars.contains(where: { $0.marker != nil }) {
            for (i, b) in bars.enumerated() where b.marker != nil { breaks.insert(i) }
        } else {
            for s in map.sections.dropFirst() {
                if let i = bars.firstIndex(where: { $0.start >= s.start - 0.05 }), i > 0 { breaks.insert(i) }
            }
        }
        var rows: [PartRow] = []
        let cuts = breaks.sorted()
        for k in 0..<cuts.count - 1 {
            let a = cuts[k], b = cuts[k + 1]
            let name = bars[a].marker
            var i = a
            while i < b {
                let e = min(b, i + maxBars)
                if let mine = yourNames[i] { rows.append(PartRow(first: i, end: e, name: mine, source: .you)) }
                else if let name { rows.append(PartRow(first: i, end: e, name: name, source: .tab, continued: i != a)) }
                else { rows.append(PartRow(first: i, end: e, name: nil, source: nil)) }
                i = e
            }
        }
        return rows
    }

    static func usualMeter(_ map: TempoMap) -> String? {
        Dictionary(grouping: map.bars, by: \.meter).max { $0.value.count < $1.value.count }?.key
    }
}
