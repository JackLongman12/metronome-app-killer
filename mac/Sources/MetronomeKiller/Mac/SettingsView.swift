import SwiftUI

/**
 * Metronome Killer → Settings… (⌘,): audio output, MIDI, and the menu bar.
 *
 * LEARN: a SwiftUI "Settings" scene is the standard Mac preferences window;
 * macOS adds the menu item and the ⌘, shortcut by itself.
 */
struct SettingsView: View {
    var body: some View {
        TabView {
            AudioSettings().tabItem { Label("Audio", systemImage: "speaker.wave.2") }
            MidiSettings().tabItem { Label("MIDI", systemImage: "pianokeys") }
            GeneralSettings().tabItem { Label("Menu bar", systemImage: "menubar.rectangle") }
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
            Text("Songs play through the same device, on its first two channels.").font(.caption).foregroundStyle(.secondary)
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

// ── MIDI ───────────────────────────────────────────────────────────────────

private struct MidiSettings: View {
    @ObservedObject private var midi = Midi.shared
    @EnvironmentObject private var app: AppState

    var body: some View {
        Form {
            SwiftUI.Section(header: Text("Sync")) { MidiSyncRows(midi: midi) }
            SwiftUI.Section(header: Text("Pedals and controllers")) { MidiPedalRows(midi: midi) }
        }
        .formStyle(.grouped)
        .onAppear { midi.start(app) }
    }
}

private struct MidiSyncRows: View {
    @ObservedObject var midi: Midi

    var body: some View {
        Toggle("Send MIDI clock", isOn: $midi.sendClock)
        Text(Self.sendHelp).font(.caption).foregroundStyle(.secondary)
        Toggle("Follow another app's MIDI clock", isOn: $midi.followClock)
        if midi.followClock {
            Text(incoming).font(.caption).foregroundStyle(.secondary)
        }
        Toggle("Start and stop with the other app", isOn: $midi.followStartStop)
    }

    private static let sendHelp = "Logic, Ableton, GarageBand or a drum machine can follow the metronome's tempo, start and stop. Choose \u{201C}Metronome Killer\u{201D} as the MIDI clock source there."

    private var incoming: String {
        midi.incomingBpm > 0 ? String(format: "Receiving %.1f BPM (quarter notes)", midi.incomingBpm) : "No clock coming in yet."
    }
}

private struct MidiPedalRows: View {
    @ObservedObject var midi: Midi

    var body: some View {
        ForEach(Midi.Action.allCases) { a in MidiActionRow(midi: midi, action: a) }
        Text(Self.help).font(.caption).foregroundStyle(.secondary)
        Text(status).font(.caption).foregroundStyle(.secondary)
    }

    private static let help = "Click Learn, then press the pedal, pad or key. Footswitches that type keys instead of sending MIDI already work: Space starts and stops, Page Down / Page Up step through a setlist."

    private var status: String {
        let n = midi.sourceCount
        let base = "Listening to \(n) MIDI source" + (n == 1 ? "" : "s")
        return midi.lastSeen.isEmpty ? base : base + " · last: " + midi.lastSeen
    }
}

private struct MidiActionRow: View {
    @ObservedObject var midi: Midi
    let action: Midi.Action

    var body: some View {
        let learned: String = midi.mappings[action]?.label ?? "—"
        let waiting: Bool = midi.learning == action
        HStack {
            Text(action.label)
            Spacer()
            Text(learned).foregroundStyle(.secondary)
            Button(waiting ? "Press it now…" : "Learn") { midi.learn(action) }
            if midi.mappings[action] != nil {
                Button { midi.forget(action) } label: { Image(systemName: "xmark") }
                    .buttonStyle(.borderless)
                    .help("Forget")
            }
        }
    }
}

// ── Menu bar ───────────────────────────────────────────────────────────────

private struct GeneralSettings: View {
    @AppStorage("menuBar") private var menuBar = true
    @AppStorage("menuBarKeepRunning") private var keepRunning = false

    var body: some View {
        Form {
            Toggle("Show the metronome in the menu bar", isOn: $menuBar)
            Toggle("Keep running in the menu bar when the window is closed", isOn: $keepRunning).disabled(!menuBar)
            Text("Otherwise closing the window quits the app, so nothing is left running.")
                .font(.caption).foregroundStyle(.secondary)
        }
        .formStyle(.grouped)
    }
}
