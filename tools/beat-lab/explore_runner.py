"""
Minimal adapter so Explore This! can be called like beat_this: audio file in,
(beats, downbeats) in seconds out.

Explore This! (Kihlborg, Holzapfel, Schlüter, ISMIR 2026) is a fork of
beat_this that adds a "tatum grid" (the fastest regular pulse) and a "subgrid"
that decides which grid points are beats and downbeats, with explicit meter
states, so it can follow time-signature changes. We use its Viterbi mode,
which the paper reports for changing meters. Max bar length it models: 7 beats.
"""
import sys
from pathlib import Path

import pathlib

import numpy as np
import torch

# The checkpoints were saved on Linux and contain pickled PosixPath objects,
# which Windows can't create. Loading them as WindowsPath is harmless here.
pathlib.PosixPath = pathlib.WindowsPath  # type: ignore[misc]

sys.path.insert(0, str(Path(__file__).parent / "explore_this_src"))
from explore_this.inference import load_model, split_predict_aggregate  # noqa: E402
from explore_this.preprocessing import LogMelSpect, load_audio  # noqa: E402

FPS = 50


class ExploreThis:
    def __init__(self, checkpoint: Path, viterbi: bool = True):
        self.model = load_model(str(checkpoint), "cpu")
        if viterbi:
            self.model.subgrid_processor.mask_method = "viterbi"
            self.model.subgrid_processor.viterbi_transition_prob = 0.3
        self.spect = LogMelSpect(device="cpu")

    @torch.inference_mode()
    def __call__(self, wav):
        signal, sr = load_audio(str(wav))
        if signal.ndim == 2:
            signal = signal.mean(1)
        if sr != 22050:
            import soxr
            signal = soxr.resample(signal, sr, 22050)
        spect = self.spect(torch.tensor(signal, dtype=torch.float32))
        out = split_predict_aggregate(spect, 1500, "keep_first", self.model)
        beats = torch.nonzero(out.beat_mask.flatten()).flatten().numpy() / FPS
        downbeats = torch.nonzero(out.downbeat_mask.flatten()).flatten().numpy() / FPS
        return np.asarray(beats, float), np.asarray(downbeats, float)
