import SwiftUI

/** The main metronome screen: beats, BPM, tempo slider, settings and Start. */
struct MetronomeView: View {
    @EnvironmentObject private var m: Metronome
    @State private var showMeter = false
    @State private var showSound = false
    @State private var typingBpm = false
    @State private var typedBpm = ""
    @FocusState private var bpmFocused: Bool

    var body: some View {
        let s = m.settings
        VStack(spacing: 22) {
            BeatRow(beats: s.beats, current: m.currentBeat) { m.cycleBeat($0) }
                .frame(maxWidth: 480)

            HStack(spacing: 24) {
                RoundButton(symbol: "minus") { m.nudge(-1) }
                VStack(spacing: 2) {
                    Text("\(s.bpm)")
                        .font(.system(size: 72, weight: .medium, design: .rounded))
                        .monospacedDigit()
                        .contentShape(Rectangle())
                        .onTapGesture { typedBpm = "\(s.bpm)"; typingBpm = true }
                        .help("Click to type a tempo")
                        .popover(isPresented: $typingBpm, arrowEdge: .bottom) { bpmEntry }
                    Text("BPM · \(MetronomeSettings.tempoName(s.bpm))").foregroundStyle(.secondary)
                }
                .frame(minWidth: 150)
                RoundButton(symbol: "plus") { m.nudge(1) }
            }

            Slider(
                value: Binding(get: { Double(s.bpm) }, set: { m.setBpm(Int($0.rounded())) }),
                in: Double(MetronomeSettings.minBpm)...Double(MetronomeSettings.maxBpm)
            )
            .tint(.mkTeal)
            .frame(maxWidth: 480)

            HStack(spacing: 10) {
                SettingTile(title: "Time", value: s.meter.label) { showMeter = true }
                    .popover(isPresented: $showMeter, arrowEdge: .bottom) { MeterPicker() }
                Menu {
                    ForEach(Subdivision.allCases) { sub in
                        Button(sub.label) { m.setSubdivision(sub) }
                    }
                } label: {
                    TileLabel(title: "Subdivision", value: s.subdivision.label)
                }
                .menuStyle(.borderlessButton)
                .menuIndicator(.hidden)
                SettingTile(title: "Sound", value: s.sound.label) { showSound = true }
                    .popover(isPresented: $showSound, arrowEdge: .bottom) { SoundPicker() }
            }
            .frame(maxWidth: 480)

            HStack(spacing: 10) {
                Image(systemName: "speaker.fill").foregroundStyle(.secondary)
                Slider(value: Binding(get: { Double(s.volume) }, set: { m.setVolume(Float($0)) }), in: 0...1.5)
                    .tint(.mkTeal)
                Image(systemName: "speaker.wave.3.fill").foregroundStyle(.secondary)
            }
            .frame(maxWidth: 300)

            HStack(spacing: 12) {
                Button("Tap") { m.tap() }.controlSize(.large)
                Button { m.togglePlay() } label: {
                    Label(m.playing ? "Stop" : "Start", systemImage: m.playing ? "stop.fill" : "play.fill")
                        .frame(minWidth: 90)
                }
                .controlSize(.large)
                .buttonStyle(.borderedProminent)
                .tint(.mkTealDark)
            }
        }
        .padding(28)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }

    /**
     * The little box that opens when you click the BPM. Return sets it, Escape closes.
     * LEARN: @FocusState lets us put the cursor in the box as soon as it opens.
     */
    private var bpmEntry: some View {
        VStack(alignment: .leading, spacing: 6) {
            TextField("BPM", text: $typedBpm)
                .textFieldStyle(.roundedBorder).frame(width: 110).monospacedDigit()
                .focused($bpmFocused)
                .onSubmit {
                    if let n = Int(typedBpm.filter(\.isNumber)) { m.setBpm(n) }
                    typingBpm = false
                }
            Text("\(MetronomeSettings.minBpm)–\(MetronomeSettings.maxBpm) BPM").font(.caption).foregroundStyle(.secondary)
        }
        .padding(12)
        .onAppear { bpmFocused = true }
    }
}

/** One block per beat; filled segments show its level. Click to change it. */
struct BeatRow: View {
    let beats: [BeatState]
    let current: Int
    let onTap: (Int) -> Void

    var body: some View {
        HStack(spacing: 6) {
            ForEach(Array(beats.enumerated()), id: \.offset) { i, b in
                VStack(spacing: 2) {
                    ForEach((0..<3).reversed(), id: \.self) { seg in
                        RoundedRectangle(cornerRadius: 3)
                            .fill(seg < b.level ? beatColor(i) : Color.secondary.opacity(0.12))
                            .frame(height: 14)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { onTap(i) }
                .help("Click to change: normal → accent → strong → mute")
            }
        }
    }

    private func beatColor(_ i: Int) -> Color { i == current ? .mkTeal : .mkTealLight }
}

struct RoundButton: View {
    let symbol: String
    let action: () -> Void
    var body: some View {
        Button(action: action) {
            Image(systemName: symbol).font(.title3).frame(width: 40, height: 40)
        }
        .buttonStyle(.plain)
        .background(Circle().stroke(Color.secondary.opacity(0.35)))
    }
}

struct TileLabel: View {
    let title: String
    let value: String
    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(title).font(.caption).foregroundStyle(.secondary)
            Text(value).lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 12)
        .padding(.vertical, 8)
        .background(RoundedRectangle(cornerRadius: 8).stroke(Color.secondary.opacity(0.25)))
        .contentShape(Rectangle())
    }
}

struct SettingTile: View {
    let title: String
    let value: String
    let action: () -> Void
    var body: some View {
        Button(action: action) { TileLabel(title: title, value: value) }.buttonStyle(.plain)
    }
}
