import SwiftUI

/** The window: a sidebar with the four sections, and the chosen one on the right. */
struct ContentView: View {
    enum Section: String, CaseIterable, Identifiable {
        case metronome = "Metronome", songs = "Songs", practice = "Practice", setlists = "Setlists"
        var id: String { rawValue }
        var icon: String {
            switch self { case .metronome: "metronome"; case .songs: "music.note.list"; case .practice: "target"; case .setlists: "list.bullet" }
        }
    }

    @State private var section: Section = .metronome
    @EnvironmentObject private var updater: Updater

    var body: some View {
        NavigationSplitView {
            List(Section.allCases, selection: $section) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(170)
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Space  start / stop")
                    Text("↑ ↓  tempo")
                    Text("T  tap")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
            }
        } detail: {
            VStack(spacing: 0) {
                UpdateBanner()
                switch section {
                case .metronome: MetronomeView()
                default: ComingSoon(name: section.rawValue)
                }
            }
        }
    }
}

/** Sections that aren't on the Mac yet. */
struct ComingSoon: View {
    let name: String
    var body: some View {
        VStack(spacing: 8) {
            Image(systemName: "hammer").font(.system(size: 28)).foregroundStyle(.secondary)
            Text("\(name) is coming in the next Mac builds").font(.headline)
            Text("The app updates itself when it's ready.").foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
    }
}

/** A thin bar at the top when a new version is out. */
struct UpdateBanner: View {
    @EnvironmentObject private var updater: Updater

    var body: some View {
        switch updater.state {
        case .available(let r):
            bar {
                Text("Build \(r.build) is available.")
                Spacer()
                Button("Update and restart") { Task { await updater.install(r) } }
            }
        case .downloading(let p):
            bar {
                Text("Downloading the update…")
                ProgressView(value: p).frame(width: 160)
                Spacer()
            }
        case .failed(let msg):
            bar {
                Text(msg).lineLimit(2)
                Spacer()
                Button("Open GitHub") { NSWorkspace.shared.open(URL(string: "https://github.com/\(Updater.repo)/releases")!) }
            }
        default:
            EmptyView()
        }
    }

    private func bar<C: View>(@ViewBuilder _ content: () -> C) -> some View {
        HStack(spacing: 12, content: content)
            .font(.callout)
            .padding(.horizontal, 16)
            .padding(.vertical, 8)
            .background(Color.mkTeal.opacity(0.12))
            .overlay(alignment: .bottom) { Divider() }
    }
}
