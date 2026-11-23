"""
Builds the benchmark: for each song in bench_songs.json, fetch its Songsterr
tab, the exact time every bar starts in the tab's synced YouTube video, and
that video's audio. The result is a real song with a human-checked answer key.

Output (local only, git-ignored):  bench/<slug>.wav  +  bench/<slug>_truth.json
truth = {"bars": [{"start", "end", "sig", "quarters", "bpm"}], "beats": [...],
         "downbeats": [...], "video": id, "songsterr": id}

Why this works: Songsterr's player scrolls the tab in sync with a video, so
it stores one timestamp per bar ("video points"). Those are our downbeats;
the time signature tells us how many beats fit between them.

Limitations: beats are placed on QUARTER notes (7/8 bars hold 3.5), and songs
whose repeat structure we can't unroll are skipped.

Usage: python -I build_benchmark.py bench_songs.json bench/
"""
import json
import re
import subprocess
import sys
import time
import urllib.parse
import urllib.request
from pathlib import Path

UA = {"User-Agent": "Mozilla/5.0", "Accept-Encoding": "identity"}


def get(url):
    req = urllib.request.Request(url, headers=UA)
    with urllib.request.urlopen(req, timeout=30) as r:
        data = r.read()
    if data[:2] == b"\x1f\x8b":  # gzip
        import gzip
        data = gzip.decompress(data)
    return json.loads(data.decode("utf-8"))


def slug(s):
    return re.sub(r"[^a-z0-9]+", "-", s.lower()).strip("-")


def find_song(artist, title):
    q = urllib.parse.quote(f"{artist} {title}")
    res = get(f"https://www.songsterr.com/api/songs?pattern={q}&size=20")
    norm = lambda s: re.sub(r"[^a-z0-9]", "", s.lower())
    for r in res:
        if norm(r["artist"]) == norm(artist) and norm(r["title"]) == norm(title) and r.get("hasPlayer"):
            return r
    for r in res:  # looser: title match only
        if norm(r["title"]) == norm(title) and r.get("hasPlayer"):
            return r
    return None


def unroll(measures):
    """Expand repeat signs into playback order (simple repeats + voltas)."""
    order, i, start, passes = [], 0, 0, {}
    guard = 0
    while i < len(measures) and guard < 20000:
        guard += 1
        m = measures[i]
        if m.get("repeatStart"):
            start = i
        volta = m.get("alternateEnding")
        p = passes.get(start, 1)
        if volta and p not in volta:
            i += 1
            continue
        order.append(i)
        close = m.get("repeatClose")
        if close:
            times = close if isinstance(close, int) else 2
            if p < times:
                passes[start] = p + 1
                i = start
                continue
            passes[start] = 1
        i += 1
    return order


def truth_from(tab, points):
    meas = tab["measures"]
    order = list(range(len(meas)))
    if len(points) != len(meas):
        unrolled = unroll(meas)
        if len(unrolled) == len(points):
            order = unrolled
        else:
            # Often the sync simply stops a few bars before the tab ends (or vice
            # versa). Accept small differences by trimming; sync_quality below
            # catches cases where that misaligns things.
            cand = min((list(range(len(meas))), unrolled), key=lambda o: abs(len(o) - len(points)))
            if abs(len(cand) - len(points)) > 5:
                return None
            order = cand
    n = min(len(order), len(points))
    order, points = order[:n], points[:n]
    tempo_list = ((tab.get("automations") or {}).get("tempo")) or [{"measure": 0, "bpm": 120, "type": 4}]
    tempos = {t["measure"]: t["bpm"] * 4 / t.get("type", 4) for t in tempo_list}
    # signatures carry forward in WRITTEN order
    sigs, sig = [], (4, 4)
    for m in meas:
        sig = tuple(m.get("signature", sig))
        sigs.append(sig)
    qpm = 120.0
    bars, beats = [], []
    for k, mi in enumerate(order):
        qpm = tempos.get(mi, qpm)
        num, den = sigs[mi]
        quarters = num * 4 / den
        start = points[k]
        end = points[k + 1] if k + 1 < len(points) else start + quarters * 60 / qpm
        if end <= start:
            continue
        bpm = quarters * 60 / (end - start)
        bars.append({"start": round(start, 3), "end": round(end, 3), "sig": f"{num}/{den}",
                     "quarters": quarters, "bpm": round(bpm, 2), "tab_bpm": qpm})
        q = (end - start) / quarters
        j = 0.0
        while j < quarters - 1e-6:
            beats.append(round(start + j * q, 4))
            j += 1.0
    # Sync quality: share of bars whose synced length matches the tab tempo within 10 %.
    # A good sync is >0.8; badly synced fan uploads are excluded from scoring.
    ok = [abs(b["bpm"] / b["tab_bpm"] - 1) < 0.10 for b in bars]
    return {"bars": bars, "beats": beats, "downbeats": [b["start"] for b in bars],
            "sync_quality": round(sum(ok) / max(len(ok), 1), 3)}


