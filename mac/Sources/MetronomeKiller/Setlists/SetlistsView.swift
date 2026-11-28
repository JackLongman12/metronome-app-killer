import SwiftUI

/**
 * Setlists: tempos and time signatures in the order you play them. Shows the list of
 * setlists, or one setlist when it's open. A port of SetlistsScreen.kt.
 */
struct SetlistsView: View {
    @EnvironmentObject private var store: SetlistStore
    @State private var open: String?

    var body: some View {
        if let id = open, let list = store.get(id) {
            SetlistDetail(list: list) { open = nil }
        } else {
            SetlistList { open = $0 }
        }
    }
}

private struct SetlistList: View {
    let onOpen: (String) -> Void
    @EnvironmentObject private var store: SetlistStore
    @State private var naming = false

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack {
                Text("Setlists").font(.title2.weight(.semibold))
                Spacer()
                Button("New setlist") { naming = true }.buttonStyle(.borderedProminent).tint(.mkTealDark)
            }
            Text("Saved metronome settings, in playing order. On stage, Next (or Page Down, ⌘], a MIDI pedal) jumps straight to the next one.")
                .font(.callout).foregroundStyle(.secondary)
            if store.lists.isEmpty {
                VStack(spacing: 6) {
                    Image(systemName: "list.bullet").font(.system(size: 28)).foregroundStyle(.secondary)
                    Text("No setlists yet").font(.headline)
                }
                .frame(maxWidth: .infinity, maxHeight: .infinity)
            } else {
                List {
                    ForEach(store.lists) { s in
                        HStack {
                            VStack(alignment: .leading, spacing: 2) {
                                Text(s.name)
                                Text("\(s.items.count) item\(s.items.count == 1 ? "" : "s")").font(.caption).foregroundStyle(.secondary)
                            }
                            Spacer()
                            Image(systemName: "chevron.right").foregroundStyle(.tertiary)
                        }
                        .padding(.vertical, 4)
                        .contentShape(Rectangle())
                        .onTapGesture { onOpen(s.id) }
                        .contextMenu { Button("Delete", role: .destructive) { store.delete(s.id) } }
                    }
                }
                .listStyle(.inset)
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .sheet(isPresented: $naming) {
            NameSheet(title: "New setlist", initial: "", placeholder: "Friday gig") { onOpen(store.create($0)) }
        }
    }


}

private struct SetlistDetail: View {
    let list: Setlist
    let onBack: () -> Void
    @EnvironmentObject private var store: SetlistStore
    @EnvironmentObject private var m: Metronome
    @EnvironmentObject private var app: AppState
    @State private var renaming = false
    @State private var addingPreset = false
    @State private var renamingItem: SetItem?
    @State private var confirmDelete = false

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Button(action: onBack) { Image(systemName: "chevron.left") }.buttonStyle(.borderless)
                Text(list.name).font(.title2.weight(.semibold)).lineLimit(1)
                    .onTapGesture(count: 2) { renaming = true }
                    .help("Double-click to rename")
                Spacer()
                Menu {
                    Button("Rename…") { renaming = true }
                    Button("Delete setlist…", role: .destructive) { confirmDelete = true }
                } label: { Image(systemName: "ellipsis.circle") }
                .menuStyle(.borderlessButton).fixedSize()
                if !list.items.isEmpty {
                    Button("Start from the top") { app.playSetItem(list.id, 0) }.buttonStyle(.borderedProminent).tint(.mkTealDark)
                }
            }
            if list.items.isEmpty {
                Text("Add your first item: the metronome as it's set right now.")
                    .foregroundStyle(.secondary).padding(.vertical, 20)
                Spacer()
            } else {
                List {
                    ForEach(Array(list.items.enumerated()), id: \.element.id) { i, item in
                        row(i, item)
                    }
                    .onMove { store.move(list.id, from: $0, to: $1) }
                }
                .listStyle(.inset)
                Text("Drag to reorder. Double-click an item to play it.").font(.caption).foregroundStyle(.secondary)
            }
            HStack {
                Button("+ Metronome as it's set now") { addingPreset = true }
            }
        }
        .padding(20)
        .frame(maxWidth: .infinity, maxHeight: .infinity, alignment: .top)
        .sheet(isPresented: $renaming) {
            NameSheet(title: "Rename setlist", initial: list.name, placeholder: list.name) { store.rename(list.id, $0) }
        }
        .sheet(item: $renamingItem) { item in
            NameSheet(title: "Rename", initial: item.name, placeholder: item.name) { store.renameItem(list.id, item: item.id, name: $0) }
        }
        .sheet(isPresented: $addingPreset) {
            let s = m.settings
            NameSheet(title: "Add the metronome as it's set now", initial: "", placeholder: "Intro · " + SetlistStore.describe(s),
                      note: SetlistStore.describe(s) + ". Set it up on the Metronome page first, then add it here.") {
                store.addPreset(list.id, name: $0.isEmpty ? SetlistStore.describe(s) : $0, settings: s)
            }
        }
        .alert("Delete \"\(list.name)\"?", isPresented: $confirmDelete) {
            Button("Delete", role: .destructive) { store.delete(list.id); onBack() }
            Button("Cancel", role: .cancel) {}
        } message: { Text("The setlist goes away; your metronome settings stay.") }
    }

    private func row(_ i: Int, _ item: SetItem) -> some View {
        let current = store.active?.list == list.id && store.active?.index == i
        return HStack(spacing: 10) {
            Text("\(i + 1)").font(.headline).monospacedDigit().foregroundStyle(current ? Color.mkTealDark : .secondary).frame(width: 28)
            VStack(alignment: .leading, spacing: 2) {
                Text(item.name).lineLimit(1)
                Text("Metronome · " + SetlistStore.describe(item.settings)).font(.caption).foregroundStyle(.secondary).lineLimit(1)
            }
            Spacer()
            Button("Play") { app.playSetItem(list.id, i) }.buttonStyle(.borderless)
        }
        .padding(.vertical, 4)
        .listRowBackground(current ? Color.mkTeal.opacity(0.12) : Color.clear)
        .contentShape(Rectangle())
        .onTapGesture(count: 2) { app.playSetItem(list.id, i) }
        .contextMenu {
            Button("Play") { app.playSetItem(list.id, i) }
            Button("Update to the current metronome") { store.updatePreset(list.id, item: item.id, settings: m.settings) }
            Button("Rename…") { renamingItem = item }
            Divider()
            Button("Remove", role: .destructive) { store.removeItem(list.id, item: item.id) }
        }
    }


}

