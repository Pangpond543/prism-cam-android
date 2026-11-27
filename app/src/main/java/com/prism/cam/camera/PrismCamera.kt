package com.prism.cam.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.TotalCaptureResult
import android.media.AudioManager
import android.media.MediaActionSound
import android.net.Uri
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.provider.MediaStore
import android.view.Surface
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.CameraState
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.UseCaseGroup
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.lifecycle.awaitInstance
import androidx.camera.video.ExperimentalPersistentRecording
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.MediaStoreOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import com.prism.cam.PrismLog
import com.prism.cam.i18n.T
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.math.abs

/**
 * The camera engine (CameraX). Owns the use cases and exposes everything the screen needs as Compose state.
 *
 * - Photo mode: Preview + ImageCapture (4:3, highest resolution, zero-shutter-lag when the camera supports it).
 * - Video mode: Preview + VideoCapture (16:9 MP4, FHD → HD → SD, stabilised when supported) + ImageCapture
 *   (photo while recording). Recording is *persistent*: flipping the camera mid-recording keeps the same clip.
 * - Preview rotation follows the display; photo / video rotation follows how the device is held (sensor),
 *   so a photo taken with rotation lock on is still upright.
 * All callbacks land on the main thread; file writes and thumbnails run on [io].
 */
class PrismCamera(private val app: Context) {
    /** Portrait = photo with the background blurred by the AI (live + on the photo) */
    enum class Mode { Photo, Video, Portrait }
    enum class Flash { Off, Auto, On }

    // ── state for the UI
    var mode by mutableStateOf(Mode.Photo); private set
    var front by mutableStateOf(false); private set
    var hasFront by mutableStateOf(true); private set
    var zoom by mutableFloatStateOf(1f); private set
    var zoomMin by mutableFloatStateOf(1f); private set
    var zoomMax by mutableFloatStateOf(1f); private set
    var flash by mutableStateOf(Flash.Off); private set
    var torch by mutableStateOf(false); private set
    var hasFlash by mutableStateOf(false); private set
    var recording by mutableStateOf(false); private set
    var paused by mutableStateOf(false); private set
    var recSeconds by mutableLongStateOf(0L); private set
    var streaming by mutableStateOf(false); private set
    /** last frame shown blurred while the camera re-binds (mode / lens switch), cleared on the next live frame */
    var freeze by mutableStateOf<ImageBitmap?>(null); private set
    var error by mutableStateOf<String?>(null); private set
    var lastThumb by mutableStateOf<ImageBitmap?>(null); private set
    var lastItem by mutableStateOf<Gallery.Item?>(null); private set
    var exposure by mutableIntStateOf(0); private set
    var exposureMin by mutableIntStateOf(0); private set
    var exposureMax by mutableIntStateOf(0); private set
    /** +1 on every shutter press (drives the blink animation) */
    var shots by mutableIntStateOf(0); private set
    /** self-timer seconds left (0 = not counting) */
    var countdown by mutableIntStateOf(0); private set
    /** hold-to-record: finger is on the shutter and a clip is (or is about to be) recording */
    var holding by mutableStateOf(false); private set
    var audioOk = false

    // ── effects (GPU pipeline is only attached while something needs it — zero cost otherwise)
    val fx by lazy { com.prism.cam.effects.GlFx() }
    var look by mutableStateOf(com.prism.cam.effects.Looks.all[0]); private set
    /** extra reasons to keep the effect pipeline on (overlays, AI) — set by the effects layer */
    var fxReasons by mutableStateOf(emptySet<String>()); private set
    private var fxBound = false
    private fun fxWanted() = look.id != "none" || fxReasons.isNotEmpty()
    /** photo needs the CPU effect path */
    var photoFx: (() -> com.prism.cam.effects.PhotoFx.Job?)? = null

    /** the look is the camera layer's look (desktop: per-layer look) */
    fun useLook(l: com.prism.cam.effects.Look) {
        look = l
        com.prism.cam.studio.Studio.camera?.let { if (it.look != l.id) { it.look = l.id; com.prism.cam.studio.Studio.commit("look") } }
        syncFx()
    }

    /** the document changed the camera layer's look (undo, scene switch…) */
    fun syncLook(id: String) { look = com.prism.cam.effects.Looks.of(id); syncFx() }

