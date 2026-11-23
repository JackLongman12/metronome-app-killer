import CryptoKit
import Foundation

/**
 * YouTube (and other sites) through yt-dlp, the open-source downloader.
 *
 * LEARN — why yt-dlp is downloaded, not built in: it's 37 MB, and YouTube
 * changes something every few weeks. yt-dlp's team ships fixes within days, and
 * yt-dlp can update itself, so the app fetches it the first time you paste a
 * link and keeps it current. YouTube now also needs a small JavaScript engine to
 * work out its download addresses; we use QuickJS (1.4 MB).
 *
 * Both live in the app's own folder (Application Support/…/tools), so the
 * uninstaller removes them too. Ads never reach us: we fetch only the audio file.
 */
enum YtDlp {
    static var dir: URL {
        let u = AppFiles.support.appendingPathComponent("tools")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }
    static var binary: URL { dir.appendingPathComponent("yt-dlp") }
    static var qjs: URL { dir.appendingPathComponent("qjs") }  // yt-dlp wants it named exactly "qjs"
    static var cache: URL { dir.appendingPathComponent("cache") }
    private static var lastUpdate: URL { dir.appendingPathComponent("last-update") }

    private static let qjsURL = "https://github.com/quickjs-ng/quickjs/releases/download/v0.18.0/qjs-darwin-arm64"
    private static let qjsSHA = "91fe89916d3b0a6c01c4dc8382a99c3c1fd91ecfc214aa81ca051902bcce590d"
    private static let ytBase = "https://github.com/yt-dlp/yt-dlp/releases/latest/download/"

    /** Gets both tools if missing; asks yt-dlp to update itself at most once a day. */
    static func ensure(progress: @escaping (String, Double) -> Void) async throws {
        let fm = FileManager.default
        if !fm.fileExists(atPath: qjs.path) {
            let f = try await Net.download(qjsURL, sha256: qjsSHA) { progress("Getting ready for YouTube (1 of 2)", $0) }
            try install(f, at: qjs)
        }
        if !fm.fileExists(atPath: binary.path) {
            // The fingerprint list comes from the same official release.
            let sums = try await Net.text(ytBase + "SHA2-256SUMS")
            guard let line = sums.split(separator: "\n").first(where: { $0.hasSuffix(" yt-dlp_macos") }) else { throw UpdateError("yt-dlp release has no Mac build") }
            let sha = String(line.split(separator: " ")[0])
            let f = try await Net.download(ytBase + "yt-dlp_macos", sha256: sha) { progress("Getting ready for YouTube (2 of 2, 37 MB)", $0) }
            try install(f, at: binary)
            try? Date().description.write(to: lastUpdate, atomically: true, encoding: .utf8)
        } else if let d = try? fm.attributesOfItem(atPath: lastUpdate.path)[.modificationDate] as? Date, Date().timeIntervalSince(d) < 86_400 {
            // updated within the last day
        } else {
            progress("Updating yt-dlp", 0)
            _ = try? await run(["-U"])
            try? Date().description.write(to: lastUpdate, atomically: true, encoding: .utf8)
        }
    }

