package com.prism.cam.studio

import android.content.Context
import android.graphics.BlurMaskFilter
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.RadialGradient
import android.graphics.RectF
import android.graphics.Shader
import android.os.SystemClock
import com.prism.cam.PrismLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Face art ported from the desktop app (facepaint.js, avatar.js):
 * - the face mesh (MediaPipe canonical face model, Apache-2.0): texture position of each of the 468 points + triangles;
 * - face paints drawn in that texture layout (the GPU wraps them onto the tracked face);
 * - VTuber characters (cat, robot, ghost) that copy the tracked face — head turn / tilt / nod, blinks, mouth, smile,
 *   brows, gaze — and idle (breathe, blink, sway) when no face is seen.
 */
object FaceArt {
    // ───────────────────────────── mesh ─────────────────────────────
    lateinit var UV: FloatArray; private set
    lateinit var TRI: ShortArray; private set
    val ready get() = ::UV.isInitialized

    fun init(ctx: Context) {
        try {
            ctx.assets.open("face_mesh.txt").bufferedReader().useLines { lines ->
                for (ln in lines) {
                    val parts = ln.trim().split(' ')
                    when (parts[0]) {
                        "UV" -> UV = FloatArray(parts.size - 1) { parts[it + 1].toFloat() }
                        "TRI" -> TRI = ShortArray(parts.size - 1) { parts[it + 1].toInt().toShort() }
                    }
                }
            }
        } catch (t: Throwable) { PrismLog.i("ERR face mesh ${t.message}") }
    }

    private const val S = 1024
    private fun u(i: Int) = floatArrayOf(UV[i * 2] * S, UV[i * 2 + 1] * S)
    private val LIPS_OUT = intArrayOf(61, 146, 91, 181, 84, 17, 314, 405, 321, 375, 291, 409, 270, 269, 267, 0, 37, 39, 40, 185)
    private val LIPS_IN = intArrayOf(78, 95, 88, 178, 87, 14, 317, 402, 318, 324, 308, 415, 310, 311, 312, 13, 82, 81, 80, 191)
    val EYE_A = intArrayOf(33, 7, 163, 144, 145, 153, 154, 155, 133, 173, 157, 158, 159, 160, 161, 246)
    val EYE_B = intArrayOf(263, 249, 390, 373, 374, 380, 381, 382, 362, 398, 384, 385, 386, 387, 388, 466)
    val OVAL = intArrayOf(10, 338, 297, 332, 284, 251, 389, 356, 454, 323, 361, 288, 397, 365, 379, 378, 400, 377, 152, 148, 176, 149, 150, 136, 172, 58, 132, 93, 234, 127, 162, 21, 54, 103, 67, 109)
    private val BROW_A = intArrayOf(70, 63, 105, 66, 107)
    private val BROW_B = intArrayOf(300, 293, 334, 296, 336)
    /** outline loops for the tuning mesh view */
    val LOOPS get() = listOf(OVAL, EYE_A, EYE_B, LIPS_OUT, LIPS_IN)

    // ───────────────────────────── face paints ─────────────────────────────
    val paints = listOf("blush", "lips", "cat", "panda", "hero", "stars", "hearts", "freckles", "tiger", "neon")
    val paintNames = mapOf("blush" to "แก้มชมพู", "lips" to "ลิปสติก", "cat" to "หน้าแมว", "panda" to "ตาแพนด้า", "hero" to "หน้ากากฮีโร่",
        "stars" to "ดาวกากเพชร", "hearts" to "แก้มหัวใจ", "freckles" to "กระน่ารัก", "tiger" to "ลายเสือ", "neon" to "เส้นนีออน")
    val paintColors = mapOf("blush" to 0xFFFF6B9A, "lips" to 0xFFD6204F, "cat" to 0xFF2A1F2B, "panda" to 0xFF151320, "hero" to 0xFF2B2D6E,
        "stars" to 0xFFFFD34D, "hearts" to 0xFFFF4F7B, "freckles" to 0xFF9A5A3C, "tiger" to 0xFF16110D, "neon" to 0xFF6EF3FF).mapValues { it.value.toInt() }

