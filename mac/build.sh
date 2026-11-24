#!/bin/sh
# ─────────────────────────────────────────────────────────────────────────────
# Builds "Metronome Killer.app" and packs it two ways:
#   - .dmg: what people download (drag the app to Applications)
#   - .zip: what the in-app updater downloads
# Run on a Mac: BUILD_NUMBER=5 ./build.sh   (CI does this on every push)
#
# LEARN: a Mac ".app" is just a folder with a fixed layout:
#   Contents/Info.plist      the ID card (name, version)
#   Contents/MacOS/<program> the program itself
#   Contents/Resources/      sounds, icons
# Finder shows that folder as a single double-clickable app.
# ─────────────────────────────────────────────────────────────────────────────
set -eu
cd "$(dirname "$0")"
BUILD="${BUILD_NUMBER:-1}"
REPO="${GITHUB_REPOSITORY:-JackLongman12/metronome-app-killer}"

# Apple Silicon only (M1 and newer): one architecture keeps the app small.
swift build -c release --arch arm64
BIN="$(swift build -c release --arch arm64 --show-bin-path)/MetronomeKiller"

APP="build/Metronome Killer.app"
rm -rf build
mkdir -p "$APP/Contents/MacOS" "$APP/Contents/Resources/sounds"
cp "$BIN" "$APP/Contents/MacOS/MetronomeKiller"
# Removes debugging names from the program: smaller download, same behaviour.
strip -x "$APP/Contents/MacOS/MetronomeKiller"
# The recorded drum hits are shared with the Android app.
cp ../app/src/main/assets/sounds/*.wav "$APP/Contents/Resources/sounds/"
sed "s/__BUILD__/$BUILD/g; s#__REPO__#$REPO#g" Info.plist > "$APP/Contents/Info.plist"

# The app icon: drawn at every size by tools/MakeIcon.swift, then packed into
# one .icns file (iconutil comes with macOS).
swift tools/MakeIcon.swift build/AppIcon.iconset
iconutil -c icns build/AppIcon.iconset -o "$APP/Contents/Resources/AppIcon.icns"
rm -rf build/AppIcon.iconset

# LEARN: Apple Silicon Macs refuse to run code with no signature at all. "-" is
# an "ad-hoc" signature: free, no Apple account, proves nothing about who made
# it, but lets the app run. (That's why macOS asks before opening it the first time.)
codesign --force --deep --sign - "$APP"

ditto -c -k --keepParent "$APP" "build/MetronomeKiller-mac-b$BUILD.zip"

mkdir -p build/dmg
cp -R "$APP" build/dmg/
ln -s /Applications build/dmg/Applications
hdiutil create -volname "Metronome Killer" -srcfolder build/dmg -ov -format UDZO "build/MetronomeKiller-mac-b$BUILD.dmg"
rm -rf build/dmg

ls -la build
