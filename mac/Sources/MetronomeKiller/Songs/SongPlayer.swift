import AVFoundation
import Foundation
import SwiftUI

/**
 * Plays a song with a click track, perfectly in time (the Mac version of SongPlayer.kt).
 *
 * LEARN — how the click stays locked to the song: AVAudioEngine is a little
 * studio inside macOS. Two "player" nodes feed one mixer: one streams the song
 * from disk (so a 15-minute song doesn't fill your memory), the other plays the
 * click sounds. Both are started at the same instant, and every click is booked
 * at an exact sample position ("this click at sample 1 234 567"). They then go
 * through ONE speed changer (time-pitch) together, so slowing the song down
 * slows the click with it, and they leave the speakers along the same path.
 */
@MainActor
final class SongPlayer: ObservableObject {
    @Published private(set) var position: Double = 0
    @Published private(set) var playing = false
    @Published private(set) var speed: Float = 1
    @Published var clickOn = true { didSet { applyLevels() } }
    /** A stretch (start, end in seconds) that repeats while playing, or nil. */
    @Published private(set) var loop: (start: Double, end: Double)?
    /** How many times the current loop has gone round (0 on the first pass). */
    @Published private(set) var loopRepeats = 0
    @Published var songLevel: Float = 1 { didSet { applyLevels() } }
    @Published var clickLevel: Float = 1 { didSet { applyLevels() } }
    /** Shown in Control Center's Now Playing. */
    var title = ""

    private let engine = AVAudioEngine()
    private let songNode = AVAudioPlayerNode()
    private let clickNode = AVAudioPlayerNode()
    private let bus = AVAudioMixerNode()
    private let timePitch = AVAudioUnitTimePitch()

    private var file: AVAudioFile?
    private var map: TempoMap?
    private var accentBuf: AVAudioPCMBuffer?
    private var normalBuf: AVAudioPCMBuffer?
    private var startPos: Double = 0       // song position where the current play began
    private var offset: Double = 0         // per-song timing nudge, seconds
    private var skips: [[Double]] = []
    private var ticker: Timer?
    private var generation = 0             // ignores "finished" callbacks from earlier plays

    var duration: Double { file.map { Double($0.length) / $0.processingFormat.sampleRate } ?? 0 }

    init() {
        engine.attach(songNode)
        engine.attach(clickNode)
        engine.attach(bus)
        engine.attach(timePitch)
    }

    func load(path: String, map: TempoMap, offsetMs: Int, skips: [[Double]]) throws {
        stop()
        let f = try AVAudioFile(forReading: URL(fileURLWithPath: path))
        file = f
        self.map = map
        self.skips = skips
        offset = Double(offsetMs) / 1000
        let fmt = f.processingFormat
        AudioOutput.shared.applyTo(engine) // the device picked in Settings → Audio, if any
        // LEARN: connections carry a format (sample rate and channels). Using the
        // song's own format everywhere means nothing gets converted before mixing.
        engine.disconnectNodeOutput(songNode)
        engine.disconnectNodeOutput(clickNode)
        engine.connect(songNode, to: bus, format: fmt)
        engine.connect(clickNode, to: bus, format: fmt)
        engine.connect(bus, to: timePitch, format: fmt)
        engine.connect(timePitch, to: engine.mainMixerNode, format: fmt)
        accentBuf = Self.click(freq: 1760, gain: 0.55, format: fmt)
        normalBuf = Self.click(freq: 1320, gain: 0.42, format: fmt)
        position = 0
        startPos = 0
        applyLevels()
    }

    /** Replace the click track (switching AI / tab / your edits). */
    func setMap(_ m: TempoMap) {
        map = m
        if playing { restart(at: currentTime()) }
    }

    func setOffset(ms: Int) {
        offset = Double(ms) / 1000
        if playing { restart(at: currentTime()) }
    }

    func toggle() { playing ? pause() : play() }

    func play() {
        guard file != nil else { return }
        restart(at: position >= duration - 0.05 ? 0 : position)
    }

    func pause() {
        position = currentTime()
        halt()
        playing = false
        tellNowPlaying()
    }

    func stop() {
        let was = playing
        halt()
        playing = false
        engine.stop()
        if was { tellNowPlaying() }
    }

