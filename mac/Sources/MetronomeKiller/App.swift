import AppKit
import SwiftUI

/**
 * The app's entry point.
 *
 * LEARN: `@main` marks where the program starts. A SwiftUI `App` describes the
 * windows (the main window, the Settings window and the menu bar panel) and
 * the menu bar commands. `@StateObject` creates an object once and keeps it
 * alive for the app's whole life.
 */
@main
struct MetronomeKillerApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var delegate
    @StateObject private var metronome: Metronome
    @StateObject private var practice: Practice
    @StateObject private var updater = Updater()
    @StateObject private var setlists = SetlistStore()
    @StateObject private var app = AppState()
    @AppStorage("menuBar") private var menuBar = true

    init() {
        // CI runs the app with --benchmark to measure speed; then it never opens a window.
        if CommandLine.arguments.contains("--benchmark") { Benchmark.run() }
        let m = Metronome()
        _metronome = StateObject(wrappedValue: m)
        _practice = StateObject(wrappedValue: Practice(metronome: m))
    }

    var body: some Scene {
        Window("Metronome Killer", id: "main") {
            ContentView()
                .environmentObject(metronome)
                .environmentObject(practice)
                .environmentObject(updater)
                .environmentObject(setlists)
                .environmentObject(app)
                .frame(minWidth: 680, minHeight: 500)
                // Check for updates every time the app opens.
                .task { await updater.check() }
                .onAppear(perform: setUp)
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
                // The plain-key shortcuts (Space, arrows, T) are handled by KeyShortcuts
                // below, so they don't fire while you type in a text box. These menu
                // items do the same things with the mouse.
                Button(metronome.playing ? "Stop" : "Start") { app.togglePlay() }
                Button("Faster") { metronome.nudge(1) }
                Button("Slower") { metronome.nudge(-1) }
                Button("Tap Tempo") { metronome.tap() }
                Divider()
                Button("Next in Setlist") { app.stepSetlist(1) }.keyboardShortcut("]", modifiers: .command).disabled(setlists.active == nil)
                Button("Previous in Setlist") { app.stepSetlist(-1) }.keyboardShortcut("[", modifiers: .command).disabled(setlists.active == nil)
            }
        }

        // ⌘, : audio output, MIDI, menu bar
        Settings {
            SettingsView()
                .environmentObject(metronome)
                .environmentObject(app)
        }

        MenuBarExtra(isInserted: $menuBar) {
            MenuBarPanel()
                .environmentObject(metronome)
                .environmentObject(setlists)
                .environmentObject(app)
        } label: {
            MenuBarIcon().environmentObject(metronome)
        }
        .menuBarExtraStyle(.window)
    }

    private func setUp() {
        SongsRemoval.run()                   // once: delete what the removed Songs feature left
        app.connect(metronome, practice, setlists)
        _ = AudioOutput.shared               // point the engine at the chosen output
        NowPlaying.install(app)
        if Midi.shared.everUsed { Midi.shared.start(app) }   // no MIDI work at all until you use it
        KeyShortcuts.install(app)
    }
}

/**
 * Single-key shortcuts: Space start/stop, ↑/↓ tempo (Shift: ±10), T tap tempo
 * (or a timing-check tap on Practice), Page Down / Page Up through a setlist.
 *
 * LEARN: a "local event monitor" sees every key press in our app before the
 * window does. We skip it while a text field is being typed in (its "field
 * editor" is an NSTextView), so Space still types a space there.
 */
@MainActor
enum KeyShortcuts {
    private static var installed = false

    static func install(_ app: AppState) {
        guard !installed else { return }
        installed = true
        NSEvent.addLocalMonitorForEvents(matching: .keyDown) { e in
            if NSApp.keyWindow?.firstResponder is NSTextView { return e }
            let mods = e.modifierFlags.intersection([.command, .option, .control])
            if !mods.isEmpty { return e }
            guard let m = app.metronome else { return e }
            let shift = e.modifierFlags.contains(.shift)
            switch e.keyCode {
            case 49: if e.isARepeat { return nil }; app.togglePlay()          // Space
            case 126: m.nudge(shift ? 10 : 1)                                  // ↑
            case 125: m.nudge(shift ? -10 : -1)                                // ↓
            case 121: if app.setlistActive { app.stepSetlist(1) } else { return e }    // Page Down
            case 116: if app.setlistActive { app.stepSetlist(-1) } else { return e }   // Page Up
            case 17:                                                           // T
                if e.isARepeat { return nil }
                if app.section == .practice, let p = app.practice, p.tapsWanted {
                    p.tap(eventTimeNs(e))
                } else { m.tap() }
            default: return e
            }
            return nil // handled: don't also pass it on
        }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    // Closing the window quits the app (no invisible app left running),
    // unless you asked it to stay in the menu bar.
    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        let d = UserDefaults.standard
        let menuBar = d.object(forKey: "menuBar") as? Bool ?? true
        return !(menuBar && d.bool(forKey: "menuBarKeepRunning"))
    }

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Don't save window state to ~/Library/Saved Application State (fewer leftover files).
        UserDefaults.standard.set(false, forKey: "NSQuitAlwaysKeepsWindows")
        Benchmark.launchProbe()
    }
}

/** The app's colours: plain grey surfaces with one teal accent, like the phone app. */
extension Color {
    static let mkTeal = Color(red: 0.11, green: 0.62, blue: 0.46)
    static let mkTealDark = Color(red: 0.06, green: 0.43, blue: 0.34)
    static let mkTealLight = Color(red: 0.62, green: 0.88, blue: 0.80)
}
