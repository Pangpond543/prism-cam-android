package com.prism.cam.studio

import android.content.Context
import android.content.SharedPreferences
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prism.cam.PrismLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * Face & hand control (desktop face.js / handctl.js / handml.js, made for a phone):
 * - commands: hold a face expression or a hand pose (built-in or your own) → photo, video, next scene, next look…;
 * - hand-at-face zones: hand at the mouth / forehead / cheek / ear counts as a trigger too;
 * - your own hand poses: record a few seconds of a pose, recognised by nearest neighbours (kNN);
 * - air drawing: point with the index finger to draw on the drawing layer, open hand = pause, fist held = clear;
 * - "fit to my face": 3 s of your resting face so expressions trigger from YOUR neutral;
 * - tracking tuning saved between runs.
 * Checks run on the AI thread after every tracked frame; actions are posted to the main thread.
 */
object Gestures {
    class Cmd(val trigger: String, val action: String) {
        fun toJson() = JSONObject().put("t", trigger).put("a", action)
    }

    val commands = mutableStateListOf<Cmd>()
    /** custom poses: name → samples (each 42 normalised numbers) */
    private val poses = LinkedHashMap<String, MutableList<FloatArray>>()
    val poseNames = mutableStateListOf<String>()
    /** last fired trigger (shown in the Tracking panel) */
    var lastFired by mutableStateOf("")
    /** live: what is seen right now (Tracking panel) */
    var seenNow by mutableStateOf("")
    /** recording a pose / calibrating: seconds left (0 = idle) */
    var recording by mutableStateOf<String?>(null)
    var calibrating by mutableStateOf(false)

    /** main-thread action runner (set by MainActivity) */
    var run: (String) -> Unit = {}
    private val main = Handler(Looper.getMainLooper())
    private lateinit var prefs: SharedPreferences
    private lateinit var posesFile: File

    val faceTriggers = listOf("smile", "mouth", "brow", "kiss", "puff", "blink", "winkL", "winkR")
    val zones = listOf("zone:mouth", "zone:forehead", "zone:cheek", "zone:ear")
    val actions = listOf("photo", "video", "nextScene", "prevScene", "nextLook", "clearDraw", "toggleTop")

    // ───────────────────────────── settings ─────────────────────────────

    fun init(ctx: Context) {
        prefs = ctx.getSharedPreferences("tracking", Context.MODE_PRIVATE)
        posesFile = File(ctx.filesDir, "studio/poses.json")
        try {
            JSONArray(prefs.getString("cmds", "[]")).let { a -> for (i in 0 until a.length()) a.getJSONObject(i).let { commands += Cmd(it.optString("t"), it.optString("a")) } }
        } catch (_: Throwable) { }
        Tracking.smoothFace = prefs.getFloat("smoothFace", .5f); Tracking.smoothHand = prefs.getFloat("smoothHand", .5f)
        Tracking.leadMs = prefs.getFloat("lead", 40f); Tracking.sensitivity = prefs.getFloat("sens", .5f); Tracking.swapEyes = prefs.getBoolean("swapEyes", false)
        try { JSONObject(prefs.getString("rest", "{}") ?: "{}").let { j -> Tracking.rest = j.keys().asSequence().associateWith { j.optDouble(it).toFloat() } } } catch (_: Throwable) { }
        try {
            if (posesFile.exists()) JSONObject(posesFile.readText()).let { j ->
                for (name in j.keys()) {
                    val a = j.getJSONArray(name)
                    poses[name] = MutableList(a.length()) { i -> a.getJSONArray(i).let { s -> FloatArray(s.length()) { k -> s.optDouble(k).toFloat() } } }
                }
            }
        } catch (t: Throwable) { PrismLog.i("ERR poses ${t.message}") }
        poseNames.addAll(poses.keys)
    }

    fun saveTuning() {
        if (!::prefs.isInitialized) return
        prefs.edit().putFloat("smoothFace", Tracking.smoothFace).putFloat("smoothHand", Tracking.smoothHand).putFloat("lead", Tracking.leadMs)
            .putFloat("sens", Tracking.sensitivity).putBoolean("swapEyes", Tracking.swapEyes)
            .putString("rest", JSONObject(Tracking.rest.mapValues { it.value.toDouble() }).toString()).apply()
    }

    fun saveCommands() { prefs.edit().putString("cmds", JSONArray().also { a -> commands.forEach { a.put(it.toJson()) } }.toString()).apply() }

