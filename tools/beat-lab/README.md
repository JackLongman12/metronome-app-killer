# Beat lab (Phase 2)

PC-side experiments for the song → tempo map feature, before porting to Android.
Nothing here ships in the app; CI ignores this folder.

## Setup

```
python -m venv .venv
.venv/Scripts/python -m pip install torch --index-url https://download.pytorch.org/whl/cpu
.venv/Scripts/python -m pip install https://github.com/CPJKU/beat_this/archive/main.zip yt-dlp soundfile matplotlib onnx onnxruntime onnxscript
```

`audio/`, `audio_syn/` and `out/` stay local (git-ignored). They hold downloaded songs,
generated test tracks and reference tabs that aren't ours to publish.

## Scripts

| Script | What it does |
|---|---|
| `make_synthetic.py audio_syn` | Drum tracks with known answers: steady, tempo jump, ramp, 4/4→7/4→4/4, 4/4→3/4→5/4 |
| `analyze.py <audio dir> out` | Runs beat_this, builds tempo maps (`tempo_map.py`), scores against `*_truth.json`, plots |
| `tempo_map.py` | Beats → sections (change points by dynamic programming, ramps, half/double fix, bar length). This is what gets ported to Kotlin |
| `compare_songsterr.py tab.json out/song.json` | Lines a Songsterr tab up with the audio and scores tempo, beats and bars |
| `export_onnx.py out/model.onnx [final0\|small0]` | Exports the model for the phone and checks it matches PyTorch |
| `quantize_check.py` | int8 model size/accuracy check (see open issue below) |

## Results so far (2026-11-23)

Synthetic tracks (known answers): beats 96–99 % correct; tempo jumps, ramps and
4/4→3/4→5/4 detected correctly; 4/4→7/4→4/4 found, with boundaries a few seconds late.
One track was read at half tempo (70 instead of 140), which is inherently ambiguous;
the app's editor needs ×2 / ÷2 buttons.

**Racecar (Periphery) against its Songsterr tab** (617 bars, 145 BPM, constant meter changes):

| Measure | v1 | v2 (beat repair + main tempo) |
|---|---|---|
| Beats vs tab grid (F-measure) | 0.90 | 0.91 |
| Time with correct tempo | 63 % | 83 % |
| Time at half/double tempo | 11 % | 0 % |
| Bar lengths matching the tab | 21 % | 23 % |
| Downbeats vs tab (F-measure) | n/a | 0.42 |

Takeaways:
- The model finds beats well even on this song. Most tempo errors came from our post-processing and are mostly fixed.
- **Downbeats in odd, constantly changing meters are the weak point.** That's the model itself: it was trained
  mostly on 4/4 and 3/4 music.
- Speed: about 50× real time on this PC (15-minute song in 17 s). On a phone, expect roughly 5–10× real time.

Model size for the phone: full model 83 MB as ONNX (23 MB as int8); small model 10.5 MB but clearly less accurate.

Open issue: running the ONNX export through beat_this's chunking gives low scores (F 0.30) even
unquantized, so the int8 comparison isn't valid yet. Probably an input-shape or chunking mismatch; to fix
before porting.
