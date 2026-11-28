import ClickCore
import SwiftUI

/**
 * The Practice page: five tools on top of the metronome (same meter, accents
 * and sound). A port of PracticeScreen.kt, laid out for a wide window.
 */
struct PracticeView: View {
    @EnvironmentObject private var m: Metronome
    @EnvironmentObject private var p: Practice
    @State private var showHistory = false

    var body: some View {
        let tool = p.saved.tool
        let on = p.running == tool
        VStack(spacing: 14) {
            HStack {
                Text("Practice").font(.title2.weight(.semibold))
                Spacer()
                TodayButton { showHistory = true }
            }
            Picker("", selection: Binding(get: { p.saved.tool }, set: { p.setTool($0) })) {
                ForEach(Practice.Tool.allCases) { Text($0.short).tag($0) }
            }
            .pickerStyle(.segmented)
            .labelsHidden()

            BeatRow(beats: m.settings.beats, current: p.running != nil ? m.currentBeat : -1) { m.cycleBeat($0) }

            ScrollView {
                Group {
                    switch tool {
                    case .speed: SpeedTrainerView(on: on)
                    case .gaps: GapClickView(on: on)
                    case .timing: TimingCheckView(on: on)
                    case .ladder: LadderView(on: on)
                    case .poly: PolyView(on: on)
                    }
                }
                .padding(.bottom, 8)
            }

            HStack(spacing: 14) {
                Button { p.toggle() } label: {
                    Label(p.running != nil ? "Stop" : "Start", systemImage: p.running != nil ? "stop.fill" : "play.fill")
                        .frame(minWidth: 90)
                }
                .controlSize(.large).buttonStyle(.borderedProminent).tint(.mkTealDark)
                Text("\(m.settings.meter.label) · \(m.settings.sound.label) · \(m.settings.bpm) BPM. Uses the metronome's meter, accents and sound.")
                    .font(.caption).foregroundStyle(.secondary)
                Spacer()
            }
        }
        .padding(24)
        .frame(maxWidth: 640)
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .sheet(isPresented: $showHistory) { HistorySheet() }
    }
}

// ── Speed trainer ──────────────────────────────────────────────────────────

private struct SpeedTrainerView: View {
    let on: Bool
    @EnvironmentObject private var m: Metronome
    @EnvironmentObject private var p: Practice

    var body: some View {
        let plan = p.saved.trainer
        let meter = m.settings.meter
        // How long one bar lasts at a tempo, the way the engine counts it
        // (the last beat of 7/8-style meters can be shorter).
        let barSeconds = { (b: Int) in (Double(meter.beatCount) - 1 + meter.lastBeatFraction) * 60 / meter.beatsPerMinute(b) }
        let step = plan.step(ofBar: p.trainerBar)
        let live = on && p.countingIn == 0
        VStack(spacing: 6) {
            VStack(spacing: 0) {
                Text(on ? "now" : "starts at").font(.caption).foregroundStyle(.secondary)
                Text("\(on ? m.settings.bpm : plan.from)").font(.system(size: 52, weight: .semibold, design: .rounded)).monospacedDigit()
                Group {
                    if on && p.countingIn > 0 { Text("BPM · count-in \(p.countingIn) of \(p.saved.countIn)") }
                    else if on && p.held { Text("BPM · holding this tempo") }
                    else if on { Text("BPM · step \(min(step, plan.lastStep) + 1) of \(plan.lastStep + 1) · bar \(p.trainerBar % max(plan.every, 1) + 1) of \(plan.every)") }
                    else { Text("BPM · \(plan.steps) tempos, \(fmtDuration(Int(plan.secondsToTop(fromBar: 0, barSeconds: barSeconds).rounded()))) to reach \(plan.to)") }
                }
                .font(.caption).foregroundStyle(.secondary)
                if live && !p.held {
                    let left = Int(plan.secondsToTop(fromBar: p.trainerBar, barSeconds: barSeconds).rounded())
                    Text(left > 0 ? "\(plan.to) BPM in \(fmtDuration(left))" : "At the top").font(.caption).foregroundStyle(Color.mkTealDark)
                }
            }
            StepGraph(plan: plan, bar: on ? p.trainerBar : -1).frame(height: 64).padding(.vertical, 6)
            // While it runs: go back a step, hold where you are, or jump ahead (also ← H → on the keyboard).
            if on {
                HStack(spacing: 8) {
                    Button { p.skipStep(-1) } label: { Text("− step").frame(maxWidth: .infinity) }
                    Button { p.setHeld(!p.held) } label: { Text(p.held ? "Carry on" : "Hold").frame(maxWidth: .infinity) }
                        .tint(p.held ? .mkTealDark : nil).buttonStyle(.borderedProminent)
                    Button { p.skipStep(1) } label: { Text("+ step").frame(maxWidth: .infinity) }
                }
                .controlSize(.large).disabled(!live)
                .help("← and → skip a step, H holds the tempo")
            }
            StepRow("From", "\(plan.from) BPM", typed: plan.from, onType: { var x = plan; x.from = $0; p.setTrainer(x) }) { var x = plan; x.from += $0; p.setTrainer(x) }
            StepRow("To", "\(plan.to) BPM", typed: plan.to, onType: { var x = plan; x.to = $0; p.setTrainer(x) }) { var x = plan; x.to += $0; p.setTrainer(x) }
            StepRow("Step", "\(plan.to >= plan.from ? "+" : "−")\(plan.step) BPM") { var x = plan; x.step += $0; p.setTrainer(x) }
            StepRow("Every", bars(plan.every)) { var x = plan; x.every += $0; p.setTrainer(x) }
            ChoiceRow("At the top", plan.top.label) {
                var x = plan; x.top = TrainerPlan.Top(rawValue: (plan.top.rawValue + 1) % 3) ?? .hold; p.setTrainer(x)
            }
            CountInRow()
        }
    }
}

