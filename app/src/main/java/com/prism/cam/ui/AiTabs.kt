package com.prism.cam.ui

import com.prism.cam.ui.kit.hScrollFade
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.ai.Vision
import com.prism.cam.studio.FaceArt
import com.prism.cam.studio.Gestures
import com.prism.cam.studio.LType
import com.prism.cam.studio.Make
import com.prism.cam.studio.Studio
import com.prism.cam.studio.Tracking
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismLabel
import com.prism.cam.ui.kit.PrismMiniButton
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismSlider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.foundation.layout.fillMaxWidth
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismSegmented
import com.prism.cam.ui.kit.PrismSwitchRow
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.rememberHaptic
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess

/** The AI tabs of the ✦ sheet: background, face (stickers, paint, characters), hands, tracking settings + commands. */
fun aiTabs(v: Vision, onPickPhoto: () -> Unit): List<Pair<String, @Composable () -> Unit>> = listOf(
    T("พื้นหลัง") to { BackgroundTab(v, onPickPhoto) },
    T("หน้า") to { FaceTab(v) },
    T("มือ") to { HandTab(v) },
    T("ตั้งค่าการจับ") to { TrackTab(v) },
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BackgroundTab(v: Vision, onPickPhoto: () -> Unit) {
    val c = PrismThemeAccess.colors
    val haptic = rememberHaptic()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismChip(T("ไม่มี"), v.bg == 0, { v.useBg(0) })
            PrismChip(T("เบลอพื้นหลัง"), v.bg == 1, { v.useBg(1) }, icon = "drop")
            PrismChip(T("เปลี่ยนพื้นหลัง"), v.bg == 2, { v.useBg(2) }, icon = "image")
        }
        if (v.bg == 1 || v.portrait) {
            PrismText(T("ความเบลอ"), style = PrismThemeAccess.type.caption, color = c.ink3)
            val level = when { v.blur < .4f -> 0; v.blur < .8f -> 1; else -> 2 }
            PrismSegmented(
                listOf(T("น้อย") to null, T("กลาง") to null, T("มาก") to null), level,
                { v.useBlur(listOf(.25f, .65f, 1f)[it]) }, Modifier.fillMaxWidth(),
            )
        }
        if (v.bg == 2) {
            Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                for (id in Vision.backgrounds) {
                    val on = v.bgId == id
                    val cols = when (id) {
                        "night" -> listOf(Color(0xFF0B1030), Color(0xFF6B2E7A))
                        "sunset" -> listOf(Color(0xFFFFB27A), Color(0xFF7A5CFF))
                        "mint" -> listOf(Color(0xFFB3F2E6), Color(0xFF6CC7FF))
                        "studio" -> listOf(Color(0xFF3A3F47), Color(0xFF111214))
                        else -> listOf(Color(0xFFBAD4FF), Color(0xFFFFC2DE))
                    }
                    Box(
                        Modifier.size(48.dp).clip(CircleShape).background(Brush.linearGradient(cols))
                            .border(if (on) 3.dp else 1.dp, if (on) Prism.grad else SolidColor(c.line), CircleShape)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); v.useBgId(id) },
                    )
                }
                Box(
                    Modifier.size(48.dp).clip(CircleShape).background(c.hover)
                        .border(if (v.bgId == "photo") 3.dp else 1.dp, if (v.bgId == "photo") Prism.grad else SolidColor(c.line), CircleShape)
                        .clickable(remember { MutableInteractionSource() }, indication = null) { onPickPhoto() },
                    contentAlignment = Alignment.Center,
                ) { PrismIcon("image", size = 20.dp, tint = c.ink2) }
            }
        }
        v.status?.let { PrismText(it, style = PrismThemeAccess.type.caption, color = Prism.danger) }
        PrismText(T("AI ทำงานในเครื่องนี้ทั้งหมด ไม่ส่งภาพไปที่ไหน"), style = PrismThemeAccess.type.caption, color = c.ink3)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FaceTab(v: Vision) {
    val c = PrismThemeAccess.colors
    val haptic = rememberHaptic()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TrackOff(v, face = true)
        PrismLabel(T("ของติดหน้า"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = Make.packOn()
            Tile("🚫", T("ไม่มี"), on == null) { Make.facePack(null) }
            for (id in Make.packs.keys) {
                Tile(Make.packIcons[id] ?: "✨", T(Make.packNames[id] ?: id), on == id) {
                    haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.facePack(id)
                }
            }
        }
        PrismLabel(T("ระบายหน้า"))
        Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = Make.facePaintOn()
            PrismChip(T("ไม่มี"), on == null, { Make.facePaint(null) })
            for (id in FaceArt.paints) PrismChip(T(FaceArt.paintNames[id] ?: id), on == id, { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.facePaint(id) })
        }
        PrismLabel(T("ตัวละคร VTuber (ขยับตามหน้าคุณ)"))
        Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = Make.avatarOn()
            PrismChip(T("ไม่มี"), on == null, { Studio.layers.removeAll { it.type == LType.Avatar }; Studio.commit("avatar") })
            for (id in FaceArt.avatars) PrismChip(T(FaceArt.avatarNames[id] ?: id), on == id, { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.avatar(id) })
        }
        PrismLabel(T("เอฟเฟกต์หน้า"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val on = Make.fxOn(LType.FaceFx)
            PrismChip(T("ไม่มี"), on == null, { Make.faceFx(null) })
            PrismChip(T("เส้นเรืองแสง"), on == "mesh", { Make.faceFx("mesh") })
            PrismChip(T("ตาเรืองแสง"), on == "eyes", { Make.faceFx("eyes") })
        }
        PrismText(T("ทุกอย่างเป็นเลเยอร์ — เปิดแผงเลเยอร์เพื่อย้าย ย่อ เปลี่ยนสี หรือติดที่จุดอื่นของหน้า"), style = PrismThemeAccess.type.caption, color = c.ink3)
        v.status?.let { PrismText(it, style = PrismThemeAccess.type.caption, color = Prism.danger) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HandTab(v: Vision) {
    val c = PrismThemeAccess.colors
    val haptic = rememberHaptic()
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        TrackOff(v, face = false)
        PrismLabel(T("เอฟเฟกต์มือ (เลือกได้หลายอย่าง)"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismChip(T("ไม่มี"), Make.fxOn(LType.HandFx) == null, { Make.handFx(null) })
            PrismChip(T("มือนีออน"), Make.handFxOn("neon"), { Make.handFx("neon") }, icon = "hand")
            PrismChip(T("หางแสงปลายนิ้ว"), Make.handFxOn("trail"), { Make.handFx("trail") }, icon = "sparkle")
            PrismChip(T("ลูกพลังในฝ่ามือ"), Make.handFxOn("orb"), { Make.handFx("orb") }, icon = "sparkle")
            PrismChip(T("วาดกลางอากาศ"), Make.handFxOn("draw"), { Make.handFx("draw") }, icon = "edit")
        }
        if (Make.handFxOn("orb")) PrismText(T("แบมือ ✋ = ลูกพลังโผล่ · กำมือ ✊ = ระเบิด"), style = PrismThemeAccess.type.caption, color = c.ink2)
        PrismLabel(T("สองมือ"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("⚡", T("สายฟ้า"), Make.handFxOn("lightning")) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.handFx("lightning") }
            Tile("🔮", T("ลูกพลัง"), Make.handFxOn("ball")) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.handFx("ball") }
            for (id in Make.twoHandIds) Tile(Make.twoHandIcons[id] ?: "✨", T(Make.twoHandNames[id] ?: id), Make.twoHandOn(id)) {
                haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.twoHand(id)
            }
        }
        PrismText(T("ยกสองมือให้กล้องเห็น — ของจะอยู่ตรงกลาง หมุนและยืดตามมือ · ทำมือหัวใจ 🫶 หรือพนมมือ 🙏 ได้ด้วย"), style = PrismThemeAccess.type.caption, color = c.ink2)
        if (Make.handFxOn("draw")) {
            PrismText(T("ชี้นิ้วชี้ ☝️ = วาด · แบมือ = หยุด · กำมือค้าง 1 วิ = ลบทั้งหมด"), style = PrismThemeAccess.type.caption, color = c.ink2)
            ColorDots(Make.penColor) { col -> Make.penColor = col; Studio.layers.firstOrNull { it.type == LType.HandFx && it.preset == "draw" }?.let { it.color = col; Studio.touch() } }
            PrismPill(T("ลบภาพวาด"), { Gestures.clearDrawing() }, kind = PillKind.Warn, icon = "trash")
        }
        PrismSwitchRow(T("ชูสองนิ้ว ✌️ เพื่อถ่าย"), T("เห็นมือชูสองนิ้ว จะนับ 3 วินาทีแล้วถ่ายเอง"), v.peace, { v.usePeace(it) }, icon = "hand")
        PrismText(T("ติดรูปหรือสติกเกอร์ไว้ที่มือ หรือระหว่างสองมือได้: เลือกเลเยอร์ → ตั้งค่า → ติดหน้า/มือ"), style = PrismThemeAccess.type.caption, color = c.ink3)
        v.status?.let { PrismText(it, style = PrismThemeAccess.type.caption, color = Prism.danger) }
    }
}

/** warning + one-tap fix when the user switched tracking off */
@Composable
private fun TrackOff(v: Vision, face: Boolean) {
    if (face && !v.faceOn) PrismPill(T("การจับหน้าถูกปิดอยู่ — แตะเพื่อเปิด"), { v.useFace(true) }, kind = PillKind.Warn, icon = "warn")
    if (!face && !v.handsOn) PrismPill(T("การจับมือถูกปิดอยู่ — แตะเพื่อเปิด"), { v.useHands(true) }, kind = PillKind.Warn, icon = "warn")
}

fun triggerName(t: String): String = when {
    t.startsWith("pose:") -> T("ท่าของฉัน") + " " + t.removePrefix("pose:")
    t == "zone:mouth" -> T("มือแตะปาก"); t == "zone:forehead" -> T("มือแตะหน้าผาก"); t == "zone:cheek" -> T("มือแตะแก้ม"); t == "zone:ear" -> T("มือแตะหู")
    t == "smile" -> T("ยิ้ม"); t == "mouth" -> T("อ้าปาก"); t == "brow" -> T("เลิกคิ้ว"); t == "kiss" -> T("จุ๊บ"); t == "puff" -> T("ป่องแก้ม")
    t == "pair:heart" -> T("มือหัวใจสองมือ 🫶"); t == "pair:pray" -> T("พนมมือ 🙏")
    t == "blink" -> T("หลับตา"); t == "winkL" -> T("ขยิบตาซ้าย"); t == "winkR" -> T("ขยิบตาขวา")
    else -> gestureName(t)
}

fun actionName(a: String): String = when (a) {
    "photo" -> T("ถ่ายรูป"); "video" -> T("เริ่ม/หยุดวิดีโอ"); "nextScene" -> T("ฉากถัดไป"); "prevScene" -> T("ฉากก่อนหน้า")
    "nextLook" -> T("ฟิลเตอร์ถัดไป"); "clearDraw" -> T("ลบภาพวาด"); "toggleTop" -> T("ซ่อน/แสดงเลเยอร์บนสุด"); else -> a
}

/** tracking switches + tuning, commands (face / hand → action), my own hand poses */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrackTab(v: Vision) {
    val c = PrismThemeAccess.colors
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        val gpuTxt = listOfNotNull(v.faceGpu?.let { T("หน้า") + if (it) " GPU" else " CPU" }, v.handGpu?.let { T("มือ") + if (it) " GPU" else " CPU" }).joinToString(" · ")
        PrismText(T("เห็นหน้า") + " ${v.facesSeen} · " + T("เห็นมือ") + " ${v.handsSeen} · AI ${v.aiMs} ms" + (if (gpuTxt.isNotEmpty()) " · $gpuTxt" else ""),
            style = PrismThemeAccess.type.caption, color = c.ink2)
        if (Gestures.seenNow.isNotEmpty()) PrismText(T("ตอนนี้เห็น:") + " " + Gestures.seenNow.split(' ').joinToString(" · ") { triggerName(it) }, style = PrismThemeAccess.type.caption, color = Prism.accent)
        PrismSwitchRow(T("จับหน้า"), T("ใช้เมื่อมีของติดหน้า ระบายหน้า ตัวละคร หรือคำสั่งด้วยหน้า"), v.faceOn, { v.useFace(it) }, icon = "face")
        PrismSwitchRow(T("จับมือ"), T("ใช้เมื่อมีของติดมือ เอฟเฟกต์มือ หรือคำสั่งด้วยมือ"), v.handsOn, { v.useHands(it) }, icon = "hand")
        PrismSwitchRow(T("แสดงจุดที่จับได้"), T("เห็นเส้นหน้าและโครงมือบนภาพ (เฉพาะตอนตั้งค่า ไม่ติดไปในรูป)"), v.showMesh, { v.useMesh(it) }, icon = "eye")
        PrismSwitchRow(T("ใช้การ์ดจอช่วย (GPU)"), T("เร็วกว่า ถ้าเครื่องมีปัญหาให้ปิด"), v.gpu, { v.useGpu(it) }, icon = "bolt")
        PrismLabel(T("จำนวนหน้าที่จับ"))
        PrismSegmented(listOf("1" to null, "2" to null, "3" to null, "4" to null), v.maxFaces - 1, { v.useMaxFaces(it + 1) }, Modifier.fillMaxWidth())
        PrismLabel(T("ปรับละเอียด"))
        var sf by remember { mutableFloatStateOf(Tracking.smoothFace) }
        var sh by remember { mutableFloatStateOf(Tracking.smoothHand) }
        var ld by remember { mutableFloatStateOf(Tracking.leadMs) }
        var sn by remember { mutableFloatStateOf(Tracking.sensitivity) }
        PrismSlider(T("ความนิ่งของหน้า"), sf, 0f..1f, { sf = it; Tracking.smoothFace = it }, onDone = { Gestures.saveTuning() })
        PrismSlider(T("ความนิ่งของมือ"), sh, 0f..1f, { sh = it; Tracking.smoothHand = it }, onDone = { Gestures.saveTuning() })
        PrismSlider(T("ชดเชยความหน่วง"), ld, 0f..120f, { ld = it; Tracking.leadMs = it }, onDone = { Gestures.saveTuning() }, format = { "${it.toInt()} ms" })
        PrismSlider(T("ความไวของสีหน้า"), sn, 0f..1f, { sn = it; Tracking.sensitivity = it }, onDone = { Gestures.saveTuning() })
        var swap by remember { mutableStateOf(Tracking.swapEyes) }
        PrismSwitchRow(T("สลับตาซ้าย/ขวา"), null, swap, { swap = it; Tracking.swapEyes = it; Gestures.saveTuning() })
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismPill(if (Gestures.calibrating) T("หน้านิ่งๆ 3 วินาที…") else T("ปรับให้เข้ากับหน้าฉัน"), { if (!v.faceOn) v.useFace(true); v.useMesh(true); Gestures.calibrate() }, kind = PillKind.Primary, icon = "face")
            if (Tracking.rest.isNotEmpty()) PrismPill(T("ล้าง"), { Gestures.clearCalibration() }, icon = "refresh")
        }
        PrismLabel(T("คำสั่งด้วยหน้าและมือ (ค้างไว้ครึ่งวินาที)"))
        for (cmd in Gestures.commands.toList()) {
            Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(c.hover).padding(horizontal = 10.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                PrismText(triggerName(cmd.trigger) + "  →  " + actionName(cmd.action), Modifier.weight(1f), style = PrismThemeAccess.type.body.copy(fontSize = 14.sp),
                    color = if (Gestures.lastFired == cmd.trigger) Prism.accent else c.ink)
                PrismMiniButton("trash", { Gestures.removeCommand(cmd) })
            }
        }
        var pickT by remember { mutableStateOf<String?>(null) }
        val triggers = Gestures.faceTriggers + Vision.gestures + Tracking.pairPoses + Gestures.zones + Gestures.poseNames.map { "pose:$it" }
        val picked = pickT
        if (picked == null) {
            PrismLabel(T("เพิ่มคำสั่ง: เลือกท่า"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (t in triggers) PrismChip(triggerName(t), false, { pickT = t })
            }
        } else {
            PrismLabel(triggerName(picked) + " → " + T("ให้ทำอะไร"))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                for (a in Gestures.actions) PrismChip(actionName(a), false, {
                    Gestures.addCommand(picked, a)
                    if (picked in Gestures.faceTriggers) v.useFace(true) else v.useHands(true)
                    pickT = null
                })
                PrismChip(T("ยกเลิก"), false, { pickT = null })
            }
        }
        PrismLabel(T("ท่ามือของฉัน"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (n in Gestures.poseNames) PrismChip("✋ $n  ✕", false, { Gestures.deletePose(n) })
        }
        val rec = Gestures.recording
        PrismPill(if (rec != null) T("ค้างท่าไว้ 3 วินาที…") else T("อัดท่าใหม่"), {
            if (rec == null) { v.useHands(true); Gestures.recordPose("${Gestures.poseNames.size + 1}") }
        }, kind = PillKind.Primary, icon = "record")
        PrismText(T("ทำท่ามือค้างไว้หน้ากล้องตอนอัด แล้วนำไปตั้งเป็นคำสั่งได้"), style = PrismThemeAccess.type.caption, color = c.ink3)
    }
}

@Composable
private fun Tile(icon: String, label: String, on: Boolean, onClick: () -> Unit) {
    val c = PrismThemeAccess.colors
    Column(
        Modifier.width(72.dp).clip(RoundedCornerShape(16.dp))
            .background(if (on) Color(0x337C8CFF) else c.hover)
            .border(if (on) 2.dp else 0.dp, if (on) Prism.grad else SolidColor(Color.Transparent), RoundedCornerShape(16.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onClick).padding(vertical = 8.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        PrismText(icon, style = TextStyle(fontSize = 26.sp))
        PrismText(label, style = PrismThemeAccess.type.caption.copy(fontSize = 11.sp, textAlign = TextAlign.Center), color = c.ink2)
    }
}
