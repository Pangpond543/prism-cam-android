package com.prism.cam.effects

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.core.content.res.ResourcesCompat
import com.prism.cam.R
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.max

/**
 * What is drawn on top of the picture: text, stickers and a frame. Positions are in U space (0..1 of the picture the
 * user sees), sizes relative to the picture height, so the same scene renders onto the live preview / video (GPU
 * overlay texture) and onto a full-resolution photo (CPU).
 */
object Scene {
    sealed class Item(val id: Long) {
        var x by mutableStateOf(.5f)
        var y by mutableStateOf(.5f)
        var scale by mutableStateOf(1f)
        var rot by mutableStateOf(0f)
        /** last rendered half size, U units (for hit-testing and the selection box) */
        var halfW = .08f
        var halfH = .05f
    }

    class Text(id: Long, text: String, color: Int, style: Int) : Item(id) {
        var text by mutableStateOf(text)
        var color by mutableIntStateOf(color)
        /** 0 shadow · 1 outline · 2 pill · 3 Prism gradient */
        var style by mutableIntStateOf(style)
    }

    class Sticker(id: Long, val emoji: String) : Item(id)

    val items = mutableStateListOf<Item>()
    var selected by mutableStateOf<Long?>(null)
    /** none · white · round · film · prism · date · cinema */
    var frame by mutableStateOf("none")
    /** bumps on every change so renderers know to redraw */
    var version by mutableIntStateOf(0)
    private var nextId = 1L

    /** face sticker id (null = off) and the faces the AI last saw (478 x,y pairs each, U space) */
    var faceSticker by mutableStateOf<String?>(null)
    var faces by mutableStateOf<List<FloatArray>>(emptyList())

    val isEmpty get() = items.isEmpty() && frame == "none" && faceSticker == null

    fun touch() { version++ }

    fun addText(text: String, color: Int = Color.WHITE, style: Int = 0): Text {
        val t = Text(nextId++, text, color, style).apply { y = .42f + .05f * (items.size % 4) }
        items += t; selected = t.id; touch(); return t
    }

    fun addSticker(e: String): Sticker {
        val s = Sticker(nextId++, e).apply { x = .5f + .06f * ((items.size % 5) - 2); y = .5f; scale = 1.4f }
        items += s; selected = s.id; touch(); return s
    }

    fun remove(id: Long) { items.removeAll { it.id == id }; if (selected == id) selected = null; touch() }
    fun clear() { items.clear(); frame = "none"; selected = null; touch() }
    fun bringToFront(it: Item) { items.remove(it); items += it }

    /** top-most item under a point (U units, aspect = w/h of the picture) */
    fun hit(u: Float, v: Float, aspect: Float): Item? = items.lastOrNull { i ->
        val dx = (u - i.x) * aspect; val dy = v - i.y
        val a = Math.toRadians(-i.rot.toDouble())
        val rx = (dx * kotlin.math.cos(a) - dy * kotlin.math.sin(a)).toFloat()
        val ry = (dx * kotlin.math.sin(a) + dy * kotlin.math.cos(a)).toFloat()
        kotlin.math.abs(rx) <= i.halfW * aspect + .02f && kotlin.math.abs(ry) <= i.halfH + .02f
    }

    // ───────────────────────────── rendering ─────────────────────────────

    private var typeface: Typeface? = null
    fun init(ctx: Context) { typeface = ResourcesCompat.getFont(ctx, R.font.plex_thai_semibold) }

    /** Draw the whole scene onto [c] (w × h = the picture). Clears nothing — caller decides. */
    fun render(
        c: Canvas, w: Int, h: Int, items: List<Item> = this.items.toList(), frame: String = this.frame,
        faces: List<FloatArray> = this.faces, faceSticker: String? = this.faceSticker,
    ) {
        if (faceSticker != null) FaceStickers.draw(c, w, h, faces, faceSticker)
        for (i in items) when (i) {
            is Text -> drawText(c, w, h, i)
            is Sticker -> drawSticker(c, w, h, i)
        }
        drawFrame(c, w, h, frame)
    }

