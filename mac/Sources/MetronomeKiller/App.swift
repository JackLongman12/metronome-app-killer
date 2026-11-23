import AppKit
import SwiftUI

/**
 * The app's entry point.
 *
 * LEARN: `@main` marks where the program starts. A SwiftUI `App` describes the
 * windows (here one main window) and the menu bar commands. `@StateObject`
 * creates an object once and keeps it alive for the app's whole life.
 */
@main
struct MetronomeKillerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var metronome = Metronome()
    @StateObject private var updater = Updater()

    var body: some Scene {
        Window("Metronome Killer", id: "main") {
            ContentView()
                .environmentObject(metronome)
                .environmentObject(updater)
                .frame(minWidth: 680, minHeight: 460)
                // Check for updates every time the app opens.
                .task { await updater.check() }
                // Keep the audio output warm only while the app is in front.
                .onReceive(NotificationCenter.default.publisher(for: NSApplication.didBecomeActiveNotification)) { _ in metronome.setActive(true) }
                .onReceive(NotificationCenter.default.publisher(for: NSApplication.didResignActiveNotification)) { _ in metronome.setActive(false) }
        }
        .windowResizability(.contentMinSize)
        .commands {
            CommandGroup(after: .appInfo) {
                Button("Check for Updates…") { Task { await updater.check() } }
                Divider()
                Button("Uninstall Metronome Killer…") { Uninstaller.confirmAndRun() }
            }
            CommandMenu("Metronome") {
                Button(metronome.playing ? "Stop" : "Start") { metronome.togglePlay() }
                    .keyboardShortcut(.space, modifiers: [])
                Button("Faster") { metronome.nudge(1) }.keyboardShortcut(.upArrow, modifiers: [])
                Button("Slower") { metronome.nudge(-1) }.keyboardShortcut(.downArrow, modifiers: [])
                Button("Faster by 10") { metronome.nudge(10) }.keyboardShortcut(.upArrow, modifiers: .shift)
                Button("Slower by 10") { metronome.nudge(-10) }.keyboardShortcut(.downArrow, modifiers: .shift)
                Button("Tap Tempo") { metronome.tap() }.keyboardShortcut("t", modifiers: [])
            }
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    // Closing the window quits the app (no invisible app left running).
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool { true }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Don't save window state to ~/Library/Saved Application State (fewer leftover files).
        UserDefaults.standard.set(false, forKey: "NSQuitAlwaysKeepsWindows")
    }
}

/** The app's colours: plain grey surfaces with one teal accent, like the phone app. */
extension Color {
    static let mkTeal = Color(red: 0.11, green: 0.62, blue: 0.46)
    static let mkTealDark = Color(red: 0.06, green: 0.43, blue: 0.34)
    static let mkTealLight = Color(red: 0.62, green: 0.88, blue: 0.80)
}
