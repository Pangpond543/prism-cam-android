# Prism Cam (Android) — notes for Claude

Android phone + tablet version of Prism Cam: a premium, minimal camera app (photo + video) with Prism effects,
Classic look by default (light on the phone), Prism glass look available. Started 2026-11-24.
The desktop app is `../prism-cam/` (Tauri). This is a separate, native app — no code is shared with it.

Read first: `../CLAUDE.md` (suite rules), `../docs/LESSONS.md`, then `docs/plan.md` (the agreed plan and every
decision the user made), `docs/pitfalls.md`, `docs/architecture.md`.

The user is not a developer. Talk to them in Thai, plainly.

## What the user runs
The app installed on the user's device over USB. Test device so far: **HUAWEI MatePad Air** (DBY2-L09, HarmonyOS 4.2 =
Android 12 / API 31, no Google services, landscape 2800×1840) — see pitfalls. The user's phone (Android 13–14) is not
connected yet. "Done" = installed on THAT phone over USB and the
version shown in the app (Info) / `adb shell dumpsys package com.prism.cam | grep versionName` equals the build you
just made. A copy of every APK goes to `apk/Prism-Cam-<ver>.apk`.

## Stack
Kotlin + Jetpack Compose (UI) · CameraX (+ Camera2 interop) · OpenGL ES effect pipeline (`effects/GlFx.kt`) ·
MediaPipe on-device AI (`ai/Vision.kt`, models in assets) · ZXing QR · WebView PC link · GitHub updater. Android Gradle Plugin 9 (built-in Kotlin), Gradle 9.9.2,
JDK 21, compileSdk/targetSdk 36, minSdk 26.

## Toolchain (installed 2026-11-24, all outside AppData — see ../docs/LESSONS.md)
- `%USERPROFILE%\PrismAndroid\jdk` (Temurin 21), `...\sdk` (cmdline-tools, platform-tools = adb, platforms;android-36,
  build-tools;37.0.0), `...\gradle` (Gradle 9.9.2). The SDK licence was accepted by the user (2026-11-24).
- Build output: `%USERPROFILE%\PrismCamAndroidBuild\` (Gradle build dirs, project cache, Kotlin cache). Never in OneDrive,
  never under AppData. The Android SDK default (`%LOCALAPPDATA%\Android\Sdk`) is NOT used — MSIX shell would hide it.

## Build + install (Git Bash, from this folder)
```bash
bash tools/build.sh            # release APK, signed with the Prism key → apk/ ; installs + starts it if a phone is connected
bash tools/build.sh --no-install
```
`tools/env.sh` sets JAVA_HOME / ANDROID_HOME / PATH for manual gradle or adb use (`source tools/env.sh`).

## Signing key (never lose it)
`%USERPROFILE%\PrismKeys\prism-cam-android.jks` + `prism-cam-android.properties` (passwords). NOT in git, NOT in OneDrive
by default. Every APK the user installs is signed with this key (also test builds) — a build signed with another key
cannot update the installed app; the user would have to uninstall and lose the app's data. See pitfalls.

## Release
GitHub `Pangpond543/prism-cam-android` (public). New version: bump `versionCode` + `versionName` in
`app/build.gradle.kts`, write `docs/release-notes-<ver>.md` (TH + EN), `bash tools/i18n-audit.sh` (must be empty),
`bash tools/build.sh` (installs on the connected device — test it there), commit + push, then
`gh release create v<ver> apk/Prism-Cam-<ver>.apk --repo Pangpond543/prism-cam-android --notes-file docs/release-notes-<ver>.md`.
The in-app updater reads `releases/latest` and the first `.apk` asset. Always sign with the Prism key (build.sh does).

## Rules specific to this app
- Thai is the source text: `T("…")` in Kotlin, English in `app/src/main/java/com/prism/cam/i18n/En.kt` keyed by the Thai
  string. Every new Thai string needs its English entry. Same mechanism as the desktop suite.
- Prism shell content (name `Prism Cam`, "by 543", teal logo + camera glyph from `../tools/brand/logos.js`, TH/EN,
  Light/Dark, Glass/Classic, Info + GitHub updater) must match the suite. No title bar on mobile: it lives in the "…" menu.
- Default look = Classic. Glass is opt-in.
- Camera frames stay on the GPU. Nothing heavy on the main thread. Measure on the real phone (docs/plan.md targets).
