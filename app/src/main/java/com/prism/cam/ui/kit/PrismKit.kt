package com.prism.cam.ui.kit

import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.addOutline
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismColors
import com.prism.cam.ui.theme.PrismFont
import com.prism.cam.ui.theme.PrismThemeAccess

// ───────────────────────────── surfaces ─────────────────────────────

/**
 * `.glass` pane: 135° translucent fill + gradient rim (white 1 → .25 @30% → .08 @55% → .85) + long tinted shadow
 * + top light line. Classic: solid panel + hairline + short shadow (html[data-look=classic] .glass).
 */
fun Modifier.prismPane(c: PrismColors, shape: Shape, strong: Boolean = false, elevation: Dp = 16.dp): Modifier {
    val fill = if (c.isGlass) Brush.linearGradient(if (strong) c.glassStrong else c.glass, start = Offset.Zero, end = Offset.Infinite)
    else SolidColor(if (strong) c.panelStrong else c.panel)
    val rim = if (c.isGlass) Brush.linearGradient(
        0f to c.rim[0], .30f to c.rim[1], .55f to c.rim[2], 1f to c.rim[3], start = Offset.Zero, end = Offset.Infinite,
    ) else SolidColor(c.line)
    return this
        .then(
            // Elevation shadows show THROUGH a translucent pane (a pale inner rectangle on Huawei, 2026-11-24),
            // so glass draws its own soft shadow clipped to the outside of the shape. Classic panes are opaque.
            if (c.isGlass) Modifier.outsideShadow(shape, c.shadow, blur = elevation * 1.6f, dy = elevation * .55f)
            else Modifier.shadow(elevation / 4, shape, clip = false, ambientColor = c.shadow, spotColor = c.shadow),
        )
        .clip(shape)
        .background(fill)
        .then(
            if (c.isGlass) Modifier.drawWithContent {
                drawContent()
                drawLine(Color.White.copy(alpha = if (c.dark) .22f else .95f), Offset(size.height * .25f, 0.5f), Offset(size.width - size.height * .25f, 0.5f), 1.5f)
            } else Modifier,
        )
        .border(if (c.isGlass) 1.2.dp else 1.dp, rim, shape)
}

/**
 * A long soft shadow drawn only OUTSIDE [shape] (CSS box-shadow look), so it never tints a translucent pane.
 * Paint.setShadowLayer is hardware-accelerated from Android 9; on 8.x it simply draws nothing.
 */
fun Modifier.outsideShadow(shape: Shape, color: Color, blur: Dp, dy: Dp): Modifier = drawBehind {
    val outline = shape.createOutline(size, layoutDirection, this)
    val path = androidx.compose.ui.graphics.Path().apply { addOutline(outline) }
    drawIntoCanvas { canvas ->
        val nc = canvas.nativeCanvas
        val save = nc.save()
        nc.clipOutPath(path.asAndroidPath())
        val paint = android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            this.color = android.graphics.Color.TRANSPARENT
            setShadowLayer(blur.toPx(), 0f, dy.toPx(), color.toArgb())
        }
        // draw the shape itself in an opaque colour under the clip: only its shadow (outside) is visible
        paint.color = color.copy(alpha = 1f).toArgb()
        nc.drawPath(path.asAndroidPath(), paint)
        nc.restoreToCount(save)
    }
}

@Composable
fun PrismPanel(modifier: Modifier = Modifier, strong: Boolean = false, radius: Dp = 24.dp, content: @Composable () -> Unit) {
    val c = PrismThemeAccess.colors
    Box(modifier.prismPane(c, RoundedCornerShape(radius), strong).padding(horizontal = 20.dp, vertical = 18.dp)) { content() }
}

// ───────────────────────────── text ─────────────────────────────

@Composable
fun PrismText(text: String, modifier: Modifier = Modifier, style: TextStyle = PrismThemeAccess.type.body, color: Color = PrismThemeAccess.colors.ink, align: TextAlign? = null) {
    BasicText(text, modifier, style = style.merge(TextStyle(color = color, textAlign = align ?: TextAlign.Unspecified)))
}