    fun fxReason(key: String, on: Boolean) {
        fxReasons = if (on) fxReasons + key else fxReasons - key
        syncFx()
    }

    /** attach / detach the GPU pipeline when the need changes (not while recording: the clip must not break) */
    private fun syncFx() {
        if (fxWanted() == fxBound || recording) return
        grabFreeze()
        rebind()
    }

    /** user-facing messages (toast): text, ok */
    var onMessage: (String, Boolean) -> Unit = { _, _ -> }

    // ── CameraX objects
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private var preview: Preview? = null
    private var imageCapture: ImageCapture? = null
    private var videoCapture: VideoCapture<Recorder>? = null
    private var activeRecording: Recording? = null
    private var owner: LifecycleOwner? = null
    private var view: PreviewView? = null
    private var displayRotation = Surface.ROTATION_0
    private var deviceRotation = Surface.ROTATION_0

    private val main = ContextCompat.getMainExecutor(app)
    private val io = Executors.newSingleThreadExecutor { r -> Thread(r, "prism-io").apply { priority = Thread.NORM_PRIORITY - 1 } }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val sound = MediaActionSound().apply {
        load(MediaActionSound.SHUTTER_CLICK); load(MediaActionSound.START_VIDEO_RECORDING); load(MediaActionSound.STOP_VIDEO_RECORDING)
    }
    private var firstFrameLogged = false
    private var bindStarted = 0L

    // ───────────────────────────── lifecycle ─────────────────────────────

