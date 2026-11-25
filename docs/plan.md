# Prism Cam Android — agreed plan

Agreed with the user 2026-11-24 (three rounds in chat). The user's priority, in their words: speed, smoothness,
"cool", and stability above everything — build time, difficulty and token cost do not matter.

## Discord on Android — the answer
- Android has no virtual-camera API for normal apps (root only). Discord mobile only sees the real camera.
  So "Prism Cam as a camera in Discord" is impossible on the phone itself.
- Workaround A (in the app): **clean screen** + Discord's own *screen share*. Friends see a screen share, not a camera.
- Workaround B (later, phase 6): the phone app sends its picture to Prism Cam on the PC (better than today's
  web page phone camera) → Discord on the PC sees a real camera.
- Core of the app = photo + video, like a high-quality phone camera app.

## Why native (Kotlin + Compose + CameraX + OpenGL + MediaCodec), not Tauri/web
Frames stay on the GPU (camera → GL effects → screen + encoder at once, zero copy), hardware H.264/HEVC MP4,
full-sensor photos, phone GPU/NPU for AI. Web view = slower camera, WebM, hot phone. Cost: everything is written new.

## Look — minimal, premium
- Full-bleed preview edge to edge. Thin white line icons, no text labels.
- Top (portrait): flash, timer, aspect ratio, "…" (everything rarely used lives in "…").
- Bottom (portrait): last photo (circle) · shutter (white ring) · flip camera. Swipe left/right = mode
  (photo / video / portrait). One ✦ button opens the effects sheet (filters, stickers, text, frames).
- Colour: white + greys only; accent only on the selected zoom value and the recording indicator.
- Zoom pills .5 / 1× / 2 above the shutter.

## Landscape (phone + tablet) — user 2026-11-24
- Phone landscape: control column on the right, shutter centre-right under the thumb, small icons on the left, zoom
  pills beside the column.
- Tablet landscape: everything in the right column (flip, mode, shutter, last photo), larger targets, 16:9 default.
- Rotating animates the controls to their new place; the camera is NOT restarted (activity handles config changes).
- Extras: orientation locked while recording (clip never flips), correct EXIF / video rotation, "left-handed" setting
  (column on the left), split screen + foldables (Android 16 forces resizable on large screens anyway).

## Clean screen — user 2026-11-24
- **The shutter stays exactly where it is** (portrait bottom centre / landscape right centre). Everything else fades out.
  (A draggable floating shutter was proposed and rejected.)
- Tap = photo, long press = video (release = stop), volume keys = shutter.
- Shutter fades to almost invisible after 3 s idle; touch brings it back.
- Android 15+: when the screen is being recorded/shared (Discord screen share) the shutter ghosts at once
  (`WindowManager.addScreenRecordingCallback`). Test phone is Android 13–14 → only the 3 s fade there.
- Exit: swipe from an edge or two-finger long press. No exit button on the picture.

## Feel of a good camera app
Haptics on shutter / zoom detents / mode change · shutter squeeze + faint flash + thumbnail flies into the last-photo
circle · icons rotate in place with the phone (portrait UI) · while recording: everything else hides, shutter becomes
a red rounded square, timer pill on top, photo button available during recording · tap to focus + drag for exposure.

## Speed and stability targets (measured on the user's phone)
| What | Target | How |
|---|---|---|
| Open → picture | < 0.5–0.8 s | camera first, effects/AI load after |
| Shutter lag | none | CameraX zero-shutter-lag capture mode |
| Screen | 60 fps (120 on 120 Hz screens) | frames stay on GPU; UI and camera on separate threads |
| Recording | sharp MP4, low battery | MediaCodec H.264 / HEVC |
| Crash mid-recording | clip survives | write in segments / fragmented MP4 |
| Overheating | no freeze | PowerManager thermal status → lower load + tell the user |
| Many brands | no model-specific bugs | CameraX quirks database; Camera2 interop only for fine control |
| Problems | traceable | local log file (never sent anywhere), read over USB |

## Classic vs Prism (glass)
- Default **Classic**: dark translucent flat circles, no blur/refraction/moving background.
- **Prism (glass)**: frosted-glass buttons, Prism gradient rim on the shutter, Prism gradient background on the
  non-camera screens (AGSL shader on Android 13+, static gradient below). Blur behind controls comes from the GL
  pipeline (cheap downsampled copy of the frame), not a live backdrop blur. Effects ease off while recording.
- Switch in "…" → appearance: Light/Dark + Glass/Classic (same menu as the desktop moon button). TH/EN, Info, update
  also live in "…". **Prism theme components** (logo, gradient wordmark, by 543, pills, segmented switch, glass
  panels, chips, toasts, sheets) are built as a reusable Compose kit — user asked for them 2026-11-24.
