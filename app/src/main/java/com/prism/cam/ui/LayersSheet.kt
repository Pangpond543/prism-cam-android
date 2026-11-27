package com.prism.cam.ui

import com.prism.cam.ui.kit.hScrollFade
import androidx.compose.foundation.Image
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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.i18n.T
import com.prism.cam.studio.Content
import com.prism.cam.studio.LType
import com.prism.cam.studio.Layer
import com.prism.cam.studio.Make
import com.prism.cam.studio.Studio
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismLabel
import com.prism.cam.ui.kit.PrismMiniButton
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.rememberHaptic
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** type → icon / Thai name (for the list) */
fun typeIcon(t: LType) = when (t) {
    LType.Camera -> "camera"; LType.Image -> "image"; LType.Video -> "film"; LType.Text -> "text"; LType.Sticker -> "sticker"
    LType.Shape -> "shape"; LType.Frame -> "frame"; LType.Drawing -> "edit"; LType.Avatar -> "face"; LType.FacePaint -> "face"
    LType.FaceFx -> "face"; LType.HandFx -> "hand"; LType.Lyrics -> "music"; LType.Screen -> "screen"
}

fun typeName(t: LType) = when (t) {
    LType.Camera -> T("กล้อง"); LType.Image -> T("รูป"); LType.Video -> T("วิดีโอ"); LType.Text -> T("ข้อความ"); LType.Sticker -> T("สติกเกอร์")
    LType.Shape -> T("รูปทรง"); LType.Frame -> T("กรอบ"); LType.Drawing -> T("ภาพวาด"); LType.Avatar -> T("ตัวละคร"); LType.FacePaint -> T("ระบายหน้า")
    LType.FaceFx -> T("เอฟเฟกต์หน้า"); LType.HandFx -> T("เอฟเฟกต์มือ"); LType.Lyrics -> T("เนื้อเพลง"); LType.Screen -> T("หน้าจอ")
}

