# Prism Cam Android — features (behaviour to keep)

## Shell (phase 0)
- Name "Prism Cam" + faint "by 543"; teal Prism logo with camera glyph (swings only in Glass).
- Appearance: Theme Light / Dark, Style **Classic (default)** / Glass, Language ไทย / English — switch at once, no restart,
  remembered (`prism.theme`, `prism.look`, `prism.lang`). Status / navigation bar icons follow the theme.
- Glass background: flowing Prism pastel gradient (AGSL, Android 13+, ~30 fps redraw, dark = deep indigo cross-fade);
  Android 8–12: five pastel light blobs drifting over the base gradient (same colours, same night cross-fade).
- Glass panes: translucent fill + gradient rim + top light line + soft shadow outside only.
- About content: logo, name + version, English purpose sentence, Version / Author / Built with AI model / GitHub ("Link not set yet").
- Haptics: pills + shutter (confirm), segmented / chips / zoom (tick).
- Test hook: `adb shell am start -n com.prism.cam/.MainActivity --es act dark,glass,en` (dark|light|glass|classic|th|en, persists).
  Screenshots of every look: `bash tools/shots.sh <out dir>` (cold start per shot, prints the start time, ends on light,classic,th).

## Camera (phase 1, `camera/PrismCamera.kt`, `ui/CameraScreen.kt`)
- Opens straight into the camera (Photo, back lens). The camera is opened from `MainActivity.onCreate` (CameraX warmed
  up in `PrismApp`); the PreviewView (SurfaceView, PERFORMANCE mode) gets its surface when Compose shows it.
- Picture box = exact output shape (photo 4:3, video 16:9), as large as fits. Portrait: photo under the top bar, video
  centred. Landscape: left column = small icons, right column = flip · mode · shutter · last photo; the picture moves so
  the right column does not cover it when there is room.
- Photo: ImageCapture 4:3 highest resolution, JPEG 95, ZERO_SHUTTER_LAG when supported; flash Off → Auto → On (yellow
  when On; hidden on cameras without flash). Video mode: the same button is the torch.
- Video: Recorder FHD → HD → SD, 16:9, stabilisation when supported, steady [30,30] fps when offered. Persistent
  recording: flipping the lens mid-recording continues the same file. Pause / resume (left), photo while recording
  (right), timer pill. Orientation locked + screen on while recording. App to background → recording stopped + saved.
  Mic asked the first time video mode is chosen; without it a mic-off icon shows (tap = ask again), clips are silent.
- Touch on the picture: tap = focus + metering (yellow ring, auto-cancel 5 s, exposure back to 0); drag up/down near
  the ring = exposure (14 dp per step, sun on a track, tick at 0); pinch = zoom (tick crossing 1× and 2×); swipe ≥ 56 dp
  sideways = mode; double tap (< 320 ms) = flip lens.
- Zoom pills: .5 (only if the camera goes below 1×), 1×, 2 (if max ≥ 2); the selected pill shows the live zoom; tap =
  260 ms smooth zoom. (The Huawei tablet has no ultra-wide → 1× and 2 only.)
- Rotation: preview follows the display; photos / videos follow how the device is held (OrientationEventListener with
  hysteresis); icons and pills turn to stay upright (spring).
- Feedback: haptics, shutter / start / stop sounds, dark blink on the picture, last-photo circle pops in. Mode / lens
  switch shows a blurred still of the last frame until the new picture is live.
- Saved to Pictures/Prism Cam and Movies/Prism Cam (MediaStore; Android 8–9: real path + storage permission). The last
  item is found again at start; tapping the circle opens it in the gallery app.
