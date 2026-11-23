import SwiftUI

/**
 * Which page is showing, and the song player if a song is open. The menu
 * shortcuts read this so Space plays the song on the song page and the
 * metronome everywhere else.
 */
@MainActor
final class AppState: ObservableObject {
    @Published var section: ContentView.Section = .metronome
    weak var songPage: SongPageControls?
}

/** What the keyboard shortcuts can do on an open song page. */
@MainActor
protocol SongPageControls: AnyObject {
    func togglePlay()
    func jumpBars(_ by: Int)
}

/** One song: part rows (or the full bar grid), play with a click, loop, speed, edit. */
struct SongView: View {
    let song: Song
    let onBack: () -> Void

    @EnvironmentObject private var library: SongLibrary
    @EnvironmentObject private var app: AppState
    @StateObject private var player = SongPlayer()
    @StateObject private var keys = SongKeys()
    @AppStorage("partRows") private var partRows = true
    @State private var editing = false
    @State private var selection: Range<Int>?
    @State private var anchor: Int?
    @State private var undo: [TempoMap] = []
    @State private var ramp = false
    @State private var renaming: PartRow?
    @State private var showMeter = false
    @State private var showMix = false
    @State private var loadError: String?

    private static let rampStart: Float = 0.7
    private static let rampStep: Float = 0.05

    var body: some View {
        let map = song.map ?? TempoMap(0, 0, [], [], [], [])
        let current = currentBar(map)
        VStack(spacing: 0) {
            header(map, current)
            Divider()
            ScrollViewReader { proxy in
                ScrollView {
                    if partRows {
                        PartRowsGrid(rows: PartRows.build(map, yourNames: song.rowNames), bars: map.bars, current: current,
                                     selection: editing ? selection : nil, usual: PartRows.usualMeter(map),
                                     onBar: tapBar, onRename: { renaming = $0 }, onLoop: loopRow)
                            .padding(16)
                    } else {
                        BarGrid(bars: map.bars, current: current, selection: editing ? selection : nil, onBar: tapBar).padding(16)
                    }
                }
                .onChange(of: current) { c in withAnimation(.easeOut(duration: 0.2)) { proxy.scrollTo(c, anchor: .center) } }
            }
            Divider()
            if editing { editBar(map) }
            transport(map)
        }
        .onAppear(perform: setUp)
        .onDisappear { player.stop(); app.songPage = nil }
        .onChange(of: song.map) { m in if let m { player.setMap(m) } }
        .onChange(of: song.offsetMs) { player.setOffset(ms: $0) }
        .onChange(of: player.loopRepeats) { n in
            if ramp, player.loop != nil { player.setSpeed(min(Self.rampStart + Self.rampStep * Float(n), 1)) }
        }
        .sheet(item: $renaming) { row in
            RenameSheet(row: row) { library.renameRow(song.id, firstBar: row.first, name: $0) }
        }
        .sheet(isPresented: $showMeter) {
            MeterSheet { beats, note in apply(MapEditor.setMeter(map, sel(map).lowerBound, sel(map).upperBound, beats: beats, noteValue: note)) }
        }
    }

    // ── Header ──────────────────────────────────────────────────────────────