    /** Fresh transparent bitmap with the scene (GPU overlay texture). */
    fun renderBitmap(w: Int, h: Int, reuse: Bitmap?): Bitmap {
        val b = if (reuse != null && !reuse.isRecycled && reuse.width == w && reuse.height == h) reuse
        else Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.TRANSPARENT)
        render(Canvas(b), w, h)
        return b
    }

    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private fun drawText(c: Canvas, w: Int, h: Int, t: Text) {
        val size = h * .062f * t.scale
        val p = textPaint.apply {
            reset(); isAntiAlias = true; typeface = this@Scene.typeface; textSize = size; textAlign = Paint.Align.CENTER
            shader = null; style = Paint.Style.FILL; clearShadowLayer()
        }
        val lines = t.text.ifBlank { " " }.split('\n')
        val lineH = size * 1.32f
        val widest = lines.maxOf { p.measureText(it) }
        val blockH = lineH * lines.size
        val padX = if (t.style == 2) size * .55f else size * .12f
        val padY = if (t.style == 2) size * .32f else size * .1f
        t.halfW = (widest / 2 + padX) / w
        t.halfH = (blockH / 2 + padY) / h
        c.save()
        c.translate(t.x * w, t.y * h)
        c.rotate(t.rot)
        val fm = p.fontMetrics
        val baseline0 = -blockH / 2 + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
        if (t.style == 2) {
            val r = RectF(-widest / 2 - padX, -blockH / 2 - padY, widest / 2 + padX, blockH / 2 + padY)
            c.drawRoundRect(r, size * .5f, size * .5f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() })
        }
        lines.forEachIndexed { k, line ->
            val y = baseline0 + k * lineH
            when (t.style) {
                1 -> {
                    c.drawText(line, 0f, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = size * .14f; color = if (isLight(t.color)) Color.BLACK else Color.WHITE; strokeJoin = Paint.Join.ROUND })
                    c.drawText(line, 0f, y, p.apply { color = t.color })
                }
                3 -> {
                    p.shader = LinearGradient(-widest / 2, 0f, widest / 2, 0f, intArrayOf(0xFF8EA2FF.toInt(), 0xFFC79BFF.toInt(), 0xFFFF9EC7.toInt(), 0xFFFFC79A.toInt()), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
                    p.setShadowLayer(size * .12f, 0f, size * .04f, 0x66000000)
                    c.drawText(line, 0f, y, p)
                }
                else -> {
                    p.color = t.color
                    if (t.style == 0) p.setShadowLayer(size * .14f, 0f, size * .05f, 0x99000000.toInt())
                    c.drawText(line, 0f, y, p)
                }
            }
        }
        c.restore()
    }

    private fun isLight(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150

    private fun drawSticker(c: Canvas, w: Int, h: Int, s: Sticker) {
        val size = h * .11f * s.scale
        val p = textPaint.apply { reset(); isAntiAlias = true; textSize = size; textAlign = Paint.Align.CENTER; typeface = null; shader = null }
        val fm = p.fontMetrics
        val ew = p.measureText(s.emoji)
        s.halfW = max(ew, size) / 2 / w
        s.halfH = (fm.descent - fm.ascent) / 2 / h
        c.save()
        c.translate(s.x * w, s.y * h)
        c.rotate(s.rot)
        c.drawText(s.emoji, 0f, -(fm.ascent + fm.descent) / 2, p)
        c.restore()
    }

    private fun drawFrame(c: Canvas, w: Int, h: Int, f: String) {
        val m = minOf(w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        when (f) {
            "white" -> {
                p.color = Color.WHITE
                val b = m * .035f; val bottom = m * .13f
                c.drawRect(0f, 0f, w.toFloat(), b, p)
                c.drawRect(0f, 0f, b, h.toFloat(), p)
                c.drawRect(w - b, 0f, w.toFloat(), h.toFloat(), p)
                c.drawRect(0f, h - bottom, w.toFloat(), h.toFloat(), p)
            }
            "round" -> {
                val r = m * .07f; val b = m * .025f
                val path = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
                    addRoundRect(RectF(b, b, w - b, h - b), r, r, Path.Direction.CW)
                }
                p.color = Color.WHITE; c.drawPath(path, p)
            }
            "film" -> {
                p.color = 0xFF111111.toInt()
                val bar = h * .085f
                c.drawRect(0f, 0f, w.toFloat(), bar, p); c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), p)
                p.color = 0xFFE8E2D0.toInt()
                val hw = bar * .38f; val hh = bar * .42f; val gap = hw * 1.25f
                var x = gap / 2
                while (x < w) {
                    c.drawRoundRect(RectF(x, (bar - hh) / 2, x + hw, (bar + hh) / 2), hw * .2f, hw * .2f, p)
                    c.drawRoundRect(RectF(x, h - (bar + hh) / 2, x + hw, h - (bar - hh) / 2), hw * .2f, hw * .2f, p)
                    x += hw + gap
                }
            }
            "prism" -> {
                val b = m * .03f
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF8EA2FF.toInt(), 0xFFC79BFF.toInt(), 0xFFFF9EC7.toInt(), 0xFFFFC79A.toInt()), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
                val path = Path().apply {
                    fillType = Path.FillType.EVEN_ODD
                    addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
                    addRoundRect(RectF(b, b, w - b, h - b), b * 1.4f, b * 1.4f, Path.Direction.CW)
                }
                c.drawPath(path, p)
            }
            "cinema" -> {
                // 2.39:1 letterbox inside the picture
                val target = w / 2.39f
                if (target < h) {
                    p.color = Color.BLACK
                    val bar = (h - target) / 2
                    c.drawRect(0f, 0f, w.toFloat(), bar, p); c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), p)
                }
            }
            "date" -> {
                val s = SimpleDateFormat("''yy  MM  dd", Locale.US).format(Date())
                p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
                p.textSize = m * .055f; p.textAlign = Paint.Align.RIGHT
                p.color = 0xFFFF9A3C.toInt()
                p.setShadowLayer(m * .012f, 0f, 0f, 0xCCFF6A00.toInt())
                c.drawText(s, w - m * .05f, h - m * .05f, p)
            }
        }
    }

    val frames = listOf("none", "white", "round", "film", "prism", "date", "cinema")
    val stickers = listOf("❤️", "✨", "⭐", "🌈", "🔥", "😎", "🥳", "😂", "😍", "🤩", "👑", "🎉", "🌸", "🍀", "☀️", "🌙", "⚡", "💯", "👍", "🙏", "🐱", "🐶", "🦄", "🍰")
    val textColors = listOf(Color.WHITE, 0xFF111111.toInt(), 0xFFFFD98A.toInt(), 0xFFFF6B8B.toInt(), 0xFF7C8CFF.toInt(), 0xFF3FCFA4.toInt(), 0xFFFF9A3C.toInt(), 0xFFC79BFF.toInt())
}
