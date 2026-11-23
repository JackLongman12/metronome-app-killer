"""
Builds the model file the Android app downloads, and checks it.

What goes in the file: AUDIO in, beat/downbeat scores out. The log-mel
spectrogram (the "picture of the sound" the network looks at) is built into the
model as plain matrix maths, so the app doesn't need to re-implement it.

  audio chunk (22050 Hz mono, already padded) ─► STFT as a 1-D convolution with
  fixed cos/sin weights ─► magnitude ─► 128 mel bands ─► log ─► beat_this ─►
  beat & downbeat scores (50 per second)

The app's side of the contract (ported 1:1 from phone_pipeline() below):
  1. decode to mono, resample to 22050 Hz
  2. pad 512 samples at both ends by reflection (= torchaudio's center=True)
  3. frames = 1 + floor(len_before_padding / 441); cut into 1500-frame chunks that
     overlap by 6 frames (beat_this's split_piece), each chunk = audio samples
     [start*441, start*441 + 1499*441 + 1024)
  4. run the model per chunk, drop 6 border frames, stitch (earlier chunk wins)
  5. peak-pick: local max over ±3 frames and score > 0; merge adjacent peaks;
     snap each downbeat to the nearest beat
Usage: python -I export_phone_model.py out/phone
"""
import json
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import soxr
import torch
import torch.nn as nn
from beat_this.inference import load_audio, load_model, split_predict_aggregate, Postprocessor
from beat_this.preprocessing import LogMelSpect

sys.path.insert(0, str(Path(__file__).parent))
from analyze import f_measure  # noqa: E402

SR, N_FFT, HOP, CHUNK, BORDER, FPS = 22050, 1024, 441, 1500, 6, 50


