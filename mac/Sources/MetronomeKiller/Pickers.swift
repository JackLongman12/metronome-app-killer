import SwiftUI

/** Time signature popover: ready-made groups plus your own top and bottom numbers. */
struct MeterPicker: View {
    @EnvironmentObject private var m: Metronome
    @State private var top: Double = 4
    @State private var bottom: Double = 4

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 14) {
                group("Common", TimeSignaturePreset.common)
                group("Odd groupings", TimeSignaturePreset.additive)
                group("Decimal (last beat cut short)", TimeSignaturePreset.decimal)
                group("Irrational (unusual beat lengths)", TimeSignaturePreset.irrational)

                Divider()
                Text("Your own").font(.subheadline).foregroundStyle(.secondary)
                HStack {
                    Stepper(value: $top, in: 0.25...16, step: 0.25) { Text("Top \(Meter.fmt(top))").monospacedDigit() }
                    Stepper(value: $bottom, in: 1...32, step: 1) { Text("Bottom \(Meter.fmt(bottom))").monospacedDigit() }
                    Spacer()
                    Button("Use \(Meter.fmt(top))/\(Meter.fmt(bottom))") {
                        m.setTimeSignature(Meter(numerator: top, denominator: bottom))
                    }
                }
            }
            .padding(16)
        }
        .frame(width: 420, height: 440)
        .onAppear { top = m.settings.meter.numerator; bottom = m.settings.meter.denominator }
    }

    private func group(_ title: String, _ presets: [TimeSignaturePreset]) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title).font(.subheadline).foregroundStyle(.secondary)
            LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: 6), count: 4), spacing: 6) {
                ForEach(presets) { p in
                    let on = m.settings.meter == p.meter && m.settings.beats == p.pattern()
                    Button(p.label) { m.setTimeSignature(p.meter, pattern: p.pattern()) }
                        .buttonStyle(.bordered)
                        .tint(on ? Color.mkTeal : nil)
                }
            }
        }
    }
}

/** Sound popover: click a sound to hear it and use it. */
struct SoundPicker: View {
    @EnvironmentObject private var m: Metronome

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            section("Electronic", ClickSound.allCases.filter { !$0.recorded })
            section("Recorded drum kit", ClickSound.allCases.filter(\.recorded))
        }
        .padding(12)
        .frame(width: 240)
    }

    private func section(_ title: String, _ sounds: [ClickSound]) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            Text(title).font(.caption).foregroundStyle(Color.mkTeal).padding(.vertical, 4)
            ForEach(sounds) { s in
                Button {
                    m.setSound(s)
                    m.preview(s)
                } label: {
                    HStack {
                        Image(systemName: "checkmark").opacity(m.settings.sound == s ? 1 : 0).foregroundStyle(Color.mkTeal)
                        Text(s.label)
                        Spacer()
                    }
                    .padding(.vertical, 5)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
            }
        }
    }
}
