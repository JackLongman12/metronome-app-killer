import SwiftUI
import UniformTypeIdentifiers

/** The Songs page: paste a link or add files; click a song to open it. */
struct SongsView: View {
    @EnvironmentObject private var library: SongLibrary
    @State private var link = ""
    @State private var open: String?          // id of the song being shown
    @State private var tabFor: Song?
    @State private var dropping = false

    var body: some View {
        if let id = open, let song = library.song(id) {
            SongView(song: song) { open = nil }
        } else {
            list
        }
    }

    private var list: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 8) {
                TextField("Paste a YouTube, Spotify or Songsterr link", text: $link)
                    .textFieldStyle(.roundedBorder)
                    .onSubmit(addLink)
                Button("Add", action: addLink).buttonStyle(.borderedProminent).tint(.mkTealDark)
                    .disabled(link.trimmingCharacters(in: .whitespaces).isEmpty)
                Button("Open file…", action: pickFiles)
            }
            if let msg = library.linkStatus {
                Text(msg).font(.callout).foregroundStyle(.secondary)
            }
            if library.songs.isEmpty {
                VStack(spacing: 6) {
                    Image(systemName: "music.note.list").font(.system(size: 30)).foregroundStyle(.secondary)
                    Text("Add your first song").font(.headline)
                    Text("Paste a link above, or drop audio files here. The app finds every beat and bar, then plays the song with a click.")
                        .multilineTextAlignment(.center).foregroundStyle(.secondary).frame(maxWidth: 380)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(library.songs) { s in
                        SongRow(song: s)
                            .contentShape(Rectangle())
                            .onTapGesture { if s.map != nil { open = s.id } }
                            .contextMenu {
                                if s.map != nil { Button("Use a Songsterr tab…") { tabFor = s } }
                                Button("Analyse again") { library.analyseAgain(s.id) }
                                Divider()
                                Button("Remove", role: .destructive) { library.delete(s.id) }
                            }
                    }
                }
                .listStyle(.inset)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        // LEARN: onDrop lets you drag audio files from Finder straight onto the window.
        .onDrop(of: [.fileURL], isTargeted: $dropping) { providers in
            for p in providers {
                _ = p.loadObject(ofClass: URL.self) { url, _ in
                    if let url { Task { @MainActor in library.add(files: [url]) } }
                }
            }
            return true
        }
        .overlay { if dropping { RoundedRectangle(cornerRadius: 10).stroke(Color.mkTeal, lineWidth: 2).padding(6) } }
        .sheet(item: $tabFor) { s in TabLinkSheet(song: s) }
    }

    private func addLink() {
        let t = link.trimmingCharacters(in: .whitespaces)
        guard !t.isEmpty else { return }
        library.addLink(t)
        link = ""
    }

    private func pickFiles() {
        let panel = NSOpenPanel()
        panel.allowsMultipleSelection = true
        panel.allowedContentTypes = [.audio]
        if panel.runModal() == .OK { library.add(files: panel.urls) }
    }
}

/** One song in the list: name, where it is in analysis, and its tempo. */
struct SongRow: View {
    let song: Song

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon).foregroundStyle(.secondary).frame(width: 18)
            VStack(alignment: .leading, spacing: 2) {
                Text(song.title).lineLimit(1)
                Text([song.artist, song.durationText, song.playlist.map { "from \($0)" }].compactMap { $0 }.filter { !$0.isEmpty }.joined(separator: " · "))
                    .font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            trailing
        }
        .padding(.vertical, 4)
    }

    private var icon: String {
        switch song.status { case .ready: "music.note"; case .failed: "exclamationmark.triangle"; default: "waveform" }
    }

    @ViewBuilder private var trailing: some View {
        switch song.status {
        case .ready:
            if song.usingEdit { Pill("Your edits") } else if song.usingTab { Pill("Tab") }
            if let m = song.map {
                Text("\(Int(m.mainBpm.rounded())) BPM · \(meters(m))").font(.caption).foregroundStyle(.secondary).monospacedDigit()
            }
        case .queued:
            Text("Waiting…").font(.caption).foregroundStyle(.secondary)
        case .working(let step, let p):
            Text(step).font(.caption).foregroundStyle(.secondary)
            ProgressView(value: p).frame(width: 80)
        case .failed(let msg):
            Text(msg).font(.caption).foregroundStyle(.red).lineLimit(2).frame(maxWidth: 260, alignment: .trailing)
        }
    }

    private func meters(_ m: TempoMap) -> String {
        let ms = Dictionary(grouping: m.bars, by: \.meter).sorted { $0.value.count > $1.value.count }.prefix(3).map(\.key)
        return ms.isEmpty ? "free" : ms.joined(separator: ", ")
    }
}

struct Pill: View {
    let text: String
    init(_ t: String) { text = t }
    var body: some View {
        Text(text).font(.caption2)
            .padding(.horizontal, 7).padding(.vertical, 2)
            .background(Capsule().fill(Color.mkTeal.opacity(0.15)))
            .foregroundStyle(Color.mkTealDark)
    }
}

/** Paste a Songsterr link to use that tab for a song. */
struct TabLinkSheet: View {
    let song: Song
    @EnvironmentObject private var library: SongLibrary
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text("Songsterr tab for \(song.title)").font(.headline)
            TextField("songsterr.com/a/wsa/…-tab-s269", text: $text).textFieldStyle(.roundedBorder)
            Text("The tab has to line up with this recording, or nothing changes.").font(.caption).foregroundStyle(.secondary)
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }
                Button("Use tab") { library.attachTab(song.id, link: text); dismiss() }
                    .buttonStyle(.borderedProminent).tint(.mkTealDark).disabled(text.isEmpty)
            }
        }
        .padding(20)
        .frame(width: 420)
    }
}
