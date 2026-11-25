package com.prism.cam.studio

import android.os.SystemClock
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.hypot

/**
 * What the AI sees, smoothed (One-Euro filter like the desktop app) and in the camera's upright space (0..1).
 * Written by the AI thread, read by the GPU thread / photo pass / UI. Plus the maths that turns a face or hand point
 * into a layer's position, size and angle on the canvas.
 */
object Tracking {
    class Face(val pts: FloatArray, val shapes: Map<String, Float>, val t: Long)
    class Hand(val pts: FloatArray, val right: Boolean, val gesture: String, val score: Float, val t: Long)

    @Volatile var faces: List<Face> = emptyList()
    @Volatile var hands: List<Hand> = emptyList()
    /** face tuning (Tracking panel) */
    @Volatile var smoothFace = .5f
    @Volatile var smoothHand = .5f
    /** predict ahead to hide the AI's delay (ms) */
    @Volatile var leadMs = 40f
    @Volatile var swapEyes = false
    /** expression sensitivity 0..1 (higher = easier to trigger) */
    @Volatile var sensitivity = .5f
    /** camera picture height / width (set by the GPU each frame): camera space is 0..1 on both axes, so a distance
     *  needs y × aspect to be in the same units as x */
    @Volatile var aspect = .75f
    /** calibration: resting blendshape values ("fit to my face") */
    @Volatile var rest: Map<String, Float> = emptyMap()

    // ───────────────────────────── smoothing ─────────────────────────────

    /** One-Euro filter on a whole point array (cut-off rises with speed: still = smooth, fast = sharp). */
    class Euro(private val n: Int) {
        private var x: FloatArray? = null
        private var dx = FloatArray(n)
        private var lastT = 0L
        fun filter(v: FloatArray, t: Long, smooth: Float): FloatArray {
            val prev = x
            if (prev == null || prev.size != v.size) { x = v.copyOf(); dx = FloatArray(v.size); lastT = t; return v.copyOf() }
            val dt = ((t - lastT).coerceAtLeast(1)) / 1000f; lastT = t
            val minCut = 3.5f - 3.2f * smooth   // 0.3 … 3.5 Hz
            val beta = 0.8f
            val out = FloatArray(v.size)
            for (i in v.indices) {
                val d = (v[i] - prev[i]) / dt
                val ad = alpha(1f, dt); dx[i] = dx[i] + ad * (d - dx[i])
                val cut = minCut + beta * abs(dx[i])
                val a = alpha(cut, dt)
                out[i] = prev[i] + a * (v[i] - prev[i])
            }
            x = out
            return out
        }
        /** where the points will be [ms] from now (velocity × time) */
        fun ahead(ms: Float): FloatArray? = x?.let { p -> FloatArray(p.size) { i -> p[i] + dx[i] * ms / 1000f } }
        private fun alpha(cut: Float, dt: Float) = (1f - exp(-2f * PI.toFloat() * cut * dt))
    }

    private val faceEuro = Array(4) { Euro(478 * 2) }
    private val handEuro = Array(2) { Euro(21 * 2) }

    /** test hook: a synthetic front-on face (the face-mesh layout as the camera sees it), tilted [tilt] degrees */
    fun fakeFace(tilt: Float, ms: Long) {
        if (!FaceArt.ready) return
        val uv = FaceArt.UV
        val pts = FloatArray(478 * 2)
        val r = Math.toRadians(tilt.toDouble()); val c = cos(r).toFloat(); val s = kotlin.math.sin(r).toFloat()
        for (i in 0 until 478) {
            val k = if (i < 468) i else if (i < 473) 159 else 386
            val x = (uv[k * 2] - .5f) * .3f; val y = (uv[k * 2 + 1] - .5f) * .3f
            pts[i * 2] = .5f + x * c - y * s; pts[i * 2 + 1] = .45f + (x * s + y * c) / aspect
        }
        val t = SystemClock.uptimeMillis()
        fakeFaceUntil = t + ms
        faces = listOf(Face(pts, emptyMap(), t))
    }
    @Volatile var fakeFaceUntil = 0L

    fun feedFaces(raw: List<Pair<FloatArray, Map<String, Float>>>) {
        val t = SystemClock.uptimeMillis()
        if (t < fakeFaceUntil) return
        faces = raw.take(4).mapIndexed { i, (p, s) ->
            faceEuro[i].filter(p, t, smoothFace)
            Face(faceEuro[i].ahead(leadMs) ?: p, s, t)
        }
    }