/** The tempo staircase, with the part you've played drawn in teal. */
private struct StepGraph: View {
    let plan: TrainerPlan
    let bar: Int

    var body: some View {
        Canvas { ctx, size in
            let total = min(max(plan.totalBars, 1), 400)
            let lo = Double(min(plan.from, plan.to))
            let hi = max(Double(max(plan.from, plan.to)), lo + 1)
            func x(_ b: Int) -> Double { size.width * Double(b) / Double(total) }
            func y(_ bpm: Int) -> Double { size.height - 4 - (size.height - 8) * (Double(bpm) - lo) / (hi - lo) }
            for b in 0..<total {
                let now = plan.bpm(forBar: b)
                let played = bar >= 0 && b <= bar % total
                var path = Path()
                path.move(to: CGPoint(x: x(b), y: y(now)))
                path.addLine(to: CGPoint(x: x(b + 1), y: y(now)))
                let next = plan.bpm(forBar: b + 1)
                if b + 1 < total && next != now { path.addLine(to: CGPoint(x: x(b + 1), y: y(next))) }
                ctx.stroke(path, with: .color(played ? .mkTeal : Color.secondary.opacity(0.25)), style: StrokeStyle(lineWidth: 3, lineCap: .round))
            }
        }
    }
}

// ── Gap click ──────────────────────────────────────────────────────────────

private struct GapClickView: View {
    let on: Bool
    @EnvironmentObject private var p: Practice

    var body: some View {
        let plan = p.saved.gaps
        let g = p.gapState
        VStack(spacing: 6) {
            // One round of the pattern: filled = click, outlined = silent
            HStack(spacing: 4) {
                ForEach(0..<(plan.on + plan.off), id: \.self) { i in
                    let isOn = i < plan.on
                    let here = on && g.pos > 0 && ((isOn && !g.silent && i == g.pos - 1) || (!isOn && g.silent && i - plan.on == g.pos - 1))
                    RoundedRectangle(cornerRadius: 5)
                        .fill(isOn ? Color.mkTeal : Color.clear)
                        .overlay(RoundedRectangle(cornerRadius: 5).strokeBorder(here ? Color.primary : Color.secondary.opacity(isOn ? 0 : 0.5), lineWidth: here ? 2.5 : 1.5))
                        .frame(height: 26)
                }
            }
            Text(!on ? "\(bars(plan.on)) on · \(plan.off) silent"
                 : p.countingIn > 0 ? "count-in \(p.countingIn) of \(p.saved.countIn)"
                 : g.pos == 0 ? "starting…" : g.silent ? "silent \(g.pos) of \(g.len)" : "click \(g.pos) of \(g.len)")
                .font(.system(size: 28, weight: .semibold))
                .foregroundStyle(on && g.silent ? Color.mkTeal : Color.primary)
                .padding(.vertical, 6)
            StepRow("Click plays", bars(plan.on)) { var x = plan; x.on += $0; p.setGaps(x) }
            StepRow("Then silent", bars(plan.off)) { var x = plan; x.off += $0; p.setGaps(x) }
            StepRow("Random mutes", plan.randomMute == 0 ? "Off" : "\(plan.randomMute)% of beats") { var x = plan; x.randomMute += $0 * 10; p.setGaps(x) }
            ChoiceRow("Silent bars grow", plan.grow ? "+1 each round" : "Off") { var x = plan; x.grow.toggle(); p.setGaps(x) }
            CountInRow()
            ChoiceRow("Check my timing", plan.checkTiming ? "On" : "Off") { var x = plan; x.checkTiming.toggle(); p.setGaps(x) }
            if plan.checkTiming {
                DotPlot(taps: p.taps).frame(height: 90)
                let with = Practice.stats(p.taps.filter { !$0.inGap }.map(\.ms))
                let gaps = Practice.stats(p.taps.filter(\.inGap).map(\.ms))
                HStack(spacing: 8) {
                    StatCard(label: "With the click", value: avgText(with), note: avgNote(with))
                    StatCard(label: "In the gaps", value: avgText(gaps), note: avgNote(gaps))
                }
                TapPad(on: on, hint: on ? "Click here or press T on each beat, gaps included" : "Press Start, then tap along").frame(height: 110)
            }
            Hint("The beat blocks keep moving through the gaps, so you can check yourself.")
        }
    }
}

