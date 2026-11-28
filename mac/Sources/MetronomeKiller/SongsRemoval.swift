import Foundation

/**
 * The Songs feature was removed (Mac build 13). The first time the new version
 * opens, this deletes everything it left behind, once:
 *  - downloaded songs and their tempo maps (Application Support/…/songs)
 *  - the beat model and ONNX Runtime (…/models, about 40 MB)
 *  - yt-dlp and QuickJS (…/tools, about 40 MB)
 *  - caches (half-finished downloads)
 * Audio files you added from your own folders are NOT touched: the app only
 * ever read them where they were.
 */
enum SongsRemoval {
    static func run() {
        let d = UserDefaults.standard
        guard !d.bool(forKey: "songsRemoved") else { return }
        let fm = FileManager.default
        for name in ["songs", "models", "tools"] {
            try? fm.removeItem(at: AppFiles.support.appendingPathComponent(name))
        }
        for f in (try? fm.contentsOfDirectory(at: AppFiles.caches, includingPropertiesForKeys: nil)) ?? [] {
            try? fm.removeItem(at: f)
        }
        d.removeObject(forKey: "partRows")
        d.set(true, forKey: "songsRemoved")
    }
}
