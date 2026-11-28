import Combine
import SwiftUI

/**
 * Which page is showing, and one place that knows what "play", "next" and
 * "previous" mean right now. The keyboard, the menu bar, media keys and MIDI
 * pedals all come through here, so they always do the same thing.
 */
@MainActor
final class AppState: ObservableObject {
    @Published var section: ContentView.Section = .metronome

    // Set once at launch (see App.swift). LEARN: "weak" because the app owns
    // these objects; AppState only points at them.
    weak var metronome: Metronome?
    weak var practice: Practice?
    weak var setlists: SetlistStore?

    private var watch: [AnyCancellable] = []

    func connect(_ m: Metronome, _ p: Practice, _ s: SetlistStore) {
        metronome = m; practice = p; setlists = s
        watch = [
            // Media keys and Control Center show the metronome while it plays.
            m.$playing.removeDuplicates().sink { [weak m] playing in
                Task { @MainActor in
                    guard let m else { return }
                    NowPlaying.metronome(bpm: m.settings.bpm, playing: playing)
                }
            },
            // MIDI clock ticks per beat depend on the time signature's bottom number.
            m.$settings.map(\.meter).removeDuplicates().sink { meter in
                Task { @MainActor in Midi.shared.applyClock(meter: meter) }
            },
        ]
    }

    var isPlaying: Bool { metronome?.playing ?? false }

    /** Space, the play/pause key, a MIDI pedal: a practice tool on Practice, else the metronome. */
    func togglePlay() {
        if section == .practice, let practice { practice.toggle(); return }
        metronome?.togglePlay()
    }

    // ── Setlists ────────────────────────────────────────────────────────────

    /** Load item [index] of setlist [list] into the metronome and show it. */
    func playSetItem(_ list: String, _ index: Int) {
        guard let setlists, let l = setlists.get(list), l.items.indices.contains(index) else { return }
        setlists.active = (list, index)
        if practice?.running != nil { practice?.stop() }
        metronome?.apply(l.items[index].settings)
        section = .metronome
    }

    /** Next (+1) or previous (−1) item of the setlist you're playing through. */
    func stepSetlist(_ by: Int) {
        guard let a = setlists?.active else { return }
        playSetItem(a.list, a.index + by)
    }

    var setlistActive: Bool { setlists?.active != nil }
}
