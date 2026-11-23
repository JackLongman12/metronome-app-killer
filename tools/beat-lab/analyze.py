"""
Runs the beat_this model on every WAV in a folder, builds tempo maps, scores
them against *_truth.json when available, and draws a tempo plot per track.

Usage:  python -I analyze.py audio/ out/
(-I keeps Python from importing stray files from the audio folder.)
"""
import json
import sys
import time
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np
import soundfile as sf

sys.path.insert(0, str(Path(__file__).parent))
import tempo_map  # noqa: E402

TOL = 0.07  # standard beat-tracking tolerance: a beat within ±70 ms counts as correct


def f_measure(est, ref, tol=TOL):
    est, ref = np.asarray(est), np.asarray(ref)
    if len(est) == 0 or len(ref) == 0:
        return 0.0
    used = np.zeros(len(est), bool)
    hits = 0
    for r in ref:
        d = np.abs(est - r)
        d[used] = np.inf
        j = int(np.argmin(d))
        if d[j] <= tol:
            used[j] = True
            hits += 1
    p, rc = hits / len(est), hits / len(ref)
    return 0.0 if p + rc == 0 else 2 * p * rc / (p + rc)


def plot(name, tm, truth, path):
    beats = np.array(tm["beats"])
    fig, ax = plt.subplots(figsize=(12, 4), dpi=110)
    if len(beats) > 2:
        ax.scatter(beats[1:], 60 / np.diff(beats), s=3, c="#9AA0A6", label="each beat")
    for s in tm["sections"]:
        if s["kind"] == "free":
            ax.axvspan(s["start"], s["end"], color="#E2E2DD", alpha=0.6, lw=0)
            continue
        ax.plot([s["start"], s["end"]], [s["bpm"], s["bpm_end"]], c="#4C8DFF", lw=2.5)
        ax.text((s["start"] + s["end"]) / 2, s["bpm"] + 4, f'{s["bpm"]:.0f} · {s["meter"]}',
                ha="center", fontsize=8, color="#22324F")
    if truth:
        for s in truth["sections"]:
            ax.plot([s["start"], s["end"]], s["bpm"], c="#2EC4B6", lw=1, ls="--")
            ax.text(s["start"], s["bpm"][0] - 12, s["label"], fontsize=7, color="#0F6E56")
    ax.set_xlabel("time (s)")
    ax.set_ylabel("BPM")
    ax.set_title(name)
    ax.set_ylim(0, 260)
    ax.grid(alpha=0.2)
    fig.tight_layout()
    fig.savefig(path)
    plt.close(fig)


def main():
    src = Path(sys.argv[1] if len(sys.argv) > 1 else "audio")
    out = Path(sys.argv[2] if len(sys.argv) > 2 else "out")
    out.mkdir(exist_ok=True)

    from beat_this.inference import Audio2Frames, Postprocessor, load_audio
    t0 = time.time()
    a2f = Audio2Frames(checkpoint_path="final0", device="cpu")
    post = Postprocessor(type="minimal", fps=50)
    print(f"model loaded in {time.time() - t0:.1f}s")

    rows = []
    for wav in sorted(src.glob("*.wav")):
        name = wav.stem
        dur = sf.info(str(wav)).duration
        t0 = time.time()
        signal, sr = load_audio(str(wav))
        beat_logits, db_logits = a2f(signal, sr)
        beats, downbeats = post(beat_logits, db_logits)
        secs = time.time() - t0
        tm = tempo_map.build(beats, downbeats, dur, downbeat_activation=db_logits.float().cpu().numpy())
        (out / f"{name}.json").write_text(json.dumps(tm, indent=1))
        truth_p = src / f"{name}_truth.json"
        truth = json.loads(truth_p.read_text()) if truth_p.exists() else None
        plot(name, tm, truth, out / f"{name}.png")
        row = {"track": name, "audio_s": round(dur), "analyze_s": round(secs, 1),
               "x_realtime": round(dur / secs, 1), "sections": len(tm["sections"])}
        if truth:
            row["beat_F"] = round(f_measure(beats, truth["beats"]), 3)
            row["downbeat_F"] = round(f_measure(downbeats, truth["downbeats"]), 3)
        rows.append(row)
        print(row)
        for s in tm["sections"]:
            print("   ", {k: s.get(k) for k in ("start", "end", "kind", "bpm", "bpm_end", "meter", "bars", "bar_confidence", "octave_fixed")})
    (out / "summary.json").write_text(json.dumps(rows, indent=1))


if __name__ == "__main__":
    main()