    private func header(_ map: TempoMap, _ current: Int) -> some View {
        HStack(spacing: 12) {
            Button(action: onBack) { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
            VStack(alignment: .leading, spacing: 1) {
                Text(song.title).font(.headline).lineLimit(1)
                Text(nowText(map, current)).font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
            Spacer()
            if song.tabInfo != nil || song.hasEdit {
                Picker("", selection: Binding(get: { song.usingEdit ? MapSource.edit : (song.usingTab ? .tab : .ai) },
                                              set: { library.useMap(song.id, $0) })) {
                    Text("AI").tag(MapSource.ai)
                    if song.tabInfo != nil { Text("Tab").tag(MapSource.tab) }
                    if song.hasEdit { Text("Yours").tag(MapSource.edit) }
                }
                .pickerStyle(.segmented).frame(width: song.tabInfo != nil && song.hasEdit ? 180 : 120)
                .help(song.tabInfo ?? "")
            }
            Picker("", selection: $partRows) {
                Text("Rows").tag(true)
                Text("Grid").tag(false)
            }
            .pickerStyle(.segmented).frame(width: 110)
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    private func nowText(_ map: TempoMap, _ current: Int) -> String {
        let pos = player.position
        let sec = map.sections.first { pos >= $0.start && pos < $0.end } ?? map.sections.first
        let bpm = sec.map { $0.kind == .FREE ? "free time" : "\(Int($0.bpm.rounded())) BPM" } ?? ""
        let meter = map.bars.indices.contains(current) ? map.bars[current].meter : (sec?.meter ?? "")
        return "Bar \(current + 1) · \(bpm) · \(meter)" + (sec?.kind == .RAMP ? " · changing tempo" : "")
    }

    // ── Transport ───────────────────────────────────────────────────────────

    private func transport(_ map: TempoMap) -> some View {
        HStack(spacing: 10) {
            Button { player.toggle() } label: {
                Image(systemName: player.playing ? "pause.fill" : "play.fill").frame(width: 28)
            }
            .buttonStyle(.borderedProminent).tint(.mkTealDark)
            Button { keys.jumpBars(-1) } label: { Image(systemName: "backward.end.fill") }.buttonStyle(.borderless)
            Button { keys.jumpBars(1) } label: { Image(systemName: "forward.end.fill") }.buttonStyle(.borderless)
            Text("\(time(player.position)) / \(time(player.duration))").font(.caption).monospacedDigit().foregroundStyle(.secondary)
            Slider(value: Binding(get: { player.position }, set: { player.seek(to: $0) }), in: 0...max(player.duration, 1))
                .tint(.mkTeal).frame(minWidth: 120)

            if let l = player.loop {
                Button {
                    player.clearLoop(); ramp = false; player.setSpeed(1)
                } label: { Label("Looping \(time(l.start))–\(time(l.end)) · \(player.loopRepeats)×", systemImage: "xmark") }
                Toggle("Speed up", isOn: $ramp)
                    .toggleStyle(.checkbox)
                    .help("Starts at 70 % speed and adds 5 % every time the loop goes round, up to full speed.")
                    .onChange(of: ramp) { on in player.setSpeed(on ? Self.rampStart : 1) }
            }
            Menu("\(Int((player.speed * 100).rounded())) %") {
                ForEach([1.0, 0.9, 0.8, 0.75, 0.7, 0.6, 0.5], id: \.self) { s in Button("\(Int(s * 100)) %") { player.setSpeed(Float(s)) } }
            }
            .frame(width: 70)
            Toggle("Click", isOn: $player.clickOn).toggleStyle(.checkbox)
            Button { showMix = true } label: { Image(systemName: "slider.horizontal.3") }
                .buttonStyle(.borderless)
                .popover(isPresented: $showMix) { MixPopover(player: player, song: song) }
            Button(editing ? "Done" : "Edit") {
                editing.toggle()
                if editing { selection = selection ?? 0..<min(map.bars.count, 8) } else { undo = [] }
            }
        }
        .padding(.horizontal, 16).padding(.vertical, 10)
    }

    // ── Edit mode ───────────────────────────────────────────────────────────

    private func editBar(_ map: TempoMap) -> some View {
        let s = sel(map)
        return HStack(spacing: 8) {
            Text(s.count == 1 ? "Bar \(s.lowerBound + 1)" : "Bars \(s.lowerBound + 1)–\(s.upperBound)").font(.callout).monospacedDigit()
            Button("All") { selection = 0..<map.bars.count }
            Divider().frame(height: 16)
            Button("½") { apply(MapEditor.halfTempo(map, s.lowerBound, s.upperBound)) }.help("Half tempo: the AI heard eighth notes as beats")
            Button("×2") { apply(MapEditor.doubleTempo(map, s.lowerBound, s.upperBound)) }.help("Double tempo: the AI heard half notes as beats")
            Button("◀ 1") { apply(MapEditor.shiftDownbeat(map, s.lowerBound, s.upperBound, by: -1)) }.help("Move the 1 one beat earlier")
            Button("1 ▶") { apply(MapEditor.shiftDownbeat(map, s.lowerBound, s.upperBound, by: 1)) }.help("Move the 1 one beat later")
            Button("Meter…") { showMeter = true }
            Spacer()
            Button("Undo") { if let last = undo.popLast() { library.saveEdit(song.id, last) } }.disabled(undo.isEmpty)
                .keyboardShortcut("z", modifiers: .command)
            if song.hasEdit { Button("Discard edits", role: .destructive) { library.discardEdit(song.id); undo = [] } }
        }
        .padding(.horizontal, 16).padding(.vertical, 8)
        .background(Color.mkTeal.opacity(0.08))
    }

    /** The selection, kept inside the map (a switch of map can make it point past the end). */
    private func sel(_ map: TempoMap) -> Range<Int> {
        let n = map.bars.count
        guard n > 0 else { return 0..<0 }
        let s = selection ?? 0..<min(n, 8)
        let a = s.lowerBound.clamped(0, n - 1)
        return a..<s.upperBound.clamped(a + 1, n)
    }

    private func apply(_ new: TempoMap) {
        guard let old = song.map, new != old else { return }
        undo.append(old)
        library.saveEdit(song.id, new)
    }

    // ── Bars ────────────────────────────────────────────────────────────────

    /** Click a bar: jump there. In Edit mode: select it (Shift-click extends the selection). */
    private func tapBar(_ i: Int) {
        guard let map = song.map, map.bars.indices.contains(i) else { return }
        if editing {
            if NSEvent.modifierFlags.contains(.shift), let a = anchor {
                selection = min(a, i)..<(max(a, i) + 1)
            } else { anchor = i; selection = i..<(i + 1) }
        } else {
            player.seek(to: map.bars[i].start)
        }
    }

    private func loopRow(_ row: PartRow) {
        guard let map = song.map else { return }
        let end = row.end < map.bars.count ? map.bars[row.end].start : map.duration
        player.setLoop(map.bars[row.first].start, end)
        if !player.playing { player.play() }
    }

    private func currentBar(_ map: TempoMap) -> Int {
        let p = player.position
        var lo = 0, hi = map.bars.count - 1, ans = 0
        while lo <= hi { let m = (lo + hi) / 2; if map.bars[m].start <= p + 0.01 { ans = m; lo = m + 1 } else { hi = m - 1 } }
        return ans
    }

    private func setUp() {
        guard let map = song.map else { return }
        do { try player.load(path: song.path, map: map, offsetMs: song.offsetMs, skips: song.skips) }
        catch { loadError = error.localizedDescription }
        keys.player = player
        keys.bars = { song.map?.bars ?? [] }
        app.songPage = keys
    }

    private func time(_ s: Double) -> String { String(format: "%d:%02d", Int(s) / 60, Int(s) % 60) }
}

/** Keyboard control for the open song (used by the menu shortcuts). */
@MainActor
final class SongKeys: ObservableObject, SongPageControls {
    weak var player: SongPlayer?
    var bars: () -> [Bar] = { [] }

    func togglePlay() { player?.toggle() }

    /** ← / →: to the start of the previous / next bar. */
    func jumpBars(_ by: Int) {
        guard let p = player else { return }
        let b = bars()
        guard !b.isEmpty else { return }
        let i = b.lastIndex { $0.start <= p.position + 0.05 } ?? 0
        let j = (i + by).clamped(0, b.count - 1)
        p.seek(to: b[j].start)
    }
}

/** Song and click volume, plus the per-song timing nudge. */
struct MixPopover: View {
    @ObservedObject var player: SongPlayer
    let song: Song
    @EnvironmentObject private var library: SongLibrary

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Song volume · \(Int(player.songLevel * 100)) %").font(.caption)
            Slider(value: $player.songLevel, in: 0...1).tint(.mkTeal)
            Text("Click volume · \(Int(player.clickLevel * 100)) %").font(.caption)
            Slider(value: $player.clickLevel, in: 0...2).tint(.mkTeal)
            Text("Click timing · \(song.offsetMs > 0 ? "+" : "")\(song.offsetMs) ms").font(.caption)
            HStack {
                Button("Earlier") { library.setOffset(song.id, song.offsetMs - 10) }
                Button("Reset") { library.setOffset(song.id, 0) }
                Button("Later") { library.setOffset(song.id, song.offsetMs + 10) }
            }
        }
        .padding(14)
        .frame(width: 260)
    }
}

/** Name a row ("Chorus"). Empty = back to the tab's name or the bar range. */
struct RenameSheet: View {
    let row: PartRow
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text("Name \(row.range.lowercased())").font(.headline)
            TextField("Chorus", text: $text).textFieldStyle(.roundedBorder).onSubmit { onSave(text); dismiss() }
            Text("Leave it empty to go back to the default.").font(.caption).foregroundStyle(.secondary)
            HStack { Spacer(); Button("Cancel") { dismiss() }; Button("Save") { onSave(text); dismiss() }.buttonStyle(.borderedProminent).tint(.mkTealDark) }
        }
        .padding(20).frame(width: 340)
        .onAppear { text = row.source == .you ? row.name ?? "" : "" }
    }
}

/** Pick a meter for the selected bars (beats don't move, only the bar lines). */
struct MeterSheet: View {
    let onPick: (Int, Int) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var beats = 4
    @State private var note = 4

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Meter for the selected bars").font(.headline)
            HStack {
                Stepper("Beats per bar: \(beats)", value: $beats, in: 1...16)
                Picker("Note", selection: $note) { ForEach([2, 4, 8, 16], id: \.self) { Text("/\($0)").tag($0) } }.frame(width: 110)
            }
            HStack { Spacer(); Button("Cancel") { dismiss() }; Button("Use \(beats)/\(note)") { onPick(beats, note); dismiss() }.buttonStyle(.borderedProminent).tint(.mkTealDark) }
        }
        .padding(20).frame(width: 360)
    }
}
