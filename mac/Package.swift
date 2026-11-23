// swift-tools-version:5.9
// ─────────────────────────────────────────────────────────────────────────────
// The Mac app's build file (Swift Package Manager).
//
// LEARN: SwiftPM builds three "targets":
// - ClickCore: the C++ audio engine (the same one as Android, on CoreAudio).
// - OrtShim: a small C bridge to ONNX Runtime, for the beat-tracking model.
// - MetronomeKiller: the Swift app (SwiftUI screens, updater, uninstaller).
// Swift calls the engine through the plain C functions in ClickCore.h.
// build.sh then wraps the program into a normal double-clickable .app.
// ─────────────────────────────────────────────────────────────────────────────
import PackageDescription

let package = Package(
    name: "MetronomeKiller",
    platforms: [.macOS(.v13)],
    targets: [
        .target(
            name: "ClickCore",
            path: "Sources/ClickCore",
            cxxSettings: [.unsafeFlags(["-O3"])],
            linkerSettings: [
                .linkedFramework("AudioToolbox"),
                .linkedFramework("CoreAudio"),
                .linkedFramework("CoreMIDI"),
            ]
        ),
        // Loads ONNX Runtime (downloaded on first song analysis) to run the beat model.
        .target(
            name: "OrtShim",
            path: "Sources/OrtShim",
            exclude: ["ort/LICENSE-onnxruntime.txt"],
            cSettings: [.unsafeFlags(["-O2"])]
        ),
        .executableTarget(
            name: "MetronomeKiller",
            dependencies: ["ClickCore", "OrtShim"],
            path: "Sources/MetronomeKiller",
            linkerSettings: [.linkedFramework("MediaPlayer")]
        ),
    ],
    cxxLanguageStandard: .cxx17
)
