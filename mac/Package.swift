// swift-tools-version:5.9
// ─────────────────────────────────────────────────────────────────────────────
// The Mac app's build file (Swift Package Manager).
//
// LEARN: SwiftPM builds two "targets":
// - ClickCore: the C++ audio engine (the same one as Android, on CoreAudio).
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
            ]
        ),
        .executableTarget(
            name: "MetronomeKiller",
            dependencies: ["ClickCore"],
            path: "Sources/MetronomeKiller"
        ),
    ],
    cxxLanguageStandard: .cxx17
)
