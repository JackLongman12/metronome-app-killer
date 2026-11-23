import AppKit
import Foundation

/**
 * Checks GitHub for a newer Mac build every time the app opens, and installs it.
 *
 * LEARN — how releases are found: CI publishes each Mac build as a GitHub
 * Release tagged `mac-b<number>` (Android builds are `b<number>`, so the two
 * never mix). We ask GitHub's public API for the newest releases. That API
 * allows 60 requests an hour per internet address; if it says "rate limited"
 * we read the releases "Atom feed" (a plain list of releases) instead, which
 * isn't limited.
 *
 * LEARN — installing: an app can't overwrite itself while running. So we
 * download and unpack the new version, then start a tiny shell script and quit.
 * The script waits for us to close, swaps the old .app for the new one, opens
 * it, and deletes itself.
 */
@MainActor
final class Updater: ObservableObject {
    struct Release: Equatable {
        let build: Int
        let notes: String
        var zipURL: URL { URL(string: "https://github.com/\(Updater.repo)/releases/download/mac-b\(build)/MetronomeKiller-mac-b\(build).zip")! }
    }

    enum State: Equatable {
        case idle, checking, upToDate
        case available(Release)
        case downloading(Double)
        case failed(String)
    }

    nonisolated static let repo = Bundle.main.object(forInfoDictionaryKey: "GitHubRepo") as? String ?? "JackLongman12/metronome-app-killer"

    @Published private(set) var state: State = .idle

    /**
     * LEARN — an "ephemeral" session keeps no cookies or cache on disk, so the
     * updater leaves no files behind (one less thing for the uninstaller).
     */
    private let session = URLSession(configuration: .ephemeral)

    func check() async {
        state = .checking
        do {
            let latest = try await fetchLatest()
            if let r = latest, r.build > AppFiles.version { state = .available(r) } else { state = .upToDate }
        } catch {
            // No internet is normal; just stay quiet and try again next launch.
            state = .idle
        }
    }

    private func fetchLatest() async throws -> Release? {
        var req = URLRequest(url: URL(string: "https://api.github.com/repos/\(Updater.repo)/releases?per_page=30")!)
        req.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        req.setValue("metronome-killer-mac-updater", forHTTPHeaderField: "User-Agent")
        req.timeoutInterval = 15
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        if code == 403 || code == 429 { return try await fromFeed() }
        guard code == 200, let list = try JSONSerialization.jsonObject(with: data) as? [[String: Any]] else { return nil }
        return list.compactMap { r -> Release? in
            guard r["draft"] as? Bool != true, let tag = r["tag_name"] as? String, let n = Updater.build(fromTag: tag) else { return nil }
            return Release(build: n, notes: r["body"] as? String ?? "")
        }.max { $0.build < $1.build }
    }

    /** Fallback: github.com/<repo>/releases.atom lists recent releases with their tags. */
    private func fromFeed() async throws -> Release? {
        var req = URLRequest(url: URL(string: "https://github.com/\(Updater.repo)/releases.atom")!)
        req.timeoutInterval = 15
        let (data, _) = try await session.data(for: req)
        let text = String(decoding: data, as: UTF8.self)
        let builds = text.components(separatedBy: "/releases/tag/").dropFirst().compactMap { part -> Int? in
            Updater.build(fromTag: String(part.prefix { $0 != "\"" && $0 != "<" }))
        }
        return builds.max().map { Release(build: $0, notes: "") }
    }

    /** "mac-b12" → 12; anything else (Android's "b24", models) → nil. */
    nonisolated static func build(fromTag tag: String) -> Int? {
        guard tag.hasPrefix("mac-b") else { return nil }
        return Int(tag.dropFirst(5))
    }

    /** Why updating can't work from where the app is, or nil if it can. */
    var blocker: String? {
        if AppFiles.isTranslocated { return "Move Metronome Killer to your Applications folder first, then open it from there." }
        if !AppFiles.appFolderWritable { return "This Mac doesn't let the app replace itself in its folder. Download the new version from GitHub instead." }
        return nil
    }

    func install(_ r: Release) async {
        if let b = blocker { state = .failed(b); return }
        state = .downloading(0)
        do {
            let work = AppFiles.caches.appendingPathComponent("update-\(r.build)")
            try? FileManager.default.removeItem(at: work)
            try FileManager.default.createDirectory(at: work, withIntermediateDirectories: true)

            // Download with progress.
            let zip = work.appendingPathComponent("update.zip")
            let (bytes, resp) = try await session.bytes(from: r.zipURL)
            guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw UpdateError("GitHub didn't have the file") }
            let total = max(resp.expectedContentLength, 1)
            FileManager.default.createFile(atPath: zip.path, contents: nil)
            let out = try FileHandle(forWritingTo: zip)
            var buf = Data(); buf.reserveCapacity(1 << 16)
            var done: Int64 = 0
            for try await b in bytes {
                buf.append(b)
                if buf.count >= 1 << 16 {
                    try out.write(contentsOf: buf); done += Int64(buf.count); buf.removeAll(keepingCapacity: true)
                    state = .downloading(Double(done) / Double(total))
                }
            }
            try out.write(contentsOf: buf)
            try out.close()

            // Unpack with ditto, Apple's own copy tool (keeps the app's internal links intact).
            try run("/usr/bin/ditto", ["-x", "-k", zip.path, work.path])
            guard let newApp = try FileManager.default.contentsOfDirectory(at: work, includingPropertiesForKeys: nil)
                .first(where: { $0.pathExtension == "app" }),
                  Bundle(url: newApp)?.bundleIdentifier == AppFiles.bundleId
            else { throw UpdateError("the download wasn't a Metronome Killer app") }

            let script = """
            #!/bin/sh
            # Waits for the old app to quit, swaps in the new one, opens it, then cleans up.
            while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done
            rm -rf \(q(AppFiles.appBundle.path))
            mv \(q(newApp.path)) \(q(AppFiles.appBundle.path))
            xattr -dr com.apple.quarantine \(q(AppFiles.appBundle.path)) 2>/dev/null
            open \(q(AppFiles.appBundle.path))
            rm -rf \(q(work.path))
            """
            try Shell.launchDetached(script, in: work)
            NSApp.terminate(nil)
        } catch {
            state = .failed("Couldn't update: \(error.localizedDescription)")
        }
    }

    private func run(_ tool: String, _ args: [String]) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        try p.run()
        p.waitUntilExit()
        if p.terminationStatus != 0 { throw UpdateError("\(tool) failed") }
    }
}

struct UpdateError: LocalizedError {
    let message: String
    init(_ m: String) { message = m }
    var errorDescription: String? { message }
}

/** Shell-quotes a path: 'like this', with any ' inside escaped. */
func q(_ s: String) -> String { "'" + s.replacingOccurrences(of: "'", with: "'\\''") + "'" }

enum Shell {
    /**
     * Starts a shell script that keeps running after the app quits.
     * LEARN: `nohup` makes the script ignore the "your parent closed" signal.
     */
    static func launchDetached(_ script: String, in dir: URL) throws {
        let file = dir.appendingPathComponent("run.sh")
        try script.write(to: file, atomically: true, encoding: .utf8)
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/usr/bin/nohup")
        p.arguments = ["/bin/sh", file.path]
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try p.run()
    }
}
