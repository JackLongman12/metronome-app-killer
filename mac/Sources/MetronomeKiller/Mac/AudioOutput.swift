import AVFoundation
import ClickCore
import CoreAudio
import SwiftUI

/**
 * Which speakers or audio interface the app plays through (Settings → Audio).
 *
 * LEARN: macOS gives every device a number (AudioDeviceID) that can change
 * after a restart, and a permanent name-like ID (its "UID"). We save the UID
 * and look the number up again each time the app opens.
 */
@MainActor
final class AudioOutput: ObservableObject {
    struct Device: Identifiable, Hashable {
        let id: AudioDeviceID
        let uid: String
        let name: String
        let channels: Int
    }

    @Published private(set) var devices: [Device] = []
    /** The saved choice: "" = follow the Mac's default output. */
    @Published private(set) var uid = UserDefaults.standard.string(forKey: "outputUID") ?? ""
    /** First of the two channels the metronome click goes to (0 = 1–2, 2 = 3–4…). */
    @Published private(set) var channel = UserDefaults.standard.integer(forKey: "outputChannel")

    static let shared = AudioOutput()

    private init() {
        refresh()
        apply()
    }

    /** Reads the list of output devices again (call when the settings page opens). */
    func refresh() {
        var ids = [UInt32](repeating: 0, count: 64)
        let n = Int(mk_output_devices(&ids, Int32(ids.count)))
        var out: [Device] = []
        for id in ids.prefix(n) {
            out.append(Device(id: id, uid: Self.text(id, mk_device_uid), name: Self.text(id, mk_device_name), channels: Int(mk_device_channels(id))))
        }
        devices = out
    }

    private static func text(_ id: UInt32, _ f: (UInt32, UnsafeMutablePointer<CChar>?, Int32) -> Void) -> String {
        var buf = [CChar](repeating: 0, count: 256)
        f(id, &buf, Int32(buf.count))
        return String(cString: buf)
    }

    /** The chosen device if it's plugged in, else nil (= the Mac's default). */
    var chosen: Device? { uid.isEmpty ? nil : devices.first { $0.uid == uid } }

    func choose(uid: String, channel: Int) {
        self.uid = uid
        self.channel = channel
        UserDefaults.standard.set(uid, forKey: "outputUID")
        UserDefaults.standard.set(channel, forKey: "outputChannel")
        apply()
    }

    /** Tells the click engine. It reopens the output right away, even mid-beat. */
    private func apply() {
        if !uid.isEmpty && chosen == nil { refresh() }
        let d = chosen
        mk_set_output(d?.id ?? 0, Int32(d == nil ? 0 : min(channel, max((d?.channels ?? 2) - 1, 0))))
    }

    /**
     * Songs play through the same device. LEARN: AVAudioEngine's output is a
     * CoreAudio unit too; we point it at the device before the engine starts.
     */
    func applyTo(_ engine: AVAudioEngine) {
        guard let d = chosen, let unit = engine.outputNode.audioUnit else { return }
        var id = d.id
        AudioUnitSetProperty(unit, kAudioOutputUnitProperty_CurrentDevice, kAudioUnitScope_Global, 0, &id, UInt32(MemoryLayout<AudioDeviceID>.size))
    }

    /** "Channels 1–2", "Channels 3–4"… for a device with [n] outputs. */
    static func pairs(_ n: Int) -> [(first: Int, label: String)] {
        guard n > 2 else { return [(0, n == 1 ? "Channel 1" : "Channels 1–2")] }
        return stride(from: 0, to: n, by: 2).map { f in (f, f + 1 < n ? "Channels \(f + 1)–\(f + 2)" : "Channel \(f + 1)") }
    }
}
