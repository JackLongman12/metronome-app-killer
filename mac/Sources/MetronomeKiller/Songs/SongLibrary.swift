import AVFoundation
import Foundation
import SwiftUI

/** Where a song is in the analysis process. */
enum SongStatus: Equatable {
    case ready, queued
    case working(String, Double)
    case failed(String)
}

/** Which tempo map a song plays with. */
enum MapSource { case ai, tab, edit }

struct Song: Identifiable, Codable, Equatable {
    var id: String
    var title: String
    var artist: String
    /** Path of the audio file (yours, or our download). Empty until downloaded. */
    var path: String
    var addedAt: Date
    var link: String? = nil
    var playlist: String? = nil
    /** Non-music stretches to skip (SponsorBlock), [start, end] in seconds. */
    var skips: [[Double]] = []
    /** Timing nudge for this song's clicks, in ms (+ later, − earlier). */
    var offsetMs = 0
    var tabInfo: String? = nil
    var usingTab = false
    var usingEdit = false
    var tabId: Int? = nil
    var rowNames: [Int: String] = [:]
    /** Length Spotify reported: helps find the same recording on YouTube. */
    var spotifySeconds = 0

    // Not saved in the index (rebuilt on load)
    var map: TempoMap? = nil
    var status: SongStatus = .queued
    var hasEdit = false

    enum CodingKeys: String, CodingKey {
        case id, title, artist, path, addedAt, link, playlist, skips, offsetMs, tabInfo, usingTab, usingEdit, tabId, rowNames, spotifySeconds
    }

    var durationText: String {
        guard let d = map?.duration else { return "" }
        return String(format: "%d:%02d", Int(d) / 60, Int(d) % 60)
    }
}

/**
 * Your songs and their tempo maps (a port of SongLibrary.kt).
 *
 * - Your own files are NOT copied: we remember where they are. Downloads go in
 *   the app's folder (so the uninstaller removes them).
 * - Analysis runs one song at a time in the background.
 * - Storage: songs/index.json (the list) and songs/<id>.json (the map in use),
 *   plus <id>.ai.json, <id>.tab.json and <id>.edit.json for switching.
 *
 * LEARN — the analysis steps: (link: find on YouTube → download) → get the
 * analysis files (first time only) → decode + resample → neural network →
 * tempo map → Songsterr tab if there is one → save.
 */
@MainActor
final class SongLibrary: ObservableObject {
    @Published private(set) var songs: [Song] = []
    /** Message while a pasted link is being looked up (nil when idle). */
    @Published var linkStatus: String?

    private let dir: URL
    private let audioDir: URL
    private var index: URL { dir.appendingPathComponent("index.json") }
    private var queue: [String] = []
    private var working = false

    init() {
        dir = AppFiles.support.appendingPathComponent("songs")
        audioDir = dir.appendingPathComponent("audio")
        try? FileManager.default.createDirectory(at: audioDir, withIntermediateDirectories: true)
        songs = load()
        // Anything that was mid-analysis when the app closed gets another go.
        for s in songs where s.map == nil { analyse(s.id) }
    }

    func song(_ id: String) -> Song? { songs.first { $0.id == id } }

    private func update(_ id: String, _ change: (inout Song) -> Void) {
        guard let i = songs.firstIndex(where: { $0.id == id }) else { return }
        change(&songs[i])
    }

    private func file(_ id: String, _ kind: String) -> URL { dir.appendingPathComponent(kind.isEmpty ? "\(id).json" : "\(id).\(kind).json") }

    // ── Adding ──────────────────────────────────────────────────────────────

    /** Add audio files from the Mac (Open… or dropped on the window). */
    func add(files: [URL]) {
        for url in files {
            let (title, artist) = Self.describe(url)
            let s = Song(id: UUID().uuidString, title: title, artist: artist, path: url.path, addedAt: Date())
            songs.insert(s, at: 0)
            analyse(s.id)
        }
        save()
    }

    /** A YouTube, Spotify or Songsterr link (or a playlist). */
    func addLink(_ text: String) {
        let url = Links.findURL(text) ?? text.trimmingCharacters(in: .whitespacesAndNewlines)
        if let tab = Songsterr.songId(fromLink: url) { addSongsterr(tab); return }
        linkStatus = "Looking up the link…"
        Task {
            do {
                let spotify = Spotify.isSpotify(url)
                if !spotify { try await YtDlp.ensure { [weak self] step, _ in Task { @MainActor in self?.linkStatus = step } } }
                let r = spotify ? try await Spotify.resolve(url) : try await Links.resolve(url)
                switch r {
                case .single(let item):
                    let s = newSong(item, playlist: nil, spotify: spotify, order: 0)
                    songs.insert(s, at: 0); save(); analyse(s.id)
                case .playlist(let name, let items):
                    let added = items.enumerated().map { newSong($1, playlist: name, spotify: spotify, order: $0) }
                    songs.insert(contentsOf: added, at: 0); save()
                    added.forEach { analyse($0.id) }
                }
                linkStatus = nil
            } catch {
                linkStatus = "Couldn't open that link: \(error.localizedDescription)"
            }
        }
    }

