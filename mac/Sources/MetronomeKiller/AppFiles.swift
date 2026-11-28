import Foundation

/**
 * Every place on the Mac this app writes to, in one list.
 *
 * LEARN: macOS apps keep their files in a few standard folders inside
 * ~/Library, each named after the app's "bundle identifier"
 * (com.metronomekiller.mac). Keeping the full list here is what makes a clean
 * uninstall possible: the uninstaller deletes exactly these, and nothing of ours
 * is left behind. If you add a new kind of file, put its folder in this list.
 */
enum AppFiles {
    static let bundleId = Bundle.main.bundleIdentifier ?? "com.metronomekiller.mac"

    private static var library: URL { FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library") }

    /** Settings, setlists and practice history live here. */
    static var support: URL {
        let u = library.appendingPathComponent("Application Support/\(bundleId)")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    /** Throwaway files, e.g. an update being downloaded. */
    static var caches: URL {
        let u = library.appendingPathComponent("Caches/\(bundleId)")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static var settingsFile: URL { support.appendingPathComponent("settings.json") }

    /**
     * Everything to delete on uninstall. Some of these macOS itself creates for
     * any app (window positions, network caches); we remove them too.
     */
    static var everything: [URL] {
        [
            library.appendingPathComponent("Application Support/\(bundleId)"),
            library.appendingPathComponent("Caches/\(bundleId)"),
            library.appendingPathComponent("HTTPStorages/\(bundleId)"),
            library.appendingPathComponent("HTTPStorages/\(bundleId).binarycookies"),
            library.appendingPathComponent("Saved Application State/\(bundleId).savedState"),
            library.appendingPathComponent("WebKit/\(bundleId)"),
            library.appendingPathComponent("Preferences/\(bundleId).plist"),
            library.appendingPathComponent("Logs/\(bundleId)"),
        ]
    }

    /** The .app itself, e.g. /Applications/Metronome Killer.app */
    static var appBundle: URL { Bundle.main.bundleURL }

    /**
     * LEARN — App Translocation: when you open an unsigned app straight from
     * Downloads, macOS secretly runs a read-only copy from a random folder. An app
     * in that state can't update or remove itself, so we ask you to move it to
     * Applications first.
     */
    static var isTranslocated: Bool { appBundle.path.contains("/AppTranslocation/") }

    static var appFolderWritable: Bool {
        FileManager.default.isWritableFile(atPath: appBundle.deletingLastPathComponent().path)
    }

    static var version: Int { Int(Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0") ?? 0 }
}
