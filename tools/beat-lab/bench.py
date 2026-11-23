"""
Scores the whole pipeline on every benchmark song and prints a table.

The model's raw output for each song is cached in bench/cache/, because the
model is slow and stays the same while we tune tempo_map.py — so re-scoring
after a tempo_map change takes seconds.

Metrics (per song, then averaged per group: chon / complex / basic)
- beat F:      beats within ±70 ms of the tab's beat grid (0–1)
- downbeat F:  bar starts within ±70 ms of the tab's bar lines
- tempo ok:    % of the song where our section tempo is within 4 % of the bar's real tempo
- x2 / ÷2:     % at exactly double/half (a one-tap fix in the app)
- bars ok:     % of tab bars (whole-beat ones) where we start a bar there with the same length

Usage: python -I bench.py bench/        (add --model small0 to test the small model)
"""
import argparse
import json
import sys
import time
from pathlib import Path

import numpy as np
import soundfile as sf
import torch

sys.path.insert(0, str(Path(__file__).parent))
import tempo_map  # noqa: E402
from analyze import f_measure  # noqa: E402


def model_output(wav: Path, cache: Path, model: str):
    c = cache / f"{wav.stem}.{model}.npz"
    if c.exists():
        z = np.load(c)
        # Other models (run_other_model.py) only store beat/downbeat times, no activations.
        act = lambda k: z[k] if k in z.files else None
        return act("beat"), act("downbeat"), z["beats"], z["downbeats"], float(z["secs"])
    if model != "final0":
        return None
    from beat_this.inference import Audio2Frames, Postprocessor, load_audio
    if not hasattr(model_output, "a2f"):
        model_output.a2f = Audio2Frames(checkpoint_path=model, device="cpu")
        model_output.post = Postprocessor(type="minimal", fps=50)
    signal, sr = load_audio(str(wav))
    t0 = time.time()
    b, d = model_output.a2f(signal, sr)
    beats, downbeats = model_output.post(b, d)
    secs = time.time() - t0
    np.savez_compressed(c, beat=b.float().numpy(), downbeat=d.float().numpy(),
                        beats=beats, downbeats=downbeats, secs=secs)
    return b.float().numpy(), d.float().numpy(), beats, downbeats, secs


def smooth_truth(truth, half_window=4):
    """Clean up hand-synced bar times.

    Songsterr's sync points are tapped by people, so each bar line can be off by
    tens of ms. Within a stretch of steady tempo, bar starts should sit on a
    straight line against "quarter notes so far". We fit that line locally
    (±4 bars) and move each bar start onto it — keeping real tempo drift, but
    removing tap jitter. Beats are then re-spaced evenly inside each bar."""
    bars = truth["bars"]
    if len(bars) < 3:
        return truth
    q = np.concatenate([[0.0], np.cumsum([b["quarters"] for b in bars])])
    t = np.array([b["start"] for b in bars] + [bars[-1]["end"]])
    new_t = t.copy()
    for k in range(len(t)):
        a, z = max(0, k - half_window), min(len(t), k + half_window + 1)
        # don't smooth across a tab tempo change
        tab = [bars[min(i, len(bars) - 1)]["tab_bpm"] for i in range(a, z)]
        here = bars[min(k, len(bars) - 1)]["tab_bpm"]
        idx = [i for i, tb in zip(range(a, z), tab) if tb == here]
        if len(idx) >= 3:
            m, c = np.polyfit(q[idx], t[idx], 1)
            new_t[k] = m * q[k] + c
    out = dict(truth)
    nb, beats = [], []
    for i, b in enumerate(bars):
        st, en = float(new_t[i]), float(new_t[i + 1])
        if en <= st:
            continue
        nb.append({**b, "start": st, "end": en, "bpm": b["quarters"] * 60 / (en - st)})
        j = 0.0
        while j < b["quarters"] - 1e-6:
            beats.append(st + j * (en - st) / b["quarters"])
            j += 1.0
    out["bars"], out["beats"], out["downbeats"] = nb, beats, [b["start"] for b in nb]
    return out


def score(truth, tm, beats, downbeats):
    bars = truth["bars"]
    out = {
        "beat_F": f_measure(beats, truth["beats"]),
        "downbeat_F": f_measure(downbeats, truth["downbeats"]),
    }
    secs = [s for s in tm["sections"] if s["kind"] != "free"]
    ok = octv = n = 0
    for b in bars:
        for t in np.arange(b["start"], b["end"], 0.5):
            n += 1
            s = next((s for s in secs if s["start"] <= t < s["end"]), None)
            if s is None:
                continue
            r = s["bpm"] / b["bpm"]
            if abs(r - 1) < 0.04:
                ok += 1
            elif abs(r - 2) < 0.08 or abs(r - 0.5) < 0.02:
                octv += 1
    out["tempo_ok"] = ok / max(n, 1)
    out["octave"] = octv / max(n, 1)
    ob = tm.get("bars", [])
    whole = [b for b in bars if abs(b["quarters"] - round(b["quarters"])) < 1e-6]
    same = 0
    if ob and whole:
        starts = np.array([b["start"] for b in ob])
        for b in whole:
            i = int(np.argmin(np.abs(starts - b["start"])))
            if abs(starts[i] - b["start"]) < 0.07 and ob[i]["beats"] == round(b["quarters"]):
                same += 1
    out["bars_ok"] = same / max(len(whole), 1)
    return out


def main():
    ap = argparse.ArgumentParser()
    ap.add_argument("folder", nargs="?", default="bench")
    ap.add_argument("--model", default="final0")
    a = ap.parse_args()
    folder = Path(a.folder)
    cache = folder / "cache"
    cache.mkdir(exist_ok=True)

    rows = []
    for tp in sorted(folder.glob("*_truth.json")):
        name = tp.name[: -len("_truth.json")]
        wav = folder / f"{name}.wav"
        if not wav.exists():
            continue
        truth = smooth_truth(json.loads(tp.read_text()))
        out = model_output(wav, cache, a.model)
        if out is None:
            continue  # this model hasn't processed this song yet
        _, db_act, beats, downbeats, secs = out
        dur = sf.info(str(wav)).duration
        tm = tempo_map.build(beats, downbeats, dur, downbeat_activation=db_act)
        r = {"song": name, "group": truth["group"], **score(truth, tm, beats, downbeats),
             "x_rt": dur / max(secs, 1e-6),
             "meters": len({b["sig"] for b in truth["bars"]})}
        rows.append(r)

    for r in rows:
        r["fixable"] = r["tempo_ok"] + r["octave"]  # right, or right after one ×2/÷2 tap
    cols = ["beat_F", "downbeat_F", "tempo_ok", "octave", "fixable", "bars_ok"]
    print(f"{'song':42s} {'grp':7s} {'meters':>6s} " + " ".join(f"{c:>10s}" for c in cols))
    for r in rows:
        print(f"{r['song'][:42]:42s} {r['group']:7s} {r['meters']:6d} " + " ".join(f"{r[c]:10.2f}" for c in cols))
    print("-" * 100)
    for g in ("chon", "complex", "basic", None):
        sel = [r for r in rows if g is None or r["group"] == g]
        if sel:
            print(f"{(g or 'ALL'):42s} {len(sel):7d} {'':6s} " + " ".join(f"{np.mean([r[c] for r in sel]):10.2f}" for c in cols))
    (folder / f"results.{a.model}.json").write_text(json.dumps(rows, indent=1))


if __name__ == "__main__":
    main()
