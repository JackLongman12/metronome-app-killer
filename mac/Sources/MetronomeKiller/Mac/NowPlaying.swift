import MediaPlayer

/**
 * Media keys (F8 play/pause, F7/F9), AirPods taps, headphone buttons and the
 * Now Playing panel in Control Center.
 *
 * LEARN: macOS sends those buttons to whichever app last said "I'm playing
 * something" through MPNowPlayingInfoCenter. So we tell it what's playing
 *, and MPRemoteCommandCenter calls us back when a
 * button is pressed. It's registered the first time something plays, so the
 * app never grabs your media keys before you've used it.
 */
@MainActor
enum NowPlaying {
    private static var app: AppState?
    private static var installed = false

    static func install(_ a: AppState) { app = a }

    private static func registerCommands() {
        guard !installed else { return }
        installed = true
        let c = MPRemoteCommandCenter.shared()
        // The handlers come in on the main thread; Task hops onto the main actor for Swift's checks.
        c.togglePlayPauseCommand.addTarget { _ in run { $0.togglePlay() } }
        c.playCommand.addTarget { _ in run { if !$0.isPlaying { $0.togglePlay() } } }
        c.pauseCommand.addTarget { _ in run { if $0.isPlaying { $0.togglePlay() } } }
        // ⏭ / ⏮: the next / previous setlist item, if you're playing through one.
        c.nextTrackCommand.addTarget { _ in run { $0.stepSetlist(1) } }
        c.previousTrackCommand.addTarget { _ in run { $0.stepSetlist(-1) } }
    }

    nonisolated private static func run(_ f: @escaping @MainActor (AppState) -> Void) -> MPRemoteCommandHandlerStatus {
        Task { @MainActor in if let app { f(app) } }
        return .success
    }

    /** The metronome started or stopped. */
    static func metronome(bpm: Int, playing: Bool) {
        if playing { registerCommands() }
        guard installed else { return }
        set([
            MPMediaItemPropertyTitle: "Metronome · \(bpm) BPM",
            MPMediaItemPropertyArtist: "Metronome Killer",
        ], playing: playing)
    }

    private static func set(_ info: [String: Any], playing: Bool) {
        let center = MPNowPlayingInfoCenter.default()
        center.nowPlayingInfo = info
        center.playbackState = playing ? .playing : .paused
    }
}
