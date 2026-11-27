package com.prism.cam.ui

import com.prism.cam.ui.kit.hScrollFade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.prism.cam.ai.Vision
import com.prism.cam.effects.Looks
import com.prism.cam.effects.Motion
import com.prism.cam.i18n.T
import com.prism.cam.studio.Content
import com.prism.cam.studio.LType
import com.prism.cam.studio.Layer
import com.prism.cam.studio.Make
import com.prism.cam.studio.Studio
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismLabel
import com.prism.cam.ui.kit.PrismMiniButton
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.PrismSlider
import com.prism.cam.ui.kit.PrismSwitchRow
import com.prism.cam.ui.kit.rememberHaptic
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess
import kotlin.math.roundToInt

private fun pct(v: Float) = "${(v * 100).roundToInt()}%"
private fun deg(v: Float) = "${v.roundToInt()}°"
private fun signed(v: Float) = (if (v > 0) "+" else "") + (v * 100).roundToInt()

/**
 * Settings of one layer (desktop "Properties"): position & shape, colour & green screen, stick to face / hand,
 * motion, and the layer's own content (text, colours, video playback, pen). Every change is live; one undo step per
 * slider drag.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PropsSheet(layer: Layer?, onClose: () -> Unit, onEditText: (Layer) -> Unit, vision: Vision?) {
    val l = layer?.let { id -> Studio.layers.firstOrNull { it.id == id.id } }
    var tab by rememberSaveable { mutableIntStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(TestTabs.props) { if (TestTabs.props >= 0) { tab = TestTabs.props; TestTabs.props = -1 } }
    PrismSheet(l != null, onClose, l?.let { it.name.ifBlank { typeName(it.type) } } ?: "", tall = true, actions = {
        if (l != null) {
            PrismMiniButton("copy", { Studio.duplicate(l.id) })
            if (l.type != LType.Camera || Studio.layers.count { it.type == LType.Camera } > 1) PrismMiniButton("trash", { Studio.remove(l.id); onClose() })
        }
    }, header = {
        if (l != null) com.prism.cam.ui.kit.PrismTabRow(
            listOf(T("ตำแหน่ง") to "sliders", T("สี") to "drop", T("ติดหน้า/มือ") to "face", T("เคลื่อนไหว") to "anim", T("เนื้อหา") to "edit"),
            tab, { tab = it },
        )
    }) {
        if (l == null) return@PrismSheet
        val done: () -> Unit = { Studio.commit("props") }
        fun ch(f: () -> Unit) { f(); Studio.touch() }
        when (tab) {
            0 -> {
                if (!com.prism.cam.studio.Geometry.isFullCanvas(l.type)) {
                    PrismSlider(T("ขนาด"), l.w, .03f..1.6f, { v -> ch { l.w = v } }, onDone = done, format = ::pct)
                    PrismSlider(T("หมุน"), l.rot, -180f..180f, { v -> ch { l.rot = snapAngle(v) } }, onDone = done, format = ::deg, center = true)
                    PrismSlider(T("ซ้าย-ขวา"), l.x, -.1f..1.1f, { v -> ch { l.x = v } }, onDone = done, format = ::pct)
                    PrismSlider(T("บน-ล่าง"), l.y, -.1f..1.1f, { v -> ch { l.y = v } }, onDone = done, format = ::pct)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrismChip(T("กลางภาพ"), false, { l.x = .5f; l.y = .5f; done() }, icon = "center")
                        PrismChip(T("กลับซ้ายขวา"), l.flipX, { l.flipX = !l.flipX; done() }, icon = "flip")
                        PrismChip(T("กลับบนล่าง"), l.flipY, { l.flipY = !l.flipY; done() }, icon = "flip")
                        PrismChip(T("หมุนตรง"), false, { l.rot = 0f; done() })
                    }
                }
                PrismSlider(T("ความทึบ"), l.opacity, 0f..1f, { v -> ch { l.opacity = v } }, onDone = done, format = ::pct)
                if (!com.prism.cam.studio.Geometry.isFullCanvas(l.type)) {
                    PrismLabel(T("รูปร่าง"))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        PrismChip(T("สี่เหลี่ยม"), l.mask == "rect", { l.mask = "rect"; done() })
                        PrismChip(T("วงกลม"), l.mask == "circle", { l.mask = "circle"; done() })
                    }
                    if (l.mask == "rect") PrismSlider(T("มุมมน"), l.radius, 0f..0.5f, { v -> ch { l.radius = v } }, onDone = done, format = ::pct)
                    PrismSlider(T("ขอบ"), l.border, 0f..0.08f, { v -> ch { l.border = v } }, onDone = done, format = { pct(it / .08f) })
                    if (l.border > 0f) ColorDots(l.borderColor) { l.borderColor = it; done() }
                    PrismSlider(T("เงา"), l.shadow, 0f..1f, { v -> ch { l.shadow = v } }, onDone = done, format = ::pct)
                    PrismLabel(T("ตัด (ครอป)"))
                    PrismSlider(T("ซ้าย"), l.cropL, 0f..0.45f, { v -> ch { l.cropL = v } }, onDone = done, format = ::pct)
                    PrismSlider(T("ขวา"), l.cropR, 0f..0.45f, { v -> ch { l.cropR = v } }, onDone = done, format = ::pct)
                    PrismSlider(T("บน"), l.cropT, 0f..0.45f, { v -> ch { l.cropT = v } }, onDone = done, format = ::pct)
                    PrismSlider(T("ล่าง"), l.cropB, 0f..0.45f, { v -> ch { l.cropB = v } }, onDone = done, format = ::pct)
                }
                PrismLabel(T("ผสมภาพ"))
                Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("normal" to T("ปกติ"), "screen" to T("สว่างขึ้น"), "add" to T("เรืองแสง"), "multiply" to T("มืดลง"))
                        .forEach { (k, s) -> PrismChip(s, l.blend == k, { l.blend = k; done() }) }
                }
            }
            1 -> {
                PrismLabel(T("ฟิลเตอร์"))
                Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (lk in Looks.all) PrismChip(T(lk.th), l.look == lk.id, { l.look = lk.id; done() })
                }
                PrismSlider(T("ความสว่าง"), l.bright, -1f..1f, { v -> ch { l.bright = v } }, onDone = done, format = ::signed, center = true)
                PrismSlider(T("คอนทราสต์"), l.contrast, -1f..1f, { v -> ch { l.contrast = v } }, onDone = done, format = ::signed, center = true)
                PrismSlider(T("ความสด"), l.sat, -1f..1f, { v -> ch { l.sat = v } }, onDone = done, format = ::signed, center = true)
                PrismSlider(T("โทนสี"), l.hue, -1f..1f, { v -> ch { l.hue = v } }, onDone = done, format = { deg(it * 180f) }, center = true)
                PrismSwitchRow(T("ตัดฉากเขียว"), T("ลบสีพื้นหลังออก (กรีนสกรีน)"), l.keyOn, { l.keyOn = it; done() }, icon = "key")
                if (l.keyOn) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        listOf(0xFF00D000.toInt() to T("เขียว"), 0xFF0050FF.toInt() to T("น้ำเงิน"), 0xFFFFFFFF.toInt() to T("ขาว"), 0xFF000000.toInt() to T("ดำ"))
                            .forEach { (col, s) -> PrismChip(s, l.keyColor == col, { l.keyColor = col; done() }) }
                    }
                    PrismSlider(T("ความไว"), l.keyTol, .05f..1f, { v -> ch { l.keyTol = v } }, onDone = done, format = ::pct)
                }
                PrismPill(T("คืนค่าสี"), { l.look = "none"; l.bright = 0f; l.contrast = 0f; l.sat = 0f; l.hue = 0f; l.keyOn = false; done() }, icon = "refresh")
            }
            2 -> AttachTab(l, vision, done)
            3 -> {
                val names = mapOf("" to T("ไม่มี"), "pop" to T("เด้งขึ้น"), "float" to T("ลอย"), "bounce" to T("กระโดด"), "spin" to T("หมุน"), "pulse" to T("เต้นตุบ"),
                    "shake" to T("สั่น"), "swing" to T("แกว่ง"), "slideL" to T("เลื่อนจากซ้าย"), "slideR" to T("เลื่อนจากขวา"), "blink" to T("กะพริบ"), "heartbeat" to T("หัวใจเต้น"))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (k in listOf("") + Motion.ids) PrismChip(names[k] ?: k, l.anim == k, { l.anim = k; done() })
                }
                if (l.anim.isNotEmpty()) PrismSlider(T("ความเร็ว"), l.animSpeed, .25f..3f, { v -> ch { l.animSpeed = v } }, onDone = done, format = { "×" + "%.2f".format(it) })
                if (!com.prism.cam.studio.Geometry.isFullCanvas(l.type)) KeyframePanel(l, done)
            }
            else -> ContentTab(l, onEditText, done)
        }
    }
}

/** keyframes: put the layer where you want it, "remember here at t", move, remember again… it then plays in a loop */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun KeyframePanel(l: Layer, done: () -> Unit) {
    var t by remember(l.id) { androidx.compose.runtime.mutableFloatStateOf(0f) }
    PrismLabel(T("คีย์เฟรม (ขยับเองตามเวลา)"))
    PrismSlider(T("ความยาวรอบ"), l.keyLen, 1f..20f, { v -> l.keyLen = v; Studio.touch() }, onDone = done, format = { "%.1f s".format(it) })
    PrismSlider(T("เวลา"), t, 0f..l.keyLen, { t = it }, format = { "%.1f s".format(it) })
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrismPill(T("จำตำแหน่งตอนนี้"), {
            l.keys.removeAll { kotlin.math.abs(it[0] - t) < .05f }
            l.keys += floatArrayOf(t, l.x, l.y, l.w, l.rot, 1f)
            t = (t + l.keyLen / 4f).coerceAtMost(l.keyLen)
            done()
        }, kind = PillKind.Primary, icon = "keyadd")
        if (l.keys.isNotEmpty()) PrismPill(T("ล้างคีย์"), { l.keys.clear(); done() }, kind = PillKind.Warn, icon = "trash")
    }
    if (l.keys.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (k in l.keys.sortedBy { it[0] }) PrismChip("◆ " + "%.1f s".format(k[0]) + "  ✕", false, { l.keys.remove(k); done() })
    }
    PrismLabel(if (l.keys.size >= 2) T("กำลังเล่นวน — ลากเลเยอร์ไปที่ใหม่แล้วกดจำ เพื่อเพิ่มท่า") else T("จำอย่างน้อย 2 ตำแหน่ง แล้วเลเยอร์จะเคลื่อนเองระหว่างจุด"))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AttachTab(l: Layer, vision: Vision?, done: () -> Unit) {
    val a = l.attach
    fun ch(f: () -> Unit) { f(); Studio.touch() }
    Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PrismChip(T("ไม่ติด"), a.kind == "", { a.kind = ""; done() })
        PrismChip(T("ติดหน้า"), a.kind == "face", {
            if (a.kind != "face") { a.kind = "face"; a.point = "eyes"; a.who = "all"; a.size = (l.w / .3f).coerceIn(.3f, 2f); a.ox = 0f; a.oy = 0f }
            done()
        }, icon = "face")
        PrismChip(T("ติดมือ"), a.kind == "hand", {
            if (a.kind != "hand") { a.kind = "hand"; a.point = "palm"; a.who = "any"; a.size = 1f; a.ox = 0f; a.oy = 0f }
            done()
        }, icon = "hand")
        PrismChip(T("ระหว่างสองมือ"), a.kind == "hands2", {
            if (a.kind != "hands2") { a.kind = "hands2"; a.point = "palm"; a.who = "any"; a.size = .8f; a.ox = 0f; a.oy = 0f; a.followRot = true; a.followScale = true; a.only = "" }
            done()
        }, icon = "hand")
    }
    if (a.kind.isEmpty()) {
        PrismLabel(T("เลเยอร์นี้จะขยับตามหน้าหรือมือที่กล้องเห็น เหมือนในคอม"))
        return
    }
    if (a.kind == "hands2") PrismText(T("วางไว้ตรงกลางระหว่างสองมือ หมุนตามเส้นระหว่างมือ และยืด/หดตามระยะห่าง — ต้องเห็นทั้งสองมือ"), style = PrismThemeAccess.type.caption, color = PrismThemeAccess.colors.ink2)
    if (vision != null && ((a.kind == "face" && !vision.faceOn) || (a.kind != "face" && !vision.handsOn))) {
        PrismPill(T("การจับถูกปิดอยู่ — แตะเพื่อเปิด"), { if (a.kind == "face") vision.useFace(true) else vision.useHands(true) }, kind = PillKind.Warn, icon = "warn")
    }
    PrismLabel(T("ติดที่"))
    val facePts = listOf("eyes" to T("ตา"), "eyeL" to T("ตาซ้าย"), "eyeR" to T("ตาขวา"), "nose" to T("จมูก"), "mouth" to T("ปาก"), "forehead" to T("หน้าผาก"),
        "top" to T("บนหัว"), "chin" to T("คาง"), "cheekL" to T("แก้มซ้าย"), "cheekR" to T("แก้มขวา"), "earL" to T("หูซ้าย"), "earR" to T("หูขวา"), "face" to T("กลางหน้า"))
    val handPts = listOf("palm" to T("ฝ่ามือ"), "index" to T("ปลายนิ้วชี้"), "thumb" to T("นิ้วโป้ง"), "middle" to T("นิ้วกลาง"), "ring" to T("นิ้วนาง"), "pinky" to T("นิ้วก้อย"), "wrist" to T("ข้อมือ"))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, s) in if (a.kind == "face") facePts else handPts) PrismChip(s, a.point == k, { a.point = k; done() })
    }
    if (a.kind != "hands2") PrismLabel(if (a.kind == "face") T("หน้าไหน") else T("มือไหน"))
    if (a.kind != "hands2") Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (a.kind == "face") {
            PrismChip(T("หน้าแรก"), a.who == "all" || a.who == "0", { a.who = "0"; done() })
            for (i in 1..3) PrismChip(T("หน้าที่") + " ${i + 1}", a.who == "$i", { a.who = "$i"; done() })
        } else {
            PrismChip(T("มือไหนก็ได้"), a.who == "any", { a.who = "any"; done() })
            PrismChip(T("มือซ้าย"), a.who == "left", { a.who = "left"; done() })
            PrismChip(T("มือขวา"), a.who == "right", { a.who = "right"; done() })
        }
    }
    PrismSlider(if (a.kind == "hands2") T("ขนาดเทียบระยะห่างมือ") else T("ขนาดเทียบหน้า/มือ"), a.size, .05f..3f, { v -> ch { a.size = v } }, onDone = done, format = { "×" + "%.2f".format(it) })
    PrismSlider(T("เลื่อนซ้าย-ขวา"), a.ox, -1.5f..1.5f, { v -> ch { a.ox = v } }, onDone = done, format = ::signed, center = true)
    PrismSlider(T("เลื่อนบน-ล่าง"), a.oy, -1.5f..1.5f, { v -> ch { a.oy = v } }, onDone = done, format = ::signed, center = true)
    PrismSwitchRow(T("หมุนตาม"), null, a.followRot, { a.followRot = it; done() })
    PrismSwitchRow(if (a.kind == "hands2") T("ยืดตามระยะห่างมือ") else T("ย่อ/ขยายตาม"), null, a.followScale, { a.followScale = it; done() })
    if (a.kind == "face") PrismSwitchRow(T("บีบตามการหันหน้า"), null, a.followTurn, { a.followTurn = it; done() })
    PrismLabel(T("เมื่อหาไม่เจอ"))
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("hide" to T("ซ่อน"), "stay" to T("ค้างไว้"), "fade" to T("ค่อยๆ จาง")).forEach { (k, s) -> PrismChip(s, a.lost == k, { a.lost = k; done() }) }
    }
    PrismLabel(T("แสดงเฉพาะตอน"))
    val onlys = if (a.kind == "face") listOf("" to T("ตลอด"), "smile" to T("ยิ้ม"), "mouth" to T("อ้าปาก"), "brow" to T("เลิกคิ้ว"), "kiss" to T("จุ๊บ"), "blink" to T("หลับตา"), "winkL" to T("ขยิบตาซ้าย"), "winkR" to T("ขยิบตาขวา"))
    else if (a.kind == "hands2") listOf("" to T("ตลอด"), "pair:heart" to T("มือหัวใจสองมือ 🫶"), "pair:pray" to T("พนมมือ 🙏")) + Vision.gestures.map { it to T("สองมือ") + " " + gestureName(it) }
    else listOf("" to T("ตลอด")) + Vision.gestures.map { it to gestureName(it) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((k, s) in onlys) PrismChip(s, a.only == k, { a.only = k; done() })
    }
}

