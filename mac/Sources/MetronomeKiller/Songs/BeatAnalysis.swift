import AVFoundation
import CryptoKit
import Foundation
import OrtShim

/**
 * Downloads for song analysis, fetched the first time you analyse a song.
 *
 * LEARN: each file has a SHA-256 "fingerprint" written here. After downloading
 * we compute the file's fingerprint and compare; a broken or tampered download
 * never gets used.
 */
enum ModelFiles {
    struct Item {
        let url: String
        let sha256: String
        let file: String   // name inside the models folder
        let sizeMB: Int
    }

    static let runtime = Item(
        url: "https://github.com/\(Updater.repo)/releases/download/ort-mac-v1/onnxruntime-mac-arm64-1.32.0.zip",
        sha256: "0320d41ac6b0bd3f4f48b90e1ecc2b29fc0806cd5a8ad8c16557f2f93e007461",
        file: "onnxruntime-1.32.0", sizeMB: 13)
    static let model = Item(
        url: "https://github.com/\(Updater.repo)/releases/download/model-v1/beat_this_v1_int8.onnx",
        sha256: "e600b1ab4b968ba3ff59854b9473370f0c6450d845ef06f7de34a3e0f735970f",
        file: "beat_this_v1_int8.onnx", sizeMB: 28)

    static var dir: URL {
        let u = AppFiles.support.appendingPathComponent("models")
        try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true)
        return u
    }

    static var dylib: URL { dir.appendingPathComponent(runtime.file).appendingPathComponent("libonnxruntime.dylib") }
    static var modelFile: URL { dir.appendingPathComponent(model.file) }
    static var ready: Bool { FileManager.default.fileExists(atPath: dylib.path) && FileManager.default.fileExists(atPath: modelFile.path) }

    /** Downloads whatever is missing. progress gets (what, 0…1). */
    static func ensure(progress: @escaping (String, Double) -> Void) async throws {
        if !FileManager.default.fileExists(atPath: dylib.path) {
            let zip = try await Net.download(runtime.url, sha256: runtime.sha256) { progress("Downloading the analysis engine (\(runtime.sizeMB) MB)", $0) }
            let out = dir.appendingPathComponent(runtime.file)
            try? FileManager.default.removeItem(at: out)
            try FileManager.default.createDirectory(at: out, withIntermediateDirectories: true)
            try Net.run("/usr/bin/ditto", ["-x", "-k", zip.path, out.path])
            try? FileManager.default.removeItem(at: zip)
        }
        if !FileManager.default.fileExists(atPath: modelFile.path) {
            let f = try await Net.download(model.url, sha256: model.sha256) { progress("Downloading the beat model (\(model.sizeMB) MB)", $0) }
            try FileManager.default.moveItem(at: f, to: modelFile)
        }
    }
}