    /** test hook: synthetic hands shown until this time (the AI's results are ignored meanwhile) */
    @Volatile var fakeUntil = 0L

    /**
     * Test hook: two open hands (or one) in camera space, [spread] = gap between the palms (0..1), [tilt] degrees,
     * [gesture] for both. Held for [ms].
     */
    fun fakeHands(n: Int, spread: Float, tilt: Float, gesture: String, ms: Long) {
        val t = SystemClock.uptimeMillis()
        fun hand(cx: Float, cy: Float, mirror: Boolean): FloatArray {
            // a simple open hand, fingers up, about .14 wide in camera space
            val base = floatArrayOf(0f, .12f, -.035f, .09f, -.06f, .06f, -.075f, .03f, -.085f, 0f,
                -.03f, .03f, -.035f, -.01f, -.037f, -.04f, -.038f, -.065f,
                0f, .025f, 0f, -.02f, 0f, -.055f, 0f, -.085f,
                .028f, .03f, .03f, -.012f, .031f, -.045f, .032f, -.07f,
                .052f, .04f, .056f, .01f, .058f, -.015f, .06f, -.035f)
            val out = FloatArray(42)
            for (i in 0 until 21) { out[i * 2] = cx + base[i * 2] * (if (mirror) -1f else 1f); out[i * 2 + 1] = cy + base[i * 2 + 1] / aspect }
            return out
        }
        val r = Math.toRadians(tilt.toDouble()); val dx = (kotlin.math.cos(r) * spread / 2).toFloat(); val dy = (kotlin.math.sin(r) * spread / 2).toFloat() / aspect
        val list = if (n == 1) listOf(Hand(hand(.5f, .5f, false), true, gesture, .9f, t))
        else listOf(Hand(hand(.5f - dx, .5f - dy, true), false, gesture, .9f, t), Hand(hand(.5f + dx, .5f + dy, false), true, gesture, .9f, t))
        fakeUntil = t + ms
        hands = list
        pair = if (gesture.startsWith("pair:")) gesture else ""
    }

    fun feedHands(raw: List<Hand>) {
        val t = SystemClock.uptimeMillis()
        if (t < fakeUntil) return
        // keep each hand in the same smoothing slot: the AI may list the two hands in either order from frame to frame,
        // which blended one hand into the other (a sticker between the hands jumped)
        val two = raw.take(2)
        val ordered = if (two.size == 2 && slotSwapped(two)) listOf(two[1], two[0]) else two
        val slots = if (ordered.size == 1) listOf(nearestSlot(ordered[0])) else ordered.indices.toList()
        hands = ordered.mapIndexed { k, h ->
            val i = slots[k]
            handEuro[i].filter(h.pts, t, smoothHand)
            Hand(handEuro[i].ahead(leadMs) ?: h.pts, h.right, h.gesture, h.score, t)
        }
        lastSlotPalm = Array(2) { i -> slots.indexOf(i).takeIf { it >= 0 }?.let { palmOf(hands[it].pts) } ?: lastSlotPalm[i] }
        updatePair(hands)
    }

    private var lastSlotPalm = arrayOf<FloatArray?>(null, null)
    private fun palmOf(p: FloatArray) = floatArrayOf((p[0] + p[18]) / 2, (p[1] + p[19]) / 2)
    private fun d2(a: FloatArray?, b: FloatArray) = if (a == null) 9f else (a[0] - b[0]).let { it * it } + ((a[1] - b[1]) * aspect).let { it * it }
    private fun slotSwapped(two: List<Hand>): Boolean {
        val a = palmOf(two[0].pts); val b = palmOf(two[1].pts)
        return d2(lastSlotPalm[0], b) + d2(lastSlotPalm[1], a) < d2(lastSlotPalm[0], a) + d2(lastSlotPalm[1], b)
    }
    private fun nearestSlot(h: Hand): Int { val p = palmOf(h.pts); return if (d2(lastSlotPalm[1], p) < d2(lastSlotPalm[0], p)) 1 else 0 }

    // ───────────────────────────── two-hand poses (desktop hands.js pairRule) ─────────────────────────────