fun gestureName(g: String) = when (g) {
    "open" -> T("แบมือ ✋"); "fist" -> T("กำมือ ✊"); "point" -> T("ชี้นิ้ว ☝️"); "peace" -> T("ชูสองนิ้ว ✌️")
    "thumbUp" -> T("ยกนิ้วโป้ง 👍"); "thumbDown" -> T("คว่ำนิ้วโป้ง 👎"); "love" -> T("มือรัก 🤟"); else -> g
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ContentTab(l: Layer, onEditText: (Layer) -> Unit, done: () -> Unit) {
    fun ch(f: () -> Unit) { f(); Studio.touch() }
    fun content(f: () -> Unit) { f(); l.touchContent(); done() }
    when (l.type) {
        LType.Text -> {
            PrismPill(T("แก้ข้อความ"), { onEditText(l) }, kind = PillKind.Primary, icon = "edit")
            ColorDots(l.color) { c -> content { l.color = c } }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf(T("เงา"), T("ขอบ"), T("ป้าย"), "Prism").forEachIndexed { i, s -> PrismChip(s, l.style == i, { content { l.style = i } }) }
            }
        }
        LType.Sticker -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (e in Content.stickers) PrismChip(e, l.text == e, { content { l.text = e; l.name = e } })
        }
        LType.Shape -> {
            Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (s in Content.shapes) PrismChip(s, l.shape == s, { content { l.shape = s } })
            }
            PrismSwitchRow(T("ไล่สี"), null, l.grad, { content { l.grad = it } })
            PrismLabel(T("สี"))
            ColorDots(l.color) { c -> content { l.color = c } }
            if (l.grad) ColorDots(l.color2) { c -> content { l.color2 = c } }
        }
        LType.Frame -> {
            Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (f in Content.frames) PrismChip(f, l.preset == f, { content { l.preset = f } })
            }
            ColorDots(l.color) { c -> content { l.color = c } }
        }
        LType.Video -> {
            PrismSwitchRow(T("เล่นวน"), null, l.loop, { l.loop = it; done() }, icon = "loop")
            PrismSwitchRow(T("ปิดเสียง"), null, l.muted, { l.muted = it; done() }, icon = "music")
            PrismSlider(T("ความเร็ว"), l.speed, .25f..2f, { v -> ch { l.speed = v } }, onDone = done, format = { "×" + "%.2f".format(it) })
        }
        LType.Drawing -> {
            PrismLabel(T("ปากกา"))
            ColorDots(Make.penColor) { Make.penColor = it }
            PrismSlider(T("ความหนา"), Make.penWidth, .002f..0.04f, { Make.penWidth = it }, format = { pct(it / .04f) })
            PrismSwitchRow(T("เรืองแสง"), null, Make.penGlow, { Make.penGlow = it })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PrismPill(T("ลบเส้นล่าสุด"), { if (l.strokes.isNotEmpty()) content { l.strokes.removeAt(l.strokes.size - 1) } }, icon = "undo")
                PrismPill(T("ลบทั้งหมด"), { content { l.strokes.clear() } }, kind = PillKind.Warn, icon = "trash")
            }
        }
        LType.Camera -> {
            PrismLabel(T("วางกล้อง"))
            val names = mapOf("fill" to T("เต็มจอ"), "fit" to T("พอดีมีขอบ"), "pip" to T("จอเล็กมุม"), "half" to T("ครึ่งจอ"), "circle" to T("วงกลม"), "tilt" to T("เอียงเท่ๆ"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                for (k in Make.layouts) PrismChip(names[k] ?: k, false, { Make.cameraLayout(k) })
            }
        }
        else -> PrismLabel(T("ไม่มีอะไรให้ตั้งค่าเพิ่ม"))
    }
}

@Composable
fun ColorDots(selected: Int, onPick: (Int) -> Unit) {
    val haptic = rememberHaptic()
    val cols = Content.textColors + listOf(0xFF00D000.toInt(), 0xFF6CC7FF.toInt(), 0xFF1FB5A6.toInt())
    Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
        for (col in cols) {
            val on = col == selected
            Box(
                Modifier.size(if (on) 32.dp else 26.dp).clip(CircleShape).background(Color(col))
                    .border(if (on) 3.dp else 1.dp, if (on) Prism.grad else SolidColor(PrismThemeAccess.colors.line), CircleShape)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); onPick(col) },
            )
        }
    }
}

/** test hook (act fxtab:<i> / propstab:<i>): open a sheet on a given tab */
object TestTabs {
    var fx by androidx.compose.runtime.mutableIntStateOf(-1)
    var props by androidx.compose.runtime.mutableIntStateOf(-1)
    /** test act `fold:1|0` — folds / opens every open sheet (grip) */
    var fold by androidx.compose.runtime.mutableIntStateOf(-1)
}
