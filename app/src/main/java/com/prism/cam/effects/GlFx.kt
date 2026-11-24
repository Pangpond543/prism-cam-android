package com.prism.cam.effects

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
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
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The GPU effect pipeline (CameraX SurfaceProcessor, OpenGL ES 2): camera frame (OES texture) → one shader pass per
 * output (preview + video encoder) with colour look, vignette, grain, background blur / replace (person mask), and the
 * overlay picture (text, stickers, frames, face stickers). Frames never leave the GPU except a tiny "analysis" copy
 * (≈256 px, ~15 fps) read back for the AI when it is on.
 *
 * Coordinates: everything drawn on top (overlay, mask, blurred background) lives in **U space** = the output picture
 * as the user sees it (x → right, y → down, 0..1). Each output gets its own buffer→U matrix from
 * SurfaceOutput.getSensorToBufferTransform() + the sensor→upright rotation, so preview and video always agree.
 * All GL work runs on one thread ("prism-gl").
 */
class GlFx : SurfaceProcessor {
    // ───────── settings written from the UI thread, read on the GL thread
    @Volatile var colorMatrix: FloatArray = Looks.IDENTITY
    @Volatile var vignette = 0f
    @Volatile var grain = 0f
    /** 0 none · 1 blur · 2 replace */
    @Volatile var bgMode = 0
    @Volatile var blurAmount = 1f
    /** sensor → upright rotation (CameraInfo.getSensorRotationDegrees(display rotation)) */
    @Volatile var uprightDeg = 0
    /** analysis frames wanted (AI on) */
    @Volatile var analysisListener: ((ByteBuffer, Int, Int, Long) -> Unit)? = null
    @Volatile var analysisBusy = false

    private val overlayPending = AtomicBoolean(false)
    @Volatile private var overlayBitmap: Bitmap? = null
    private val maskPending = AtomicBoolean(false)
    @Volatile private var maskBytes: ByteBuffer? = null
    @Volatile private var maskW = 0
    @Volatile private var maskH = 0
    private val bgPending = AtomicBoolean(false)
    @Volatile private var bgBitmap: Bitmap? = null

