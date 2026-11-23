"""
Fixtures for the app's MdUnmaskTest: runs the reference unmasking loop
(export_md_phone.unmask) with a deterministic stand-in network that the Kotlin
test reproduces exactly, and records the decisions. The Kotlin loop must match.
Usage (Masked-Diffusion env): python -I make_md_fixtures.py ../../app/src/test/resources/md
"""
import json, math, sys
from pathlib import Path
import numpy as np
sys.path.insert(0, str(Path(__file__).parent))
from export_md_phone import unmask

def spect(L, pad):
    s = np.zeros((L, 128), np.float32)
    for i in range(L - pad):
        s[i, 0] = ((i * 7919) % 100) / 100.0
        s[i, 1] = 1.0
    return s

def fake_net(sp, bt, dt):
    L = len(bt)
    b = np.zeros(L, np.float32); d = np.zeros(L, np.float32)
    for i in range(L):
        tb = 0.8 if bt[i] == 3 else (-0.3 if bt[i] == 2 else 0.0)
        td = 0.5 if dt[i] == 3 else 0.0
        b[i] = np.float32(3 * math.sin(0.37 * i) + 0.5 * float(sp[i, 0]) + tb)
        d[i] = np.float32(2 * math.cos(0.11 * i) - 1 + td)
    return b, d

out = Path(sys.argv[1])
cases = []
for (L, pad, steps, prefix_len) in [(300, 0, 8, 0), (300, 40, 8, 0), (1500, 12, 8, 400), (500, 0, 4, 0), (500, 0, 2, 120)]:
    sp = spect(L, pad)
    prefix = None
    if prefix_len:
        pb = np.array([(i % 9) == 0 for i in range(prefix_len)])
        pd = np.array([(i % 36) == 0 for i in range(prefix_len)])
        prefix = (pb, pd)
    beat, down = unmask(fake_net, sp, steps, prefix)
    cases.append({"L": L, "pad": pad, "steps": steps, "prefix_len": prefix_len,
                  "beats": np.nonzero(beat)[0].tolist(), "downbeats": np.nonzero(down)[0].tolist()})
    print(L, pad, steps, prefix_len, "beats", int(beat.sum()), "downbeats", int(down.sum()))
(out / "unmask_cases.json").write_text(json.dumps(cases))