    /** two-hand pose held right now: "pair:heart" 🫶 · "pair:pray" 🙏 · "" */
    @Volatile var pair = ""
    private var pairRaw = ""; private var pairN = 0
    val pairPoses = listOf("pair:heart", "pair:pray")

    private fun updatePair(list: List<Hand>) {
        val g = if (list.size >= 2) pairRule(list[0], list[1]) else ""
        if (g == pairRaw) pairN++ else { pairRaw = g; pairN = 1 }
        if (g != pair && (if (g.isNotEmpty()) pairN >= 3 else pairN >= 4)) pair = g
    }

    /** both hands' points around the point between the palms, in hand sizes; the hand further left comes first */
    private fun pairRule(h0: Hand, h1: Hand): String {
        val (a, b) = if (palmOf(h0.pts)[0] <= palmOf(h1.pts)[0]) h0 to h1 else h1 to h0
        val pa = palmOf(a.pts); val pb = palmOf(b.pts)
        val cx = (pa[0] + pb[0]) / 2; val cy = (pa[1] + pb[1]) / 2
        fun size(h: Hand) = hypot(h.pts[0] - h.pts[18], (h.pts[1] - h.pts[19]) * aspect)
        val sc = ((size(a) + size(b)) / 2).coerceAtLeast(1e-3f)
        fun pt(h: Hand, i: Int) = floatArrayOf((h.pts[i * 2] - cx) / sc, (h.pts[i * 2 + 1] - cy) * aspect / sc)
        fun dist(p: FloatArray, q: FloatArray) = hypot(p[0] - q[0], p[1] - q[1])
        val i0 = pt(a, 8); val i1 = pt(b, 8); val t0 = pt(a, 4); val t1 = pt(b, 4); val w0 = pt(a, 0); val w1 = pt(b, 0)
        // 🫶 index tips meet at the top, thumb tips at the bottom, a gap (the heart) between the hands
        if (dist(i0, i1) < .55f && dist(t0, t1) < .55f && (i0[1] + i1[1]) / 2 < (t0[1] + t1[1]) / 2 - .35f && dist(w0, w1) > .6f) return "pair:heart"
        // 🙏 palms together, fingers up
        val m0 = pt(a, 12); val m1 = pt(b, 12); val c0 = pt(a, 9); val c1 = pt(b, 9)
        if (dist(c0, c1) < .55f && dist(m0, m1) < .45f && (m0[1] + m1[1]) / 2 < (w0[1] + w1[1]) / 2 - .6f) return "pair:pray"
        return ""
    }

    // ───────────────────────────── face measures ─────────────────────────────

    class FacePose(val x: Float, val y: Float, val width: Float, val roll: Float, val yaw: Float, val pitch: Float)

    /** a face point in camera space (0..1) */
    fun facePoint(f: Face, point: String): FloatArray {
        val p = f.pts
        fun at(i: Int) = floatArrayOf(p[i * 2], p[i * 2 + 1])
        fun mid(a: Int, b: Int) = floatArrayOf((p[a * 2] + p[b * 2]) / 2, (p[a * 2 + 1] + p[b * 2 + 1]) / 2)
        val pose = pose(f)
        val up = floatArrayOf(kotlin.math.sin(pose.roll) * pose.width, -cos(pose.roll) * pose.width / aspect)
        val (lEye, rEye) = if (swapEyes) 263 to 33 else 33 to 263
        return when (point) {
            "eyes" -> mid(lEye, rEye)
            "eyeL" -> at(lEye)
            "eyeR" -> at(rEye)
            "nose" -> at(1)
            "mouth" -> mid(13, 14)
            "forehead" -> at(10).let { floatArrayOf(it[0] + up[0] * .12f, it[1] + up[1] * .12f) }
            "top" -> at(10).let { floatArrayOf(it[0] + up[0] * .55f, it[1] + up[1] * .55f) }
            "chin" -> at(152)
            "cheekL" -> at(50)
            "cheekR" -> at(280)
            "earL" -> at(234)
            "earR" -> at(454)
            else -> mid(10, 152)
        }
    }