    private fun poly(p: Path, idx: IntArray, k: Float = 1f): FloatArray {
        var cx = 0f; var cy = 0f
        for (i in idx) { val q = u(i); cx += q[0]; cy += q[1] }
        cx /= idx.size; cy /= idx.size
        idx.forEachIndexed { n, i -> val q = u(i); val x = cx + (q[0] - cx) * k; val y = cy + (q[1] - cy) * k; if (n == 0) p.moveTo(x, y) else p.lineTo(x, y) }
        p.close(); return floatArrayOf(cx, cy)
    }
    private fun dist(a: Int, b: Int) = hypot(u(a)[0] - u(b)[0], u(a)[1] - u(b)[1])
    private fun heart(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        val path = Path(); path.moveTo(x, y + r * .9f)
        path.cubicTo(x - r * 1.6f, y - r * .2f, x - r * .7f, y - r * 1.3f, x, y - r * .45f)
        path.cubicTo(x + r * .7f, y - r * 1.3f, x + r * 1.6f, y - r * .2f, x, y + r * .9f)
        c.drawPath(path, p)
    }
    private fun star(c: Canvas, x: Float, y: Float, r: Float, p: Paint) {
        val path = Path()
        for (i in 0 until 10) { val a = -PI / 2 + i * PI / 5; val q = if (i % 2 == 1) r * .42f else r; val px = x + cos(a).toFloat() * q; val py = y + sin(a).toFloat() * q; if (i == 0) path.moveTo(px, py) else path.lineTo(px, py) }
        path.close(); c.drawPath(path, p)
    }
    private var seed = 1L
    private fun rnd(): Float { seed = (seed * 16807) % 2147483647; return seed / 2147483647f }
    private fun withA(col: Int, a: Int) = (col and 0xFFFFFF) or (a shl 24)