// ── Timing check ───────────────────────────────────────────────────────────

private struct TimingCheckView: View {
    let on: Bool
    @EnvironmentObject private var p: Practice

    var body: some View {
        let st = Practice.stats(p.taps.map(\.ms))
        VStack(spacing: 8) {
            DotPlot(taps: p.taps).frame(height: 120)
            HStack(spacing: 8) {
                StatCard(label: "Average", value: avgText(st), note: avgNote(st))
                StatCard(label: "Spread", value: st.map { "±\(Int($0.sd.rounded())) ms" } ?? "–", note: spreadNote(st))
            }
            TapPad(on: on, hint: on ? "Click here or press T on each beat" : "Press Start, then tap along here").frame(height: 160)
            Hint("Your taps are compared with when each click actually left the speakers.")
        }
    }
}

private func avgText(_ s: (mean: Double, sd: Double)?) -> String { s.map { "\($0.mean >= 0 ? "+" : "")\(Int($0.mean.rounded())) ms" } ?? "–" }
private func avgNote(_ s: (mean: Double, sd: Double)?) -> String {
    guard let m = s?.mean else { return "tap a few beats" }
    if abs(m) < 8 { return "right on" }
    return m < 0 ? (m < -25 ? "rushing" : "a bit early") : (m > 25 ? "dragging" : "a bit late")
}
private func spreadNote(_ s: (mean: Double, sd: Double)?) -> String {
    guard let sd = s?.sd else { return "" }
    return sd < 12 ? "very steady" : sd < 25 ? "steady" : "uneven"
}

/** Newest tap at the bottom, early on the left, late on the right (±60 ms). Taps in a silent bar are hollow. */
private struct DotPlot: View {
    let taps: [Tap]

    var body: some View {
        ZStack(alignment: .bottom) {
            Canvas { ctx, size in
                let mid = size.width / 2
                var line = Path()
                line.move(to: CGPoint(x: mid, y: 0))
                line.addLine(to: CGPoint(x: mid, y: size.height - 14))
                ctx.stroke(line, with: .color(.secondary.opacity(0.5)), style: StrokeStyle(lineWidth: 1.5, dash: [4, 4]))
                let rowH = (size.height - 18) / Double(Practice.maxTaps)
                for (i, t) in taps.enumerated() {
                    let x = mid + (min(max(t.ms, -60), 60) / 60) * (mid - 8)
                    let rect = CGRect(x: x - 4, y: 3 + Double(i) * rowH, width: 8, height: 8)
                    let color: Color = abs(t.ms) < 15 ? .mkTeal : .orange
                    if t.inGap { ctx.stroke(Path(ellipseIn: rect), with: .color(color), lineWidth: 1.5) }
                    else { ctx.fill(Path(ellipseIn: rect), with: .color(color)) }
                }
            }
            .padding(.horizontal, 8).padding(.vertical, 4)
            HStack { Text("early"); Spacer(); Text("late") }.font(.caption2).foregroundStyle(.secondary).padding(.horizontal, 8).padding(.bottom, 2)
        }
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.08)))
    }
}

