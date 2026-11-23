import Foundation

/**
 * Songsterr tab lookup: a human-written tempo map for songs that have a tab.
 * A port of Songsterr.kt.
 *
 * Tabs list every bar's time signature and tempo; tabs with a player are also
 * synced to YouTube videos (the time each bar starts in that video). Only the
 * song's title and artist are sent, like opening the tab in a browser. It's
 * Songsterr's unofficial web API, so the AI map is always kept as a fallback.
 */
enum Songsterr {
    struct TabBar {
        let num: Int, den: Int, qpm: Double
        var marker: String? = nil
        var quarters: Double { Double(num) * 4 / Double(den) }
        var seconds: Double { quarters * 60 / qpm }
    }

    struct Tab {
        let songId: Int
        let title: String
        let artist: String
        let bars: [TabBar]
        /** Synced YouTube videos, Songsterr's main one first: (video id, start time of every bar). */
        let videos: [(id: String, points: [Double])]
        func points(for video: String?) -> [Double]? { videos.first { $0.id == video }?.points }
    }

    /** Song id from a link like .../nirvana-smells-like-teen-spirit-tab-s269 → 269. */
    static func songId(fromLink text: String) -> Int? {
        guard text.range(of: "songsterr.com", options: .caseInsensitive) != nil else { return nil }
        return text.trimmingCharacters(in: .whitespaces).firstMatch(#"-s(\d+)(?:t\d+)?(?:[/?#]|$)"#).flatMap { Int($0) }
    }

    /** "[A] Intro I (Guitar Riff)" → "Intro I (Guitar Riff)", "Verse 1:" → "Verse 1". */
    static func cleanMarker(_ text: String) -> String? {
        var s = text.replacingOccurrences(of: #"^\s*\[[^\]]*]\s*"#, with: "", options: .regularExpression).trimmingCharacters(in: .whitespaces)
        while s.hasSuffix(":") { s.removeLast() }
        s = String(s.trimmingCharacters(in: .whitespaces).prefix(40))
        return s.isEmpty ? nil : s
    }

    /** Messy video titles like "Periphery - Racecar (HQ Audio) [Official]" → "Periphery Racecar". */
    static func cleanQuery(_ title: String, _ artist: String) -> String {
        let t = title.replacingOccurrences(of: #"[(\[].*?[)\]]"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)\b(official|audio|video|lyrics?|hq|hd|4k|remaster(ed)?|music video|full album)\b"#, with: " ", options: .regularExpression)
        let a = artist.replacingOccurrences(of: #"(?i)\s*-\s*topic$"#, with: "", options: .regularExpression)
            .replacingOccurrences(of: #"(?i)vevo$"#, with: "", options: .regularExpression)
        let q = t.contains(" - ") || a.trimmingCharacters(in: .whitespaces).isEmpty || t.range(of: a, options: .caseInsensitive) != nil ? t : "\(a) \(t)"
        return q.replacingOccurrences(of: #"[^\p{L}\p{N}' ]"#, with: " ", options: .regularExpression)
            .replacingOccurrences(of: #"\s+"#, with: " ", options: .regularExpression).trimmingCharacters(in: .whitespaces)
    }

    static func tokens(_ s: String) -> Set<String> {
        Set(s.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { $0.count > 1 })
    }

    /** Finds the best-matching tab with a player, or nil. */
    static func find(title: String, artist: String) async throws -> Tab? {
        let q = cleanQuery(title, artist)
        if q.count < 3 { return nil }
        let res = try await json("https://www.songsterr.com/api/songs?pattern=\(q.urlQuery)&size=15") as? [[String: Any]] ?? []
        let words = tokens(q)
        var best: [String: Any]?
        var bestScore = 0.0
        for r in res where r["hasPlayer"] as? Bool == true {
            let rTitle = r["title"] as? String ?? ""
            let tTitle = tokens(rTitle.replacingOccurrences(of: #"(?i)\s-\s.*$"#, with: "", options: .regularExpression))
            let tAll = tTitle.union(tokens(r["artist"] as? String ?? "")).union(tokens(rTitle))
            if tTitle.isEmpty || !tTitle.isSubset(of: words) { continue }
            let score = Double(words.filter { tAll.contains($0) }.count) / Double(words.count)
            if score > bestScore { bestScore = score; best = r }
        }
        guard let best, bestScore >= 0.5, let id = best["songId"] as? Int else { return nil }
        return try await fetch(id)
    }

    /** Downloads one tab by its Songsterr song id. */
    static func fetch(_ songId: Int) async throws -> Tab {
        guard let meta = try await json("https://www.songsterr.com/api/meta/\(songId)") as? [String: Any],
              let rev = meta["revisionId"] as? Int, let image = meta["image"] as? String else { throw UpdateError("tab not found") }
        let title = meta["title"] as? String ?? ""
        let artist = (meta["artist"] as? [String: Any])?["name"] as? String ?? (meta["artist"] as? String ?? "")
        let defaultTrack = meta["defaultTrack"] as? Int ?? 0
        let part = max(meta["popularTrack"] as? Int ?? defaultTrack, 0)
        func track(_ n: Int) async throws -> [String: Any] {
            try await json("https://dqsljvtekg760.cloudfront.net/\(songId)/\(rev)/\(image)/\(n).json") as? [String: Any] ?? [:]
        }
        let tab = try await track(part)
        let measures = tab["measures"] as? [[String: Any]] ?? []
        let order = unroll(measures)

        func markersOf(_ ms: [[String: Any]]) -> [Int: String] {
            var out: [Int: String] = [:]
            for (i, m) in ms.enumerated() {
                if let text = (m["marker"] as? [String: Any])?["text"] as? String, let c = cleanMarker(text) { out[i] = c }
            }
            return out
        }
        var markers = markersOf(measures)
        if markers.isEmpty {
            for n in Array(Set([defaultTrack, 0])).sorted() where n != part && n >= 0 {
                guard let other = try? await track(n)["measures"] as? [[String: Any]] else { continue }
                if other.count == measures.count { markers = markersOf(other) }
                if !markers.isEmpty { break }
            }
        }

        var tempos: [Int: Double] = [:]
        for t in ((tab["automations"] as? [String: Any])?["tempo"] as? [[String: Any]]) ?? [] {
            if let m = t["measure"] as? Int, let bpm = (t["bpm"] as? NSNumber)?.doubleValue {
                tempos[m] = bpm * 4 / Double(t["type"] as? Int ?? 4)
            }
        }
        var sigs: [(Int, Int)] = []
        var sig = (4, 4)
        for m in measures {
            if let s = m["signature"] as? [Int], s.count == 2 { sig = (s[0], s[1]) }
            sigs.append(sig)
        }
        var qpm = 120.0
        let bars = order.map { mi -> TabBar in qpm = tempos[mi] ?? qpm; return TabBar(num: sigs[mi].0, den: sigs[mi].1, qpm: qpm, marker: markers[mi]) }

        var videos: [(String, [Double])] = []
        if let arr = try? await json("https://www.songsterr.com/api/video-points/\(songId)/\(rev)/list") as? [[String: Any]] {
            for v in arr {
                guard let pts = v["points"] as? [NSNumber], v["status"] as? String == "done", pts.count >= bars.count - 5,
                      let vid = v["videoId"] as? String else { continue }
                videos.append((vid, pts.map(\.doubleValue)))
            }
        }
        return Tab(songId: songId, title: title, artist: artist, bars: bars, videos: videos)
    }

    /** Expands repeat signs and voltas into playback order. */
    private static func unroll(_ measures: [[String: Any]]) -> [Int] {
        var order: [Int] = []
        var i = 0, start = 0, guardN = 0
        var passes: [Int: Int] = [:]
        while i < measures.count && guardN < 20000 {
            guardN += 1
            let m = measures[i]
            if m["repeatStart"] as? Bool == true { start = i }
            let p = passes[start] ?? 1
            if let volta = m["alternateEnding"] as? [Int], !volta.contains(p) { i += 1; continue }
            order.append(i)
            // LEARN: JSON true and the number 2 both arrive as NSNumber; CFBoolean tells them apart.
            // repeatClose is either true (play twice) or a count of plays.
            if let close = m["repeatClose"] as? NSNumber,
               !(CFGetTypeID(close) == CFBooleanGetTypeID() && !close.boolValue) {
                let times = CFGetTypeID(close) == CFBooleanGetTypeID() ? 2 : close.intValue
                if p < times { passes[start] = p + 1; i = start; continue }
                passes[start] = 1
            }
            i += 1
        }
        return order
    }

    // ── tab → tempo map ─────────────────────────────────────────────────────

    /** The song IS one of the tab's synced videos: bar starts from the sync points, gently smoothed. */
    static func mapFromSync(_ tab: Tab, _ points: [Double], _ duration: Double) -> TempoMap {
        let n = min(tab.bars.count, points.count)
        var q = [Double](repeating: 0, count: n + 1)
        for i in 0..<n { q[i + 1] = q[i] + tab.bars[i].quarters }
        let t = (0...n).map { $0 < n ? points[$0] : points[n - 1] + tab.bars[n - 1].seconds }
        var smooth = t
        for k in 0...n {
            let idx = (max(0, k - 4)...min(n, k + 4)).filter { tab.bars[min($0, n - 1)].qpm == tab.bars[min(k, n - 1)].qpm }
            if idx.count >= 3 {
                let mx = idx.map { q[$0] }.reduce(0, +) / Double(idx.count)
                let my = idx.map { t[$0] }.reduce(0, +) / Double(idx.count)
                var num = 0.0, den = 0.0
                for i in idx { num += (q[i] - mx) * (t[i] - my); den += (q[i] - mx) * (q[i] - mx) }
                if den > 0 { smooth[k] = my + num / den * (q[k] - mx) }
            }
        }
        return build(Array(tab.bars.prefix(n)), Array(smooth.prefix(n)), smooth[n], duration)
    }

    /** Other audio: slide the tab's bar grid until its bar lines land on the AI's beats. nil if it doesn't fit. */
    static func mapAligned(_ tab: Tab, _ aiBeats: [Double], _ duration: Double) -> TempoMap? {
        if aiBeats.count < 16 { return nil }
        var nominal = [Double](repeating: 0, count: tab.bars.count + 1)
        for i in tab.bars.indices { nominal[i + 1] = nominal[i] + tab.bars[i].seconds }
        let tabLen = nominal.last!
        if tabLen > duration * 1.15 || tabLen < duration * 0.6 { return nil }
        func nearest(_ x: Double) -> Double {
            var lo = 0, hi = aiBeats.count - 1
            while lo < hi { let m = (lo + hi) / 2; if aiBeats[m] < x { lo = m + 1 } else { hi = m } }
            let a = aiBeats[lo], b = lo > 0 ? aiBeats[lo - 1] : a
            return abs(a - x) < abs(b - x) ? a : b
        }
        var bestOff = 0.0, bestHits = -1
        var off = -5.0
        while off <= duration - tabLen * 0.6 && off <= 60 {
            var hits = 0
            for i in tab.bars.indices where abs(nearest(nominal[i] + off) - (nominal[i] + off)) < 0.07 { hits += 1 }
            if hits > bestHits { bestHits = hits; bestOff = off }
            off += 0.01
        }
        if Double(bestHits) < Double(tab.bars.count) * 0.55 { return nil }
        let starts = tab.bars.indices.map { i -> Double in
            let x = nominal[i] + bestOff
            let b = nearest(x)
            return abs(b - x) < 0.06 ? b : x
        }
        return build(tab.bars, starts, nominal.last! + bestOff, duration)
    }

    private static func build(_ bars: [TabBar], _ starts: [Double], _ end: Double, _ duration: Double) -> TempoMap {
        var beats: [Double] = []
        var outBars: [Bar] = []
        for i in bars.indices {
            let s = starts[i]
            let e = i + 1 < starts.count ? starts[i + 1] : end
            if e <= s { continue }
            outBars.append(Bar(s, bars[i].num, bars[i].den, marker: bars[i].marker))
            for k in 0..<bars[i].num { beats.append(s + (e - s) * Double(k) / Double(bars[i].num)) }
        }
        var sections: [Section] = []
        var a = 0
        while a < bars.count {
            var b = a
            while b + 1 < bars.count && bars[b + 1].qpm == bars[a].qpm && b + 1 - a < 16 { b += 1 }
            var tail = b + 1
            while tail < bars.count && bars[tail].qpm == bars[a].qpm { tail += 1 }
            if (1...7).contains(tail - (b + 1)) { b = tail - 1 }
            let group = Array(bars[a...b])
            let uniform = group.allSatisfy { $0.num == group[0].num && $0.den == group[0].den }
            let s = starts[a]
            let e = b + 1 < starts.count ? starts[b + 1] : end
            let noteValue = uniform ? group[0].den : 4
            let units = group.reduce(0.0) { $0 + (uniform ? Double($1.num) : $1.quarters) }
            let bpm = round1(units * 60 / (e - s))
            sections.append(Section(s, e, .STEADY, bpm, bpm, uniform ? group[0].num : 0, group.reduce(0) { $0 + $1.num }, s, false, noteValue))
            a = b + 1
        }
        if let f = sections.first, f.start > 1.0 { sections.insert(Section(0, f.start, .FREE, 0, 0, 0, 0, 0, false), at: 0) }
        if let l = sections.last, duration - l.end > 1.0 { sections.append(Section(l.end, duration, .FREE, 0, 0, 0, 0, l.end, false)) }
        let main = sections.filter { $0.kind == .STEADY }.max { ($0.end - $0.start) < ($1.end - $1.start) }?.bpm ?? 0
        return TempoMap(duration, main, beats, outBars.map(\.start), outBars, sections)
    }

    /** GET a JSON URL. Tab files come gzip-compressed even unasked, so check the magic bytes. */
    private static func json(_ url: String) async throws -> Any {
        var req = URLRequest(url: URL(string: url)!)
        req.timeoutInterval = 20
        req.setValue("Mozilla/5.0 (Macintosh) MetronomeKiller", forHTTPHeaderField: "User-Agent")
        let (data, resp) = try await Net.session.data(for: req)
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw UpdateError("Songsterr HTTP \((resp as? HTTPURLResponse)?.statusCode ?? 0)") }
        return try JSONSerialization.jsonObject(with: Gzip.unzipIfNeeded(data))
    }
}

/**
 * LEARN — gzip: a gzip file is a short header, then "deflate"-compressed data,
 * then a checksum. Apple's built-in decompressor reads raw deflate, so we skip
 * the header ourselves.
 */
enum Gzip {
    static func unzipIfNeeded(_ d: Data) -> Data {
        let b = [UInt8](d)
        guard b.count > 18, b[0] == 0x1f, b[1] == 0x8b else { return d }
        let flags = b[3]
        var pos = 10
        if flags & 4 != 0 { pos += 2 + Int(b[pos]) + Int(b[pos + 1]) << 8 }      // extra field
        if flags & 8 != 0 { while pos < b.count && b[pos] != 0 { pos += 1 }; pos += 1 } // file name
        if flags & 16 != 0 { while pos < b.count && b[pos] != 0 { pos += 1 }; pos += 1 } // comment
        if flags & 2 != 0 { pos += 2 }                                            // header checksum
        guard pos < b.count - 8 else { return d }
        let body = Data(b[pos..<(b.count - 8)])
        return (try? (body as NSData).decompressed(using: .zlib) as Data) ?? d
    }
}

extension String {
    /** First capture group of a regular expression, or nil. */
    func firstMatch(_ pattern: String, options: NSRegularExpression.Options = []) -> String? {
        guard let re = try? NSRegularExpression(pattern: pattern, options: options),
              let m = re.firstMatch(in: self, range: NSRange(startIndex..., in: self)), m.numberOfRanges > 1,
              let r = Range(m.range(at: 1), in: self) else { return nil }
        return String(self[r])
    }

    var urlQuery: String { addingPercentEncoding(withAllowedCharacters: .urlQueryAllowed.subtracting(CharacterSet(charactersIn: "&=+?"))) ?? self }
}