    private func newSong(_ item: LinkItem, playlist: String?, spotify: Bool, order: Int) -> Song {
        Song(id: UUID().uuidString, title: item.title, artist: item.artist, path: "",
             addedAt: Date().addingTimeInterval(-Double(order)), link: item.url, playlist: playlist,
             spotifySeconds: spotify ? item.durationSec : 0)
    }

    /** A Songsterr link on its own: use the YouTube video the tab is synced to. */
    private func addSongsterr(_ songId: Int) {
        linkStatus = "Looking up the Songsterr tab…"
        Task {
            do {
                let tab = try await Songsterr.fetch(songId)
                guard let video = tab.videos.first?.id else {
                    linkStatus = "That tab isn't synced to a video, so it can't be used on its own. Add the song first, then use ••• → Use a Songsterr tab."
                    return
                }
                let s = Song(id: UUID().uuidString, title: tab.title, artist: tab.artist, path: "", addedAt: Date(),
                             link: "https://www.youtube.com/watch?v=\(video)", tabId: songId)
                songs.insert(s, at: 0); save(); analyse(s.id)
                linkStatus = nil
            } catch {
                linkStatus = "Couldn't open that link: \(error.localizedDescription)"
            }
        }
    }

    func delete(_ id: String) {
        guard let s = song(id) else { return }
        songs.removeAll { $0.id == id }
        for k in ["", "ai", "tab", "edit"] { try? FileManager.default.removeItem(at: file(id, k)) }
        // Only delete audio we downloaded, never your own files.
        if s.path.hasPrefix(audioDir.path) { try? FileManager.default.removeItem(atPath: s.path) }
        save()
    }

    func analyseAgain(_ id: String) { analyse(id) }

    // ── Analysis ────────────────────────────────────────────────────────────

    private func analyse(_ id: String) {
        update(id) { $0.status = .queued }
        if !queue.contains(id) { queue.append(id) }
        runQueue()
    }

    private func runQueue() {
        guard !working, !queue.isEmpty else { return }
        working = true
        let id = queue.removeFirst()
        Task {
            await runAnalysis(id)
            working = false
            runQueue()
        }
    }

    private func status(_ id: String, _ step: String, _ p: Double) { update(id) { $0.status = .working(step, p) } }

    /** Progress callbacks arrive on background threads; this hops them back to the main thread. */
    private nonisolated func reporter(_ id: String) -> (String, Double) -> Void {
        { [weak self] step, p in Task { @MainActor in self?.status(id, step, p) } }
    }

    private func runAnalysis(_ id: String) async {
        guard var s = song(id) else { return }
        let report = reporter(id)
        do {
            // Spotify songs: find the same recording on YouTube, then carry on as a YouTube link.
            if let link = s.link, Spotify.isSpotify(link) {
                try await YtDlp.ensure(progress: report)
                report("Finding it on YouTube", 0)
                let yt = try await Spotify.matchOnYouTube(title: s.title, artist: s.artist, durationSec: s.spotifySeconds)
                update(id) { $0.link = yt }; save()
                s = song(id) ?? s
            }
            // Links: download the audio first (once), and look up parts to skip.
            if let link = s.link, s.path.isEmpty || !FileManager.default.fileExists(atPath: s.path) {
                try await YtDlp.ensure(progress: report)
                report("Downloading audio", 0)
                let f = try await Links.downloadAudio(link, dir: audioDir, baseName: id) { report("Downloading audio", $0) }
                let skips = await Links.youtubeId(link).asyncMap { await Links.sponsorSegments($0) } ?? []
                update(id) { $0.path = f.path; $0.skips = skips }; save()
                s = song(id) ?? s
            }
            try await ModelFiles.ensure(progress: report)

            report("Reading the song", 0)
            let path = s.path
            // LEARN: Task.detached runs the heavy maths off the main thread, so the window stays smooth.
            let ai: TempoMap = try await Task.detached(priority: .userInitiated) {
                let audio = try AudioDecoder.decode(URL(fileURLWithPath: path))
                report("Finding the beats", 0)
                let r = try BeatPipeline.analyse(audio.samples) { report("Finding the beats", $0) }
                return TempoMapBuilder.build(rawBeats: r.beats, downbeats: r.downbeats, duration: audio.duration, dbAct: r.downbeatScores)
            }.value
            try ai.save(to: file(id, "ai"))
            try? FileManager.default.removeItem(at: file(id, "edit")) // a fresh analysis replaces old hand fixes

            // Songsterr: a human-written tempo map, if this song has a synced tab.
            var map = ai
            var tabInfo: String?
            report("Looking for a Songsterr tab", 0.5)
            var tab: Songsterr.Tab?
            if let tid = s.tabId { tab = try? await Songsterr.fetch(tid) } else { tab = (try? await Songsterr.find(title: s.title, artist: s.artist)) ?? nil }
            if let tab {
                let points = tab.points(for: s.link.flatMap(Links.youtubeId))
                let tabMap = points.map { Songsterr.mapFromSync(tab, $0, ai.duration) } ?? Songsterr.mapAligned(tab, ai.beats, ai.duration)
                if let tabMap {
                    try tabMap.save(to: file(id, "tab"))
                    map = tabMap
                    tabInfo = "\(tab.artist) – \(tab.title) · \(points != nil ? "synced to this video" : "lined up with the audio")"
                }
            }
            try map.save(to: file(id, ""))
            update(id) {
                $0.map = map; $0.status = .ready; $0.tabInfo = tabInfo; $0.usingTab = tabInfo != nil
                $0.rowNames = [:]; $0.hasEdit = false; $0.usingEdit = false
            }
            save()
        } catch {
            update(id) { $0.status = .failed(error.localizedDescription) }
        }
    }

