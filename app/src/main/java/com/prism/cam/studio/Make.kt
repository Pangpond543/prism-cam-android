package com.prism.cam.studio

import android.graphics.Color
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

/** Ready-made layers (the ✦ sheet, the "+" menu, face packs, camera layouts). Each call is one undo step. */
object Make {
    fun text(t: String, color: Int = Color.WHITE, style: Int = 0): Layer = Studio.add(Layer(Studio.newId(), LType.Text).apply {
        text = t; this.color = color; this.style = style; w = .5f; y = .62f; aspect = .25f
    })

    fun sticker(e: String): Layer = Studio.add(Layer(Studio.newId(), LType.Sticker).apply {
        text = e; w = .22f; aspect = 1f; x = .5f + (Math.random().toFloat() - .5f) * .2f; y = .45f + (Math.random().toFloat() - .5f) * .2f
    })

    fun shape(s: String, color: Int = 0xFF7C8CFF.toInt(), color2: Int = 0xFFFF9EC7.toInt()): Layer = Studio.add(Layer(Studio.newId(), LType.Shape).apply {
        shape = s; this.color = color; this.color2 = color2; w = .3f; aspect = if (s == "arrow") .5f else if (s == "bubble") .7f else 1f
    })

    /** one frame at a time (like the desktop's frame layer): picking another replaces it, "none" removes it */
    fun frame(id: String) {
        Studio.layers.removeAll { it.type == LType.Frame }
        if (id != "none") Studio.layers += Layer(Studio.newId(), LType.Frame).apply { preset = id; name = "frame"; w = 1f }
        Studio.commit("frame")
    }

    fun frameId(): String = Studio.layers.lastOrNull { it.type == LType.Frame }?.preset ?: "none"

    /** a picture / GIF / video from the library */
    fun media(m: Content.Media): Layer = Studio.add(Layer(Studio.newId(), m.kind).apply {
        media = m.file.name; name = m.file.nameWithoutExtension.take(16)
        aspect = if (m.w > 0 && m.h > 0) m.h.toFloat() / m.w else 1f
        w = if (aspect > 1.2f) .34f else .5f
    })

    // ── finger / air drawing pen
    var penColor by mutableIntStateOf(0xFFFF9EC7.toInt())
    var penWidth by mutableFloatStateOf(.01f)
    var penGlow by mutableStateOf(true)

    fun drawing(): Layer = Studio.add(Layer(Studio.newId(), LType.Drawing).apply { w = 1f; name = "drawing" })

    // ───────────────────────────── face & hand art (phases 8–9) ─────────────────────────────

    /** face paint wrapped on the face (one per face set; picking another changes it) */
    fun facePaint(id: String?) {
        Studio.layers.removeAll { it.type == LType.FacePaint }
        if (id != null) Studio.layers += Layer(Studio.newId(), LType.FacePaint).apply {
            preset = id; color = FaceArt.paintColors[id] ?: Color.MAGENTA; name = "paint"; w = 1f; attach.who = "all"; blend = if (id == "neon") "add" else "normal"
        }
        Studio.commit("paint")
    }
    fun facePaintOn(): String? = Studio.layers.firstOrNull { it.type == LType.FacePaint }?.preset

    /** a VTuber character that copies your face */
    fun avatar(kind: String): Layer {
        Studio.layers.firstOrNull { it.type == LType.Avatar }?.let { a ->
            a.preset = kind; val (c1, c2) = FaceArt.avatarColors[kind] ?: (Color.WHITE to Color.WHITE); a.color = c1; a.color2 = c2; Studio.commit("avatar"); return a
        }
        val (c1, c2) = FaceArt.avatarColors[kind] ?: (Color.WHITE to Color.WHITE)
        return Studio.add(Layer(Studio.newId(), LType.Avatar).apply { preset = kind; color = c1; color2 = c2; w = .42f; aspect = 1f; x = .5f; y = .58f; attach.who = "0" })
    }
    fun avatarOn(): String? = Studio.layers.firstOrNull { it.type == LType.Avatar }?.preset

