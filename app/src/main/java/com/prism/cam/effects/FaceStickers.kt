package com.prism.cam.effects

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.SystemClock
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Stickers that sit on faces (MediaPipe face mesh, 478 points, U space). Sized from the eye distance and turned with
 * the head (eye line), so they follow the face naturally.
 */
object FaceStickers {
    val ids = listOf("glasses", "crown", "cat", "blush", "hearts", "clown")
    /** chip labels: Thai source text */
    val names = mapOf("glasses" to "แว่นดำ", "crown" to "มงกุฎ", "cat" to "แมวเหมียว", "blush" to "แก้มแดง", "hearts" to "หัวใจ", "clown" to "จมูกตัวตลก")
    val icons = mapOf("glasses" to "🕶️", "crown" to "👑", "cat" to "🐱", "blush" to "☺️", "hearts" to "💕", "clown" to "🔴")

    private val emoji = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.CENTER }
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)

    fun draw(c: Canvas, w: Int, h: Int, faces: List<FloatArray>, id: String) {
        for (f in faces) {
            if (f.size < 300 * 2) continue
            fun px(i: Int) = f[i * 2] * w
            fun py(i: Int) = f[i * 2 + 1] * h
            val lx = px(33); val ly = py(33); val rx = px(263); val ry = py(263)
            val eye = hypot(rx - lx, ry - ly).coerceAtLeast(4f)
            val roll = Math.toDegrees(atan2((ry - ly).toDouble(), (rx - lx).toDouble())).toFloat()
            val mx = (lx + rx) / 2; val my = (ly + ry) / 2
            // "up" on the face (perpendicular to the eye line)
            val r = Math.toRadians(roll.toDouble())
            val ux = sin(r).toFloat(); val uy = -cos(r).toFloat()
            when (id) {
                "glasses" -> glyph(c, "🕶️", mx, my + eye * .05f, eye * 1.95f, roll)
                "crown" -> {
                    val tx = px(10); val ty = py(10)
                    glyph(c, "👑", tx + ux * eye * .75f, ty + uy * eye * .75f, eye * 1.7f, roll)
                }
                "cat" -> cat(c, px(10), py(10), px(1), py(1), eye, roll, ux, uy)
                "blush" -> {
                    for (k in intArrayOf(50, 280)) {
                        val cx = px(k); val cy = py(k)
                        fill.shader = RadialGradient(cx, cy, eye * .42f, intArrayOf(0x99FF6B8B.toInt(), 0x00FF6B8B), null, Shader.TileMode.CLAMP)
                        c.drawCircle(cx, cy, eye * .42f, fill)
                    }
                    fill.shader = null
                    glyph(c, "✨", px(10) + ux * eye * .2f - (uy * eye), py(10) + uy * eye * .2f + ux * eye, eye * .55f, roll)
                }
                "hearts" -> {
                    val t = (SystemClock.uptimeMillis() % 6000) / 6000f * 360f
                    val cx = px(10) + ux * eye * .5f; val cy = py(10) + uy * eye * .5f
                    for (k in 0 until 5) {
                        val a = Math.toRadians((t + k * 72).toDouble())
                        val ox = (cos(a) * eye * 1.25f).toFloat(); val oy = (sin(a) * eye * .35f).toFloat()
                        glyph(c, "❤️", cx + ox, cy + oy, eye * (.42f + .12f * sin(a).toFloat()), 0f)
                    }
                }
                "clown" -> {
                    val nx = px(1); val ny = py(1); val rad = eye * .3f
                    fill.shader = RadialGradient(nx - rad * .3f, ny - rad * .35f, rad * 1.2f, intArrayOf(0xFFFF8A8A.toInt(), 0xFFE0242F.toInt(), 0xFF9A0F18.toInt()), floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
                    c.drawCircle(nx, ny, rad, fill)
                    fill.shader = null
                    fill.color = 0xCCFFFFFF.toInt(); c.drawCircle(nx - rad * .35f, ny - rad * .4f, rad * .18f, fill)
                }
            }
        }
    }

    private fun glyph(c: Canvas, s: String, x: Float, y: Float, size: Float, rot: Float) {
        emoji.textSize = size
        val fm = emoji.fontMetrics
        c.save(); c.translate(x, y); c.rotate(rot)
        c.drawText(s, 0f, -(fm.ascent + fm.descent) / 2, emoji)
        c.restore()
    }

    private fun cat(c: Canvas, tx: Float, ty: Float, nx: Float, ny: Float, eye: Float, roll: Float, ux: Float, uy: Float) {
        c.save(); c.translate(tx, ty); c.rotate(roll)
        // two ears above the forehead (drawn in face-aligned space: x along the eyes, y down)
        val ear = Path()
        for (sgn in intArrayOf(-1, 1)) {
            val bx = sgn * eye * .85f
            ear.reset()
            ear.moveTo(bx - eye * .42f, -eye * .25f)
            ear.lineTo(bx + sgn * eye * .12f, -eye * 1.25f)
            ear.lineTo(bx + eye * .42f, -eye * .25f); ear.close()
            fill.color = 0xFF3A3340.toInt(); c.drawPath(ear, fill)
            ear.reset()
            ear.moveTo(bx - eye * .24f, -eye * .34f)
            ear.lineTo(bx + sgn * eye * .1f, -eye * 1.0f)
            ear.lineTo(bx + eye * .24f, -eye * .34f); ear.close()
            fill.color = 0xFFFFA8C5.toInt(); c.drawPath(ear, fill)
        }
        c.restore()
        // nose + whiskers
        c.save(); c.translate(nx, ny); c.rotate(roll)
        val nose = Path().apply { moveTo(-eye * .1f, -eye * .05f); lineTo(eye * .1f, -eye * .05f); lineTo(0f, eye * .08f); close() }
        fill.color = 0xFFFF7FA6.toInt(); c.drawPath(nose, fill)
        val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = eye * .03f; color = 0xE6FFFFFF.toInt(); strokeCap = Paint.Cap.ROUND }
        for (sgn in intArrayOf(-1, 1)) for (k in -1..1) {
            c.drawLine(sgn * eye * .25f, eye * .1f + k * eye * .08f, sgn * eye * .85f, eye * .02f + k * eye * .16f, stroke)
        }
        c.restore()
        @Suppress("UNUSED_VARIABLE") val unused = ux + uy
    }
}
