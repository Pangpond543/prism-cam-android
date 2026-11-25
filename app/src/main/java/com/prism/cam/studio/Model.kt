package com.prism.cam.studio

import android.content.Context
import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.prism.cam.PrismLog
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * The studio document — same ideas as the desktop Prism Cam (studio.js `newLayer`): scenes → layers, each layer with
 * transform, look, chroma key, face / hand attachment, motion. Positions are fractions of the canvas (the picture as
 * the user sees it): x / y = centre, w = width as a fraction of the canvas width, `aspect` = content height / width (px).
 * Everything the UI touches is Compose state; the GPU thread works from immutable snapshots ([RLayer]).
 */
enum class LType { Camera, Image, Video, Text, Sticker, Shape, Frame, Drawing, Avatar, FacePaint, FaceFx, HandFx, Lyrics, Screen }

class Attach {
    /** "" = not attached · "face" · "hand" */
    var kind by mutableStateOf("")
    /** face: eyes · nose · mouth · forehead · chin · cheekL · cheekR · earL · earR · top · face — hand: palm · thumb · index · middle · ring · pinky · wrist */
    var point by mutableStateOf("eyes")
    /** which face / hand: "0".."3" or "all" (face) · "any" | "left" | "right" (hand) */
    var who by mutableStateOf("0")
    var followRot by mutableStateOf(true)
    var followScale by mutableStateOf(true)
    /** squash with head turn (face) */
    var followTurn by mutableStateOf(true)
    /** offset from the point, in face / hand widths */
    var ox by mutableFloatStateOf(0f)
    var oy by mutableFloatStateOf(0f)
    /** size relative to the face / hand width (1 = as wide as the face) */
    var size by mutableFloatStateOf(1f)
    /** hide · stay · fade when the face / hand is lost */
    var lost by mutableStateOf("hide")
    /** show only while this face expression / hand pose is held ("" = always) */
    var only by mutableStateOf("")
    var smooth by mutableFloatStateOf(.5f)

    fun toJson() = JSONObject().put("kind", kind).put("point", point).put("who", who).put("rot", followRot).put("scale", followScale)
        .put("turn", followTurn).put("ox", ox.toDouble()).put("oy", oy.toDouble()).put("size", size.toDouble()).put("lost", lost).put("only", only)
        .put("smooth", smooth.toDouble())

    fun read(j: JSONObject?) {
        j ?: return
        kind = j.optString("kind"); point = j.optString("point", "eyes"); who = j.optString("who", "0")
        followRot = j.optBoolean("rot", true); followScale = j.optBoolean("scale", true); followTurn = j.optBoolean("turn", true)
        ox = j.optDouble("ox", 0.0).toFloat(); oy = j.optDouble("oy", 0.0).toFloat(); size = j.optDouble("size", 1.0).toFloat()
        lost = j.optString("lost", "hide"); only = j.optString("only"); smooth = j.optDouble("smooth", .5).toFloat()
    }
}

class Layer(val id: Long, val type: LType) {
    var name by mutableStateOf("")
    var visible by mutableStateOf(true)
    var locked by mutableStateOf(false)
    var x by mutableFloatStateOf(.5f)
    var y by mutableFloatStateOf(.5f)
    var w by mutableFloatStateOf(.4f)
    /** content height / width in pixels (kept when resizing) */
    var aspect by mutableFloatStateOf(1f)
    var rot by mutableFloatStateOf(0f)
    var opacity by mutableFloatStateOf(1f)
    var flipX by mutableStateOf(false)
    var flipY by mutableStateOf(false)
    /** crop fractions of the content: left, top, right, bottom */
    var cropL by mutableFloatStateOf(0f)
    var cropT by mutableFloatStateOf(0f)
    var cropR by mutableFloatStateOf(0f)
    var cropB by mutableFloatStateOf(0f)
    /** corner radius as a fraction of the shorter side (0..0.5); mask "circle" = ellipse */
    var radius by mutableFloatStateOf(0f)
    var mask by mutableStateOf("rect")
    /** border width as a fraction of the shorter side */
    var border by mutableFloatStateOf(0f)
    var borderColor by mutableIntStateOf(Color.WHITE)
    /** drop shadow strength 0..1 */
    var shadow by mutableFloatStateOf(0f)
    /** normal · screen · add · multiply */
    var blend by mutableStateOf("normal")
    /** look id (Looks) + adjustments */
    var look by mutableStateOf("none")
    var bright by mutableFloatStateOf(0f)
    var contrast by mutableFloatStateOf(0f)
    var sat by mutableFloatStateOf(0f)
    var hue by mutableFloatStateOf(0f)
    /** chroma key (green screen) */
    var keyOn by mutableStateOf(false)
    var keyColor by mutableIntStateOf(0xFF00D000.toInt())
    var keyTol by mutableFloatStateOf(.3f)
    val attach = Attach()
    /** motion preset ("" none · pop · float · bounce · spin · pulse · shake · slideL · slideR · typewriter · glow) */
    var anim by mutableStateOf("")
    var animSpeed by mutableFloatStateOf(1f)

