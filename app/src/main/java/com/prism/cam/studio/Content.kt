package com.prism.cam.studio

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ImageDecoder
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.AnimatedImageDrawable
import android.media.ExifInterface
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import androidx.core.content.res.ResourcesCompat
import com.prism.cam.PrismLog
import com.prism.cam.R
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Pixels of the content layers (text, sticker, shape, frame, image, GIF, drawing) drawn on the CPU into bitmaps that
 * become GPU textures. Rendered only when the content changes; moving / turning / resizing happens on the GPU.
 * Also the media library: files picked by the user are copied into the app (`files/studio/media`).
 */
object Content {
    private lateinit var app: Context
    lateinit var mediaDir: File; private set
    private var font: Typeface? = null

    fun init(ctx: Context) {
        app = ctx.applicationContext
        mediaDir = File(ctx.filesDir, "studio/media").apply { mkdirs() }
        font = ResourcesCompat.getFont(ctx, R.font.plex_thai_semibold)
    }

    // ───────────────────────────── media library ─────────────────────────────

    class Media(val file: File, val kind: LType, val gif: Boolean, val w: Int, val h: Int)

    fun kindOf(name: String): Pair<LType, Boolean> = when (name.substringAfterLast('.').lowercase()) {
        "mp4", "webm", "mov", "mkv", "3gp", "m4v" -> LType.Video to false
        "gif" -> LType.Image to true
        else -> LType.Image to false
    }

    /** copy a picked file into the library (same file twice = same copy) */
    fun import(uri: Uri): Media? = try {
        val r = app.contentResolver
        val mime = r.getType(uri) ?: ""
        val ext = when {
            mime.contains("gif") -> "gif"; mime.startsWith("video") -> "mp4"; mime.contains("png") -> "png"; mime.contains("webp") -> "webp"
            else -> "jpg"
        }
        val size = r.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1
        val sig = "${uri.lastPathSegment}|$size".hashCode().toUInt().toString(16)
        val f = File(mediaDir, "m_$sig.$ext")
        if (!f.exists()) r.openInputStream(uri)?.use { i -> f.outputStream().use { o -> i.copyTo(o) } }
        info(f)
    } catch (t: Throwable) { PrismLog.i("ERR import ${t.message}"); null }

    fun info(f: File): Media? {
        val (kind, gif) = kindOf(f.name)
        return if (kind == LType.Video) {
            MediaMetadataRetriever().run {
                try {
                    setDataSource(f.path)
                    var w = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 16
                    var h = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 9
                    val rot = extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
                    if (rot % 180 != 0) { val t = w; w = h; h = t }
                    Media(f, kind, false, w, h)
                } finally { release() }
            }
        } else {
            val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(f.path, o)
            var w = o.outWidth; var h = o.outHeight
            if (exifTurn(f) % 180 != 0) { val t = w; w = h; h = t }
            Media(f, kind, gif, w.coerceAtLeast(1), h.coerceAtLeast(1))
        }
    }

    fun library(): List<Media> = (mediaDir.listFiles() ?: emptyArray()).sortedByDescending { it.lastModified() }.mapNotNull { info(it) }