/** Shared download helpers. */
enum Net {
    /** Ephemeral = keeps no cookies or cache files on disk. */
    static let session = URLSession(configuration: .ephemeral)
    static let browserUA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_0) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/17.0 Safari/605.1.15"

    /** Downloads to a temporary file, checking the fingerprint if given. */
    static func download(_ url: String, sha256: String? = nil, progress: @escaping (Double) -> Void) async throws -> URL {
        let (tmp, resp) = try await session.download(from: URL(string: url)!, delegate: ProgressDelegate(progress))
        guard (resp as? HTTPURLResponse)?.statusCode == 200 else { throw UpdateError("Download failed (\(url))") }
        let dest = AppFiles.caches.appendingPathComponent(UUID().uuidString)
        try FileManager.default.moveItem(at: tmp, to: dest)
        if let sha256 {
            let digest = try SHA256.hash(data: Data(contentsOf: dest, options: .mappedIfSafe)).map { String(format: "%02x", $0) }.joined()
            if digest != sha256 {
                try? FileManager.default.removeItem(at: dest)
                throw UpdateError("The download was corrupted. Try again.")
            }
        }
        return dest
    }

    static func text(_ url: String, headers: [String: String] = [:]) async throws -> String {
        var req = URLRequest(url: URL(string: url)!)
        req.timeoutInterval = 20
        req.setValue(browserUA, forHTTPHeaderField: "User-Agent")
        for (k, v) in headers { req.setValue(v, forHTTPHeaderField: k) }
        let (data, resp) = try await session.data(for: req)
        let code = (resp as? HTTPURLResponse)?.statusCode ?? 0
        guard code == 200 else { throw UpdateError("HTTP \(code) from \(req.url?.host ?? "server")") }
        return String(decoding: data, as: UTF8.self)
    }

    static func run(_ tool: String, _ args: [String]) throws {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = args
        try p.run()
        p.waitUntilExit()
        if p.terminationStatus != 0 { throw UpdateError("\((tool as NSString).lastPathComponent) failed") }
    }

    private final class ProgressDelegate: NSObject, URLSessionDownloadDelegate {
        let onProgress: (Double) -> Void
        init(_ f: @escaping (Double) -> Void) { onProgress = f }
        func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didWriteData _: Int64, totalBytesWritten w: Int64, totalBytesExpectedToWrite t: Int64) {
            if t > 0 { onProgress(Double(w) / Double(t)) }
        }
        func urlSession(_ s: URLSession, downloadTask: URLSessionDownloadTask, didFinishDownloadingTo location: URL) {}
    }
}

/**
 * Reads any audio file macOS can play (m4a, mp3, wav, aiff, flac…) as 22050 Hz
 * mono, the format the beat model expects.
 *
 * LEARN: AVAudioConverter does both jobs at once: mixing the channels down to
 * one, and resampling (changing the number of samples per second).
 */
enum AudioDecoder {
    static let rate = 22050.0

    static func decode(_ url: URL) throws -> (samples: [Float], duration: Double) {
        let file = try AVAudioFile(forReading: url)
        let inFormat = file.processingFormat
        let outFormat = AVAudioFormat(commonFormat: .pcmFormatFloat32, sampleRate: rate, channels: 1, interleaved: false)!
        guard let conv = AVAudioConverter(from: inFormat, to: outFormat) else { throw UpdateError("Can't read this audio format") }
        let duration = Double(file.length) / inFormat.sampleRate
        var out: [Float] = []
        out.reserveCapacity(Int(duration * rate) + 4096)
        let inBuf = AVAudioPCMBuffer(pcmFormat: inFormat, frameCapacity: 65536)!
        let outBuf = AVAudioPCMBuffer(pcmFormat: outFormat, frameCapacity: 65536)!
        var done = false
        while true {
            outBuf.frameLength = 0
            var error: NSError?
            let status = conv.convert(to: outBuf, error: &error) { _, inputStatus in
                if done { inputStatus.pointee = .endOfStream; return nil }
                do {
                    try file.read(into: inBuf)
                } catch { done = true; inputStatus.pointee = .endOfStream; return nil }
                if inBuf.frameLength == 0 { done = true; inputStatus.pointee = .endOfStream; return nil }
                inputStatus.pointee = .haveData
                return inBuf
            }
            if let error { throw error }
            if outBuf.frameLength > 0, let p = outBuf.floatChannelData?[0] {
                out.append(contentsOf: UnsafeBufferPointer(start: p, count: Int(outBuf.frameLength)))
            }
            if status == .endOfStream || status == .error { break }
        }
        return (out, duration)
    }
}

/**
 * Turns a whole song into beat and downbeat times, chunk by chunk. A port of
 * BeatPipeline in BeatModel.kt (itself checked against the original model).
 */
enum BeatPipeline {
    private static let HOP = 441          // samples per frame at 22050 Hz → 50 frames/s
    private static let N_FFT = 1024
    private static let CHUNK = 1500       // frames per chunk (30 s), what the model was trained on
    private static let BORDER = 6         // frames dropped at chunk edges (less reliable there)
    static let FPS = 50.0