    // ── content
    var text by mutableStateOf("")
    var color by mutableIntStateOf(Color.WHITE)
    var color2 by mutableIntStateOf(0xFFFF9EC7.toInt())
    /** text: 0 shadow · 1 outline · 2 label · 3 Prism — shape: rect · round · circle · star · heart · arrow · bubble */
    var style by mutableIntStateOf(0)
    var shape by mutableStateOf("round")
    var grad by mutableStateOf(true)
    /** media file in the app's library (images / GIF / video) */
    var media by mutableStateOf("")
    var loop by mutableStateOf(true)
    var muted by mutableStateOf(true)
    var speed by mutableFloatStateOf(1f)
    /** frame id / face paint id / avatar kind / face fx id */
    var preset by mutableStateOf("")
    /** camera layer: which camera (back / front) — "main" follows the app's lens switch */
    var lens by mutableStateOf("main")
    /** keyframes: [t, x, y, w, rot, opacity] each; played in a loop of [keyLen] seconds */
    val keys = mutableStateListOf<FloatArray>()
    var keyLen by mutableFloatStateOf(4f)
    /** drawing strokes: list of float arrays (x, y pairs in canvas fractions), colour + width per stroke */
    val strokes = mutableStateListOf<Stroke>()

    /** bumps when the pixels of the content change (text, colours, file…) → re-render its texture */
    var contentVersion by mutableIntStateOf(0)
    fun touchContent() { contentVersion++; Studio.touch() }

    fun toJson(): JSONObject = JSONObject().apply {
        put("id", id); put("type", type.name); put("name", name); put("vis", visible); put("lock", locked)
        put("x", x.d); put("y", y.d); put("w", w.d); put("aspect", aspect.d); put("rot", rot.d); put("op", opacity.d)
        put("flipX", flipX); put("flipY", flipY); put("crop", JSONArray(listOf(cropL.d, cropT.d, cropR.d, cropB.d)))
        put("radius", radius.d); put("mask", mask); put("border", border.d); put("borderColor", borderColor); put("shadow", shadow.d)
        put("blend", blend); put("look", look); put("bright", bright.d); put("contrast", contrast.d); put("sat", sat.d); put("hue", hue.d)
        put("keyOn", keyOn); put("keyColor", keyColor); put("keyTol", keyTol.d); put("attach", attach.toJson())
        put("anim", anim); put("animSpeed", animSpeed.d)
        put("text", text); put("color", color); put("color2", color2); put("style", style); put("shape", shape); put("grad", grad)
        put("media", media); put("loop", loop); put("muted", muted); put("speed", speed.d); put("preset", preset); put("lens", lens)
        put("strokes", JSONArray().also { a -> strokes.forEach { a.put(it.toJson()) } })
        put("keys", JSONArray().also { a -> keys.forEach { k -> a.put(JSONArray(k.map { it.toDouble() })) } }); put("keyLen", keyLen.d)
    }

