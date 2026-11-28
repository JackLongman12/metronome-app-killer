import SwiftUI

/** The window: a sidebar with the four sections, and the chosen one on the right. */
struct ContentView: View {
    enum Section: String, CaseIterable, Identifiable {
        case metronome = "Metronome", practice = "Practice", setlists = "Setlists"
        var id: String { rawValue }
        var icon: String {
            switch self { case .metronome: "metronome"; case .practice: "target"; case .setlists: "list.bullet" }
        }
    }

    @EnvironmentObject private var app: AppState

    var body: some View {
        NavigationSplitView {
            List(Section.allCases, selection: Binding(get: { app.section }, set: { if let s = $0 { app.section = s } })) { s in
                Label(s.rawValue, systemImage: s.icon).tag(s)
            }
            .navigationSplitViewColumnWidth(170)
            .safeAreaInset(edge: .bottom) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Space  start / stop")
                    Text("↑ ↓  tempo")
                    Text("T  tap")
                    Text("PgDn PgUp  setlist")
                }
                .font(.caption)
                .foregroundStyle(.secondary)
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(12)
            }
        } detail: {
            VStack(spacing: 0) {
                UpdateBanner()
                if app.section == .metronome { SetlistStrip() }
                switch app.section {
                case .metronome: MetronomeView()
                case .practice: PracticeView()
                case .setlists: SetlistsView()
                }
            }
        }
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
