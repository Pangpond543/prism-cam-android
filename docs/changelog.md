# Prism Cam Android — changelog

- 2026-11-24 — **Phase 0: start.** Plan agreed (`docs/plan.md`): native Kotlin camera app, Classic default, glass
  optional, portrait + landscape (phone + tablet), clean screen keeps the shutter in place. Installed JDK 21, Android SDK
  (licence accepted by the user), Gradle 9.9.2 under `%USERPROFILE%\PrismAndroid`. Project skeleton (AGP 9.5.2, Kotlin 2.4.21,
  Compose BOM 2026.11.01), Prism component kit (theme tokens Classic/Glass × Light/Dark, teal Prism logo, wordmark +
  by 543, pills, segmented switch, panels, chips, toast, shutter / round / zoom camera controls, suite icons
  converted to Kotlin), TH/EN mechanism, local log, test hook, signing key in `%USERPROFILE%\PrismKeys`.
  First APK `apk/Prism-Cam-0.0.1.apk` (1.3 MB, signature checked). compileSdk 37.2 (AndroidX needs it), targetSdk 36.
- 2026-11-24 — **Installed on the user's tablet** (HUAWEI MatePad Air, HarmonyOS 4.2 / Android 12). Phone reports
  `versionName=0.0.1`, log `START ver=0.0.1 sdk=31 device=HUAWEI DBY2-L09`. Cold start 0.4–0.7 s. Checked on the tablet:
  Classic light / dark, Glass light / dark, English. Fixes from that check: glass panes showed a pale inner rectangle
  (shadow through the glass) → outside-only shadow; Android < 13 got a flat gradient → drifting pastel blobs; test-hook
  acts did not arrive on a warm start → `tools/shots.sh` cold-starts, `addOnNewIntentListener`.
- 2026-11-24 — **Phase 1: the camera (v0.1.0)** — user: "start phase 1". CameraX engine (`camera/PrismCamera.kt`) +
  minimal camera screen (`ui/CameraScreen.kt`), portrait + landscape (phone / tablet sizes), "…" menu (`ui/MenuSheet.kt`:
  theme, style, language, About), permission screen. Photo 4:3 at full sensor size (4160×3120 on the tablet, JPEG 95,
  zero-shutter-lag), video 16:9 MP4 FHD 30 fps (stabilised when supported), pause / resume, photo while recording,
  **flip camera while recording (one clip)**, volume keys = shutter, tap focus + drag for brightness, pinch zoom with
  ticks at 1× / 2×, .5/1×/2 pills, double tap = flip, swipe = mode, icons turn with the device, orientation locked and
  screen kept on while recording, shutter sounds (silent with the ringer off unless the law requires them), shutter
  blink, last-photo circle that pops in and opens the gallery, blurred still while switching mode / lens, recording
  stopped + saved when the app goes to the background, camera-busy / error panel with retry, heat warning, mic asked
  only the first time video is chosen. Saved in the "Prism Cam" album (Pictures/ and Movies/).
  Measured on the Huawei tablet: picture 0.70–0.83 s after tapping the icon (the camera hardware needs ~0.45–0.55 s
  after the screen is ready; the camera is now opened from Activity.onCreate, before the UI); photo starts 0.17–0.22 s
  after the press, saved after 0.40–0.52 s; mode switch 0.6–0.7 s, lens flip 0.5 s; 30 fps in normal light.
  Dim room: photo preview drops to 13 fps — a steady 30 fps was tried and made the picture much darker, so photo keeps
  the camera's own range (video stays a steady 30).
- 2026-11-24 — **Phase 2 (v0.2.0)** — user: "start phase 2". **Clean screen** (eye-off icon): everything fades away,
  the shutter stays exactly where it was (hidden controls keep their space), ghosts to 14 % after 3 s without touches
  and at once while the screen is shared (Android 15+ screen-recording callback); back = swipe in from a side edge,
  two-finger hold 0.65 s, or Back; hint toast the first time; recording shows only a red dot on the shutter.
  **Self-timer** 3 / 10 s (yellow badge, big popping countdown, haptic per second, press again = cancel).
  **Photo shape** 4:3 / 16:9 / 1:1 (button in the top bar; 1:1 = centre square, 3120×3120; 16:9 = 3840×2160).
  **Grid + level** (thirds + centre line that turns yellow and ticks when straight). **Hold-to-record**: long-press the
  shutter = record while held (photo mode switches to video for the clip and back after; ~0.7 s to start from photo).
  "…" menu: Camera section with switches (grid + level, always smooth = steady 30 fps photo preview, left-handed
  landscape). Glass look: frosted zoom pills. Settings remembered (`prefs/CamPrefs.kt`).
  Fixes found on the tablet: in 1:1 the SurfaceView picture spilled outside the square box → black masks around the
  box; timer badge clipped by the round icon → no clip, badge inside.
- 2026-11-24 — **Phases 3, 4, 6, 5 → v1.0.0** — user: "do every phase". 
  **Phase 3:** GPU effect pipeline (`effects/GlFx.kt`, CameraX SurfaceProcessor, OpenGL ES 2) on preview + video,
  attached only while something needs it; 11 looks (`effects/Looks.kt`); scene of text (4 styles, 8 colours) /
  24 emoji stickers / 7 frames (`effects/Scene.kt`) drawn into a GPU overlay texture and onto photos; ✦ sheet
  (`ui/FxSheet.kt`) with live look thumbnails; drag / pinch / turn items on the picture, tap again to edit text
  (`ui/TextEditor.kt`); photos with effects go through the CPU path (`effects/PhotoFx.kt`) at full resolution.
  **Phase 4:** on-device MediaPipe (`ai/Vision.kt`, models in `assets/models`): background blur (Kawase on the GPU) /
  replace (5 built-in + own photo), Portrait mode (third mode), 6 face stickers (`effects/FaceStickers.kt`), peace
  sign ✌️ → 3 s countdown photo; photos get the same background + face stickers on the CPU.
  **Phase 6:** "Send to Prism Cam on the PC" (`ui/PcLink.kt`): QR scan (ZXing) or pasted link → the PC's phone page
  runs in a WebView with the camera handed over; private-network hosts only, PC certificate pinned.
  **Phase 5:** GitHub updater (`update/Updater.kt`): quiet check 4 s after start + every 6 h (pink dot on "…"),
  About box shows status + Update now + progress, APK must carry the same signing key, Android's installer finishes.
  Video bitrate pinned to 16 Mbps (with effects CameraX had dropped to ~5.5). APK limited to arm64 + armv7 (43 MB).
  Fixes found on the tablet: GPU window surface EGL_BAD_ALLOC (old session still holding it) → lazy attach with
  retry; MediaPipe broken by R8 (protobuf + Flogger renamed, tasks-core not pulled) → keep rules + explicit
  dependency; segmenter GPU delegate fails to read its own mask → CPU (20 ms at 256 px); separable blur streaked →
  Kawase blur.
