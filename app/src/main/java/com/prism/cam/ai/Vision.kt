package com.prism.cam.ai

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
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
import com.prism.cam.effects.Scene
import com.prism.cam.i18n.T
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors

/**
 * On-device AI (MediaPipe, bundled models, no internet, no Google services):
 * - person mask → background blur / replace (live on the GPU, and on photos);
 * - face landmarks → face stickers;
 * - hand gesture → peace sign ✌️ = photo with a 3 s countdown.
 * Works on the small upright "analysis" copy of each frame the GPU pipeline reads back (~256 px, ~15 fps); only the
 * parts that are switched on run. Everything AI runs on one background thread.
 */
class Vision(private val ctx: Context, private val cam: PrismCamera) {
    // ── what is on (UI state)
    /** 0 none · 1 blur · 2 replace */
    var bg by mutableIntStateOf(0); private set
    var blur by mutableFloatStateOf(.75f); private set
    /** id of the replacement background (built-in gradient or "photo") */
    var bgId by mutableStateOf("prism"); private set
    var faceSticker by mutableStateOf<String?>(null); private set
    var peace by mutableStateOf(false); private set
    /** "portrait" mode forces blur on while it is selected */
    var portrait by mutableStateOf(false); private set
    var status by mutableStateOf<String?>(null); private set

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "prism-ai").apply { priority = Thread.NORM_PRIORITY } }
    private var seg: ImageSegmenter? = null
    private var face: FaceLandmarker? = null
    private var gesture: GestureRecognizer? = null
    private var segImage: ImageSegmenter? = null
    private var faceImage: FaceLandmarker? = null
    private var bgPicture: Bitmap? = null
    private var peaceStreak = 0
    private var lastPeace = 0L
    private var frameNo = 0L
    private val main = ContextCompat.getMainExecutor(ctx)

    init {
        cam.clearAi = { useBg(0); useFaceSticker(null); usePeace(false) }
        cam.photoBackground = { photoBackgroundStep() }
    }

    private val effectiveBg get() = if (portrait) 1 else bg

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
    fun useFaceSticker(id: String?) { faceSticker = id; Scene.faceSticker = id; if (id == null) { Scene.faces = emptyList(); Scene.touch() }; apply() }
    fun usePeace(on: Boolean) { peace = on; peaceStreak = 0; apply() }
    fun usePortrait(on: Boolean) { portrait = on; apply() }

    /** Turn the GPU analysis + effect pipeline on/off to match what is switched on. */
    private fun apply() {
        val fx = cam.fx
        fx.bgMode = effectiveBg
        fx.blurAmount = blur
        if (effectiveBg == 2 && bgPicture == null) { bgPicture = builtInBackground(bgId); fx.setBackground(bgPicture) }
        if (effectiveBg == 0) fx.setMask(null, 0, 0)
        val needFrames = effectiveBg != 0 || faceSticker != null || peace
        fx.analysisBusy = false
        fx.analysisListener = if (needFrames) ({ buf, w, h, _ -> onFrame(buf, w, h) }) else null
        cam.fxReason("ai", needFrames)
        if (needFrames) worker.execute { ensureModels() }
    }

    private fun ensureModels() {
        try {
            if (effectiveBg != 0 && seg == null) seg = makeSegmenter(RunningMode.VIDEO)
            if (faceSticker != null && face == null) face = makeFace(RunningMode.VIDEO)
            if (peace && gesture == null) gesture = makeGesture()
            main.execute { status = null }
        } catch (t: Throwable) {
            PrismLog.i("ERR ai init ${t.javaClass.simpleName} ${t.message}")
            main.execute { status = T("เปิดระบบ AI ไม่ได้ในเครื่องนี้") }
        }
    }

    private fun base(path: String, gpu: Boolean) = BaseOptions.builder().setModelAssetPath(path).setDelegate(if (gpu) Delegate.GPU else Delegate.CPU).build()

    private fun <T> gpuThenCpu(make: (Boolean) -> T): T = try { make(true) } catch (t: Throwable) {
        PrismLog.i("AI gpu delegate failed: ${t.stackTraceToString().take(1500)}"); make(false)
    }

    // CPU on purpose: with the GPU delegate MediaPipe 1.2 fails to read its own confidence mask
    // ("Expected buffer size 98304 got: 196608", tablet 2026-11-24). The model is tiny (256 px), CPU is fast enough.
    private fun makeSegmenter(mode: RunningMode) = run { val gpu = false
        ImageSegmenter.createFromOptions(
            ctx, ImageSegmenter.ImageSegmenterOptions.builder().setBaseOptions(base("models/selfie_segmenter.tflite", gpu))
                .setRunningMode(mode).setOutputConfidenceMasks(true).setOutputCategoryMask(false).build(),
        ).also { PrismLog.i("AI segmenter ready gpu=$gpu mode=$mode") }
    }

    private fun makeFace(mode: RunningMode) = gpuThenCpu { gpu ->
        FaceLandmarker.createFromOptions(
            ctx, FaceLandmarker.FaceLandmarkerOptions.builder().setBaseOptions(base("models/face_landmarker.task", gpu))
                .setRunningMode(mode).setNumFaces(3).setMinFaceDetectionConfidence(.5f).setMinTrackingConfidence(.5f).build(),
        ).also { PrismLog.i("AI face ready gpu=$gpu mode=$mode") }
    }

    private fun makeGesture() = gpuThenCpu { gpu ->
        GestureRecognizer.createFromOptions(
            ctx, GestureRecognizer.GestureRecognizerOptions.builder().setBaseOptions(base("models/gesture_recognizer.task", gpu))
                .setRunningMode(RunningMode.VIDEO).setNumHands(2).build(),
        ).also { PrismLog.i("AI gesture ready gpu=$gpu") }
    }

    // ───────────────────────────── live frames ─────────────────────────────

    private var timing = 0L
    private var frameBitmap: Bitmap? = null
    private var errors = 0
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
                if (faceSticker != null) face?.let { f ->
                    val r = f.detectForVideo(img, ts)
                    val faces = r.faceLandmarks().map { lm -> FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { i, p -> a[i * 2] = p.x(); a[i * 2 + 1] = p.y() } } }
                    main.execute { Scene.faces = faces; Scene.touch() }
                }
                if (peace && frameNo % 2 == 0L) gesture?.let { g ->
                    val r = g.recognizeForVideo(img, ts)
                    val v = r.gestures().any { hand -> hand.firstOrNull()?.let { it.categoryName() == "Victory" && it.score() > .65f } == true }
                    peaceStreak = if (v) peaceStreak + 1 else 0
                    val now = SystemClock.elapsedRealtime()
                    if (peaceStreak >= 3 && now - lastPeace > 4500) {
                        lastPeace = now; peaceStreak = 0
                        PrismLog.i("AI peace sign → photo")
                        main.execute { cam.peaceShot() }
                    }
                }
            } catch (t: Throwable) {
                if (errors++ < 2) PrismLog.i("ERR ai frame ${t.stackTraceToString().take(1200)}")
            } finally {
                cam.fx.analysisBusy = false
                val dt = SystemClock.elapsedRealtime() - t0
                if (frameNo % 60 == 1L) PrismLog.i("AI frame ${w}x$h in $dt ms (bg=$effectiveBg face=${faceSticker != null} peace=$peace)")
                timing = dt
            }
        }
    }

    // ───────────────────────────── photos ─────────────────────────────

    /** Background step for the full-resolution photo (runs on the camera io thread). */
    private fun photoBackgroundStep(): ((Bitmap) -> Bitmap)? {
        val mode = effectiveBg
        if (mode == 0) return null
        val strength = blur
        val picture = bgPicture
        return { photo -> composite(photo, mode, strength, picture) }
    }

    @Synchronized
    private fun maskFor(photo: Bitmap): Bitmap? {
        if (segImage == null) segImage = makeSegmenter(RunningMode.IMAGE)
        val long = 512f
        val k = long / maxOf(photo.width, photo.height)
        val small = Bitmap.createScaledBitmap(photo, (photo.width * k).toInt().coerceAtLeast(1), (photo.height * k).toInt().coerceAtLeast(1), true)
        val r = segImage!!.segment(BitmapImageBuilder(small).build())
        small.recycle()
        val m = r.confidenceMasks().orElse(null)?.lastOrNull() ?: return null
        val fb = ByteBufferExtractor.extract(m).order(ByteOrder.nativeOrder()).asFloatBuffer()
        val alpha = Bitmap.createBitmap(m.width, m.height, Bitmap.Config.ALPHA_8)
        val bytes = ByteBuffer.allocate(m.width * m.height)
        for (i in 0 until m.width * m.height) {
            // soft edge like the shader: smoothstep(0.30, 0.70, p)
            val p = ((fb.get(i) - .3f) / .4f).coerceIn(0f, 1f)
            bytes.put((p * p * (3 - 2 * p) * 255f).toInt().toByte())
        }
        bytes.position(0)
        alpha.copyPixelsFromBuffer(bytes)
        return alpha
    }

    private fun composite(photo: Bitmap, mode: Int, strength: Float, picture: Bitmap?): Bitmap {
        val mask = try { maskFor(photo) } catch (t: Throwable) { PrismLog.i("ERR photo mask ${t.message}"); null } ?: return photo
        val w = photo.width; val h = photo.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        // 1) background
        if (mode == 1) {
            val k = (24 - 16 * strength).coerceIn(6f, 24f)  // stronger = smaller copy = softer
            val sw = (w / k).toInt().coerceAtLeast(8); val sh = (h / k).toInt().coerceAtLeast(8)
            val small = Bitmap.createScaledBitmap(photo, sw, sh, true)
            boxBlur(small, 2 + (strength * 3).toInt())
            c.drawBitmap(Bitmap.createScaledBitmap(small, w, h, true), 0f, 0f, null)
            small.recycle()
        } else if (picture != null) {
            drawCover(c, picture, w, h)
        }
        // 2) person on top, cut out with the mask
        val person = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val pc = Canvas(person)
        pc.drawBitmap(photo, 0f, 0f, null)
        pc.drawBitmap(Bitmap.createScaledBitmap(mask, w, h, true), 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_IN) })
        c.drawBitmap(person, 0f, 0f, null)
        person.recycle(); mask.recycle(); photo.recycle()
        return out
    }

    /** Face stickers for a photo: detect faces on the (U-space) photo, then draw. */
    fun photoFaces(photo: Bitmap): List<FloatArray> = synchronized(this) {
        if (faceSticker == null) return emptyList()
        try {
            if (faceImage == null) faceImage = makeFace(RunningMode.IMAGE)
            val k = 768f / maxOf(photo.width, photo.height)
            val small = Bitmap.createScaledBitmap(photo, (photo.width * k).toInt(), (photo.height * k).toInt(), true)
            val r = faceImage!!.detect(BitmapImageBuilder(small).build())
            small.recycle()
            r.faceLandmarks().map { lm -> FloatArray(lm.size * 2).also { a -> lm.forEachIndexed { i, p -> a[i * 2] = p.x(); a[i * 2 + 1] = p.y() } } }
        } catch (t: Throwable) { PrismLog.i("ERR photo faces ${t.message}"); emptyList() }
    }

    fun release() {
        worker.execute { seg?.close(); face?.close(); gesture?.close(); segImage?.close(); faceImage?.close() }
        worker.shutdown()
    }

    companion object {
        val backgrounds = listOf("prism", "night", "sunset", "mint", "studio")

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

        fun drawCover(c: Canvas, pic: Bitmap, w: Int, h: Int) {
            val k = maxOf(w.toFloat() / pic.width, h.toFloat() / pic.height)
            val dw = pic.width * k; val dh = pic.height * k
            c.drawBitmap(pic, null, android.graphics.RectF((w - dw) / 2, (h - dh) / 2, (w + dw) / 2, (h + dh) / 2), Paint(Paint.FILTER_BITMAP_FLAG))
        }

        /** In-place separable box blur, 3 passes (≈ gaussian). Small bitmaps only. */
        fun boxBlur(b: Bitmap, r: Int) {
            val w = b.width; val h = b.height
            val px = IntArray(w * h); b.getPixels(px, 0, w, 0, 0, w, h)
            val tmp = IntArray(w * h)
            repeat(3) {
                blurPass(px, tmp, w, h, r, true)
                blurPass(tmp, px, w, h, r, false)
            }
            b.setPixels(px, 0, w, 0, 0, w, h)
        }

        private fun blurPass(src: IntArray, dst: IntArray, w: Int, h: Int, r: Int, horizontal: Boolean) {
            val n = if (horizontal) w else h
            val lines = if (horizontal) h else w
            val div = 2 * r + 1
            for (l in 0 until lines) {
                var sa = 0; var sr = 0; var sg = 0; var sb = 0
                fun idx(i: Int): Int { val k = i.coerceIn(0, n - 1); return if (horizontal) l * w + k else k * w + l }
                for (i in -r..r) { val p = src[idx(i)]; sa += p ushr 24; sr += (p shr 16) and 255; sg += (p shr 8) and 255; sb += p and 255 }
                for (i in 0 until n) {
                    dst[idx(i)] = ((sa / div) shl 24) or ((sr / div) shl 16) or ((sg / div) shl 8) or (sb / div)
                    val pOut = src[idx(i - r)]; val pIn = src[idx(i + r + 1)]
                    sa += (pIn ushr 24) - (pOut ushr 24); sr += ((pIn shr 16) and 255) - ((pOut shr 16) and 255)
                    sg += ((pIn shr 8) and 255) - ((pOut shr 8) and 255); sb += (pIn and 255) - (pOut and 255)
                }
            }
        }
    }
}