    fun pose(f: Face): FacePose {
        val p = f.pts
        val lx = p[234 * 2]; val ly = p[234 * 2 + 1]; val rx = p[454 * 2]; val ry = p[454 * 2 + 1]
        val a = aspect
        val w = hypot(rx - lx, (ry - ly) * a)
        val roll = atan2((p[263 * 2 + 1] - p[33 * 2 + 1]) * a, p[263 * 2] - p[33 * 2])
        // yaw from where the nose sits between the ears, pitch from nose vs eye / chin line
        val mx = (lx + rx) / 2; val my = (ly + ry) / 2
        val nx = p[1 * 2] - mx; val ny = (p[1 * 2 + 1] - my) * a
        val c = cos(-roll); val s = kotlin.math.sin(-roll)
        val rnx = nx * c - ny * s
        val yaw = asin((rnx / (w / 2)).coerceIn(-1f, 1f) * .9f)
        val eyeY = (p[33 * 2 + 1] + p[263 * 2 + 1]) / 2; val chinY = p[152 * 2 + 1]
        val pitch = (((p[1 * 2 + 1] - eyeY) / (chinY - eyeY).coerceAtLeast(1e-4f)) - .38f) * -2.2f
        return FacePose((lx + rx) / 2, (ly + ry) / 2, w, roll, yaw, pitch)
    }

    /** expression value 0..1 after "fit to my face" (rest subtracted) */
    fun expr(f: Face, name: String): Float {
        fun bs(k: String): Float { val v = f.shapes[k] ?: 0f; val r = (rest[k] ?: 0f).coerceAtMost(.6f); return ((v - r) / (1f - r)).coerceIn(0f, 1f) }
        return when (name) {
            "smile" -> (bs("mouthSmileLeft") + bs("mouthSmileRight")) / 2
            "mouth" -> bs("jawOpen")
            "brow" -> bs("browInnerUp")
            "kiss" -> bs("mouthPucker")
            "puff" -> bs("cheekPuff")
            "blink" -> (bs("eyeBlinkLeft") + bs("eyeBlinkRight")) / 2
            "winkL" -> (bs("eyeBlinkLeft") - bs("eyeBlinkRight")).coerceAtLeast(0f)
            "winkR" -> (bs("eyeBlinkRight") - bs("eyeBlinkLeft")).coerceAtLeast(0f)
            else -> 0f
        }
    }

    // ───────────────────────────── hand measures ─────────────────────────────

    fun handPoint(h: Hand, point: String): FloatArray {
        val p = h.pts
        fun at(i: Int) = floatArrayOf(p[i * 2], p[i * 2 + 1])
        return when (point) {
            "thumb" -> at(4); "index" -> at(8); "middle" -> at(12); "ring" -> at(16); "pinky" -> at(20); "wrist" -> at(0)
            else -> floatArrayOf((p[0] + p[10] + p[18] + p[26] + p[34]) / 5, (p[1] + p[11] + p[19] + p[27] + p[35]) / 5)
        }
    }

    fun handWidth(h: Hand) = hypot(h.pts[5 * 2] - h.pts[17 * 2], (h.pts[5 * 2 + 1] - h.pts[17 * 2 + 1]) * aspect) * 1.6f
    fun handRoll(h: Hand) = atan2((h.pts[9 * 2 + 1] - h.pts[1]) * aspect, h.pts[9 * 2] - h.pts[0]) + (PI / 2).toFloat()

    // ───────────────────────────── attachment → placement ─────────────────────────────

    class Place(val cx: Float, val cy: Float, val w: Float, val rot: Float, val sx: Float, val alpha: Float)

    private val lastSeen = HashMap<Long, Pair<Place, Long>>()

