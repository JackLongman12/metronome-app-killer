import SwiftUI

/**
 * The small metronome in the menu bar: start/stop, tempo and tap without
 * opening the window. Handy while you record in another app.
 *
 * LEARN: MenuBarExtra (macOS 13+) puts an icon next to the clock. With the
 * ".window" style, clicking it opens this little panel instead of a menu.
 * The panel is only drawn while it's open, so it costs nothing otherwise.
 */
struct MenuBarPanel: View {
    @EnvironmentObject private var m: Metronome
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var setlists: SetlistStore
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        let s = m.settings
        VStack(spacing: 12) {
            HStack {
                Text("Metronome").font(.headline)
                Spacer()
                Text("\(s.meter.label) · \(s.subdivision.label)").font(.caption).foregroundStyle(.secondary)
            }
            BeatRow(beats: s.beats, current: m.currentBeat) { m.cycleBeat($0) }
            HStack(spacing: 18) {
                RoundButton(symbol: "minus") { m.nudge(-1) }
                VStack(spacing: 0) {
                    Text("\(s.bpm)").font(.system(size: 40, weight: .medium, design: .rounded)).monospacedDigit()
                    Text("BPM").font(.caption).foregroundStyle(.secondary)
                }
                .frame(minWidth: 80)
                RoundButton(symbol: "plus") { m.nudge(1) }
            }
            Slider(value: Binding(get: { Double(s.bpm) }, set: { m.setBpm(Int($0.rounded())) }),
                   in: Double(MetronomeSettings.minBpm)...Double(MetronomeSettings.maxBpm))
                .tint(.mkTeal)
            HStack {
                Button("Tap") { m.tap() }
                Spacer()
                Button { m.togglePlay() } label: {
                    Label(m.playing ? "Stop" : "Start", systemImage: m.playing ? "stop.fill" : "play.fill").frame(minWidth: 70)
                }
                .buttonStyle(.borderedProminent).tint(.mkTealDark)
            }
            if let a = setlists.active, let list = setlists.get(a.list), list.items.indices.contains(a.index + 1) {
                Button { app.stepSetlist(1) } label: {
                    Text("Next: \(list.items[a.index + 1].name)").lineLimit(1).frame(maxWidth: .infinity)
                }
            }
            Divider()
            HStack {
                Button("Open window") {
                    openWindow(id: "main")
                    NSApp.activate(ignoringOtherApps: true)
                }
                Spacer()
                Button("Quit") { NSApp.terminate(nil) }
            }
            .buttonStyle(.borderless)
            .font(.callout)
        }
        .padding(14)
        .frame(width: 270)
    }
}

/** The menu bar icon: filled while the metronome plays. */
struct MenuBarIcon: View {
    @EnvironmentObject private var m: Metronome
    var body: some View {
        Image(systemName: m.playing ? "metronome.fill" : "metronome")
    }
}