    companion object {
        private val Float.d get() = toDouble()
        fun fromJson(j: JSONObject): Layer? {
            val t = try { LType.valueOf(j.optString("type")) } catch (_: Throwable) { return null }
            return Layer(j.optLong("id"), t).apply {
                name = j.optString("name"); visible = j.optBoolean("vis", true); locked = j.optBoolean("lock")
                x = j.f("x", .5f); y = j.f("y", .5f); w = j.f("w", .4f); aspect = j.f("aspect", 1f); rot = j.f("rot", 0f); opacity = j.f("op", 1f)
                flipX = j.optBoolean("flipX"); flipY = j.optBoolean("flipY")
                j.optJSONArray("crop")?.let { c -> cropL = c.optDouble(0).toFloat(); cropT = c.optDouble(1).toFloat(); cropR = c.optDouble(2).toFloat(); cropB = c.optDouble(3).toFloat() }
                radius = j.f("radius", 0f); mask = j.optString("mask", "rect"); border = j.f("border", 0f); borderColor = j.optInt("borderColor", Color.WHITE)
                shadow = j.f("shadow", 0f); blend = j.optString("blend", "normal"); look = j.optString("look", "none")
                bright = j.f("bright", 0f); contrast = j.f("contrast", 0f); sat = j.f("sat", 0f); hue = j.f("hue", 0f)
                keyOn = j.optBoolean("keyOn"); keyColor = j.optInt("keyColor", 0xFF00D000.toInt()); keyTol = j.f("keyTol", .3f)
                attach.read(j.optJSONObject("attach")); anim = j.optString("anim"); animSpeed = j.f("animSpeed", 1f)
                text = j.optString("text"); color = j.optInt("color", Color.WHITE); color2 = j.optInt("color2", 0xFFFF9EC7.toInt())
                style = j.optInt("style"); shape = j.optString("shape", "round"); grad = j.optBoolean("grad", true)
                media = j.optString("media"); loop = j.optBoolean("loop", true); muted = j.optBoolean("muted", true); speed = j.f("speed", 1f)
                preset = j.optString("preset"); lens = j.optString("lens", "main")
                j.optJSONArray("strokes")?.let { a -> for (i in 0 until a.length()) Stroke.fromJson(a.getJSONObject(i))?.let { strokes += it } }
                j.optJSONArray("keys")?.let { a -> for (i in 0 until a.length()) a.getJSONArray(i).let { k -> keys += FloatArray(k.length()) { n -> k.optDouble(n).toFloat() } } }
                keyLen = j.f("keyLen", 4f)
            }
        }
        private fun JSONObject.f(k: String, d: Float) = optDouble(k, d.toDouble()).toFloat()
    }
}

class Stroke(val pts: FloatArray, val color: Int, val width: Float, val glow: Boolean) {
    fun toJson() = JSONObject().put("pts", JSONArray(pts.map { it.toDouble() })).put("color", color).put("width", width.toDouble()).put("glow", glow)
    companion object {
        fun fromJson(j: JSONObject): Stroke? {
            val a = j.optJSONArray("pts") ?: return null
            return Stroke(FloatArray(a.length()) { a.optDouble(it).toFloat() }, j.optInt("color", Color.WHITE), j.optDouble("width", .01).toFloat(), j.optBoolean("glow"))
        }
    }
}

class SceneDoc(val id: Long, name: String) {
    var name by mutableStateOf(name)
    val layers = mutableStateListOf<Layer>()
    var bg by mutableIntStateOf(Color.BLACK)
    fun toJson() = JSONObject().put("id", id).put("name", name).put("bg", bg).put("layers", JSONArray().also { a -> layers.forEach { a.put(it.toJson()) } })
}

/** Immutable copy of a layer for the GPU thread (taken on the main thread whenever the scene changes). */
data class RLayer(
    val id: Long, val type: LType, val x: Float, val y: Float, val w: Float, val aspect: Float, val rot: Float, val opacity: Float,
    val flipX: Boolean, val flipY: Boolean, val crop: FloatArray, val radius: Float, val circle: Boolean, val border: Float, val borderColor: Int,
    val shadow: Float, val blend: String, val color: FloatArray, val keyOn: Boolean, val keyColor: Int, val keyTol: Float,
    val attachKind: String, val attachPoint: String, val attachWho: String, val followRot: Boolean, val followScale: Boolean, val followTurn: Boolean,
    val ox: Float, val oy: Float, val attachSize: Float, val lost: String, val only: String, val anim: String, val animSpeed: Float,
    val contentKey: String, val media: String, val loop: Boolean, val muted: Boolean, val speed: Float, val lens: String,
    val look: String = "none",
    /** content colour (face / hand fx) and preset id */
    val tint: Int = -1, val preset: String = "", val tint2: Int = -1,
    /** keyframes (flattened [t, x, y, w, rot, opacity] × n) and loop length (s) */
    val keys: FloatArray = FloatArray(0), val keyLen: Float = 4f,
)

object Studio {
    val scenes = mutableStateListOf<SceneDoc>()
    var current by mutableIntStateOf(0)
    val scene: SceneDoc get() = scenes.getOrNull(current) ?: scenes.first()
    val layers get() = scene.layers
    /** selected layer ids (first = primary) */
    val selection = mutableStateListOf<Long>()
    var version by mutableIntStateOf(0)
    /** slideshow: seconds per scene (0 = off) */
    var autoPlay by mutableIntStateOf(0)
    private var nextId = 1L
    private lateinit var dir: File

    fun touch() { version++ }
    fun newId() = nextId++

