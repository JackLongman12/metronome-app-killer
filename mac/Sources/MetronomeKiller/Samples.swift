import ClickCore
import Foundation

/**
 * Loads the recorded drum hits (the .wav files in Contents/Resources/sounds inside the app)
 * into the engine. The same files and slot order as Android's SampleLoader.kt.
 *
 * LEARN — the WAV format: a small header of "chunks" (a 4-letter name plus a
 * length), then a "data" chunk of raw samples. Ours are 16-bit mono: each
 * sample is a whole number from −32768 to 32767, stored low byte first. Dividing
 * by 32768 gives the −1…1 numbers the engine uses.
 */
enum Samples {
    /** Order must match the kSample* slots in ClickEngine.cpp. */
    private static let files = [
        "stick_accent", "stick_normal", "cowbell_accent", "cowbell_normal", "bell_accent", "bell_normal",
        "hat_accent", "hat_normal", "hat_soft", "kick", "rim_accent", "rim_normal",
        "wood_accent", "wood_normal", "wood_soft", "clave_accent", "clave_normal", "clave_soft",
        "agogo_high", "agogo_low", "agogo_soft", "log_high", "log_low", "log_soft",
        "triangle_accent", "triangle_normal", "clap_accent", "clap_normal", "clap_soft",
    ]

    /** Call once at launch, before the audio output first opens. A missing file falls back to the plain click. */
    static func load() {
        for (slot, name) in files.enumerated() {
            guard let url = Bundle.main.url(forResource: name, withExtension: "wav", subdirectory: "sounds"),
                  let data = try? Data(contentsOf: url),
                  let (samples, rate) = decodeWav(data) else { continue }
            samples.withUnsafeBufferPointer { mk_set_sample(Int32(slot), $0.baseAddress, Int32($0.count), Int32(rate)) }
        }
    }

    static func decodeWav(_ d: Data) -> ([Float], Int)? {
        let b = [UInt8](d)
        func u32(_ i: Int) -> Int { Int(b[i]) | Int(b[i + 1]) << 8 | Int(b[i + 2]) << 16 | Int(b[i + 3]) << 24 }
        func u16(_ i: Int) -> Int { Int(b[i]) | Int(b[i + 1]) << 8 }
        guard b.count > 12, String(bytes: b[0..<4], encoding: .ascii) == "RIFF", String(bytes: b[8..<12], encoding: .ascii) == "WAVE" else { return nil }
        var pos = 12, rate = 0, bits = 0, channels = 0
        while pos + 8 <= b.count {
            let id = String(bytes: b[pos..<pos + 4], encoding: .ascii) ?? ""
            let len = u32(pos + 4)
            let body = pos + 8
            if id == "fmt " { channels = u16(body + 2); rate = u32(body + 4); bits = u16(body + 14) }
            if id == "data" {
                guard bits == 16, channels == 1 else { return nil }
                let n = min(len, b.count - body) / 2
                let out = (0..<n).map { i -> Float in Float(Int16(bitPattern: UInt16(u16(body + i * 2)))) / 32768 }
                return (out, rate)
            }
            pos = body + len + (len & 1) // chunks are padded to an even length
        }
        return nil
    }
}