    /** a built-in paint as a 1024² texture in face-mesh layout */
    fun makePaint(id: String, color: Int): Bitmap {
        val b = Bitmap.createBitmap(S, S, Bitmap.Config.ARGB_8888)
        if (!ready) return b
        val c = Canvas(b)
        val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
        seed = 7
        val fw = dist(234, 454)
        fun fill(col: Int) = Paint(p).apply { style = Paint.Style.FILL; this.color = col }
        fun stroke(col: Int, w: Float) = Paint(p).apply { style = Paint.Style.STROKE; this.color = col; strokeWidth = w }
        when (id) {
            "blush" -> for (i in intArrayOf(50, 280)) {
                val (x, y) = u(i).let { it[0] to it[1] }; val r = fw * .13f
                c.drawCircle(x, y, r, Paint(p).apply { shader = RadialGradient(x, y, r, withA(color, 0xAA), withA(color, 0), Shader.TileMode.CLAMP) })
            }
            "lips" -> {
                val path = Path().apply { fillType = Path.FillType.EVEN_ODD }; poly(path, LIPS_OUT); poly(path, LIPS_IN)
                c.drawPath(path, fill(color))
                val q = u(14); c.save(); c.rotate(-11.5f, q[0], q[1])
                c.drawOval(RectF(q[0] - fw * .03f - fw * .035f, q[1] + fw * .025f - fw * .01f, q[0] - fw * .03f + fw * .035f, q[1] + fw * .025f + fw * .01f), fill(0x47FFFFFF))
                c.restore()
            }
            "cat" -> {
                val n = u(4); heart(c, n[0], n[1] + fw * .005f, fw * .045f, fill(color))
                c.drawLine(u(2)[0], u(2)[1], u(0)[0], u(0)[1], stroke(color, fw * .012f))
                val s = stroke(color, fw * .008f)
                for ((i, dir) in listOf(205 to -1f, 425 to 1f)) {
                    val q = u(i)
                    for (k in -1..1) {
                        val path = Path(); path.moveTo(q[0], q[1] + k * fw * .03f)
                        path.quadTo(q[0] + dir * fw * .1f, q[1] + k * fw * .04f - fw * .01f, q[0] + dir * fw * .2f, q[1] + k * fw * .06f)
                        c.drawPath(path, s)
                    }
                }
            }
            "panda" -> {
                val f = fill(color).apply { maskFilter = BlurMaskFilter(fw * .012f, BlurMaskFilter.Blur.NORMAL) }
                for (e in listOf(EYE_A, EYE_B)) { val path = Path(); poly(path, e, 1.9f); c.drawPath(path, f) }
            }
            "hero" -> {
                val f = fill(color)
                val mask = Path(); poly(mask, EYE_A, 2.3f); poly(mask, EYE_B, 2.3f); c.drawPath(mask, f)
                val a = u(133); val bb = u(362); val t = u(168)
                val br = Path(); br.moveTo(a[0], a[1] - fw * .04f); br.quadTo(t[0], t[1] - fw * .02f, bb[0], bb[1] - fw * .04f); br.lineTo(bb[0], bb[1] + fw * .03f)
                br.quadTo(t[0], t[1] + fw * .05f, a[0], a[1] + fw * .03f); br.close(); c.drawPath(br, f)
                val holes = Path(); poly(holes, EYE_A, 1.25f); poly(holes, EYE_B, 1.25f)
                c.drawPath(holes, Paint(p).apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
                val rim = stroke(0x59FFFFFF, fw * .006f)
                for (e in listOf(EYE_A, EYE_B)) { val path = Path(); poly(path, e, 2.3f); c.drawPath(path, rim) }
            }
            "stars" -> {
                val f = fill(color).apply { setShadowLayer(fw * .03f, 0f, 0f, color) }
                for ((i, s) in listOf(117 to 1f, 346 to 1f, 118 to .6f, 347 to .6f, 101 to .45f, 330 to .45f)) { val q = u(i); star(c, q[0], q[1], fw * .05f * s, f) }
                val w = fill(Color.WHITE)
                for (k in 0 until 40) {
                    val q = u(intArrayOf(117, 346, 118, 347)[k % 4]); w.alpha = ((.5f + rnd() * .5f) * 255).toInt()
                    c.drawCircle(q[0] + (rnd() - .5f) * fw * .2f, q[1] + (rnd() - .5f) * fw * .14f, fw * .004f * (1 + rnd()), w)
                }
            }
            "hearts" -> for (i in intArrayOf(50, 280)) { val q = u(i); heart(c, q[0], q[1], fw * .06f, fill(color)) }
            "freckles" -> {
                val f = fill(withA(color, 0xB0))
                for (i in intArrayOf(205, 425, 6)) { val q = u(i); repeat(14) { c.drawCircle(q[0] + (rnd() - .5f) * fw * (if (i == 6) .12f else .2f), q[1] + (rnd() - .5f) * fw * .1f, fw * (.004f + rnd() * .005f), f) } }
            }
            "tiger" -> {
                val f = fill(color); val cc = u(6)
                OVAL.forEachIndexed { n, i ->
                    if (n % 3 != 0) return@forEachIndexed
                    val q = u(i); val dx = cc[0] - q[0]; val dy = cc[1] - q[1]; val l = hypot(dx, dy).coerceAtLeast(1f)
                    val len = l * (.22f + rnd() * .12f); val nx = -dy / l; val ny = dx / l; val w = fw * .022f
                    val path = Path(); path.moveTo(q[0] + nx * w, q[1] + ny * w)
                    path.quadTo(q[0] + dx / l * len * .6f + nx * w * .3f, q[1] + dy / l * len * .6f + ny * w * .3f, q[0] + dx / l * len, q[1] + dy / l * len)
                    path.quadTo(q[0] + dx / l * len * .6f - nx * w * .3f, q[1] + dy / l * len * .6f - ny * w * .3f, q[0] - nx * w, q[1] - ny * w)
                    c.drawPath(path, f)
                }
            }
            "neon" -> {
                val s = stroke(color, fw * .008f).apply { setShadowLayer(fw * .02f, 0f, 0f, color) }
                for (loop in listOf(OVAL, LIPS_OUT, EYE_A, EYE_B)) { val path = Path(); poly(path, loop, if (loop === OVAL) .97f else 1.15f); c.drawPath(path, s) }
                for (br in listOf(BROW_A, BROW_B)) { val path = Path(); br.forEachIndexed { n, i -> val q = u(i); if (n == 0) path.moveTo(q[0], q[1]) else path.lineTo(q[0], q[1]) }; c.drawPath(path, s) }
            }
        }
        return b
    }

    // ───────────────────────────── VTuber characters ─────────────────────────────
    val avatars = listOf("cat", "bot", "ghost")
    val avatarNames = mapOf("cat" to "แมวส้มโมจิ", "bot" to "หุ่นยนต์โบลต์", "ghost" to "ผีน้อยพัฟ")
    val avatarColors = mapOf("cat" to (0xFFFFA94D.toInt() to 0xFFFFF3E6.toInt()), "bot" to (0xFF7AA7FF.toInt() to 0xFFBFF6FF.toInt()), "ghost" to (0xFFF6F3FF.toInt() to 0xFFB79CFF.toInt()))

    class Rig {
        var t = 0L; var yaw = 0f; var pitch = 0f; var roll = 0f; var mouth = 0f; var eL = 0f; var eR = 0f; var smile = 0f; var brow = 0f
        var gx = 0f; var gy = 0f; var fx = 0f; var fy = 0f; var nextBlink = 0L; var blinkT = 0L
    }
    private val rigs = HashMap<Long, Rig>()

    private fun clamp(v: Float, a: Float, b: Float) = max(a, min(b, v))
    private fun ease(a: Float, b: Float, k: Float) = a + (b - a) * k

    /** update the character's rig from the face it follows (null = idle); [mirror] = the picture is shown mirrored */
    fun rig(id: Long, f: Tracking.Face?, mirror: Boolean, motion: Float = 1f): Rig {
        val now = SystemClock.uptimeMillis()
        val s = rigs.getOrPut(id) { Rig().apply { t = now; nextBlink = now + 2500 } }
        val dt = min(.1f, (now - s.t) / 1000f); s.t = now
        val k = 1 - exp(-dt / .06f); val kk = 1 - exp(-dt / .03f)
        fun shut(v: Float) = clamp((v - .3f) / .3f, 0f, 1f)
        if (f != null) {
            val pose = Tracking.pose(f)
            val m = if (mirror) -1f else 1f
            fun bs(n: String) = f.shapes[n] ?: 0f
            s.yaw = ease(s.yaw, clamp(pose.yaw / (40f * PI.toFloat() / 180f), -1f, 1f) * motion * m, k)
            s.pitch = ease(s.pitch, clamp(pose.pitch, -1f, 1f) * motion, k)
            s.roll = ease(s.roll, clamp(pose.roll, -.6f, .6f) * motion * m, k)
            s.mouth = ease(s.mouth, clamp(bs("jawOpen") / .55f, 0f, 1f), kk)
            val bl = bs("eyeBlinkLeft"); val br = bs("eyeBlinkRight")
            // MediaPipe "left" = the person's left; on a mirrored screen that is the screen's left
            s.eL = ease(s.eL, shut(if (mirror) bl else br), kk); s.eR = ease(s.eR, shut(if (mirror) br else bl), kk)
            s.smile = ease(s.smile, clamp(((bs("mouthSmileLeft") + bs("mouthSmileRight")) / 2 - .15f) / .55f, 0f, 1f), k)
            s.brow = ease(s.brow, clamp((bs("browInnerUp") - .2f) / .5f, 0f, 1f), k)
            val gxr = (bs("eyeLookOutLeft") - bs("eyeLookInLeft") + bs("eyeLookInRight") - bs("eyeLookOutRight")) / 2
            s.gx = ease(s.gx, clamp(gxr * m * 1.6f, -1f, 1f), k)
            s.gy = ease(s.gy, clamp((bs("eyeLookUpLeft") - bs("eyeLookDownLeft")) * 1.6f, -1f, 1f), k)
            s.fx = ease(s.fx, clamp((pose.x - .5f) * 2f * m, -1f, 1f), k); s.fy = ease(s.fy, clamp((pose.y - .5f) * 2f, -1f, 1f), k)
        } else {
            val r = 1 - exp(-dt / .4f)
            s.yaw = ease(s.yaw, 0f, r); s.pitch = ease(s.pitch, 0f, r); s.roll = ease(s.roll, 0f, r); s.mouth = ease(s.mouth, 0f, r)
            s.smile = ease(s.smile, 0f, r); s.brow = ease(s.brow, 0f, r); s.gx = ease(s.gx, 0f, r); s.gy = ease(s.gy, 0f, r); s.fx = ease(s.fx, 0f, r); s.fy = ease(s.fy, 0f, r)
            if (now > s.nextBlink) { s.blinkT = now; s.nextBlink = now + 2200 + (Math.random() * 3200).toLong() }
            val b = if (now - s.blinkT < 160) 1f else 0f; s.eL = ease(s.eL, b, kk); s.eR = ease(s.eR, b, kk)
            s.yaw += sin(now / 1900.0).toFloat() * .002f; s.roll += sin(now / 2300.0).toFloat() * .0015f
        }
        return s
    }

    fun dropRig(id: Long) { rigs.remove(id) }

    /** draw the character into [b] (square); canvas units: 1000 = the box, centre at 0 */
    fun drawAvatar(b: Bitmap, kind: String, c1: Int, c2: Int, s: Rig, follow: Float = .4f) {
        b.eraseColor(Color.TRANSPARENT)
        val g = Canvas(b)
        val k = b.width / 1000f
        g.translate(b.width / 2f, b.height / 2f); g.scale(k, k)
        g.translate(s.fx * 260 * follow, s.fy * 120 * follow)
        val t = SystemClock.uptimeMillis() / 1000f
        when (kind) { "bot" -> bot(g, c1, c2, s, t); "ghost" -> ghost(g, c1, c2, s, t); else -> cat(g, c1, c2, s, t) }
    }

    private fun shade(col: Int, k: Float): Int {
        fun ch(v: Int) = (v * k).toInt().coerceIn(0, 255)
        return Color.rgb(ch(Color.red(col)), ch(Color.green(col)), ch(Color.blue(col)))
    }
    private val fp = Paint(Paint.ANTI_ALIAS_FLAG)
    private fun f(col: Int) = fp.apply { reset(); isAntiAlias = true; style = Paint.Style.FILL; color = col }
    private fun st(col: Int, w: Float) = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = col; strokeWidth = w; strokeCap = Paint.Cap.ROUND }
    private fun ell(g: Canvas, x: Float, y: Float, rx: Float, ry: Float, p: Paint) { g.drawOval(RectF(x - max(.1f, rx), y - max(.1f, ry), x + max(.1f, rx), y + max(.1f, ry)), p) }
    private fun arc(g: Canvas, x: Float, y: Float, r: Float, a0: Float, a1: Float, p: Paint) {
        g.drawArc(RectF(x - r, y - r, x + r, y + r), Math.toDegrees(a0.toDouble()).toFloat(), Math.toDegrees((a1 - a0).toDouble()).toFloat(), false, p)
    }
    private val PIf = PI.toFloat()

