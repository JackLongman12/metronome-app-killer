"""
Turns beat times into a TEMPO MAP: sections with a steady (or ramping) tempo
and a time signature. The app will reuse this logic (ported to Kotlin) after
the beat_this model has found the beats.

v2 approach
1. CHANGE POINTS by dynamic programming. Within a constant-tempo section, beat
   times lie on a straight line (time = start + index * beat_length). We find
   the split into sections that best fits straight lines, paying a fixed
   PENALTY per extra section, so we only split when the tempo really changes.
   (Fitting a line also removes the model's 20 ms frame jitter: per-beat BPM
   from raw intervals wobbles a lot, the slope of a line doesn't.)
2. RAMPS: runs of neighbouring sections that keep moving the same way in
   small steps are merged into one "ramp" section (accelerando/ritardando).
3. HALF/DOUBLE TIME: a section at almost exactly 1/2 or 2x its neighbours'
   tempo is usually the model switching counting level, not the band; we
   re-count it at the neighbours' level so the click doesn't jump.
4. BAR LENGTH: for each section we try every bar length from 2 to 16 beats
   and every starting offset, and score how much stronger the model's
   downbeat signal is on the "1"s than on the other beats. The best wins.
5. GAPS (beats stop) become "free" sections with no click.

Output (format version 1, also what the app stores per song):
{"version": 1, "duration": s, "beats": [...], "downbeats": [...],
 "sections": [{"start", "end", "kind": "steady"|"ramp"|"free",
               "bpm", "bpm_end", "beats_per_bar", "meter", "bars", "beats",
               "first_downbeat"}]}
"""
from __future__ import annotations

import numpy as np

PENALTY = 0.004        # cost (s^2) of starting a new section; higher = fewer sections
MIN_SEG = 8            # a section has at least this many beats
GAP_FACTOR = 2.5       # an interval this many times its neighbours = beats stopped
RAMP_STEP = 0.07       # neighbouring sections within 7 % may form a ramp
OCTAVE_TOL = 0.05      # within 5 % of exactly 2x or 1/2 = half/double time
TRUST_BEATS = 16       # sections shorter than this that disagree with both
                       # neighbours are usually fills the model mis-counted
FPS = 50               # beat_this frame rate


# ── 1. change points ────────────────────────────────────────────────────────

def _segment(t: np.ndarray) -> list[tuple[int, int]]:
    """Split beat times t[0..n) into [a, b) index ranges with constant tempo."""
    n = len(t)
    if n < 2 * MIN_SEG:
        return [(0, n)]
    x = np.arange(n, dtype=float)
    # Prefix sums let us compute any segment's line-fit error in O(1).
    S = lambda v: np.concatenate([[0.0], np.cumsum(v)])
    sx, sy, sxx, sxy, syy = S(x), S(t), S(x * x), S(x * t), S(t * t)

    def sse(a, b):  # vectorised over a (array), b scalar; segment [a, b)
        m = b - a
        X, Y = sx[b] - sx[a], sy[b] - sy[a]
        XX, XY, YY = sxx[b] - sxx[a], sxy[b] - sxy[a], syy[b] - syy[a]
        vx = XX - X * X / m
        cov = XY - X * Y / m
        vy = YY - Y * Y / m
        return np.maximum(vy - cov * cov / np.maximum(vx, 1e-12), 0.0)

    best = np.full(n + 1, np.inf)
    best[0] = 0.0
    prev = np.zeros(n + 1, dtype=int)
    for b in range(MIN_SEG, n + 1):
        a = np.arange(0, b - MIN_SEG + 1)
        a = a[np.isfinite(best[a])]
        if len(a) == 0:
            continue
        cost = best[a] + sse(a, b) + PENALTY
        k = int(np.argmin(cost))
        best[b], prev[b] = cost[k], a[k]
    cuts, b = [], n
    while b > 0:
        a = prev[b]
        cuts.append((a, b))
        b = a
    return cuts[::-1]


def _fit_bpm(t: np.ndarray) -> float:
    slope = np.polyfit(np.arange(len(t)), t, 1)[0]
    return 60.0 / slope


# ── 4. bar length from the downbeat signal ──────────────────────────────────