    struct Result { let beats: [Double]; let downbeats: [Double]; let downbeatScores: [Float] }

    static func open() throws {
        var err = [CChar](repeating: 0, count: 512)
        let threads = Int32(min(max(ProcessInfo.processInfo.activeProcessorCount / 2, 2), 4))
        if mk_ort_open(ModelFiles.dylib.path, ModelFiles.modelFile.path, threads, &err, Int32(err.count)) != 0 {
            throw UpdateError(String(cString: err))
        }
    }

    static func analyse(_ sig: [Float], progress: (Double) -> Void) throws -> Result {
        try open()
        let nFrames = 1 + sig.count / HOP
        // Reflect-pad 512 samples at both ends (what torchaudio's center=True does).
        let pad = N_FFT / 2
        var padded = [Float](repeating: 0, count: sig.count + 2 * pad)
        for i in 0..<sig.count { padded[pad + i] = sig[i] }
        for i in 0..<pad {
            padded[pad - 1 - i] = i + 1 < sig.count ? sig[i + 1] : 0
            let j = sig.count - 2 - i
            padded[pad + sig.count + i] = j >= 0 && j < sig.count ? sig[j] : 0
        }

        var starts: [Int] = []
        var s = -BORDER
        while s < nFrames - BORDER { starts.append(s); s += CHUNK - 2 * BORDER }
        if nFrames > CHUNK - 2 * BORDER { starts[starts.count - 1] = nFrames - (CHUNK - BORDER) }
        var beat = [Float](repeating: -1000, count: nFrames)
        var down = [Float](repeating: -1000, count: nFrames)

        // keep_first: process from the last chunk to the first so earlier chunks win overlaps
        var err = [CChar](repeating: 0, count: 512)
        for (done, st) in starts.reversed().enumerated() {
            let lo = max(st, 0)
            let hi = min(st + CHUNK, nFrames)
            let from = lo * HOP
            let len = min((hi - 1) * HOP + N_FFT, padded.count) - from
            var b: UnsafeMutablePointer<Float>?, d: UnsafeMutablePointer<Float>?
            var n: Int64 = 0
            let rc = padded.withUnsafeBufferPointer { mk_ort_run($0.baseAddress! + from, Int64(len), &b, &d, &n, &err, Int32(err.count)) }
            if rc != 0 { throw UpdateError("Beat model failed: \(String(cString: err))") }
            let k0 = max(st + BORDER, 0)
            let k1 = min(st + CHUNK - BORDER, nFrames)
            if k0 < k1 {
                for k in k0..<k1 {
                    let j = k - lo
                    if j >= 0 && j < Int(n) { beat[k] = b![j]; down[k] = d![j] }
                }
            }
            mk_ort_free(b); mk_ort_free(d)
            progress(Double(done + 1) / Double(starts.count))
        }

        let bt = peaks(beat)
        var dt = peaks(down)
        if !bt.isEmpty {
            // snap each downbeat to the nearest beat, then de-duplicate
            dt = Array(Set(dt.map { x in bt.min { abs($0 - x) < abs($1 - x) }! })).sorted()
        }
        return Result(beats: bt, downbeats: dt, downbeatScores: down)
    }

    /** Local maximum within ±3 frames and score > 0; neighbouring peak frames merged. */
    private static func peaks(_ s: [Float]) -> [Double] {
        var idx: [Int] = []
        for i in s.indices where s[i] > 0 {
            var isMax = true
            for k in max(0, i - 3)...min(s.count - 1, i + 3) where s[k] > s[i] { isMax = false; break }
            if isMax { idx.append(i) }
        }
        var out: [Double] = []
        var run: [Int] = []
        for i in idx {
            if let last = run.last, i - last > 1 { out.append(Double(run.reduce(0, +)) / Double(run.count) / FPS); run = [] }
            run.append(i)
        }
        if !run.isEmpty { out.append(Double(run.reduce(0, +)) / Double(run.count) / FPS) }
        return out
    }
}
