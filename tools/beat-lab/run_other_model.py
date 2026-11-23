"""
Runs a different beat/downbeat model on every benchmark song and stores its
output in bench/cache/<song>.<tag>.npz, so bench.py can score it exactly like
our default model. Run it with THAT model's own Python environment:

  .venv-md/Scripts/python -I run_other_model.py md bench        (Masked-Diffusion Beat This)
  .venv-et/Scripts/python -I run_other_model.py explore bench   (Explore This!)
"""
import sys
import time
from pathlib import Path

import numpy as np

tag, folder = sys.argv[1], Path(sys.argv[2])
cache = folder / "cache"
cache.mkdir(exist_ok=True)

if tag == "md":
    from md_beat_this.inference import File2Beats
    # run1 ensemble, but only its first model: 3x faster on CPU, slightly less accurate
    f2b = File2Beats(checkpoint_path="run1", device="cpu", steps=8, num_models=1)
    run = lambda wav: f2b(str(wav))
elif tag == "explore":
    sys.path.insert(0, str(Path(__file__).parent))
    from explore_runner import ExploreThis  # small adapter, see explore_runner.py
    model = ExploreThis(Path(__file__).parent / "models/explore_this/explore_this_checkpoints/final0")
    run = model
else:
    raise SystemExit(f"unknown model tag {tag}")

for wav in sorted(folder.glob("*.wav")):
    out = cache / f"{wav.stem}.{tag}.npz"
    if out.exists():
        continue
    t0 = time.time()
    beats, downbeats = run(wav)
    secs = time.time() - t0
    np.savez_compressed(out, beats=np.asarray(beats), downbeats=np.asarray(downbeats), secs=secs)
    print(f"{wav.stem}: {len(beats)} beats, {secs:.0f}s", flush=True)
