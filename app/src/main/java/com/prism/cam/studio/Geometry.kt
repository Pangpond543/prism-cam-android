package com.prism.cam.studio

import android.graphics.ColorMatrix
import com.prism.cam.effects.Looks
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Layer geometry shared by the GPU thread, the photo pass and the touch code. Canvas pixels: x → right, y → down.
 * A layer is a (possibly rotated) rectangle; its displayed aspect accounts for the crop.
 */
object Geometry {
    class Quad(val cx: Float, val cy: Float, val w: Float, val h: Float, val rot: Float, val sx: Float = 1f) {
        /** corners TL, TR, BR, BL in canvas px */
        fun corners(): FloatArray {
            val a = Math.toRadians(rot.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val hw = w * sx / 2; val hh = h / 2
            val p = floatArrayOf(-hw, -hh, hw, -hh, hw, hh, -hw, hh)
            for (i in 0 until 4) { val x = p[i * 2]; val y = p[i * 2 + 1]; p[i * 2] = cx + x * c - y * s; p[i * 2 + 1] = cy + x * s + y * c }
            return p
        }
        /** canvas px → local (0..1) inside the quad, or null when outside (with [pad] px of slack) */
        fun local(px: Float, py: Float, pad: Float = 0f): Pair<Float, Float>? {
            val a = Math.toRadians(-rot.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
            val dx = px - cx; val dy = py - cy
            val lx = dx * c - dy * s; val ly = dx * s + dy * c
            val hw = w * sx / 2; val hh = h / 2
            if (kotlin.math.abs(lx) > hw + pad || kotlin.math.abs(ly) > hh + pad) return null
            return ((lx + hw) / (2 * hw)) to ((ly + hh) / (2 * hh))
        }
    }

    /** displayed aspect (h / w) after the crop */
    fun shownAspect(l: Layer, canvasW: Float, canvasH: Float): Float {
        val base = if (l.type == LType.Camera || isFullCanvas(l.type)) canvasH / canvasW else l.aspect
        val c = cropOf(l.type, l.mask == "circle", floatArrayOf(l.cropL, l.cropT, l.cropR, l.cropB), base)
        val cw = (1f - c[0] - c[2]).coerceAtLeast(.02f); val ch = (1f - c[1] - c[3]).coerceAtLeast(.02f)
        return base * ch / cw
    }

    /**
     * Crop used for drawing: a circle with no crop of its own is cropped to the middle square of its content, so a
     * camera / picture bubble stays round whatever the picture shape (portrait, landscape, 16:9).
     */
    fun cropOf(type: LType, circle: Boolean, crop: FloatArray, aspect: Float): FloatArray {
        if (!circle || crop.any { it != 0f } || isFullCanvas(type)) return crop
        return if (aspect > 1f) { val t = (1f - 1f / aspect) / 2f; floatArrayOf(0f, t, 0f, t) } else { val s = (1f - aspect) / 2f; floatArrayOf(s, 0f, s, 0f) }
    }

    fun isFullCanvas(t: LType) = t == LType.Frame || t == LType.Drawing || t == LType.FaceFx || t == LType.HandFx

    fun quad(l: Layer, cw: Float, ch: Float): Quad {
        if (isFullCanvas(l.type)) return Quad(cw / 2, ch / 2, cw, ch, 0f)
        val w = l.w * cw
        return Quad(l.x * cw, l.y * ch, w, w * shownAspect(l, cw, ch), l.rot)
    }

    /** camera picture (upright camera space 0..1) → canvas px, through the camera layer's quad, crop and flip */
    fun camToCanvas(cam: RLayer?, front: Boolean, cw: Float, ch: Float, u: Float, v: Float): FloatArray {
        if (cam == null) return floatArrayOf(u * cw, v * ch)
        val flip = cam.flipX != front
        val cc = cropOf(LType.Camera, cam.circle, cam.crop, ch / cw)
        val cl = cc[0]; val ct = cc[1]; val cr = cc[2]; val cb = cc[3]
        var lu = (u - cl) / (1f - cl - cr).coerceAtLeast(.02f)
        val lv = (v - ct) / (1f - ct - cb).coerceAtLeast(.02f)
        if (flip) lu = 1f - lu
        val w = cam.w * cw; val h = w * (ch / cw) * (1f - ct - cb) / (1f - cl - cr).coerceAtLeast(.02f)
        val a = Math.toRadians(cam.rot.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val x = (lu - .5f) * w; val y = (lv - .5f) * h
        return floatArrayOf(cam.x * cw + x * c - y * s, cam.y * ch + x * s + y * c)
    }

    /** look + adjustments → one ColorMatrix (4×5) */
    fun colorOf(l: Layer): FloatArray {
        val cm = ColorMatrix(Looks.of(l.look).m)
        if (l.sat != 0f) cm.postConcat(ColorMatrix().apply { setSaturation((1f + l.sat).coerceAtLeast(0f)) })
        if (l.contrast != 0f) { val c = 1f + l.contrast; cm.postConcat(ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, 128f * (1 - c), 0f, c, 0f, 0f, 128f * (1 - c), 0f, 0f, c, 0f, 128f * (1 - c), 0f, 0f, 0f, 1f, 0f))) }
        if (l.bright != 0f) { val b = l.bright * 255f * .5f; cm.postConcat(ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, b, 0f, 1f, 0f, 0f, b, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))) }
        if (l.hue != 0f) cm.postConcat(hueMatrix(l.hue * 180f))
        return cm.array.copyOf()
    }

    private fun hueMatrix(deg: Float): ColorMatrix {
        val a = Math.toRadians(deg.toDouble()); val c = cos(a).toFloat(); val s = sin(a).toFloat()
        val lr = .213f; val lg = .715f; val lb = .072f
        return ColorMatrix(floatArrayOf(
            lr + c * (1 - lr) + s * -lr, lg + c * -lg + s * -lg, lb + c * -lb + s * (1 - lb), 0f, 0f,
            lr + c * -lr + s * .143f, lg + c * (1 - lg) + s * .14f, lb + c * -lb + s * -.283f, 0f, 0f,
            lr + c * -lr + s * -(1 - lr), lg + c * -lg + s * lg, lb + c * (1 - lb) + s * lb, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        ))
    }

    fun dist(ax: Float, ay: Float, bx: Float, by: Float) = hypot(ax - bx, ay - by)
}
