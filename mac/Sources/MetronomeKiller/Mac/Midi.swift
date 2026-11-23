import ClickCore
import Foundation

/**
 * MIDI settings and controls (Settings → MIDI).
 *
 * - Send clock: other apps (Logic, Ableton, GarageBand, drum machines) can
 *   follow the metronome. Pick "Metronome Killer" as their MIDI clock source.
 * - Follow clock: the metronome takes its tempo from another app's MIDI clock,
 *   and can start and stop with it.
 * - Pedals and controllers: press Learn, then the pedal or key, to make it
 *   start/stop, tap the tempo, change the tempo or step through a setlist.
 *
 * The timing-critical part (booking clock ticks) is C++ in Midi.cpp. This side
 * only keeps the settings and turns incoming messages into actions.
 */
@MainActor
final class Midi: ObservableObject {
    enum Action: String, CaseIterable, Identifiable, Codable {
        case startStop, tap, faster, slower, next, previous
        var id: String { rawValue }
        var label: String {
            switch self {
            case .startStop: "Start / stop"
            case .tap: "Tap tempo"
            case .faster: "Faster (+1 BPM)"
            case .slower: "Slower (−1 BPM)"
            case .next: "Next setlist item"
            case .previous: "Previous setlist item"
            }
        }
    }

    /** A learned control: the kind of message, its channel and its number (note, CC or program). */
    struct Trigger: Codable, Equatable {
        let status: UInt8   // kind + channel, e.g. 0x90 = note on, channel 1
        let number: UInt8

        var label: String {
            let ch = Int(status & 0x0F) + 1
            switch status & 0xF0 {
            case 0x90: return "Note \(Trigger.noteName(number)) · ch \(ch)"
            case 0xB0: return "CC \(number) · ch \(ch)"
            case 0xC0: return "Program \(Int(number) + 1) · ch \(ch)"
            default: return String(format: "%02X %d", status, number)
            }
        }

        static func noteName(_ n: UInt8) -> String {
            let names = ["C", "C♯", "D", "D♯", "E", "F", "F♯", "G", "G♯", "A", "A♯", "B"]
            return "\(names[Int(n) % 12])\(Int(n) / 12 - 1)"
        }
    }

    @Published var sendClock = UserDefaults.standard.bool(forKey: "midiSendClock") { didSet { save(); applyClock() } }
    @Published var followClock = UserDefaults.standard.bool(forKey: "midiFollowClock") { didSet { save(); applyClock(); followTimer() } }
    @Published var followStartStop = UserDefaults.standard.bool(forKey: "midiFollowStartStop") { didSet { save() } }
    @Published private(set) var mappings: [Action: Trigger] = [:]
    /** The action waiting for you to press a pedal, or nil. */
    @Published var learning: Action?
    /** The last message that came in, so you can see a pedal works. */
    @Published private(set) var lastSeen = ""
    @Published private(set) var sourceCount = 0
    /** The tempo of the clock coming in (0 = none). */
    @Published private(set) var incomingBpm = 0.0

    static let shared = Midi()

    private weak var app: AppState?
    private var follow: Timer?

    private init() {
        if let d = UserDefaults.standard.data(forKey: "midiMappings"), let m = try? JSONDecoder().decode([Action: Trigger].self, from: d) {
            mappings = m
        }
    }

    /** Starts MIDI. Called at launch only if you've used MIDI before, or when the MIDI settings open. */
    func start(_ app: AppState) {
        self.app = app
        guard mk_midi_open() else { return }
        // LEARN: C code can't call a Swift closure that captures things, so the
        // callback is a plain function that hops onto the main thread and finds
        // Midi.shared there.
        mk_midi_set_input({ _, status, d1, d2, _ in
            Task { @MainActor in Midi.shared.received(status, d1, d2) }
        }, nil)
        applyClock()
        followTimer()
        sourceCount = Int(mk_midi_source_count())
    }

    var everUsed: Bool { sendClock || followClock || followStartStop || !mappings.isEmpty }

    func learn(_ a: Action) { learning = learning == a ? nil : a }

    func forget(_ a: Action) {
        mappings[a] = nil
        saveMappings()
    }

    // ── Incoming ────────────────────────────────────────────────────────────

    private func received(_ status: UInt8, _ d1: UInt8, _ d2: UInt8) {
        sourceCount = Int(mk_midi_source_count())
        let kind = status & 0xF0
        // Start / Continue / Stop from another app
        if status == 0xFA || status == 0xFB || status == 0xFC {
            lastSeen = status == 0xFC ? "Stop" : "Start"
            guard followStartStop, let m = app?.metronome else { return }
            if status == 0xFC { m.stop() } else { m.start() }
            return
        }
        // A "press": note on, a pedal CC going down (64 and up), or a program change.
        let pressed = (kind == 0x90 && d2 > 0) || (kind == 0xB0 && d2 >= 64) || kind == 0xC0
        guard pressed else { return }
        let t = Trigger(status: status, number: d1)
        lastSeen = t.label
        if let a = learning {
            // The same pedal can only do one thing.
            for (k, v) in mappings where v == t { mappings[k] = nil }
            mappings[a] = t
            learning = nil
            saveMappings()
            return
        }
        guard let a = mappings.first(where: { $0.value == t })?.key, let app, let m = app.metronome else { return }
        switch a {
        case .startStop: app.togglePlay()
        case .tap: m.tap()
        case .faster: m.nudge(1)
        case .slower: m.nudge(-1)
        case .next: app.stepSetlist(1)
        case .previous: app.stepSetlist(-1)
        }
    }

    // ── Clock ───────────────────────────────────────────────────────────────

    /**
     * MIDI clock counts quarter notes (24 ticks each). The metronome counts the
     * bottom note of the time signature, so in 6/8 one beat = an eighth = 12 ticks.
     */
    func applyClock(meter: Meter? = nil) {
        guard let m = app?.metronome else { return }
        let den = (meter ?? m.settings.meter).denominator
        // Never send while following: the other app would hear its own tempo come back.
        mk_midi_send_clock(sendClock && !followClock, 96.0 / den)
    }

    /** While following, read the incoming tempo 4 times a second (no timer otherwise). */
    private func followTimer() {
        follow?.invalidate()
        follow = nil
        guard followClock else { incomingBpm = 0; return }
        let t = Timer(timeInterval: 0.25, repeats: true) { _ in
            Task { @MainActor in Midi.shared.followTick() }
        }
        RunLoop.main.add(t, forMode: .common)
        follow = t
    }

    private func followTick() {
        let q = mk_midi_incoming_bpm()
        incomingBpm = q
        guard q > 0, let m = app?.metronome else { return }
        // Quarter notes → the metronome's own beats (irrational meters already count quarters).
        let meter = m.settings.meter
        let bpm = Int((meter.isIrrational ? q : q * meter.denominator / 4).rounded())
        if abs(bpm - m.settings.bpm) >= 1 { m.setBpm(bpm) }
    }

    private func save() {
        UserDefaults.standard.set(sendClock, forKey: "midiSendClock")
        UserDefaults.standard.set(followClock, forKey: "midiFollowClock")
        UserDefaults.standard.set(followStartStop, forKey: "midiFollowStartStop")
    }

    private func saveMappings() {
        if let d = try? JSONEncoder().encode(mappings) { UserDefaults.standard.set(d, forKey: "midiMappings") }
    }
}
