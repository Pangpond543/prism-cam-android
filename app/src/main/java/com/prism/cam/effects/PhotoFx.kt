package com.prism.cam.effects

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.Shader
import androidx.camera.core.ImageProxy

/**
 * Photo path when effects are on: the full-resolution still (not the GPU preview) gets the same look, background,
 * overlays and face stickers on the CPU, in the same U space the user saw, then it is turned to how the device was
 * held. Runs on the camera io thread.
 */
object PhotoFx {
    class Job(
        val look: Look,
        /** bitmap → overlay picture of the same size, drawn on top (text, stickers, frames, face stickers) */
        val overlay: ((Bitmap) -> Unit)?,
        /** person mask / background step (AI), applied before the look */
        val background: ((Bitmap) -> Bitmap)?,
        /** rotation that makes the sensor picture look like the screen (U space) */
        val rotDisplay: Int,
        /** mirror like the preview (front camera) */
        val mirror: Boolean,
    )

    /** ImageProxy (JPEG) → finished, upright bitmap (caller saves it). */
    fun process(img: ImageProxy, job: Job): Bitmap {
        val rotDevice = img.imageInfo.rotationDegrees
        var bmp = img.toBitmap()
        val crop = img.cropRect
        if (crop.width() in 1 until bmp.width || crop.height() in 1 until bmp.height) {
            bmp = Bitmap.createBitmap(bmp, crop.left, crop.top, crop.width(), crop.height())
        }
        // 1) to U space (as the screen showed it)
        bmp = rotate(bmp, job.rotDisplay, job.mirror)
        // 2) background (AI)
        job.background?.let { bmp = it(bmp) }
        // 3) look
        bmp = applyLook(bmp, job.look)
        // 4) overlay
        job.overlay?.invoke(bmp)
        // 5) turn to how the device was held
        val extra = ((rotDevice - job.rotDisplay) % 360 + 360) % 360
        return rotate(bmp, extra, false)
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

    fun applyLook(src: Bitmap, look: Look): Bitmap {
        val identity = look.m.contentEquals(Looks.IDENTITY)
        if (identity && look.vignette <= 0f) return src.let { if (it.isMutable) it else it.copy(Bitmap.Config.ARGB_8888, true).also { _ -> it.recycle() } }
        val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
        val c = Canvas(out)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        if (!identity) p.colorFilter = ColorMatrixColorFilter(ColorMatrix(look.m))
        c.drawBitmap(src, 0f, 0f, p)
        src.recycle()
        if (look.vignette > 0f) vignette(c, out.width, out.height, look.vignette)
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
