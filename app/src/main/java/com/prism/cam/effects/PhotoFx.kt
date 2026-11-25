package com.prism.cam.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.camera.core.ImageProxy
import com.prism.cam.PrismLog
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Photo path when effects are on: the full-resolution still replaces the live camera picture in the GPU compositor
 * ([GlFx.photo]) — the same layers, looks, AI background and face / hand positions the user saw, at photo resolution —
 * then it is turned to how the device was held. Runs on the camera io thread.
 */
object PhotoFx {
    class Job(
        /** rotation that makes the sensor picture upright (camera space, as the preview uses it) */
        val rotDisplay: Int,
        /** person mask for the still (AI background), or null */
        val mask: ((Bitmap) -> Pair<ByteBuffer, IntArray>?)?,
        /** the camera look's vignette (drawn here; the GPU photo pass leaves it out) */
        val vignette: Float,
    )

    /** ImageProxy (JPEG) → finished, upright bitmap (caller saves it). */
    fun process(img: ImageProxy, job: Job, fx: GlFx): Bitmap {
        val rotDevice = img.imageInfo.rotationDegrees
        var bmp = img.toBitmap()
        val crop = img.cropRect
        if (crop.width() in 1 until bmp.width || crop.height() in 1 until bmp.height) {
            bmp = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
        }
        // 1) camera space (upright, not mirrored — the compositor mirrors the front camera like the screen)
        bmp = rotate(bmp, job.rotDisplay, false)
        // 2) AI mask for the still
        val mask = job.mask?.invoke(bmp)
        // 3) the whole scene on the GPU at photo size
        val latch = CountDownLatch(1)
        var out: Bitmap? = null
        fx.photo(bmp, 0f, 0f, 1f, 1f, mask) { out = it; latch.countDown() }
        if (!latch.await(12, TimeUnit.SECONDS)) PrismLog.i("ERR photo pass timeout")
        bmp.recycle()
        var res = out ?: error("photo pass failed")
        if (job.vignette > 0f) {
            if (!res.isMutable) res = res.copy(Bitmap.Config.ARGB_8888, true)
            vignette(Canvas(res), res.width, res.height, job.vignette)
        }
        // 4) turn to how the device was held
        val extra = ((rotDevice - job.rotDisplay) % 360 + 360) % 360
        return rotate(res, extra, false)
    }

    fun rotate(src: Bitmap, deg: Int, mirror: Boolean): Bitmap {
        if (deg % 360 == 0 && !mirror) return src
        val m = Matrix()
        if (mirror) m.postScale(-1f, 1f)
        m.postRotate(deg.toFloat())
        val out = Bitmap.createBitmap(src, 0, 0, src.width, src.height, m, true)
        if (out !== src) src.recycle()
        return out
    }

    /** same falloff as the shader: darken from ~30 % to ~85 % of the half-diagonal */
    fun vignette(c: Canvas, w: Int, h: Int, amount: Float) {
        val r = 0.5f * kotlin.math.hypot(w.toFloat(), h.toFloat())
        val a = (amount * 255).toInt().coerceIn(0, 255)
        val g = RadialGradient(
            w / 2f, h / 2f, r / 1.2f * 0.85f * 2f / 1.0f,
            intArrayOf(0x00000000, 0x00000000, (a shl 24)), floatArrayOf(0f, 0.3f, 1f), Shader.TileMode.CLAMP,
        )
        c.drawRect(Rect(0, 0, w, h), Paint().apply { shader = g })
    }
}