def main():
    songs = json.load(open(sys.argv[1], encoding="utf-8"))["songs"]
    out = Path(sys.argv[2] if len(sys.argv) > 2 else "bench")
    out.mkdir(exist_ok=True)
    py = sys.executable
    report = []
    for s in songs:
        name = slug(f'{s["artist"]}-{s["title"]}')
        truth_p = out / f"{name}_truth.json"
        if truth_p.exists() and (out / f"{name}.wav").exists():
            report.append((name, "cached"))
            continue
        try:
            hit = {"songId": s["songId"]} if s.get("songId") else find_song(s["artist"], s["title"])
            if not hit:
                report.append((name, "not on Songsterr with a player"))
                continue
            sid = hit["songId"]
            meta = get(f"https://www.songsterr.com/api/meta/{sid}")
            rev, image = meta["revisionId"], meta["image"]
            part = hit.get("popularTrack", hit.get("defaultTrack", 0)) or 0
            if "songId" in s:  # pinned: read the track list from meta
                part = meta.get("popularTrack", meta.get("defaultTrack", 0)) or 0
            tab = get(f"https://dqsljvtekg760.cloudfront.net/{sid}/{rev}/{image}/{part}.json")
            vids = get(f"https://www.songsterr.com/api/video-points/{sid}/{rev}/list")
            vids = [v for v in vids if v.get("status") == "done" and v.get("points")]
            # prefer the original recording sync (feature None), then any alternative
            vids.sort(key=lambda v: (v.get("feature") is not None, v.get("feature") != "alternative"))
            if not vids:
                report.append((name, "no video sync"))
                continue
            v = vids[0]
            truth = truth_from(tab, v["points"])
            if not truth:
                report.append((name, f"bar count mismatch ({len(v['points'])} points vs {len(tab['measures'])} bars)"))
                continue
            truth.update({"video": v["videoId"], "songsterr": sid, "revision": rev,
                          "group": s["group"], "artist": s["artist"], "title": s["title"]})
            wav = out / f"{name}.wav"
            r = subprocess.run([py, "-m", "yt_dlp", "-q", "--no-playlist", "-f", "bestaudio", "-x",
                                "--audio-format", "wav", "--postprocessor-args", "ffmpeg:-ar 22050 -ac 1",
                                "-o", str(out / f"{name}.%(ext)s"), f"https://www.youtube.com/watch?v={v['videoId']}"],
                               capture_output=True, text=True)
            if not wav.exists():
                report.append((name, "audio download failed: " + r.stderr.strip()[-120:]))
                continue
            truth_p.write_text(json.dumps(truth))
            report.append((name, f"ok: {len(truth['bars'])} bars, video {v['videoId']}, sync quality {truth['sync_quality']}"))
        except Exception as e:  # keep going; one bad song shouldn't stop the batch
            report.append((name, f"error: {e}"))
        print(report[-1], flush=True)
        time.sleep(1.0)  # be polite to Songsterr
    print("\n".join(f"{n:45s} {msg}" for n, msg in report))


if __name__ == "__main__":
    main()
