"""
Exports the beat_this model to ONNX so the Android app can run it with
ONNX Runtime Mobile, then checks the ONNX copy gives the same output as the
original PyTorch model, and how fast it is.

LEARN: ONNX is a portable file format for neural networks. PyTorch is a big
Python library (no good on a phone), but ONNX Runtime is a small C++ engine
with an Android package, so we convert once here and ship the .onnx file.

Usage: python -I export_onnx.py out/beat_this.onnx
"""
import sys
import time

import numpy as np
import onnxruntime as ort
import torch
from beat_this.inference import load_model, LogMelSpect, load_audio

out = sys.argv[1] if len(sys.argv) > 1 else "out/beat_this.onnx"
model = load_model(sys.argv[2] if len(sys.argv) > 2 else "final0", device="cpu").eval()
# The model eats a log-mel spectrogram: 128 frequency bands, 50 frames/second.
# The app must compute the same spectrogram (we'll port LogMelSpect to Kotlin/C++).
T = 1500  # 30 s chunk, the size beat_this itself uses for long songs
dummy = torch.randn(1, T, 128)
torch.onnx.export(model, (dummy,), out, input_names=["spect"], output_names=["beat", "downbeat"],
                  dynamic_axes={"spect": {1: "frames"}, "beat": {1: "frames"}, "downbeat": {1: "frames"}},
                  opset_version=17, dynamo=False)
import os
print(f"saved {out}: {os.path.getsize(out) / 1e6:.1f} MB")

sess = ort.InferenceSession(out, providers=["CPUExecutionProvider"])
signal, sr = load_audio("audio_syn/syn_steady120.wav")
spect = LogMelSpect(device="cpu")(torch.tensor(signal[: sr * 30], dtype=torch.float32))
x = spect[None].numpy()
with torch.no_grad():
    ref = model(torch.tensor(x))
t0 = time.time()
b, d = sess.run(None, {"spect": x})
dt = time.time() - t0
print(f"30 s chunk: {dt * 1000:.0f} ms on this PC with ONNX Runtime")
print("max diff vs PyTorch:", float(np.abs(b - ref["beat"].numpy()).max()), float(np.abs(d - ref["downbeat"].numpy()).max()))