/** "Prism Cam" in the Prism gradient + faint "by 543" (docs/brand.md). */
@Composable
fun PrismWordmark(modifier: Modifier = Modifier, size: Float = 18f) {
    val c = PrismThemeAccess.colors
    Row(modifier, verticalAlignment = Alignment.Bottom) {
        BasicText(
            "Prism Cam",
            modifier = Modifier.padding(vertical = (size * .08f).dp),
            style = TextStyle(fontFamily = PrismFont, fontWeight = FontWeight.SemiBold, fontSize = size.sp, lineHeight = (size * 1.3f).sp, letterSpacing = .3.sp, brush = Prism.gradText()),
        )
        Spacer(Modifier.width(6.dp))
        BasicText(
            "by 543",
            modifier = Modifier.padding(bottom = (size * .22f).dp).alpha(.55f),
            style = TextStyle(fontFamily = PrismFont, fontSize = (size * .6f).coerceAtLeast(10.5f).sp, color = c.ink3),
        )
    }
}

// ───────────────────────────── press feedback ─────────────────────────────

/** Glass: spring squeeze (.96). Classic: no bounce, just a quick dim (desktop classic = no hover lift / bounce). */
@Composable
private fun pressScale(pressed: Boolean, glass: Boolean): Float {
    val target = if (pressed) (if (glass) .96f else 1f) else 1f
    return animateFloatAsState(target, if (glass) spring(dampingRatio = .55f, stiffness = Spring.StiffnessMediumLow) else tween(80), label = "press").value
}

@Composable
fun rememberHaptic(): (Int) -> Unit {
    val view = LocalView.current
    return remember(view) { { kind: Int -> view.performHapticFeedback(kind) } }
}

// ───────────────────────────── buttons ─────────────────────────────

enum class PillKind { Primary, Secondary, Warn }

