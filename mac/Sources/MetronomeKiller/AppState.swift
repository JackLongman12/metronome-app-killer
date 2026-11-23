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
    /** The song open on the Songs page (nil = the list). */
    @Published var openSong: String?
    weak var songPage: SongPageControls?

    // Set once at launch (see App.swift). LEARN: "weak" because the app owns
    // these objects; AppState only points at them.
    weak var metronome: Metronome?
    weak var practice: Practice?
    weak var setlists: SetlistStore?
    weak var library: SongLibrary?

    private var watch: [AnyCancellable] = []

    func connect(_ m: Metronome, _ p: Practice, _ s: SetlistStore, _ l: SongLibrary) {
        metronome = m; practice = p; setlists = s; library = l
        // Media keys and Control Center show the metronome while it plays.
        watch = [
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

    /** The song page, if it's the one showing. */
    private var page: SongPageControls? { section == .songs ? songPage : nil }

    var isPlaying: Bool { page?.isPlaying ?? metronome?.playing ?? false }

    /** Space, the play/pause key, a MIDI pedal: the song on the song page, a practice tool on Practice, else the metronome. */
    func togglePlay() {
        if let page { page.togglePlay(); return }
        if section == .practice, let practice { practice.toggle(); return }
        metronome?.togglePlay()
    }

    // ── Setlists ────────────────────────────────────────────────────────────

    /** Load item [index] of setlist [list] and show it. */
    func playSetItem(_ list: String, _ index: Int) {
        guard let setlists, let l = setlists.get(list), l.items.indices.contains(index) else { return }
        setlists.active = (list, index)
        switch l.items[index].kind {
        case .preset(let s):
            if practice?.running != nil { practice?.stop() }
            metronome?.apply(s)
            openSong = nil
            section = .metronome
        case .song(let id):
            // Going on stage from a song to a song: stop the click track first.
            if metronome?.playing == true { metronome?.stop() }
            openSong = library?.song(id)?.map != nil ? id : nil
            section = .songs
        }
    }

    /** Next (+1) or previous (−1) item of the setlist you're playing through. */
    func stepSetlist(_ by: Int) {
        guard let a = setlists?.active else { return }
        playSetItem(a.list, a.index + by)
    }

    var setlistActive: Bool { setlists?.active != nil }
}

/** What the shortcuts, media keys and pedals can do on an open song page. */
@MainActor
protocol SongPageControls: AnyObject {
    func togglePlay()
    func jumpBars(_ by: Int)
    func seek(to sec: Double)
    var isPlaying: Bool { get }
}