    fun faceFx(id: String?) = fxLayer(LType.FaceFx, id, 0xFF86E8D6.toInt())
    /** hand effects mix (neon + lightning + orb…): each one switches on / off by itself; null = all off */
    fun handFx(id: String?) {
        if (id == null) { Studio.layers.removeAll { it.type == LType.HandFx }; Studio.commit("fx"); return }
        val had = Studio.layers.removeAll { it.type == LType.HandFx && it.preset == id }
        if (!had) Studio.layers += Layer(Studio.newId(), LType.HandFx).apply {
            preset = id; color = handFxColors[id] ?: 0xFF6CC7FF.toInt(); if (id == "draw") color = penColor
            name = id; w = 1f; attach.who = "any"; if (id == "lightning" || id == "orb" || id == "ball") blend = "add"
        }
        Studio.commit("fx")
    }
    fun handFxOn(id: String) = Studio.layers.any { it.type == LType.HandFx && it.preset == id }
    val handFxColors = mapOf("neon" to 0xFF6CC7FF.toInt(), "trail" to 0xFFFFD98A.toInt(), "lightning" to 0xFF9AB8FF.toInt(), "ball" to 0xFF7CF0FF.toInt(), "orb" to 0xFFC08CFF.toInt())
    fun fxOn(t: LType): String? = Studio.layers.firstOrNull { it.type == t }?.preset

    // ── things that live between your two hands
    val twoHandIds = listOf("heart", "pray", "banner", "rainbow")
    val twoHandIcons = mapOf("heart" to "💖", "pray" to "✨", "banner" to "🎀", "rainbow" to "🌈")
    val twoHandNames = mapOf("heart" to "มือหัวใจ", "pray" to "พนมมือ", "banner" to "ป้ายข้อความ", "rainbow" to "สายรุ้ง")
    fun twoHandOn(id: String) = Studio.layers.any { it.preset == "two:$id" }

    /** add (or take away) a ready-made two-hand layer; the user can change it like any layer afterwards */
    fun twoHand(id: String) {
        if (Studio.layers.removeAll { it.preset == "two:$id" }) { Studio.commit("two"); return }
        val l = when (id) {
            "heart" -> Layer(Studio.newId(), LType.Sticker).apply { text = "💖"; aspect = 1f; anim = "heartbeat"; attach.point = "palm"; attach.size = 1.3f; attach.oy = -.2f; attach.only = "pair:heart"; attach.lost = "fade" }
            "pray" -> Layer(Studio.newId(), LType.Sticker).apply { text = "✨"; aspect = 1f; anim = "pulse"; attach.point = "middle"; attach.size = 1.6f; attach.oy = -.1f; attach.only = "pair:pray"; attach.lost = "fade"; attach.followScale = false; w = .3f }
            "banner" -> Layer(Studio.newId(), LType.Text).apply { text = "สวัสดี!"; color = 0xFFFF7EB6.toInt(); style = 2; aspect = .25f; attach.point = "palm"; attach.size = .85f }
            else -> Layer(Studio.newId(), LType.Sticker).apply { text = "🌈"; aspect = 1f; attach.point = "index"; attach.size = 1.05f; attach.oy = -.12f; anim = "float" }
        }
        l.preset = "two:$id"; l.name = (twoHandIcons[id] ?: "") + " " + id
        l.attach.kind = "hands2"; l.attach.who = "any"
        Studio.layers += l
        Studio.select(l.id)
        Studio.commit("two")
    }

    private fun fxLayer(t: LType, id: String?, color: Int) {
        Studio.layers.removeAll { it.type == t }
        if (id != null) Studio.layers += Layer(Studio.newId(), t).apply { preset = id; this.color = color; name = id; w = 1f; attach.who = if (t == LType.HandFx) "any" else "all" }
        Studio.commit("fx")
    }

    // ───────────────────────────── face packs (attached stickers) ─────────────────────────────

    class Part(val emoji: String? = null, val shape: String? = null, val color: Int = 0, val point: String, val size: Float,
               val ox: Float = 0f, val oy: Float = 0f, val rot: Float = 0f, val opacity: Float = 1f, val anim: String = "")

