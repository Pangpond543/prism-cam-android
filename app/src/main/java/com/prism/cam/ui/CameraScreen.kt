package com.prism.cam.ui

import android.app.Activity
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerId
import com.prism.cam.prefs.CamPrefs
import kotlinx.coroutines.withTimeoutOrNull
import android.content.Intent
import android.content.pm.ActivityInfo
import android.view.HapticFeedbackConstants
import android.view.OrientationEventListener
import android.view.Surface
import androidx.camera.view.PreviewView
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.calculateZoom
import androidx.compose.foundation.gestures.calculatePan
import androidx.compose.foundation.gestures.calculateRotation
import androidx.compose.ui.input.pointer.positionChange
import com.prism.cam.studio.Layer
import com.prism.cam.studio.LType
import com.prism.cam.studio.Make
import com.prism.cam.studio.Studio
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.prism.cam.PrismLog
import com.prism.cam.camera.PrismCamera
import com.prism.cam.camera.PrismCamera.Mode
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.CamRoundButton
import com.prism.cam.ui.kit.CamShutter
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.prismPane
import com.prism.cam.ui.kit.rememberHaptic
import com.prism.cam.ui.theme.PrismFont
import com.prism.cam.ui.theme.PrismThemeAccess
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/** Sizes: phones vs tablets (smallest width ≥ 600 dp). */
private class Sz(val shutter: Dp, val round: Dp, val icon: Dp, val bar: Dp, val side: Dp, val column: Dp, val thumb: Dp)
private val PHONE = Sz(shutter = 74.dp, round = 46.dp, icon = 24.dp, bar = 56.dp, side = 64.dp, column = 120.dp, thumb = 46.dp)
private val TABLET = Sz(shutter = 84.dp, round = 52.dp, icon = 26.dp, bar = 64.dp, side = 76.dp, column = 140.dp, thumb = 52.dp)

private fun degOf(r: Int) = when (r) { Surface.ROTATION_90 -> 270; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 90; else -> 0 }
private fun norm180(a: Float): Float { var x = a % 360f; if (x > 180f) x -= 360f; if (x <= -180f) x += 360f; return x }

/**
 * The camera screen (docs/plan.md "Look", "Landscape"): the picture is shown at exactly the shape that will be saved
 * (photo 4:3, video 16:9), controls float on top. Portrait: top bar + bottom controls. Landscape (phone and tablet):
 * small icons in a left column, flip / mode / shutter / last photo in a right column under the thumb.
 */