    fun selected(): Layer? = selection.firstOrNull()?.let { id -> layers.firstOrNull { it.id == id } }
    fun select(id: Long?, add: Boolean = false) {
        if (!add) selection.clear()
        if (id != null) { if (id in selection) selection.remove(id); selection.add(0, id) }
    }

    val camera: Layer? get() = layers.firstOrNull { it.type == LType.Camera }

    // ───────────────────────────── building ─────────────────────────────

    fun add(l: Layer, select: Boolean = true): Layer {
        if (l.name.isEmpty()) l.name = defaultName(l)
        layers += l
        if (select) select(l.id)
        commit("add")
        return l
    }

    fun remove(id: Long) {
        val l = layers.firstOrNull { it.id == id } ?: return
        if (l.type == LType.Camera && layers.count { it.type == LType.Camera } <= 1) return
        layers.remove(l); selection.remove(id); commit("remove")
    }

    fun duplicate(id: Long): Layer? {
        val l = layers.firstOrNull { it.id == id } ?: return null
        val c = Layer.fromJson(l.toJson().put("id", newId())) ?: return null
        c.x = (c.x + .04f).coerceAtMost(1f); c.y = (c.y + .04f).coerceAtMost(1f); c.name = l.name + " 2"
        val i = layers.indexOf(l)
        layers.add(i + 1, c); select(c.id); commit("duplicate")
        return c
    }

    fun move(id: Long, to: Int) {
        val i = layers.indexOfFirst { it.id == id }; if (i < 0) return
        val l = layers.removeAt(i)
        layers.add(to.coerceIn(0, layers.size), l); commit("order")
    }

    fun defaultName(l: Layer) = when (l.type) {
        LType.Camera -> "camera"; LType.Text -> l.text.take(20); LType.Sticker -> l.text
        else -> l.type.name.lowercase()
    }

    /** a fresh document: one scene with the camera filling the picture */
    fun reset() {
        scenes.clear(); selection.clear()
        val s = SceneDoc(newId(), "1")
        s.layers += Layer(newId(), LType.Camera).apply { name = "camera"; w = 1f; aspect = 1f; x = .5f; y = .5f }
        scenes += s; current = 0; touch()
    }

    fun addScene(copyFrom: SceneDoc? = null): SceneDoc {
        val s = SceneDoc(newId(), "${scenes.size + 1}")
        if (copyFrom != null) copyFrom.layers.forEach { l -> Layer.fromJson(l.toJson().put("id", newId()))?.let { s.layers += it } }
        else s.layers += Layer(newId(), LType.Camera).apply { name = "camera"; w = 1f }
        scenes += s; current = scenes.size - 1; selection.clear(); commit("scene"); return s
    }

    fun removeScene(i: Int) {
        if (scenes.size <= 1) return
        scenes.removeAt(i); current = current.coerceIn(0, scenes.size - 1); selection.clear(); commit("scene")
    }

    fun goScene(i: Int) { if (i in scenes.indices) { current = i; selection.clear(); touch(); saveSoon() } }

    // ───────────────────────────── undo / redo ─────────────────────────────

    private val undo = ArrayDeque<String>()
    private val redo = ArrayDeque<String>()
    private var lastSaved = ""
    var canUndo by mutableStateOf(false); private set
    var canRedo by mutableStateOf(false); private set

    /** a finished change: remember the previous state for undo, save soon */
    fun commit(@Suppress("UNUSED_PARAMETER") why: String = "") {
        touch()
        val now = toJson().toString()
        if (lastSaved.isNotEmpty() && lastSaved != now) {
            undo.addLast(lastSaved); if (undo.size > 60) undo.removeFirst(); redo.clear()
        }
        lastSaved = now
        canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty()
        saveSoon()
    }

    fun undo() { val prev = undo.removeLastOrNull() ?: return; redo.addLast(lastSaved); restore(prev) }
    fun redo() { val nxt = redo.removeLastOrNull() ?: return; undo.addLast(lastSaved); restore(nxt) }
    private fun restore(s: String) {
        try { load(JSONObject(s)) } catch (_: Throwable) { }
        lastSaved = s; canUndo = undo.isNotEmpty(); canRedo = redo.isNotEmpty(); touch(); saveSoon()
    }

    // ───────────────────────────── save / load ─────────────────────────────

    fun toJson(): JSONObject = JSONObject().put("prismStudio", 1).put("current", current).put("nextId", nextId)
        .put("scenes", JSONArray().also { a -> scenes.forEach { a.put(it.toJson()) } })