def _bar_length(beat_times: np.ndarray, db_act: np.ndarray | None) -> tuple[int, int, float]:
    """Return (beats_per_bar, phase, confidence) for a section's beats."""
    if db_act is None or len(beat_times) < 8:
        return 4, 0, 0.0
    idx = np.clip(np.round(beat_times * FPS).astype(int), 0, len(db_act) - 1)
    # take the max activation in a ±1 frame window around each beat
    act = np.max(np.stack([db_act[np.clip(idx + d, 0, len(db_act) - 1)] for d in (-1, 0, 1)]), axis=0)
    act = 1 / (1 + np.exp(-act))  # logits -> probabilities
    best = (4, 0, -np.inf)
    for n in range(2, 17):
        if len(act) < 2 * n:
            break
        for p in range(n):
            on = act[p::n]
            off = np.delete(act, np.arange(p, len(act), n))
            if len(off) == 0:
                continue
            # contrast between "1"s and the rest; small bias toward common meters
            score = on.mean() - off.mean() + (0.02 if n in (3, 4) else 0.0) - 0.002 * n
            if score > best[2]:
                best = (n, p, score)
    return best


# ── main entry ──────────────────────────────────────────────────────────────

def _regularize(beats: np.ndarray) -> np.ndarray:
    """Repair the beat list before measuring tempo.

    The model sometimes misses a beat (gap ≈ 2 beats) or adds one (gap ≈ ½).
    Left alone, a line fit over "beat number" then drifts (that's how a 145
    BPM song came out as 135.7). We fill single/double misses and drop
    obvious extras, comparing each gap with the local typical gap."""
    if len(beats) < 8:
        return beats
    out = [beats[0]]
    ibi = np.diff(beats)
    for i, d in enumerate(ibi):
        local = np.median(ibi[max(0, i - 8):i + 9])
        r = d / local
        if 1.7 < r < 3.3:                       # 1 or 2 missed beats: fill in evenly
            k = int(round(r))
            out.extend(beats[i] + d * j / k for j in range(1, k))
        elif r < 0.6 and i + 1 < len(ibi):      # an extra beat squeezed in: skip it
            continue
        out.append(beats[i + 1])
    return np.array(out)


def _dominant_bpm(beats: np.ndarray) -> float:
    """The song's main tempo: the most common beat spacing (histogram peak)."""
    bpm = 60 / np.diff(beats)
    bpm = bpm[(bpm > 40) & (bpm < 320)]
    hist, edges = np.histogram(bpm, bins=np.arange(40, 321, 1.0))
    k = int(np.argmax(hist))
    near = bpm[(bpm >= edges[k] - 2) & (bpm <= edges[k + 1] + 2)]
    return float(np.median(near))


def _bars(beats: np.ndarray, downbeats: np.ndarray) -> list[dict]:
    """Bar-by-bar grid from the model's downbeats: where each bar starts and
    how many beats it has. Songs like Racecar change meter almost every bar,
    so the app keeps this list (it's also what bar navigation uses)."""
    bars = []
    if len(downbeats) < 2:
        return bars
    for a, b in zip(downbeats[:-1], downbeats[1:]):
        n = int(np.sum((beats >= a - 0.07) & (beats < b - 0.07)))
        if n >= 1:
            bars.append({"start": round(float(a), 3), "beats": n})
    return bars


