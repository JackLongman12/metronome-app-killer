"""
Turns recorded drum hits into the short sounds the app ships (app/src/main/assets/sounds).

LEARN: a metronome sample must start *exactly* on its hit, or every click is
late. So for each file we find where the hit really begins (see main) and
cut everything before it, keep only the first part of the ring (long tails blur
fast tempos), and fade the end out so it doesn't pop.

Sources (both free to ship with a GPL app):
- Hydrogen's "GMRockKit" (GPL-3.0):
  https://github.com/hydrogen-music/hydrogen/tree/master/data/drumkits/GMRockKit
- VCSL, the Versilian Community Sample Library (CC0, public domain):
  https://github.com/sgossner/VCSL (Idiophones/Struck Idiophones)

Usage: python tools/sounds/prepare_samples.py <hydrogen folder> <vcsl folder>
(each holding the downloaded .wav files named below)
"""
import array, os, sys, wave

# output name: (source kit, source file, longest length in seconds)
SOUNDS = {
    "stick_accent": ("hydrogen", "SideStick-Hardest", 0.18), "stick_normal": ("hydrogen", "SideStick-Med", 0.18),
    "cowbell_accent": ("hydrogen", "Cowbell-Hardest", 0.16), "cowbell_normal": ("hydrogen", "Cowbell-Med", 0.16),
    "bell_accent": ("hydrogen", "Bell-Hard", 0.35), "bell_normal": ("hydrogen", "Bell-Med", 0.35),
    "hat_accent": ("hydrogen", "HatClosed-Hardest", 0.12), "hat_normal": ("hydrogen", "HatClosed-Med", 0.12),
    "hat_soft": ("hydrogen", "HatClosed-Soft", 0.08),
    "kick": ("hydrogen", "Kick-Hardest", 0.25),
    "rim_accent": ("hydrogen", "SnareRimshot-Hardest", 0.2), "rim_normal": ("hydrogen", "SnareRimshot-Med", 0.2),
    # VCSL
    "wood_accent": ("vcsl", "wood_click_ff", 0.15), "wood_normal": ("vcsl", "wood_click_mp", 0.15),
    "wood_soft": ("vcsl", "wood_click_pp_rr1", 0.1),
    "clave_accent": ("vcsl", "Claves1_Hit_v3_rr1_Mid", 0.15), "clave_normal": ("vcsl", "Claves1_Hit_v2_rr1_Mid", 0.15),
    "clave_soft": ("vcsl", "Claves1_Hit_v1_rr1_Mid", 0.1),
    "agogo_high": ("vcsl", "Agogo_High_v3_rr1_Mid", 0.25), "agogo_low": ("vcsl", "Agogo_Low_v2_rr1_Mid", 0.25),
    "agogo_soft": ("vcsl", "Agogo_High_v1_rr1_Mid", 0.15),
    "log_high": ("vcsl", "LogDrumHi_MedM_v3_rr1_Sum", 0.25), "log_low": ("vcsl", "LogDrumLo_MedM_v2_rr1_Sum", 0.25),
    "log_soft": ("vcsl", "LogDrumLo_MedM_v1_rr1_Sum", 0.15),
    "triangle_accent": ("vcsl", "Triangle3_HitFM_v2_rr1_Mid", 0.4), "triangle_normal": ("vcsl", "Triangle3_HitFM_v1_rr1_Mid", 0.4),
    "clap_accent": ("vcsl", "SoloClap_vl4", 0.2), "clap_normal": ("vcsl", "SoloClap_vl2", 0.2), "clap_soft": ("vcsl", "SoloClap_vl1", 0.15),
}


def read_mono(path):
    """Any 16- or 24-bit WAV -> (list of ints in 16-bit range, sample rate). Stereo is averaged to mono."""
    with wave.open(path) as w:
        ch, width, rate = w.getnchannels(), w.getsampwidth(), w.getframerate()
        raw = w.readframes(w.getnframes())
    if width == 2:
        v = array.array("h", raw).tolist()
    elif width == 3:
        # LEARN: 24-bit samples are 3 bytes each, little-endian; shift right by 8 to fit 16 bits.
        v = [int.from_bytes(raw[i:i + 3], "little", signed=True) >> 8 for i in range(0, len(raw), 3)]
    else:
        raise ValueError(f"{path}: {width * 8}-bit not supported")
    if ch == 2:
        v = [(v[i] + v[i + 1]) // 2 for i in range(0, len(v) - 1, 2)]
    return v, rate


def main(folders):
    out_dir = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "sounds")
    os.makedirs(out_dir, exist_ok=True)
    for name, (kit, file, seconds) in SOUNDS.items():
        x, rate = read_mono(os.path.join(folders[kit], file + ".wav"))
        peak = max(abs(v) for v in x)
        # The hit proper is the first sample above 15% of the peak (quieter stuff
        # before it is mic noise or the stick's approach); keep 0.5 ms before it
        # so the very start of the transient isn't chopped.
        hit = next(i for i, v in enumerate(x) if abs(v) > peak * 0.15)
        start = max(0, hit - rate // 2000)
        x = x[start:start + int(rate * seconds)]
        fade = int(rate * 0.02)
        for i in range(fade):
            x[len(x) - fade + i] = int(x[len(x) - fade + i] * (1 - i / fade))
        with wave.open(os.path.join(out_dir, name + ".wav"), "wb") as o:
            o.setnchannels(1); o.setsampwidth(2); o.setframerate(rate)
            o.writeframes(array.array("h", x).tobytes())
        print(f"{name}: {file}, cut {start} samples of lead-in, {len(x) / rate * 1000:.0f} ms")


if __name__ == "__main__":
    main({"hydrogen": sys.argv[1], "vcsl": sys.argv[2]})
