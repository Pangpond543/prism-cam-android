# Prism Cam Android — pitfalls

## Toolchain / build
- **Nothing under AppData.** Claude's shell is inside the MSIX Claude app: `%LOCALAPPDATA%` / `%APPDATA%` writes go to a
  private copy. So the SDK is NOT in the Android default `%LOCALAPPDATA%\Android\Sdk` but in `%USERPROFILE%\PrismAndroid\sdk`.
  `~/.gradle` and `~/.android` are in the user profile (fine).
- **Nothing generated in OneDrive.** The source lives in OneDrive; `build/`, `.gradle/` and `.kotlin/` would sync
  thousands of files. `build.gradle.kts` moves every build dir to `%USERPROFILE%\PrismCamAndroidBuild\build\<module>`;
  `tools/build.sh` passes `--project-cache-dir` and `-Pkotlin.project.persistent.dir`. Always build through
  `tools/build.sh` (or `source tools/env.sh` + the same flags).
- The system `java` on PATH is Java 8 (Oracle JRE). Gradle/AGP need 17+: `tools/env.sh` puts the bundled JDK 21 first.
- AGP 9 has Kotlin built in: do NOT apply `org.jetbrains.kotlin.android`. The Compose compiler plugin version must match
  the Kotlin version on the classpath.
- AndroidX libraries of 2026-11 (core 1.19, Compose UI 1.13, lifecycle 2.11) refuse to build against compileSdk 36:
  `compileSdk = 37` + `compileSdkMinor = 2` (platform `android-37.2` installed). targetSdk stays 36 on purpose.
- Kotlin: a `var x by mutableStateOf(..); private set` plus a `fun setX(..)` clash on the JVM
  (CONFLICTING_JVM_DECLARATIONS). Prefs setters are named `useLang / useDark / useLook`.

## Test device (HUAWEI MatePad Air DBY2-L09, HarmonyOS 4.2 = Android 12 / API 31, 2800×1840 landscape, 360 dpi)
- No Google services on it — never depend on Play services (CameraX, MediaCodec, MediaPipe tasks are fine).
- HarmonyOS NEXT (5+) cannot run APKs at all. Check `hw_sc.build.platform.version` on any new Huawei device.
- First connection showed only MTP + a "HiSuite" CD drive: USB debugging had to be switched on and allowed on the
  tablet. Windows driver for the ADB interface is the generic WinUSB one (already present).
- API 31 → no AGSL: the glass background uses the drifting-blobs fallback (`BlobBackground`).
- Warm `am start --es act …` was not delivered to `onNewIntent` (while the user had the control panel open).
  `tools/shots.sh` therefore force-stops and cold-starts for each act; the activity also uses `addOnNewIntentListener`.
- From Git Bash, paths given to `adb shell` get mangled (`/sdcard` → `C:/Program Files/Git/sdcard`): `export MSYS_NO_PATHCONV=1`.

## Camera (phase 1)
- **Again** the Kotlin JVM clash: `var mode by mutableStateOf(..); private set` + `fun setMode(..)` does not compile.
  Camera methods are `selectMode / zoomTo / exposeTo`. Never name a function `set<Property>` next to a state property.
- `ProcessCameraProvider.awaitInstance` resumed on the main thread only after the first composition (~T+250 ms), so the
  camera opened late. `start()` now waits synchronously (≤ 400 ms) in onCreate — PrismApp already warmed CameraX up.
- The first frame still needs ~0.45–0.55 s after the surface exists (Huawei camera session setup) — that is the floor.
  ZSL off saved maybe ~80 ms there (noisy) but costs ~0.1–0.2 s per shot → ZSL stays on.
- A fixed [30,30] fps in photo mode makes dim rooms much darker (the tablet offers only [13,30] / [30,30]). Photo keeps
  the camera's range; video uses [30,30].
- The system permission dialog is a secure window: `screencap` returns a black image while it is up.
- **Never inject taps (`adb shell input`) while a system dialog can be on screen.** Swiping into video mode raised the
  mic dialog and the next test taps landed on "Only this time" (2026-11-24). It was revoked again with
  `adb shell pm revoke com.prism.cam android.permission.RECORD_AUDIO`. Prefer acts (`video:`, `mode:`) — they don't ask.
- `adb shell uiautomator dump` lists Compose clickables with their bounds — the quickest way to check a button's place.
- Front camera on the tablet has no flash → the flash button is hidden there (not a bug).

## Phase 2
- **PreviewView (PERFORMANCE / SurfaceView) is not clipped to its box** when it crops the stream (FILL_CENTER on a
  1:1 box over a 4:3 stream): the picture spilled sideways over the controls area. Black masks are drawn around the
  picture box. Keep them if the box ever stops matching the stream shape.
- Hiding controls with `AnimatedVisibility` removes their space and moves the shutter. Clean screen uses
  `Modifier.hideWhen` (alpha + swallow touches) so the shutter never moves — use it for anything near the shutter.
- A badge inside a `clip(CircleShape)` icon box gets cut off — don't clip boxes that carry badges.
- `pointerInput(Unit)` keeps the FIRST lambdas it saw: callbacks that change (swipe in clean mode, shutter long-press)
  go through `rememberUpdatedState`.