    fun setOverlay(b: Bitmap?) { overlayBitmap = b; overlayPending.set(true) }
    /** the last overlay has not reached the GPU yet (don't draw into it) */
    fun overlayBusy() = overlayPending.get() && input != null
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
        /** buffer pixel → U (0..1) */
        val b2u = Matrix()
        /** size of the picture once upright (px) */
        var uw = w.toFloat(); var uh = h.toFloat()
        val b2uOk get() = !b2u.isIdentity
        var preview = (out.targets and CameraEffect.PREVIEW) != 0
    }

    private var input: Input? = null
    private val outputs = LinkedHashMap<SurfaceOutput, Output>()
    private val stMat = FloatArray(16)

    // programs + textures
    private var prog = 0
    private var progAnalysis = 0
    private var progBlur = 0
    private var texOverlay = 0
    private var texMask = 0
    private var texBg = 0
    private var hasOverlay = false
    private var hasMask = false
    private var hasBg = false
    // analysis + blur targets (U space)
    private var aW = 0; private var aH = 0
    private var fboA = 0; private var texA = 0
    private var fboB1 = 0; private var texB1 = 0
    private var fboB2 = 0; private var texB2 = 0
    private var bW = 0; private var bH = 0
    private var readBuf: ByteBuffer? = null
    private var frameNo = 0L
    private var overlayLogs = 0
    private var lastRead = 0L
    private val t0 = SystemClock.elapsedRealtime()

    private val quad: FloatBuffer = floatArrayOf(-1f, -1f, 0f, 0f, 1f, -1f, 1f, 0f, -1f, 1f, 0f, 1f, 1f, 1f, 1f, 1f)
        .let { ByteBuffer.allocateDirect(it.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer().put(it).apply { position(0) } }

    init { handler.post { setupEgl() } }

    // ───────────────────────────── SurfaceProcessor ─────────────────────────────

    override fun onInputSurface(request: SurfaceRequest) {
        handler.post {
            if (!ready) { request.willNotProvideSurface(); return@post }
            val tex = IntArray(1).also { GLES20.glGenTextures(1, it, 0) }[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, tex)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
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
        if (s2b == null || !s2b.invert(m)) {
            // fallback: buffer is already upright
            m.reset()
        }
        m.postRotate(uprightDeg.toFloat())
        val pts = floatArrayOf(0f, 0f, o.w.toFloat(), 0f, 0f, o.h.toFloat(), o.w.toFloat(), o.h.toFloat())
        m.mapPoints(pts)
        val xs = floatArrayOf(pts[0], pts[2], pts[4], pts[6]); val ys = floatArrayOf(pts[1], pts[3], pts[5], pts[7])
        val minX = xs.min(); val minY = ys.min()
        val sw = (xs.max() - minX).coerceAtLeast(1f); val sh = (ys.max() - minY).coerceAtLeast(1f)
        m.postTranslate(-minX, -minY)
        m.postScale(1f / sw, 1f / sh)
        o.b2u.set(m)
        o.uw = sw; o.uh = sh
        val v = FloatArray(9); m.getValues(v)
        PrismLog.i("FX b2u ${o.w}x${o.h} up=$uprightDeg s2b=${s2b != null} upright=${sw.toInt()}x${sh.toInt()} m=${v.joinToString(",") { "%.4f".format(it) }}")
    }

    /** the display turned: overlays / masks follow */
    fun refreshGeometry() = handler.post { outputs.values.forEach { computeB2U(it) } }

    fun release() {
        handler.post {
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

    // ───────────────────────────── drawing ─────────────────────────────

    private fun draw() {
        val inp = input ?: return
        if (!EGL14.eglMakeCurrent(display, pbuffer, pbuffer, context)) { PrismLog.i("ERR fx makeCurrent 0x${Integer.toHexString(EGL14.eglGetError())}"); return }
        try { inp.st.updateTexImage() } catch (_: Throwable) { return }
        inp.st.getTransformMatrix(stMat)
        if (outputs.isEmpty()) return
        frameNo++
        uploadPending()

        val ref = outputs.values.firstOrNull { it.preview } ?: outputs.values.first()
        ref.out.updateTransformMatrix(ref.texM, stMat)
        val needAnalysis = bgMode == 1 || analysisListener != null
        if (needAnalysis) renderAnalysis(inp, ref)
        val blurReady = bgMode == 1 && fboA != 0
        if (blurReady) renderBlur()

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
            o.out.updateTransformMatrix(o.texM, stMat)
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
            GLES20.glViewport(0, 0, o.w, o.h)
            GLES20.glUseProgram(prog)
            bindQuad(prog)
            GLES20.glUniformMatrix4fv(loc(prog, "uTexM"), 1, false, o.texM, 0)
            GLES20.glUniformMatrix3fv(loc(prog, "uBufToU"), 1, false, bufUvToU(o), 0)
            val cm = colorMatrix
            GLES20.glUniformMatrix4fv(loc(prog, "uColor"), 1, false, colorMat4(cm), 0)
            GLES20.glUniform4f(loc(prog, "uColorOff"), cm[4] / 255f, cm[9] / 255f, cm[14] / 255f, cm[19] / 255f)
            GLES20.glUniform1f(loc(prog, "uVignette"), vignette)
            GLES20.glUniform1f(loc(prog, "uGrain"), grain)
            GLES20.glUniform1f(loc(prog, "uTime"), ((SystemClock.elapsedRealtime() - t0) % 100000) / 1000f)
            val mode = if (bgMode == 1 && blurReady) 1 else if (bgMode == 2 && hasBg) 2 else 0
            GLES20.glUniform1i(loc(prog, "uBgMode"), if (hasMask) mode else 0)
            GLES20.glUniform1f(loc(prog, "uHasOverlay"), if (hasOverlay) 1f else 0f)
            bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inp.tex, prog, "sCam")
            bindTex(1, GLES20.GL_TEXTURE_2D, if (hasOverlay) texOverlay else texMask, prog, "sOverlay")
            bindTex(2, GLES20.GL_TEXTURE_2D, texMask, prog, "sMask")
            bindTex(3, GLES20.GL_TEXTURE_2D, if (texB2 != 0) texB2 else texMask, prog, "sBlur")
            bindTex(4, GLES20.GL_TEXTURE_2D, if (hasBg) texBg else texMask, prog, "sBg")
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
            EGLExt.eglPresentationTimeANDROID(display, o.egl, ts)
            val swapped = EGL14.eglSwapBuffers(display, o.egl)
            if (frameNo % 300 == 1L || !swapped) PrismLog.i("FX frame $frameNo out ${o.w}x${o.h} swap=$swapped egl=0x${Integer.toHexString(EGL14.eglGetError())} gl=0x${Integer.toHexString(GLES20.glGetError())} overlay=$hasOverlay")
        }
    }

    /** buffer UV (GL, y up) → U, as a GL column-major mat3. */
    private fun bufUvToU(o: Output): FloatArray {
        val m = Matrix()
        // (u, v) → buffer pixel (u·W, (1−v)·H)
        m.setValues(floatArrayOf(o.w.toFloat(), 0f, 0f, 0f, -o.h.toFloat(), o.h.toFloat(), 0f, 0f, 1f))
        m.postConcat(o.b2u)
        return colMajor3(m)
    }

    /** Camera picture in U space at low resolution (analysis + blurred background). */
    private fun renderAnalysis(inp: Input, ref: Output) {
        val uAspect = ref.uw / ref.uh
        val w = if (uAspect >= 1f) 256 else (256 * uAspect).toInt().coerceAtLeast(64)
        val h = if (uAspect >= 1f) (256 / uAspect).toInt().coerceAtLeast(64) else 256
        if (w != aW || h != aH || fboA == 0) {
            aW = w; aH = h
            freeFbo(fboA, texA); freeFbo(fboB1, texB1); freeFbo(fboB2, texB2)
            makeFbo(w, h).let { fboA = it.first; texA = it.second }
            bW = (w / 2).coerceAtLeast(32); bH = (h / 2).coerceAtLeast(32)
            makeFbo(bW, bH).let { fboB1 = it.first; texB1 = it.second }
            makeFbo(bW, bH).let { fboB2 = it.first; texB2 = it.second }
            readBuf = ByteBuffer.allocateDirect(w * h * 4).order(ByteOrder.nativeOrder())
        }
        // U (0..1, y down) → buffer UV (GL) of the reference output
        val u2b = Matrix(); ref.b2u.invert(u2b)
        val toUv = Matrix(); toUv.setValues(floatArrayOf(1f / ref.w, 0f, 0f, 0f, -1f / ref.h, 1f, 0f, 0f, 1f))
        u2b.postConcat(toUv)
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboA)
        GLES20.glViewport(0, 0, aW, aH)
        GLES20.glUseProgram(progAnalysis)
        bindQuad(progAnalysis)
        GLES20.glUniformMatrix4fv(loc(progAnalysis, "uTexM"), 1, false, ref.texM, 0)
        GLES20.glUniformMatrix3fv(loc(progAnalysis, "uUToBuf"), 1, false, colMajor3(u2b), 0)
        bindTex(0, GLES11Ext.GL_TEXTURE_EXTERNAL_OES, inp.tex, progAnalysis, "sCam")
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)

        val l = analysisListener
        val now = SystemClock.elapsedRealtime()
        if (l != null && !analysisBusy && now - lastRead >= 66) {
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

    /**
     * Kawase blur (4 passes, each averaging 4 diagonal neighbours at a growing distance) on the small U-space copy:
     * smooth in every direction, no streaks, ~0.2 ms on the tablet's GPU.
     */
    private fun renderBlur() {
        GLES20.glUseProgram(progBlur)
        bindQuad(progBlur)
        val k = .6f + 1.2f * blurAmount
        val passes = arrayOf(
            Triple(texA, fboB1, 1.0f), Triple(texB1, fboB2, 2.0f), Triple(texB2, fboB1, 3.0f), Triple(texB1, fboB2, 4.0f),
        )
        GLES20.glViewport(0, 0, bW, bH)
        for ((src, dst, off) in passes) {
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, dst)
            bindTex(0, GLES20.GL_TEXTURE_2D, src, progBlur, "sSrc")
            GLES20.glUniform2f(loc(progBlur, "uStep"), off * k / bW, off * k / bH)
            GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        }
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0)
    }

    private fun uploadPending() {
        if (overlayPending.getAndSet(false)) {
            val b = overlayBitmap
            if (b == null || b.isRecycled) hasOverlay = false
            else { texOverlay = upload(texOverlay, b); hasOverlay = true; if (overlayLogs++ < 3) PrismLog.i("FX overlay uploaded ${b.width}x${b.height}") }
        }
        if (bgPending.getAndSet(false)) {
            val b = bgBitmap
            if (b == null || b.isRecycled) hasBg = false
            else { texBg = upload(texBg, b); hasBg = true }
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
        if (texMask == 0) {
            // a 1×1 white mask so samplers always have something bound
            texMask = newTex()
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texMask)
            GLES20.glPixelStorei(GLES20.GL_UNPACK_ALIGNMENT, 1)
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_LUMINANCE, 1, 1, 0, GLES20.GL_LUMINANCE, GLES20.GL_UNSIGNED_BYTE, ByteBuffer.allocateDirect(1).put(255.toByte()).apply { position(0) })
        }
    }

    private fun upload(tex: Int, b: Bitmap): Int {
        val t = if (tex == 0) newTex() else tex
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, t)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, b, 0)
        return t
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

    // ───────────────────────────── setup ─────────────────────────────

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
        prog = program(VS_MAIN, FS_MAIN)
        progAnalysis = program(VS_ANALYSIS, FS_ANALYSIS)
        progBlur = program(VS_PLAIN, FS_BLUR)
        ready = prog != 0 && progAnalysis != 0 && progBlur != 0
        PrismLog.i("FX gl ready=$ready ${GLES20.glGetString(GLES20.GL_RENDERER)}")
    }

    private fun program(vs: String, fs: String): Int {
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
        GLES20.glLinkProgram(p)
        val ok = IntArray(1); GLES20.glGetProgramiv(p, GLES20.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) { PrismLog.i("ERR link ${GLES20.glGetProgramInfoLog(p)}"); return 0 }
        return p
    }

    companion object {
        /** android.graphics.Matrix (row-major 3×3) → GL column-major mat3 */
        fun colMajor3(m: Matrix): FloatArray {
            val v = FloatArray(9); m.getValues(v)
            return floatArrayOf(v[0], v[3], v[6], v[1], v[4], v[7], v[2], v[5], v[8])
        }

        /** ColorMatrix 4×5 (row-major, offsets in 0..255) → GL column-major mat4 (offsets passed separately). */
        fun colorMat4(cm: FloatArray): FloatArray {
            val m = FloatArray(16)
            for (r in 0 until 4) for (c in 0 until 4) m[c * 4 + r] = cm[r * 5 + c]
            return m
        }

        private const val VS_MAIN = """
            attribute vec4 aPos; attribute vec4 aTex;
            uniform mat4 uTexM; uniform mat3 uBufToU;
            varying vec2 vTex; varying vec2 vU;
            void main() {
                gl_Position = aPos;
                vTex = (uTexM * vec4(aTex.xy, 0.0, 1.0)).xy;
                vU = (uBufToU * vec3(aTex.xy, 1.0)).xy;
            }"""
        private const val FS_MAIN = """
            #extension GL_OES_EGL_image_external : require
            precision highp float;
            uniform samplerExternalOES sCam;
            uniform sampler2D sOverlay; uniform sampler2D sMask; uniform sampler2D sBlur; uniform sampler2D sBg;
            uniform mat4 uColor; uniform vec4 uColorOff;
            uniform float uVignette; uniform float uGrain; uniform float uTime;
            uniform int uBgMode; uniform float uHasOverlay;
            varying vec2 vTex; varying vec2 vU;
            float hash(vec2 p) { return fract(sin(dot(p, vec2(12.9898, 78.233)) + uTime) * 43758.5453); }
            void main() {
                vec4 c = texture2D(sCam, vTex);
                if (uBgMode > 0) {
                    float m = smoothstep(0.30, 0.70, texture2D(sMask, vU).r);
                    vec3 back = uBgMode == 1 ? texture2D(sBlur, vU).rgb : texture2D(sBg, vU).rgb;
                    c.rgb = mix(back, c.rgb, m);
                }
                c = clamp(uColor * c + uColorOff, 0.0, 1.0);
                if (uVignette > 0.0) {
                    vec2 d = vU - 0.5;
                    c.rgb *= 1.0 - uVignette * smoothstep(0.30, 0.85, length(d) * 1.2);
                }
                if (uGrain > 0.0) c.rgb += (hash(vU * 1000.0) - 0.5) * uGrain;
                if (uHasOverlay > 0.5) {
                    vec4 o = texture2D(sOverlay, vU);
                    c.rgb = o.rgb + c.rgb * (1.0 - o.a);
                }
                gl_FragColor = vec4(c.rgb, 1.0);
            }"""
        private const val VS_ANALYSIS = """
            attribute vec4 aPos; attribute vec4 aTex;
            uniform mat4 uTexM; uniform mat3 uUToBuf;
            varying vec2 vTex;
            void main() {
                gl_Position = aPos;
                // FBO row 0 (bottom) = top of the upright picture, so a read-back comes out top-down
                vec2 u = vec2(aTex.x, aTex.y);
                vec2 b = (uUToBuf * vec3(u, 1.0)).xy;
                vTex = (uTexM * vec4(b, 0.0, 1.0)).xy;
            }"""
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
