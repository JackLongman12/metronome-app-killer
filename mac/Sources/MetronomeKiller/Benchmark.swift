import AppKit
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

        // 2. Memory: the most this process used at any moment, and what it holds now.
        var usage = rusage()
        getrusage(RUSAGE_SELF, &usage)
        stat("peak_memory_mb", String(format: "%.0f", Double(usage.ru_maxrss) / 1_048_576)) // bytes on macOS
        stat("memory_mb", String(format: "%.0f", Double(currentMemory()) / 1_048_576))
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