- Bash heredocs that contain an apostrophe inside a Python/Kotlin block failed in this shell ("unexpected EOF looking
  for matching '") — write the text with the Write tool and run a script file instead.

## Phases 3–6
- **GPU pipeline: create the EGL window surface lazily.** Right after a rebind the preview window is still held by
  the old camera session → `eglCreateWindowSurface` = EGL_BAD_ALLOC (0x3003) on Huawei, every swap fails and the
  preview silently freezes on the old frame. `GlFx.draw()` creates it on the first frame and retries.
- With effects on, Preview + Video come out of CameraX as ONE SurfaceOutput (targets = 3).
- With effects on, CameraX picked ~5.5 Mbps for 1080p video (24 without): `setTargetVideoEncodingBitRate(16 Mbps)`.
- **MediaPipe + R8:** (1) protobuf-lite fields renamed → "Field platform_ for mn1 not found"; (2) Flogger renamed →
  `Graph.<clinit>` "no caller found on the stack"; (3) tasks-vision's POM dependency tasks-core was not pulled →
  NoClassDefFoundError Graph. Keep rules in `proguard-rules.pro` + explicit `tasks-core`. Test AI in a RELEASE build.
- MediaPipe 1.2 image segmenter with the GPU delegate cannot read its own confidence mask ("Expected buffer size 98304
  got 196608") → segmenter on CPU (20 ms at 256 px). Face landmarker on GPU works.
- `ByteBufferImageBuilder` path was rejected; analysis frames go through a reused ARGB_8888 Bitmap.
- Separable gaussian on the tiny analysis texture showed horizontal streaks → Kawase blur (4 diagonal taps × 4 passes).
- `adb shell am start --es act "a b"` splits at the space; emoji in acts may not survive the shell either.
- MediaPipe models must be stored uncompressed (`androidResources.noCompress += tflite, task`).
- APK was 85 MB with x86 / x86_64 AI libraries → `abiFilters arm64-v8a, armeabi-v7a` (43 MB).

## Front camera (1.0.1)
- PreviewView mirrors the front camera AFTER our GPU pipeline. Anything the user places or touches must be in screen
  space; the overlay is flipped in the shader for the front camera, AI landmarks (camera space) are mirrored before they
  reach the scene, the person mask stays in camera space. Video uses MIRROR_MODE_ON_FRONT_ONLY so it matches the screen.

## Studio compositor (2.0.0)
- Effects on at cold start (a saved scene) → the first bind happened with display rotation 0 → scene drawn turned 90°
  on the landscape tablet. Set `cam.setDisplayRotation(display.rotation)` BEFORE `cam.start`.
- With a CameraEffect, rotating the screen afterwards (PreviewView rotation changes, the processed buffer does not)
  left the scene turned 90° even with `refreshGeometry`. Fix: rebind on display rotation while the effect is bound
  (not while recording — orientation is locked then).
- Kotlin `0f...5f` is NOT a range (it is a syntax error) — write `0f..0.5f`.
- Layer textures are premultiplied (Android bitmaps); camera / video are not → `uPremul` per layer.
- `GLES20.glVertexAttribPointer` with fixed locations: bind `aPos/aLocal/aTex/aSrc` to 0–3 before linking, for both
  layer programs, so one vertex buffer works for both.
- AI mask / blur / background textures are in camera space (row 0 = top); sample them with the camera-space varying
  (`vSrc`), not the OES texture coordinates.
- **Front camera = mirrored canvas.** An angle measured along a left-right line (eye to eye, across the hand) flips
  180° through the mirror → stickers upside down. Measure along the UP direction (chin → forehead, wrist → fingers);
  for a two-hand line always read it left → right on screen. Camera space is 0..1 on both axes but 4:3: multiply y by
  `Tracking.aspect` before taking a length or an angle.
- MediaPipe may list the two hands in either order frame to frame: smoothing per list index blends the hands. Match
  each hand to its slot by the nearest palm (`Tracking.feedHands`).
- No real face / hands in front of the tablet: use `fakeface:<tilt>/<s>` and `fakehands:<n>/<gap>/<tilt>/<gesture>/<s>`
  (AI results are ignored meanwhile) and test BOTH cameras + a photo.
- Test acts are sent through `adb shell`: `|` and `;` are eaten by the shell — use `/` inside an act value.
- Test while the room is dark: the picture is black and only layers show — check the raw camera (`studioreset`) before
  hunting a bug. The tablet also locks itself (keyguard) → the camera "cannot open from background".

## Look
- Compose `Modifier.shadow` under a translucent (glass) pane shows through as a pale inner rectangle. Glass panes use
  `outsideShadow` (Paint shadow layer, clipped OUT of the shape); opaque classic panes keep `Modifier.shadow`.

## Signing
- One key for every APK the user installs, test builds included. Installing an APK signed with another key fails
  with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`; the only way out is uninstalling (loses the app's settings, not
  the photos in the gallery). Never generate a second key. Never commit the key or its passwords.
- Key SHA-256: `43:5B:5F:F7:…:5B:78:AF` (CN=Prism Cam, O=543). Check an APK with
  `apksigner verify --print-certs apk/Prism-Cam-<ver>.apk`.