- Auto quality tier at first start (RAM / SoC) → 720p30 or 1080p30/60.

## Features — keep / later / cut
- Keep (first): front/back, pinch zoom, tap focus, flash/torch, photo, video + sound, colour filters, text / sticker /
  image / frame (finger drag, pinch, two-finger rotate), aspect 9:16 / 1:1 / 16:9 / 4:3, TH/EN, dark/light,
  classic/glass, GitHub update.
- Later: AI background blur/remove, face stickers, face paint, VTuber characters, hand gestures (peace sign = photo).
- Cut: virtual camera, screen capture source, phone-QR source, hand mouse/keyboard control, now-playing lyrics,
  animation mode / timeline / keyframes, areas / slideshows, several scenes, video-file layers, library / templates,
  custom gesture training.
- New for mobile: volume-key shutter, 3/10 s timer, grid, burst, photo while recording, stabilisation, share sheet,
  front + back at once (phones that support concurrent cameras), clean screen.

## Phases
| Phase | Result |
|---|---|
| 0 ✅ | Toolchain, signing key + backup, phone over USB, project skeleton with the Prism component kit |
| 1 ✅ v0.1.0 (2026-11-24) | Minimal camera screen, portrait + landscape (phone + tablet): photo, video, zoom, focus, haptics, rotating icons, save to "Prism Cam" album; measure the targets |
| 2 ✅ v0.2.0 (2026-11-24) | Clean screen (shutter stays put), timer, grid, aspect, volume keys, Prism glass look |
| 3 ✅ (2026-11-24) | ✦ sheet: filters, text, stickers, frames |
| 4 ✅ (2026-11-24) | AI: background blur/remove, portrait mode, face stickers, peace-sign shutter |
| 5 ✅ v1.0.0 (2026-11-24) | v1.0 on GitHub (own repo, e.g. `Pangpond543/prism-cam-android`) + in-app updater (downloads the APK, Android asks to install) |
| 6 ✅ (in 1.0.0) | Send to Prism Cam on the PC |

## Desktop parity plan (proposed 2026-11-24, after 1.0.1)
User: "lots of PC features are missing — face tuning, hand tuning, layers; add files; stick things to hands and faces
like the PC; make it as rich as the PC". Earlier "cut" decisions are reversed except what Android cannot do.

| Phase | What | Notes |
|---|---|---|
| 7 ✅ 2.0.0 Layer engine + Studio | every layer drawn on the GPU each frame (camera, images, GIF, video, text, stickers, shapes, frames); Layers panel (order, show / hide / solo, lock, duplicate, multi-select, align); add files (gallery, Files, paste) + media library; corner handles, crop, snap guides; per-layer look (opacity, radius, border, shadow, blend, filter, chroma key); camera as a layer (fill / fit / PiP / half / circle bubble); undo / redo; scenes; auto-save project (+ .bak) | replaces the single overlay bitmap — needed by everything below |
| 8 ✅ 2.0.0 Face tracking | Tracking panel (on/off, 1–4 faces, GPU/CPU, smoothing, lag compensation, expression sensitivity, face mesh, swap eyes, "fit to my face" 3 s); attach ANY layer to a face point (follow turn / size / head turn, offset, which face, when lost); face paint on the 3D mesh; VTuber avatars (cat / robot / ghost / own PNG); face gestures → commands | port of desktop face.js / facepaint / avatar |
| 9 ✅ 2.0.0 Hand tracking | hand landmarks (2 hands) + smoothing / prediction; attach layers to hand points; neon hands + air drawing; gestures → commands (show / hide layer, next scene / slide, photo, start / stop video, effect); built-in poses + teach your own poses (kNN) + practice; hand-at-face zones (put on / take off) | port of desktop hands / handml / handtrain; no PC control on phone |
| 10 ✅ 2.0.0 (part) Motion + extras | layer animation presets (pop, float, bounce, spin, pulse, shake, slide, typewriter) then keyframe timeline; slideshow areas; templates; lyrics of the song playing on the phone (opt-in); screen capture as a layer; front + back camera at once (supported phones); speed panel | |
| 11 ✅ 2.0.0 (part) PC ⇄ phone | share projects / templates with Prism Cam on the PC; send the finished Prism picture (not only the raw camera) to the PC | native WebRTC |
Still impossible / not useful on a phone: virtual camera, controlling the PC with hands, hotkeys / macros / open programs.

Done in 2.0.0 (2026-11-24): all of 7–9; from 10: motion presets, keyframes, slideshow, templates, speed panel + light
mode; from 11: project file to share between devices. Left for later (ideas.md): screen capture layer, both cameras at
once, lyrics, finished picture live to the PC (needs native WebRTC), desktop-compatible project format, own-PNG
characters, typewriter text.