    fun addCommand(trigger: String, action: String) { commands.removeAll { it.trigger == trigger }; commands += Cmd(trigger, action); saveCommands() }
    fun removeCommand(c: Cmd) { commands.remove(c); saveCommands() }

    private fun savePoses() {
        val j = JSONObject()
        for ((k, v) in poses) j.put(k, JSONArray().also { a -> v.forEach { s -> a.put(JSONArray(s.map { it.toDouble() })) } })
        try { posesFile.parentFile?.mkdirs(); posesFile.writeText(j.toString()) } catch (t: Throwable) { PrismLog.i("ERR poses save ${t.message}") }
    }

    fun deletePose(name: String) { poses.remove(name); poseNames.remove(name); commands.removeAll { it.trigger == "pose:$name" }; savePoses(); saveCommands() }

    // ───────────────────────────── recording / calibration ─────────────────────────────

    private var recUntil = 0L
    private var recName = ""
    private val recSamples = ArrayList<FloatArray>()
    private var calUntil = 0L
    private val calSum = HashMap<String, Float>()
    private var calN = 0

    /** record a hand pose for 3 s (hold the pose in front of the camera) */
    fun recordPose(name: String) { recName = name; recSamples.clear(); recUntil = SystemClock.uptimeMillis() + 3000; recording = name }
    /** "fit to my face": 3 s of a relaxed face */
    fun calibrate() { calSum.clear(); calN = 0; calUntil = SystemClock.uptimeMillis() + 3000; calibrating = true }
    fun clearCalibration() { Tracking.rest = emptyMap(); saveTuning() }

    // ───────────────────────────── per frame (AI thread) ─────────────────────────────

    private val holdSince = HashMap<String, Long>()
    private val armed = HashMap<String, Boolean>()
    private var lastFire = 0L
    private var fistSince = 0L
    private var lastPt: FloatArray? = null
    @Volatile var front = false

    fun onFrame() {
        val now = SystemClock.uptimeMillis()
        val faces = Tracking.faces; val hands = Tracking.hands
        val active = HashSet<String>()
        val face = faces.firstOrNull()
        val thr = .75f - Tracking.sensitivity * .4f
        if (face != null) {
            for (t in faceTriggers) if (Tracking.expr(face, t) > thr) active += t
            // calibration
            if (calUntil > 0) {
                if (now < calUntil) { for ((k, v) in face.shapes) calSum[k] = (calSum[k] ?: 0f) + v; calN++ }
                else {
                    calUntil = 0
                    if (calN > 5) { Tracking.rest = calSum.mapValues { it.value / calN }; saveTuning() }
                    main.post { calibrating = false }
                    PrismLog.i("TRACK calibrated n=$calN")
                }
            }
        }
        for (h in hands) {
            if (h.gesture.isNotEmpty()) active += h.gesture
            val n = normalise(h)
            classify(n)?.let { active += "pose:$it" }
            if (face != null) zoneOf(h, face)?.let { active += it }
            if (recUntil > 0 && h === hands.first()) recSamples += n
        }
        if (recUntil > 0 && now >= recUntil) {
            recUntil = 0
            val name = recName
            if (recSamples.size >= 5) { poses.getOrPut(name) { mutableListOf() }.addAll(recSamples.takeLast(60)); savePoses(); main.post { if (name !in poseNames) poseNames += name } }
            PrismLog.i("TRACK pose '$name' samples=${recSamples.size}")
            main.post { recording = null }
        }
        if (Tracking.pair.isNotEmpty()) active += Tracking.pair
        airDraw(hands, now)
        // commands: held for 0.5 s, fire once, must let go before firing again
        val seen = active.sorted().joinToString(" ")
        if (seen != lastSeen) { lastSeen = seen; main.post { seenNow = seen } }
        for (c in commands) {
            val on = c.trigger in active
            if (!on) { holdSince.remove(c.trigger); armed[c.trigger] = true; continue }
            val since = holdSince.getOrPut(c.trigger) { now }
            if (armed[c.trigger] != false && now - since >= 500 && now - lastFire > 1200) {
                armed[c.trigger] = false; lastFire = now
                PrismLog.i("TRACK fire ${c.trigger} → ${c.action}")
                main.post { lastFired = c.trigger; run(c.action) }
            }
        }
    }
    private var lastSeen = ""

