"""
Builds the phone version of the Masked-Diffusion Beat This model and checks it.

Two files go to the phone:
  md_spect.onnx  audio (22050 Hz mono, reflect-padded by 512) -> log-mel spectrogram (frames, 128)
  md_net.onnx    spectrogram chunk + beat/downbeat tokens -> beat/downbeat logits
The iterative unmasking loop (md_beat_this/diffusion.py) runs in the app's own
code; md_pipeline() below is the line-by-line reference the Kotlin port follows.

Run with the Masked-Diffusion environment:
  .venv-md/Scripts/python -I export_md_phone.py out/md_phone
"""
import json
import math
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
import torch.nn as nn
from md_beat_this.inference import File2Beats, load_models
from md_beat_this.preprocessing import LogMelSpect, load_audio, prepare_signal, rms_trim_bounds

SR, N_FFT, HOP, FPS = 22050, 1024, 441, 50
CHUNK, OVERLAP, BORDER, DISCARD = 1500, 500, 6, 100
MASK, PAD, NO_EVENT, EVENT = 0, 1, 2, 3
TOL = 3


class Spect(nn.Module):
    """log(1 + 1000 * mel(|STFT|)) with the MD settings, as a convolution."""

    def __init__(self):
        super().__init__()
        ref = LogMelSpect()
        n = torch.arange(N_FFT, dtype=torch.float64)
        k = torch.arange(N_FFT // 2 + 1, dtype=torch.float64)[:, None]
        ang = 2 * torch.pi * k * n / N_FFT
        w = ref.spect_class.spectrogram.window.double()
        basis = torch.cat([w * torch.cos(ang), -w * torch.sin(ang)])  # no normalisation for MD
        self.register_buffer("basis", basis.float()[:, None, :])
        self.register_buffer("mel", ref.spect_class.mel_scale.fb.float())

    def forward(self, audio):  # (1, samples) already reflect-padded by N_FFT//2
        x = torch.nn.functional.conv1d(audio[:, None, :], self.basis, stride=HOP)
        mag = torch.sqrt(x[:, :513] ** 2 + x[:, 513:] ** 2 + 1e-12)
        return torch.log(torch.matmul(mag.transpose(1, 2), self.mel) * 1000.0 + 1.0)  # (1, frames, 128)


class Net(nn.Module):
    def __init__(self, model):
        super().__init__()
        self.m = model

    def forward(self, spect, beat_tokens, downbeat_tokens):
        out = self.m(spect, beat_tokens, downbeat_tokens)
        return out["beat"], out["downbeat"]


# ── reference of the phone pipeline (numpy + a `net(spect, bt, dt)` callable) ──

def maxpool(x, k):
    n = len(x); h = k // 2
    pad = np.pad(x, h, constant_values=-np.inf)
    return np.max(np.stack([pad[i:i + n] for i in range(k)]), axis=0)


def near_events(events, candidates):
    near = maxpool(events.astype(float), 2 * TOL + 1) > 0
    return near & ~events & candidates


def top_k(values, candidates, k):
    sel = np.zeros_like(candidates)
    k = min(k, int(candidates.sum()))
    if k > 0:
        v = np.where(candidates, values, -np.inf)
        sel[np.argsort(-v, kind="stable")[:k]] = True
    return sel


def unmask(net, spect, steps, prefix=None):
    L = spect.shape[0]
    tok = {c: np.full(L, MASK, np.int64) for c in ("beat", "downbeat")}
    masked = {c: np.ones(L, bool) for c in tok}
    if prefix is not None:
        for c, fixed in zip(tok, prefix):
            tok[c][:len(fixed)] = np.where(fixed, EVENT, NO_EVENT)
            masked[c][:len(fixed)] = False
    nz = (spect != 0).any(-1)
    if nz.any():
        nv = int(np.nonzero(nz)[0].max()) + 1
        for c in tok:
            tok[c][nv:] = PAD
            masked[c][nv:] = False
    pred = None
    for step in range(steps):
        b, d = net(spect, tok["beat"], tok["downbeat"])
        logits = {"beat": b, "downbeat": d}
        lm = b == maxpool(b, 2 * TOL + 1)
        beat_ev = lm & (b > 0)
        pred = {"beat": np.where(beat_ev, EVENT, NO_EVENT), "downbeat": np.where(beat_ev & (d > 0), EVENT, NO_EVENT)}
        sel = {}
        for c in tok:
            lg, m = logits[c], masked[c]
            pos, neg = m & (lg > 0), m & (lg <= 0)
            npos, nneg = int(pos.sum()), int(neg.sum())
            ratio = nneg / npos if npos and nneg else 1.0
            budget = math.ceil((npos + nneg) / (steps - step))
            kpos = min(max(1, round(budget / (1 + ratio))), npos)
            s = top_k(np.abs(lg), pos, kpos) | top_k(np.abs(lg), neg, budget - kpos)
            # spare_suppressed_frames
            new_ev = s & (pred[c] == EVENT)
            nsup = int(near_events(new_ev, m & ~s).sum())
            removable = s & (pred[c] != EVENT) & ~near_events(new_ev, m)
            nrem = min(nsup, int(removable.sum()))
            if nrem:
                conf = np.where(removable, np.abs(lg), np.inf)
                s = s.copy(); s[np.argsort(conf, kind="stable")[:nrem]] = False
            sel[c] = s
        for c in tok:
            tok[c][sel[c]] = pred[c][sel[c]]
            masked[c] &= ~sel[c]
        new_db = sel["downbeat"] & (tok["downbeat"] == EVENT)
        forced = new_db & masked["beat"]
        if forced.any():
            tok["beat"][forced] = EVENT
            masked["beat"] &= ~forced
            sel["beat"] |= forced
            other = sel["beat"] & ~forced & (tok["beat"] == EVENT)
            conflict = near_events(forced, other)
            tok["beat"][conflict] = NO_EVENT
            sel["beat"] &= ~conflict
        for c in tok:
            new_ev = sel[c] & (tok[c] == EVENT)
            sup = near_events(new_ev, masked[c])
            tok[c][sup] = NO_EVENT
            masked[c] &= ~sup
        if not any(masked[c].any() for c in tok) or not any(sel[c].any() for c in tok):
            break
    for c in tok:
        tok[c][masked[c]] = pred[c][masked[c]]
    down = tok["downbeat"] == EVENT
    return (tok["beat"] == EVENT) | down, down


def md_pipeline(sig, spect_fn, net, steps=8):
    """sig: 22050 Hz mono. Returns beat and downbeat times (s)."""
    start, end = rms_trim_bounds(sig)
    s = sig[start:end]
    padded = np.pad(s, (N_FFT // 2, N_FFT // 2), mode="reflect").astype(np.float32)
    spect = spect_fn(padded)                       # (frames, 128)
    spect = np.concatenate([np.zeros((BORDER, 128), np.float32), spect])
    n = len(spect) - BORDER
    if len(spect) <= CHUNK:
        chunks = [np.concatenate([spect, np.zeros((max(0, CHUNK - len(spect)), 128), np.float32)])]
        starts = [0]
    else:
        starts = list(range(0, len(spect) - CHUNK, CHUNK - OVERLAP)) + [len(spect) - CHUNK]
        chunks = [spect[st:st + CHUNK] for st in starts]
    preds = []
    for i, ch in enumerate(chunks):
        prefix = None
        if i > 0:
            o = starts[i] - starts[i - 1]
            prefix = tuple(p[o:CHUNK - DISCARD] for p in preds[-1])
        preds.append(unmask(net, ch, steps, prefix))
    hop = CHUNK - OVERLAP
    context = (CHUNK - 1) // 2 - (hop - 1) // 2 - BORDER
    out = [np.zeros(n, bool), np.zeros(n, bool)]
    for j in range(2):
        for i, (st, p) in enumerate(zip(starts, preds)):
            b = 0 if i == 0 else i * hop + context
            e = n if i == len(starts) - 1 else (i + 1) * hop + context
            shift = BORDER - st
            out[j][b:e] = p[j][b + shift:e + shift]
    frames = np.nonzero(out[0])[0]
    beats = frames / FPS + start / SR
    downs = beats[out[1][frames]]
    return beats, downs


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "out/md_phone")
    out.mkdir(parents=True, exist_ok=True)
    model = load_models("run1", "cpu", num_models=1)[0]
    spect_m, net_m = Spect().eval(), Net(model).eval()

    # 1) spectrogram identical to MD's?
    sig, _ = load_audio("audio_syn/syn_mixed.wav")
    ref = LogMelSpect()(torch.tensor(sig))
    with torch.no_grad():
        mine = spect_m(torch.tensor(np.pad(sig, (512, 512), mode="reflect"))[None])[0]
    print("spectrogram max abs diff:", float((mine - ref).abs().max()))

    # 2) export both parts
    sp, nt = out / "md_spect.onnx", out / "md_net.onnx"
    torch.onnx.export(spect_m, (torch.randn(1, 22050 * 10),), sp, input_names=["audio"], output_names=["spect"],
                      dynamic_axes={"audio": {1: "samples"}, "spect": {1: "frames"}}, opset_version=17, dynamo=False)
    dummy = (torch.randn(1, CHUNK, 128), torch.zeros(1, CHUNK, dtype=torch.long), torch.zeros(1, CHUNK, dtype=torch.long))
    torch.onnx.export(net_m, dummy, nt, input_names=["spect", "beat_tokens", "downbeat_tokens"],
                      output_names=["beat", "downbeat"], opset_version=17, dynamo=False,
                      dynamic_axes={"spect": {1: "frames"}, "beat_tokens": {1: "frames"}, "downbeat_tokens": {1: "frames"},
                                    "beat": {1: "frames"}, "downbeat": {1: "frames"}})
    from onnxruntime.quantization import QuantType, quantize_dynamic
    nt8 = out / "md_net_int8.onnx"
    quantize_dynamic(str(nt), str(nt8), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"])
    for p in (sp, nt, nt8):
        print(f"{p.name}: {p.stat().st_size / 1e6:.1f} MB")

    # 3) phone pipeline vs original, and fewer steps
    sys.path.insert(0, str(Path(__file__).parent))
    from analyze import f_measure
    s_sess = ort.InferenceSession(str(sp), providers=["CPUExecutionProvider"])
    spect_fn = lambda a: s_sess.run(None, {"audio": a[None]})[0][0]
    report = {}
    orig = File2Beats(checkpoint_path="run1", device="cpu", steps=8, num_models=1)
    for wav in [Path("audio_syn/syn_meter_4_7_4.wav")] + sorted(Path("bench").glob("*.wav"))[:4]:
        sig, _ = load_audio(str(wav))
        sig = prepare_signal(sig, SR)
        ob, _ = orig(str(wav))
        row = {}
        for name, path in (("fp32", nt), ("int8", nt8)):
            sess = ort.InferenceSession(str(path), providers=["CPUExecutionProvider"])
            def net(spec, bt, dt, sess=sess):
                b, d = sess.run(None, {"spect": spec[None].astype(np.float32), "beat_tokens": bt[None], "downbeat_tokens": dt[None]})
                return b[0], d[0]
            for steps in ((8, 4, 2) if name == "int8" else (8,)):
                t0 = time.time()
                pb, _ = md_pipeline(sig, spect_fn, net, steps)
                row[f"{name}_s{steps}"] = {"agree_F": round(f_measure(pb, ob, 0.02), 3), "x_rt": round(len(sig) / SR / (time.time() - t0), 1)}
        report[wav.stem] = row
        print(wav.stem, row, flush=True)
    (out / "check.json").write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
