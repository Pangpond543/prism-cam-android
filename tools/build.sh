#!/usr/bin/env bash
# Build the signed release APK → apk/Prism-Cam-<ver>.apk, then (unless --no-install) install it on the connected
# phone, start it and print the version the phone now has. That last line is the proof (CLAUDE.md "What the user runs").
set -euo pipefail
cd "$(dirname "$0")/.."
source tools/env.sh
[ -f "$KEYPROPS" ] || { echo "Signing key missing: $KEYPROPS (see docs/pitfalls.md 'Signing')"; exit 1; }

VER=$(sed -n 's/.*versionName = "\([^"]*\)".*/\1/p' app/build.gradle.kts)
echo "== Prism Cam Android $VER"
gradle "${GRADLE_FLAGS[@]}" :app:assembleRelease

APK="$PRISM_OUT/build/app/outputs/apk/release/app-release.apk"
mkdir -p apk
cp "$APK" "apk/Prism-Cam-$VER.apk"
ls -l "apk/Prism-Cam-$VER.apk"

if [ "${1:-}" = "--no-install" ]; then exit 0; fi
if [ "$(adb get-state 2>/dev/null || true)" != "device" ]; then
  echo "No phone connected (adb devices below) — APK built but NOT installed."
  adb devices -l
  exit 0
fi
adb install -r "apk/Prism-Cam-$VER.apk"
adb shell am start -n com.prism.cam/.MainActivity >/dev/null
echo "phone now has: $(adb shell dumpsys package com.prism.cam | grep -m1 versionName | tr -d '\r' | xargs)"
