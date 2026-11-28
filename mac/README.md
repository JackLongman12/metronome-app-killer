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

**Metronome Killer menu → Uninstall Metronome Killer…** removes the app and everything it created: settings, setlists, practice history, caches, and the files macOS keeps for every app. The full list is in `Sources/MetronomeKiller/AppFiles.swift`.

## What's here so far

- The metronome: odd, decimal and irrational time signatures, accents, subdivisions, 14 sounds (6 recorded), tap tempo, and keyboard shortcuts (Space, ↑/↓, Shift+↑/↓, T).
- Practice: speed trainer, gap click (with timing check), timing check, subdivision ladder and polyrhythm, plus a daily timer and history. On the tap pad, click or press T.
- Setlists: metronome settings in playing order. Next / Previous from the strip at the top, Page Down / Page Up, ⌘] / ⌘[, a MIDI pedal, or the ⏭ / ⏮ media keys.
- Mac-only:
  - **Menu bar metronome**: start/stop, tempo and tap from the menu bar. It can keep running there with the window closed (Settings → Menu bar).
  - **MIDI** (Settings → MIDI): send MIDI clock so Logic, Ableton or GarageBand follow the metronome; follow another app's clock and start/stop; and Learn any pedal, pad or key to start/stop, tap, change tempo or step through a setlist.
  - **Audio output** (Settings → Audio): play through a chosen device, and put the click on any pair of an interface's outputs (e.g. 3–4 for headphones only).
  - **Media keys, AirPods and Control Center**: play/pause, and ⏭ / ⏮ for the next setlist item.
- Self-update and clean uninstall.

## Building

On a Mac with Xcode 16 or newer:

```
BUILD_NUMBER=1 sh mac/build.sh
```

CI (`.github/workflows/mac.yml`) does this on every push that changes `mac/`.
