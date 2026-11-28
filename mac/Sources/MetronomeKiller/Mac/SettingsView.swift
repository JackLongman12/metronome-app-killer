import SwiftUI

/**
 * Metronome Killer → Settings… (⌘,): audio output, beat flash and the menu bar.
 *
 * LEARN: a SwiftUI "Settings" scene is the standard Mac preferences window;
 * macOS adds the menu item and the ⌘, shortcut by itself.
 */
struct SettingsView: View {
    var body: some View {
        TabView {
            AudioSettings().tabItem { Label("Audio", systemImage: "speaker.wave.2") }
            GeneralSettings().tabItem { Label("General", systemImage: "gearshape") }
        }
        .frame(width: 520)
        .padding(.vertical, 6)
    }
}

// ── Audio ──────────────────────────────────────────────────────────────────

private struct AudioSettings: View {
    @ObservedObject private var out = AudioOutput.shared
    @EnvironmentObject private var m: Metronome

    var body: some View {
        Form {
            Picker("Play through", selection: Binding(get: { out.uid }, set: { out.choose(uid: $0, channel: 0) })) {
                Text("The Mac's default output").tag("")
                ForEach(out.devices) { d in Text("\(d.name)  (\(d.channels) ch)").tag(d.uid) }
                if !out.uid.isEmpty && out.chosen == nil { Text("Not connected").tag(out.uid) }
            }
            if !out.uid.isEmpty && out.chosen == nil {
                Text("That device isn't connected, so the Mac's default output is used. It switches back when you plug it in.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if let d = out.chosen, d.channels > 2 {
                Picker("Metronome click on", selection: Binding(get: { out.channel }, set: { out.choose(uid: d.uid, channel: $0) })) {
                    ForEach(AudioOutput.pairs(d.channels)) { Text($0.label).tag($0.first) }
                }
                Text("On an audio interface, put the click on the outputs that feed only your headphones (often 3–4), so it never goes to the speakers or the recording.")
                    .font(.caption).foregroundStyle(.secondary)
            }
            if m.latencyMs > 0 {
                Text(String(format: "Sound reaches the output %.1f ms after each beat is made.", m.latencyMs))
                    .font(.caption).foregroundStyle(.secondary)
            }
            Button("Look for devices again") { out.refresh() }
        }
        .formStyle(.grouped)
        .onAppear { out.refresh() }
    }
}

// ── General: beat flash and menu bar ───────────────────────────────────────────────────────────────

private struct GeneralSettings: View {
    @AppStorage("menuBar") private var menuBar = true
    @AppStorage("menuBarKeepRunning") private var keepRunning = false
    @AppStorage("flash") private var flash = false
    @AppStorage("flashAccentsOnly") private var accentsOnly = false

    var body: some View {
        Form {
            Toggle("Flash the window on each beat", isOn: $flash)
            Toggle("Accents only", isOn: $accentsOnly).disabled(!flash)
            Text("Handy on a loud stage or with earplugs. Muted beats don't flash.")
                .font(.caption).foregroundStyle(.secondary)
            Toggle("Show the metronome in the menu bar", isOn: $menuBar)
            Toggle("Keep running in the menu bar when the window is closed", isOn: $keepRunning).disabled(!menuBar)
            Text("Otherwise closing the window quits the app, so nothing is left running.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
    }
}
