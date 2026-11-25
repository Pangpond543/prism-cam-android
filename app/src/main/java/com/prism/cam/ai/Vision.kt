package com.prism.cam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.framework.image.ByteBufferExtractor
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.facelandmarker.FaceLandmarker
import com.google.mediapipe.tasks.vision.gesturerecognizer.GestureRecognizer
import com.google.mediapipe.tasks.vision.imagesegmenter.ImageSegmenter
import com.prism.cam.PrismLog
import com.prism.cam.camera.PrismCamera
import com.prism.cam.i18n.T
import com.prism.cam.studio.Tracking
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * On-device AI (MediaPipe, bundled models, no internet, no Google services), like the desktop app's shared AI worker:
 * - person mask → background blur / replace (live on the GPU, and on photos);
 * - face landmarks + expressions (1–4 faces) → [Tracking] → layers attached to faces, face paint, characters, face gestures;
 * - hand landmarks + gestures (2 hands) → [Tracking] → layers attached to hands, air drawing, hand gestures, ✌️ shutter.
 * Works on the small upright "analysis" copy of each frame the GPU pipeline reads back (~256 px, ~15 fps); only the
 * parts that are needed run. Everything AI runs on one background thread. Points stay in camera space (not mirrored);
 * the compositor maps them onto the canvas.
 */
class Vision(private val ctx: Context, private val cam: PrismCamera) {
    // ── what is on (UI state)
    /** 0 none · 1 blur · 2 replace */
    var bg by mutableIntStateOf(0); private set
    var blur by mutableFloatStateOf(.75f); private set
    /** id of the replacement background (built-in gradient or "photo") */
    var bgId by mutableStateOf("prism"); private set
    var peace by mutableStateOf(false); private set
    /** "portrait" mode forces blur on while it is selected */
    var portrait by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set