    private static func install(_ file: URL, at dest: URL) throws {
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: file, to: dest)
        try FileManager.default.setAttributes([.posixPermissions: 0o755], ofItemAtPath: dest.path)
        // Apple Silicon only runs signed programs; an ad-hoc signature is enough (and harmless if already signed).
        try? Net.run("/usr/bin/codesign", ["--force", "--sign", "-", dest.path])
    }

    /** Options every call uses: our JS engine, our cache folder (not ~/.cache), and no user config file. */
    private static var common: [String] {
        ["--ignore-config", "--no-warnings", "--js-runtimes", "quickjs:\(qjs.path)", "--cache-dir", cache.path]
    }

    /** Runs yt-dlp and returns what it printed. onLine gets each line as it comes (for progress). */
    @discardableResult
    static func run(_ args: [String], onLine: ((String) -> Void)? = nil) async throws -> String {
        try await withCheckedThrowingContinuation { cont in
            let p = Process()
            p.executableURL = binary
            p.arguments = common + args
            let out = Pipe(), err = Pipe()
            p.standardOutput = out
            p.standardError = err
            // LEARN: the reading code below runs on background threads, so the text it
            // collects lives in one shared box object, guarded by a lock.
            // Both streams are read as they come: progress can arrive on either, and a
            // program whose output nobody reads stalls once the pipe is full.
            let box = OutputBox(), errBox = OutputBox()
            out.fileHandleForReading.readabilityHandler = { h in
                let chunk = h.availableData
                guard !chunk.isEmpty else { return }
                for line in box.add(chunk) { onLine?(line) }
            }
            err.fileHandleForReading.readabilityHandler = { h in
                let chunk = h.availableData
                guard !chunk.isEmpty else { return }
                for line in errBox.add(chunk) { onLine?(line) }
            }
            p.terminationHandler = { proc in
                out.fileHandleForReading.readabilityHandler = nil
                err.fileHandleForReading.readabilityHandler = nil
                _ = box.add(out.fileHandleForReading.readDataToEndOfFile())
                _ = errBox.add(err.fileHandleForReading.readDataToEndOfFile())
                let all = box.all()
                let errText = String(decoding: errBox.all(), as: UTF8.self)
                if proc.terminationStatus == 0 { cont.resume(returning: String(decoding: all, as: UTF8.self)) }
                else {
                    let msg = errText.split(separator: "\n").last { $0.contains("ERROR") }.map(String.init) ?? "yt-dlp failed"
                    cont.resume(throwing: UpdateError(msg.replacingOccurrences(of: "ERROR: ", with: "")))
                }
            }
            do { try p.run() } catch { cont.resume(throwing: error) }
        }
    }
}

/** Collects a program's output from several threads safely. */
final class OutputBox: @unchecked Sendable {
    private let lock = NSLock()
    private var data = Data()
    private var partial = ""

    /** Adds a chunk; returns any complete lines in it. */
    func add(_ chunk: Data) -> [String] {
        lock.lock(); defer { lock.unlock() }
        data.append(chunk)
        partial += String(decoding: chunk, as: UTF8.self)
        var lines: [String] = []
        while let nl = partial.firstIndex(of: "\n") {
            lines.append(String(partial[..<nl]))
            partial = String(partial[partial.index(after: nl)...])
        }
        return lines
    }

    func all() -> Data { lock.lock(); defer { lock.unlock() }; return data }
}

/** What a pasted link turned out to be. */
enum Resolved {
    case single(LinkItem)
    case playlist(name: String, items: [LinkItem])
}

struct LinkItem {
    let url: String
    let title: String
    let artist: String
    let durationSec: Int
}

enum Links {
    /** Pulls the first http(s) link out of text like "Check this out https://youtu.be/...". */
    static func findURL(_ text: String) -> String? {
        text.firstMatch(#"(https?://\S+)"#).map { $0.trimmingCharacters(in: CharacterSet(charactersIn: ".,)")) }
    }

    /** Works out what a link is (one video or a playlist). */
    static func resolve(_ url: String) async throws -> Resolved {
        let json = try await YtDlp.run(["-J", "--flat-playlist", url])
        guard let o = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any] else { throw UpdateError("yt-dlp gave no details") }
        if o["_type"] as? String == "playlist" {
            let entries = (o["entries"] as? [[String: Any]] ?? []).prefix(300).compactMap(item)
            if entries.isEmpty { throw UpdateError("That playlist is empty.") }
            return .playlist(name: o["title"] as? String ?? "Playlist", items: Array(entries))
        }
        guard let one = item(o) else { throw UpdateError("That link isn't a song.") }
        return .single(one)
    }

    private static func item(_ o: [String: Any]) -> LinkItem? {
        let url = o["webpage_url"] as? String ?? (o["url"] as? String).map { $0.hasPrefix("http") ? $0 : "https://www.youtube.com/watch?v=\($0)" }
        guard let url else { return nil }
        let artist = o["artist"] as? String ?? o["channel"] as? String ?? o["uploader"] as? String ?? ""
        return LinkItem(url: url, title: o["track"] as? String ?? o["title"] as? String ?? "Untitled", artist: artist,
                        durationSec: Int((o["duration"] as? NSNumber)?.doubleValue ?? 0))
    }

