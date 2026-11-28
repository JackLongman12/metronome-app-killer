# Metronome Killer

An ad-free metronome for Android and Mac, with practice tools and setlists.

Hobby project, GPL-3.0, not on the Play Store. Install it from [Releases](../../releases/latest).

## Install and update

1. Download the latest `metronome-killer-b*.apk` from [Releases](../../releases/latest) and open it. Android will ask you to allow installs from your browser or file manager.
2. After that the app updates itself. Every push to `main` publishes a new build, and the app offers it the next time you open it (Settings → Check for updates).
   - The first self-update asks you to allow installs from Metronome Killer.
   - On Android 12 and newer, later updates install without a confirmation prompt.

## What it does

- The metronome: odd, decimal and irrational time signatures, accents, subdivisions, recorded and synthesised sounds, tap tempo, flash and vibration, and a Bluetooth delay calibration.
- Practice: speed trainer, gap click, timing check, subdivision ladder, polyrhythm, and a daily practice timer.
- Setlists: saved metronome settings in playing order, with quick Next / Previous on stage.
- A Mac version with the same engine, plus a menu bar metronome and a choice of audio output: see [mac/README.md](mac/README.md).

The Songs feature (click tracks from YouTube, Spotify or your own songs) was removed in Android build 28 and Mac build 14. Updating to those builds deletes everything it downloaded.

## How it's built

If you're reading the code to learn, start here. Comments marked `LEARN:` explain the concepts.

```
UI (Jetpack Compose)          app/src/main/java/.../metronome, settings, MainActivity.kt
   │  reads StateFlows, calls methods
   ▼
MetronomeController           engine/MetronomeController.kt: one app-wide owner of state
   │  JNI calls (NativeEngine.kt)
   ▼
C++ ClickEngine on Oboe       app/src/main/cpp: sample-accurate clicks on a real-time thread
   ▲
PlaybackService               keeps it running with the screen off (foreground service)
```

- `App.kt` creates the long-lived objects once when the app starts.
- `update/` is the self-updater: it reads the latest GitHub Release and installs it.
- `practice/` and `setlists/` are the Practice and Setlists tabs.
- `.github/workflows/build.yml` builds and signs the APK on every push, then publishes it.

## Building

Requires JDK 17 and the Android SDK (platform `android-37.2`, build-tools 37, NDK 30.0.16248370, CMake 4.1.2).

```
./gradlew assembleDebug
```

Release builds in CI are signed with a key stored in repository secrets, so every build installs over the previous one.

## Credits

- Recorded click sounds: side stick, cowbell, ride bell, hi-hat, kick and rimshot from the "GMRockKit" drum kit from [Hydrogen](https://github.com/hydrogen-music/hydrogen) (GPL-3.0), and woodblock, claves, agogo bells, log drum, triangle and hand clap from [VCSL](https://github.com/sgossner/VCSL) (CC0), all trimmed by `tools/sounds/prepare_samples.py`.

- Fonts in the Comic book theme: [Bangers](https://github.com/googlefonts/bangers) and [Comic Neue](https://github.com/crozynski/comicneue), both under the SIL Open Font License 1.1.

## License

GPL-3.0. See [LICENSE](LICENSE).