@Composable
fun CameraScreen(
    cam: PrismCamera,
    onMenu: () -> Unit,
    onVideoMode: () -> Unit,
    /** ✦ sheet */
    onFx: () -> Unit = {},
    /** tap on an already-selected text = edit it */
    onEditText: (Layer) -> Unit = {},
    /** layers panel */
    onLayers: () -> Unit = {},
    /** settings of one layer (⚙ on the selection box) */
    onLayerProps: (Layer) -> Unit = {},
    /** orientation when not recording (test hook `orient:` can force one; normally follows the device) */
    freeOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,
    /** test hook: >0 = enter clean screen, <0 = leave (a new value each time) */
    cleanRequest: Int? = null,
) {
    val ctx = LocalContext.current
    val activity = ctx as Activity
    val owner = LocalLifecycleOwner.current
    val view = LocalView.current
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()

    // ── full screen while the camera is shown (bars come back with a swipe)
    DisposableEffect(Unit) {
        val ctl = WindowCompat.getInsetsController(activity.window, activity.window.decorView)
        ctl.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        ctl.hide(WindowInsetsCompat.Type.systemBars())
        onDispose { ctl.show(WindowInsetsCompat.Type.systemBars()) }
    }

    // ── the PreviewView (SurfaceView inside = lowest latency) and the camera binding
    val pv = remember {
        PreviewView(ctx).apply {
            implementationMode = PreviewView.ImplementationMode.PERFORMANCE
            scaleType = PreviewView.ScaleType.FILL_CENTER
        }
    }
    LaunchedEffect(Unit) { cam.start(owner); cam.attachView(pv) }
    DisposableEffect(owner) {
        val obs = LifecycleEventObserver { _, e -> if (e == Lifecycle.Event.ON_STOP) cam.onStop() else if (e == Lifecycle.Event.ON_START) cam.refreshLatest() }
        owner.lifecycle.addObserver(obs)
        onDispose { owner.lifecycle.removeObserver(obs) }
    }

    // ── rotation: preview follows the screen, captures follow how the device is held, icons stay upright
    val conf = LocalConfiguration.current
    val dispRot = remember(conf) { view.display?.rotation ?: Surface.ROTATION_0 }
    var devRot by remember { mutableIntStateOf(dispRot) }
    LaunchedEffect(dispRot) { cam.setDisplayRotation(dispRot) }
    DisposableEffect(Unit) {
        cam.setDeviceRotation(devRot)
        val l = object : OrientationEventListener(ctx) {
            override fun onOrientationChanged(o: Int) {
                val r = PrismCamera.rotationFor(o, devRot)
                if (r != devRot) { devRot = r; cam.setDeviceRotation(r) }
            }
        }
        if (l.canDetectOrientation()) l.enable()
        onDispose { l.disable() }
    }
    val iconAngle = remember { Animatable(0f) }
    LaunchedEffect(dispRot, devRot) {
        val target = norm180((degOf(dispRot) - degOf(devRot)).toFloat())
        val delta = norm180(target - iconAngle.value)
        iconAngle.animateTo(iconAngle.value + delta, spring(dampingRatio = .8f, stiffness = 260f))
    }
    val rot = iconAngle.value

    // ── while recording: keep the screen on, keep the orientation (the clip never flips)
    LaunchedEffect(cam.recording, freeOrientation) {
        view.keepScreenOn = cam.recording
        activity.requestedOrientation = if (cam.recording) ActivityInfo.SCREEN_ORIENTATION_LOCKED else freeOrientation
    }

    fun changeMode(m: Mode) {
        if (m == cam.mode || cam.recording) return
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        cam.selectMode(m)
        if (m == Mode.Video) onVideoMode()
    }

    // ── clean screen (docs/plan.md): only the shutter stays, in exactly the same place; it fades to a ghost after
    //    3 s without touches, and at once while the screen is being recorded / shared (Android 15+).
    var clean by rememberSaveable { mutableStateOf(false) }
    var lastTouch by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idle by remember { mutableStateOf(false) }
    var screenShared by remember { mutableStateOf(false) }
    LaunchedEffect(clean, lastTouch) { idle = false; if (clean) { delay(3000); idle = true } }
    BackHandler(clean) { clean = false }
    if (Build.VERSION.SDK_INT >= 35) ScreenShareWatch { screenShared = it }
    val ghost = clean && (idle || screenShared) && !cam.recording
    val shutterAlpha by animateFloatAsState(if (ghost) .14f else 1f, tween(if (ghost) 900 else 160), label = "ghost")
    val chromeAlpha by animateFloatAsState(if (clean) 0f else 1f, tween(260), label = "chrome")
    fun enterClean() {
        clean = true
        lastTouch = System.currentTimeMillis()
        haptic(HapticFeedbackConstants.CLOCK_TICK)
        if (!CamPrefs.cleanHintShown) {
            CamPrefs.markCleanHint()
            cam.onMessage(T("จอสะอาด: แตะสองนิ้วค้าง หรือปัดจากขอบจอ เพื่อกลับ"), true)
        }
    }
    cleanRequest?.let { req -> LaunchedEffect(req) { if (req > 0) enterClean() else clean = false } }

    // ── grid + level (sensor only while the grid is on)
    val tilt = if (CamPrefs.grid) rememberTilt(dispRot) else null

    // ── countdown haptics
    LaunchedEffect(cam.countdown) { if (cam.countdown > 0) haptic(HapticFeedbackConstants.CLOCK_TICK) }

    BoxWithConstraints(
        Modifier.fillMaxSize().background(Color.Black).pointerInput(clean) {
            // sees every touch first (Initial pass, consumes nothing): wakes the ghost shutter, and in clean mode
            // a swipe in from a side edge or a two-finger hold (0.65 s) brings the controls back
            val edge = 28.dp.toPx()
            val swipe = 56.dp.toPx()
            val still = 24.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                lastTouch = System.currentTimeMillis()
                if (clean) {
                    val fromEdge = down.position.x < edge || down.position.x > size.width - edge
                    var starts: Map<PointerId, Offset> = emptyMap()
                    var done = false
                    while (!done) {
                        val ev = if (starts.size >= 2) withTimeoutOrNull(650) { awaitPointerEvent(PointerEventPass.Initial) }
                        else awaitPointerEvent(PointerEventPass.Initial)
                        if (ev == null) { clean = false; haptic(HapticFeedbackConstants.LONG_PRESS); break }
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.isEmpty()) break
                        if (fromEdge && pressed.size == 1 && abs(pressed[0].position.x - down.position.x) > swipe) {
                            clean = false; haptic(HapticFeedbackConstants.CLOCK_TICK); done = true
                        }
                        if (pressed.size >= 2) {
                            if (starts.size < 2) starts = pressed.associate { it.id to it.position }
                            else if (pressed.any { p -> starts[p.id]?.let { (p.position - it).getDistance() > still } != false }) starts = emptyMap()
                        } else starts = emptyMap()
                    }
                }
            }
        },
    ) {
        val w = maxWidth
        val h = maxHeight
        val land = w > h
        val s = if (minOf(w, h) >= 600.dp) TABLET else PHONE
        val safe = WindowInsets.safeDrawing.asPaddingValues()
        val lefty = land && CamPrefs.lefty
        val ratio = if (cam.mode == Mode.Video) 16f / 9f else CamPrefs.aspect.long

        // ── where the picture goes: exact output shape, as large as fits
        val bw: Dp; val bh: Dp; val bx: Dp; val by: Dp
        if (!land) {
            var pw = w; var ph = w * ratio
            if (ph > h) { ph = h; pw = h / ratio }
            val top = s.bar + safe.calculateTopPadding()
            bw = pw; bh = ph; bx = (w - pw) / 2
            by = if (cam.mode != Mode.Video && ph + top <= h) top else (h - ph) / 2
        } else {
            var ph = h; var pw = h * ratio
            if (pw > w) { pw = w; ph = w / ratio }
            bw = pw; bh = ph; by = (h - ph) / 2
            // keep the shutter column off the picture when there is room; the small icons may overlap instead
            val x = if (pw + s.side + s.column <= w) s.side else maxOf(0.dp, minOf((w - pw) / 2, w - s.column - pw))
            bx = if (lefty) w - pw - x else x
        }

        // ───────── picture
        Box(Modifier.offset(bx, by).size(bw, bh)) {
            AndroidView({ pv }, Modifier.fillMaxSize())
            if (CamPrefs.grid) GridOverlay(tilt)
            FreezeOverlay(cam.freeze)
            BlinkOverlay(cam.shots)
            FocusLayer(cam, rot, onEditText = onEditText, onDoubleTap = {
                if (cam.hasFront) { haptic(HapticFeedbackConstants.VIRTUAL_KEY); cam.switchLens() }
            }, onSwipe = { dir ->
                // strip order: [video, photo] — swipe right shows the mode on the left (not in clean mode)
                if (!clean) {
                    val order = listOf(Mode.Video, Mode.Photo, Mode.Portrait)
                    val i = (order.indexOf(cam.mode) - dir).coerceIn(0, order.size - 1)
                    changeMode(order[i])
                }
            })
            if (!clean) SelectionBox(cam.fx, bw, bh, onLayerProps)
            if (!clean) Studio.selected()?.takeIf { it.type == LType.Drawing }?.let { d -> DrawingBar(d) { onLayerProps(d) } }
            Countdown(cam.countdown, rot)
        }
        // The SurfaceView inside PreviewView is NOT clipped to its box when the picture is cropped (1:1 shows the
        // centre of a 4:3 stream): black masks outside the box hide the overflow (seen on the Huawei tablet).
        if (bx > 0.dp) Box(Modifier.offset(0.dp, 0.dp).size(bx, h).background(Color.Black))
        if (bx + bw < w) Box(Modifier.offset(bx + bw, 0.dp).size(w - bx - bw, h).background(Color.Black))
        if (by > 0.dp) Box(Modifier.offset(0.dp, 0.dp).size(w, by).background(Color.Black))
        if (by + bh < h) Box(Modifier.offset(0.dp, by + bh).size(w, h - by - bh).background(Color.Black))

        // soft scrims so white icons stay readable on bright pictures
        Box(Modifier.fillMaxSize().alpha(chromeAlpha)) {
            if (!land) {
                Box(Modifier.fillMaxWidth().height(s.bar + safe.calculateTopPadding() + 30.dp).background(Brush.verticalGradient(listOf(Color(0x66000000), Color.Transparent))))
                Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth().height(250.dp).background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x80000000)))))
            } else {
                val shutterSide = if (lefty) Alignment.CenterStart else Alignment.CenterEnd
                val iconSide = if (lefty) Alignment.CenterEnd else Alignment.CenterStart
                Box(Modifier.align(shutterSide).fillMaxHeight().width(s.column + 70.dp).background(Brush.horizontalGradient(if (lefty) listOf(Color(0x80000000), Color.Transparent) else listOf(Color.Transparent, Color(0x80000000)))))
                Box(Modifier.align(iconSide).fillMaxHeight().width(s.side + 30.dp).background(Brush.horizontalGradient(if (lefty) listOf(Color.Transparent, Color(0x59000000)) else listOf(Color(0x59000000), Color.Transparent))))
            }
        }

        val rec = cam.recording
        val flashIcon = when {
            cam.mode == Mode.Video -> if (cam.torch) "bolt" else "boltoff"
            cam.flash == PrismCamera.Flash.Auto -> "boltauto"
            cam.flash == PrismCamera.Flash.On -> "bolt"
            else -> "boltoff"
        }
        val flashTint = if ((cam.mode == Mode.Video && cam.torch) || (cam.mode != Mode.Video && cam.flash == PrismCamera.Flash.On)) Color(0xFFFFD98A) else Color.White
        val topIcons: @Composable () -> Unit = {
            if (cam.hasFlash) TopIcon(flashIcon, s, rot, flashTint) { haptic(HapticFeedbackConstants.CLOCK_TICK); cam.cycleFlash() }
            else Spacer(Modifier.size(s.round))
            TimerIcon(s, rot) { haptic(HapticFeedbackConstants.CLOCK_TICK); CamPrefs.cycleTimer() }
            if (cam.mode != Mode.Video) AspectIcon(s, rot) {
                haptic(HapticFeedbackConstants.CLOCK_TICK); CamPrefs.cycleAspect(); cam.applyShape()
            }
            if (cam.mode == Mode.Video && !cam.audioOk) TopIcon("micoff", s, rot, Color(0xCCFFFFFF)) { onVideoMode() }
            TopIcon("sparkle", s, rot, if (cam.look.id != "none" || !Make.isEmpty() || cam.fxReasons.isNotEmpty()) Color(0xFFFFD98A) else Color.White) { onFx() }
            TopIcon("layers", s, rot, if (Studio.layers.size > 1) Color(0xFF86E8D6) else Color.White) { onLayers() }
            TopIcon("eyeoff", s, rot) { enterClean() }
            Box {
                TopIcon("dots", s, rot) { onMenu() }
                // update nudge (suite): pink dot when a newer version is on GitHub
                if (com.prism.cam.update.Updater.nudge != null) Box(Modifier.align(Alignment.TopEnd).offset((-6).dp, 8.dp).size(9.dp).clip(CircleShape).background(com.prism.cam.ui.theme.Prism.accent2))
            }
        }
        val chromeOff = clean || rec
        val chromeMod = Modifier.hideWhen(chromeOff)
        val shutter: @Composable () -> Unit = {
            Box(Modifier.alpha(shutterAlpha), contentAlignment = Alignment.Center) {
                CamShutter(
                    { if (ghost) lastTouch = System.currentTimeMillis() else cam.shutter() },
                    size = s.shutter, recording = rec, video = cam.mode == Mode.Video,
                    onLongPress = { cam.holdStart() }, onRelease = { cam.holdEnd() },
                )
                if (clean && rec) Box(Modifier.align(Alignment.TopCenter).offset(y = (-14).dp).size(8.dp).clip(CircleShape).background(Color(0xFFE24B4A)))
            }
        }

        // ───────── recording timer (hidden in clean mode — a small red dot sits on the shutter instead)
        AnimatedVisibility(rec && !clean, Modifier.align(Alignment.TopCenter).padding(top = safe.calculateTopPadding() + 12.dp), enter = fadeIn(), exit = fadeOut()) {
            RecTimer(cam.recSeconds, cam.paused)
        }

        if (!land) {
            // ───────── portrait: top bar
            Row(
                Modifier.fillMaxWidth().padding(top = safe.calculateTopPadding()).height(s.bar).padding(horizontal = 14.dp).then(chromeMod),
                horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically,
            ) { topIcons() }
            // ───────── portrait: bottom controls (hidden pieces keep their space, so the shutter never moves)
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(bottom = safe.calculateBottomPadding() + 26.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box(Modifier.hideWhen(clean)) { ZoomPills(cam, rot, vertical = false) }
                Spacer(Modifier.height(14.dp))
                Box(Modifier.hideWhen(chromeOff)) { ModeStrip(cam.mode, vertical = false, onPick = ::changeMode) }
                Spacer(Modifier.height(16.dp))
                Row(Modifier.fillMaxWidth().padding(horizontal = 30.dp), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.hideWhen(clean)) { LeftSlot(cam, s, rot) }
                    shutter()
                    Box(Modifier.hideWhen(clean)) { RightSlot(cam, s, rot) }
                }
            }
        } else {
            val iconSide = if (lefty) Alignment.CenterEnd else Alignment.CenterStart
            val shutterSide = if (lefty) Alignment.CenterStart else Alignment.CenterEnd
            val ltr = androidx.compose.ui.unit.LayoutDirection.Ltr
            // ───────── landscape: small-icon column
            Column(
                Modifier.align(iconSide).fillMaxHeight()
                    .padding(start = safe.calculateLeftPadding(ltr), end = safe.calculateRightPadding(ltr))
                    .width(s.side).padding(vertical = 22.dp).then(chromeMod),
                verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally,
            ) { topIcons() }
            // ───────── landscape: shutter column (flip · mode · shutter · last photo)
            Row(
                Modifier.align(shutterSide).fillMaxHeight()
                    .padding(start = safe.calculateLeftPadding(ltr), end = safe.calculateRightPadding(ltr)),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val pills: @Composable () -> Unit = { Box(Modifier.hideWhen(clean)) { ZoomPills(cam, rot, vertical = true) } }
                if (!lefty) { pills(); Spacer(Modifier.width(10.dp)) }
                Column(
                    Modifier.fillMaxHeight().width(s.column).padding(vertical = 26.dp),
                    verticalArrangement = Arrangement.SpaceBetween, horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Box(Modifier.hideWhen(clean)) { RightSlot(cam, s, rot) }
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.hideWhen(chromeOff)) { ModeStrip(cam.mode, vertical = true, onPick = ::changeMode) }
                        Spacer(Modifier.height(18.dp))
                        shutter()
                        // an invisible copy of the mode strip below keeps the shutter centred on the screen
                        Spacer(Modifier.height(18.dp))
                        Box(Modifier.alpha(0f)) { ModeStrip(cam.mode, vertical = true, onPick = {}) }
                    }
                    Box(Modifier.hideWhen(clean)) { LeftSlot(cam, s, rot) }
                }
                if (lefty) { Spacer(Modifier.width(10.dp)); pills() }
            }
        }

        // ───────── camera problem
        cam.error?.let { msg ->
            Column(
                Modifier.align(Alignment.Center).padding(24.dp)
                    .prismPane(PrismThemeAccess.colors, RoundedCornerShape(24.dp), strong = true).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                PrismIcon("camera", size = 30.dp, tint = PrismThemeAccess.colors.ink2)
                PrismText(msg, style = PrismThemeAccess.type.body)
                PrismPill(T("ลองเปิดกล้องอีกครั้ง"), { cam.retry() }, kind = PillKind.Primary, icon = "refresh")
            }
        }
    }
}