    // ── tracking switches (Tracking panel) — the user's master switches; work only runs when something needs it
    var faceOn by mutableStateOf(true); private set
    var handsOn by mutableStateOf(true); private set
    var maxFaces by mutableIntStateOf(1); private set
    var gpu by mutableStateOf(true); private set
    /** show the tracked points over the picture (tuning) */
    var showMesh by mutableStateOf(false); private set
    /** what the scene needs right now (set by the studio pump) */
    private var faceNeeded = false
    private var handNeeded = false
    // ── live numbers (Tracking panel / speed panel)
    var facesSeen by mutableIntStateOf(0); private set
    var handsSeen by mutableIntStateOf(0); private set
    var aiMs by mutableIntStateOf(0); private set
    var faceGpu by mutableStateOf<Boolean?>(null); private set
    var handGpu by mutableStateOf<Boolean?>(null); private set
    /** gesture listeners (face / hand commands, air drawing): called on the AI thread after each tracked frame */
    var onTracked: (() -> Unit)? = null

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "prism-ai").apply { priority = Thread.NORM_PRIORITY } }
    private var seg: ImageSegmenter? = null
    private var face: FaceLandmarker? = null
    private var faceKey = ""
    private var gesture: GestureRecognizer? = null
    private var gestureKey = ""
    private var segImage: ImageSegmenter? = null
    private var bgPicture: Bitmap? = null
    private var peaceStreak = 0
    private var lastPeace = 0L
    private var frameNo = 0L
    private val main = ContextCompat.getMainExecutor(ctx)

    init {
        cam.clearAi = { useBg(0); usePeace(false) }
    }

    private val effectiveBg get() = if (portrait) 1 else bg
    val faceActive get() = faceOn && (faceNeeded || showMesh)
    val handActive get() = handsOn && (handNeeded || peace || showMesh)

    // ───────────────────────────── switches ─────────────────────────────

    fun useBg(mode: Int) { bg = mode; apply() }
    fun useBlur(v: Float) { blur = v.coerceIn(0f, 1f); cam.fx.blurAmount = blur }
    fun useBgId(id: String, picture: Bitmap? = null) {
        bgId = id
        if (picture != null) bgPicture?.recycle()
        bgPicture = picture ?: builtInBackground(id)
        cam.fx.setBackground(bgPicture)
        if (bg != 2) bg = 2
        apply()
    }
    fun usePeace(on: Boolean) { peace = on; peaceStreak = 0; apply() }
    fun usePortrait(on: Boolean) { portrait = on; apply() }
    fun useFace(on: Boolean) { faceOn = on; if (!on) Tracking.faces = emptyList(); apply() }
    fun useHands(on: Boolean) { handsOn = on; if (!on) run { Tracking.hands = emptyList(); Tracking.pair = "" }; apply() }
    fun useMaxFaces(n: Int) { maxFaces = n.coerceIn(1, 4); apply() }
    fun useGpu(on: Boolean) { gpu = on; apply() }
    fun useMesh(on: Boolean) { showMesh = on; cam.fx.showMesh = on; apply() }

    /** the studio tells us what its layers need */
    fun need(face: Boolean, hand: Boolean) {
        if (face == faceNeeded && hand == handNeeded) return
        faceNeeded = face; handNeeded = hand
        if (!face && !showMesh) Tracking.faces = emptyList()
        if (!hand && !showMesh) run { Tracking.hands = emptyList(); Tracking.pair = "" }
        apply()
    }

    /** Turn the GPU analysis + effect pipeline on/off to match what is switched on. */
    private fun apply() {
        val fx = cam.fx
        fx.bgMode = effectiveBg
        fx.blurAmount = blur
        if (effectiveBg == 2 && bgPicture == null) { bgPicture = builtInBackground(bgId); fx.setBackground(bgPicture) }
        if (effectiveBg == 0) fx.setMask(null, 0, 0)
        val needFrames = effectiveBg != 0 || faceActive || handActive
        fx.analysisBusy = false
        fx.analysisListener = if (needFrames) ({ buf, w, h, _ -> onFrame(buf, w, h) }) else null
        cam.fxReason("ai", needFrames)
        if (needFrames) worker.execute { ensureModels() }
    }

    private fun ensureModels() {
        try {
            if (effectiveBg != 0 && seg == null) seg = makeSegmenter(RunningMode.VIDEO)
            val fk = "$maxFaces/$gpu"
            if (faceActive && (face == null || faceKey != fk)) { face?.close(); face = null; face = makeFace(); faceKey = fk }
            val gk = "$gpu"
            if (handActive && (gesture == null || gestureKey != gk)) { gesture?.close(); gesture = null; gesture = makeGesture(); gestureKey = gk }
            main.execute { status = null }
        } catch (t: Throwable) {
            PrismLog.i("ERR ai init ${t.javaClass.simpleName} ${t.message}")
            main.execute { status = T("เปิดระบบ AI ไม่ได้ในเครื่องนี้") }
        }
    }

    private fun base(path: String, gpu: Boolean) = BaseOptions.builder().setModelAssetPath(path).setDelegate(if (gpu) Delegate.GPU else Delegate.CPU).build()

    private fun <T> gpuThenCpu(want: Boolean, make: (Boolean) -> T): Pair<T, Boolean> {
        if (!want) return make(false) to false
        return try { make(true) to true } catch (t: Throwable) {
            PrismLog.i("AI gpu delegate failed: ${t.stackTraceToString().take(1500)}"); make(false) to false
        }
    }

    // CPU on purpose: with the GPU delegate MediaPipe 1.2 fails to read its own confidence mask
    // ("Expected buffer size 98304 got: 196608", tablet 2026-11-24). The model is tiny (256 px), CPU is fast enough.
    private fun makeSegmenter(mode: RunningMode) = run { val gpu = false
        ImageSegmenter.createFromOptions(
            ctx, ImageSegmenter.ImageSegmenterOptions.builder().setBaseOptions(base("models/selfie_segmenter.tflite", gpu))
                .setRunningMode(mode).setOutputConfidenceMasks(true).setOutputCategoryMask(false).build(),
        ).also { PrismLog.i("AI segmenter ready gpu=$gpu mode=$mode") }
    }

    private fun makeFace(): FaceLandmarker {
        val (f, onGpu) = gpuThenCpu(gpu) { g ->
            FaceLandmarker.createFromOptions(
                ctx, FaceLandmarker.FaceLandmarkerOptions.builder().setBaseOptions(base("models/face_landmarker.task", g))
                    .setRunningMode(RunningMode.VIDEO).setNumFaces(maxFaces).setOutputFaceBlendshapes(true)
                    .setMinFaceDetectionConfidence(.5f).setMinFacePresenceConfidence(.5f).setMinTrackingConfidence(.5f).build(),
            )
        }
        PrismLog.i("AI face ready gpu=$onGpu faces=$maxFaces")
        main.execute { faceGpu = onGpu }
        return f
    }

    private fun makeGesture(): GestureRecognizer {
        val (g, onGpu) = gpuThenCpu(gpu) { gp ->
            GestureRecognizer.createFromOptions(
                ctx, GestureRecognizer.GestureRecognizerOptions.builder().setBaseOptions(base("models/gesture_recognizer.task", gp))
                    .setRunningMode(RunningMode.VIDEO).setNumHands(2).setMinHandDetectionConfidence(.5f).setMinTrackingConfidence(.5f).build(),
            )
        }
        PrismLog.i("AI hands ready gpu=$onGpu")
        main.execute { handGpu = onGpu }
        return g
    }

    // ───────────────────────────── live frames ─────────────────────────────

    private var frameBitmap: Bitmap? = null
    private var errors = 0
    private var msSum = 0L
    private var msN = 0
    /** test hook: save the next picture the AI sees */
    fun dumpFrame(f: java.io.File) = worker.execute {
        frameBitmap?.let { b -> f.outputStream().use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }; PrismLog.i("AIDUMP ${b.width}x${b.height} → ${f.name}") }
    }

    private fun onFrame(buf: ByteBuffer, w: Int, h: Int) {
        worker.execute {
            val t0 = SystemClock.elapsedRealtime()
            try {
                // RGBA read-back → Bitmap (same byte order as ARGB_8888 in memory); the raw-buffer path was rejected
                // by MediaPipe 1.2 ("Expected buffer size …") on the tablet
                val bmp = frameBitmap?.takeIf { it.width == w && it.height == h } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { frameBitmap = it }
                buf.position(0); bmp.copyPixelsFromBuffer(buf)
                val img = BitmapImageBuilder(bmp).build()
                val ts = SystemClock.uptimeMillis()
                frameNo++
                if (effectiveBg != 0) seg?.let { s ->
                    val r = s.segmentForVideo(img, ts)
                    val masks = r.confidenceMasks().orElse(null)
                    if (masks != null && masks.isNotEmpty()) {
                        val m = masks.last()
                        val fb = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder()).asFloatBuffer()
                        val mw = m.width; val mh = m.height
                        val out = ByteBuffer.allocateDirect(mw * mh)
                        for (i in 0 until mw * mh) out.put((fb.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte())
                        out.position(0)
                        cam.fx.setMask(out, mw, mh)
                    }
                }
                if (faceActive) face?.let { f ->
                    val r = f.detectForVideo(img, ts)
                    val shapes = r.faceBlendshapes().orElse(null)
                    val list = r.faceLandmarks().mapIndexed { i, lm ->
                        val pts = FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { k, p -> a[k * 2] = p.x(); a[k * 2 + 1] = p.y() } }
                        val bs = shapes?.getOrNull(i)?.associate { it.categoryName() to it.score() } ?: emptyMap()
                        pts to bs
                    }
                    Tracking.feedFaces(list)
                    if (list.size != facesSeen) main.execute { facesSeen = list.size }
                } else if (Tracking.faces.isNotEmpty() && SystemClock.uptimeMillis() > Tracking.fakeFaceUntil) Tracking.faces = emptyList()
                if (handActive) gesture?.let { g ->
                    val r = g.recognizeForVideo(img, ts)
                    val hands = r.landmarks().mapIndexed { i, lm ->
                        val pts = FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { k, p -> a[k * 2] = p.x(); a[k * 2 + 1] = p.y() } }
                        // MediaPipe names hands as if the picture were mirrored; ours is not
                        val label = r.handedness().getOrNull(i)?.firstOrNull()?.categoryName() ?: "Right"
                        val top = r.gestures().getOrNull(i)?.firstOrNull()
                        val score = top?.score() ?: 0f
                        Tracking.Hand(pts, label == "Left", if (score > .55f) gestureId(top?.categoryName()) else "", score, 0L)
                    }
                    Tracking.feedHands(hands)
                    if (hands.size != handsSeen) main.execute { handsSeen = hands.size }
                    if (peace) {
                        val v = hands.any { it.gesture == "peace" && it.score > .65f }
                        peaceStreak = if (v) peaceStreak + 1 else 0
                        val now = SystemClock.elapsedRealtime()
                        if (peaceStreak >= 3 && now - lastPeace > 4500) {
                            lastPeace = now; peaceStreak = 0
                            PrismLog.i("AI peace sign → photo")
                            main.execute { cam.peaceShot() }
                        }
                    }
                } else if (Tracking.hands.isNotEmpty() && SystemClock.uptimeMillis() > Tracking.fakeUntil) run { Tracking.hands = emptyList(); Tracking.pair = "" }
                onTracked?.invoke()
            } catch (t: Throwable) {
                if (errors++ < 2) PrismLog.i("ERR ai frame ${t.stackTraceToString().take(1200)}")
            } finally {
                cam.fx.analysisBusy = false
                val dt = SystemClock.elapsedRealtime() - t0
                msSum += dt; msN++
                if (msN >= 15) { val avg = (msSum / msN).toInt(); msSum = 0; msN = 0; main.execute { aiMs = avg } }
                if (frameNo % 60 == 1L) PrismLog.i("AI frame ${w}x$h in $dt ms (bg=$effectiveBg face=$faceActive faces=${Tracking.faces.size} hands=$handActive n=${Tracking.hands.size})")
            }
        }
    }

    // ───────────────────────────── photos ─────────────────────────────

    /** Person mask for a full-resolution still (camera space), for the GPU photo pass: bytes + [w, h]. Null = no AI background. */
    @Synchronized
    fun photoMask(photo: Bitmap): Pair<ByteBuffer, IntArray>? {
        if (effectiveBg == 0) return null
        return try {
            if (segImage == null) segImage = makeSegmenter(RunningMode.IMAGE)
            val k = 512f / maxOf(photo.width, photo.height)
            val small = Bitmap.createScaledBitmap(photo, (photo.width * k).toInt().coerceAtLeast(1), (photo.height * k).toInt().coerceAtLeast(1), true)
            val r = segImage!!.segment(BitmapImageBuilder(small).build())
            small.recycle()
            val m = r.confidenceMasks().orElse(null)?.lastOrNull() ?: return null
            val fb = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder()).asFloatBuffer()
            val bytes = ByteBuffer.allocateDirect(m.width * m.height)
            for (i in 0 until m.width * m.height) bytes.put((fb.get(i).coerceIn(0f, 1f) * 255f).toInt().toByte())
            bytes.position(0)
            bytes to intArrayOf(m.width, m.height)
        } catch (t: Throwable) { PrismLog.i("ERR photo mask ${t.message}"); null }
    }

    fun release() {
        worker.execute { seg?.close(); face?.close(); gesture?.close(); segImage?.close() }
        worker.shutdown()
    }

    companion object {
        val backgrounds = listOf("prism", "night", "sunset", "mint", "studio")
        /** MediaPipe gesture names → ours */
        fun gestureId(name: String?) = when (name) {
            "Closed_Fist" -> "fist"; "Open_Palm" -> "open"; "Pointing_Up" -> "point"; "Thumb_Down" -> "thumbDown"
            "Thumb_Up" -> "thumbUp"; "Victory" -> "peace"; "ILoveYou" -> "love"; else -> ""
        }
        val gestures = listOf("open", "fist", "point", "peace", "thumbUp", "thumbDown", "love")

        fun builtInBackground(id: String, w: Int = 1280, h: Int = 960): Bitmap {
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            val c = Canvas(b)
            val cols = when (id) {
                "night" -> intArrayOf(0xFF0B1030.toInt(), 0xFF2A1B5C.toInt(), 0xFF6B2E7A.toInt())
                "sunset" -> intArrayOf(0xFFFFB27A.toInt(), 0xFFFF7EA8.toInt(), 0xFF7A5CFF.toInt())
                "mint" -> intArrayOf(0xFFB3F2E6.toInt(), 0xFF7FE3D0.toInt(), 0xFF6CC7FF.toInt())
                "studio" -> intArrayOf(0xFF3A3F47.toInt(), 0xFF22252B.toInt(), 0xFF111214.toInt())
                else -> intArrayOf(0xFFBAD4FF.toInt(), 0xFFD6BFFF.toInt(), 0xFFFFC2DE.toInt())
            }
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), cols, null, Shader.TileMode.CLAMP) })
            // soft light pool, like the Prism background
            c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), Paint().apply {
                shader = RadialGradient(w * .3f, h * .25f, maxOf(w, h) * .6f, intArrayOf(0x55FFFFFF, 0x00FFFFFF), null, Shader.TileMode.CLAMP)
            })
            return b
        }
    }
}