    /** add the scenes of another project (import): fresh ids, media names kept */
    fun appendScenes(j: JSONObject): Int {
        val a = j.optJSONArray("scenes") ?: return 0
        var n = 0
        for (i in 0 until a.length()) {
            val sj = a.getJSONObject(i)
            val s = SceneDoc(newId(), sj.optString("name", "${scenes.size + 1}")).apply { bg = sj.optInt("bg", Color.BLACK) }
            sj.optJSONArray("layers")?.let { la -> for (k in 0 until la.length()) Layer.fromJson(la.getJSONObject(k).put("id", newId()))?.let { s.layers += it } }
            if (s.layers.none { it.type == LType.Camera }) s.layers.add(0, Layer(newId(), LType.Camera).apply { name = "camera"; w = 1f })
            scenes += s; n++
        }
        if (n > 0) { current = scenes.size - n; selection.clear(); commit("import") }
        return n
    }

    private fun load(j: JSONObject) {
        val keepSel = selection.toList()
        scenes.clear()
        nextId = maxOf(nextId, j.optLong("nextId", 1))
        val a = j.optJSONArray("scenes") ?: JSONArray()
        for (i in 0 until a.length()) {
            val sj = a.getJSONObject(i)
            val s = SceneDoc(sj.optLong("id"), sj.optString("name", "${i + 1}")).apply { bg = sj.optInt("bg", Color.BLACK) }
            sj.optJSONArray("layers")?.let { la -> for (k in 0 until la.length()) Layer.fromJson(la.getJSONObject(k))?.let { s.layers += it } }
            if (s.layers.none { it.type == LType.Camera }) s.layers.add(0, Layer(nextId++, LType.Camera).apply { name = "camera"; w = 1f })
            scenes += s
        }
        if (scenes.isEmpty()) reset()
        current = j.optInt("current").coerceIn(0, scenes.size - 1)
        selection.clear(); selection.addAll(keepSel.filter { id -> layers.any { it.id == id } })
        for (s in scenes) for (l in s.layers) nextId = maxOf(nextId, l.id + 1)
    }

    fun init(ctx: Context) {
        dir = File(ctx.filesDir, "studio").apply { mkdirs() }
        val f = File(dir, "studio.json")
        val ok = try { if (f.exists()) { load(JSONObject(f.readText())); true } else false } catch (t: Throwable) {
            PrismLog.i("ERR studio load ${t.message}")
            // keep the broken file, fall back to the previous save
            f.renameTo(File(dir, "studio.broken-${System.currentTimeMillis()}.json"))
            try { load(JSONObject(File(dir, "studio.json.bak").readText())); true } catch (_: Throwable) { false }
        }
        if (!ok) reset()
        lastSaved = toJson().toString()
        touch()
    }

    /**
     * Test runs: keep the user's own scenes before any test act changes them (files/studio/before-test.json, written
     * once per test session = only while no copy exists), and put them back with act "studiorestore".
     * Lesson 2026-11-25: test acts (studioreset / template) wiped a scene the user had built.
     */
    fun keepForTest() {
        if (!::dir.isInitialized) return
        val keep = File(dir, "before-test.json")
        // a copy older than 6 h belongs to an earlier test session that was never restored: the user's work since then wins
        if (keep.exists() && System.currentTimeMillis() - keep.lastModified() > 6 * 3600_000L) keep.delete()
        if (!keep.exists()) try { keep.writeText(toJson().toString()); PrismLog.i("STUDIO kept the user's scenes before the test") } catch (_: Throwable) { }
    }

    fun restoreAfterTest(): Boolean {
        if (!::dir.isInitialized) return false
        val keep = File(dir, "before-test.json")
        if (!keep.exists()) return false
        return try { load(JSONObject(keep.readText())); commit("restore"); keep.delete(); PrismLog.i("STUDIO restored the user's scenes"); true }
        catch (t: Throwable) { PrismLog.i("ERR studio restore ${t.message}"); false }
    }

    private var saveJob: Thread? = null
    @Volatile private var dirty = false
    fun saveSoon() {
        if (!::dir.isInitialized) return
        dirty = true
        if (saveJob?.isAlive == true) return
        val text = toJson().toString()
        saveJob = Thread {
            try {
                Thread.sleep(800)
                val f = File(dir, "studio.json")
                if (f.exists()) f.copyTo(File(dir, "studio.json.bak"), overwrite = true)
                val tmp = File(dir, "studio.json.tmp"); tmp.writeText(toJson().toString().ifEmpty { text }); tmp.renameTo(f)
                dirty = false
            } catch (t: Throwable) { PrismLog.i("ERR studio save ${t.message}") }
        }.apply { isDaemon = true; start() }
    }
}