/** Fades a control out but keeps its space (so nothing else moves) and stops it from taking touches. */
@Composable
private fun Modifier.hideWhen(hidden: Boolean): Modifier {
    val a by animateFloatAsState(if (hidden) 0f else 1f, tween(240), label = "hide")
    return this.alpha(a).then(
        if (hidden) Modifier.pointerInput(Unit) {
            awaitPointerEventScope { while (true) awaitPointerEvent(PointerEventPass.Initial).changes.forEach { it.consume() } }
        } else Modifier,
    )
}

/** Android 15+: tells us when this window is being recorded / shared (Discord screen share). */
@androidx.annotation.RequiresApi(35)
@Composable
private fun ScreenShareWatch(onChange: (Boolean) -> Unit) {
    val ctx = LocalContext.current
    val cb by androidx.compose.runtime.rememberUpdatedState(onChange)
    DisposableEffect(Unit) {
        val wm = (ctx as Activity).windowManager
        val listener = java.util.function.Consumer<Int> { st -> cb(st == android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE) }
        val first = try { wm.addScreenRecordingCallback(ctx.mainExecutor, listener) } catch (t: Throwable) { PrismLog.i("ERR screen-rec watch ${t.message}"); -1 }
        cb(first == android.view.WindowManager.SCREEN_RECORDING_STATE_VISIBLE)
        onDispose { try { wm.removeScreenRecordingCallback(listener) } catch (_: Throwable) { } }
    }
}

