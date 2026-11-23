import AppKit
import AVFoundation
import ClickCore
import Darwin
import Foundation

/**
 * Speed and resource measurements, run by CI on a real Mac after every build:
 *   MetronomeKiller --benchmark
 * Prints "STAT <name> <value>" lines that the workflow puts in the release notes.
 *
 * LEARN — what's measured and why:
 * - Engine cost: how long the C++ engine takes to make one 128-sample buffer
 *   (2.7 ms of sound). Compared with 2.7 ms that gives the CPU share of the click.
 * - Analysis: a made-up 4-minute song at exactly 120 BPM goes through the same
 *   steps as a real one (read → beat model → tempo map), timed step by step,
 *   and we check the tempo found really is 120.
 * - Peak memory: the most RAM the process used at any point.
 */
enum Benchmark {
    static func stat(_ name: String, _ value: String) { print("STAT \(name) \(value)"); fflush(stdout) }

    /** Runs everything, then exits the process. */
    static func run() -> Never {
        let done = DispatchSemaphore(value: 0)
        Task.detached {
            await measure()
            done.signal()
        }
        done.wait()
        exit(0)
    }

    private static func measure() async {
        // 1. The click engine
        let ns = mk_bench_render(128, 20_000)
        stat("engine_us_per_buffer", String(format: "%.2f", ns / 1000))
        stat("engine_cpu_percent", String(format: "%.3f", ns / (128.0 / 48_000 * 1e9) * 100))

        // 2. Song analysis on a synthetic song
        do {
            let url = try makeTestSong(seconds: 240, bpm: 120)
            var t = Date()
            try await ModelFiles.ensure { _, _ in }
            stat("analysis_files_download_s", String(format: "%.1f", Date().timeIntervalSince(t)))

            t = Date()
            let audio = try AudioDecoder.decode(url)
            stat("decode_s", String(format: "%.2f", Date().timeIntervalSince(t)))

            t = Date()
            try BeatPipeline.open()
            stat("model_load_s", String(format: "%.2f", Date().timeIntervalSince(t)))

            t = Date()
            let r = try BeatPipeline.analyse(audio.samples) { _ in }
            let modelS = Date().timeIntervalSince(t)
            stat("beat_model_s", String(format: "%.2f", modelS))
            stat("beat_model_x_realtime", String(format: "%.0f", audio.duration / modelS))

            t = Date()
            let map = TempoMapBuilder.build(rawBeats: r.beats, downbeats: r.downbeats, duration: audio.duration, dbAct: r.downbeatScores)
            stat("tempo_map_ms", String(format: "%.1f", Date().timeIntervalSince(t) * 1000))
            stat("detected_bpm", String(format: "%.1f", map.mainBpm))
            stat("detected_bars", "\(map.bars.count)")
            stat("detected_meter", PartRows.usualMeter(map) ?? "none")
            stat("bpm_correct", abs(map.mainBpm - 120) < 1.5 ? "yes" : "NO")
            try? FileManager.default.removeItem(at: url)
        } catch {
            stat("analysis_error", error.localizedDescription.replacingOccurrences(of: " ", with: "_"))
        }

        // 3. Memory: the most used at any moment, and what's still held after analysis.
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        stat("peak_memory_during_analysis_mb", String(format: "%.0f", Double(usage.ru_maxrss) / 1_048_576)) // bytes on macOS
        stat("memory_after_analysis_mb", String(format: "%.0f", Double(currentMemory()) / 1_048_576))
    }

    /** Memory in use right now ("physical footprint", what Activity Monitor shows). */
    private static func currentMemory() -> UInt64 {
        var info = task_vm_info_data_t()
        var count = mach_msg_type_number_t(MemoryLayout<task_vm_info_data_t>.size / MemoryLayout<natural_t>.size)
        let kr = withUnsafeMutablePointer(to: &info) {
            $0.withMemoryRebound(to: integer_t.self, capacity: Int(count)) { task_info(mach_task_self_, task_flavor_t(TASK_VM_INFO), $0, &count) }
        }
        return kr == KERN_SUCCESS ? info.phys_footprint : 0
    }

    /**
     * A drum-like test track: a low "kick" on every beat (louder on the 1 of
     * each 4/4 bar), a noisy "hat" between beats, and a quiet bass note that
     * changes every bar, saved as AAC like a YouTube download.
     */
    private static func makeTestSong(seconds: Double, bpm: Double) throws -> URL {
        let rate = 44_100.0
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("mk-bench.m4a")
        try? FileManager.default.removeItem(at: url)
        let settings: [String: Any] = [AVFormatIDKey: kAudioFormatMPEG4AAC, AVSampleRateKey: rate, AVNumberOfChannelsKey: 2, AVEncoderBitRateKey: 128_000]
        let file = try AVAudioFile(forWriting: url, settings: settings, commonFormat: .pcmFormatFloat32, interleaved: false)
        let fmt = file.processingFormat
        let chunk = AVAudioFrameCount(rate)
        let beat = 60 / bpm
        var seed: UInt32 = 1
        var n = 0
        let total = Int(seconds * rate)
        while n < total {
            let len = min(Int(chunk), total - n)
            let buf = AVAudioPCMBuffer(pcmFormat: fmt, frameCapacity: AVAudioFrameCount(len))!
            buf.frameLength = AVAudioFrameCount(len)
            for i in 0..<len {
                let t = Double(n + i) / rate
                let k = Int(t / beat)
                let inBeat = t - Double(k) * beat
                let accent = k % 4 == 0 ? 1.0 : 0.6
                let kick = sin(2 * .pi * (50 + 90 * exp(-inBeat / 0.03)) * inBeat) * exp(-inBeat / 0.12) * accent
                let half = inBeat - beat / 2
                seed = seed &* 1664525 &+ 1013904223
                let noise = Double(Int32(bitPattern: seed)) / Double(Int32.max)
                let hat = half >= 0 ? noise * exp(-half / 0.02) * 0.25 : 0
                let bassF = [55.0, 65.4, 73.4, 49.0][(k / 4) % 4]
                let bass = sin(2 * .pi * bassF * t) * 0.15
                let v = Float((kick + hat + bass) * 0.6)
                buf.floatChannelData![0][i] = v
                buf.floatChannelData![1][i] = v
            }
            try file.write(from: buf)
            n += len
        }
        return url
    }

    /**
     * Launch probe: with MK_LAUNCH_PROBE set, print how long it took from the
     * process starting to the app being ready, then quit.
     */
    @MainActor
    static func launchProbe() {
        guard ProcessInfo.processInfo.environment["MK_LAUNCH_PROBE"] != nil else { return }
        var info = kinfo_proc()
        var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        sysctl(&mib, 4, &info, &size, nil, 0)
        let st = info.kp_proc.p_un.__p_starttime
        let started = Double(st.tv_sec) + Double(st.tv_usec) / 1e6
        var now = timeval()
        gettimeofday(&now, nil)
        stat("launch_ms", String(format: "%.0f", (Double(now.tv_sec) + Double(now.tv_usec) / 1e6 - started) * 1000))
        // Give the window a moment to draw, then quit.
        DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) { NSApp.terminate(nil) }
    }
}