def build(beats, downbeats, duration: float, downbeat_activation=None) -> dict:
    raw_beats = np.asarray(beats, dtype=float)
    beats = _regularize(raw_beats)
    downbeats = np.asarray(downbeats, dtype=float)
    out: list[dict] = []
    main_bpm = _dominant_bpm(beats) if len(beats) > 8 else None

    # 5. split at gaps first, then segment each continuous run
    runs, start = [], 0
    if len(beats) > 2:
        ibi = np.diff(beats)
        local = np.array([np.median(ibi[max(0, i - 4):i + 5]) for i in range(len(ibi))])
        for i, d in enumerate(ibi):
            if d > GAP_FACTOR * local[i]:
                runs.append((start, i + 1))
                start = i + 1
    runs.append((start, len(beats)))

    for ra, rb in runs:
        t = beats[ra:rb]
        if len(t) < MIN_SEG:
            continue
        for a, b in _segment(t):
            seg = t[a:b]
            out.append({"start": float(seg[0]), "end": float(seg[-1] + (seg[-1] - seg[-2])),
                        "kind": "steady", "bpm": _fit_bpm(seg), "_t": seg})

    out = _fix_octaves(out, main_bpm)
    out = _absorb_blips(out)
    out = _merge(out)

    out = _split_meters(out, downbeats, downbeat_activation)

    for s in out:
        seg = s.pop("_t")
        s["bpm_end"] = s.get("bpm_end", s["bpm"])
        n, phase, conf = _bar_length(seg, downbeat_activation)
        s.update({
            "bpm": round(s["bpm"], 1), "bpm_end": round(s["bpm_end"], 1),
            "beats_per_bar": n, "meter": f"{n}/4", "beats": int(len(seg)),
            "bars": round(len(seg) / n, 1), "first_downbeat": round(float(seg[phase]), 3),
            "bar_confidence": round(float(conf), 3), "uncertain": bool(s.get("uncertain", False)),
            "start": round(s["start"], 3), "end": round(s["end"], 3),
        })

    out = _merge_same(out)

    # free time before, between and after
    final, cursor = [], 0.0
    for s in out:
        if s["start"] - cursor > 1.0:
            final.append(_free(cursor, s["start"]))
        final.append(s)
        cursor = s["end"]
    if duration - cursor > 1.0:
        final.append(_free(cursor, duration))
    return {"version": 1, "duration": round(duration, 3), "main_bpm": round(main_bpm, 1) if main_bpm else None,
            "beats": [round(x, 4) for x in beats], "downbeats": [round(x, 4) for x in downbeats],
            "bars": _bars(beats, downbeats), "sections": final}


def _fix_octaves(secs, main_bpm=None):
    """3. Re-count half/double-time sections at the song's main tempo level
    (or their neighbours' when there's no clear main tempo)."""
    if len(secs) < 2:
        return secs
    w = np.array([len(s["_t"]) * 60 / s["bpm"] for s in secs])  # duration weights
    for i, s in enumerate(secs):
        neigh = [secs[j]["bpm"] for j in (i - 1, i + 1) if 0 <= j < len(secs)]
        ref = main_bpm or np.median(neigh)
        for f in (2.0, 0.5):
            if abs(s["bpm"] * f - ref) / ref < OCTAVE_TOL and w[i] < w.sum() * 0.5:
                t = s["_t"]
                if f == 2.0:  # insert midpoints
                    mids = (t[:-1] + t[1:]) / 2
                    s["_t"] = np.sort(np.concatenate([t, mids]))
                else:         # keep every other beat
                    s["_t"] = t[::2]
                s["bpm"] *= f
                s["octave_fixed"] = True
                break
    return secs


def _beat_strength(t, act):
    """Downbeat probability at each beat time (max over ±1 frame)."""
    idx = np.clip(np.round(t * FPS).astype(int), 0, len(act) - 1)
    a = np.max(np.stack([act[np.clip(idx + d, 0, len(act) - 1)] for d in (-1, 0, 1)]), axis=0)
    return 1 / (1 + np.exp(-a))