@Composable
private fun TimerIcon(s: Sz, rot: Float, onClick: () -> Unit) {
    val t = CamPrefs.timer
    Box(
        Modifier.size(s.round).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).rotate(rot),
        contentAlignment = Alignment.Center,
    ) {
        val tint = if (t > 0) Color(0xFFFFD98A) else Color.White
        PrismIcon("clock", Modifier.offset(0.dp, 1.dp).alpha(.35f), size = s.icon, tint = Color.Black, stroke = 1.9f)
        PrismIcon("clock", size = s.icon, tint = tint, stroke = 1.6f)
        if (t > 0) Box(Modifier.align(Alignment.BottomEnd).offset((-3).dp, (-5).dp).clip(RoundedCornerShape(6.dp)).background(Color(0xFFFFD98A)).padding(horizontal = 4.dp)) {
            PrismText("$t", style = TextStyle(fontFamily = PrismFont, fontSize = 10.sp, fontWeight = FontWeight.Bold), color = Color(0xFF3A2A05))
        }
    }
}

@Composable
private fun AspectIcon(s: Sz, rot: Float, onClick: () -> Unit) {
    Box(
        Modifier.size(s.round).clip(CircleShape).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).rotate(rot),
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.border(1.4.dp, Color(0xE6FFFFFF), RoundedCornerShape(5.dp)).padding(horizontal = 5.dp, vertical = 1.dp)) {
            PrismText(CamPrefs.aspect.label, style = TextStyle(fontFamily = PrismFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold), color = Color.White)
        }
    }
}

