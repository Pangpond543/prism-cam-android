package com.prism.cam.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import com.prism.cam.studio.Make
import com.prism.cam.ui.kit.PillKind
import com.prism.cam.ui.kit.PrismMiniButton
import com.prism.cam.ui.kit.PrismPill
import com.prism.cam.ui.kit.prismPane
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.cam.effects.GlFx
import com.prism.cam.studio.Geometry
import com.prism.cam.studio.Layer
import com.prism.cam.studio.LType
import com.prism.cam.studio.Studio
import com.prism.cam.ui.kit.PrismIcon
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Where a layer is on the picture box (px): attached / animated layers from what the GPU drew last frame. */
fun layerQuad(l: Layer, fx: GlFx, bw: Float, bh: Float): Geometry.Quad? {
    if (Geometry.isFullCanvas(l.type)) return null
    val live = l.attach.kind.isNotEmpty() || l.anim.isNotEmpty() || l.keys.size >= 2
    if (live) {
        val d = fx.drawn[l.id] ?: return null
        val k = bw / fx.canvasW.coerceAtLeast(1)
        if (d.w <= 0f) return null
        return Geometry.Quad(d.cx * k, d.cy * k, d.w * k, d.h * k, d.rot, d.sx)
    }
    return Geometry.quad(l, bw, bh)
}

/** the layer a finger at (px, py) picks: top-most first; a full-picture camera only when it is already selected */
fun hitLayer(px: Float, py: Float, bw: Float, bh: Float, fx: GlFx, pad: Float): Layer? {
    val sel = Studio.selection.firstOrNull()
    for (l in Studio.layers.asReversed()) {
        if (!l.visible || l.locked || Geometry.isFullCanvas(l.type)) continue
        if (l.type == LType.Camera && l.w >= .98f && l.rot == 0f && l.id != sel) continue
        val q = layerQuad(l, fx, bw, bh) ?: continue
        if (q.local(px, py, if (l.id == sel) pad * 1.5f else pad) != null) return l
    }
    return null
}

/**
 * Dashed box around the selected layer with three handles: ✕ delete (top right), ⚙ settings (top left), and the
 * corner dot (bottom right) — drag it to resize and turn around the centre. Follows attached / moving layers live.
 */
@Composable
fun SelectionBox(fx: GlFx, boxW: Dp, boxH: Dp, onSettings: (Layer) -> Unit) {
    val l = Studio.selected() ?: return
    if (!l.visible || Geometry.isFullCanvas(l.type)) return
    val density = LocalDensity.current
    val bw = with(density) { boxW.toPx() }; val bh = with(density) { boxH.toPx() }
    val live = l.attach.kind.isNotEmpty() || l.anim.isNotEmpty() || l.keys.size >= 2
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(l.id, live) { if (live) while (true) { withFrameNanos { }; tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    @Suppress("UNUSED_EXPRESSION") Studio.version
    val q = layerQuad(l, fx, bw, bh) ?: return
    val pad = with(density) { 8.dp.toPx() }
    val wPx = q.w * q.sx + pad * 2; val hPx = q.h + pad * 2
    val w = with(density) { wPx.toDp() }; val h = with(density) { hPx.toDp() }
    val cx = with(density) { q.cx.toDp() }; val cy = with(density) { q.cy.toDp() }
    Box(
        Modifier.offset(cx - w / 2, cy - h / 2).size(w, h).graphicsLayer { rotationZ = q.rot }
            .drawBehind {
                drawRoundRect(
                    Color(0xE6FFFFFF), cornerRadius = CornerRadius(10.dp.toPx()),
                    style = Stroke(1.5.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))),
                )
            },
    ) {
        if (!l.locked && l.type != LType.Camera || Studio.layers.count { it.type == LType.Camera } > 1) Handle(Modifier.align(Alignment.TopEnd).offset(12.dp, (-12).dp), Color(0xE6FF6B8B), "x") { Studio.remove(l.id) }
        Handle(Modifier.align(Alignment.TopStart).offset((-12).dp, (-12).dp), Color(0xE67C8CFF), "sliders") { onSettings(l) }
        if (!l.locked) {
            // resize + turn: the finger's position relative to the centre (screen space)
            Box(
                Modifier.align(Alignment.BottomEnd).offset(12.dp, 12.dp).size(30.dp).clip(CircleShape).background(Color(0xF2FFFFFF))
                    .border(2.dp, Color(0xFF7C8CFF), CircleShape)
                    .pointerInput(l.id) {
                        var px = 0f; var py = 0f; var p0x = 0f; var p0y = 0f
                        var w0 = 0f; var r0 = 0f; var s0 = 1f
                        detectDragGestures(
                            onDragStart = {
                                val a = Math.toRadians(l.rot.toDouble())
                                val hx = wPx / 2; val hy = hPx / 2
                                p0x = (hx * cos(a) - hy * sin(a)).toFloat(); p0y = (hx * sin(a) + hy * cos(a)).toFloat()
                                px = p0x; py = p0y; w0 = l.w; r0 = l.rot; s0 = l.attach.size
                            },
                            onDragEnd = { Studio.commit("resize") },
                            onDragCancel = { Studio.commit("resize") },
                        ) { ch, d ->
                            ch.consume()
                            val a = Math.toRadians(l.rot.toDouble())
                            px += (d.x * cos(a) - d.y * sin(a)).toFloat(); py += (d.x * sin(a) + d.y * cos(a)).toFloat()
                            val k = (hypot(px, py) / hypot(p0x, p0y).coerceAtLeast(1f)).coerceIn(.05f, 20f)
                            val turn = Math.toDegrees((atan2(py, px) - atan2(p0y, p0x)).toDouble()).toFloat()
                            if (l.attach.kind.isNotEmpty() && l.attach.followScale) l.attach.size = (s0 * k).coerceIn(.05f, 6f)
                            else l.w = (w0 * k).coerceIn(.03f, 3f)
                            l.rot = snapAngle(r0 + turn)
                            Studio.touch()
                        }
                    },
            )
        }
    }
}