/**
 * Layers panel (desktop "Layers" tab): scenes, quick camera layouts, add pictures / videos / text / shapes, and the
 * layer list — top of the list = in front. Each row: eye (show / hide), lock, move up / down, duplicate, settings, delete.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LayersSheet(open: Boolean, onClose: () -> Unit, onProps: (Layer) -> Unit, onAddMedia: () -> Unit, onNewText: () -> Unit, onShare: () -> Unit = {}, onOpenProject: () -> Unit = {}) {
    val c = PrismThemeAccess.colors
    val haptic = rememberHaptic()
    PrismSheet(open, onClose, T("เลเยอร์"), tall = true, actions = {
        PrismMiniButton("undo", { Studio.undo() }, enabled = Studio.canUndo)
        PrismMiniButton("undo", { Studio.redo() }, Modifier.scale(-1f, 1f), enabled = Studio.canRedo)
    }) {
        // ── scenes
        PrismLabel(T("ฉาก"))
        Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            Studio.scenes.forEachIndexed { i, s -> PrismChip(s.name, i == Studio.current, { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Studio.goScene(i) }) }
            PrismMiniButton("plus", { Studio.addScene() })
            PrismMiniButton("copy", { Studio.addScene(Studio.scene) })
            if (Studio.scenes.size > 1) PrismMiniButton("trash", { Studio.removeScene(Studio.current) })
        }
        if (Studio.scenes.size > 1) Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (sec in listOf(0, 3, 5, 10)) PrismChip(if (sec == 0) T("ไม่เล่นอัตโนมัติ") else T("สไลด์ทุก") + " $sec " + T("วิ"), Studio.autoPlay == sec, { Studio.autoPlay = sec })
        }
        // ── templates
        PrismLabel(T("แม่แบบ"))
        Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (id in Make.templates) PrismChip(Make.templateIcons[id] + " " + T(Make.templateNames[id] ?: id), false, { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.template(id) })
        }
        // ── add
        PrismLabel(T("เพิ่ม"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismPill(T("รูป / วิดีโอ"), onAddMedia, kind = PillKind.Primary, icon = "image")
            PrismPill(T("ข้อความ"), onNewText, icon = "text")
            PrismPill(T("รูปทรง"), { Make.shape("round") }, icon = "shape")
            PrismPill(T("วาดด้วยนิ้ว"), { Make.drawing(); onClose() }, icon = "edit")
        }
        MediaLibrary()
        // ── camera layouts
        PrismLabel(T("วางกล้อง"))
        val names = mapOf("fill" to T("เต็มจอ"), "fit" to T("พอดีมีขอบ"), "pip" to T("จอเล็กมุม"), "half" to T("ครึ่งจอ"), "circle" to T("วงกลม"), "tilt" to T("เอียงเท่ๆ"))
        Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            for (k in Make.layouts) PrismChip(names[k] ?: k, false, { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Make.cameraLayout(k) })
        }
        // ── list (front first)
        PrismLabel(T("เลเยอร์ทั้งหมด") + " · " + Studio.layers.size)
        val list = Studio.layers.asReversed().toList()
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            for (l in list) LayerRow(l, onProps)
        }
        if (!Make.isEmpty()) PrismPill(T("ล้างทั้งหมด"), { Make.clearAll() }, kind = PillKind.Warn, icon = "trash")
        PrismLabel(T("โปรเจกต์"))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            PrismPill(T("ส่งโปรเจกต์"), onShare, icon = "download")
            PrismPill(T("เปิดโปรเจกต์"), onOpenProject, icon = "folder")
        }
        PrismText(T("ส่งทุกฉากพร้อมรูปและวิดีโอเป็นไฟล์เดียว ไปอีกเครื่องผ่าน LINE, Drive หรือแชร์ใกล้เคียง แล้วกดเปิดโปรเจกต์ที่เครื่องนั้น"), style = PrismThemeAccess.type.caption, color = c.ink3)
        PrismText(T("แตะเลเยอร์บนภาพเพื่อเลือก · ลากเพื่อย้าย · สองนิ้วย่อ/หมุน · จุดมุมเพื่อปรับขนาด"), style = PrismThemeAccess.type.caption, color = c.ink3)
    }
}

@Composable
private fun LayerRow(l: Layer, onProps: (Layer) -> Unit) {
    val c = PrismThemeAccess.colors
    val sel = Studio.selection.firstOrNull() == l.id
    val i = Studio.layers.indexOf(l)
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(if (sel) Color(0x267C8CFF) else c.hover)
            .border(if (sel) 1.5.dp else 0.dp, if (sel) Prism.grad else SolidColor(Color.Transparent), RoundedCornerShape(14.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) { Studio.select(l.id) }
            .padding(horizontal = 8.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        Thumb(l)
        Column(Modifier.weight(1f)) {
            PrismText(l.name.ifBlank { typeName(l.type) }, style = PrismThemeAccess.type.body.copy(fontSize = 14.sp), color = if (l.visible) c.ink else c.ink3)
            val tags = buildList {
                add(typeName(l.type))
                if (l.attach.kind == "face") add(T("ติดหน้า")); if (l.attach.kind == "hand") add(T("ติดมือ")); if (l.attach.kind == "hands2") add(T("ระหว่างสองมือ"))
                if (l.anim.isNotEmpty()) add(T("เคลื่อนไหว"))
            }
            PrismText(tags.joinToString(" · "), style = PrismThemeAccess.type.caption.copy(fontSize = 11.sp), color = c.ink3)
        }
        PrismMiniButton(if (l.visible) "eye" else "eyeoff", { l.visible = !l.visible; Studio.commit("vis") })
        PrismMiniButton(if (l.locked) "lock" else "unlock", { l.locked = !l.locked; Studio.commit("lock") }, on = l.locked)
        PrismMiniButton("up", { Studio.move(l.id, i + 1) }, enabled = i < Studio.layers.size - 1)
        PrismMiniButton("up", { Studio.move(l.id, i - 1) }, Modifier.rotate(180f), enabled = i > 0)
        PrismMiniButton("sliders", { Studio.select(l.id); onProps(l) })
    }
}

@Composable
private fun Thumb(l: Layer) {
    val c = PrismThemeAccess.colors
    var img by remember(l.id, l.media) { mutableStateOf<ImageBitmap?>(null) }
    if (l.media.isNotEmpty()) LaunchedEffect(l.id, l.media) {
        img = withContext(Dispatchers.IO) {
            Content.info(java.io.File(Content.mediaDir, l.media))?.let { Content.thumbnail(it, 96)?.asImageBitmap() }
        }
    }
    Box(Modifier.size(38.dp).clip(RoundedCornerShape(10.dp)).background(c.fill), contentAlignment = Alignment.Center) {
        val b = img
        when {
            b != null -> Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
            l.type == LType.Sticker -> PrismText(l.text, style = TextStyle(fontSize = 20.sp))
            l.type == LType.Text -> PrismText(l.text.take(3), style = TextStyle(fontSize = 13.sp), color = c.ink2)
            else -> PrismIcon(typeIcon(l.type), size = 18.dp, tint = c.ink2)
        }
    }
}

/** recent pictures / videos already in the app: tap to add again */
@Composable
private fun MediaLibrary() {
    var items by remember { mutableStateOf<List<Pair<Content.Media, ImageBitmap?>>>(emptyList()) }
    LaunchedEffect(Studio.version) {
        items = withContext(Dispatchers.IO) { Content.library().take(24).map { it to Content.thumbnail(it, 120)?.asImageBitmap() } }
    }
    if (items.isEmpty()) return
    PrismLabel(T("ไฟล์ในแอป"))
    Row(Modifier.hScrollFade(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        for ((m, b) in items) {
            Box(
                Modifier.size(56.dp).clip(RoundedCornerShape(12.dp)).background(PrismThemeAccess.colors.fill)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { Make.media(m) },
                contentAlignment = Alignment.Center,
            ) {
                if (b != null) Image(b, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop)
                if (m.kind == LType.Video) PrismIcon("play", size = 16.dp, tint = Color.White)
                if (m.gif) PrismText("GIF", Modifier.align(Alignment.BottomEnd).padding(3.dp), style = TextStyle(fontSize = 9.sp), color = Color.White)
            }
        }
    }
    @Suppress("unused") val o = TextOverflow.Ellipsis
}