@Composable
fun PrismPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, kind: PillKind = PillKind.Secondary, icon: String? = null) {
    val c = PrismThemeAccess.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val shape = CircleShape
    val bg: Brush = when (kind) {
        PillKind.Primary -> Prism.grad
        PillKind.Warn -> Brush.linearGradient(listOf(Color(0xFFFF8FA6), Prism.danger))
        PillKind.Secondary -> when {
            c.isGlass && !c.dark -> Brush.verticalGradient(listOf(Color(0xD9FFFFFF), Color(0x73FFFFFF)))
            c.isGlass -> SolidColor(Color(0x1AFFFFFF))
            c.dark -> SolidColor(Color(0x0FFFFFFF))
            else -> SolidColor(Color.White)
        }
    }
    val ink = if (kind == PillKind.Secondary) c.ink else Color.White
    val ring = when {
        kind != PillKind.Secondary -> Color(0x59FFFFFF)
        c.isGlass -> Color(if (c.dark) 0x33FFFFFF else 0xE6FFFFFF.toInt())
        c.dark -> Color(0x1AFFFFFF)
        else -> Color(0x24141828)
    }
    val glow = if (kind == PillKind.Primary) Color(0xCC8C78FF) else c.shadow
    Row(
        modifier
            .scale(pressScale(pressed, c.isGlass))
            .alpha(if (pressed && !c.isGlass) .82f else 1f)
            .shadow(if (kind == PillKind.Primary) 10.dp else if (c.isGlass) 4.dp else 1.dp, shape, ambientColor = glow, spotColor = glow)
            .clip(shape)
            .background(bg)
            .border(1.dp, ring, shape)
            .clickable(src, indication = null) { haptic(HapticFeedbackConstants.VIRTUAL_KEY); onClick() }
            .padding(horizontal = 18.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (icon != null) PrismIcon(icon, size = 18.dp, tint = ink)
        PrismText(text, style = PrismThemeAccess.type.label, color = ink)
    }
}

/** `.seg` — pill track with a sliding gradient thumb (theme / style / language menus). */
@Composable
fun PrismSegmented(options: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val c = PrismThemeAccess.colors
    val haptic = rememberHaptic()
    BoxWithConstraints(
        modifier
            .height(40.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(c.segTrack)
            .border(1.dp, if (c.isGlass) Color(if (c.dark) 0x1AFFFFFF else 0xB3FFFFFF.toInt()) else c.line, RoundedCornerShape(14.dp))
            .padding(3.dp),
    ) {
        val w = maxWidth / options.size
        val x by animateDpAsState(
            w * selected,
            if (c.isGlass) spring(dampingRatio = .72f, stiffness = Spring.StiffnessMediumLow) else tween(160, easing = Prism.ease),
            label = "seg",
        )
        Box(
            Modifier.offset(x = x).width(w).fillMaxHeight()
                .shadow(if (c.isGlass) 6.dp else 1.dp, RoundedCornerShape(11.dp), ambientColor = Color(0xE68C78FF), spotColor = Color(0xE68C78FF))
                .clip(RoundedCornerShape(11.dp))
                .background(Prism.grad),
        )
        Row(Modifier.fillMaxHeight()) {
            options.forEachIndexed { i, (label, icon) ->
                val on = i == selected
                Row(
                    Modifier.width(w).fillMaxHeight().clip(RoundedCornerShape(11.dp))
                        .clickable(remember { MutableInteractionSource() }, indication = null) {
                            if (!on) { haptic(HapticFeedbackConstants.CLOCK_TICK); onSelect(i) }
                        },
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    val ink = if (on) Color.White else c.ink2
                    if (icon != null) { PrismIcon(icon, size = 16.dp, tint = ink); Spacer(Modifier.width(7.dp)) }
                    PrismText(label, style = PrismThemeAccess.type.label.copy(fontSize = 13.sp), color = ink)
                }
            }
        }
    }
}

/** `.chip` — on = gradient + white. */
@Composable
fun PrismChip(text: String, on: Boolean, onClick: () -> Unit, icon: String? = null) {
    val c = PrismThemeAccess.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    val bg: Brush = if (on) Prism.grad else SolidColor(if (c.isGlass) (if (c.dark) Color(0x14FFFFFF) else Color(0x8CFFFFFF)) else c.fill)
    Row(
        Modifier.scale(pressScale(pressed, c.isGlass)).clip(CircleShape).background(bg)
            .border(1.dp, if (on) Color(0x4DFFFFFF) else (if (c.isGlass) c.line else Color.Transparent), CircleShape)
            .clickable(src, indication = null) { haptic(HapticFeedbackConstants.CLOCK_TICK); onClick() }
            .padding(horizontal = 14.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        val ink = if (on) Color.White else c.ink2
        if (icon != null) PrismIcon(icon, size = 15.dp, tint = ink)
        PrismText(text, style = PrismThemeAccess.type.caption.copy(fontWeight = FontWeight.SemiBold), color = ink)
    }
}

/** On/off row: label + caption on the left, a pill switch on the right (thumb slides; on = Prism gradient). */
@Composable
fun PrismSwitchRow(label: String, caption: String?, checked: Boolean, onChange: (Boolean) -> Unit, icon: String? = null) {
    val c = PrismThemeAccess.colors
    val haptic = rememberHaptic()
    val x by animateDpAsState(if (checked) 20.dp else 0.dp, if (c.isGlass) spring(dampingRatio = .6f, stiffness = Spring.StiffnessMediumLow) else tween(150), label = "sw")
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .clickable(remember { MutableInteractionSource() }, indication = null) { haptic(HapticFeedbackConstants.CLOCK_TICK); onChange(!checked) }
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { PrismIcon(icon, size = 18.dp, tint = c.ink2); Spacer(Modifier.width(12.dp)) }
        androidx.compose.foundation.layout.Column(Modifier.weight(1f)) {
            PrismText(label, style = PrismThemeAccess.type.label.copy(fontSize = 14.sp))
            if (caption != null) PrismText(caption, style = PrismThemeAccess.type.caption, color = c.ink3)
        }
        Spacer(Modifier.width(12.dp))
        Box(
            Modifier.size(46.dp, 26.dp).clip(CircleShape)
                .background(if (checked) Prism.grad else SolidColor(c.segTrack))
                .border(1.dp, if (checked) Color(0x4DFFFFFF) else c.line, CircleShape)
                .padding(3.dp),
        ) {
            Box(Modifier.offset(x = x).size(20.dp).shadow(2.dp, CircleShape).clip(CircleShape).background(Color.White))
        }
    }
}

// ───────────────────────────── on the camera picture ─────────────────────────────

/**
 * Round control drawn on the live picture (flip camera, last photo frame, mode buttons). Always white ink.
 * Classic: flat translucent black circle. Glass: frosted white circle with a light rim.
 */
@Composable
fun CamRoundButton(icon: String, onClick: () -> Unit, size: Dp = 44.dp, modifier: Modifier = Modifier) {
    val c = PrismThemeAccess.colors
    val src = remember { MutableInteractionSource() }
    val pressed by src.collectIsPressedAsState()
    val haptic = rememberHaptic()
    Box(
        modifier.size(size).scale(pressScale(pressed, true)).clip(CircleShape)
            .background(if (c.isGlass) Color(0x2EFFFFFF) else Color(0x47000000))
            .then(if (c.isGlass) Modifier.border(1.dp, Brush.linearGradient(listOf(Color(0xCCFFFFFF), Color(0x1AFFFFFF), Color(0x80FFFFFF))), CircleShape) else Modifier)
            .clickable(src, indication = null) { haptic(HapticFeedbackConstants.VIRTUAL_KEY); onClick() },
        contentAlignment = Alignment.Center,
    ) { PrismIcon(icon, size = size * .44f, tint = Color.White, stroke = 1.6f) }
}

/**
 * The shutter. White ring + white disc; pressing squeezes the disc. Recording = red rounded square.
 * Glass: the ring carries the Prism gradient (docs/plan.md).
 */
@Composable
fun CamShutter(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 72.dp,
    recording: Boolean = false,
    video: Boolean = false,
    /** long press = hold-to-record; [onRelease] fires when that finger lifts */
    onLongPress: (() -> Unit)? = null,
    onRelease: (() -> Unit)? = null,
) {
    val c = PrismThemeAccess.colors
    var pressed by remember { mutableStateOf(false) }
    val haptic = rememberHaptic()
    val click by rememberUpdatedState(onClick)
    val long by rememberUpdatedState(onLongPress)
    val release by rememberUpdatedState(onRelease)
    val inner by animateFloatAsState(if (pressed) .78f else 1f, spring(dampingRatio = .5f, stiffness = Spring.StiffnessMedium), label = "shutter")
    val discSize by animateDpAsState(if (recording) size * .36f else size * .8f, tween(260, easing = Prism.ease), label = "disc")
    val corner by animateDpAsState(if (recording) 8.dp else size, tween(260, easing = Prism.ease), label = "corner")
    Box(
        modifier.size(size)
            .border(size * .05f, if (c.isGlass) Prism.grad else SolidColor(Color.White), CircleShape)
            .pointerInput(Unit) {
                var longFired = false
                detectTapGestures(
                    onPress = {
                        pressed = true
                        tryAwaitRelease()
                        pressed = false
                        if (longFired) { longFired = false; release?.invoke() }
                    },
                    onLongPress = {
                        val l = long
                        if (l != null) { longFired = true; haptic(HapticFeedbackConstants.LONG_PRESS); l() }
                    },
                    onTap = { haptic(HapticFeedbackConstants.CONFIRM); click() },
                )
            },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(discSize).scale(inner).clip(RoundedCornerShape(corner))
                .background(if (recording || video) Color(0xFFE24B4A) else Color.White),
        )
    }
}

/** Zoom pills (.5 / 1× / 2). Selected = warm yellow text on a darker disc, like a good camera app. */
@Composable
fun CamZoomPills(values: List<Float>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier, vertical: Boolean = false) {
    val haptic = rememberHaptic()
    val items: @Composable () -> Unit = {
        values.forEachIndexed { i, v ->
            val on = i == selected
            val label = when { on -> (if (v < 1f) ".${(v * 10).toInt()}" else "${v.toInt()}") + "×"; v < 1f -> ".${(v * 10).toInt()}"; else -> "${v.toInt()}" }
            Box(
                Modifier.size(if (on) 38.dp else 32.dp).clip(CircleShape).background(Color(if (on) 0x80000000L else 0x4D000000L))
                    .clickable(remember { MutableInteractionSource() }, indication = null) { if (!on) { haptic(HapticFeedbackConstants.CLOCK_TICK); onSelect(i) } },
                contentAlignment = Alignment.Center,
            ) {
                PrismText(label, style = TextStyle(fontFamily = PrismFont, fontWeight = FontWeight.SemiBold, fontSize = if (on) 12.sp else 11.sp), color = if (on) Color(0xFFFFD98A) else Color.White)
            }
        }
    }
    if (vertical) androidx.compose.foundation.layout.Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) { items() }
    else Row(modifier, horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically) { items() }
}

// ───────────────────────────── toast ─────────────────────────────

@Composable
fun PrismToast(text: String, ok: Boolean = true, modifier: Modifier = Modifier) {
    val c = PrismThemeAccess.colors
    Row(
        modifier.prismPane(c, CircleShape, strong = true, elevation = 14.dp).padding(horizontal = 18.dp, vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val dot = if (ok) Prism.ok else Prism.danger
        Box(Modifier.size(9.dp).shadow(if (c.isGlass) 6.dp else 0.dp, CircleShape, ambientColor = dot, spotColor = dot).clip(CircleShape).background(dot))
        PrismText(text, style = PrismThemeAccess.type.label.copy(fontSize = 13.5.sp))
    }
}