/** Self-timer: big number in the middle of the picture, popping every second. */
@Composable
private fun Countdown(n: Int, rot: Float) {
    if (n <= 0) return
    val pop = remember(n) { Animatable(1.6f) }
    LaunchedEffect(n) { pop.animateTo(1f, spring(dampingRatio = .5f, stiffness = 300f)) }
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        PrismText(
            "$n", Modifier.scale(pop.value).rotate(rot).alpha(.92f),
            style = TextStyle(fontFamily = PrismFont, fontSize = 120.sp, fontWeight = FontWeight.Light, shadow = androidx.compose.ui.graphics.Shadow(Color(0x88000000), Offset(0f, 4f), 18f)),
            color = Color.White,
        )
    }
}

/** Thirds grid + a level line in the middle (yellow and still when the camera is straight). */
@Composable
private fun GridOverlay(tilt: Float?) {
    val haptic = rememberHaptic()
    val level = tilt != null && abs(tilt) < 1f
    LaunchedEffect(level) { if (level) haptic(HapticFeedbackConstants.CLOCK_TICK) }
    androidx.compose.foundation.Canvas(Modifier.fillMaxSize()) {
        val c = Color(0x59FFFFFF)
        val sw = 1.dp.toPx()
        for (i in 1..2) {
            drawLine(c, Offset(size.width * i / 3f, 0f), Offset(size.width * i / 3f, size.height), sw)
            drawLine(c, Offset(0f, size.height * i / 3f), Offset(size.width, size.height * i / 3f), sw)
        }
        if (tilt != null) {
            val half = 44.dp.toPx()
            val col = if (level) Color(0xFFFFD98A) else Color(0xCCFFFFFF)
            val a = Math.toRadians((if (level) 0f else tilt).toDouble())
            val dx = (kotlin.math.cos(a) * half).toFloat(); val dy = (kotlin.math.sin(a) * half).toFloat()
            val cx = size.width / 2; val cy = size.height / 2
            drawLine(col, Offset(cx - dx, cy - dy), Offset(cx + dx, cy + dy), 1.6.dp.toPx())
            // fixed short marks either side show where "straight" is
            drawLine(Color(0x80FFFFFF), Offset(cx - half - 18.dp.toPx(), cy), Offset(cx - half - 6.dp.toPx(), cy), 1.6.dp.toPx())
            drawLine(Color(0x80FFFFFF), Offset(cx + half + 6.dp.toPx(), cy), Offset(cx + half + 18.dp.toPx(), cy), 1.6.dp.toPx())
        }
    }
}

/** Roll of the device relative to the current screen orientation, in degrees (null when lying flat). */
@Composable
private fun rememberTilt(dispRot: Int): Float? {
    val ctx = LocalContext.current
    var tilt by remember { mutableStateOf<Float?>(null) }
    val rotNow by androidx.compose.runtime.rememberUpdatedState(dispRot)
    DisposableEffect(Unit) {
        val sm = ctx.getSystemService(android.content.Context.SENSOR_SERVICE) as android.hardware.SensorManager
        val sensor = sm.getDefaultSensor(android.hardware.Sensor.TYPE_GRAVITY) ?: sm.getDefaultSensor(android.hardware.Sensor.TYPE_ACCELEROMETER)
        var smooth = 0f
        val l = object : android.hardware.SensorEventListener {
            override fun onSensorChanged(e: android.hardware.SensorEvent) {
                val x = e.values[0]; val y = e.values[1]; val z = e.values[2]
                val g = kotlin.math.sqrt(x * x + y * y + z * z).coerceAtLeast(.1f)
                if (abs(z) / g > .85f) { tilt = null; return }
                val (gx, gy) = when (rotNow) {
                    Surface.ROTATION_90 -> -y to x
                    Surface.ROTATION_180 -> -x to -y
                    Surface.ROTATION_270 -> y to -x
                    else -> x to y
                }
                val a = Math.toDegrees(kotlin.math.atan2(gx.toDouble(), gy.toDouble())).toFloat()
                val t = a - kotlin.math.round(a / 90f) * 90f
                smooth += (t - smooth) * .25f
                tilt = if (abs(smooth) > 30f) null else smooth
            }
            override fun onAccuracyChanged(s: android.hardware.Sensor?, a: Int) {}
        }
        if (sensor != null) sm.registerListener(l, sensor, android.hardware.SensorManager.SENSOR_DELAY_UI)
        onDispose { sm.unregisterListener(l) }
    }
    return tilt
}

// ───────────────────────────── pieces ─────────────────────────────

@Composable
private fun TopIcon(name: String, s: Sz, rot: Float, tint: Color = Color.White, onClick: () -> Unit) {
    Box(
        Modifier.size(s.round).clip(CircleShape).clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).rotate(rot),
        contentAlignment = Alignment.Center,
    ) {
        // faint shadow copy keeps the thin white line readable on white walls
        PrismIcon(name, Modifier.offset(0.dp, 1.dp).alpha(.35f), size = s.icon, tint = Color.Black, stroke = 1.9f)
        PrismIcon(name, size = s.icon, tint = tint, stroke = 1.6f)
    }
}