def _split_meters(secs, downbeats, act, window=28):
    """Same tempo, different bar length (e.g. 4/4 -> 7/4 at 110 BPM): split.

    Slide a 28-beat window along the section; in each window find the bar
    length whose "1"s carry the most downbeat probability. Smooth those
    labels and cut where a new bar length holds for at least 2 bars.
    (The model's own downbeat list is biased toward 4/4, so we score its raw
    downbeat signal ourselves.)"""
    if act is None:
        return secs
    out = []
    for s in secs:
        t = s["_t"]
        if len(t) < 2 * window:
            out.append(s)
            continue
        strength = _beat_strength(t, act)
        labels = np.zeros(len(t), dtype=int)
        for k in range(0, len(t) - window + 1):
            w = strength[k:k + window]
            best, bn = -np.inf, 4
            for n in range(2, 13):
                for p in range(n):
                    on = w[p::n]
                    off = np.delete(w, np.arange(p, window, n))
                    sc = on.mean() - off.mean() + (0.02 if n in (3, 4) else 0.0) - 0.002 * n
                    if sc > best:
                        best, bn = sc, n
            labels[k + window // 2] = bn
        labels[: window // 2] = labels[window // 2]
        labels[len(t) - window + window // 2 + 1:] = labels[len(t) - window + window // 2]
        # mode filter over ~one window to remove flicker
        sm = labels.copy()
        for i in range(len(t)):
            seg = labels[max(0, i - window // 2):i + window // 2]
            sm[i] = np.bincount(seg).argmax()
        cuts = [0]
        for i in range(1, len(t)):
            if sm[i] != sm[cuts[-1]]:
                j = i
                while j < len(t) and sm[j] == sm[i]:
                    j += 1
                if j - i >= 2 * sm[i]:
                    cuts.append(i)
        if len(cuts) == 1:
            out.append(s)
            continue
        cuts.append(len(t))
        for a, b in zip(cuts[:-1], cuts[1:]):
            piece = dict(s)
            piece["_t"] = t[a:b]
            piece["start"] = float(t[a])
            piece["end"] = float(t[b]) if b < len(t) else s["end"]
            piece["bpm"] = _fit_bpm(piece["_t"]) if s["kind"] == "steady" else s["bpm"]
            out.append(piece)
    return out


def _absorb_blips(secs):
    """Short sections far off both neighbours' tempo (drum fills, stops) are
    folded into the previous section and flagged so the app can ask the user."""
    out = []
    for i, s in enumerate(secs):
        neigh = [secs[j]["bpm"] for j in (i - 1, i + 1) if 0 <= j < len(secs)]
        short = len(s["_t"]) < TRUST_BEATS
        far = neigh and all(abs(s["bpm"] - n) / n > 0.10 for n in neigh)
        if out and short and far:
            p = out[-1]
            p["end"] = s["end"]
            p["uncertain"] = True
            continue
        out.append(s)
    return out


def _merge(secs):
    """2. Merge same-tempo neighbours; turn monotonic small steps into ramps."""
    merged = []
    for s in secs:
        if merged:
            p = merged[-1]
            pe = p.get("bpm_end", p["bpm"])
            same = abs(s["bpm"] - pe) / pe < 0.02
            step = abs(s["bpm"] - pe) / pe < RAMP_STEP
            direction = np.sign(s["bpm"] - pe)
            p_dir = np.sign(pe - p["bpm"]) if p["kind"] == "ramp" else direction
            if same and p["kind"] == "steady":
                p["_t"] = np.concatenate([p["_t"], s["_t"]])
                p["bpm"] = _fit_bpm(p["_t"])
                p["end"] = s["end"]
                continue
            if step and not same and direction == p_dir:
                if p["kind"] == "steady":
                    p["kind"] = "ramp"
                p["_t"] = np.concatenate([p["_t"], s["_t"]])
                p["bpm_end"] = s["bpm"]
                p["end"] = s["end"]
                continue
        merged.append(s)
    # a "ramp" of only two near-equal pieces is really steady
    for s in merged:
        if s["kind"] == "ramp" and abs(s["bpm_end"] - s["bpm"]) / s["bpm"] < 0.04:
            s["kind"] = "steady"
            s["bpm"] = s["bpm_end"] = _fit_bpm(s["_t"])
    return merged


def _merge_same(secs):
    """After bar detection: join neighbours with the same kind, tempo and meter."""
    merged = []
    for s in secs:
        if merged:
            p = merged[-1]
            if (p["kind"] == s["kind"] == "steady" and p["beats_per_bar"] == s["beats_per_bar"]
                    and abs(p["bpm"] - s["bpm"]) / p["bpm"] < 0.02):
                w = p["beats"] + s["beats"]
                p["bpm"] = p["bpm_end"] = round((p["bpm"] * p["beats"] + s["bpm"] * s["beats"]) / w, 1)
                p["end"], p["beats"] = s["end"], w
                p["bars"] = round(w / p["beats_per_bar"], 1)
                p["uncertain"] = p["uncertain"] or s["uncertain"]
                continue
        merged.append(s)
    return merged


def _free(start, end):
    return {"start": round(float(start), 3), "end": round(float(end), 3), "kind": "free",
            "bpm": None, "bpm_end": None, "beats_per_bar": None, "meter": None,
            "beats": 0, "bars": 0, "first_downbeat": None, "bar_confidence": None}