    /**
     * Opens the camera right away (from Activity.onCreate), before the screen exists: the camera hardware opens
     * (~0.3–0.5 s) while Compose builds the UI; the picture appears as soon as [attachView] hands over the surface.
     * Binding is tied to [owner]'s lifecycle (released on stop, reopened on start).
     */
    fun start(owner: LifecycleOwner) {
        if (this.owner != null) return
        this.owner = owner
        // Fast path: CameraX was warmed up in PrismApp. Wait for it here (main thread, ≤ 400 ms, nothing is on
        // screen yet anyway) so the camera opens BEFORE Compose builds the UI — measured: a suspended wait only
        // resumed after the first composition (~T+250 ms), which delayed the picture by ~200 ms.
        val ready = try {
            ProcessCameraProvider.getInstance(app).get(400, TimeUnit.MILLISECONDS)
        } catch (_: Throwable) { null }
        if (ready != null) {
            provider = ready
            PrismLog.mark("provider (sync)")
            try {
                hasFront = ready.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA)
                rebind()
            } catch (t: Throwable) { fail("init", t) }
            refreshLatest()
            return
        }
        scope.launch {
            try {
                provider = ProcessCameraProvider.awaitInstance(app)
                PrismLog.mark("provider")
                hasFront = provider?.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) == true
                rebind()
                refreshLatest()
            } catch (t: Throwable) {
                fail("init", t)
            }
        }
    }

    /** Another part of the app (QR scanner, PC link) takes the camera: stop recording, let go of it. */
    private var lent = false
    fun lendCamera() {
        if (recording) stopRecording()
        lent = true
        try { provider?.unbindAll() } catch (_: Throwable) { }
        camera?.cameraInfo?.let { unobserve(it) }
        camera = null
        streaming = false
    }

    /** The PreviewView is on screen: give the running camera its surface. */
    fun attachView(view: PreviewView) {
        val owner = owner ?: return
        if (lent) { lent = false; this.view = view; rebind() }
        if (this.view === view) return
        this.view = view
        PrismLog.mark("view")
        preview?.setSurfaceProvider(view.surfaceProvider)
        view.previewStreamState.observe(owner) { st ->
            val live = st == PreviewView.StreamState.STREAMING
            streaming = live
            liveSince = if (live) SystemClock.elapsedRealtime() else 0L
            if (live) {
                freeze = null
                if (pendingShot) { pendingShot = false; scope.launch { kotlinx.coroutines.delay(SETTLE_MS); takePhoto() } }
                if (holdPending && mode == Mode.Video) { holdPending = false; startRecording() }
                val now = SystemClock.elapsedRealtime()
                if (!firstFrameLogged) {
                    firstFrameLogged = true
                    PrismLog.i("OPEN first frame ${now - Process.getStartElapsedRealtime()} ms after process start")
                } else if (bindStarted > 0) PrismLog.i("BIND live in ${now - bindStarted} ms (${mode.name} ${if (front) "front" else "back"})")
                bindStarted = 0
            }
        }
    }

    fun refreshLatest() {
        io.execute {
            val item = Gallery.latest(app) ?: return@execute
            val bmp = Gallery.thumbnail(app, item.uri, item.video)?.asImageBitmap()
            main.execute { lastItem = item; if (bmp != null) lastThumb = bmp }
        }
    }

    /** App went to the background: a persistent recording would keep running without frames — stop it. */
    fun onStop() {
        if (recording) stopRecording()
    }

    fun release() {
        sound.release()
        io.shutdown()
    }

    // ───────────────────────────── binding ─────────────────────────────

    @SuppressLint("RestrictedApi")
    @OptIn(ExperimentalCamera2Interop::class)
    private fun rebind() {
        val p = provider ?: return
        val owner = owner ?: return
        bindStarted = SystemClock.elapsedRealtime()
        var selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        if (!p.hasCamera(selector)) {
            front = !front
            selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        }
        try {
            val info = p.getCameraInfo(selector)
            val photo = mode != Mode.Video
            val shape = com.prism.cam.prefs.CamPrefs.aspect
            val wide = !photo || shape == com.prism.cam.prefs.CamPrefs.Aspect.R16_9
            val aspect = AspectRatioStrategy(
                if (wide) AspectRatio.RATIO_16_9 else AspectRatio.RATIO_4_3, AspectRatioStrategy.FALLBACK_RULE_AUTO,
            )

            val pb = Preview.Builder()
                .setResolutionSelector(ResolutionSelector.Builder().setAspectRatioStrategy(aspect).build())
                .setTargetRotation(displayRotation)
            if (!photo && Preview.getPreviewCapabilities(info).isStabilizationSupported) pb.setPreviewStabilizationEnabled(true)
            val ext = Camera2Interop.Extender(pb).setSessionCaptureCallback(fpsMeter)
            // Smooth picture: never let auto-exposure drop the preview below ~15–20 fps in a dim room
            // (the tablet fell to 13 fps by default). Video: steady 30 when the camera offers it.
            val fps = pickFps(info, video = !photo)
            if (fps != null) ext.setCaptureRequestOption(CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE, fps)
            val pv = pb.build().also { p -> view?.let { p.setSurfaceProvider(it.surfaceProvider) } }

            val zsl = photo && info.isZslSupported && zslAllowed
            val ib = ImageCapture.Builder()
                .setCaptureMode(if (zsl) ImageCapture.CAPTURE_MODE_ZERO_SHUTTER_LAG else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setResolutionSelector(
                    ResolutionSelector.Builder().setAspectRatioStrategy(aspect)
                        .setResolutionStrategy(ResolutionStrategy.HIGHEST_AVAILABLE_STRATEGY).build(),
                )
                .setJpegQuality(95)
                .setFlashMode(flashMode())
                .setTargetRotation(deviceRotation)
            val ic = ib.build()
            // 1:1 = the centre square of the 4:3 sensor picture (the preview box shows exactly that square)
            if (photo && shape == com.prism.cam.prefs.CamPrefs.Aspect.R1_1) ic.setCropAspectRatio(android.util.Rational(1, 1))

            val group = UseCaseGroup.Builder().addUseCase(pv).addUseCase(ic)
            fxBound = fxWanted()
            if (fxBound) {
                fx.uprightDeg = info.getSensorRotationDegrees(displayRotation)
                fx.front = front
                group.addEffect(com.prism.cam.effects.PrismEffect(fx))
            }
            if (!photo) {
                // keep the same VideoCapture while recording: a persistent recording survives a camera flip
                val vc = if (recording && videoCapture != null) videoCapture!! else buildVideo(info)
                videoCapture = vc
                group.addUseCase(vc)
            } else if (!recording) videoCapture = null

            p.unbindAll()
            val cam = p.bindToLifecycle(owner, selector, group.build())
            PrismLog.mark("bound")
            camera?.cameraInfo?.let { unobserve(it) }
            camera = cam
            preview = pv
            imageCapture = ic
            observe(cam.cameraInfo, owner)
            hasFlash = cam.cameraInfo.hasFlashUnit()
            val ex = cam.cameraInfo.exposureState
            exposureMin = if (ex.isExposureCompensationSupported) ex.exposureCompensationRange.lower else 0
            exposureMax = if (ex.isExposureCompensationSupported) ex.exposureCompensationRange.upper else 0
            exposure = 0
            if (!photo && torch && hasFlash) cam.cameraControl.enableTorch(true)
            error = null
            fpsFrames = 0; fpsSince = 0L; fpsReports = 0
            PrismLog.i(
                "BIND ${mode.name} ${if (front) "front" else "back"} zsl=$zsl " +
                    "flash=$hasFlash exp=$exposureMin..$exposureMax fps=$fps fx=$fxBound",
            )
        } catch (t: Throwable) {
            fail("bind", t)
        }
    }

    /** test hook `zsl:0` turns zero-shutter-lag off to compare shutter delay */
    var zslAllowed = true
        set(v) { field = v; rebind() }

    @OptIn(ExperimentalCamera2Interop::class)
    private fun pickFps(info: CameraInfo, video: Boolean): android.util.Range<Int>? = try {
        val ranges = androidx.camera.camera2.interop.Camera2CameraInfo.from(info)
            .getCameraCharacteristic(android.hardware.camera2.CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
            ?.filter { it.upper == 30 }.orEmpty()
        if (!loggedFps) { loggedFps = true; PrismLog.i("FPS ranges ${ranges.joinToString()}") }
        if (video) ranges.firstOrNull { it.lower == 30 } ?: ranges.filter { it.lower >= 20 }.minByOrNull { it.lower }
        // photo: a 15–24 fps floor if offered; otherwise the camera's own range. A steady 30 made dim rooms much
        // darker on the Huawei tablet (only [13,30] / [30,30] offered) — tested 2026-11-24, brighter photos win.
        else if (com.prism.cam.prefs.CamPrefs.smooth) ranges.firstOrNull { it.lower == 30 } ?: ranges.filter { it.lower >= 15 }.maxByOrNull { it.lower }
        else ranges.filter { it.lower in 15..24 }.maxByOrNull { it.lower }
    } catch (_: Throwable) { null }
    private var loggedFps = false

    private fun buildVideo(info: CameraInfo): VideoCapture<Recorder> {
        val recorder = Recorder.Builder()
            .setQualitySelector(
                QualitySelector.fromOrderedList(
                    listOf(Quality.FHD, Quality.HD, Quality.SD),
                    FallbackStrategy.lowerQualityOrHigherThan(Quality.SD),
                ),
            )
            .setAspectRatio(AspectRatio.RATIO_16_9)
            // steady quality: with the GPU effect pipeline CameraX picked only ~5.5 Mbps for 1080p (24 without)
            .setTargetVideoEncodingBitRate(16_000_000)
            .build()
        // front camera: save the clip mirrored like the screen and the photos (text / stickers stay readable)
        val b = VideoCapture.Builder(recorder).setTargetRotation(deviceRotation).setMirrorMode(androidx.camera.core.MirrorMode.MIRROR_MODE_ON_FRONT_ONLY)
        if (Recorder.getVideoCapabilities(info).isStabilizationSupported) b.setVideoStabilizationEnabled(true)
        return b.build()
    }

    private fun observe(info: CameraInfo, owner: LifecycleOwner) {
        info.zoomState.observe(owner) { z ->
            zoomMin = z.minZoomRatio
            zoomMax = z.maxZoomRatio
            if (!pinching) zoom = z.zoomRatio
        }
        info.torchState.observe(owner) { torch = it == androidx.camera.core.TorchState.ON }
        info.cameraState.observe(owner) { st ->
            val e = st.error ?: run { if (st.type == CameraState.Type.OPEN) error = null; return@observe }
            PrismLog.i("CAMERA state ${st.type} error ${e.code}")
            error = when (e.code) {
                CameraState.ERROR_CAMERA_IN_USE, CameraState.ERROR_MAX_CAMERAS_IN_USE -> T("แอปอื่นกำลังใช้กล้องอยู่ ปิดแอปนั้นแล้วลองใหม่")
                CameraState.ERROR_CAMERA_DISABLED -> T("กล้องถูกปิดไว้ในการตั้งค่าของเครื่อง")
                CameraState.ERROR_DO_NOT_DISTURB_MODE_ENABLED -> T("ปิดโหมดห้ามรบกวนก่อน แล้วลองใหม่")
                CameraState.ERROR_STREAM_CONFIG, CameraState.ERROR_CAMERA_FATAL_ERROR, CameraState.ERROR_OTHER_RECOVERABLE_ERROR ->
                    if (st.type == CameraState.Type.OPEN) null else T("กล้องมีปัญหา กำลังลองเปิดใหม่…")
                else -> null
            }
        }
    }

    private fun unobserve(info: CameraInfo) {
        val o = owner ?: return
        info.zoomState.removeObservers(o)
        info.torchState.removeObservers(o)
        info.cameraState.removeObservers(o)
    }

    fun retry() {
        error = null
        rebind()
    }

    private fun fail(where: String, t: Throwable) {
        PrismLog.i("ERR $where ${t.javaClass.simpleName}: ${t.message}")
        error = T("เปิดกล้องไม่ได้") + " (${t.javaClass.simpleName})"
    }

    // ───────────────────────────── fps meter (log only, first 10 s after each bind) ─────────────────────────────

    private var fpsFrames = 0
    private var fpsSince = 0L
    private var fpsReports = 0
    private val fpsMeter = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            if (fpsReports >= 5) return
            val now = SystemClock.elapsedRealtime()
            if (fpsSince == 0L) { fpsSince = now; fpsFrames = 0; return }
            fpsFrames++
            if (now - fpsSince >= 2000) {
                PrismLog.i("FPS camera %.1f".format(fpsFrames * 1000f / (now - fpsSince)))
                fpsReports++; fpsSince = now; fpsFrames = 0
            }
        }
    }

    // ───────────────────────────── rotation ─────────────────────────────

    fun setDisplayRotation(r: Int) {
        if (r == displayRotation) return
        displayRotation = r
        preview?.targetRotation = r
        camera?.cameraInfo?.let { fx.uprightDeg = it.getSensorRotationDegrees(r); if (fxBound) fx.refreshGeometry() }
        // With an effect, CameraX fixes the processed preview's rotation when it binds: a rotation afterwards left the
        // whole scene turned 90° (tablet, 2026-11-24). Rebind so the effect geometry is built for the new rotation.
        if (fxBound && !recording && camera != null) { PrismLog.i("ROT rebind for effects (rotation $r)"); grabFreeze(); rebind() }
    }

    /** How the device is held (OrientationEventListener). Photos/videos follow this, not the screen. */
    fun setDeviceRotation(r: Int) {
        if (r == deviceRotation) return
        deviceRotation = r
        imageCapture?.targetRotation = r
        if (!recording) videoCapture?.targetRotation = r
    }

    // ───────────────────────────── controls ─────────────────────────────

    fun selectMode(m: Mode) {
        if (m == mode || recording) return
        cancelCountdown()
        grabFreeze()
        mode = m
        if (m != Mode.Video && torch) camera?.cameraControl?.enableTorch(false)
        rebind()
    }

    fun switchLens() {
        if (!hasFront) return
        grabFreeze()
        front = !front
        rebind()
    }

    private fun grabFreeze() {
        val b = try { view?.bitmap } catch (_: Throwable) { null } ?: return
        // a small copy is enough for a blurred still and keeps memory low
        val w = 360
        val small = android.graphics.Bitmap.createScaledBitmap(b, w, (w.toFloat() * b.height / b.width).toInt().coerceAtLeast(1), true)
        if (small !== b) b.recycle()
        freeze = small.asImageBitmap()
    }

    // ── small stills for the look thumbnails (the un-filtered picture is remembered while no look is on)
    private var rawSample: android.graphics.Bitmap? = null
    fun sampleForThumbs(): android.graphics.Bitmap? {
        val b = try { view?.bitmap } catch (t: Throwable) { PrismLog.i("ERR sample ${t.message}"); null }
        if (b == null) return rawSample
        val w = 160
        val small = android.graphics.Bitmap.createScaledBitmap(b, w, (w.toFloat() * b.height / b.width).toInt().coerceAtLeast(1), true)
        if (small !== b) b.recycle()
        if (!fxBound || look.id == "none") rawSample = small
        return if (fxBound && look.id != "none") rawSample ?: small else small
    }

    /** sensor → screen rotation for the current lens (photo effects work in screen space) */
    fun displaySensorRotation(): Int = camera?.cameraInfo?.getSensorRotationDegrees(displayRotation) ?: 0

    /** set by the AI layer: background step for photos (null = none) */
    var photoBackground: (() -> ((android.graphics.Bitmap) -> android.graphics.Bitmap)?)? = null

    /** set by the AI layer: turns background / face effects off ("clear all") */
    var clearAi: () -> Unit = {}
    fun clearAiLooks() = clearAi()

    private var pinching = false
    fun pinchStart() { pinching = true }
    fun pinchEnd() { pinching = false }

    fun zoomTo(r: Float) {
        val c = camera ?: return
        val v = r.coerceIn(zoomMin, zoomMax)
        zoom = v
        c.cameraControl.setZoomRatio(v)
    }

    fun focusAt(x: Float, y: Float) {
        val c = camera ?: return
        val v = view ?: return
        val pt = v.meteringPointFactory.createPoint(x, y)
        val action = FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
            .setAutoCancelDuration(5, TimeUnit.SECONDS).build()
        c.cameraControl.startFocusAndMetering(action)
        if (exposure != 0) exposeTo(0)
    }

    fun exposeTo(i: Int) {
        val c = camera ?: return
        val v = i.coerceIn(exposureMin, exposureMax)
        if (v == exposure && i != 0) return
        exposure = v
        c.cameraControl.setExposureCompensationIndex(v)
    }

    fun cycleFlash() {
        if (mode == Mode.Video) {
            val on = !torch
            camera?.cameraControl?.enableTorch(on)
            torch = on
            return
        }
        flash = when (flash) { Flash.Off -> Flash.Auto; Flash.Auto -> Flash.On; Flash.On -> Flash.Off }
        imageCapture?.flashMode = flashMode()
    }

    private fun flashMode() = when (flash) {
        Flash.Off -> ImageCapture.FLASH_MODE_OFF
        Flash.Auto -> ImageCapture.FLASH_MODE_AUTO
        Flash.On -> ImageCapture.FLASH_MODE_ON
    }

    /** Photo shape / smooth picture changed in the menu: rebind with a blurred still over the switch. */
    fun applyShape() {
        if (recording) return
        grabFreeze()
        rebind()
    }

    /**
     * The shutter / volume key: photo in photo mode, start / stop in video mode. With the self-timer on, a press
     * starts the countdown and a second press cancels it. Stopping a recording never waits.
     */
    fun shutter() {
        if (countdownJob != null) { cancelCountdown(); return }
        if (mode == Mode.Video && recording) { stopRecording(); return }
        val t = com.prism.cam.prefs.CamPrefs.timer
        if (t > 0) startCountdown(t) else fire()
    }

    private fun fire() { if (mode != Mode.Video) takePhoto() else startRecording() }

    /** ✌️ seen by the AI: photo after a 3 s countdown (time to lower the hand); in video mode start / stop. */
    fun peaceShot() {
        if (countdownJob != null) return
        if (mode == Mode.Video && recording) { stopRecording(); return }
        startCountdown(3)
    }

    private var countdownJob: kotlinx.coroutines.Job? = null
    private fun startCountdown(secs: Int) {
        countdownJob = scope.launch {
            for (s in secs downTo 1) { countdown = s; kotlinx.coroutines.delay(1000) }
            countdown = 0
            countdownJob = null
            fire()
        }
    }

    fun cancelCountdown() {
        countdownJob?.cancel()
        countdownJob = null
        countdown = 0
    }

    // ── hold-to-record: long-press the shutter = record while the finger stays down (photo mode switches to video
    //    for the clip and comes back afterwards)
    private var holdPending = false
    private var holdBackToPhoto = false
    private var holdReturnMode = Mode.Photo

    fun holdStart() {
        if (recording || countdownJob != null) return
        holding = true
        if (mode == Mode.Video) { startRecording(); return }
        holdReturnMode = mode
        holdBackToPhoto = true
        holdPending = true
        selectMode(Mode.Video)
    }

    fun holdEnd() {
        if (!holding) return
        holding = false
        if (holdPending) {
            // finger lifted before the video camera was ready: nothing recorded, go back
            holdPending = false
            if (holdBackToPhoto) { holdBackToPhoto = false; selectMode(holdReturnMode) }
            return
        }
        if (recording) stopRecording()
    }

    // ───────────────────────────── photo ─────────────────────────────

    // A press before the picture is live (app just opened, mode / lens switch, effects rebind) was captured from a
    // camera whose exposure had not started: a black photo on the realme 9 (2026-11-27). Wait for the picture instead.
    private var pendingShot = false
    private var liveSince = 0L
    private val SETTLE_MS = 250L
    /** how long the picture has been live (0 = not live) — a copy of it taken in the first ~0.5 s can be black */
    fun liveMs(): Long = if (!streaming || liveSince == 0L) 0L else SystemClock.elapsedRealtime() - liveSince

    fun takePhoto() {
        val ic = imageCapture ?: return
        if (!streaming || liveSince == 0L) {
            if (!pendingShot) {
                pendingShot = true
                PrismLog.i("SHOT waits for the picture")
                scope.launch { kotlinx.coroutines.delay(4000); if (pendingShot) { pendingShot = false; PrismLog.i("SHOT dropped: picture never came") } }
            }
            return
        }
        val young = SETTLE_MS - (SystemClock.elapsedRealtime() - liveSince)
        if (young > 0) { scope.launch { kotlinx.coroutines.delay(young); takePhoto() }; return }
        val t0 = SystemClock.elapsedRealtime()
        shots++
        playSound(MediaActionSound.SHUTTER_CLICK)
        val job = if (fxBound) photoFx?.invoke() ?: com.prism.cam.effects.PhotoFx.Job(
            camera?.cameraInfo?.getSensorRotationDegrees(displayRotation) ?: 0, null, look.vignette,
        ) else null
        if (job != null) { takeFxPhoto(ic, job, t0); return }
        val name = Gallery.newName()
        val opts = ImageCapture.OutputFileOptions.Builder(
            app.contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, Gallery.photoValues(name),
        ).build()
        ic.takePicture(opts, io, object : ImageCapture.OnImageSavedCallback {
            override fun onCaptureStarted() {
                PrismLog.i("SHOT capture started ${SystemClock.elapsedRealtime() - t0} ms after press")
            }

            override fun onImageSaved(r: ImageCapture.OutputFileResults) {
                val uri = r.savedUri
                PrismLog.i("SHOT saved ${SystemClock.elapsedRealtime() - t0} ms after press → $uri")
                if (uri != null) showThumb(uri, video = false)
            }

            override fun onError(e: ImageCaptureException) {
                PrismLog.i("ERR photo ${e.imageCaptureError} ${e.message}")
                main.execute { onMessage(T("ถ่ายรูปไม่สำเร็จ ลองอีกครั้ง"), false) }
            }
        })
    }

    /** Effects on: capture to memory, run the CPU effect path, save the finished JPEG. */
    private fun takeFxPhoto(ic: ImageCapture, job: com.prism.cam.effects.PhotoFx.Job, t0: Long) {
        ic.takePicture(io, object : ImageCapture.OnImageCapturedCallback() {
            override fun onCaptureStarted() {
                PrismLog.i("SHOT capture started ${SystemClock.elapsedRealtime() - t0} ms after press (fx)")
            }

            override fun onCaptureSuccess(image: androidx.camera.core.ImageProxy) {
                try {
                    val out = image.use { com.prism.cam.effects.PhotoFx.process(it, job, fx) }
                    val uri = Gallery.saveJpeg(app, out, Gallery.newName())
                    out.recycle()
                    PrismLog.i("SHOT saved ${SystemClock.elapsedRealtime() - t0} ms after press (fx) → $uri")
                    if (uri != null) showThumb(uri, video = false)
                    else main.execute { onMessage(T("ถ่ายรูปไม่สำเร็จ ลองอีกครั้ง"), false) }
                } catch (t: Throwable) {
                    PrismLog.i("ERR fx photo ${t.javaClass.simpleName} ${t.message}")
                    main.execute { onMessage(T("ถ่ายรูปไม่สำเร็จ ลองอีกครั้ง"), false) }
                }
            }

            override fun onError(e: ImageCaptureException) {
                PrismLog.i("ERR photo ${e.imageCaptureError} ${e.message}")
                main.execute { onMessage(T("ถ่ายรูปไม่สำเร็จ ลองอีกครั้ง"), false) }
            }
        })
    }

    private fun showThumb(uri: Uri, video: Boolean) {
        io.execute {
            val bmp = Gallery.thumbnail(app, uri, video)?.asImageBitmap()
            main.execute {
                lastItem = Gallery.Item(uri, video, System.currentTimeMillis() / 1000)
                if (bmp != null) lastThumb = bmp
            }
        }
    }

    // ───────────────────────────── video ─────────────────────────────

    @SuppressLint("MissingPermission")
    @OptIn(ExperimentalPersistentRecording::class)
    fun startRecording() {
        val vc = videoCapture ?: return
        if (recording) return
        val name = Gallery.newName()
        val out = MediaStoreOutputOptions.Builder(app.contentResolver, MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .setContentValues(Gallery.videoValues(name)).build()
        var pending = vc.output.prepareRecording(app, out).asPersistentRecording()
        val mic = audioOk && ContextCompat.checkSelfPermission(app, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED
        if (mic) pending = pending.withAudioEnabled()
        val t0 = SystemClock.elapsedRealtime()
        recording = true
        paused = false
        recSeconds = 0
        activeRecording = pending.start(main) { ev ->
            when (ev) {
                is VideoRecordEvent.Start -> {
                    playSound(MediaActionSound.START_VIDEO_RECORDING)
                    PrismLog.i("REC start ${SystemClock.elapsedRealtime() - t0} ms after press, mic=$mic")
                }
                is VideoRecordEvent.Status -> {
                    val s = ev.recordingStats.recordedDurationNanos / 1_000_000_000L
                    if (s != recSeconds) recSeconds = s
                }
                is VideoRecordEvent.Pause -> paused = true
                is VideoRecordEvent.Resume -> paused = false
                is VideoRecordEvent.Finalize -> {
                    recording = false
                    paused = false
                    activeRecording = null
                    val uri = ev.outputResults.outputUri
                    val bytes = ev.recordingStats.numBytesRecorded
                    PrismLog.i("REC end error=${ev.error} bytes=$bytes secs=${ev.recordingStats.recordedDurationNanos / 1e9} → $uri")
                    val usable = uri != Uri.EMPTY && bytes > 0 &&
                        (ev.error == VideoRecordEvent.Finalize.ERROR_NONE || ev.error == VideoRecordEvent.Finalize.ERROR_SOURCE_INACTIVE ||
                            ev.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE || ev.error == VideoRecordEvent.Finalize.ERROR_FILE_SIZE_LIMIT_REACHED)
                    if (usable) {
                        showThumb(uri, video = true)
                        onMessage(
                            if (ev.error == VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE) T("พื้นที่เต็ม บันทึกวิดีโอเท่าที่อัดได้แล้ว")
                            else T("บันทึกวิดีโอแล้ว"),
                            ev.error != VideoRecordEvent.Finalize.ERROR_INSUFFICIENT_STORAGE,
                        )
                    } else onMessage(T("อัดวิดีโอไม่สำเร็จ"), false)
                    holding = false
                    if (holdBackToPhoto) { holdBackToPhoto = false; selectMode(holdReturnMode) }
                }
            }
        }
    }

    fun stopRecording() {
        val r = activeRecording ?: return
        playSound(MediaActionSound.STOP_VIDEO_RECORDING)
        r.stop()
    }

    fun togglePause() {
        val r = activeRecording ?: return
        if (paused) r.resume() else r.pause()
    }

    // ───────────────────────────── sound ─────────────────────────────

    private fun playSound(what: Int) {
        val am = app.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val must = Build.VERSION.SDK_INT >= 33 && MediaActionSound.mustPlayShutterSound()
        if (must || am.ringerMode == AudioManager.RINGER_MODE_NORMAL) sound.play(what)
    }

    companion object {
        /** OrientationEventListener degrees → Surface rotation, with 10° of hysteresis around each edge. */
        fun rotationFor(deg: Int, current: Int): Int {
            if (deg < 0) return current
            val currentDeg = when (current) { Surface.ROTATION_90 -> 270; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 90; else -> 0 }
            var diff = abs(deg - currentDeg); if (diff > 180) diff = 360 - diff
            if (diff < 55) return current
            return when (deg) {
                in 45 until 135 -> Surface.ROTATION_270
                in 135 until 225 -> Surface.ROTATION_180
                in 225 until 315 -> Surface.ROTATION_90
                else -> Surface.ROTATION_0
            }
        }
    }
}
