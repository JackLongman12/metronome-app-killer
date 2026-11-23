"""
Makes drum-loop test tracks where we KNOW the right answer.

Real songs don't come with a correct tempo map, so these synthetic tracks are
our ground truth: each WAV has a matching *_truth.json listing every beat,
downbeat and the true sections. If the analyzer gets these wrong, the bug is
ours; if it gets these right but a real song wrong, the song is just hard.

Usage:  python make_synthetic.py audio/
"""
import json
import sys
from pathlib import Path

import numpy as np
import soundfile as sf

SR = 44100
rng = np.random.default_rng(7)


def env(n, decay_s):
    return np.exp(-np.arange(n) / (decay_s * SR))


def kick():
    n = int(0.25 * SR)
    t = np.arange(n) / SR
    f = 50 + 100 * np.exp(-t / 0.03)
    return np.sin(2 * np.pi * np.cumsum(f) / SR) * env(n, 0.09)


def snare():
    n = int(0.2 * SR)
    t = np.arange(n) / SR
    return 0.6 * rng.standard_normal(n) * env(n, 0.05) + 0.4 * np.sin(2 * np.pi * 190 * t) * env(n, 0.04)


def hat():
    n = int(0.06 * SR)
    x = rng.standard_normal(n)
    return 0.3 * np.diff(x, prepend=0) * env(n, 0.015)


def bass(freq, dur):
    n = int(dur * SR)
    t = np.arange(n) / SR
    return 0.25 * np.sign(np.sin(2 * np.pi * freq * t)) * env(n, dur * 0.6)


def place(buf, sample, at):
    i = int(at * SR)
    j = min(len(buf), i + len(sample))
    if i < len(buf):
        buf[i:j] += sample[: j - i]


def render(sections, name, out_dir):
    """sections: list of (bars, beats_per_bar, bpm_start, bpm_end, label)."""
    beats, downbeats, truth_sections = [], [], []
    t = 0.5
    for bars, bpb, b0, b1, label in sections:
        start = t
        total = bars * bpb
        for k in range(total):
            bpm = b0 + (b1 - b0) * k / max(1, total - 1)
            beats.append(t)
            if k % bpb == 0:
                downbeats.append(t)
            t += 60.0 / bpm
        truth_sections.append({"start": round(start, 3), "end": round(t, 3), "bpm": [b0, b1],
                               "beats_per_bar": bpb, "label": label})
    dur = t + 1.0
    buf = np.zeros(int(dur * SR))
    # Make the bar unambiguous, like real music: kick + a new bass note on "1",
    # snare in the middle of the bar, hats on every beat and off-beat.
    notes = [55, 65.4, 73.4, 49]
    bar_of, pos_of = [], []
    t_db = list(downbeats)
    bi = -1
    for b in beats:
        if bi + 1 < len(t_db) and abs(b - t_db[bi + 1]) < 1e-6:
            bi += 1
        bar_of.append(bi)
        pos_of.append(0 if abs(b - t_db[bi]) < 1e-6 else pos_of[-1] + 1)
    bar_len = {}
    for bar, pos in zip(bar_of, pos_of):
        bar_len[bar] = max(bar_len.get(bar, 0), pos + 1)
    for i, b in enumerate(beats):
        nxt = beats[i + 1] if i + 1 < len(beats) else b + 0.5
        pos, n = pos_of[i], bar_len[bar_of[i]]
        if pos == 0:
            place(buf, kick(), b)
            bar_end = beats[i + n] if i + n < len(beats) else b + n * (nxt - b)
            place(buf, bass(notes[bar_of[i] % 4], (bar_end - b) * 0.95), b)
        elif pos == n // 2:
            place(buf, snare(), b)
        place(buf, hat() * (1.0 if pos == 0 else 0.7), b)
        place(buf, 0.5 * hat(), (b + nxt) / 2)
    buf += 0.01 * rng.standard_normal(len(buf))  # a little "room" noise
    buf /= np.max(np.abs(buf)) * 1.05
    sf.write(out_dir / f"{name}.wav", buf.astype(np.float32), SR)
    (out_dir / f"{name}_truth.json").write_text(json.dumps(
        {"beats": beats, "downbeats": downbeats, "sections": truth_sections}, indent=1))
    print(f"{name}: {dur:.0f}s, {len(beats)} beats")


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "audio")
    out.mkdir(parents=True, exist_ok=True)
    render([(40, 4, 120, 120, "4/4 @120")], "syn_steady120", out)
    render([(24, 4, 100, 100, "4/4 @100"), (24, 4, 140, 140, "4/4 @140")], "syn_jump100_140", out)
    render([(16, 4, 110, 110, "4/4 @110"), (16, 7, 110, 110, "7/4 @110"), (16, 4, 110, 110, "4/4 @110")],
           "syn_meter_4_7_4", out)
    render([(32, 4, 90, 130, "4/4 90→130")], "syn_ramp90_130", out)
    render([(12, 4, 96, 96, "4/4 @96"), (12, 3, 150, 150, "3/4 @150"), (12, 5, 120, 120, "5/4 @120")],
           "syn_mixed", out)


if __name__ == "__main__":
    main()