    // ── Choosing and editing maps ───────────────────────────────────────────

    /** Switch which map plays: the AI's, the tab's, or your edited one. */
    func useMap(_ id: String, _ source: MapSource) {
        let kind = source == .ai ? "ai" : (source == .tab ? "tab" : "edit")
        guard let m = TempoMap.load(file(id, kind)) else { return }
        try? m.save(to: file(id, ""))
        update(id) { $0.map = m; $0.usingTab = source == .tab; $0.usingEdit = source == .edit }
        save()
    }

    func saveEdit(_ id: String, _ map: TempoMap) {
        try? map.save(to: file(id, "edit"))
        try? map.save(to: file(id, ""))
        update(id) { $0.map = map; $0.hasEdit = true; $0.usingEdit = true; $0.usingTab = false }
        save()
    }

    func discardEdit(_ id: String) {
        try? FileManager.default.removeItem(at: file(id, "edit"))
        update(id) { $0.hasEdit = false; $0.usingEdit = false }
        useMap(id, song(id)?.tabInfo != nil ? .tab : .ai)
    }

    func renameRow(_ id: String, firstBar: Int, name: String) {
        update(id) { s in
            let n = name.trimmingCharacters(in: .whitespaces)
            if n.isEmpty { s.rowNames.removeValue(forKey: firstBar) } else { s.rowNames[firstBar] = n }
        }
        save()
    }

    func setOffset(_ id: String, _ ms: Int) { update(id) { $0.offsetMs = ms.clamped(-300, 300) }; save() }

    /** Use a specific Songsterr tab you picked (pasted link). It still has to line up with the recording. */
    func attachTab(_ id: String, link: String) {
        guard let songId = Songsterr.songId(fromLink: link) else { linkStatus = "Couldn't read that link. Copy it from the tab's page on songsterr.com."; return }
        linkStatus = "Fetching the tab…"
        Task {
            do {
                guard let s = song(id), let ai = TempoMap.load(file(id, "ai")) else { return }
                let tab = try await Songsterr.fetch(songId)
                let points = tab.points(for: s.link.flatMap(Links.youtubeId))
                guard let tabMap = points.map({ Songsterr.mapFromSync(tab, $0, ai.duration) }) ?? Songsterr.mapAligned(tab, ai.beats, ai.duration) else {
                    linkStatus = "That tab doesn't line up with this recording (maybe a different version), so nothing changed."
                    return
                }
                try tabMap.save(to: file(id, "tab"))
                try tabMap.save(to: file(id, ""))
                let info = "\(tab.artist) – \(tab.title) · \(points != nil ? "synced to this video" : "lined up with the audio")"
                update(id) { $0.map = tabMap; $0.tabInfo = info; $0.usingTab = true; $0.usingEdit = false; $0.tabId = songId; $0.rowNames = [:] }
                save()
                linkStatus = "Using the tab: \(info)"
            } catch {
                linkStatus = "Couldn't use that tab: \(error.localizedDescription)"
            }
        }
    }

    // ── Storage ─────────────────────────────────────────────────────────────

    private func load() -> [Song] {
        guard let data = try? Data(contentsOf: index), var list = try? JSONDecoder().decode([Song].self, from: data) else { return [] }
        for i in list.indices {
            let id = list[i].id
            list[i].map = TempoMap.load(file(id, ""))
            list[i].status = list[i].map != nil ? .ready : .queued
            list[i].hasEdit = FileManager.default.fileExists(atPath: file(id, "edit").path)
        }
        return list
    }

    private func save() {
        if let data = try? JSONEncoder().encode(songs) { try? data.write(to: index, options: .atomic) }
    }

    /** Title and artist from the file's tags, else its name. */
    private static func describe(_ url: URL) -> (String, String) {
        var title: String?, artist: String?
        let asset = AVURLAsset(url: url)
        for item in asset.commonMetadata {
            if item.commonKey == .commonKeyTitle { title = item.stringValue }
            if item.commonKey == .commonKeyArtist { artist = item.stringValue }
        }
        return (title?.isEmpty == false ? title! : url.deletingPathExtension().lastPathComponent, artist ?? "")
    }
}

extension Optional {
    /** Like map, for async work. */
    func asyncMap<T>(_ f: (Wrapped) async -> T) async -> T? {
        guard let v = self else { return nil }
        return await f(v)
    }
}
