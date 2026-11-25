package com.prism.cam.effects

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.media.PlaybackParams
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.GLUtils
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.view.Surface
import androidx.camera.core.CameraEffect
import androidx.camera.core.SurfaceOutput
import androidx.camera.core.SurfaceProcessor
import androidx.camera.core.SurfaceRequest
import com.prism.cam.PrismLog
import com.prism.cam.studio.Geometry
import com.prism.cam.studio.LType
import com.prism.cam.studio.RLayer
import com.prism.cam.studio.Tracking
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The GPU studio compositor (CameraX SurfaceProcessor, OpenGL ES 2), the heart of phases 7–11.
 *
 * Every frame: the camera frame (OES texture) and every layer of the current scene — images, GIFs, videos, text,
 * stickers, shapes, frames, drawings, face / hand effects — are drawn as textured quads into one **canvas** (the
 * picture as the user sees it, x → right, y → down). Each layer gets crop, flip, rounded / circle mask, border, soft
 * shadow, colour look + adjustments, chroma key, opacity, blend mode, motion presets and face / hand attachment
 * (positions from [Tracking], computed here so they move at camera frame rate). The camera layer also gets the AI
 * background blur / replace. The canvas is then copied to each output (preview and video encoder).
 *
 * Front camera: PreviewView mirrors the preview after us, so the canvas is copied mirrored and the camera layer is
 * drawn mirrored — on screen both come out right, and video (MIRROR_MODE_ON_FRONT_ONLY) matches the screen.
 * Frames never leave the GPU except the small AI copy and photos. All GL work runs on one thread ("prism-gl").
 */
class GlFx : SurfaceProcessor {
    // ───────── settings written from the UI thread, read on the GL thread
    /** 0 none · 1 blur · 2 replace */
    @Volatile var bgMode = 0
    @Volatile var blurAmount = 1f
    /** sensor → upright rotation (CameraInfo.getSensorRotationDegrees(display rotation)) */
    @Volatile var uprightDeg = 0
    /** front camera in use */
    @Volatile var front = false
    /** the scene (immutable snapshot, back to front) */
    @Volatile var layers: List<RLayer> = emptyList()
    /** analysis frames wanted (AI on) */
    @Volatile var analysisListener: ((ByteBuffer, Int, Int, Long) -> Unit)? = null
    @Volatile var analysisBusy = false
    /** tuning view: draw the tracked face outline + hand skeleton */
    @Volatile var showMesh = false
    /** light mode: smaller canvas */
    @Volatile var lite = false

    // ───────── stats (speed panel)
    @Volatile var statDrawMs = 0f; private set
    @Volatile var statFps = 0f; private set
    @Volatile var canvasW = 0; private set
    @Volatile var canvasH = 0; private set
    @Volatile var gpuName = ""; private set

    /** layer pixels waiting for upload: id → (bitmap, key) */
    val pending = ConcurrentHashMap<Long, Pair<Bitmap, String>>()
    /** keys of the textures on the GPU (the content pump skips work already done) */
    val uploaded = ConcurrentHashMap<Long, String>()
    /** where each attached / animated layer was drawn last frame (canvas px), for touch + photos */
    val drawn = ConcurrentHashMap<Long, Geometry.Quad>()
    /** video positions (ms) for the photo pass / UI */
    val videoPos = ConcurrentHashMap<Long, Int>()

    private val maskPending = AtomicBoolean(false)
    @Volatile private var maskBytes: ByteBuffer? = null
    @Volatile private var maskW = 0
    @Volatile private var maskH = 0
    private val bgPending = AtomicBoolean(false)
    @Volatile private var bgBitmap: Bitmap? = null

    fun setMask(bytes: ByteBuffer?, w: Int, h: Int) { maskBytes = bytes; maskW = w; maskH = h; maskPending.set(true) }
    fun setBackground(b: Bitmap?) { bgBitmap = b; bgPending.set(true) }

    // ───────── thread
    private val thread = HandlerThread("prism-gl").apply { start(); priority = Thread.MAX_PRIORITY - 1 }
    private val handler = Handler(thread.looper)
    val executor = Executor { handler.post(it) }

    // ───────── EGL
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var config: EGLConfig? = null
    private var pbuffer: EGLSurface = EGL14.EGL_NO_SURFACE
    private var ready = false

    private class Input(val st: SurfaceTexture, val tex: Int, val surface: Surface, val w: Int, val h: Int)
    private class Output(val out: SurfaceOutput, val surface: Surface, var egl: EGLSurface, val w: Int, val h: Int) {
        var tries = 0
        val texM = FloatArray(16)
        val b2u = Matrix()
        var uw = w.toFloat(); var uh = h.toFloat()
        val preview = (out.targets and CameraEffect.PREVIEW) != 0
    }
    private class Tex(val id: Int, val w: Int, val h: Int, val key: String)
    private class Video(val id: Long, val media: String, val player: MediaPlayer, val st: SurfaceTexture, val tex: Int, val surface: Surface) {
        val m = FloatArray(16)
        var w = 16; var h = 9
        @Volatile var fresh = false
        var ready = false
    }

    private var input: Input? = null
    private val outputs = LinkedHashMap<SurfaceOutput, Output>()
    private val stMat = FloatArray(16)
    private val textures = HashMap<Long, Tex>()
    private val videos = HashMap<Long, Video>()

    // programs
    private var progLayer2D = 0
    private var progLayerOES = 0
    private var progOut = 0
    private var progAnalysis = 0
    private var progBlur = 0
    private var progGlow = 0
    // textures / targets
    private var texMask = 0
    private var texBg = 0
    private var texWhite = 0
    private var hasMask = false
    private var hasBg = false
    private var fboCanvas = 0; private var texCanvas = 0
    private var aW = 0; private var aH = 0
    private var fboA = 0; private var texA = 0
    private var fboB1 = 0; private var texB1 = 0
    private var fboB2 = 0; private var texB2 = 0
    private var bW = 0; private var bH = 0
    private var readBuf: ByteBuffer? = null
    private var lastRead = 0L
    private var frames = 0
    private var fpsSince = 0L
    private val t0 = SystemClock.elapsedRealtime()
    private val camToTex = Matrix()