/**
 * The tap pad. LEARN: we take the time the mouse button went DOWN (from the
 * event itself), not when SwiftUI got round to telling us.
 */
private struct TapPad: View {
    let on: Bool
    let hint: String
    @EnvironmentObject private var p: Practice
    @State private var down = false

    var body: some View {
        RoundedRectangle(cornerRadius: 14)
            .strokeBorder(Color.secondary.opacity(down ? 0.8 : 0.35), lineWidth: 1.5)
            .background(RoundedRectangle(cornerRadius: 14).fill(down ? Color.mkTeal.opacity(0.12) : Color.clear))
            .overlay(Text(hint).font(.headline).foregroundStyle(.secondary).multilineTextAlignment(.center).padding())
            .contentShape(Rectangle())
            .gesture(DragGesture(minimumDistance: 0)
                .onChanged { _ in if !down { down = true; if on { p.tap(eventTimeNs()) } } }
                .onEnded { _ in down = false })
    }
}

private struct StatCard: View {
    let label: String, value: String, note: String
    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(label).font(.caption).foregroundStyle(.secondary)
            Text(value).font(.title3.weight(.semibold)).monospacedDigit()
            Text(note).font(.caption).foregroundStyle(.secondary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(10)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.08)))
    }
}

// ── Subdivision ladder ─────────────────────────────────────────────────────

private struct LadderView: View {
    let on: Bool
    @EnvironmentObject private var p: Practice

    var body: some View {
        let plan = p.saved.ladder
        let cycle = plan.cycle
        let at = on && p.bar >= 0 ? (p.bar / max(plan.every, 1)) % cycle.count : -1
        VStack(spacing: 8) {
            Text(on ? LadderPlan.name(p.ladderSub) : "Same tempo, more notes").font(.system(size: 28, weight: .semibold)).padding(.vertical, 6)
            HStack(alignment: .bottom, spacing: 4) {
                ForEach(Array(cycle.enumerated()), id: \.offset) { i, n in
                    RoundedRectangle(cornerRadius: 5)
                        .fill(i == at ? Color.mkTeal : Color.secondary.opacity(0.15))
                        .frame(height: CGFloat(14 + n * 10))
                        .overlay(Text("\(n)").font(.caption2).foregroundStyle(i == at ? .white : .secondary))
                }
            }
            HStack {
                Text("Rungs").font(.callout).foregroundStyle(.secondary)
                Spacer()
                ForEach(1...4, id: \.self) { n in
                    Toggle(LadderPlan.name(n), isOn: Binding(get: { plan.rungs.contains(n) }, set: { inIt in
                        var x = plan
                        x.rungs = inIt ? (plan.rungs + [n]).sorted() : plan.rungs.filter { $0 != n }
                        p.setLadder(x)
                    }))
                    .toggleStyle(.button)
                }
            }
            .padding(.horizontal, 12).padding(.vertical, 6)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.08)))
            StepRow("Each rung", bars(plan.every)) { var x = plan; x.every += $0; p.setLadder(x) }
            ChoiceRow("At the top", plan.backDown ? "Come back down" : "Start over") { var x = plan; x.backDown.toggle(); p.setLadder(x) }
            Hint("Changes land exactly on the next bar's 1. Keep the tempo; only the notes get denser.")
        }
    }
}

// ── Polyrhythm ─────────────────────────────────────────────────────────────

private struct PolyView: View {
    let on: Bool
    @EnvironmentObject private var m: Metronome
    @EnvironmentObject private var p: Practice

    var body: some View {
        let plan = p.saved.poly
        let beats = m.settings.meter.beatCount
        VStack(spacing: 8) {
            Text("\(plan.count) against \(beats)").font(.system(size: 32, weight: .semibold)).padding(.vertical, 6)
            // LEARN: TimelineView redraws once per screen frame only while it's
            // on screen and running, so the moving dot costs nothing when stopped.
            TimelineView(.animation(paused: !on || p.barStartNs == 0)) { _ in
                let hit = polyHit(count: plan.count, beats: beats)
                VStack(spacing: 8) {
                    PolyRow(label: "Beats", n: beats, lit: on ? m.currentBeat : -1, color: .primary)
                    PolyRow(label: "\(plan.count)s", n: plan.count, lit: hit, color: .mkTeal)
                }
            }
            Hint("Both rows start together on the 1. The second sound plays the \(plan.count).")
            StepRow("Cross-rhythm", "\(plan.count) per bar") { var x = plan; x.count += $0; p.setPoly(x) }
            ChoiceRow("Normal click", plan.mainOnlyOne ? "Only the 1" : "Every beat") { var x = plan; x.mainOnlyOne.toggle(); p.setPoly(x) }
            Hint("Change the beats per bar with the metronome's time signature (4/4 gives 3 against 4).")
        }
    }