    /** hand landmarks → translation / size / rotation free, right-hand shape (42 numbers) */
    fun normalise(h: Tracking.Hand): FloatArray {
        val p = h.pts
        val ox = p[0]; val oy = p[1]
        val mx = p[18] - ox; val my = p[19] - oy
        val size = hypot(mx, my).coerceAtLeast(1e-4f)
        val a = atan2(my, mx) + (Math.PI / 2).toFloat()
        val c = cos(-a); val s = sin(-a)
        val mirror = if (h.right) 1f else -1f
        return FloatArray(42) { i ->
            val k = i / 2; val x = (p[k * 2] - ox) / size; val y = (p[k * 2 + 1] - oy) / size
            if (i % 2 == 0) (x * c - y * s) * mirror else x * s + y * c
        }
    }

    /** nearest neighbours over the recorded poses (k = 5, with a distance limit so random hands do not match) */
    private fun classify(n: FloatArray): String? {
        if (poses.isEmpty()) return null
        val best = ArrayList<Pair<Float, String>>()
        for ((name, list) in poses) for (s in list) {
            var d = 0f; for (i in n.indices) { val e = n[i] - s[i]; d += e * e }
            best += d to name
        }
        best.sortBy { it.first }
        val top = best.take(5)
        if (top.first().first > 1.6f) return null
        return top.groupingBy { it.second }.eachCount().maxByOrNull { it.value }?.takeIf { it.value >= 3 }?.key
    }

    /** hand (index tip / palm) close to a face zone, measured in face widths */
    private fun zoneOf(h: Tracking.Hand, f: Tracking.Face): String? {
        val fw = Tracking.pose(f).width.coerceAtLeast(1e-3f)
        val tip = Tracking.handPoint(h, "index"); val palm = Tracking.handPoint(h, "palm")
        fun near(pt: String, r: Float): Boolean {
            val q = Tracking.facePoint(f, pt)
            return minOf(hypot(tip[0] - q[0], tip[1] - q[1]), hypot(palm[0] - q[0], palm[1] - q[1])) < fw * r
        }
        return when {
            near("mouth", .28f) -> "zone:mouth"
            near("forehead", .3f) -> "zone:forehead"
            near("earL", .25f) || near("earR", .25f) -> "zone:ear"
            near("cheekL", .25f) || near("cheekR", .25f) -> "zone:cheek"
            else -> null
        }
    }

    /** index finger points → line on the drawing layer (HandFx "draw" makes one if needed) */
    private fun airDraw(hands: List<Tracking.Hand>, now: Long) {
        val fxL = Studio.layers.firstOrNull { it.type == LType.HandFx && it.preset == "draw" && it.visible } ?: run { lastPt = null; return }
        val h = hands.firstOrNull { when (fxL.attach.who) { "left" -> !it.right; "right" -> it.right; else -> true } }
        if (h == null) { lastPt = null; return }
        if (h.gesture == "fist") {
            if (fistSince == 0L) fistSince = now
            if (now - fistSince > 1200) { fistSince = Long.MAX_VALUE / 2; main.post { clearDrawing() } }
        } else fistSince = 0L
        if (h.gesture != "point") { if (lastPt != null) main.post { Studio.commit("air") }; lastPt = null; return }
        val cam = Studio.camera
        val tip = Tracking.handPoint(h, "index")
        // camera space → canvas fractions (through the camera layer)
        val snap = cam?.let { Pump.snapshot(it, 1000, (1000 * Make.canvasAspect).toInt()) }
        val p = Geometry.camToCanvas(snap, front, 1f, Make.canvasAspect, tip[0], tip[1])
        val pt = floatArrayOf(p[0], p[1] / Make.canvasAspect)
        val start = lastPt == null
        lastPt = pt
        val color = fxL.color; val width = Make.penWidth
        main.post {
            val d = Studio.layers.lastOrNull { it.type == LType.Drawing } ?: Make.drawing().also { Studio.selection.clear() }
            if (start || d.strokes.isEmpty()) d.strokes.add(Stroke(pt, color, width, true))
            else { val last = d.strokes.last(); d.strokes[d.strokes.size - 1] = Stroke(last.pts + pt, last.color, last.width, last.glow) }
            d.touchContent()
        }
    }

    fun clearDrawing() {
        Studio.layers.filter { it.type == LType.Drawing }.forEach { it.strokes.clear(); it.touchContent() }
        Studio.commit("clear draw")
    }
}
