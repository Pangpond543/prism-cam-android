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

## Studio (2.0.0, `studio/*`, `effects/GlFx.kt`, `ui/LayersSheet.kt`, `ui/PropsSheet.kt`, `ui/StudioLayer.kt`)
- Layers icon (top bar / left column, teal when the scene has layers). Every scene = a list of layers drawn on the GPU
  each frame, front = top of the list. Types: camera, picture (JPG / PNG / WebP / HEIC), GIF (animated, Android 9+),
  video (loop / mute / speed), text, sticker, shape, frame, finger drawing, face paint, VTuber character, face / hand
  effects. Scenes: add, copy, delete, switch, slideshow 3 / 5 / 10 s. Undo / redo 60 steps. Auto-saved
  (`files/studio/studio.json`, `.bak`, broken files kept aside).
- On the picture: tap = select (dashed box: ⚙ settings, ✕ delete, corner dot = resize + turn, snaps to right angles),
  drag = move, two fingers = resize + turn, tap a selected text = edit. A full-screen camera is only grabbed when it is
  already selected (so tap-to-focus / swipe still work). Attached layers: drag = offset from the face / hand, pinch =
  size relative to it.
- Settings of a layer: Position (size, rotate, x / y, centre, flip, straighten, opacity, rectangle / circle, corner
  radius, border + colour, shadow, crop 4 sides, blend), Colour (11 filters, brightness, contrast, saturation, hue,
  green screen with green / blue / white / black + tolerance), Face / hand (see below), Motion (12 presets + speed,
  keyframes), Content (text, colours, styles; sticker; shape + gradient; frame + tint; video loop / mute / speed;
  pen colour / width / glow, undo line, clear; camera layouts).
- Camera layouts: fill, fit (rounded, shadow), corner PiP, half, circle bubble (square crop), tilted card.
- Templates: Vlog, Live, Cute, Podcast, VTuber (camera hidden, character on a gradient), Party (stars face paint,
  light trails, hat on every face).
- Photos with layers: the still is drawn through the same compositor at full resolution (same positions, face / hand
  places, video frames). Video: what is on screen is recorded.
- Project file: Layers → Share project = `.prismcam` (zip: studio.json + used media) to any app; Open project adds its
  scenes.

## Face & hands (2.0.0, `ai/Vision.kt`, `studio/Tracking.kt`, `studio/Gestures.kt`, `studio/FaceArt.kt`)
- Trackers run only when something needs them (an attached layer, face paint, character, face / hand effect, a
  command, recording a pose, calibration, "show tracked points"); master switches in ✦ → Tracking.
- Tracking tab: faces / hands seen, AI ms, GPU or CPU per tracker; face / hands on-off; show tracked points (face
  outline + hand skeleton, never in photos); GPU on-off; 1–4 faces; steadiness (One-Euro filter) face / hands; lag
  compensation 0–120 ms (prediction); expression sensitivity; swap eyes; "fit to my face" (3 s resting face).
- Stick any layer to: eyes, left / right eye, nose, mouth, forehead, top of head, chin, cheeks, ears, face centre ·
  palm, fingertips, wrist. Which face (1–4) / hand (any / left / right). Follow rotation, size, head turn. Offset.
  When lost: hide / stay / fade. Show only while: smile, open mouth, raise brows, kiss, eyes closed, wink L / R · hand
  gestures.
- **Between two hands** (attach mode `hands2`): sits in the middle of the same point on both hands (palm, a fingertip,
  wrist), turns with the line between the hands (read left → right so it never flips), width = gap × size ("stretch
  with the gap" on) or its own width; offsets in gap units. Show only while: 🫶 heart hands, 🙏 praying hands, or both
  hands making the same gesture. Angles of every attached layer come from the face's / hand's up direction, so both
  cameras (front is mirrored) agree.
- Face tab: face sticker packs (now layers), 10 face paints (desktop facepaint.js, on the face mesh, far side hidden
  when the head turns), VTuber characters cat / robot / ghost (desktop avatar.js), glowing face lines / eyes.
- Hands tab: hand effects that mix — neon hands, fingertip light trail, energy orb in the palm (open hand = orb, fist =
  burst), air drawing (draws on a drawing layer) · **Two hands**: ⚡ lightning between the index tips, 🔮 energy ball
  between the palms, ready-made layers 💖 (with heart hands), ✨ (when praying), 🎀 text sign, 🌈 rainbow · ✌️ shutter.
- Two-hand poses 🫶 / 🙏 (`Tracking.pair`, desktop rules) count as command triggers too.
- Commands: trigger held 0.5 s, fires once, 1.2 s apart. Triggers: 8 expressions, 7 gestures, hand at mouth /
  forehead / cheek / ear, own poses (record 3 s, kNN k = 5). Actions: photo, start / stop video, next / previous scene,
  next filter, clear drawing, hide / show top layer. Saved in prefs; poses in `files/studio/poses.json`.

## Speed (2.0.0)
- "…" → Speed: fps, GPU ms per frame, canvas size, AI ms, layer count, GPU name. Light mode: canvas ≤ 1280 px and AI
  every 100 ms instead of 66.
