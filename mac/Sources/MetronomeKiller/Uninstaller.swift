import AppKit
import Foundation

/**
 * Removes the app and every file it ever made, leaving nothing behind.
 *
 * LEARN: dragging an app to the Trash only removes the .app; its settings and
 * caches in ~/Library stay forever. Here we delete the folders listed in
 * [AppFiles.everything] too. Like the updater, the actual deleting is done by a
 * small script that runs after the app has quit (a running app can't delete
 * itself cleanly). The script lives in the system's temporary folder, which
 * macOS empties by itself, and it deletes itself when finished.
 */
enum Uninstaller {
    /** What will be removed, for the confirmation message. */
    static var summary: String {
        let existing = AppFiles.everything.filter { FileManager.default.fileExists(atPath: $0.path) }
        let size = ([AppFiles.appBundle] + existing).reduce(Int64(0)) { $0 + folderSize($1) }
        let mb = ByteCountFormatter.string(fromByteCount: size, countStyle: .file)
        return "This removes the app, your settings, songs, setlists and downloaded tools (\(mb) in total). It can't be undone."
    }

    @MainActor
    static func confirmAndRun() {
        let alert = NSAlert()
        alert.messageText = "Uninstall Metronome Killer?"
        alert.informativeText = summary
        alert.alertStyle = .warning
        alert.addButton(withTitle: "Uninstall")
        alert.addButton(withTitle: "Cancel")
        alert.buttons.first?.hasDestructiveAction = true
        guard alert.runModal() == .alertFirstButtonReturn else { return }

        if AppFiles.isTranslocated {
            // The running copy is a hidden read-only one; we still clean up the files,
            // but the original .app must be dragged to the Trash by hand.
            note("Your files will be removed. macOS is running this app from a hidden copy, so drag the original Metronome Killer from where you downloaded it to the Trash.")
        }
        do {
            try run()
            NSApp.terminate(nil)
        } catch {
            note("Couldn't uninstall: \(error.localizedDescription)")
        }
    }

    static func run() throws {
        let pid = ProcessInfo.processInfo.processIdentifier
        var lines = [
            "#!/bin/sh",
            "# Metronome Killer uninstaller: waits for the app to quit, then removes everything it made.",
            "while kill -0 \(pid) 2>/dev/null; do sleep 0.2; done",
        ]
        if !AppFiles.isTranslocated { lines.append("rm -rf \(q(AppFiles.appBundle.path))") }
        lines += AppFiles.everything.map { "rm -rf \(q($0.path))" }
        // Settings live in macOS's preferences system too, which keeps a copy in memory; ask it to forget them.
        lines.append("defaults delete \(q(AppFiles.bundleId)) 2>/dev/null")
        lines.append("rm -f \(q(FileManager.default.homeDirectoryForCurrentUser.appendingPathComponent("Library/Preferences/\(AppFiles.bundleId).plist").path))")
        lines.append("rm -rf \"$(dirname \"$0\")\"")

        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("mk-uninstall-\(pid)")
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        try Shell.launchDetached(lines.joined(separator: "\n") + "\n", in: dir)
    }

    private static func folderSize(_ url: URL) -> Int64 {
        guard let e = FileManager.default.enumerator(at: url, includingPropertiesForKeys: [.totalFileAllocatedSizeKey]) else {
            return Int64((try? url.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize) ?? 0)
        }
        var total: Int64 = 0
        for case let f as URL in e { total += Int64((try? f.resourceValues(forKeys: [.totalFileAllocatedSizeKey]).totalFileAllocatedSize) ?? 0) }
        return total
    }

    @MainActor
    private static func note(_ text: String) {
        let a = NSAlert()
        a.messageText = text
        a.runModal()
    }
}