/** snap to 0 / 90 / 180 / 270 within 4° (like the desktop) */
fun snapAngle(a: Float): Float {
    var r = ((a % 360f) + 540f) % 360f - 180f
    for (t in floatArrayOf(-180f, -90f, 0f, 90f, 180f)) if (kotlin.math.abs(r - t) < 4f) r = t
    return r
}

@Composable
private fun Handle(m: Modifier, bg: Color, icon: String, onClick: () -> Unit) {
    Box(
        m.size(28.dp).clip(CircleShape).background(bg).border(1.dp, Color.White, CircleShape)
            .clickable(remember { MutableInteractionSource() }, indication = null) { onClick() },
        contentAlignment = Alignment.Center,
    ) { PrismIcon(icon, size = 15.dp, tint = Color.White, stroke = 2.2f) }
}

/** While a drawing layer is selected: draw with one finger; this bar = pen colour, undo line, settings, done. */
@Composable
fun BoxScope.DrawingBar(d: Layer, onSettings: () -> Unit) {
    val c = com.prism.cam.ui.theme.PrismThemeAccess.colors
    Row(
        Modifier.align(Alignment.TopCenter).padding(top = 10.dp).prismPane(c, RoundedCornerShape(50), strong = true).padding(6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
    ) {
        for (col in listOf(0xFFFF9EC7.toInt(), 0xFF7C8CFF.toInt(), 0xFF86E8D6.toInt(), 0xFFFFD98A.toInt(), android.graphics.Color.WHITE)) {
            val on = Make.penColor == col
            Box(Modifier.size(if (on) 26.dp else 22.dp).clip(CircleShape).background(Color(col)).border(if (on) 2.dp else 1.dp, if (on) com.prism.cam.ui.theme.Prism.grad else androidx.compose.ui.graphics.SolidColor(c.line), CircleShape)
                .clickable(remember { MutableInteractionSource() }, indication = null) { Make.penColor = col })
        }
        PrismMiniButton("undo", { if (d.strokes.isNotEmpty()) { d.strokes.removeAt(d.strokes.size - 1); d.touchContent(); Studio.commit("draw") } }, enabled = d.strokes.isNotEmpty())
        PrismMiniButton("sliders", onSettings)
        PrismPill(com.prism.cam.i18n.T("เสร็จ"), { Studio.selection.clear() }, kind = PillKind.Primary, icon = "check")
    }
}

/** Speed panel (desktop "perf"): live numbers of the effect engine + light mode for weak devices. */
@Composable
fun SpeedPanel(cam: com.prism.cam.camera.PrismCamera, vision: com.prism.cam.ai.Vision?) {
    val c = com.prism.cam.ui.theme.PrismThemeAccess.colors
    var tick by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) { while (true) { kotlinx.coroutines.delay(500); tick++ } }
    @Suppress("UNUSED_EXPRESSION") tick
    val fx = cam.fx
    val on = cam.fxReasons.isNotEmpty() || cam.look.id != "none"
    val lines = if (!on) com.prism.cam.i18n.T("ตอนนี้ไม่มีเอฟเฟกต์ — กล้องส่งภาพตรง เร็วที่สุด")
    else "${"%.0f".format(fx.statFps)} fps · GPU ${"%.1f".format(fx.statDrawMs)} ms · ${fx.canvasW}×${fx.canvasH}" +
        (vision?.let { v -> if (v.aiMs > 0) " · AI ${v.aiMs} ms" else "" } ?: "") + " · " + Studio.layers.size + " " + com.prism.cam.i18n.T("เลเยอร์")
    com.prism.cam.ui.kit.PrismText(lines, style = com.prism.cam.ui.theme.PrismThemeAccess.type.caption, color = c.ink2)
    if (fx.gpuName.isNotEmpty()) com.prism.cam.ui.kit.PrismText(fx.gpuName, style = com.prism.cam.ui.theme.PrismThemeAccess.type.caption, color = c.ink3)
    com.prism.cam.ui.kit.PrismSwitchRow(
        com.prism.cam.i18n.T("โหมดประหยัด"), com.prism.cam.i18n.T("เครื่องช้าหรือร้อน: ลดขนาดภาพเอฟเฟกต์และความถี่ของ AI"),
        com.prism.cam.prefs.CamPrefs.lite, { com.prism.cam.prefs.CamPrefs.useLite(it); fx.lite = it }, icon = "bolt",
    )
}