/** Last photo (idle) or pause / resume (recording). */
@Composable
private fun LeftSlot(cam: PrismCamera, s: Sz, rot: Float) {
    val ctx = LocalContext.current
    if (cam.recording) {
        CamRoundButton(if (cam.paused) "play" else "pause", { cam.togglePause() }, size = s.round, modifier = Modifier.rotate(rot))
        return
    }
    val thumb = cam.lastThumb
    val pop = remember { Animatable(1f) }
    LaunchedEffect(thumb) { if (thumb != null) { pop.snapTo(.6f); pop.animateTo(1f, spring(dampingRatio = .45f, stiffness = 420f)) } }
    Box(
        Modifier.size(s.thumb).scale(pop.value).rotate(rot).clip(CircleShape).background(Color(0xFF2A2D31))
            .border(1.5.dp, Color(0xCCFFFFFF), CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) {
                val item = cam.lastItem ?: return@clickable
                try {
                    ctx.startActivity(
                        Intent(Intent.ACTION_VIEW).setDataAndType(item.uri, if (item.video) "video/*" else "image/*")
                            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK),
                    )
                } catch (t: Throwable) { PrismLog.i("ERR open gallery ${t.message}") }
            },
    ) {
        if (thumb != null) Image(thumb, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
    }
}

/** Flip camera (idle) or photo-while-recording (recording). */
@Composable
private fun RightSlot(cam: PrismCamera, s: Sz, rot: Float) {
    if (cam.recording) {
        CamRoundButton("shutter", { cam.takePhoto() }, size = s.round, modifier = Modifier.rotate(rot))
        return
    }
    if (cam.hasFront) {
        var spin by remember { mutableIntStateOf(0) }
        val a by animateFloatAsState(spin * 180f, tween(420), label = "flip")
        CamRoundButton("camswitch", { spin++; cam.switchLens() }, size = s.round, modifier = Modifier.rotate(rot + a))
    } else Spacer(Modifier.size(s.round))
}

@Composable
private fun ModeStrip(mode: Mode, vertical: Boolean, onPick: (Mode) -> Unit) {
    val items = listOf(Mode.Video to T("วิดีโอ"), Mode.Photo to T("รูป"), Mode.Portrait to T("ภาพบุคคล"))
    val content: @Composable () -> Unit = {
        for ((m, label) in items) {
            val on = m == mode
            Box(
                Modifier.clip(RoundedCornerShape(12.dp)).clickable(remember { MutableInteractionSource() }, indication = null) { onPick(m) }
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                PrismText(
                    label,
                    style = TextStyle(fontFamily = PrismFont, fontSize = 13.5.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal, letterSpacing = .2.sp),
                    color = if (on) Color(0xFFFFD98A) else Color(0xB3FFFFFF),
                )
            }
        }
    }
    if (vertical) Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) { content() }
    else Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) { content() }
}

@Composable
private fun RecTimer(secs: Long, paused: Boolean) {
    var blink by remember { mutableStateOf(true) }
    LaunchedEffect(paused) { while (true) { delay(600); blink = !blink } }
    Row(
        Modifier.clip(RoundedCornerShape(8.dp)).background(if (paused) Color(0x99000000) else Color(0xFFD93A3A)).padding(horizontal = 10.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (paused) PrismIcon("pause", size = 13.dp, tint = Color.White)
        else Box(Modifier.size(7.dp).alpha(if (blink) 1f else .35f).clip(CircleShape).background(Color.White))
        PrismText(
            "%02d:%02d".format(secs / 60, secs % 60).let { if (secs >= 3600) "${secs / 3600}:$it" else it },
            style = TextStyle(fontFamily = PrismFont, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFeatureSettings = "tnum"),
            color = Color.White,
        )
    }
}

/** .5 / 1× / 2 — selected pill shows the live zoom while pinching ("1.4×"). Tap = smooth zoom. */
@Composable
private fun ZoomPills(cam: PrismCamera, rot: Float, vertical: Boolean) {
    val glass = PrismThemeAccess.colors.isGlass
    val haptic = rememberHaptic()
    val scope = rememberCoroutineScope()
    var anim by remember { mutableStateOf<Job?>(null) }
    val values = buildList {
        if (cam.zoomMin < .95f) add(cam.zoomMin)
        add(1f)
        if (cam.zoomMax >= 2f) add(2f)
    }
    if (values.size < 2 && cam.zoomMax < 1.5f) return
    val z = cam.zoom
    val sel = values.indices.minByOrNull { abs(ln(values[it]) - ln(z)) } ?: 0
    val content: @Composable () -> Unit = {
        values.forEachIndexed { i, v ->
            val on = i == sel
            val exact = abs(z - v) < .05f
            val label = if (on) {
                val shown = if (exact) v else z
                (if (shown < 1f) ".${(shown * 10).roundToInt()}" else if (abs(shown - shown.roundToInt()) < .05f) "${shown.roundToInt()}" else "%.1f".format(shown)) + "×"
            } else if (v < 1f) ".${(v * 10).roundToInt()}" else "${v.roundToInt()}"
            Box(
                Modifier.size(if (on) 40.dp else 34.dp).rotate(rot).clip(CircleShape)
                    .background(if (glass) Color(if (on) 0x47FFFFFF else 0x29FFFFFF) else Color(if (on) 0x8C000000 else 0x59000000))
                    .then(if (glass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color(0xB3FFFFFF), Color(0x1AFFFFFF), Color(0x66FFFFFF))), CircleShape) else Modifier)
                    .clickable(remember { MutableInteractionSource() }, indication = null) {
                        haptic(HapticFeedbackConstants.CLOCK_TICK)
                        anim?.cancel()
                        anim = scope.launch {
                            val a = Animatable(cam.zoom)
                            a.animateTo(v, tween(260)) { cam.zoomTo(value) }
                        }
                    },
                contentAlignment = Alignment.Center,
            ) {
                PrismText(label, style = TextStyle(fontFamily = PrismFont, fontWeight = FontWeight.SemiBold, fontSize = if (on) 12.5.sp else 11.5.sp), color = if (on) Color(0xFFFFD98A) else Color.White)
            }
        }
    }
    if (vertical) Column(verticalArrangement = Arrangement.spacedBy(8.dp), horizontalAlignment = Alignment.CenterHorizontally) { content() }
    else Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) { content() }
}