    /** the old v1 face stickers, now real layers you can move, resize and tune */
    val packs: Map<String, List<Part>> = linkedMapOf(
        "glasses" to listOf(Part("🕶️", point = "eyes", size = .95f, oy = -.02f)),
        "crown" to listOf(Part("👑", point = "top", size = .62f, oy = .05f)),
        "cat" to listOf(
            Part("🔺", point = "top", size = .3f, ox = -.3f, oy = -.02f, rot = -18f),
            Part("🔺", point = "top", size = .3f, ox = .3f, oy = -.02f, rot = 18f),
            Part(shape = "circle", color = 0xFFFF9EC7.toInt(), point = "nose", size = .12f, oy = .01f),
        ),
        "blush" to listOf(
            Part(shape = "circle", color = 0xFFFF6B8B.toInt(), point = "cheekL", size = .22f, opacity = .38f),
            Part(shape = "circle", color = 0xFFFF6B8B.toInt(), point = "cheekR", size = .22f, opacity = .38f),
        ),
        "hearts" to listOf(Part("💕", point = "top", size = .45f, oy = .08f, anim = "float"), Part("💖", point = "forehead", size = .2f, ox = .45f, anim = "pulse")),
        "clown" to listOf(Part(shape = "circle", color = 0xFFFF3B3B.toInt(), point = "nose", size = .2f)),
    )
    val packIcons = mapOf("glasses" to "🕶️", "crown" to "👑", "cat" to "🐱", "blush" to "☺️", "hearts" to "💕", "clown" to "🔴")
    val packNames = mapOf("glasses" to "แว่นดำ", "crown" to "มงกุฎ", "cat" to "แมวเหมียว", "blush" to "แก้มแดง", "hearts" to "หัวใจ", "clown" to "จมูกตัวตลก")

    fun packOn(): String? = Studio.layers.firstOrNull { it.preset.startsWith("pack:") }?.preset?.removePrefix("pack:")

    /** put a pack on the face (replaces the previous pack; null = take it off) */
    fun facePack(id: String?, who: String = "all") {
        Studio.layers.removeAll { it.preset.startsWith("pack:") }
        val parts = id?.let { packs[it] }
        if (parts != null) for (p in parts) {
            val l = if (p.emoji != null) Layer(Studio.newId(), LType.Sticker).apply { text = p.emoji; aspect = 1f }
            else Layer(Studio.newId(), LType.Shape).apply { shape = p.shape ?: "circle"; color = p.color; color2 = p.color; grad = false; aspect = 1f }
            l.preset = "pack:$id"; l.name = (p.emoji ?: "●") + " " + id
            l.w = .2f; l.rot = p.rot; l.opacity = p.opacity; l.anim = p.anim
            l.attach.kind = "face"; l.attach.point = p.point; l.attach.size = p.size; l.attach.ox = p.ox; l.attach.oy = p.oy; l.attach.who = who
            Studio.layers += l
        }
        Studio.commit("pack")
    }

    // ───────────────────────────── camera layouts ─────────────────────────────

    val layouts = listOf("fill", "fit", "pip", "half", "circle", "tilt")
    /** picture height / width (set by the pump from the GPU canvas) */
    @Volatile var canvasAspect = 4f / 3f

    fun cameraLayout(kind: String) {
        val c = Studio.camera ?: return
        c.cropL = 0f; c.cropT = 0f; c.cropR = 0f; c.cropB = 0f; c.rot = 0f; c.mask = "rect"; c.radius = 0f; c.border = 0f; c.shadow = 0f
        when (kind) {
            "fill" -> { c.x = .5f; c.y = .5f; c.w = 1f }
            "fit" -> { c.x = .5f; c.y = .5f; c.w = .86f; c.radius = .06f; c.shadow = .6f }
            "pip" -> { c.w = .34f; c.x = .78f; c.y = .78f; c.radius = .12f; c.border = .02f; c.shadow = .8f }
            "half" -> { c.w = 1f; c.x = .5f; c.y = .5f; c.cropL = .25f; c.cropR = .25f; c.x = .25f; c.w = .5f }
            "circle" -> {
                // the GPU crops a circle to the middle square by itself (Geometry.cropOf), whatever the picture shape
                val a = canvasAspect
                c.w = if (a > 1f) .62f else .42f; c.x = .5f; c.y = .45f; c.mask = "circle"; c.border = .02f; c.shadow = .7f
            }
            "tilt" -> { c.w = .7f; c.x = .5f; c.y = .5f; c.rot = -6f; c.radius = .04f; c.border = .025f; c.shadow = .9f }
        }
        Studio.commit("layout")
    }

    // ───────────────────────────── templates (phase 10) ─────────────────────────────

    val templates = listOf("vlog", "live", "cute", "podcast", "vtuber", "party")
    val templateNames = mapOf("vlog" to "วล็อก", "live" to "ไลฟ์สด", "cute" to "น่ารัก", "podcast" to "พอดแคสต์", "vtuber" to "VTuber", "party" to "ปาร์ตี้")
    val templateIcons = mapOf("vlog" to "🎬", "live" to "🔴", "cute" to "🌸", "podcast" to "🎙️", "vtuber" to "🤖", "party" to "🎉")