    /**
     * Where an attached layer goes this frame (canvas px), or null if it is not attached.
     * [baseW] = the layer's own width in px (used when "follow size" is off).
     */
    fun place(l: RLayer, cam: RLayer?, front: Boolean, cw: Float, ch: Float, baseW: Float): Place? {
        if (l.attachKind.isEmpty()) return null
        val now = SystemClock.uptimeMillis()
        val found: Place? = when (l.attachKind) {
            "face" -> {
                val list = faces
                val f = if (l.attachWho == "all") list.firstOrNull() else list.getOrNull(l.attachWho.toIntOrNull() ?: 0)
                if (f == null || (l.only.isNotEmpty() && expr(f, l.only) < .5f)) null else {
                    val pose = pose(f)
                    val pt = facePoint(f, l.attachPoint)
                    placeAt(pt, pose.width, pose.roll, if (l.followTurn) cos(pose.yaw) else 1f, l, cam, front, cw, ch, baseW)
                }
            }
            "hand" -> {
                val list = hands
                val h = when (l.attachWho) { "left" -> list.firstOrNull { !it.right }; "right" -> list.firstOrNull { it.right }; else -> list.firstOrNull() }
                if (h == null || (l.only.isNotEmpty() && h.gesture != l.only)) null else {
                    placeAt(handPoint(h, l.attachPoint), handWidth(h), handRoll(h), 1f, l, cam, front, cw, ch, baseW)
                }
            }
            "hands2" -> {
                val list = hands
                val ok = when {
                    l.only.isEmpty() -> true
                    l.only.startsWith("pair:") -> pair == l.only
                    else -> list.size >= 2 && list.all { it.gesture == l.only }
                }
                if (list.size < 2 || !ok) null
                else placeBetween(handPoint(list[0], l.attachPoint), handPoint(list[1], l.attachPoint), l, cam, front, cw, ch, baseW)
            }
            else -> null
        }
        if (found != null) { lastSeen[l.id] = found to now; return found }
        val (last, t) = lastSeen[l.id] ?: return Place(0f, 0f, 0f, 0f, 1f, 0f)
        return when (l.lost) {
            "stay" -> last
            "fade" -> Place(last.cx, last.cy, last.w, last.rot, last.sx, (1f - (now - t) / 600f).coerceIn(0f, 1f))
            else -> Place(last.cx, last.cy, last.w, last.rot, last.sx, 0f)
        }
    }

    /** between two hands: middle of the two points, turned along the line between them, as wide as the gap × size */
    private fun placeBetween(a: FloatArray, b: FloatArray, l: RLayer, cam: RLayer?, front: Boolean, cw: Float, ch: Float, baseW: Float): Place {
        var p = Geometry.camToCanvas(cam, front, cw, ch, a[0], a[1]); var q = Geometry.camToCanvas(cam, front, cw, ch, b[0], b[1])
        if (q[0] < p[0]) { val t = p; p = q; q = t }   // always left → right on screen, so it never turns upside down
        val dx = q[0] - p[0]; val dy = q[1] - p[1]
        val dist = hypot(dx, dy).coerceAtLeast(1f)
        val angle = Math.toDegrees(atan2(dy.toDouble(), dx.toDouble())).toFloat()
        val ax = dx / dist; val ay = dy / dist; val ux = ay; val uy = -ax
        val cx = (p[0] + q[0]) / 2 + (ax * l.ox - ux * l.oy) * dist
        val cy = (p[1] + q[1]) / 2 + (ay * l.ox - uy * l.oy) * dist
        val w = if (l.followScale) dist * l.attachSize else baseW
        return Place(cx, cy, w, (if (l.followRot) angle else 0f) + l.rot, 1f, 1f)
    }

    private fun placeAt(pt: FloatArray, widthCam: Float, roll: Float, sx: Float, l: RLayer, cam: RLayer?, front: Boolean, cw: Float, ch: Float, baseW: Float): Place {
        val p = Geometry.camToCanvas(cam, front, cw, ch, pt[0], pt[1])
        // Size + angle from the face's / hand's UP direction, not the eye-to-eye line: the front camera mirrors the
        // picture, which turned the across line around and put every attached sticker upside down (tablet, 2026-11-25).
        // "Up" stays up through a mirror, so both cameras agree.
        val q = Geometry.camToCanvas(cam, front, cw, ch, pt[0] + widthCam * kotlin.math.sin(roll), pt[1] - widthCam * cos(roll) / aspect)
        val wPx = hypot(q[0] - p[0], q[1] - p[1])
        val angle = Math.toDegrees(atan2((q[1] - p[1]).toDouble(), (q[0] - p[0]).toDouble())).toFloat() + 90f
        val ux = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat(); val uy = -cos(Math.toRadians(angle.toDouble())).toFloat()
        val ax = cos(Math.toRadians(angle.toDouble())).toFloat(); val ay = kotlin.math.sin(Math.toRadians(angle.toDouble())).toFloat()
        val cx = p[0] + (ax * l.ox - ux * l.oy) * wPx
        val cy = p[1] + (ay * l.ox - uy * l.oy) * wPx
        val w = if (l.followScale) wPx * l.attachSize else baseW
        return Place(cx, cy, w, (if (l.followRot) angle else 0f) + l.rot, if (l.attachKind == "face") sx.coerceIn(.3f, 1f) else 1f, 1f)
    }
}
