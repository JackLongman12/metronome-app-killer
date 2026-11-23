"""
Shrinks the ONNX model by storing weights as 8-bit integers ("dynamic
quantization") and checks how much accuracy that costs on the synthetic tracks.

LEARN: a neural network is mostly a huge list of numbers (weights). Stored as
32-bit floats they take 4 bytes each; as 8-bit ints, 1 byte. The maths is a
little less precise, which often barely matters.
Usage: python -I quantize_check.py out/beat_this.onnx out/beat_this_int8.onnx
"""
import json
import os
import sys
import time
from pathlib import Path

import numpy as np
import onnxruntime as ort
import torch
from onnxruntime.quantization import QuantType, quantize_dynamic
from beat_this.inference import LogMelSpect, Postprocessor, load_audio, split_predict_aggregate

sys.path.insert(0, str(Path(__file__).parent))
from analyze import f_measure  # noqa: E402

src, dst = sys.argv[1], sys.argv[2]
quantize_dynamic(src, dst, weight_type=QuantType.QInt8)
print(f"int8 model: {os.path.getsize(dst) / 1e6:.1f} MB (from {os.path.getsize(src) / 1e6:.1f} MB)")

mel = LogMelSpect(device="cpu")
post = Postprocessor(type="minimal", fps=50)
for path in (src, dst):
    sess = ort.InferenceSession(path, providers=["CPUExecutionProvider"])
    scores, t_total, a_total = [], 0.0, 0.0
    for wav in sorted(Path("audio_syn").glob("*.wav")):
        signal, sr = load_audio(str(wav))
        spect = mel(torch.tensor(signal, dtype=torch.float32))
        # Wrap the ONNX session so beat_this's own chunk-and-stitch code can call it.
        # (It splits into 30 s chunks with a small overlap and drops the edges,
        # which the app must copy.)
        def onnx_model(x, sess=sess):
            b, d = sess.run(None, {"spect": x.numpy().astype(np.float32)})
            return {"beat": torch.tensor(b), "downbeat": torch.tensor(d)}
        t0 = time.time()
        pred = split_predict_aggregate(spect=spect, chunk_size=1500, border_size=6,
                                       overlap_mode="keep_first", model=onnx_model)
        t_total += time.time() - t0
        a_total += len(signal) / sr
        beats, _ = post(pred["beat"], pred["downbeat"])
        truth = json.loads(Path(f"audio_syn/{wav.stem}_truth.json").read_text())
        scores.append(f_measure(beats, truth["beats"]))
    print(f"{Path(path).name}: mean beat F {np.mean(scores):.3f}, {a_total / t_total:.0f}x realtime on this PC")