/** A small "type a name" sheet. */
struct NameSheet: View {
    let title: String
    let initial: String
    let placeholder: String
    var note: String? = nil
    let onSave: (String) -> Void
    @Environment(\.dismiss) private var dismiss
    @State private var text = ""

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            Text(title).font(.headline)
            TextField(placeholder, text: $text).textFieldStyle(.roundedBorder).onSubmit(save)
            if let note { Text(note).font(.caption).foregroundStyle(.secondary) }
            HStack {
                Spacer()
                Button("Cancel") { dismiss() }.keyboardShortcut(.cancelAction)
                Button("Save", action: save).buttonStyle(.borderedProminent).tint(.mkTealDark).keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 360)
        .onAppear { text = initial }
    }

    private func save() { onSave(text.trimmingCharacters(in: .whitespaces)); dismiss() }
}

/**
 * Shown above the Metronome page while you play through a setlist:
 * where you are, and Previous / Next for quick changes on stage.
 */
struct SetlistStrip: View {
    @EnvironmentObject private var store: SetlistStore
    @EnvironmentObject private var app: AppState

    var body: some View {
        if let a = store.active, let list = store.get(a.list) {
            let item = list.items.indices.contains(a.index) ? list.items[a.index] : nil
            let next = list.items.indices.contains(a.index + 1) ? list.items[a.index + 1] : nil
            HStack(spacing: 10) {
                Button { app.stepSetlist(-1) } label: { Image(systemName: "chevron.left") }.disabled(a.index == 0)
                VStack(alignment: .leading, spacing: 1) {
                    Text("\(list.name) · \(a.index + 1) of \(list.items.count)").font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    Text(item?.name ?? "").font(.callout.weight(.medium)).lineLimit(1)
                }
                Spacer()
                if let next {
                    Button { app.stepSetlist(1) } label: { Text("Next: \(next.name)").lineLimit(1) }
                        .buttonStyle(.borderedProminent).tint(.mkTealDark)
                        .help("Page Down, ⌘] or a MIDI pedal does this too")
                } else {
                    Text("Last one").font(.caption).foregroundStyle(.secondary)
                }
                Button { store.active = nil } label: { Image(systemName: "xmark") }.buttonStyle(.borderless).help("Stop following this setlist")
            }
            .padding(.horizontal, 16).padding(.vertical, 6)
            .background(Color.secondary.opacity(0.08))
            .overlay(alignment: .bottom) { Divider() }
        }
    }
}
