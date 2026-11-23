import SwiftUI

/**
 * The "part rows" view: one line per part (from the tab, or where the tempo
 * changes), up to 8 bars each, like a chord chart. Click a bar to jump there.
 */
struct PartRowsGrid: View {
    let rows: [PartRow]
    let bars: [Bar]
    let current: Int
    let selection: Range<Int>?
    let usual: String?
    let onBar: (Int) -> Void
    let onRename: (PartRow) -> Void
    let onLoop: (PartRow) -> Void

    var body: some View {
        LazyVStack(alignment: .leading, spacing: 6) {
            ForEach(rows) { row in
                HStack(spacing: 10) {
                    VStack(alignment: .leading, spacing: 0) {
                        Text(row.name ?? row.range)
                            .font(.callout)
                            .foregroundStyle(row.continued ? Color.secondary : Color.primary)
                            .lineLimit(1)
                        if row.name != nil && !row.continued { Text(row.range).font(.caption2).foregroundStyle(.tertiary) }
                    }
                    .frame(width: 120, alignment: .leading)
                    .contentShape(Rectangle())
                    .onTapGesture(count: 2) { onRename(row) }
                    .help("Double-click to name this row")

                    HStack(spacing: 4) {
                        ForEach(row.first..<row.end, id: \.self) { i in
                            BarCell(index: i, bar: bars[i], current: i == current, selected: selection?.contains(i) == true,
                                    odd: usual != nil && bars[i].meter != usual) { onBar(i) }
                                .id(i)
                        }
                        ForEach(0..<(PartRows.maxBars - row.size), id: \.self) { _ in Color.clear.frame(maxWidth: .infinity, minHeight: 34) }
                    }
                    Button { onLoop(row) } label: { Image(systemName: "repeat") }
                        .buttonStyle(.borderless).foregroundStyle(.secondary).help("Loop this row")
                }
            }
        }
    }
}

/** Every bar in one wrapping grid (the default view on Android). */
struct BarGrid: View {
    let bars: [Bar]
    let current: Int
    let selection: Range<Int>?
    let onBar: (Int) -> Void

    var body: some View {
        let usual = Dictionary(grouping: bars, by: \.meter).max { $0.value.count < $1.value.count }?.key
        LazyVGrid(columns: [GridItem(.adaptive(minimum: 46), spacing: 4)], spacing: 4) {
            ForEach(bars.indices, id: \.self) { i in
                BarCell(index: i, bar: bars[i], current: i == current, selected: selection?.contains(i) == true,
                        odd: bars[i].meter != usual) { onBar(i) }
                    .id(i)
            }
        }
    }
}

struct BarCell: View {
    let index: Int
    let bar: Bar
    let current: Bool
    let selected: Bool
    /** A bar in a different meter from most: its meter is shown. */
    let odd: Bool
    let onTap: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            Text("\(index + 1)").font(.system(size: 10)).monospacedDigit()
            if odd { Text(bar.meter).font(.system(size: 9)).foregroundStyle(current ? Color.white : Color.secondary) }
        }
        .frame(maxWidth: .infinity, minHeight: 34)
        .foregroundStyle(current ? Color.white : Color.primary)
        .background(RoundedRectangle(cornerRadius: 5).fill(current ? Color.mkTeal : Color.mkTealLight.opacity(bar.marker != nil ? 0.75 : 0.45)))
        .overlay(RoundedRectangle(cornerRadius: 5).stroke(selected ? Color.mkTealDark : .clear, lineWidth: 2))
        .contentShape(Rectangle())
        .onTapGesture(perform: onTap)
    }
}
