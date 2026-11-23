"""
Writes fixtures for the app's unit test (TempoMapBuilderTest): the model's raw
output for each synthetic track plus what tempo_map.py builds from it. The
Kotlin port must produce the same sections.
Usage: python -I make_test_fixtures.py ../../app/src/test/resources/tempo
"""
import json, sys
from pathlib import Path
import soundfile as sf
sys.path.insert(0, str(Path(__file__).parent))
import tempo_map
from beat_this.inference import Audio2Frames, Postprocessor, load_audio

out = Path(sys.argv[1])
a2f = Audio2Frames(checkpoint_path="final0", device="cpu")
post = Postprocessor(type="minimal", fps=50)
for name in ("syn_meter_4_7_4", "syn_mixed", "syn_ramp90_130", "syn_jump100_140"):
    wav = Path("audio_syn") / f"{name}.wav"
    sig, sr = load_audio(str(wav))
    b, d = a2f(sig, sr)
    beats, downbeats = post(b, d)
    act = d.float().numpy()
    dur = sf.info(str(wav)).duration
    tm = tempo_map.build(beats, downbeats, dur, downbeat_activation=act)
    exp = [{k: s[k] for k in ("start", "end", "kind", "bpm", "beats_per_bar")} for s in tm["sections"]]
    (out / f"{name}.json").write_text(json.dumps({
        "beats": [float(x) for x in beats], "downbeats": [float(x) for x in downbeats],
        "act": [round(float(x), 3) for x in act], "duration": dur, "expected": exp}))
    print(name, exp)
