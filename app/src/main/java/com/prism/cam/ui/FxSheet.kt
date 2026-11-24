package com.prism.cam.ui

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.camera.PrismCamera
import com.prism.cam.effects.Look
import com.prism.cam.effects.Looks
import com.prism.cam.effects.Scene
import com.prism.cam.i18n.T
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismChip
import com.prism.cam.ui.kit.PrismIcon
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.PrismText
import com.prism.cam.ui.kit.prismPane
import com.prism.cam.ui.kit.rememberHaptic
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismFont
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * The ✦ sheet: looks, text, stickers, frames (+ AI background and face stickers). Slides up in portrait, in from the
 * right in landscape, leaves the picture visible so every change is seen live. Tap outside / Back closes.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun FxSheet(
    open: Boolean,
    cam: PrismCamera,
    onClose: () -> Unit,
    onNewText: () -> Unit,
    extraTabs: List<Pair<String, @Composable () -> Unit>> = emptyList(),
) {
    BackHandler(open) { onClose() }
    val haptic = rememberHaptic()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val land = maxWidth > maxHeight
        AnimatedVisibility(open, enter = fadeIn(tween(160)), exit = fadeOut(tween(160))) {
            Box(Modifier.fillMaxSize().clickable(remember { MutableInteractionSource() }, indication = null) { onClose() })
        }
        AnimatedVisibility(
            open,
            Modifier.align(if (land) Alignment.CenterEnd else Alignment.BottomCenter),
            enter = if (land) slideInHorizontally(tween(300, easing = Prism.ease)) { it } + fadeIn() else slideInVertically(tween(300, easing = Prism.ease)) { it } + fadeIn(),
            exit = if (land) slideOutHorizontally(tween(200)) { it } + fadeOut() else slideOutVertically(tween(200)) { it } + fadeOut(),
        ) {
            val c = PrismThemeAccess.colors
            val tabs = listOf(T("ฟิลเตอร์") to "wand", T("ข้อความ") to "text", T("สติกเกอร์") to "sticker", T("กรอบ") to "frame") +
                extraTabs.map { it.first to (if (it.first == T("พื้นหลัง")) "image" else "face") }
            var tab by rememberSaveable { mutableIntStateOf(0) }
            val shape = if (land) RoundedCornerShape(28.dp) else RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
            Column(
                Modifier.windowInsetsPadding(WindowInsets.safeDrawing)
                    .then(if (land) Modifier.padding(12.dp).width(420.dp).fillMaxHeight() else Modifier.widthIn(max = 640.dp).fillMaxWidth().heightIn(max = maxHeight * .5f))
                    .prismPane(c, shape, strong = true, elevation = 22.dp)
                    .clickable(remember { MutableInteractionSource() }, indication = null) { }
                    .padding(horizontal = 18.dp, vertical = 14.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                // tab chips + close
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        tabs.forEachIndexed { i, (label, icon) -> PrismChip(label, tab == i, { tab = i }, icon = icon) }
                    }
                    Spacer(Modifier.width(8.dp))
                    Box(
                        Modifier.size(34.dp).clip(CircleShape).background(c.hover)
                            .clickable(remember { MutableInteractionSource() }, indication = null) { onClose() },
                        contentAlignment = Alignment.Center,
                    ) { PrismIcon("x", size = 16.dp, tint = c.ink2) }
                }
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (tab) {
                        0 -> LookRow(cam)
                        1 -> {
                            PrismPill(T("เพิ่มข้อความ"), onNewText, kind = PillKind.Primary, icon = "plus")
                            PrismText(T("แตะข้อความบนภาพเพื่อเลือก · ลากเพื่อย้าย · สองนิ้วเพื่อย่อ/ขยาย/หมุน · แตะซ้ำเพื่อแก้"), style = PrismThemeAccess.type.caption, color = c.ink3)
                        }
                        2 -> FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            for (e in Scene.stickers) {
                                Box(
                                    Modifier.size(52.dp).clip(RoundedCornerShape(16.dp)).background(c.hover)
                                        .clickable(remember { MutableInteractionSource() }, indication = null) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Scene.addSticker(e) },
                                    contentAlignment = Alignment.Center,
                                ) { PrismText(e, style = TextStyle(fontSize = 28.sp)) }
                            }
                        }
                        3 -> FrameRow()
                        else -> extraTabs.getOrNull(tab - 4)?.second?.invoke()
                    }
                    if (!Scene.isEmpty || cam.look.id != "none") {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            PrismPill(T("ล้างทั้งหมด"), { Scene.clear(); cam.useLook(Looks.all[0]); cam.clearAiLooks() }, kind = PillKind.Warn, icon = "trash")
                        }
                    }
                }
            }
        }
    }
}

/** Round previews of every look, made from a small still of the camera. */
@Composable
private fun LookRow(cam: PrismCamera) {
    val haptic = rememberHaptic()
    val c = PrismThemeAccess.colors
    val sample = remember { cam.sampleForThumbs() }
    val thumbs: List<Pair<Look, ImageBitmap?>> = remember(sample) {
        Looks.all.map { l -> l to sample?.let { s -> tint(s, l).asImageBitmap() } }
    }
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        for ((l, img) in thumbs) {
            val on = cam.look.id == l.id
            Column(
                Modifier.clickable(remember { MutableInteractionSource() }, indication = null) { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); cam.useLook(l) },
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Box(
                    Modifier.size(62.dp).clip(CircleShape)
                        .border(if (on) 3.dp else 1.dp, if (on) Prism.grad else SolidColor(c.line), CircleShape)
                        .background(Brush.linearGradient(listOf(Color(0xFF6B8CC7), Color(0xFFE8B08A)))),
                ) {
                    if (img != null) Image(img, null, Modifier.fillMaxSize().padding(if (on) 3.dp else 0.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                }
                PrismText(T(l.th), style = TextStyle(fontFamily = PrismFont, fontSize = 12.sp, fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal), color = if (on) c.ink else c.ink2)
            }
        }
    }
}

private fun tint(src: Bitmap, l: Look): Bitmap {
    val out = Bitmap.createBitmap(src.width, src.height, Bitmap.Config.ARGB_8888)
    val cv = Canvas(out)
    cv.drawBitmap(src, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG).apply { colorFilter = ColorMatrixColorFilter(ColorMatrix(l.m)) })
    if (l.vignette > 0f) com.prism.cam.effects.PhotoFx.vignette(cv, out.width, out.height, l.vignette)
    return out
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FrameRow() {
    val haptic = rememberHaptic()
    val names = mapOf(
        "none" to T("ไม่มีกรอบ"), "white" to T("โพลารอยด์"), "round" to T("มุมมน"), "film" to T("ฟิล์ม"),
        "prism" to "Prism", "date" to T("วันที่"), "cinema" to T("จอหนัง"),
    )
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        for (f in Scene.frames) PrismChip(names[f] ?: f, Scene.frame == f, { haptic(android.view.HapticFeedbackConstants.CLOCK_TICK); Scene.frame = f; Scene.touch() })
    }
}
