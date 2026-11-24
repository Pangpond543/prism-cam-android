package com.prism.cam.ui

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
import com.prism.cam.effects.FaceStickers
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismSegmented
import com.prism.cam.ui.kit.PrismSwitchRow
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.rememberHaptic
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess

/** The two AI tabs of the ✦ sheet: background (blur / replace) and face (stickers + peace-sign shutter). */
fun aiTabs(v: Vision, onPickPhoto: () -> Unit): List<Pair<String, @Composable () -> Unit>> = listOf(
    T("พื้นหลัง") to { BackgroundTab(v, onPickPhoto) },
    T("หน้า") to { FaceTab(v) },
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
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
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
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("🚫", T("ไม่มี"), v.faceSticker == null) { v.useFaceSticker(null) }
            for (id in FaceStickers.ids) {
                Tile(FaceStickers.icons[id] ?: "✨", T(FaceStickers.names[id] ?: id), v.faceSticker == id) {
                    haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); v.useFaceSticker(id)
                }
            }
        }
        PrismSwitchRow(T("ชูสองนิ้ว ✌️ เพื่อถ่าย"), T("เห็นมือชูสองนิ้ว จะนับ 3 วินาทีแล้วถ่ายเอง"), v.peace, { v.usePeace(it) }, icon = "hand")
        v.status?.let { PrismText(it, style = PrismThemeAccess.type.caption, color = Prism.danger) }
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
