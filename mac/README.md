# Metronome Killer for Mac

A native Mac version: Swift and SwiftUI for the screens, and the same C++ click engine as the Android app, playing through CoreAudio with about 3 ms of output delay. There's no web view and no Java, and it uses no CPU while stopped.

Apple Silicon (M1 or newer), macOS 13 or later.

## Install

1. Download the newest `MetronomeKiller-mac-b<number>.dmg` from [Releases](https://github.com/JackLongman12/metronome-app-killer/releases). Mac builds are tagged `mac-b…`.
2. Open it and drag **Metronome Killer** to **Applications**.
3. The app isn't signed with a paid Apple developer account, so macOS blocks it the first time:
   - **macOS 15 and newer:** open it once and close the warning. Then go to **System Settings → Privacy & Security**, scroll down, and click **Open Anyway**.
   - **macOS 13–14:** right-click the app, choose **Open**, then **Open** again.

After that it opens normally. It checks GitHub for a new version every time it opens and can update itself (it must be in Applications to do that).

## Uninstall

**Metronome Killer menu → Uninstall Metronome Killer…** removes the app and everything it created: settings, songs, setlists, downloaded tools, caches, and the files macOS keeps for every app. The full list is in `Sources/MetronomeKiller/AppFiles.swift`.

## What's here so far

- The metronome: odd, decimal and irrational time signatures, accents, subdivisions, 14 sounds (6 recorded), tap tempo, and keyboard shortcuts (Space, ↑/↓, Shift+↑/↓, T).
- Songs:
  - Paste a YouTube, Spotify or Songsterr link (playlists too), or drop audio files on the window.
  - The app finds every beat and bar, plays the song with a click locked to it, and reads part names from Songsterr tabs.
  - Part rows or the full grid, looping a row, the Speed up loop, 50–100 % speed, song and click volume, timing nudge, and Edit mode.
  - ← and → move by one bar.
- Downloaded on first use, into the app's own folder (so Uninstall removes them):
  - For analysis: ONNX Runtime (13 MB, MIT) and the beat model (28 MB).
  - For links: yt-dlp (37 MB, updates itself) and QuickJS (1.4 MB), which yt-dlp needs for YouTube.
  - YouTube audio is saved as AAC (.m4a), because macOS can't decode YouTube's Opus files without extra software.
- Self-update and clean uninstall.
- Practice and Setlists are coming next.

## Building

On a Mac with Xcode 16 or newer:

```
BUILD_NUMBER=1 sh mac/build.sh
```

CI (`.github/workflows/mac.yml`) does this on every push that changes `mac/`.
