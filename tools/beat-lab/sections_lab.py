"""
Song-part lettering lab: can we label repeated parts (A, B, A, B, C...) from the
audio alone, without knowing which one is "the verse"?

Reference implementation for the app's PartRows/SectionLetters code. Checked
against the part markers ("Intro", "Verse 1", "Chorus") people put in the
Songsterr tabs of the benchmark songs.

  .venv/Scripts/python -I sections_lab.py            # all benchmark songs
  .venv/Scripts/python -I sections_lab.py --t 0.5    # try another threshold

RESULT (Nov 2026, 22 songs with part names): precision 0.39-0.44, recall 0.11-0.28
at thresholds 0.55-0.8. Chroma alone mixes up parts that share chords (riff songs)
and 8-bar rows rarely line up with where parts really start, so the app does NOT
letter parts from audio yet; rows use tab part names or bar ranges instead.

Steps (same as the app):
 1. chroma: how strong each of the 12 notes (C, C#, ... B) is, ~10 times a second
 2. average it per beat, so a part played faster or slower still lines up
 3. rows: cut the bars into rows at tempo/meter changes, otherwise every 8 bars
    (4 when 8 doesn't fit)
 4. two rows with the same number of beats are "the same part" when their beat
    chroma matches (mean cosine >= threshold); repeated rows share a letter
"""
import argparse
import gzip
import json
import re
import urllib.request
from pathlib import Path

import numpy as np
import soundfile as sf

SR, WIN, HOP = 22050, 4096, 2048
BENCH = Path("bench")


# ── 1. chroma ─────────────────────────────────────────────────────────────