class PhoneModel(nn.Module):
    def __init__(self, checkpoint="final0"):
        super().__init__()
        ref = LogMelSpect()
        spec = ref.spect_class.spectrogram
        window = spec.window if hasattr(spec, "window") else torch.hann_window(N_FFT)
        n = torch.arange(N_FFT, dtype=torch.float64)
        k = torch.arange(N_FFT // 2 + 1, dtype=torch.float64)[:, None]
        ang = 2 * torch.pi * k * n / N_FFT
        w = window.double()
        basis = torch.cat([w * torch.cos(ang), -w * torch.sin(ang)])  # (1026, 1024)
        # normalized="frame_length" in torchaudio = torch.stft(normalized=True),
        # which divides by sqrt(n_fft) (not by the window's energy)
        basis = basis / (N_FFT ** 0.5)
        self.register_buffer("basis", basis.float()[:, None, :])           # conv weight (1026, 1, 1024)
        self.register_buffer("mel", ref.spect_class.mel_scale.fb.float())   # (513, 128)
        self.net = load_model(checkpoint, device="cpu").eval()

    def forward(self, audio):  # audio: (1, samples)
        x = torch.nn.functional.conv1d(audio[:, None, :], self.basis, stride=HOP)  # (1, 1026, frames)
        re, im = x[:, :513], x[:, 513:]
        mag = torch.sqrt(re * re + im * im + 1e-12)                              # power=1
        spect = torch.log1p(1000.0 * torch.matmul(mag.transpose(1, 2), self.mel))  # (1, frames, 128)
        out = self.net(spect)
        return out["beat"], out["downbeat"]


def to_22k_mono(path):
    sig, sr = load_audio(str(path))
    if sig.ndim == 2:
        sig = sig.mean(1)
    if sr != SR:
        sig = soxr.resample(sig, sr, SR)
    return sig.astype(np.float32)


def phone_pipeline(sig, run):
    """Exactly what the app does (see module doc). `run(chunk_audio) -> (beat, downbeat)` logits."""
    n_frames = 1 + len(sig) // HOP
    padded = np.pad(sig, (N_FFT // 2, N_FFT // 2), mode="reflect")
    starts = list(range(-BORDER, n_frames - BORDER, CHUNK - 2 * BORDER))
    if n_frames > CHUNK - 2 * BORDER:
        starts[-1] = n_frames - (CHUNK - BORDER)
    beat = np.full(n_frames, -1000.0, np.float32)
    down = np.full(n_frames, -1000.0, np.float32)
    for st in reversed(starts):  # keep_first: earlier chunks overwrite later ones
        lo = max(st, 0)
        hi = min(st + CHUNK, n_frames)
        seg = padded[lo * HOP: (hi - 1) * HOP + N_FFT]
        b, d = run(seg)
        # chunks before frame 0 / after the end were zero-padded spectrogram frames in
        # beat_this; we pad the SCORES instead with the frames we have (equivalent after border cut)
        full_b = np.full(CHUNK, -1000.0, np.float32)
        full_d = np.full(CHUNK, -1000.0, np.float32)
        full_b[lo - st: lo - st + len(b)] = b
        full_d[lo - st: lo - st + len(d)] = d
        a0, a1 = st + BORDER, st + CHUNK - BORDER
        k0, k1 = max(a0, 0), min(a1, n_frames)
        beat[k0:k1] = full_b[k0 - st:k1 - st]
        down[k0:k1] = full_d[k0 - st:k1 - st]
    return beat, down


def peaks(logits):
    """Local max within ±3 frames and logit > 0, adjacent peaks merged."""
    n = len(logits)
    pad = np.pad(logits, 3, constant_values=-1000)
    mx = np.max(np.stack([pad[i:i + n] for i in range(7)]), axis=0)
    idx = np.nonzero((logits == mx) & (logits > 0))[0]
    out, run_ = [], []
    for i in idx:
        if run_ and i - run_[-1] > 1:
            out.append(np.mean(run_)); run_ = []
        run_.append(i)
    if run_:
        out.append(np.mean(run_))
    return np.array(out) / FPS


def beats_from(beat, down):
    b, d = peaks(beat), peaks(down)
    if len(b):
        d = np.unique([b[np.argmin(np.abs(b - x))] for x in d])
    return b, d


def main():
    out = Path(sys.argv[1] if len(sys.argv) > 1 else "out/phone")
    out.mkdir(parents=True, exist_ok=True)
    m = PhoneModel().eval()

    # 1) spectrogram matches beat_this exactly?
    sig = to_22k_mono("audio_syn/syn_mixed.wav")
    ref_spec = LogMelSpect()(torch.tensor(sig))
    padded = np.pad(sig, (512, 512), mode="reflect")
    with torch.no_grad():
        x = torch.nn.functional.conv1d(torch.tensor(padded)[None, None], m.basis, stride=HOP)
        mag = torch.sqrt(x[:, :513] ** 2 + x[:, 513:] ** 2 + 1e-12)
        mine = torch.log1p(1000 * torch.matmul(mag.transpose(1, 2), m.mel))[0]
    print("spectrogram max abs diff vs beat_this:", float((mine - ref_spec).abs().max()))

    # 2) export
    fp32 = out / "beat_this_audio.onnx"
    dummy = torch.randn(1, (CHUNK - 1) * HOP + N_FFT)
    torch.onnx.export(m, (dummy,), fp32, input_names=["audio"], output_names=["beat", "downbeat"],
                      dynamic_axes={"audio": {1: "samples"}, "beat": {1: "frames"}, "downbeat": {1: "frames"}},
                      opset_version=17, dynamo=False)
    from onnxruntime.quantization import QuantType, quantize_dynamic
    int8 = out / "beat_this_audio_int8.onnx"
    # Only the transformer's MatMuls get 8-bit weights; the spectrogram stays exact.
    import onnx
    g = onnx.load(str(fp32)).graph
    keep = [n.name for n in g.node if n.op_type in ("Conv", "MatMul") and any("mel" in i or "basis" in i for i in n.input)]
    quantize_dynamic(str(fp32), str(int8), weight_type=QuantType.QInt8, op_types_to_quantize=["MatMul"], nodes_to_exclude=keep)
    for p in (fp32, int8):
        print(f"{p.name}: {p.stat().st_size / 1e6:.1f} MB")

    # 3) accuracy: original pipeline vs phone pipeline (fp32, int8)
    a2f_model = load_model("final0", device="cpu").eval()
    post = Postprocessor(type="minimal", fps=FPS)
    sessions = {k: ort.InferenceSession(str(p), providers=["CPUExecutionProvider"]) for k, p in (("fp32", fp32), ("int8", int8))}
    report = {}
    files = sorted(Path("audio_syn").glob("*.wav")) + sorted(Path("bench").glob("*.wav"))[:6]
    for wav in files:
        sig = to_22k_mono(wav)
        with torch.no_grad():
            spect = LogMelSpect()(torch.tensor(sig))
            ref = split_predict_aggregate(spect, CHUNK, BORDER, "keep_first", a2f_model)
        rb, _ = post(ref["beat"], ref["downbeat"])
        row = {}
        for k, s in sessions.items():
            t0 = time.time()
            b, d = phone_pipeline(sig, lambda a: [v[0] for v in s.run(None, {"audio": a[None].astype(np.float32)})])
            secs = time.time() - t0
            pb, _ = beats_from(b, d)
            row[k] = {"agree_with_original_F": round(f_measure(pb, rb, 0.02), 3), "x_realtime": round(len(sig) / SR / secs, 1)}
        report[wav.stem] = row
        print(wav.stem, row, flush=True)
    (out / "check.json").write_text(json.dumps(report, indent=1))


if __name__ == "__main__":
    main()