- Volume up / down / camera key = shutter (no auto-repeat). Heat: thermal status logged, toast at SEVERE.
- Errors: camera in use / disabled / Do Not Disturb / fatal → panel with the reason + "try again".
- "…" menu: theme, style, language (apply at once), About (purpose, version · Android, author, AI model, GitHub "not
  set yet", where photos go). Slides up (portrait) / in from the right (landscape). Back closes.
- Log: `T+<ms> app|activity|provider|bound|view`, `OPEN first frame`, `BIND …`, `BIND live in`, `FPS camera`,
  `FPS ranges`, `SHOT capture started / saved`, `REC start / end`, `TOUCH end …`, `PERM`, `HEAT`, `CAMERA state`, `ERR`.
- Test acts (camera): `photo`, `video:<s>`, `mode:photo|video`, `front`, `back`, `zoom:<r>`, `rec`, `stop`, `pause`,
  `snap`, `menu`, `unmenu`, `kit`, `wait:<ms>`, `zsl:0|1`, `orient:portrait|landscape|free`; phase 2: `clean`,
  `unclean`, `timer:0|3|10`, `aspect:4:3|16:9|1:1`, `grid:0|1`, `smooth:0|1`, `lefty:0|1`, `shutter` (honours the
  timer), `hold:<ms>` (hold-to-record).

## Phase 2 (v0.2.0)
- Top bar / icon column: flash · timer (badge 3 / 10) · photo shape (4:3 → 16:9 → 1:1, photo mode only) · mic-off
  (video without mic) · clean screen (eye-off) · "…".
- Clean screen: every control fades (alpha 0, keeps its space, no touches) except the shutter, which never moves.
  Shutter ghosts to 14 % after 3 s idle and immediately while the window is being recorded / shared (Android 15+,
  `DETECT_SCREEN_RECORDING`); any touch wakes it (a tap on the ghost only wakes it, it does not shoot). Exit: swipe in
  from a side edge (≥ 56 dp from within 28 dp of the edge), two-finger hold 0.65 s (fingers still), or Back. First use
  shows a hint toast once. Recording in clean mode: no timer pill, a small red dot above the shutter. Mode swipes are
  off in clean mode; tap-focus, pinch and double-tap still work.
- Self-timer: shutter / volume key starts the countdown (big number in the picture, haptic each second); a second
  press cancels; changing mode cancels. Stopping a recording never waits.
- Photo shape: 4:3 (default), 16:9 (sensor 16:9 stream), 1:1 (`ImageCapture.setCropAspectRatio(1:1)` on the 4:3 stream).
  The picture box takes the same shape; black masks cover the SurfaceView overflow outside the box.
- Grid + level ("…" → Camera): thirds lines; level line from the gravity sensor (relative to the current screen
  orientation), yellow + haptic when within 1°, hidden when the device lies flat or tilts > 30°.
- Hold-to-record: long-press the shutter (haptic) = record until the finger lifts. In photo mode the app switches to
  video (~0.7 s), records, then returns to photo. Lifting before the video camera is ready records nothing.
- Always smooth ("…" → Camera): photo preview at a steady 30 fps (darker in dim rooms).
- Left-handed ("…" → Camera): in landscape the shutter column is on the left and the icons on the right.
- Remembered: timer, shape, grid, smooth, left-handed, "clean hint shown" (`prefs/CamPrefs.kt`, keys `cam.*`).

## Effects — ✦ sheet (phase 3)
- ✦ icon in the top bar (yellow while anything is on). Tabs: Filters · Text · Stickers · Frames · Background · Face.
  "Clear all" turns everything off.
- GPU pipeline (`GlFx`) is attached only while a look / scene item / AI effect is on (rebind ~0.5 s with the blurred
  still); otherwise the camera runs exactly as in phase 1. Same pipeline feeds preview and video (what you see is
  what is recorded). Never toggled while recording.
- Looks: Natural, Vivid, Warm, Cool, Prism, Film (grain), Faded, Movie (teal-orange), Mono, Noir, Sepia — colour
  matrix + vignette (+ grain); thumbnails made from a still of the live picture.
- Scene items live in U space (0..1 of the picture as seen): tap an item = select (dashed box + red ×), drag = move,
  two fingers = resize + turn, tap a selected text = edit (text, 8 colours, styles Shadow / Outline / Label / Prism).
  Stickers: 24 emoji. Frames: none, Polaroid, Rounded, Film strip, Prism gradient, Date stamp, Cinema 2.39:1.
- Photos with effects: captured to memory, turned to screen space, background (AI) → look → scene + face stickers on
  the full-resolution still, turned to how the device was held, saved as JPEG 95 (front camera saved mirrored, like
  the preview). ~1–1.4 s to save on the tablet.

## AI (phase 4, `ai/Vision.kt`)
- MediaPipe tasks-vision 1.2.3 + tasks-core, models bundled (selfie segmenter 0.25 MB, face landmarker 3.7 MB,
  gesture recognizer 8.4 MB). Runs on the small upright copy the GPU reads back (256 px, ≤ 15 fps), one thread.
- Background: blur (Low / Medium / High, Kawase on the GPU) or replace (Prism, Night, Sunset, Mint, Studio, or a
  picture from the gallery via the photo picker). Mask smoothstep 0.30–0.70. Photos: mask from the still (512 px).
- Portrait = third mode (video · photo · portrait): photo with background blur on.
- Face stickers: Shades, Crown, Kitty, Blush, Hearts (orbiting), Clown nose — up to 3 faces, sized by eye distance,
  turned with the head; photos detect faces again on the still.
- Peace sign ✌️ (switch in the Face tab): 3 frames in a row with "Victory" > 0.65 → 3 s countdown → photo
  (video mode: stop a running clip). 4.5 s cool-down.

## Send to Prism Cam on the PC (phase 6, `ui/PcLink.kt`)
- "…" → Send to Prism Cam on the PC: QR scanner (CameraX + ZXing) or paste the link; "Connect to the same PC" reuses
  the last link. The camera is lent (`PrismCamera.lendCamera`) and taken back on return.
- Accepts only `https://<private IPv4 or .local>:<port>/?k=…`. The PC's self-signed certificate is accepted for that
  host only and pinned (SHA-256) after the first connection. WebView grants camera / mic only to that host.
- Screen stays on while sending; "Stop sending" / Back returns to the camera.

## Updates (phase 5, `update/Updater.kt`)
- Repo `Pangpond543/prism-cam-android`, `releases/latest`, first `.apk` asset. Quiet check 4 s after start and every
  6 h → pink dot on "…". About box: status line (checking / latest / vX available / downloading n% / ready / reason),
  Check for updates · Update now · Install. Downloads to `Android/data/com.prism.cam/files/updates`, refuses an APK
  signed with another key, then Android's installer asks the user (first time: allow installs from Prism Cam).
- Test acts: `look:<id>`, `text:<word>`, `sticker:<emoji>`, `frame:<id>`, `fxsheet[:0]`, `fxclear`, `bg:blur|replace|none`,
  `face:<id>|none`, `peace:0|1`, `portrait`, `pc`, `unpc`, `updcheck`, `vframe` (saves frame 1 s of the last video to
  `files/vframe.png` + logs rotation / bitrate). No spaces in acts (adb splits them).
  `bash tools/camtest.sh <name> "<acts>" [wait s] [out dir]` = cold start + screenshot + the new log lines.
