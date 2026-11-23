"""
Scores our tempo map against a Songsterr tab, a human-made reference with a
tempo and time signature for every bar.

1. Build the reference timeline: each bar lasts numerator * (4/denominator)
   quarter notes at the tab's tempo (7/8 = 3.5 quarters).
2. Line it up with the audio: try start offsets and keep the one where the
   most reference downbeats land within 70 ms of a beat our model found.
3. Second by second, compare:
   - tempo: ours within 4 % of the reference, or exactly half/double
     (an "octave" mistake the user can fix with one tap);
   - bar length: our beats-per-bar vs the reference (in quarter notes).
   - beat accuracy: F-measure of our beats vs the reference beat grid.

The tab JSON is downloaded locally into out/ref and is NOT committed
(it's Songsterr's content). Usage:
    python -I compare_songsterr.py out/ref/racecar_part6.json out/racecar.json
"""
import json
import sys
from pathlib import Path

import numpy as np

sys.path.insert(0, str(Path(__file__).parent))
from analyze import f_measure  # noqa: E402


def reference(tab: dict):
    tempos = {t["measure"]: t["bpm"] * (4 / t.get("type", 4)) / 4 * 4 / 4 for t in tab["automations"]["tempo"]}
    # tempo "type" is the beat unit (4 = quarter). Convert to quarter notes per minute.
    tempos = {t["measure"]: t["bpm"] * 4 / t.get("type", 4) for t in tab["automations"]["tempo"]}
    sig, qpm, t = (4, 4), 120.0, 0.0
    bars, beats = [], []
    for i, m in enumerate(tab["measures"]):
        sig = tuple(m.get("signature", sig))
        qpm = tempos.get(i, qpm)
        quarters = sig[0] * 4 / sig[1]
        q = 60.0 / qpm
        bars.append({"start": t, "end": t + quarters * q, "quarters": quarters, "sig": f"{sig[0]}/{sig[1]}", "qpm": qpm})
        k = 0.0
        while k < quarters - 1e-6:
            beats.append(t + k * q)
            k += 1.0
        t += quarters * q
    return bars, np.array(beats)


def best_offset(ref_downbeats, our_beats, lo=-5.0, hi=60.0):
    best = (0.0, -1)
    for off in np.arange(lo, hi, 0.01):
        d = ref_downbeats + off
        idx = np.searchsorted(our_beats, d)
        idx = np.clip(idx, 1, len(our_beats) - 1)
        near = np.minimum(np.abs(our_beats[idx] - d), np.abs(our_beats[idx - 1] - d))
        score = int(np.sum(near < 0.07))
        if score > best[1]:
            best = (off, score)
    return best


def main():
    tab = json.load(open(sys.argv[1], encoding="utf-8"))
    ours = json.load(open(sys.argv[2], encoding="utf-8"))
    bars, ref_beats = reference(tab)
    our_beats = np.array(ours["beats"])
    off, hits = best_offset(np.array([b["start"] for b in bars]), our_beats)
    print(f"reference: {len(bars)} bars, {bars[-1]['end']:.1f} s at {bars[0]['qpm']:.0f} BPM; "
          f"audio {ours['duration']:.1f} s; best offset {off:+.2f} s ({hits}/{len(bars)} downbeats matched)")

    # second-by-second comparison over the span the tab covers
    t0, t1 = off, min(off + bars[-1]["end"], ours["duration"])
    secs = [s for s in ours["sections"] if s["kind"] != "free"]
    stats = {"tempo_ok": 0, "tempo_octave": 0, "tempo_wrong": 0, "no_tempo": 0, "bar_ok": 0, "bar_checked": 0}
    wrong_spans = []
    for t in np.arange(t0, t1, 1.0):
        rb = next((b for b in bars if b["start"] + off <= t < b["end"] + off), None)
        s = next((s for s in secs if s["start"] <= t < s["end"]), None)
        if rb is None:
            continue
        if s is None:
            stats["no_tempo"] += 1
            continue
        r = s["bpm"] / rb["qpm"]
        if abs(r - 1) < 0.04:
            stats["tempo_ok"] += 1
            stats["bar_checked"] += 1
            if abs(s["beats_per_bar"] - rb["quarters"]) < 0.01:
                stats["bar_ok"] += 1
        elif abs(r - 2) < 0.08 or abs(r - 0.5) < 0.02:
            stats["tempo_octave"] += 1
        else:
            stats["tempo_wrong"] += 1
            wrong_spans.append((round(t), s["bpm"]))
    n = sum(stats[k] for k in ("tempo_ok", "tempo_octave", "tempo_wrong", "no_tempo"))
    pct = lambda k: f"{100 * stats[k] / n:.0f}%"
    print(f"tempo correct: {pct('tempo_ok')}, half/double: {pct('tempo_octave')}, "
          f"wrong: {pct('tempo_wrong')}, no click: {pct('no_tempo')}  (of {n} s)")
    if stats["bar_checked"]:
        print(f"bar length matches the tab: {100 * stats['bar_ok'] / stats['bar_checked']:.0f}% "
              f"of the time where tempo was right")
    ref_in_audio = ref_beats + off
    print(f"beat F-measure vs tab grid: {f_measure(our_beats, ref_in_audio[ref_in_audio < ours['duration']]):.3f}")
    ref_db = np.array([b["start"] for b in bars]) + off
    print(f"downbeat F-measure vs tab: {f_measure(np.array(ours['downbeats']), ref_db[ref_db < ours['duration']]):.3f}")
    # bar by bar: for each tab bar, is there one of our bars starting within 70 ms with the same length?
    ob = ours.get("bars", [])
    if ob:
        starts = np.array([b["start"] for b in ob])
        same, found = 0, 0
        for b in bars:
            q = b["quarters"]
            if abs(q - round(q)) > 1e-6:
                continue  # 7/8, 9/8: half-beat bars can't match a whole-beat count; skip
            i = int(np.argmin(np.abs(starts - (b["start"] + off))))
            if abs(starts[i] - (b["start"] + off)) < 0.07:
                found += 1
                same += ob[i]["beats"] == round(q)
        whole = sum(1 for b in bars if abs(b["quarters"] - round(b["quarters"])) < 1e-6)
        print(f"bar-by-bar: start found for {found}/{whole} tab bars; length right for {same}/{whole} "
              f"({100 * same / whole:.0f}%)")
    if wrong_spans:
        spans, cur = [], None
        for t, b in wrong_spans:
            if cur and t == cur[1] + 1 and b == cur[2]:
                cur[1] = t
            else:
                cur = [t, t, b]
                spans.append(cur)
        print("wrong-tempo stretches (from s, to s, our BPM):", [tuple(x) for x in spans])


if __name__ == "__main__":
    main()