    /**
     * Downloads the audio of [url] into [dir]. AAC (m4a) because macOS plays
     * and decodes it natively (it can't open YouTube's Opus/WebM without extra
     * software). About 4 MB for a 4-minute song.
     */
    static func downloadAudio(_ url: String, dir: URL, baseName: String, progress: @escaping (Double) -> Void) async throws -> URL {
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let template = dir.appendingPathComponent("\(baseName).%(ext)s").path
        let out = try await YtDlp.run([
            "-f", "bestaudio[ext=m4a]/bestaudio[acodec^=mp4a]/bestaudio",
            "--no-playlist", "--newline", "--no-mtime", "--progress",
            "--progress-template", "download:PROGRESS %(progress._percent_str)s",
            "-o", template, "--print", "after_move:filepath", url,
        ]) { line in
            if line.hasPrefix("PROGRESS"), let p = Double(line.dropFirst(8).trimmingCharacters(in: .whitespaces).replacingOccurrences(of: "%", with: "")) {
                progress(p / 100)
            }
        }
        guard let path = out.split(separator: "\n").last(where: { $0.hasPrefix("/") }) else { throw UpdateError("Download didn't finish") }
        return URL(fileURLWithPath: String(path))
    }

    /** YouTube video id, or nil for other sites. */
    static func youtubeId(_ url: String) -> String? {
        url.firstMatch(#"(?:youtube\.com/(?:watch\?(?:.*&)?v=|shorts/|embed/)|youtu\.be/)([A-Za-z0-9_-]{11})"#)
    }

    /**
     * SponsorBlock: stretches of a YouTube video that aren't the song (sponsor
     * reads, intros, talking). Sends only the video id. Returns [start, end] pairs.
     */
    static func sponsorSegments(_ videoId: String) async -> [[Double]] {
        let cats = #"["sponsor","selfpromo","interaction","intro","outro","music_offtopic"]"#
        guard let text = try? await Net.text("https://sponsor.ajay.app/api/skipSegments?videoID=\(videoId)&categories=\(cats.urlQuery)"),
              let arr = try? JSONSerialization.jsonObject(with: Data(text.utf8)) as? [[String: Any]] else { return [] }
        return arr.compactMap { ($0["segment"] as? [NSNumber])?.map(\.doubleValue) }.filter { $0.count == 2 }
    }
}

/**
 * Spotify links (tracks, albums, playlists). A port of Spotify.kt.
 *
 * Spotify's audio is locked to its own app, so we only read the NAME, ARTIST
 * and LENGTH from Spotify's public embed player, then find the same recording
 * on YouTube. The embed lists at most about 50 songs of a playlist.
 */
enum Spotify {
    struct Ref { let type: String; let id: String }

    static func parse(_ url: String) -> Ref? {
        for p in [#"open\.spotify\.com/(?:intl-[a-z]+/)?(?:embed/)?(track|album|playlist)/([A-Za-z0-9]+)"#, #"spotify:(track|album|playlist):([A-Za-z0-9]+)"#] {
            if let re = try? NSRegularExpression(pattern: p), let m = re.firstMatch(in: url, range: NSRange(url.startIndex..., in: url)),
               let t = Range(m.range(at: 1), in: url), let i = Range(m.range(at: 2), in: url) {
                return Ref(type: String(url[t]), id: String(url[i]))
            }
        }
        return nil
    }

    static func isSpotify(_ url: String) -> Bool { parse(url) != nil }
    static func trackURL(_ id: String) -> String { "https://open.spotify.com/track/\(id)" }