    private fun eyes(g: Canvas, s: Rig, x: Float, y: Float, rx: Float, ry: Float, col: Int) {
        for ((side, e) in listOf(-1f to s.eL, 1f to s.eR)) {
            val ex = x * side; val open = 1 - e
            if (s.smile > .6f && e < .5f) { arc(g, ex, y + ry * .3f, rx * .9f, PIf * 1.15f, PIf * 1.85f, st(col, rx * .32f)); continue }
            if (open < .18f) { arc(g, ex, y - ry * .2f, rx * .95f, PIf * .15f, PIf * .85f, st(col, rx * .3f)); continue }
            ell(g, ex + s.gx * rx * .35f, y - s.gy * ry * .2f, rx, ry * open, f(col))
            ell(g, ex + s.gx * rx * .35f + rx * .32f, y - s.gy * ry * .2f - ry * .32f * open, rx * .3f, rx * .3f * min(1f, open * 1.4f), f(Color.WHITE))
        }
    }

    private fun cat(g: Canvas, c: Int, c2: Int, s: Rig, t: Float) {
        val breathe = sin(t * 2.1f) * 6
        ell(g, 0f, 488f, 230f, 16f, f(0x1F000000))
        ell(g, 0f, 330 - breathe * .4f, 265f, 205 + breathe, f(c))
        ell(g, 0f, 370f, 150f, 130 + breathe * .6f, f(c2))
        for (x in floatArrayOf(-115f, 115f)) ell(g, x, 478f, 72f, 34f, f(shade(c, .92f)))
        g.save(); g.translate(s.yaw * 45, -70 - s.pitch * 32 + breathe * .5f); g.rotate(Math.toDegrees(s.roll * .85).toFloat())
        g.save(); g.translate(-s.yaw * 18, 0f)
        for (sd in floatArrayOf(-1f, 1f)) {
            g.drawPath(Path().apply { moveTo(sd * 235, -95f); lineTo(sd * 205, -330f); lineTo(sd * 60, -205f); close() }, f(c))
            g.drawPath(Path().apply { moveTo(sd * 200, -130f); lineTo(sd * 190, -280f); lineTo(sd * 100, -205f); close() }, f(0xFFFFB3C4.toInt()))
        }
        g.restore()
        ell(g, 0f, 0f, 255f, 215f, f(c))
        for (x in floatArrayOf(-40f, 0f, 40f)) g.drawPath(Path().apply { moveTo(x - 14, -212f); lineTo(x, -150f); lineTo(x + 14, -212f); close() }, f(shade(c, .88f)))
        g.save(); g.translate(s.yaw * 60, -s.pitch * 36)
        for (x in floatArrayOf(-150f, 150f)) ell(g, x, 70f, 48f, 26f, f(Color.argb(((.18f + s.smile * .5f) * 255).toInt(), 255, 120, 150)))
        eyes(g, s, 95f, -18 - s.brow * 8, 34f, 46f, 0xFF2B2233.toInt())
        if (s.brow > .15f) { val p = st(shade(c, .6f), 12f); for (sd in floatArrayOf(-1f, 1f)) g.drawLine(sd * 70, -95 - s.brow * 30, sd * 125, -100 - s.brow * 24, p) }
        g.drawPath(Path().apply { moveTo(-18f, 38f); lineTo(18f, 38f); lineTo(0f, 56f); close() }, f(0xFFFF8FAB.toInt()))
        if (s.mouth > .12f) {
            ell(g, 0f, 92 + s.mouth * 18, 38 + s.smile * 12, 10 + s.mouth * 52, f(0xFF5A2232.toInt()))
            ell(g, 0f, 105 + s.mouth * 42, 24f, 8 + s.mouth * 16, f(0xFFFF7F9F.toInt()))
        } else {
            val p = st(0xFF4A2A33.toInt(), 9f)
            arc(g, -22f, 60f, 22f, .1f, PIf - .3f, p); arc(g, 22f, 60f, 22f, .3f, PIf - .1f, p)
        }
        val w = st(0x8C462828.toInt(), 6f)
        for (sd in floatArrayOf(-1f, 1f)) for (dy in floatArrayOf(-18f, 6f, 30f)) g.drawLine(sd * 165, 55 + dy * .5f, sd * 280, 40 + dy, w)
        g.restore(); g.restore()
    }