    private func polyHit(count: Int, beats: Int) -> Int {
        guard on, p.barStartNs > 0 else { return -1 }
        let s = m.settings
        let barNs = Double(beats) * 60e9 / s.meter.beatsPerMinute(s.bpm)
        let f = min(max(Double(mk_now_ns() - p.barStartNs) / barNs, 0), 0.999)
        return Int(f * Double(count))
    }
}

private struct PolyRow: View {
    let label: String, n: Int, lit: Int, color: Color
    var body: some View {
        HStack {
            Text(label).font(.caption).foregroundStyle(.secondary).frame(width: 52, alignment: .leading)
            Canvas { ctx, size in
                var line = Path()
                line.move(to: CGPoint(x: 0, y: size.height / 2))
                line.addLine(to: CGPoint(x: size.width, y: size.height / 2))
                ctx.stroke(line, with: .color(.secondary.opacity(0.3)), lineWidth: 1)
                for i in 0..<max(n, 1) {
                    let x = 10 + (size.width - 20) * Double(i) / Double(max(n, 1))
                    let r: Double = i == lit ? 9 : 5
                    ctx.fill(Path(ellipseIn: CGRect(x: x - r, y: size.height / 2 - r, width: 2 * r, height: 2 * r)),
                             with: .color(i == lit ? color : .secondary.opacity(0.5)))
                }
            }
            .frame(height: 30)
        }
    }
}

// ── Today and history ──────────────────────────────────────────────────────

private func dayStart() -> Date { Calendar.current.startOfDay(for: Date()) }

private func fmtDuration(_ sec: Int) -> String {
    sec >= 3600 ? String(format: "%d:%02d:%02d", sec / 3600, sec / 60 % 60, sec % 60) : String(format: "%d:%02d", sec / 60, sec % 60)
}

/** "Today 12:34 / 30 min", ticking once a second while a tool runs. */
private struct TodayButton: View {
    let action: () -> Void
    @EnvironmentObject private var p: Practice