    /** a ready-made scene (replaces the current one; one undo step brings the old one back) */
    fun template(id: String) {
        Studio.layers.removeAll { it.type != LType.Camera }
        val c = Studio.camera ?: return
        c.visible = true; c.look = "none"; c.opacity = 1f
        fun lay(t: LType, f: Layer.() -> Unit) { Studio.layers += Layer(Studio.newId(), t).apply(f).also { if (it.name.isEmpty()) it.name = Studio.defaultName(it) } }
        when (id) {
            "vlog" -> {
                cameraLayout("fill"); c.look = "film"
                lay(LType.Text) { text = "VLOG"; style = 3; w = .22f; x = .16f; y = .1f; aspect = .3f; anim = "pop" }
                lay(LType.Frame) { preset = "date"; color = Color.WHITE; w = 1f }
            }
            "live" -> {
                cameraLayout("fill"); c.look = "vivid"
                lay(LType.Text) { text = "● LIVE"; style = 2; color = 0xFFFF3B5C.toInt(); w = .2f; x = .14f; y = .09f; aspect = .3f; anim = "blink"; animSpeed = .5f }
                lay(LType.Frame) { preset = "neon"; color = 0xFFFF6B8B.toInt(); w = 1f }
            }
            "cute" -> {
                cameraLayout("circle"); c.look = "warm"; c.border = .03f; c.borderColor = 0xFFFFC2DE.toInt()
                lay(LType.Shape) { shape = "rect"; color = 0xFFFFE3EF.toInt(); color2 = 0xFFD6BFFF.toInt(); w = 1.6f; aspect = 1f; x = .5f; y = .5f }
                lay(LType.Sticker) { text = "🌸"; w = .16f; x = .2f; y = .2f; anim = "float" }
                lay(LType.Sticker) { text = "✨"; w = .12f; x = .82f; y = .25f; anim = "pulse" }
                lay(LType.Sticker) { text = "💕"; w = .14f; x = .8f; y = .78f; anim = "heartbeat" }
            }
            "podcast" -> {
                cameraLayout("fit"); c.look = "teal"
                lay(LType.Shape) { shape = "round"; color = 0xFF111214.toInt(); color2 = 0xFF2A1B5C.toInt(); w = 1.6f; aspect = 1f }
                lay(LType.Text) { text = "PODCAST · EP.1"; style = 2; w = .42f; x = .5f; y = .9f; aspect = .2f }
            }
            "vtuber" -> {
                c.visible = false
                lay(LType.Shape) { shape = "rect"; color = 0xFFBAD4FF.toInt(); color2 = 0xFFFFC2DE.toInt(); w = 1.6f; aspect = 1f }
                lay(LType.Avatar) { preset = "cat"; val (c1, c2) = FaceArt.avatarColors["cat"]!!; color = c1; color2 = c2; w = .5f; aspect = 1f; x = .5f; y = .56f; attach.who = "0" }
                lay(LType.Text) { text = "Prism VTuber"; style = 3; w = .36f; x = .5f; y = .1f; aspect = .2f }
            }
            "party" -> {
                cameraLayout("fill"); c.look = "prism"
                lay(LType.FacePaint) { preset = "stars"; color = FaceArt.paintColors["stars"] ?: Color.YELLOW; w = 1f; attach.who = "all" }
                lay(LType.HandFx) { preset = "trail"; color = 0xFFFFD98A.toInt(); w = 1f; attach.who = "any" }
                lay(LType.Sticker) { text = "🎉"; w = .16f; x = .14f; y = .16f; anim = "swing" }
                lay(LType.Sticker) { text = "🥳"; w = .2f; attach.kind = "face"; attach.point = "top"; attach.size = .55f; attach.oy = .05f; attach.who = "all" }
            }
        }
        // backgrounds go right behind the camera
        val bgs = Studio.layers.filter { it.type == LType.Shape && it.w > 1.2f }
        for (b in bgs) { Studio.layers.remove(b); Studio.layers.add(0, b) }
        Studio.selection.clear()
        Studio.commit("template")
    }

    /** everything off: only the camera, full picture, no look */
    fun clearAll() {
        Studio.layers.removeAll { it.type != LType.Camera }
        cameraLayout("fill")
        Studio.camera?.let { it.look = "none"; it.bright = 0f; it.contrast = 0f; it.sat = 0f; it.hue = 0f; it.opacity = 1f; it.keyOn = false; it.anim = ""; it.flipX = false; it.flipY = false }
        Studio.selection.clear()
        Studio.commit("clear")
    }

    /** anything on top of the plain camera? */
    fun isEmpty(): Boolean = Studio.layers.all { it.type == LType.Camera }
}