def chroma(sig):
    """(frames, 12) note strengths; frame k covers samples k*HOP .. k*HOP+WIN."""
    win = np.hanning(WIN).astype(np.float32)
    freqs = np.fft.rfftfreq(WIN, 1 / SR)
    use = (freqs >= 55) & (freqs <= 4200)
    pc = (np.round(12 * np.log2(freqs[use] / 440.0)) % 12).astype(int)  # pitch class, A = 0
    n = max(0, (len(sig) - WIN) // HOP + 1)
    out = np.zeros((n, 12), np.float32)
    for k in range(n):
        mag = np.abs(np.fft.rfft(sig[k * HOP:k * HOP + WIN] * win))[use]
        np.add.at(out[k], pc, np.log1p(100 * mag))
    return out


def beat_chroma(ch, beats, ends):
    """Mean chroma between each beat and the next."""
    fps = SR / HOP
    rows = []
    for a, b in zip(beats, ends):
        i, j = int(a * fps), max(int(a * fps) + 1, int(b * fps))
        seg = ch[i:j]
        rows.append(seg.mean(0) if len(seg) else np.zeros(12, np.float32))
    v = np.array(rows)
    v -= v.mean(0)                                   # remove the song's overall key colour
    v /= np.linalg.norm(v, axis=1, keepdims=True) + 1e-9
    return v


# ── 3. rows ───────────────────────────────────────────────────────────────

def rows_of(bar_meters, breaks, size=8):
    """Bar index ranges [a, b). Breaks at given bar indices; otherwise chunks of `size`."""
    cuts = sorted(set([0, len(bar_meters)] + [b for b in breaks if 0 < b < len(bar_meters)]))
    out = []
    for a, b in zip(cuts, cuts[1:]):
        n = b - a
        step = size if n % size == 0 or n > size else n
        if n % size and n > size and n % 4 == 0:
            step = 4
        k = a
        while k < b:
            out.append((k, min(b, k + step)))
            k += step
    return out


# ── 4. letters ────────────────────────────────────────────────────────────

def letters(rows, bar_beats, bc, threshold):
    """Group rows that sound alike. Returns one group id per row (-1 = unique)."""
    first = np.concatenate([[0], np.cumsum(bar_beats)])
    sig = [bc[first[a]:first[b]] for a, b in rows]
    group = [-1] * len(rows)
    nxt = 0
    for i in range(len(rows)):
        best, bj = threshold, -1
        for j in range(i):
            if len(sig[j]) != len(sig[i]) or len(sig[i]) == 0:
                continue
            s = float(np.mean(np.sum(sig[i] * sig[j], axis=1)))
            if s > best:
                best, bj = s, j
        if bj >= 0:
            if group[bj] < 0:
                group[bj] = nxt; nxt += 1
            group[i] = group[bj]
    return group


# ── reference: Songsterr part markers ─────────────────────────────────────

UA = {"User-Agent": "Mozilla/5.0 (Android) MetronomeKiller"}


def get(url):
    raw = urllib.request.urlopen(urllib.request.Request(url, headers=UA), timeout=20).read()
    return json.loads(gzip.decompress(raw) if raw[:2] == b"\x1f\x8b" else raw)


def unroll(ms):
    order, i, start, passes, guard = [], 0, 0, {}, 0
    while i < len(ms) and guard < 20000:
        guard += 1
        m = ms[i]
        if m.get("repeatStart"): start = i
        p = passes.get(start, 1)
        v = m.get("alternateEnding")
        if v is not None and p not in v: i += 1; continue
        order.append(i)
        c = m.get("repeatClose")
        if c not in (None, False):
            times = c if isinstance(c, int) and not isinstance(c, bool) else 2
            if p < times: passes[start] = p + 1; i = start; continue
            passes[start] = 1
        i += 1
    return order


def clean_marker(t):
    t = re.sub(r"^\s*\[[^\]]*\]\s*", "", t).strip().rstrip(":").strip()
    return t


def part_kind(t):
    """'Verse 1' / 'Verse II' / 'verse:' -> 'verse' (for scoring only)."""
    t = clean_marker(t).lower()
    t = re.sub(r"\(.*?\)", "", t)
    t = re.sub(r"\b(\d+|i{1,3}|iv|v?i{0,3})\b", "", t)
    return re.sub(r"[^a-z]+", " ", t).strip()


def markers_for(truth, stem):
    cache = BENCH / "cache" / f"{stem}.markers.json"
    if cache.exists():
        return json.loads(cache.read_text())
    sid, rev = truth["songsterr"], truth["revision"]
    meta = get(f"https://www.songsterr.com/api/meta/{sid}")
    tracks = [meta.get("popularTrack", 0), meta.get("defaultTrack", 0), 0]
    res = []
    for tr in dict.fromkeys(t for t in tracks if t is not None and t >= 0):
        tab = get(f"https://dqsljvtekg760.cloudfront.net/{sid}/{rev}/{meta['image']}/{tr}.json")
        ms = tab["measures"]
        res = [ms[i].get("marker", {}).get("text") for i in unroll(ms)]
        if any(res): break
    cache.write_text(json.dumps(res))
    return res


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("--t", type=float, default=0.55)
    args = ap.parse_args()
    tot = {"tp": 0, "fp": 0, "fn": 0}
    for tf in sorted(BENCH.glob("*_truth.json")):
        stem = tf.name[:-len("_truth.json")]
        truth = json.loads(tf.read_text())
        bars = truth["bars"]
        try:
            marks = markers_for(truth, stem)
        except Exception as e:
            print(stem, "no tab markers:", e); continue
        if not any(marks):
            print(f"{stem:40s} (tab has no part names)"); continue
        # truth part per bar (bars are in playback order; the truth may be trimmed at the end)
        kind, cur = [], ""
        for i in range(len(bars)):
            m = marks[i] if i < len(marks) else None
            if m: cur = part_kind(m)
            kind.append(cur)
        sig, sr = sf.read(str(BENCH / f"{stem}.wav"), dtype="float32")
        if sig.ndim > 1: sig = sig.mean(1)
        assert sr == SR, sr
        ch = chroma(sig)
        nb = [int(b["sig"].split("/")[0]) for b in bars]
        beats, ends = [], []
        for b, n in zip(bars, nb):
            for k in range(n):
                beats.append(b["start"] + (b["end"] - b["start"]) * k / n)
                ends.append(b["start"] + (b["end"] - b["start"]) * (k + 1) / n)
        bc = beat_chroma(ch, beats, ends)
        meters = [b["sig"] for b in bars]
        rows = rows_of(meters, [])
        g = letters(rows, nb, bc, args.t)
        rk = [kind[a] for a, _ in rows]
        tp = fp = fn = 0
        for i in range(len(rows)):
            for j in range(i):
                same_t = rk[i] == rk[j] and rk[i] != ""
                same_p = g[i] >= 0 and g[i] == g[j]
                tp += same_t and same_p; fp += same_p and not same_t; fn += same_t and not same_p
        for k in tot: tot[k] += locals()[k]
        p = tp / max(1, tp + fp); r = tp / max(1, tp + fn)
        lab = "".join(chr(65 + x) if 0 <= x < 26 else "·" for x in g)
        print(f"{stem:40s} rows {len(rows):3d}  precision {p:.2f} recall {r:.2f}  {lab}")
    p = tot["tp"] / max(1, tot["tp"] + tot["fp"]); r = tot["tp"] / max(1, tot["tp"] + tot["fn"])
    print(f"ALL  precision {p:.2f}  recall {r:.2f}  (pairs of rows: 'same part' agreement with tab names)")


if __name__ == "__main__":
    main()