    static func resolve(_ url: String) async throws -> Resolved {
        guard let ref = parse(url) else { throw UpdateError("That isn't a Spotify link.") }
        let html = try await Net.text("https://open.spotify.com/embed/\(ref.type)/\(ref.id)")
        guard let json = html.firstMatch(#"<script id="__NEXT_DATA__" type="application/json">(.*?)</script>"#, options: .dotMatchesLineSeparators),
              let root = try JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any],
              let e = (((root["props"] as? [String: Any])?["pageProps"] as? [String: Any])?["state"] as? [String: Any]).flatMap({ ($0["data"] as? [String: Any])?["entity"] as? [String: Any] })
        else { throw UpdateError("Spotify didn't send the song details.") }
        if e["type"] as? String == "track" {
            let artist = (e["artists"] as? [[String: Any]] ?? []).compactMap { $0["name"] as? String }.joined(separator: ", ")
            return .single(LinkItem(url: trackURL(e["id"] as? String ?? ref.id), title: e["name"] as? String ?? e["title"] as? String ?? "",
                                    artist: artist, durationSec: (e["duration"] as? Int ?? 0) / 1000))
        }
        let items = (e["trackList"] as? [[String: Any]] ?? []).compactMap { t -> LinkItem? in
            guard let uri = t["uri"] as? String, uri.hasPrefix("spotify:track:") else { return nil }
            let artist = (t["subtitle"] as? String ?? "").replacingOccurrences(of: "\u{00A0}", with: " ")
                .replacingOccurrences(of: #",\s*"#, with: ", ", options: .regularExpression)
            return LinkItem(url: trackURL(String(uri.dropFirst(14))), title: t["title"] as? String ?? "", artist: artist,
                            durationSec: (t["duration"] as? Int ?? 0) / 1000)
        }
        if items.isEmpty { throw UpdateError("That Spotify link has no songs.") }
        return .playlist(name: e["name"] as? String ?? e["title"] as? String ?? "Spotify", items: items)
    }

    struct Candidate { let url: String; let title: String; let channel: String; let durationSec: Int }

    private static let versionWords = ["live", "cover", "remix", "karaoke", "instrumental", "acoustic", "sped up", "slowed", "nightcore", "8d", "reaction", "lesson", "tutorial"]

    private static func words(_ s: String) -> Set<String> {
        Set(s.lowercased().components(separatedBy: CharacterSet.alphanumerics.inverted).filter { !$0.isEmpty })
    }

    /**
     * Picks the YouTube result most likely to be the same recording. LENGTH is
     * the strongest clue (within 8 s, or 5 % for long songs); then title words,
     * "Artist - Topic" channels (official audio), and no live/cover/remix versions.
     */
    static func pick(_ candidates: [Candidate], title: String, artist: String, durationSec: Int) -> Candidate? {
        let want = words(title), artistWords = words(artist)
        let tolerance = max(8, durationSec / 20)
        return candidates
            .filter { durationSec <= 0 || ($0.durationSec > 0 && abs($0.durationSec - durationSec) <= tolerance) }
            .map { c -> (Candidate, Double) in
                let have = words(c.title)
                let channel = c.channel.lowercased()
                var score = want.isEmpty ? 0 : 4.0 * Double(want.filter { have.contains($0) }.count) / Double(want.count)
                if artistWords.contains(where: { have.contains($0) || words(channel).contains($0) }) { score += 1.5 }
                if channel.hasSuffix(" - topic") { score += 1.5 }
                for w in versionWords where c.title.lowercased().contains(w) && !title.lowercased().contains(w) { score -= 3 }
                if durationSec > 0 { score -= Double(abs(c.durationSec - durationSec)) / Double(tolerance) }
                return (c, score)
            }
            .filter { $0.1 > 1.0 }
            .max { $0.1 < $1.1 }?.0
    }

    /** Finds the song on YouTube and returns the video URL. */
    static func matchOnYouTube(title: String, artist: String, durationSec: Int) async throws -> String {
        let query = "\(artist) \(title)".trimmingCharacters(in: .whitespaces)
        let json = try await YtDlp.run(["-J", "--flat-playlist", "ytsearch10:\(query)"])
        let entries = ((try? JSONSerialization.jsonObject(with: Data(json.utf8)) as? [String: Any])?["entries"] as? [[String: Any]]) ?? []
        let cands = entries.compactMap { o -> Candidate? in
            guard let id = o["id"] as? String else { return nil }
            return Candidate(url: "https://www.youtube.com/watch?v=\(id)", title: o["title"] as? String ?? "",
                             channel: o["channel"] as? String ?? o["uploader"] as? String ?? "",
                             durationSec: Int((o["duration"] as? NSNumber)?.doubleValue ?? 0))
        }
        guard let c = pick(cands, title: title, artist: artist, durationSec: durationSec) else { throw UpdateError("Couldn't find \"\(title)\" on YouTube.") }
        return c.url
    }
}
