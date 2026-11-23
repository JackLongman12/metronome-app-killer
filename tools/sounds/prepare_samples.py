"""
Turns recorded drum hits into the short sounds the app ships (app/src/main/assets/sounds).

LEARN: a metronome sample must start *exactly* on its hit, or every click is
late. So for each file we find the first sample louder than 2% of the peak and
cut everything before it, keep only the first part of the ring (long tails blur
fast tempos), and fade the end out so it doesn't pop.

Source: Hydrogen's "GMRockKit" (GPL-3.0, same licence as this app):
https://github.com/hydrogen-music/hydrogen/tree/master/data/drumkits/GMRockKit

Usage: python tools/sounds/prepare_samples.py <folder with the downloaded .wav files>
"""
import array, os, sys, wave

# output name: (source file, longest length in seconds)
SOUNDS = {
    "stick_accent": ("SideStick-Hardest", 0.18), "stick_normal": ("SideStick-Med", 0.18),
    "cowbell_accent": ("Cowbell-Hardest", 0.16), "cowbell_normal": ("Cowbell-Med", 0.16),
    "bell_accent": ("Bell-Hard", 0.35), "bell_normal": ("Bell-Med", 0.35),
    "hat_accent": ("HatClosed-Hardest", 0.12), "hat_normal": ("HatClosed-Med", 0.12), "hat_soft": ("HatClosed-Soft", 0.08),
    "kick": ("Kick-Hardest", 0.25),
    "rim_accent": ("SnareRimshot-Hardest", 0.2), "rim_normal": ("SnareRimshot-Med", 0.2),
}

def main(src):
    out_dir = os.path.join(os.path.dirname(__file__), "..", "..", "app", "src", "main", "assets", "sounds")
    os.makedirs(out_dir, exist_ok=True)
    for name, (file, seconds) in SOUNDS.items():
        with wave.open(os.path.join(src, file + ".wav")) as w:
            assert w.getnchannels() == 1 and w.getsampwidth() == 2
            rate = w.getframerate()
            x = array.array("h", w.readframes(w.getnframes()))
        peak = max(abs(v) for v in x)
        start = next(i for i, v in enumerate(x) if abs(v) > peak * 0.02)
        x = x[start:start + int(rate * seconds)]
        fade = int(rate * 0.02)
        for i in range(fade):
            x[len(x) - fade + i] = int(x[len(x) - fade + i] * (1 - i / fade))
        with wave.open(os.path.join(out_dir, name + ".wav"), "wb") as o:
            o.setnchannels(1); o.setsampwidth(2); o.setframerate(rate)
            o.writeframes(x.tobytes())
        print(f"{name}: {file}, cut {start} samples of lead-in, {len(x) / rate * 1000:.0f} ms")

if __name__ == "__main__":
    main(sys.argv[1])
