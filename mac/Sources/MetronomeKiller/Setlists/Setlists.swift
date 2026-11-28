import Foundation

/** One entry in a setlist: saved metronome settings ("Intro click, 132 BPM, 7/8"). */
struct SetItem: Codable, Equatable, Identifiable {
    enum Kind: Codable, Equatable {
        case preset(MetronomeSettings)
        /** Only in setlists saved before Songs was removed; dropped when the app loads them. */
        case song(String)
    }
    var id = UUID().uuidString
    var name: String
    var kind: Kind

    /** The saved settings (songs are never kept, see SetlistStore.init). */
    var settings: MetronomeSettings {
        if case .preset(let s) = kind { return s }
        return MetronomeSettings()
    }
}

struct Setlist: Codable, Equatable, Identifiable {
    var id = UUID().uuidString
    var name: String
    var items: [SetItem] = []
}

/**
 * Your setlists, saved as JSON in the app's folder (setlists.json).
 *
 * LEARN: [active] remembers which setlist you're playing through and where you
 * are in it, so the Metronome page can show "3 of 12 · Next" for quick
 * changes between songs on stage.
 */
@MainActor
final class SetlistStore: ObservableObject {
    @Published private(set) var lists: [Setlist]
    /** The setlist being played through and the current item's index, or nil. */
    @Published var active: (list: String, index: Int)?

    private static var file: URL { AppFiles.support.appendingPathComponent("setlists.json") }

    init() {
        lists = (try? JSONDecoder().decode([Setlist].self, from: Data(contentsOf: Self.file))) ?? []
        // Songs were removed from the app: take any out of older setlists, and save.
        let hadSongs = lists.contains { $0.items.contains { if case .song = $0.kind { true } else { false } } }
        if hadSongs {
            change { lists in
                for i in lists.indices { lists[i].items.removeAll { if case .song = $0.kind { true } else { false } } }
            }
        }
    }

    func get(_ id: String) -> Setlist? { lists.first { $0.id == id } }

    @discardableResult
    func create(_ name: String, items: [SetItem] = []) -> String {
        let s = Setlist(name: name.isEmpty ? "New setlist" : name, items: items)
        change { $0.append(s) }
        return s.id
    }

    func rename(_ id: String, _ name: String) { if !name.isEmpty { edit(id) { $0.name = name } } }

    func delete(_ id: String) {
        change { $0.removeAll { $0.id == id } }
        if active?.list == id { active = nil }
    }

    func addPreset(_ id: String, name: String, settings: MetronomeSettings) {
        edit(id) { $0.items.append(SetItem(name: name, kind: .preset(settings))) }
    }

    /** Replace a preset's settings (keeps its name and place). */
    func updatePreset(_ id: String, item: String, settings: MetronomeSettings) {
        edit(id) { s in
            if let i = s.items.firstIndex(where: { $0.id == item }) { s.items[i].kind = .preset(settings) }
        }
    }

    func renameItem(_ id: String, item: String, name: String) {
        guard !name.isEmpty else { return }
        edit(id) { s in if let i = s.items.firstIndex(where: { $0.id == item }) { s.items[i].name = name } }
    }

    func removeItem(_ id: String, item: String) { edit(id) { $0.items.removeAll { $0.id == item } } }

    /** Drag-to-reorder in the list. */
    func move(_ id: String, from: IndexSet, to: Int) { edit(id) { $0.items.move(fromOffsets: from, toOffset: to) } }

    private func edit(_ id: String, _ f: (inout Setlist) -> Void) {
        change { lists in if let i = lists.firstIndex(where: { $0.id == id }) { f(&lists[i]) } }
    }

    private func change(_ f: (inout [Setlist]) -> Void) {
        f(&lists)
        if let d = try? JSONEncoder().encode(lists) { try? d.write(to: Self.file, options: .atomic) }
    }

    /** Short description of a metronome preset, e.g. "132 BPM · 7/8". */
    static func describe(_ m: MetronomeSettings) -> String {
        "\(m.bpm) BPM · \(m.meter.label)" + (m.subdivision != .quarters ? " · \(m.subdivision.label.lowercased())" : "")
    }
}
