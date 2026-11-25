package com.prism.cam.ui.kit

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess
import kotlin.math.roundToInt

/**
 * Prism slider: label + value on top, a thin track with the Prism gradient up to the thumb. Drag or tap anywhere on
 * the track. [onDone] when the finger lifts (one undo step per drag). [center] draws the fill from the middle
 * (for −/+ values like brightness).
 */
@Composable
fun PrismSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onDone: () -> Unit = {},
    format: (Float) -> String = { ((it * 100).roundToInt()).toString() },
    center: Boolean = false,
) {
    val c = PrismThemeAccess.colors
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val lo = range.start; val hi = range.endInclusive
    val f = ((value - lo) / (hi - lo)).coerceIn(0f, 1f)
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            PrismText(label, Modifier.weight(1f), style = PrismThemeAccess.type.caption, color = c.ink2)
            PrismText(format(value), style = PrismThemeAccess.type.caption.copy(fontSize = 12.sp), color = c.ink3)
        }
        BoxWithConstraints(Modifier.fillMaxWidth().height(30.dp)) {
            val wPx = constraints.maxWidth.toFloat().coerceAtLeast(1f)
            fun at(x: Float) = lo + (x / wPx).coerceIn(0f, 1f) * (hi - lo)
            val thumb = 22.dp
            val mw = maxWidth
            Box(
                Modifier.fillMaxWidth().height(30.dp)
                    .pointerInput(lo, hi) { detectTapGestures { change(at(it.x)); done() } }
                    .pointerInput(lo, hi) {
                        detectHorizontalDragGestures(onDragEnd = { done() }, onDragCancel = { done() }) { ch, _ -> ch.consume(); change(at(ch.position.x)) }
                    },
            ) {
                Box(Modifier.align(Alignment.CenterStart).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)).background(c.line))
                val from = if (center) .5f else 0f
                val a = minOf(from, f); val b = maxOf(from, f)
                Box(Modifier.align(Alignment.CenterStart).offset(mw * a).width(mw * (b - a)).height(4.dp).clip(RoundedCornerShape(2.dp)).background(Prism.grad))
                Box(
                    Modifier.align(Alignment.CenterStart).offset((mw - thumb) * f).size(thumb).clip(CircleShape)
                        .background(androidx.compose.ui.graphics.Color.White).border(2.dp, Prism.grad, CircleShape),
                )
            }
        }
    }
}

/** small round icon button for panels */
@Composable
fun PrismMiniButton(icon: String, onClick: () -> Unit, modifier: Modifier = Modifier, on: Boolean = false, enabled: Boolean = true) {
    val c = PrismThemeAccess.colors
    Box(
        modifier.size(34.dp).clip(CircleShape).background(if (on) androidx.compose.ui.graphics.Color(0x337C8CFF) else c.hover)
            .then(if (enabled) Modifier.pointerInput(onClick) { detectTapGestures { onClick() } } else Modifier),
        contentAlignment = Alignment.Center,
    ) { PrismIcon(icon, size = 17.dp, tint = if (!enabled) c.ink3.copy(alpha = .4f) else if (on) Prism.accent else c.ink2) }
}

/** caption-styled section title */
@Composable
fun PrismLabel(text: String) {
    PrismText(text, style = PrismThemeAccess.type.caption.copy(fontSize = 12.sp, letterSpacing = .3.sp), color = PrismThemeAccess.colors.ink3)
}

@Suppress("unused")
private val tiny = TextStyle(fontSize = 10.sp)