    private fun bot(g: Canvas, c: Int, glow: Int, s: Rig, t: Float) {
        val breathe = sin(t * 2f) * 4
        ell(g, 0f, 490f, 220f, 14f, f(0x24000000))
        g.drawRoundRect(RectF(-220f, 200 - breathe, 220f, 500f), 70f, 70f, f(shade(c, .7f)))
        g.drawRect(-50f, 150f, 50f, 220f, f(shade(c, .55f)))
        val gl = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glow; alpha = ((.5f + s.mouth * .5f) * 255).toInt(); setShadowLayer(30 + s.mouth * 50, 0f, 0f, glow) }
        ell(g, 0f, 340f, 46f, 46f, gl)
        g.save(); g.translate(s.yaw * 40, -60 - s.pitch * 30); g.rotate(Math.toDegrees(s.roll * .8).toFloat())
        g.save(); g.translate(-s.yaw * 22, 0f); for (sd in floatArrayOf(-1f, 1f)) ell(g, sd * 252, -10f, 42f, 62f, f(shade(c, .8f))); g.restore()
        g.drawLine(0f, -200f, s.yaw * -20, -300f, st(shade(c, .6f), 14f))
        ell(g, s.yaw * -20, -312f, 26f, 26f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glow; setShadowLayer(25 + s.mouth * 40, 0f, 0f, glow) })
        g.drawRoundRect(RectF(-240f, -200f, 240f, 180f), 90f, 90f, Paint(Paint.ANTI_ALIAS_FLAG).apply { shader = LinearGradient(0f, -200f, 0f, 180f, shade(c, 1.12f), shade(c, .85f), Shader.TileMode.CLAMP) })
        g.drawRoundRect(RectF(-190f, -135f, 190f, 115f), 60f, 60f, f(0xFF141B36.toInt()))
        g.save(); g.translate(s.yaw * 50, -s.pitch * 26)
        val gp = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = glow; setShadowLayer(22f, 0f, 0f, glow) }
        for ((sd, e) in listOf(-1f to s.eL, 1f to s.eR)) {
            val x = sd * 78 + s.gx * 14; val y = -30 - s.gy * 8 - s.brow * 12
            if (s.smile > .6f && e < .5f) { arc(g, x, y + 20, 32f, PIf * 1.1f, PIf * 1.9f, st(glow, 16f)); continue }
            val h = max(10f, 86 * (1 - e)); g.drawRoundRect(RectF(x - 34, y - h / 2, x + 34, y + h / 2), 22f, 22f, gp)
        }
        for (i in 0 until 7) {
            val kq = 1 - abs(i - 3) / 4f; val h = 10 + s.mouth * 64 * kq + s.smile * 6
            gp.alpha = ((.35f + .65f * min(1f, s.mouth * 2 + .2f)) * 255).toInt()
            g.drawRoundRect(RectF(-91f + i * 26 - 9, 70 - h / 2, -91f + i * 26 + 9, 70 + h / 2), 8f, 8f, gp)
        }
        g.restore(); g.restore()
    }

    private fun ghost(g: Canvas, c: Int, c2: Int, s: Rig, t: Float) {
        val bob = sin(t * 1.6f) * 18
        ell(g, 0f, 480f, 170 - bob, 18f, f(0x1F000000))
        g.save(); g.translate(s.yaw * 40, bob - s.pitch * 26); g.rotate(Math.toDegrees(s.roll * .7).toFloat())
        val wv = sin(t * 3f) * 10
        val body = Path(); body.moveTo(-260f, 0f); body.arcTo(RectF(-260f, -260f, 260f, 260f), 180f, 180f); body.lineTo(260f, 330f)
        for (i in 0 until 4) { val x0 = 260f - i * 130; body.quadTo(x0 - 32, 390 + if (i % 2 == 1) wv else -wv, x0 - 65, 330f); body.quadTo(x0 - 98, 270f, x0 - 130, 330f) }
        body.close()
        g.drawPath(body, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c; setShadowLayer(40f, 0f, 0f, c2) })
        for (sd in floatArrayOf(-1f, 1f)) { g.save(); g.translate(sd * 250, 150f); g.rotate(Math.toDegrees(sd * (.4 + sin(t * 2.4 + sd) * .25)).toFloat()); ell(g, 0f, 0f, 70f, 38f, f(c)); g.restore() }
        g.save(); g.translate(s.yaw * 70, -s.pitch * 40)
        val ch = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = c2; alpha = ((.35f + s.smile * .45f) * 255).toInt() }
        for (x in floatArrayOf(-140f, 140f)) ell(g, x, 80f, 44f, 24f, ch)
        eyes(g, s, 85f, -10 - s.brow * 14, 42f, 58f, 0xFF2D2340.toInt())
        if (s.mouth > .1f) ell(g, 0f, 95f, 30 + s.smile * 10, 10 + s.mouth * 46, f(0xFF2D2340.toInt()))
        else arc(g, 0f, 70f, 26 + s.smile * 10, .25f * PIf, .75f * PIf, st(0xFF2D2340.toInt(), 9f))
        g.restore(); g.restore()
    }
}
