# Metronome Killer

An ad-free Android metronome that can build a click track for a specific song. Give it a YouTube, SoundCloud or Spotify link, or a song on your phone, and it maps the song's tempo, including tempo changes, so the click follows the music.

Hobby project, GPL-3.0, not on the Play Store. Install it from [Releases](../../releases/latest).

## Install and update

1. Download the latest `metronome-killer-b*.apk` from [Releases](../../releases/latest) and open it. Android will ask you to allow installs from your browser or file manager.
2. After that the app updates itself. Every push to `main` publishes a new build, and the app offers it the next time you open it (Settings → Check for updates).
   - The first self-update asks you to allow installs from Metronome Killer.
   - On Android 12 and newer, later updates install without a confirmation prompt.

## Status

| Phase | What | State |
|---|---|---|
| 0 | Project setup, CI releases, in-app updater, basic click | Done |
| 1 | Native Oboe engine, 8 sounds, background playback, beat editor, time signature/sound pickers, flash/vibration, Bluetooth sync | Done |
| 2 | Beat-tracking prototype (beat_this) on test songs | Next |
| 3 | Local songs → tempo map, editor, play-along | Planned |
| 4 | YouTube / SoundCloud (NewPipe Extractor), Spotify matching, share-to-app | Planned |
| 5 | Speed trainer, gap click, A–B loop, slow-down, setlists, export | Planned |
| 6 | Listen mode (live mic tracking) and timing feedback | Planned |

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
- `.github/workflows/build.yml` builds and signs the APK on every push, then publishes it.

## Building

Requires JDK 17 and the Android SDK (platform `android-37.2`, build-tools 37, NDK 30.0.16248370, CMake 4.1.2).

```
./gradlew assembleDebug
```

Release builds in CI are signed with a key stored in repository secrets, so every build installs over the previous one.

## Credits

- Fonts in the Comic book theme: [Bangers](https://github.com/googlefonts/bangers) and [Comic Neue](https://github.com/crozynski/comicneue), both under the SIL Open Font License 1.1.

## License

GPL-3.0. See [LICENSE](LICENSE).