    private func tellNowPlaying() {
        NowPlaying.song(title: title, playing: playing, position: position, duration: duration, rate: speed)
    }

    func seek(to sec: Double) {
        let s = sec.clamped(0, max(duration - 0.01, 0))
        if playing { restart(at: s) } else { position = s }
    }

    /** Practice speed 0.5×–1.0×, pitch stays the same. */
    func setSpeed(_ f: Float) {
        speed = f.clamped(0.5, 1)
        timePitch.rate = speed
        if playing { tellNowPlaying() }
    }

    func setLoop(_ start: Double, _ end: Double) {
        loop = (start, end)
        loopRepeats = 0
        let now = currentTime()
        if now < start || now >= end { seek(to: start) }
    }

    func clearLoop() { loop = nil }

    // ── The engine work ─────────────────────────────────────────────────────

    private func halt() {
        generation += 1
        ticker?.invalidate(); ticker = nil
        songNode.stop()
        clickNode.stop()
    }

    /** (Re)starts both players from song position [sec], with clicks booked from there on. */
    private func restart(at sec: Double) {
        guard let file, let map else { return }
        halt()
        if !engine.isRunning { try? engine.start() }
        let rate = file.processingFormat.sampleRate
        let first = AVAudioFramePosition(sec * rate)
        let count = AVAudioFrameCount(max(file.length - first, 0))
        guard count > 0 else { position = duration; playing = false; return }

        let gen = generation
        songNode.scheduleSegment(file, startingFrame: first, frameCount: count, at: nil) { [weak self] in
            Task { @MainActor in
                guard let self, self.generation == gen else { return }
                self.position = self.duration; self.halt(); self.playing = false
            }
        }
        // Book every click from here to the end at its exact sample.
        let down = Set(map.downbeats)
        for t in map.beats where t + offset >= sec - 0.001 {
            guard let buf = down.contains(t) ? accentBuf : normalBuf else { continue }
            let at = AVAudioFramePosition((t + offset - sec) * rate)
            clickNode.scheduleBuffer(buf, at: AVAudioTime(sampleTime: max(at, 0), atRate: rate), options: [])
        }
        // Start both together, a few ms from now, so neither is late.
        let start = AVAudioTime(hostTime: mach_absolute_time() + AVAudioTime.hostTime(forSeconds: 0.02))
        songNode.play(at: start)
        clickNode.play(at: start)
        startPos = sec
        position = sec
        playing = true
        startTicker()
        tellNowPlaying()
    }

    /** Song position now: where we started plus how far the song player has got. */
    private func currentTime() -> Double {
        guard playing, let file, let nodeTime = songNode.lastRenderTime,
              let t = songNode.playerTime(forNodeTime: nodeTime), t.sampleTime >= 0 else { return position }
        return startPos + Double(t.sampleTime) / file.processingFormat.sampleRate
    }

    /** ~30 times a second: update the position, jump over skips, wrap loops. Only while playing. */
    private func startTicker() {
        let t = Timer(timeInterval: 1.0 / 30, repeats: true) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.playing else { return }
                let now = self.currentTime()
                if let s = self.skips.first(where: { now >= $0[0] && now < $0[1] - 0.25 }) { self.restart(at: s[1]); return }
                if let l = self.loop, now >= l.end - 0.02 { self.loopRepeats += 1; self.restart(at: l.start); return }
                self.position = now
            }
        }
        RunLoop.main.add(t, forMode: .common)
        ticker = t
    }

    private func applyLevels() {
        songNode.volume = songLevel
        clickNode.volume = clickOn ? clickLevel : 0
    }

    /** A short decaying tone, like the Android song click. */
    private static func click(freq: Double, gain: Float, format: AVAudioFormat) -> AVAudioPCMBuffer? {
        let rate = format.sampleRate
        let n = AVAudioFrameCount(rate * 0.04)
        guard let buf = AVAudioPCMBuffer(pcmFormat: format, frameCapacity: n), let ch = buf.floatChannelData else { return nil }
        buf.frameLength = n
        for i in 0..<Int(n) {
            let t = Double(i) / rate
            let v = Float(sin(2 * .pi * freq * t) * exp(-t / 0.012)) * gain
            for c in 0..<Int(format.channelCount) { ch[c][i] = v }
        }
        return buf
    }
}