    var body: some View {
        // LEARN: this redraws once a second, and only while practice is running.
        TimelineView(.animation(minimumInterval: 1, paused: p.runStartedAt == nil)) { _ in
            let sec = p.seconds(since: dayStart())
            let goal = p.saved.goalMinutes
            Button(action: action) {
                VStack(alignment: .trailing, spacing: 3) {
                    Text("Today \(fmtDuration(sec))\(goal > 0 ? " / \(goal) min" : "")").font(.callout).monospacedDigit()
                    if goal > 0 {
                        ProgressView(value: min(Double(sec) / Double(goal * 60), 1)).tint(.mkTeal).frame(width: 110)
                    } else {
                        Text("History").font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .buttonStyle(.plain)
        }
    }
}

private struct HistorySheet: View {
    @EnvironmentObject private var p: Practice
    @Environment(\.dismiss) private var dismiss
    @State private var confirmClear = false

    var body: some View {
        let week = Calendar.current.date(byAdding: .day, value: -6, to: dayStart()) ?? dayStart()
        VStack(alignment: .leading, spacing: 12) {
            Text("Practice history").font(.headline)
            HStack(spacing: 8) {
                StatCard(label: "Today", value: fmtDuration(p.seconds(since: dayStart())), note: p.saved.goalMinutes > 0 ? "goal \(p.saved.goalMinutes) min" : "")
                StatCard(label: "Last 7 days", value: fmtDuration(p.seconds(since: week)), note: "\(p.history.filter { $0.startedAt >= week }.count) sessions")
            }
            Picker("Daily goal", selection: Binding(get: { p.saved.goalMinutes }, set: { p.setGoal($0) })) {
                ForEach([0, 10, 20, 30, 45, 60], id: \.self) { Text($0 == 0 ? "Off" : "\($0) min").tag($0) }
            }
            .pickerStyle(.segmented)
            List {
                if p.history.isEmpty {
                    Text("Sessions show up here when you stop a practice tool (anything over 20 seconds).").foregroundStyle(.secondary)
                }
                ForEach(p.history.reversed()) { e in
                    VStack(alignment: .leading, spacing: 2) {
                        HStack { Text(e.tool).fontWeight(.medium); Spacer(); Text(fmtDuration(e.seconds)).monospacedDigit() }
                        Text("\(e.startedAt.formatted(.dateTime.weekday().day().month().hour().minute())) · \(e.detail)")
                            .font(.caption).foregroundStyle(.secondary)
                    }
                }
            }
            .frame(height: 300)
            HStack {
                if !p.history.isEmpty {
                    Button(confirmClear ? "Click again to delete all history" : "Clear history", role: .destructive) {
                        if confirmClear { p.clearHistory(); confirmClear = false } else { confirmClear = true }
                    }
                }
                Spacer()
                Button("Done") { dismiss() }.keyboardShortcut(.defaultAction)
            }
        }
        .padding(20)
        .frame(width: 480)
    }
}

// ── Small parts ────────────────────────────────────────────────────────────

private func bars(_ n: Int) -> String { "\(n) bar\(n == 1 ? "" : "s")" }

/**
 * A setting row with − and + buttons. change gets −1 or +1 (±10 with Option
 * held). With [onType], the number is a box you can click and type into.
 */
private struct StepRow: View {
    let label: String, value: String
    var typed: Int? = nil
    var onType: ((Int) -> Void)? = nil
    let change: (Int) -> Void
    @State private var text = ""
    @FocusState private var typing: Bool
    init(_ label: String, _ value: String, typed: Int? = nil, onType: ((Int) -> Void)? = nil, change: @escaping (Int) -> Void) {
        self.label = label; self.value = value; self.typed = typed; self.onType = onType; self.change = change
    }

    /** LEARN: NSEvent.modifierFlags is the keys held down right now, so Option-click jumps by 10. */
    private var by: Int { NSEvent.modifierFlags.contains(.option) ? 10 : 1 }

    var body: some View {
        HStack {
            Text(label).foregroundStyle(.secondary)
            Spacer()
            if let typed, let onType {
                HStack(spacing: 4) {
                    TextField("", text: $text)
                        .textFieldStyle(.roundedBorder).multilineTextAlignment(.trailing).monospacedDigit()
                        .frame(width: 56).focused($typing)
                        .onSubmit { if let n = Int(text) { onType(n) }; text = "\(typed)" }
                        .onChange(of: typing) { on in if !on, let n = Int(text), n != typed { onType(n) } }
                        .onAppear { text = "\(typed)" }
                        .onChange(of: typed) { v in if !typing { text = "\(v)" } }
                    Text("BPM").foregroundStyle(.secondary)
                }
            } else {
                Text(value).fontWeight(.medium).monospacedDigit()
            }
            Button { change(-by) } label: { Image(systemName: "minus").frame(width: 22, height: 18) }
            Button { change(by) } label: { Image(systemName: "plus").frame(width: 22, height: 18) }
        }
        .help("Option-click − or + to change by 10")
        .padding(.horizontal, 12).padding(.vertical, 6)
        .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.08)))
    }
}

/** A setting row you click to step through its choices. */
private struct ChoiceRow: View {
    let label: String, value: String
    let next: () -> Void
    init(_ label: String, _ value: String, next: @escaping () -> Void) { self.label = label; self.value = value; self.next = next }

    var body: some View {
        Button(action: next) {
            HStack {
                Text(label).foregroundStyle(.secondary)
                Spacer()
                Text(value).fontWeight(.medium).foregroundStyle(Color.mkTealDark)
            }
            .padding(.horizontal, 12).padding(.vertical, 9)
            .background(RoundedRectangle(cornerRadius: 10).fill(Color.secondary.opacity(0.08)))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

private struct CountInRow: View {
    @EnvironmentObject private var p: Practice
    var body: some View {
        let n = p.saved.countIn
        ChoiceRow("Count-in", n == 0 ? "Off" : n == 1 ? "1 bar" : "2 bars") { p.setCountIn((n + 1) % 3) }
    }
}

private struct Hint: View {
    let text: String
    init(_ t: String) { text = t }
    var body: some View {
        Text(text).font(.caption).foregroundStyle(.secondary).frame(maxWidth: .infinity, alignment: .leading).padding(.top, 2)
    }
}