    private val vbuf: FloatBuffer = ByteBuffer.allocateDirect(4 * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val quad: FloatBuffer = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        .let { ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(it).apply { position(0) } }

    init { handler.post { setupEgl() } }

    // ───────────────────────────── SurfaceProcessor ─────────────────────────────

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            if (!ready) { request.willNotProvideSurface(); return@post }
            val tex = newOes()
            val st = SurfaceTexture(tex)
            val res = request.resolution
            st.setDefaultBufferSize(res.width, res.height)
            val surface = Surface(st)
            val inp = Input(st, tex, surface, res.width, res.height)
            request.provideSurface(surface, executor) {
                st.setOnFrameAvailableListener(null)
                surface.release(); st.release()
                GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
                if (input === inp) input = null
            }
            st.setOnFrameAvailableListener({ draw() }, handler)
            input = inp
            PrismLog.i("FX input ${res.width}x${res.height}")
        }
    }

    override fun onOutputSurface(out: SurfaceOutput) {
        handler.post {
            if (!ready) { out.close(); return@post }
            val surface = out.getSurface(executor) { _ ->
                outputs.remove(out)?.let { o -> if (o.egl != EGL14.EGL_NO_SURFACE) { EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context); EGL14.eglDestroySurface(display, o.egl) } }
                out.close()
            }
            // The window may still be held by the previous camera session for a moment (EGL_BAD_ALLOC on Huawei):
            // the EGL surface is created lazily in draw() and retried every frame until it attaches.
            val o = Output(out, surface, EGL14.EGL_NO_SURFACE, out.size.width, out.size.height)
            outputs[out] = o
            computeB2U(o)
            PrismLog.i("FX output ${o.w}x${o.h} targets=${out.targets}")
        }
    }

    /** buffer pixel → U (0..1): buffer → sensor → rotate upright → normalise to the rotated buffer's own extent. */
    private fun computeB2U(o: Output) {
        val s2b = try { o.out.sensorToBufferTransform } catch (_: Throwable) { null }
        val m = Matrix()
        if (s2b == null || !s2b.invert(m)) m.reset()
        m.postRotate(uprightDeg.toFloat())
        val pts = floatArrayOf(0f, 0f, o.w.toFloat(), 0f, 0f, o.h.toFloat(), o.w.toFloat(), o.h.toFloat())
        m.mapPoints(pts)
        val xs = floatArrayOf(pts[0], pts[2], pts[4], pts[6]); val ys = floatArrayOf(pts[1], pts[3], pts[5], pts[7])
        val minX = xs.min(); val minY = ys.min()
        val sw = (xs.max() - minX).coerceAtLeast(1f); val sh = (ys.max() - minY).coerceAtLeast(1f)
        m.postTranslate(-minX, -minY)
        m.postScale(1f / sw, 1f / sh)
        o.b2u.set(m)
        // upright size in OUTPUT pixels (sensor units cancel out): the buffer's own size, turned
        val turned = (uprightDeg / 90) % 2 == 1 && sw / sh < 1f != o.w.toFloat() / o.h < 1f
        o.uw = if (turned) o.h.toFloat() else o.w.toFloat(); o.uh = if (turned) o.w.toFloat() else o.h.toFloat()
        if (sw / sh > 1f != o.uw / o.uh > 1f) { val t = o.uw; o.uw = o.uh; o.uh = t }
    }

    fun refreshGeometry() = handler.post { outputs.values.forEach { computeB2U(it) } }

    fun release() {
        handler.post {
            videos.values.forEach { freeVideo(it) }; videos.clear()
            outputs.values.forEach { if (it.egl != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, it.egl); it.out.close() }
            outputs.clear()
            if (display != EGL14.EGL_NO_DISPLAY) {
                EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
                EGL14.eglDestroySurface(display, pbuffer)
                EGL14.eglDestroyContext(display, context)
                EGL14.eglTerminate(display)
            }
            thread.quitSafely()
        }
    }

    // ───────────────────────────── frame ─────────────────────────────

    private fun draw() {
        val inp = input ?: return
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) return
        try { inp.st.updateTexImage() } catch (_: Throwable) { return }
        inp.st.getTransformMatrix(stMat)
        if (outputs.isEmpty()) return
        val tStart = SystemClock.elapsedRealtimeNanos()
        val ref = outputs.values.firstOrNull { it.preview } ?: outputs.values.first()
        ref.out.updateTransformMatrix(ref.texM, stMat)
        buildCamToTex(ref)
        uploadPending()
        val scene = layers
        syncVideos(scene)

        // canvas size = the upright output, long side ≤ 1920 (≤ 1280 in light mode)
        val cap = if (lite) 1280f else 1920f
        val k = min(1f, cap / max(ref.uw, ref.uh))
        val cw = (ref.uw * k).toInt().coerceAtLeast(16); val ch = (ref.uh * k).toInt().coerceAtLeast(16)
        if (cw != canvasW || ch != canvasH || fboCanvas == 0) {
            freeFbo(fboCanvas, texCanvas)
            makeFbo(cw, ch).let { fboCanvas = it.first; texCanvas = it.second }
            canvasW = cw; canvasH = ch
            PrismLog.i("FX canvas ${cw}x$ch")
        }
        com.prism.cam.studio.Tracking.aspect = ref.uh / ref.uw

        val needAnalysis = bgMode == 1 || analysisListener != null
        if (needAnalysis) renderAnalysis(inp, ref)
        val blurReady = bgMode == 1 && fboA != 0
        if (blurReady) renderBlur()

        compose(scene, inp.tex, cw.toFloat(), ch.toFloat(), fboCanvas, cw, ch, photo = null, blurReady)

        val ts = inp.st.timestamp
        for (o in outputs.values) {
            if (o.egl == EGL14.EGL_NO_SURFACE) {
                o.egl = EGL14.eglCreateWindowSurface(display, config, o.surface, intArrayOf(EGL14.EGL_NONE), 0)
                o.tries++
                if (o.egl == EGL14.EGL_NO_SURFACE) {
                    if (o.tries == 1 || o.tries % 30 == 0) PrismLog.i("FX window not ready (try ${o.tries}) 0x${Integer.toHexString(EGL14.eglGetError())}")
                    continue
                }
                PrismLog.i("FX window attached after ${o.tries} tries ${o.w}x${o.h}")
            }
            if (!EGL14.eglMakeCurrent(display, o.egl, o.egl, context)) continue
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, o.w, o.h)
            GLES20.glDisable(GLES20.GL_BLEND)
            GLES20.glUseProgram(progOut)
            bindQuad(progOut)
            GLES20.glUniformMatrix3fv(loc(progOut, "uBufToU"), 1, false, bufUvToU(o), 0)
            GLES20.glUniform1f(loc(progOut, "uMirror"), if (front) 1f else 0f)
            val cam = scene.firstOrNull { it.type == LType.Camera }
            GLES20.glUniform1f(loc(progOut, "uVignette"), cam?.let { Looks.of(it.look).vignette } ?: 0f)
            GLES20.glUniform1f(loc(progOut, "uGrain"), cam?.let { Looks.of(it.look).grain } ?: 0f)
            GLES20.glUniform1f(loc(progOut, "uTime"), ((SystemClock.elapsedRealtime() - t0) % 100000) / 1000f)
            bindTex(0, GLES20.GL_TEXTURE_2D, texCanvas, progOut, "sCanvas")
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, o.egl, ts)
            if (!EGL14.eglSwapBuffers(display, o.egl)) PrismLog.i("FX swap failed 0x${Integer.toHexString(EGL14.eglGetError())}")
        }
        val dt = (SystemClock.elapsedRealtimeNanos() - tStart) / 1e6f
        statDrawMs = statDrawMs * .9f + dt * .1f
        frames++
        val now = SystemClock.elapsedRealtime()
        if (fpsSince == 0L) fpsSince = now
        if (now - fpsSince >= 1000) { statFps = frames * 1000f / (now - fpsSince); frames = 0; fpsSince = now }
    }


    // ───────────────────────────── composing ─────────────────────────────

    private class PhotoSrc(val tex: Int, val u0: Float, val v0: Float, val u1: Float, val v1: Float)

    /**
     * Draw the scene into [fbo] (w × h px) using a canvas of [cw] × [ch] units. In the photo pass [photo] replaces the
     * live camera texture (photo covers camera space u0..u1 × v0..v1).
     */
    private fun compose(scene: List<RLayer>, camTex: Int, cw: Float, ch: Float, fbo: Int, w: Int, h: Int, photo: PhotoSrc?, blurReady: Boolean, scale: Float = 1f) {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glViewport(0, 0, w, h)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glEnable(GLES20.GL_BLEND)
        val cam = scene.firstOrNull { it.type == LType.Camera }
        val time = (SystemClock.elapsedRealtime() - t0) / 1000f
        for (l in scene) {
            // face / hand art drawn straight from the tracked points
            when (l.type) {
                LType.FacePaint -> { textures[l.id]?.let { drawFacePaint(l, it.id, cam, cw, ch) }; continue }
                LType.FaceFx -> { drawFaceFx(l, cam, cw, ch, time); continue }
                LType.HandFx -> { drawHandFx(l, cam, cw, ch, time); continue }
                else -> {}
            }
            val oes: Boolean
            val tex: Int
            when (l.type) {
                LType.Camera -> { oes = photo == null; tex = photo?.tex ?: camTex }
                LType.Video, LType.Screen -> {
                    val v = videos[l.id] ?: continue
                    if (!v.ready) continue
                    oes = true; tex = v.tex
                }
                else -> {
                    val t = textures[l.id] ?: continue
                    oes = false; tex = t.id
                }
            }
            // ── placement: own transform (or its keyframes), or the face / hand it is attached to, then motion
            var q = quadOf(if (l.keys.size >= 12) Keys.at(l, time) else l, cw, ch)
            var alpha = l.opacity
            if (l.attachKind.isNotEmpty()) {
                val p = Tracking.place(l, cam, front, cw, ch, q.w)
                if (p == null || p.alpha <= 0f) { drawn.remove(l.id); continue }
                q = Geometry.Quad(p.cx, p.cy, p.w, p.w * (q.h / q.w.coerceAtLeast(1e-3f)), p.rot, p.sx)
                alpha *= p.alpha
            }
            if (l.anim.isNotEmpty()) {
                val a = Motion.at(l.anim, time * l.animSpeed, l.id)
                q = Geometry.Quad(q.cx + a.dx * cw, q.cy + a.dy * ch, q.w * a.scale, q.h * a.scale, q.rot + a.rot, q.sx)
                alpha *= a.alpha
            }
            if (photo == null) drawn[l.id] = q
            if (alpha <= .002f) continue
            drawLayer(l, q, alpha, oes, tex, cw, ch, photo, blurReady && l.type == LType.Camera, scale)
        }
        if (showMesh && photo == null) drawMeshOverlay(cam, cw, ch)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ───────────────────────────── face / hand art ─────────────────────────────

    private val meshV: FloatBuffer = ByteBuffer.allocateDirect(478 * 8 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private var meshI: java.nio.ShortBuffer? = null
    private val meshXY = FloatArray(478 * 2)

    private fun facesFor(who: String): List<Tracking.Face> {
        val all = Tracking.faces
        return if (who == "all" || who.isEmpty()) all else listOfNotNull(all.getOrNull(who.toIntOrNull() ?: 0))
    }

    /** face points → canvas px (through the camera layer's place, crop and mirror) */
    private fun faceToCanvas(f: Tracking.Face, cam: RLayer?, cw: Float, ch: Float, n: Int = 468) {
        for (i in 0 until n) {
            val p = Geometry.camToCanvas(cam, front, cw, ch, f.pts[i * 2], f.pts[i * 2 + 1])
            meshXY[i * 2] = p[0]; meshXY[i * 2 + 1] = p[1]
        }
    }

    /** the paint texture wrapped onto each face (desktop facepaint.js): mesh triangles facing the camera only */
    private fun drawFacePaint(l: RLayer, tex: Int, cam: RLayer?, cw: Float, ch: Float) {
        if (!com.prism.cam.studio.FaceArt.ready) return
        val faces = facesFor(l.attachWho); if (faces.isEmpty()) return
        val uv = com.prism.cam.studio.FaceArt.UV; val tri = com.prism.cam.studio.FaceArt.TRI
        val ib = meshI ?: ByteBuffer.allocateDirect(tri.size * 2).order(ByteOrder.nativeOrder()).asShortBuffer().also { meshI = it }
        GLES20.glUseProgram(progLayer2D)
        setLayerUniforms(progLayer2D, l, Geometry.Quad(0f, 0f, 1e5f, 1e5f, 0f), l.opacity, 0f)
        GLES20.glUniform1f(loc(progLayer2D, "uPx"), 1f)
        GLES20.glUniform1f(loc(progLayer2D, "uRadius"), 0f); GLES20.glUniform1f(loc(progLayer2D, "uCircle"), 0f); GLES20.glUniform1f(loc(progLayer2D, "uBorder"), 0f)
        GLES20.glUniform1i(loc(progLayer2D, "uBgMode"), 0)
        blendFor(l.blend)
        bindTex(0, GLES20.GL_TEXTURE_2D, tex, progLayer2D, "sTex")
        bindTex(1, GLES20.GL_TEXTURE_2D, texWhite, progLayer2D, "sMask")
        bindTex(2, GLES20.GL_TEXTURE_2D, texWhite, progLayer2D, "sBlur")
        bindTex(3, GLES20.GL_TEXTURE_2D, texWhite, progLayer2D, "sBg")
        for (f in faces) {
            if (f.pts.size < 468 * 2) continue
            faceToCanvas(f, cam, cw, ch)
            meshV.position(0)
            for (i in 0 until 468) {
                meshV.put(meshXY[i * 2] / cw * 2f - 1f).put(1f - meshXY[i * 2 + 1] / ch * 2f).put(.5f).put(.5f).put(uv[i * 2]).put(uv[i * 2 + 1]).put(0f).put(0f)
            }
            // keep the triangles that face the same way as most of the face (hides the far side when the head turns)
            var total = 0f
            val areas = FloatArray(tri.size / 3)
            for (t in areas.indices) {
                val a = tri[t * 3].toInt(); val b = tri[t * 3 + 1].toInt(); val c = tri[t * 3 + 2].toInt()
                val ar = (meshXY[b * 2] - meshXY[a * 2]) * (meshXY[c * 2 + 1] - meshXY[a * 2 + 1]) - (meshXY[c * 2] - meshXY[a * 2]) * (meshXY[b * 2 + 1] - meshXY[a * 2 + 1])
                areas[t] = ar; total += ar
            }
            ib.position(0)
            var n = 0
            for (t in areas.indices) if (areas[t] * total > 0f) { ib.put(tri[t * 3]).put(tri[t * 3 + 1]).put(tri[t * 3 + 2]); n += 3 }
            ib.position(0)
            for (a in 0 until 4) { meshV.position(a * 2); GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 32, meshV); GLES20.glEnableVertexAttribArray(a) }
            GLES20.glDrawElements(GLES20.GL_TRIANGLES, n, GLES20.GL_UNSIGNED_SHORT, ib)
        }
    }

    private fun blendFor(b: String) = when (b) {
        "screen" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
        "add" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
        "multiply" -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    private val segV: FloatBuffer = ByteBuffer.allocateDirect(4 * 4 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

    /** a soft glowing capsule from (x0,y0) to (x1,y1), [w] px core width, premultiplied additive-ish */
    private fun segment(x0: Float, y0: Float, x1: Float, y1: Float, w: Float, r: Float, g: Float, b: Float, a: Float, cw: Float, ch: Float) {
        val dx = x1 - x0; val dy = y1 - y0; val len = kotlin.math.hypot(dx, dy)
        val ux = if (len > 1e-3f) dx / len else 1f; val uy = if (len > 1e-3f) dy / len else 0f
        val nx = -uy; val ny = ux
        val half = len / 2; val ext = w * 2.2f
        val cx = (x0 + x1) / 2; val cy = (y0 + y1) / 2
        val along = half + ext
        segV.position(0)
        for ((sa, sn) in arrayOf(-1f to -1f, 1f to -1f, -1f to 1f, 1f to 1f)) {
            val px = cx + ux * along * sa + nx * ext * sn; val py = cy + uy * along * sa + ny * ext * sn
            segV.put(px / cw * 2f - 1f).put(1f - py / ch * 2f).put(along * sa).put(ext * sn)
        }
        segV.position(0); GLES20.glVertexAttribPointer(0, 2, GLES20.GL_FLOAT, false, 16, segV); GLES20.glEnableVertexAttribArray(0)
        segV.position(2); GLES20.glVertexAttribPointer(1, 2, GLES20.GL_FLOAT, false, 16, segV); GLES20.glEnableVertexAttribArray(1)
        GLES20.glUniform1f(loc(progGlow, "uHalf"), half)
        GLES20.glUniform1f(loc(progGlow, "uW"), w)
        GLES20.glUniform4f(loc(progGlow, "uColor"), r, g, b, a)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun rgb(c: Int) = floatArrayOf(((c shr 16) and 255) / 255f, ((c shr 8) and 255) / 255f, (c and 255) / 255f)

    private val BONES = intArrayOf(0, 1, 1, 2, 2, 3, 3, 4, 0, 5, 5, 6, 6, 7, 7, 8, 5, 9, 9, 10, 10, 11, 11, 12, 9, 13, 13, 14, 14, 15, 15, 16, 13, 17, 0, 17, 17, 18, 18, 19, 19, 20)
    private val trails = HashMap<String, ArrayDeque<FloatArray>>()

    private fun handsFor(who: String) = Tracking.hands.filter { h -> when (who) { "left" -> !h.right; "right" -> h.right; else -> true } }

    /** neon hands, finger trails, air-drawing cursor (desktop handfx.js) */
    private fun drawHandFx(l: RLayer, cam: RLayer?, cw: Float, ch: Float, time: Float) {
        GLES20.glUseProgram(progGlow)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val c = rgb(l.tint); val a = l.opacity
        val now = SystemClock.uptimeMillis()
        if (l.preset == "lightning" || l.preset == "ball") { drawTwoHandFx(l, cam, cw, ch, time); return }
        if (l.preset == "orb") { drawOrbs(l, cam, cw, ch, time); return }
        for ((hi, h) in handsFor(l.attachWho).withIndex()) {
            val pts = FloatArray(42)
            for (i in 0 until 21) { val p = Geometry.camToCanvas(cam, front, cw, ch, h.pts[i * 2], h.pts[i * 2 + 1]); pts[i * 2] = p[0]; pts[i * 2 + 1] = p[1] }
            val hw = kotlin.math.hypot(pts[10] - pts[34], pts[11] - pts[35]).coerceAtLeast(8f)
            when (l.preset) {
                "trail", "draw" -> {
                    val key = "${l.id}/$hi"
                    val tr = trails.getOrPut(key) { ArrayDeque() }
                    tr.addLast(floatArrayOf(pts[16], pts[17], now.toFloat()))
                    while (tr.size > 40 || (tr.isNotEmpty() && now - tr.first()[2] > 700)) tr.removeFirst()
                    if (l.preset == "trail") {
                        for (i in 1 until tr.size) {
                            val k = i / tr.size.toFloat()
                            segment(tr[i - 1][0], tr[i - 1][1], tr[i][0], tr[i][1], hw * .09f * k, c[0], c[1], c[2], a * k, cw, ch)
                        }
                    }
                    // cursor at the index finger tip (pulses while drawing)
                    val drawing = l.preset == "draw" && h.gesture == "point"
                    val pr = if (drawing) 1f + .25f * kotlin.math.sin(time * 10f) else .8f
                    segment(pts[16], pts[17], pts[16], pts[17], hw * .12f * pr, c[0], c[1], c[2], a * (if (drawing) 1f else .55f), cw, ch)
                }
                else -> { // neon skeleton
                    var i = 0
                    while (i < BONES.size) {
                        val p = BONES[i]; val q = BONES[i + 1]
                        segment(pts[p * 2], pts[p * 2 + 1], pts[q * 2], pts[q * 2 + 1], hw * .06f, c[0], c[1], c[2], a, cw, ch)
                        i += 2
                    }
                    for (tip in intArrayOf(4, 8, 12, 16, 20)) segment(pts[tip * 2], pts[tip * 2 + 1], pts[tip * 2], pts[tip * 2 + 1], hw * .1f, 1f, 1f, 1f, a * .9f, cw, ch)
                }
            }
        }
    }

    private fun handXY(h: Tracking.Hand, i: Int, cam: RLayer?, cw: Float, ch: Float) = Geometry.camToCanvas(cam, front, cw, ch, h.pts[i * 2], h.pts[i * 2 + 1])
    private fun palmXY(h: Tracking.Hand, cam: RLayer?, cw: Float, ch: Float): FloatArray {
        val p = Tracking.handPoint(h, "palm"); return Geometry.camToCanvas(cam, front, cw, ch, p[0], p[1])
    }
    private fun handSize(h: Tracking.Hand, cam: RLayer?, cw: Float, ch: Float): Float {
        val p = handXY(h, 5, cam, cw, ch); val q = handXY(h, 17, cam, cw, ch); return kotlin.math.hypot(p[0] - q[0], p[1] - q[1]).coerceAtLeast(8f) * 1.6f
    }
    private val rnd = java.util.Random()
    private var ballLevel = 0f
    private var lastFxT = 0L

    /** two-hand effects (desktop handfx.js): lightning between the index tips, an energy ball between the palms */
    private fun drawTwoHandFx(l: RLayer, cam: RLayer?, cw: Float, ch: Float, time: Float) {
        val hs = Tracking.hands
        val c = rgb(l.tint); val a = l.opacity
        if (l.preset == "ball") {
            val now = SystemClock.uptimeMillis(); val dt = ((now - lastFxT).coerceIn(0, 100)) / 1000f; lastFxT = now
            ballLevel += ((if (hs.size >= 2) 1f else 0f) - ballLevel) * kotlin.math.min(1f, dt * 6f)
            if (hs.size < 2 || ballLevel < .02f) return
            val p = palmXY(hs[0], cam, cw, ch); val q = palmXY(hs[1], cam, cw, ch)
            val mx = (p[0] + q[0]) / 2; val my = (p[1] + q[1]) / 2
            val dist = kotlin.math.hypot(q[0] - p[0], q[1] - p[1])
            val r = (dist * .18f).coerceIn(ch * .03f, ch * .3f) * ballLevel * (1f + .06f * kotlin.math.sin(time * 9f))
            // beams from the palms feeding the ball
            segment(p[0], p[1], mx, my, r * .22f, c[0], c[1], c[2], a * .25f * ballLevel, cw, ch)
            segment(q[0], q[1], mx, my, r * .22f, c[0], c[1], c[2], a * .25f * ballLevel, cw, ch)
            segment(mx, my, mx, my, r * 2.6f, c[0], c[1], c[2], a * .45f, cw, ch)
            segment(mx, my, mx, my, r * 1.4f, c[0], c[1], c[2], a * .8f, cw, ch)
            segment(mx, my, mx, my, r * .7f, 1f, 1f, 1f, a * .9f, cw, ch)
            // spinning rings
            for (k in 0 until 3) {
                val rot = time * (2.4f + k * .7f) * (if (k % 2 == 0) 1f else -1f) + k * 2.1f
                val rx = r * (1.15f + k * .16f); val ry = r * (.45f + k * .12f)
                val cr = kotlin.math.cos(rot); val sr = kotlin.math.sin(rot)
                var px = 0f; var py = 0f
                for (i in 0..20) {
                    val t = i / 20f * 4.4f
                    val ex = kotlin.math.cos(t) * rx; val ey = kotlin.math.sin(t) * ry
                    val x = mx + ex * cr - ey * sr; val y = my + ex * sr + ey * cr
                    if (i > 0) {
                        if (k % 2 == 0) segment(px, py, x, y, r * .06f, c[0], c[1], c[2], a * .8f, cw, ch)
                        else segment(px, py, x, y, r * .05f, 1f, .9f, 1f, a * .7f, cw, ch)
                    }
                    px = x; py = y
                }
            }
            return
        }
        if (hs.size < 2) return
        val p = handXY(hs[0], 8, cam, cw, ch); val q = handXY(hs[1], 8, cam, cw, ch)
        val len = kotlin.math.hypot(q[0] - p[0], q[1] - p[1]).coerceAtLeast(1f)
        val nx = -(q[1] - p[1]) / len; val ny = (q[0] - p[0]) / len
        val amp = kotlin.math.min(len * .09f, ch * .07f)
        val n = 14
        for (pass in 0 until 3) {
            var px = p[0]; var py = p[1]
            for (i in 1..n) {
                val t = i / n.toFloat()
                val j = if (i == n) 0f else (rnd.nextFloat() - .5f) * 2f * amp * kotlin.math.sin(t * Math.PI.toFloat())
                val x = p[0] + (q[0] - p[0]) * t + nx * j; val y = p[1] + (q[1] - p[1]) * t + ny * j
                segment(px, py, x, y, kotlin.math.max(5f, ch * .016f), c[0], c[1], c[2], a * .3f, cw, ch)
                segment(px, py, x, y, kotlin.math.max(1.5f, ch * .004f), 1f, 1f, 1f, a * .85f, cw, ch)
                px = x; py = y
            }
        }
        val s0 = handSize(hs[0], cam, cw, ch); val s1 = handSize(hs[1], cam, cw, ch)
        segment(p[0], p[1], p[0], p[1], s0 * .35f, c[0], c[1], c[2], a * .7f, cw, ch)
        segment(q[0], q[1], q[0], q[1], s1 * .35f, c[0], c[1], c[2], a * .7f, cw, ch)
    }

    private class Spark(var x: Float, var y: Float, var vx: Float, var vy: Float, var life: Float, val r: Float, val white: Boolean)
    private val sparks = ArrayList<Spark>()
    private val orbLevel = FloatArray(2)
    private var lastOrbT = 0L

    /** energy orb in an open hand; closing the hand bursts it into sparks (desktop handfx.js "orb") */
    private fun drawOrbs(l: RLayer, cam: RLayer?, cw: Float, ch: Float, time: Float) {
        val c = rgb(l.tint); val a = l.opacity
        val now = SystemClock.uptimeMillis(); val dt = ((now - lastOrbT).coerceIn(0, 100)) / 1000f; lastOrbT = now
        val hs = handsFor(l.attachWho)
        for (i in 0 until 2) {
            val h = hs.getOrNull(i)
            val want = if (h?.gesture == "open") 1f else 0f
            if (h != null && want == 0f && orbLevel[i] > .6f && h.gesture == "fist") {
                val p = palmXY(h, cam, cw, ch); val sz = handSize(h, cam, cw, ch)
                for (k in 0 until 46) {
                    val q = rnd.nextFloat() * 6.2832f; val v = ch * (.25f + rnd.nextFloat() * .9f)
                    sparks += Spark(p[0], p[1], kotlin.math.cos(q) * v, kotlin.math.sin(q) * v, 1f, sz * (.06f + rnd.nextFloat() * .1f), rnd.nextBoolean())
                }
                orbLevel[i] = 0f
            }
            orbLevel[i] += (want - orbLevel[i]) * kotlin.math.min(1f, dt * (if (want > 0f) 5f else 9f))
            if (h == null || orbLevel[i] < .02f) continue
            val p = palmXY(h, cam, cw, ch); val sz = handSize(h, cam, cw, ch)
            val r = sz * .5f * orbLevel[i] * (1f + .05f * kotlin.math.sin(time * 7.7f))
            segment(p[0], p[1], p[0], p[1], r * 2.6f, c[0], c[1], c[2], a * .4f, cw, ch)
            segment(p[0], p[1], p[0], p[1], r * 1.5f, c[0], c[1], c[2], a * .75f, cw, ch)
            segment(p[0], p[1], p[0], p[1], r * .8f, 1f, 1f, 1f, a * .9f, cw, ch)
            if (rnd.nextFloat() < .6f && sparks.size < 400) {
                val q = rnd.nextFloat() * 6.2832f
                sparks += Spark(p[0] + kotlin.math.cos(q) * r, p[1] + kotlin.math.sin(q) * r, kotlin.math.cos(q) * ch * .08f, -ch * .22f - rnd.nextFloat() * ch * .12f, 1f, sz * .05f, false)
            }
        }
        val iter = sparks.iterator()
        while (iter.hasNext()) {
            val s = iter.next()
            s.life -= dt * 1.3f
            if (s.life <= 0f) { iter.remove(); continue }
            s.vy += ch * .45f * dt; s.vx *= 1f - dt * 1.6f; s.vy *= 1f - dt * 1.6f
            s.x += s.vx * dt; s.y += s.vy * dt
            val r = s.r * (.6f + s.life * .9f)
            val al = a * kotlin.math.min(1f, s.life * 1.6f)
            if (s.white) segment(s.x, s.y, s.x, s.y, r, 1f, 1f, 1f, al, cw, ch) else segment(s.x, s.y, s.x, s.y, r, c[0], c[1], c[2], al, cw, ch)
        }
    }

    /** glowing face outline / eyes (FaceFx presets) */
    private fun drawFaceFx(l: RLayer, cam: RLayer?, cw: Float, ch: Float, time: Float) {
        if (!com.prism.cam.studio.FaceArt.ready) return
        GLES20.glUseProgram(progGlow)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        val c = rgb(l.tint); val a = l.opacity
        for (f in facesFor(l.attachWho)) {
            if (f.pts.size < 468 * 2) continue
            faceToCanvas(f, cam, cw, ch)
            val fw = kotlin.math.hypot(meshXY[234 * 2] - meshXY[454 * 2], meshXY[234 * 2 + 1] - meshXY[454 * 2 + 1]).coerceAtLeast(8f)
            when (l.preset) {
                "eyes" -> {
                    val pr = 1f + .2f * kotlin.math.sin(time * 6f)
                    for (e in intArrayOf(468, 473)) {
                        val idx = if (f.pts.size >= 478 * 2) e else if (e == 468) 159 else 386
                        val p = Geometry.camToCanvas(cam, front, cw, ch, f.pts[idx * 2], f.pts[idx * 2 + 1])
                        segment(p[0], p[1], p[0], p[1], fw * .05f * pr, c[0], c[1], c[2], a, cw, ch)
                    }
                }
                else -> for (loop in com.prism.cam.studio.FaceArt.LOOPS) {
                    for (i in loop.indices) {
                        val p = loop[i]; val q = loop[(i + 1) % loop.size]
                        segment(meshXY[p * 2], meshXY[p * 2 + 1], meshXY[q * 2], meshXY[q * 2 + 1], fw * .012f, c[0], c[1], c[2], a, cw, ch)
                    }
                }
            }
        }
    }

    /** tuning view: thin face outline + hand skeleton (Tracking panel "show points") */
    private fun drawMeshOverlay(cam: RLayer?, cw: Float, ch: Float) {
        if (!com.prism.cam.studio.FaceArt.ready) return
        GLES20.glUseProgram(progGlow)
        GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        for (f in Tracking.faces) {
            if (f.pts.size < 468 * 2) continue
            faceToCanvas(f, cam, cw, ch)
            for (loop in com.prism.cam.studio.FaceArt.LOOPS) for (i in loop.indices) {
                val p = loop[i]; val q = loop[(i + 1) % loop.size]
                segment(meshXY[p * 2], meshXY[p * 2 + 1], meshXY[q * 2], meshXY[q * 2 + 1], 1.6f, .42f, .91f, .84f, .9f, cw, ch)
            }
        }
        for (h in Tracking.hands) {
            val pts = FloatArray(42)
            for (i in 0 until 21) { val p = Geometry.camToCanvas(cam, front, cw, ch, h.pts[i * 2], h.pts[i * 2 + 1]); pts[i * 2] = p[0]; pts[i * 2 + 1] = p[1] }
            var i = 0
            while (i < BONES.size) { val p = BONES[i]; val q = BONES[i + 1]; segment(pts[p * 2], pts[p * 2 + 1], pts[q * 2], pts[q * 2 + 1], 1.8f, 1f, 1f, 1f, .9f, cw, ch); i += 2 }
        }
    }

    private fun quadOf(l: RLayer, cw: Float, ch: Float): Geometry.Quad {
        if (l.type == LType.Frame || l.type == LType.Drawing || l.type == LType.FaceFx || l.type == LType.HandFx) return Geometry.Quad(cw / 2, ch / 2, cw, ch, 0f)
        val baseAspect = if (l.type == LType.Camera) ch / cw else l.aspect
        val cr = Geometry.cropOf(l.type, l.circle, l.crop, baseAspect)
        val cropW = (1f - cr[0] - cr[2]).coerceAtLeast(.02f); val cropH = (1f - cr[1] - cr[3]).coerceAtLeast(.02f)
        val w = l.w * cw
        return Geometry.Quad(l.x * cw, l.y * ch, w, w * baseAspect * cropH / cropW, l.rot)
    }

    private fun drawLayer(l: RLayer, q: Geometry.Quad, alpha: Float, oes: Boolean, tex: Int, cw: Float, ch: Float, photo: PhotoSrc?, bg: Boolean, scale: Float) {
        val prog = if (oes) progLayerOES else progLayer2D
        GLES20.glUseProgram(prog)
        val flipX = if (l.type == LType.Camera) l.flipX != front else l.flipX
        val crop = Geometry.cropOf(l.type, l.circle, l.crop, if (l.type == LType.Camera) ch / cw else l.aspect)
        val cl = crop[0]; val ct = crop[1]; val cr = crop[2]; val cb = crop[3]
        // shadow first (a soft dark copy of the shape, a little lower)
        if (l.shadow > 0f) {
            val pad = q.w.coerceAtMost(q.h) * .12f + 10f
            val sq = Geometry.Quad(q.cx, q.cy + pad * .35f, q.w + pad * 2 / q.sx, q.h + pad * 2, q.rot, q.sx)
            writeQuad(sq, cw, ch) { _, _ -> ZERO4 }
            setLayerUniforms(prog, l, q, alpha, shadowPad = pad)
            GLES20.glUniform1f(loc(prog, "uPx"), scale)
            GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            bindTex(0, if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, tex, prog, "sTex")
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        // texture coordinates (xy) + camera-space coordinates for the AI mask / blur / background (zw) of a local corner
        val v = videos[l.id]
        writeQuad(q, cw, ch) { lu, lv ->
            val su0 = if (flipX) 1f - lu else lu
            val sv0 = if (l.flipY) 1f - lv else lv
            val su = cl + su0 * (1f - cl - cr); val sv = ct + sv0 * (1f - ct - cb)
            when {
                l.type == LType.Camera && photo != null -> {
                    val pu = (su - photo.u0) / (photo.u1 - photo.u0); val pv = (sv - photo.v0) / (photo.v1 - photo.v0)
                    floatArrayOf(pu, pv, pu, pv)
                }
                l.type == LType.Camera -> { val p = floatArrayOf(su, sv); camToTex.mapPoints(p); floatArrayOf(p[0], p[1], su, sv) }
                v != null -> { val x = v.m[0] * su + v.m[4] * (1f - sv) + v.m[12]; val y = v.m[1] * su + v.m[5] * (1f - sv) + v.m[13]; floatArrayOf(x, y, su, sv) }
                else -> floatArrayOf(su, sv, su, sv)
            }
        }
        setLayerUniforms(prog, l, q, alpha, shadowPad = 0f)
        GLES20.glUniform1f(loc(prog, "uPx"), scale)
        GLES20.glUniform1i(loc(prog, "uBgMode"), if (l.type == LType.Camera && hasMask) (if (bgMode == 1 && bg) 1 else if (bgMode == 2 && hasBg) 2 else 0) else 0)
        when (l.blend) {
            "screen" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_COLOR)
            "add" -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE)
            "multiply" -> GLES20.glBlendFunc(GLES20.GL_DST_COLOR, GLES20.GL_ONE_MINUS_SRC_ALPHA)
            else -> GLES20.glBlendFunc(GLES20.GL_ONE, GLES20.GL_ONE_MINUS_SRC_ALPHA)
        }
        bindTex(0, if (oes) GLES11Ext.GL_TEXTURE_EXTERNAL_OES else GLES20.GL_TEXTURE_2D, tex, prog, "sTex")
        bindTex(1, GLES20.GL_TEXTURE_2D, if (hasMask) texMask else texWhite, prog, "sMask")
        bindTex(2, GLES20.GL_TEXTURE_2D, if (bg && texB2 != 0) texB2 else texWhite, prog, "sBlur")
        bindTex(3, GLES20.GL_TEXTURE_2D, if (hasBg) texBg else texWhite, prog, "sBg")
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
    }

    private fun setLayerUniforms(prog: Int, l: RLayer, q: Geometry.Quad, alpha: Float, shadowPad: Float) {
        val qw = q.w * q.sx; val qh = q.h
        GLES20.glUniform2f(loc(prog, "uSize"), qw, qh)
        GLES20.glUniform1f(loc(prog, "uPad"), shadowPad)
        GLES20.glUniform1f(loc(prog, "uShadow"), if (shadowPad > 0f) l.shadow else 0f)
        val m = min(qw, qh)
        GLES20.glUniform1f(loc(prog, "uRadius"), (l.radius * m).coerceAtMost(m / 2))
        GLES20.glUniform1f(loc(prog, "uCircle"), if (l.circle) 1f else 0f)
        GLES20.glUniform1f(loc(prog, "uBorder"), l.border * m)
        val bc = l.borderColor
        GLES20.glUniform4f(loc(prog, "uBorderColor"), ((bc shr 16) and 255) / 255f, ((bc shr 8) and 255) / 255f, (bc and 255) / 255f, 1f)
        GLES20.glUniform1f(loc(prog, "uOpacity"), alpha)
        GLES20.glUniformMatrix4fv(loc(prog, "uColor"), 1, false, colorMat4(l.color), 0)
        GLES20.glUniform4f(loc(prog, "uColorOff"), l.color[4] / 255f, l.color[9] / 255f, l.color[14] / 255f, l.color[19] / 255f)
        GLES20.glUniform1f(loc(prog, "uKeyOn"), if (l.keyOn) 1f else 0f)
        val kc = l.keyColor
        GLES20.glUniform3f(loc(prog, "uKey"), ((kc shr 16) and 255) / 255f, ((kc shr 8) and 255) / 255f, (kc and 255) / 255f)
        GLES20.glUniform1f(loc(prog, "uKeyTol"), l.keyTol)
        GLES20.glUniform1f(loc(prog, "uPremul"), if (l.type == LType.Camera || l.type == LType.Video || l.type == LType.Screen) 0f else 1f)
    }

    /** 4 vertices: clip position, local uv, texture coords + camera-space coords (from [tx]) */
    private fun writeQuad(q: Geometry.Quad, cw: Float, ch: Float, tx: (Float, Float) -> FloatArray) {
        val c = q.corners()
        vbuf.position(0)
        for (i in 0 until 4) {
            val k = STRIP[i]
            val x = c[k * 2] / cw * 2f - 1f; val y = 1f - c[k * 2 + 1] / ch * 2f
            val lu = LOCAL[i * 2]; val lv = LOCAL[i * 2 + 1]
            val t = tx(lu, lv)
            vbuf.put(x).put(y).put(lu).put(lv).put(t[0]).put(t[1]).put(t[2]).put(t[3])
        }
        // attribute locations are fixed (bound before link) and the same in both layer programs
        for (a in 0 until 4) {
            vbuf.position(a * 2)
            GLES20.glVertexAttribPointer(a, 2, GLES20.GL_FLOAT, false, 32, vbuf)
            GLES20.glEnableVertexAttribArray(a)
        }
    }

    private fun buildCamToTex(ref: Output) {
        // camera upright space (0..1) → buffer UV → texture coords of the camera OES texture
        val u2b = Matrix(); ref.b2u.invert(u2b)
        val toUv = Matrix(); toUv.setValues(floatArrayOf(1f / ref.w, 0f, 0f, 0f, -1f / ref.h, 1f, 0f, 0f, 1f))
        u2b.postConcat(toUv)
        val t = ref.texM
        val tm = Matrix(); tm.setValues(floatArrayOf(t[0], t[4], t[12], t[1], t[5], t[13], 0f, 0f, 1f))
        u2b.postConcat(tm)
        camToTex.set(u2b)
    }

    // ───────────────────────────── videos ─────────────────────────────

    /** open / close players to match the video layers of the scene */
    private fun syncVideos(scene: List<RLayer>) {
        val want = scene.filter { it.type == LType.Video && it.media.isNotEmpty() }.associateBy { it.id }
        val it = videos.entries.iterator()
        while (it.hasNext()) { val (id, v) = it.next(); if (want[id]?.media != v.media) { freeVideo(v); it.remove() } }
        for ((id, l) in want) {
            var v = videos[id]
            if (v == null) {
                val f = File(com.prism.cam.studio.Content.mediaDir, l.media)
                if (!f.exists()) continue
                val tex = newOes()
                val st = SurfaceTexture(tex); val s = Surface(st)
                val mp = MediaPlayer()
                val vid = Video(id, l.media, mp, st, tex, s)
                st.setOnFrameAvailableListener({ vid.fresh = true }, handler)
                try {
                    mp.setDataSource(f.path); mp.setSurface(s); mp.isLooping = l.loop
                    mp.setOnPreparedListener { p -> vid.w = p.videoWidth; vid.h = p.videoHeight; p.start(); vid.ready = true }
                    mp.prepareAsync()
                } catch (t: Throwable) { PrismLog.i("ERR video ${t.message}") }
                videos[id] = vid; v = vid
            }
            try {
                val vol = if (l.muted) 0f else 1f
                v.player.setVolume(vol, vol)
                v.player.isLooping = l.loop
                if (v.ready && android.os.Build.VERSION.SDK_INT >= 23 && kotlin.math.abs(v.player.playbackParams.speed - l.speed) > .01f) {
                    v.player.playbackParams = PlaybackParams().setSpeed(l.speed)
                }
                if (v.fresh) { v.st.updateTexImage(); v.st.getTransformMatrix(v.m); v.fresh = false }
                if (v.ready) videoPos[id] = v.player.currentPosition
            } catch (_: Throwable) { }
        }
    }

    private fun freeVideo(v: Video) {
        try { v.player.release() } catch (_: Throwable) { }
        v.surface.release(); v.st.release()
        GLES20.glDeleteTextures(1, intArrayOf(v.tex), 0)
        videoPos.remove(v.id)
    }

    // ───────────────────────────── uploads ─────────────────────────────

    private fun uploadPending() {
        if (pending.isNotEmpty()) {
            for ((id, pair) in pending.entries.toList()) {
                pending.remove(id)
                val (b, key) = pair
                if (b.isRecycled) continue
                val old = textures[id]
                val t = if (old != null && old.w == b.width && old.h == b.height) old.id else { old?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }; newTex() }
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
                GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
                textures[id] = Tex(t, b.width, b.height, key)
                uploaded[id] = key
            }
        }
        // forget textures of layers that are gone
        val ids = layers.mapTo(HashSet()) { it.id }
        val gone = textures.keys.filter { it !in ids }
        for (id in gone) { textures.remove(id)?.let { GLES20.glDeleteTextures(1, intArrayOf(it.id), 0) }; uploaded.remove(id); drawn.remove(id) }

        if (bgPending.getAndSet(false)) {
            val b = bgBitmap
            if (b == null || b.isRecycled) hasBg = false
            else { if (texBg == 0) texBg = newTex(); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texBg); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0); hasBg = true }
        }
        if (maskPending.getAndSet(false)) {
            val m = maskBytes
            if (m == null) hasMask = false
            else {
                if (texMask == 0) texMask = newTex()
                GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texMask)
                GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                m.position(0)
                GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, maskW, maskH, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, m)
                hasMask = true
            }
        }
    }

    // ───────────────────────────── AI copy + blur ─────────────────────────────

    /** Camera picture in camera upright space at low resolution (analysis + blurred background). */
    private fun renderAnalysis(inp: Input, ref: Output) {
        val uAspect = ref.uw / ref.uh
        val w = if (uAspect >= 1f) 256 else (256 * uAspect).toInt().coerceAtLeast(64)
        val h = if (uAspect >= 1f) (256 / uAspect).toInt().coerceAtLeast(64) else 256
        ensureAnalysis(w, h)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
        GLES20.glViewport(0, 0, aW, aH)
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progAnalysis)
        bindQuad(progAnalysis)
        GLES20.glUniformMatrix3fv(loc(progAnalysis, "uUToTex"), 1, false, colMajor3(camToTex), 0)
        bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inp.tex, progAnalysis, "sCam")
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val l = analysisListener
        val now = SystemClock.elapsedRealtime()
        if (l != null && !analysisBusy && now - lastRead >= (if (lite) 100 else 66)) {
            lastRead = now
            val buf = readBuf!!
            buf.position(0)
            GLES20.glReadPixels(0, 0, aW, aH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
            buf.position(0)
            analysisBusy = true
            val copy = ByteBuffer.allocateDirect(buf.capacity()).order(ByteOrder.nativeOrder()).put(buf).apply { position(0) }
            l(copy, aW, aH, inp.st.timestamp)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun ensureAnalysis(w: Int, h: Int) {
        if (w == aW && h == aH && fboA != 0) return
        aW = w; aH = h
        freeFbo(fboA, texA); freeFbo(fboB1, texB1); freeFbo(fboB2, texB2)
        makeFbo(w, h).let { fboA = it.first; texA = it.second }
        bW = (w / 2).coerceAtLeast(32); bH = (h / 2).coerceAtLeast(32)
        makeFbo(bW, bH).let { fboB1 = it.first; texB1 = it.second }
        makeFbo(bW, bH).let { fboB2 = it.first; texB2 = it.second }
        readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
    }

    /** Kawase blur (4 passes of 4 diagonal taps) on the small camera-space copy */
    private fun renderBlur() {
        GLES20.glDisable(GLES20.GL_BLEND)
        GLES20.glUseProgram(progBlur)
        bindQuad(progBlur)
        val k = .6f + 1.2f * blurAmount
        val passes = arrayOf(Triple(texA, fboB1, 1.0f), Triple(texB1, fboB2, 2.0f), Triple(texB2, fboB1, 3.0f), Triple(texB1, fboB2, 4.0f))
        GLES20.glViewport(0, 0, bW, bH)
        for ((src, dst, off) in passes) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst)
            bindTex(0, GLES20.GL_TEXTURE_2D, src, progBlur, "sSrc")
            GLES20.glUniform2f(loc(progBlur, "uStep"), off * k / bW, off * k / bH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    // ───────────────────────────── photo pass ─────────────────────────────

    /**
     * Full-resolution photo of the whole scene: [photo] = the camera still in camera upright space (not mirrored),
     * covering camera space [u0..u1] × [v0..v1]. The scene is drawn at the photo's size with the same layers, attached
     * positions and video frames as the screen. [mask] = person mask for the still (or null). Result is upright,
     * mirrored like the screen for the front camera. Runs on the GL thread; [done] gets the bitmap (or null).
     */
    fun photo(photo: Bitmap, u0: Float, v0: Float, u1: Float, v1: Float, mask: Pair<ByteBuffer, IntArray>?, done: (Bitmap?) -> Unit) {
        handler.post {
            try {
                EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
                uploadPending()
                val pw = photo.width; val ph = photo.height
                val maxTex = IntArray(1).also { GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE, it, 0) }[0]
                val k = min(1f, maxTex.toFloat() / max(pw, ph))
                // output keeps the canvas aspect (what the screen showed) at the photo's resolution
                val aspect = canvasH.toFloat().coerceAtLeast(1f) / canvasW.toFloat().coerceAtLeast(1f)
                val ow = (max(pw, (ph / aspect).toInt()) * k).toInt().coerceAtMost(maxTex); val oh = (ow * aspect).toInt().coerceAtMost(maxTex)
                val src = if (k < 1f) Bitmap.createScaledBitmap(photo, (pw * k).toInt(), (ph * k).toInt(), true) else photo
                val tp = newTex(); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tp); GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, src, 0)
                if (src !== photo) src.recycle()
                // person mask + blurred background for the still
                val keepMask = hasMask; val keepTexMask = texMask
                if (mask != null && bgMode != 0) {
                    val tm = newTex(); GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tm); GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
                    mask.first.position(0)
                    GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, mask.second[0], mask.second[1], 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, mask.first)
                    texMask = tm; hasMask = true
                } else if (bgMode != 0) hasMask = false
                var blurReady = false
                if (bgMode == 1 && hasMask) {
                    // still → small camera-space copy → Kawase blur (same look as live)
                    ensureAnalysis(aW.coerceAtLeast(64), aH.coerceAtLeast(64))
                    GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA); GLES20.glViewport(0, 0, aW, aH)
                    GLES20.glUseProgram(progBlur); bindQuad(progBlur)
                    bindTex(0, GLES20.GL_TEXTURE_2D, tp, progBlur, "sSrc"); GLES20.glUniform2f(loc(progBlur, "uStep"), 0f, 0f)
                    GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
                    renderBlur(); blurReady = true
                }
                // canvas units = the live canvas, so every layer lands where it was seen; target = photo resolution
                val cw = canvasW.toFloat().coerceAtLeast(1f); val ch = canvasH.toFloat().coerceAtLeast(1f)
                val (fbo, tex) = makeFbo(ow, oh)
                compose(layers, tp, cw, ch, fbo, ow, oh, PhotoSrc(tp, u0, v0, u1, v1), blurReady, scale = ow / cw)
                val buf = ByteBuffer.allocateDirect(ow * oh * 4).order(ByteOrder.nativeOrder())
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
                GLES20.glReadPixels(0, 0, ow, oh, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, buf)
                GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
                freeFbo(fbo, tex); GLES20.glDeleteTextures(1, intArrayOf(tp), 0)
                if (texMask != keepTexMask) { GLES20.glDeleteTextures(1, intArrayOf(texMask), 0); texMask = keepTexMask }
                hasMask = keepMask
                buf.position(0)
                val out = Bitmap.createBitmap(ow, oh, Bitmap.Config.ARGB_8888)
                out.copyPixelsFromBuffer(buf)
                // GL rows are bottom-up (the canvas already looks like the screen, front camera included)
                val m = Matrix(); m.postScale(1f, -1f, ow / 2f, oh / 2f)
                val upright = Bitmap.createBitmap(out, 0, 0, ow, oh, m, true)
                out.recycle()
                done(upright)
            } catch (t: Throwable) {
                PrismLog.i("ERR photo pass ${t.javaClass.simpleName} ${t.message}")
                done(null)
            }
        }
    }

    // ───────────────────────────── GL helpers ─────────────────────────────

    private fun bufUvToU(o: Output): FloatArray {
        val m = Matrix()
        m.setValues(floatArrayOf(o.w.toFloat(), 0f, 0f, 0f, -o.h.toFloat(), o.h.toFloat(), 0f, 0f, 1f))
        m.postConcat(o.b2u)
        return colMajor3(m)
    }

    private fun newOes(): Int {
        val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return tex
    }

    private fun newTex(): Int {
        val t = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        return t
    }

    private fun makeFbo(w: Int, h: Int): Pair<Int, Int> {
        val tex = newTex()
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA, w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null)
        val fbo = IntArray(1).also { GLES20.glGenFramebuffers(1, it, 0) }[0]
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo)
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0, GLES20.GL_TEXTURE_2D, tex, 0)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
        return fbo to tex
    }

    private fun freeFbo(fbo: Int, tex: Int) {
        if (fbo != 0) GLES20.glDeleteFramebuffers(1, intArrayOf(fbo), 0)
        if (tex != 0) GLES20.glDeleteTextures(1, intArrayOf(tex), 0)
    }

    private val locs = HashMap<String, Int>()
    private fun loc(p: Int, name: String): Int = locs.getOrPut("$p/$name") { GLES20.glGetUniformLocation(p, name) }

    private fun bindQuad(p: Int) {
        val aPos = GLES20.glGetAttribLocation(p, "aPos")
        val aTex = GLES20.glGetAttribLocation(p, "aTex")
        quad.position(0)
        GLES20.glVertexAttribPointer(aPos, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aPos)
        quad.position(2)
        GLES20.glVertexAttribPointer(aTex, 2, GLES20.GL_FLOAT, false, 16, quad)
        GLES20.glEnableVertexAttribArray(aTex)
    }

    private fun bindTex(unit: Int, target: Int, tex: Int, p: Int, name: String) {
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + unit)
        GLES20.glBindTexture(target, tex)
        GLES20.glUniform1i(loc(p, name), unit)
    }

    private fun setupEgl() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        val v = IntArray(2)
        EGL14.eglInitialize(display, v, 0, v, 1)
        val attrs = intArrayOf(
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8, EGL14.EGL_ALPHA_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            0x3142 /* EGL_RECORDABLE_ANDROID */, 1,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_NONE,
        )
        val cfgs = arrayOfNulls<EGLConfig>(1); val n = IntArray(1)
        EGL14.eglChooseConfig(display, attrs, 0, cfgs, 0, 1, n, 0)
        config = cfgs[0]
        context = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT, intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
        pbuffer = EGL14.eglCreatePbufferSurface(display, config, intArrayOf(EGL14.EGL_WIDTH, 1, EGL14.EGL_HEIGHT, 1, EGL14.EGL_NONE), 0)
        EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)
        progLayer2D = program(VS_LAYER, fsLayer(false), layerAttribs = true)
        progLayerOES = program(VS_LAYER, fsLayer(true), layerAttribs = true)
        progOut = program(VS_OUT, FS_OUT)
        progAnalysis = program(VS_ANALYSIS, FS_ANALYSIS)
        progBlur = program(VS_PLAIN, FS_BLUR)
        progGlow = program(VS_GLOW, FS_GLOW, glowAttribs = true)
        texWhite = newTex()
        GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1).put(255.toByte()).apply { position(0) })
        ready = progLayer2D != 0 && progLayerOES != 0 && progOut != 0 && progAnalysis != 0 && progBlur != 0 && progGlow != 0
        gpuName = GLES20.glGetString(GLES20.GL_RENDERER) ?: ""
        PrismLog.i("FX gl ready=$ready $gpuName")
    }

    private fun program(vs: String, fs: String, layerAttribs: Boolean = false, glowAttribs: Boolean = false): Int {
        fun shader(type: Int, src: String): Int {
            val s = GLES20.glCreateShader(type)
            GLES20.glShaderSource(s, src); GLES20.glCompileShader(s)
            val ok = IntArray(1); GLES20.glGetShaderiv(s, GLES20.GL_COMPILE_STATUS, ok, 0)
            if (ok[0] == 0) { PrismLog.i("ERR shader ${GLES20.glGetShaderInfoLog(s)}"); return 0 }
            return s
        }
        val p = GLES20.glCreateProgram()
        GLES20.glAttachShader(p, shader(GLES20.GL_VERTEX_SHADER, vs))
        GLES20.glAttachShader(p, shader(GLES20.GL_FRAGMENT_SHADER, fs))
        if (layerAttribs) {
            GLES20.glBindAttribLocation(p, 0, "aPos"); GLES20.glBindAttribLocation(p, 1, "aLocal"); GLES20.glBindAttribLocation(p, 2, "aTex"); GLES20.glBindAttribLocation(p, 3, "aSrc")
        }
        if (glowAttribs) { GLES20.glBindAttribLocation(p, 0, "aPos"); GLES20.glBindAttribLocation(p, 1, "aLocal") }
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { PrismLog.i("ERR link ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
        return p
    }

    companion object {
        private val ZERO4 = floatArrayOf(0f, 0f, 0f, 0f)
        /** quad corners TL, TR, BR, BL → triangle strip TL, TR, BL, BR */
        private val STRIP = intArrayOf(0, 1, 3, 2)
        private val LOCAL = floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun colMajor3(m: Matrix): FloatArray {
            val v = FloatArray(9); m.getValues(v)
            return floatArrayOf(v[0], v[3], v[6], v[1], v[4], v[7], v[2], v[5], v[8])
        }

        fun colorMat4(cm: FloatArray): FloatArray {
            val m = FloatArray(16)
            for (r in 0 until 4) for (c in 0 until 4) m[c * 4 + r] = cm[r * 5 + c]
            return m
        }

        private const val VS_LAYER = """
            attribute vec2 aPos; attribute vec2 aLocal; attribute vec2 aTex; attribute vec2 aSrc;
            varying vec2 vLocal; varying vec2 vTex; varying vec2 vSrc;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vLocal = aLocal; vTex = aTex; vSrc = aSrc; }"""

        /** one layer: texture → (camera: background) → colour → chroma key → shape / border → opacity, premultiplied out */
        private fun fsLayer(oes: Boolean) = (if (oes) "#extension GL_OES_EGL_image_external : require\n" else "") + """
            precision highp float;
            uniform ${if (oes) "samplerExternalOES" else "sampler2D"} sTex;
            uniform sampler2D sMask; uniform sampler2D sBlur; uniform sampler2D sBg;
            uniform vec2 uSize; uniform float uPad; uniform float uShadow;
            uniform float uRadius; uniform float uCircle; uniform float uBorder; uniform vec4 uBorderColor;
            uniform float uOpacity; uniform mat4 uColor; uniform vec4 uColorOff;
            uniform float uKeyOn; uniform vec3 uKey; uniform float uKeyTol; uniform float uPremul;
            uniform int uBgMode; uniform float uPx;
            varying vec2 vLocal; varying vec2 vTex; varying vec2 vSrc;
            float sdf(vec2 p, vec2 b) {
                if (uCircle > 0.5) { vec2 q = p / b; float k = length(q); return (k - 1.0) * k / max(length(q / b), 1e-5); }
                float r = min(uRadius, min(b.x, b.y));
                vec2 q = abs(p) - b + r;
                return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;
            }
            vec2 cbcr(vec3 c) { return vec2(-0.169 * c.r - 0.331 * c.g + 0.5 * c.b, 0.5 * c.r - 0.419 * c.g - 0.081 * c.b); }
            void main() {
                vec2 b = uSize * 0.5;
                if (uShadow > 0.0) {
                    vec2 p = (vLocal - 0.5) * (uSize + 2.0 * uPad);
                    float d = sdf(p, b);
                    float a = (1.0 - smoothstep(-uPad * 0.3, uPad, d)) * uShadow * 0.55 * uOpacity;
                    gl_FragColor = vec4(0.0, 0.0, 0.0, a);
                    return;
                }
                vec4 c = texture2D(sTex, vTex);
                if (uPremul > 0.5) { if (c.a > 0.0) c.rgb /= c.a; } else c.a = 1.0;
                if (uBgMode > 0) {
                    float m = smoothstep(0.30, 0.70, texture2D(sMask, vSrc).r);
                    vec3 back = uBgMode == 1 ? texture2D(sBlur, vSrc).rgb : texture2D(sBg, vSrc).rgb;
                    c.rgb = mix(back, c.rgb, m);
                }
                c = vec4(clamp((uColor * vec4(c.rgb, 1.0)).rgb + uColorOff.rgb, 0.0, 1.0), c.a);
                if (uKeyOn > 0.5) {
                    float d = distance(cbcr(c.rgb), cbcr(uKey));
                    c.a *= smoothstep(uKeyTol * 0.5, uKeyTol * 0.5 + 0.06, d);
                }
                vec2 p = (vLocal - 0.5) * uSize;
                float d = sdf(p, b);
                float cover = clamp(0.5 - d * uPx, 0.0, 1.0);
                if (uBorder > 0.0) {
                    float edge = clamp((d + uBorder) * uPx + 0.5, 0.0, 1.0);
                    c = mix(c, vec4(uBorderColor.rgb, 1.0), edge);
                }
                float a = c.a * cover * uOpacity;
                gl_FragColor = vec4(c.rgb * a, a);
            }"""

        private const val VS_GLOW = """
            attribute vec2 aPos; attribute vec2 aLocal;
            varying vec2 vL;
            void main() { gl_Position = vec4(aPos, 0.0, 1.0); vL = aLocal; }"""
        /** capsule glow: white-hot core + coloured halo */
        private const val FS_GLOW = """
            precision highp float;
            uniform vec4 uColor; uniform float uHalf; uniform float uW;
            varying vec2 vL;
            void main() {
                float d = length(vec2(max(abs(vL.x) - uHalf, 0.0), vL.y));
                float w = max(uW, 0.5);
                float core = 1.0 - smoothstep(w * 0.32, w * 0.5, d);
                float glow = exp(-d * d / (w * w) * 1.3) * 0.8;
                float a = clamp(core + glow, 0.0, 1.0) * uColor.a;
                vec3 c = mix(uColor.rgb, vec3(1.0), core * 0.55);
                gl_FragColor = vec4(c * a, a);
            }"""

        private const val VS_OUT = """
            attribute vec4 aPos; attribute vec4 aTex;
            uniform mat3 uBufToU;
            varying vec2 vU;
            void main() { gl_Position = aPos; vU = (uBufToU * vec3(aTex.xy, 1.0)).xy; }"""
        private const val FS_OUT = """
            precision highp float;
            uniform sampler2D sCanvas; uniform float uMirror; uniform float uVignette; uniform float uGrain; uniform float uTime;
            varying vec2 vU;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uTime) * 43758.5453); }
            void main() {
                vec2 u = vec2(uMirror > 0.5 ? 1.0 - vU.x : vU.x, vU.y);
                vec3 c = texture2D(sCanvas, vec2(u.x, 1.0 - u.y)).rgb;
                if (uVignette > 0.0) c *= 1.0 - uVignette * smoothstep(0.30, 0.85, length(u - 0.5) * 1.2);
                if (uGrain > 0.0) c += (hash(u * 1000.0) - 0.5) * uGrain;
                gl_FragColor = vec4(c, 1.0);
            }"""
        private const val VS_ANALYSIS = """
            attribute vec4 aPos; attribute vec4 aTex;
            uniform mat3 uUToTex;
            varying vec2 vTex;
            void main() { gl_Position = aPos; vTex = (uUToTex * vec3(aTex.xy, 1.0)).xy; }"""
        private const val FS_ANALYSIS = """
            #extension GL_OES_EGL_image_external : require
            precision mediump float;
            uniform samplerExternalOES sCam;
            varying vec2 vTex;
            void main() { gl_FragColor = texture2D(sCam, vTex); }"""
        private const val VS_PLAIN = """
            attribute vec4 aPos; attribute vec4 aTex;
            varying vec2 vUv;
            void main() { gl_Position = aPos; vUv = aTex.xy; }"""
        private const val FS_BLUR = """
            precision mediump float;
            uniform sampler2D sSrc; uniform vec2 uStep;
            varying vec2 vUv;
            void main() {
                vec4 s = texture2D(sSrc, vUv + vec2(-uStep.x, -uStep.y));
                s += texture2D(sSrc, vUv + vec2(uStep.x, -uStep.y));
                s += texture2D(sSrc, vUv + vec2(-uStep.x, uStep.y));
                s += texture2D(sSrc, vUv + vec2(uStep.x, uStep.y));
                gl_FragColor = s * 0.25;
            }"""
    }
}

/** CameraX effect wrapper: our processor on the preview + video streams. */
class PrismEffect(fx: GlFx) : CameraEffect(
    PREVIEW or VIDEO_CAPTURE, fx.executor, fx, { t -> PrismLog.i("ERR effect ${t.message}") },
)

/** Keyframe playback: smooth (ease in-out) between the recorded states, looping over keyLen seconds. */
object Keys {
    fun at(l: RLayer, time: Float): RLayer {
        val k = l.keys; val n = k.size / 6
        if (n < 2) return l
        val len = l.keyLen.coerceAtLeast(.2f)
        val t = time % len
        var i = 0
        while (i < n - 1 && k[(i + 1) * 6] <= t) i++
        val a = i * 6
        if (t <= k[0]) return l.copy(x = k[1], y = k[2], w = k[3], rot = k[4], opacity = l.opacity * k[5])
        if (i >= n - 1) return l.copy(x = k[a + 1], y = k[a + 2], w = k[a + 3], rot = k[a + 4], opacity = l.opacity * k[a + 5])
        val b = a + 6
        val span = (k[b] - k[a]).coerceAtLeast(1e-3f)
        var f = ((t - k[a]) / span).coerceIn(0f, 1f)
        f = f * f * (3 - 2 * f)
        fun mix(o: Int) = k[a + o] + (k[b + o] - k[a + o]) * f
        return l.copy(x = mix(1), y = mix(2), w = mix(3), rot = mix(4), opacity = l.opacity * mix(5))
    }
}

/** Motion presets (desktop-like): offsets in canvas fractions, scale, degrees, alpha — for a time t (s). */
object Motion {
    class A(val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val rot: Float = 0f, val alpha: Float = 1f)
    val ids = listOf("pop", "float", "bounce", "spin", "pulse", "shake", "swing", "slideL", "slideR", "blink", "heartbeat")
    fun at(id: String, t: Float, seed: Long): A {
        val ph = (seed % 97) / 97f * 6.283f
        return when (id) {
            "pop" -> { val k = (t % 2.4f) / .45f; if (k < 1f) A(scale = .4f + .6f * easeBack(k)) else A() }
            "float" -> A(dy = .012f * sin(t * 2.1f + ph))
            "bounce" -> A(dy = -.035f * kotlin.math.abs(sin(t * 3.4f + ph)))
            "spin" -> A(rot = (t * 90f) % 360f)
            "pulse" -> A(scale = 1f + .07f * sin(t * 4.2f + ph))
            "shake" -> A(dx = .006f * sin(t * 38f + ph), rot = 2.5f * sin(t * 31f))
            "swing" -> A(rot = 12f * sin(t * 2.4f + ph))
            "slideL" -> { val k = ((t % 3f) / .6f).coerceAtMost(1f); A(dx = -(1f - easeOut(k)) * .6f, alpha = k) }
            "slideR" -> { val k = ((t % 3f) / .6f).coerceAtMost(1f); A(dx = (1f - easeOut(k)) * .6f, alpha = k) }
            "blink" -> A(alpha = if (sin(t * 5f) > -.3f) 1f else .15f)
            "heartbeat" -> { val k = t % 1.1f; A(scale = 1f + .12f * (if (k < .12f) k / .12f else if (k < .3f) 1f - (k - .12f) / .18f else 0f)) }
            else -> A()
        }
    }
    private fun easeOut(k: Float) = 1f - (1f - k) * (1f - k)
    private fun easeBack(k: Float): Float { val c = 1.7f; val x = k - 1f; return 1f + (c + 1f) * x * x * x + c * x * x }
}