@Composable
private fun FreezeOverlay(freeze: ImageBitmap?) {
    var last by remember { mutableStateOf<ImageBitmap?>(null) }
    if (freeze != null) last = freeze
    AnimatedVisibility(freeze != null, enter = fadeIn(tween(60)), exit = fadeOut(tween(260))) {
        last?.let {
            Box(Modifier.fillMaxSize()) {
                Image(it, null, Modifier.fillMaxSize().blur(28.dp), contentScale = ContentScale.Crop)
                Box(Modifier.fillMaxSize().background(Color(0x33000000)))
            }
        }
    }
}

/** Shutter feedback: a short dark blink over the picture, like a real shutter. */
@Composable
private fun BlinkOverlay(shots: Int) {
    val a = remember { Animatable(0f) }
    LaunchedEffect(shots) {
        if (shots == 0) return@LaunchedEffect
        a.snapTo(.75f)
        a.animateTo(0f, tween(220))
    }
    if (a.value > 0f) Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = a.value)))
}

private class Ring(val pos: Offset, val id: Int)

/**
 * Touch on the picture: tap = focus + exposure there (ring), drag up / down next to the ring = brighter / darker,
 * pinch = zoom (tick at 1× and 2×), swipe left / right = change mode, double tap = flip camera.
 */
@Composable
private fun FocusLayer(cam: PrismCamera, rot: Float, onEditText: (Layer) -> Unit, onDoubleTap: () -> Unit, onSwipe: (Int) -> Unit) {
    val editCb by androidx.compose.runtime.rememberUpdatedState(onEditText)
    val swipeCb by androidx.compose.runtime.rememberUpdatedState(onSwipe)
    val doubleCb by androidx.compose.runtime.rememberUpdatedState(onDoubleTap)
    val haptic = rememberHaptic()
    val density = LocalDensity.current
    var ring by remember { mutableStateOf<Ring?>(null) }
    var lastTouch by remember { mutableLongStateOf(0L) }
    var lastTap by remember { mutableStateOf(0L to Offset.Zero) }
    LaunchedEffect(ring?.id, lastTouch) {
        if (ring == null) return@LaunchedEffect
        delay(if (cam.exposure != 0) 5000 else 2600)
        ring = null
    }
    Box(
        Modifier.fillMaxSize().pointerInput(Unit) {
            val slop = viewConfiguration.touchSlop
            val swipeDist = 56.dp.toPx()
            val ringReach = 130.dp.toPx()
            val expStep = 14.dp.toPx()
            awaitEachGesture {
                val down = awaitFirstDown(requireUnconsumed = false)
                val start = down.position
                var pinch = false
                var swiped = false
                var expDrag = false
                var expAcc = 0f
                var last = start
                var moved = Offset.Zero
                val nearRing = ring?.let { (it.pos - start).getDistance() < ringReach } == true
                // ── a layer under the finger: move it (1 finger) or scale + turn it (2 fingers); attached layers move
                // their offset from the face / hand, and pinch changes their size relative to it
                // ── finger drawing on the selected drawing layer (two fingers = cancel the line)
                val drawL = Studio.selected()?.takeIf { it.type == LType.Drawing && !it.locked && it.visible }
                if (drawL != null) {
                    val pts = arrayListOf(start.x / size.width, start.y / size.height)
                    fun line() = com.prism.cam.studio.Stroke(pts.toFloatArray(), Make.penColor, Make.penWidth, Make.penGlow)
                    drawL.strokes.add(line())
                    var multi = false
                    var lastP = start
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) multi = true
                        else if (pressed.size == 1 && !multi) {
                            val p = pressed[0].position
                            if ((p - lastP).getDistance() > 2.5f) {
                                lastP = p; pts += p.x / size.width; pts += p.y / size.height
                                drawL.strokes[drawL.strokes.size - 1] = line()
                                drawL.touchContent()
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    if (multi) drawL.strokes.removeAt(drawL.strokes.size - 1)
                    drawL.touchContent(); Studio.commit("draw")
                    return@awaitEachGesture
                }
                val hit = hitLayer(start.x, start.y, size.width.toFloat(), size.height.toFloat(), cam.fx, 14.dp.toPx())
                if (hit != null) {
                    val wasSelected = Studio.selection.firstOrNull() == hit.id
                    Studio.select(hit.id)
                    var movedAny = false
                    val bwPx = size.width.toFloat(); val bhPx = size.height.toFloat()
                    fun moveBy(dx: Float, dy: Float) {
                        if (hit.attach.kind.isNotEmpty()) {
                            val q = layerQuad(hit, cam.fx, bwPx, bhPx) ?: return
                            val faceW = (q.w / hit.attach.size.coerceAtLeast(.05f)).coerceAtLeast(8f)
                            val ang = Math.toRadians((if (hit.attach.followRot) q.rot - hit.rot else 0f).toDouble())
                            val c = kotlin.math.cos(ang).toFloat(); val sn = kotlin.math.sin(ang).toFloat()
                            hit.attach.ox += (dx * c + dy * sn) / faceW
                            hit.attach.oy += (-dx * sn + dy * c) / faceW
                        } else {
                            hit.x = (hit.x + dx / bwPx).coerceIn(-.2f, 1.2f)
                            hit.y = (hit.y + dy / bhPx).coerceIn(-.2f, 1.2f)
                        }
                    }
                    do {
                        val ev = awaitPointerEvent()
                        val pressed = ev.changes.filter { it.pressed }
                        if (pressed.size >= 2) {
                            val z = ev.calculateZoom(); val r = ev.calculateRotation(); val pan = ev.calculatePan()
                            if (hit.attach.kind.isNotEmpty() && hit.attach.followScale) hit.attach.size = (hit.attach.size * z).coerceIn(.05f, 6f)
                            else hit.w = (hit.w * z).coerceIn(.03f, 3f)
                            hit.rot += r
                            moveBy(pan.x, pan.y)
                            movedAny = true
                            Studio.touch()
                        } else if (pressed.size == 1) {
                            val ch = pressed[0]
                            if ((ch.position - start).getDistance() > slop) movedAny = true
                            if (movedAny) {
                                val d = ch.positionChange()
                                moveBy(d.x, d.y)
                                Studio.touch()
                            }
                        }
                        ev.changes.forEach { it.consume() }
                    } while (ev.changes.any { it.pressed })
                    if (movedAny) { hit.rot = snapAngle(hit.rot); Studio.commit("move") }
                    else if (wasSelected && hit.type == LType.Text) editCb(hit)
                    return@awaitEachGesture
                }
                do {
                    val ev = awaitPointerEvent()
                    val pressed = ev.changes.filter { it.pressed }
                    if (pressed.size >= 2) {
                        if (!pinch) { pinch = true; cam.pinchStart() }
                        val zf = ev.calculateZoom()
                        if (zf != 1f) {
                            val before = cam.zoom
                            cam.zoomTo(before * zf)
                            val after = cam.zoom
                            if ((before - 1f) * (after - 1f) < 0f || (before - 2f) * (after - 2f) < 0f) haptic(HapticFeedbackConstants.CLOCK_TICK)
                        }
                        ev.changes.forEach { it.consume() }
                    } else if (pressed.size == 1 && !pinch) {
                        val ch = pressed[0]
                        moved = ch.position - start
                        if (!swiped && !expDrag) {
                            if (nearRing && abs(moved.y) > slop && abs(moved.y) > abs(moved.x)) expDrag = true
                            else if (abs(moved.x) > swipeDist && abs(moved.x) > 2 * abs(moved.y)) { swiped = true; swipeCb(if (moved.x > 0) 1 else -1) }
                        }
                        if (expDrag) {
                            expAcc += last.y - ch.position.y // up = brighter
                            while (abs(expAcc) >= expStep) {
                                val step = if (expAcc > 0) 1 else -1
                                expAcc -= step * expStep
                                val before = cam.exposure
                                cam.exposeTo(before + step)
                                if (cam.exposure != before && cam.exposure == 0) haptic(HapticFeedbackConstants.CLOCK_TICK)
                            }
                            lastTouch = System.currentTimeMillis()
                            ch.consume()
                        }
                        last = ch.position
                    }
                } while (ev.changes.any { it.pressed })
                if (pinch) cam.pinchEnd()
                PrismLog.i("TOUCH end pinch=$pinch swipe=$swiped exp=$expDrag near=$nearRing moved=${moved.getDistance().toInt()} expo=${cam.exposure} ring=${ring != null}")
                if (!pinch && !swiped && !expDrag && moved.getDistance() < slop * 2) {
                    val now = System.currentTimeMillis()
                    val (t, p) = lastTap
                    if (now - t < 320 && (p - start).getDistance() < 90.dp.toPx()) {
                        lastTap = 0L to Offset.Zero
                        doubleCb()
                    } else {
                        lastTap = now to start
                        if (Studio.selection.isNotEmpty()) Studio.selection.clear()
                        cam.focusAt(start.x, start.y)
                        ring = Ring(start, (ring?.id ?: 0) + 1)
                        lastTouch = now
                    }
                }
            }
        },
    ) {
        ring?.let { r -> FocusRing(r, cam, rot, density.run { r.pos.x.toDp() }, density.run { r.pos.y.toDp() }) }
    }
}

@Composable
private fun FocusRing(r: Ring, cam: PrismCamera, rot: Float, x: Dp, y: Dp) {
    val grow = remember(r.id) { Animatable(1.35f) }
    LaunchedEffect(r.id) { grow.animateTo(1f, spring(dampingRatio = .55f, stiffness = 380f)) }
    val size = 76.dp
    Box(Modifier.offset(x - size / 2, y - size / 2).size(size).scale(grow.value).border(1.5.dp, Color(0xFFFFD98A), CircleShape))
    if (cam.exposureMax > cam.exposureMin) {
        // exposure track beside the ring: sun moves up when brighter
        val track = 96.dp
        val f = (cam.exposure - cam.exposureMin).toFloat() / (cam.exposureMax - cam.exposureMin)
        Box(Modifier.offset(x + size / 2 + 10.dp, y - track / 2).width(22.dp).height(track)) {
            Box(Modifier.align(Alignment.Center).width(1.dp).fillMaxHeight().background(Color(0x99FFD98A)))
            Box(Modifier.offset { IntOffset(0, ((1f - f) * (track - 22.dp).toPx()).roundToInt()) }.size(22.dp).rotate(rot), contentAlignment = Alignment.Center) {
                PrismIcon("day", size = 20.dp, tint = Color(0xFFFFD98A), stroke = 1.8f)
            }
        }
    }
}