    private fun exifTurn(f: File): Int = try {
        when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
            ExifInterface.ORIENTATION_ROTATE_90 -> 90; ExifInterface.ORIENTATION_ROTATE_180 -> 180; ExifInterface.ORIENTATION_ROTATE_270 -> 270; else -> 0
        }
    } catch (_: Throwable) { 0 }

    fun thumbnail(m: Media, px: Int = 160): Bitmap? = try {
        if (m.kind == LType.Video) MediaMetadataRetriever().run { try { setDataSource(m.file.path); getFrameAtTime(0)?.let { scaleTo(it, px) } } finally { release() } }
        else decodeImage(m.file, px)
    } catch (_: Throwable) { null }

    fun decodeImage(f: File, maxPx: Int): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.path, o)
        var s = 1
        while (max(o.outWidth, o.outHeight) / (s * 2) >= maxPx) s *= 2
        val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = s }) ?: return null
        val turn = exifTurn(f)
        return if (turn == 0) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(turn.toFloat()) }, true)
    }

    private fun scaleTo(b: Bitmap, px: Int): Bitmap {
        val k = px.toFloat() / max(b.width, b.height)
        return if (k >= 1f) b else Bitmap.createScaledBitmap(b, (b.width * k).toInt().coerceAtLeast(1), (b.height * k).toInt().coerceAtLeast(1), true)
    }

    // ───────────────────────────── animated GIF ─────────────────────────────

    private val gifs = HashMap<String, AnimatedImageDrawable>()

    /** current frame of a GIF (Android 9+ animates; older shows the first frame) */
    fun gifFrame(file: File, target: Bitmap?, maxPx: Int): Bitmap? {
        if (Build.VERSION.SDK_INT < 28) return target ?: decodeImage(file, maxPx)
        val d = gifs.getOrPut(file.path) {
            (ImageDecoder.decodeDrawable(ImageDecoder.createSource(file)) as? AnimatedImageDrawable)?.apply { repeatCount = AnimatedImageDrawable.REPEAT_INFINITE; start() }
                ?: return decodeImage(file, maxPx)
        }
        val k = min(1f, maxPx.toFloat() / max(d.intrinsicWidth, d.intrinsicHeight))
        val w = (d.intrinsicWidth * k).toInt().coerceAtLeast(1); val h = (d.intrinsicHeight * k).toInt().coerceAtLeast(1)
        val b = target?.takeIf { it.width == w && it.height == h && !it.isRecycled } ?: Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        b.eraseColor(Color.TRANSPARENT)
        d.setBounds(0, 0, w, h); d.draw(Canvas(b))
        return b
    }

    // ───────────────────────────── layer pixels ─────────────────────────────

    /**
     * Render the layer's pixels. [cw]/[ch] = canvas px, [k] = extra sharpness (photo pass > 1).
     * Returns the bitmap and the content aspect (h / w) to store on the layer.
     */
    fun render(l: Layer, cw: Int, ch: Int, k: Float = 1f): Pair<Bitmap, Float>? = try {
        when (l.type) {
            LType.Text -> text(l, cw, k)
            LType.Sticker -> sticker(l, cw, k)
            LType.Shape -> shape(l, cw, k)
            LType.Frame -> full(cw, ch, k) { c, w, h -> frame(c, w, h, l.preset, l.color) }
            LType.Drawing -> full(cw, ch, k) { c, w, h -> strokes(c, w, h, l) }
            LType.FacePaint -> FaceArt.makePaint(l.preset, l.color) to 1f
            LType.Image -> File(mediaDir, l.media).takeIf { it.exists() }?.let { f -> decodeImage(f, (2048 * k).toInt().coerceAtMost(4096))?.let { it to it.height.toFloat() / it.width } }
            else -> null
        }
    } catch (t: Throwable) { PrismLog.i("ERR render ${l.type} ${t.message}"); null }

    private fun full(cw: Int, ch: Int, k: Float, draw: (Canvas, Int, Int) -> Unit): Pair<Bitmap, Float> {
        val s = min(k, 4096f / max(cw, ch))
        val w = (cw * s).toInt().coerceAtLeast(1); val h = (ch * s).toInt().coerceAtLeast(1)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        draw(Canvas(b), w, h)
        return b to h.toFloat() / w
    }

    private val tp = Paint(Paint.ANTI_ALIAS_FLAG or Paint.SUBPIXEL_TEXT_FLAG)

    private fun text(l: Layer, cw: Int, k: Float): Pair<Bitmap, Float> {
        val size = (cw * .06f * k).coerceIn(24f, 400f)
        val p = Paint(tp).apply { typeface = font; textSize = size; textAlign = Paint.Align.CENTER }
        val lines = l.text.ifBlank { " " }.split('\n')
        val lineH = size * 1.32f
        val widest = lines.maxOf { p.measureText(it) }.coerceAtLeast(size)
        val padX = if (l.style == 2) size * .6f else size * .3f
        val padY = if (l.style == 2) size * .35f else size * .25f
        val w = (widest + padX * 2).toInt(); val h = (lineH * lines.size + padY * 2).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        if (l.style == 2) c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h.toFloat()), h / 2f, h / 2f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xB3000000.toInt() })
        val fm = p.fontMetrics
        lines.forEachIndexed { i, line ->
            val y = padY + i * lineH + (lineH - (fm.descent - fm.ascent)) / 2 - fm.ascent
            val x = w / 2f
            when (l.style) {
                1 -> {
                    c.drawText(line, x, y, Paint(p).apply { style = Paint.Style.STROKE; strokeWidth = size * .14f; strokeJoin = Paint.Join.ROUND; color = if (isLight(l.color)) Color.BLACK else Color.WHITE })
                    c.drawText(line, x, y, p.apply { color = l.color })
                }
                3 -> {
                    p.shader = LinearGradient(x - widest / 2, 0f, x + widest / 2, 0f, intArrayOf(0xFF8EA2FF.toInt(), 0xFFC79BFF.toInt(), 0xFFFF9EC7.toInt(), 0xFFFFC79A.toInt()), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
                    p.setShadowLayer(size * .12f, 0f, size * .04f, 0x66000000)
                    c.drawText(line, x, y, p)
                }
                else -> { p.color = l.color; if (l.style == 0) p.setShadowLayer(size * .14f, 0f, size * .05f, 0x99000000.toInt()); c.drawText(line, x, y, p) }
            }
        }
        return b to h.toFloat() / w
    }

    private fun isLight(c: Int) = (Color.red(c) * 299 + Color.green(c) * 587 + Color.blue(c) * 114) / 1000 > 150

    private fun sticker(l: Layer, cw: Int, k: Float): Pair<Bitmap, Float> {
        val size = (cw * .12f * k).coerceIn(48f, 512f)
        val p = Paint(tp).apply { textSize = size; textAlign = Paint.Align.CENTER; typeface = null }
        val fm = p.fontMetrics
        val w = (max(p.measureText(l.text), size) * 1.1f).toInt(); val h = ((fm.descent - fm.ascent) * 1.05f).toInt()
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        Canvas(b).drawText(l.text, w / 2f, h / 2f - (fm.ascent + fm.descent) / 2, p)
        return b to h.toFloat() / w
    }

    private fun shape(l: Layer, cw: Int, k: Float): Pair<Bitmap, Float> {
        val asp = l.aspect.coerceIn(.05f, 20f)
        val w = (cw * .5f * k).coerceIn(64f, 2048f).toInt(); val h = (w * asp).toInt().coerceAtLeast(8)
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            if (l.grad) shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), l.color, l.color2, Shader.TileMode.CLAMP) else color = l.color
        }
        val r = RectF(0f, 0f, w.toFloat(), h.toFloat())
        val m = min(w, h).toFloat()
        when (l.shape) {
            "rect" -> c.drawRect(r, p)
            "circle" -> c.drawOval(r, p)
            "star" -> c.drawPath(star(w / 2f, h / 2f, m / 2f, m / 4.6f, 5), p)
            "heart" -> c.drawPath(heart(w.toFloat(), h.toFloat()), p)
            "arrow" -> c.drawPath(Path().apply { moveTo(0f, h * .35f); lineTo(w * .62f, h * .35f); lineTo(w * .62f, 0f); lineTo(w.toFloat(), h / 2f); lineTo(w * .62f, h.toFloat()); lineTo(w * .62f, h * .65f); lineTo(0f, h * .65f); close() }, p)
            "bubble" -> {
                c.drawRoundRect(RectF(0f, 0f, w.toFloat(), h * .8f), m * .18f, m * .18f, p)
                c.drawPath(Path().apply { moveTo(w * .2f, h * .78f); lineTo(w * .16f, h.toFloat()); lineTo(w * .38f, h * .78f); close() }, p)
            }
            else -> c.drawRoundRect(r, m * .2f, m * .2f, p)
        }
        return b to h.toFloat() / w
    }

    private fun star(cx: Float, cy: Float, ro: Float, ri: Float, n: Int) = Path().apply {
        for (i in 0 until n * 2) {
            val a = Math.PI / n * i - Math.PI / 2; val r = if (i % 2 == 0) ro else ri
            val x = cx + (cos(a) * r).toFloat(); val y = cy + (sin(a) * r).toFloat()
            if (i == 0) moveTo(x, y) else lineTo(x, y)
        }
        close()
    }

    private fun heart(w: Float, h: Float) = Path().apply {
        moveTo(w / 2, h * .95f)
        cubicTo(w * -.15f, h * .55f, w * .15f, h * -.1f, w / 2, h * .25f)
        cubicTo(w * .85f, h * -.1f, w * 1.15f, h * .55f, w / 2, h * .95f)
        close()
    }

    /** frames (full canvas) — same set as 1.0, plus a tint colour */
    fun frame(c: Canvas, w: Int, h: Int, f: String, tint: Int) {
        val m = min(w, h)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        fun ring(b: Float, r: Float, paint: Paint) {
            val path = Path().apply {
                fillType = Path.FillType.EVEN_ODD
                addRect(0f, 0f, w.toFloat(), h.toFloat(), Path.Direction.CW)
                addRoundRect(RectF(b, b, w - b, h - b), r, r, Path.Direction.CW)
            }
            c.drawPath(path, paint)
        }
        when (f) {
            "white" -> {
                p.color = tint
                val b = m * .035f; val bottom = m * .13f
                c.drawRect(0f, 0f, w.toFloat(), b, p); c.drawRect(0f, 0f, b, h.toFloat(), p)
                c.drawRect(w - b, 0f, w.toFloat(), h.toFloat(), p); c.drawRect(0f, h - bottom, w.toFloat(), h.toFloat(), p)
            }
            "round" -> { p.color = tint; ring(m * .025f, m * .07f, p) }
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
                p.shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), intArrayOf(0xFF8EA2FF.toInt(), 0xFFC79BFF.toInt(), 0xFFFF9EC7.toInt(), 0xFFFFC79A.toInt()), floatArrayOf(0f, .45f, .8f, 1f), Shader.TileMode.CLAMP)
                ring(m * .03f, m * .042f, p)
            }
            "cinema" -> {
                val target = w / 2.39f
                if (target < h) { p.color = Color.BLACK; val bar = (h - target) / 2; c.drawRect(0f, 0f, w.toFloat(), bar, p); c.drawRect(0f, h - bar, w.toFloat(), h.toFloat(), p) }
            }
            "date" -> {
                val s = SimpleDateFormat("''yy  MM  dd", Locale.US).format(Date())
                p.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD); p.textSize = m * .055f; p.textAlign = Paint.Align.RIGHT
                p.color = 0xFFFF9A3C.toInt(); p.setShadowLayer(m * .012f, 0f, 0f, 0xCCFF6A00.toInt())
                c.drawText(s, w - m * .05f, h - m * .05f, p)
            }
            "neon" -> {
                p.style = Paint.Style.STROKE; p.strokeWidth = m * .012f; p.color = tint
                p.setShadowLayer(m * .03f, 0f, 0f, tint)
                val b = m * .04f
                c.drawRoundRect(RectF(b, b, w - b, h - b), m * .05f, m * .05f, p)
            }
        }
    }

    val frames = listOf("white", "round", "film", "prism", "date", "cinema", "neon")

    fun strokes(c: Canvas, w: Int, h: Int, l: Layer) {
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        for (s in l.strokes.toList()) {
            if (s.pts.size < 4) continue
            val path = Path().apply { moveTo(s.pts[0] * w, s.pts[1] * h); var i = 2; while (i + 1 < s.pts.size) { lineTo(s.pts[i] * w, s.pts[i + 1] * h); i += 2 } }
            p.strokeWidth = s.width * min(w, h)
            if (s.glow) {
                p.color = s.color; p.setShadowLayer(p.strokeWidth * 1.6f, 0f, 0f, s.color); c.drawPath(path, p)
                p.clearShadowLayer(); p.color = Color.WHITE; p.strokeWidth *= .35f; c.drawPath(path, p)
            } else { p.clearShadowLayer(); p.color = s.color; c.drawPath(path, p) }
        }
    }

    val stickers = listOf("❤️", "✨", "⭐", "🌈", "🔥", "😎", "🥳", "😂", "😍", "🤩", "👑", "🎉", "🌸", "🍀", "☀️", "🌙", "⚡", "💯", "👍", "🙏", "🐱", "🐶", "🦄", "🍰",
        "🕶️", "🎀", "💋", "🦋", "🎧", "🎮", "🍓", "🧸", "💎", "🌟", "💫", "🫶")
    val textColors = listOf(Color.WHITE, 0xFF111111.toInt(), 0xFFFFD98A.toInt(), 0xFFFF6B8B.toInt(), 0xFF7C8CFF.toInt(), 0xFF3FCFA4.toInt(), 0xFFFF9A3C.toInt(), 0xFFC79BFF.toInt())
    val shapes = listOf("round", "rect", "circle", "star", "heart", "arrow", "bubble")
}
