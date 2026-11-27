package com.prism.cam.ui.kit

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.layout.positionInParent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp

/**
 * Sideways-scrolling row whose cut-off edge fades out, so a narrow phone screen shows there is more to the side
 * (a hard cut in the middle of a chip looked like a layout bug). Fades only on a side that can still scroll.
 */
@Composable
fun Modifier.hScrollFade(state: ScrollState = rememberScrollState()): Modifier =
    this.graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = 28.dp.toPx().coerceAtMost(size.width / 3)
            if (state.canScrollBackward) drawRect(
                Brush.horizontalGradient(listOf(Color.Transparent, Color.Black), startX = 0f, endX = w),
                Offset.Zero, Size(w, size.height), blendMode = BlendMode.DstIn,
            )
            if (state.canScrollForward) drawRect(
                Brush.horizontalGradient(listOf(Color.Black, Color.Transparent), startX = size.width - w, endX = size.width),
                Offset(size.width - w, 0f), Size(w, size.height), blendMode = BlendMode.DstIn,
            )
        }
        .horizontalScroll(state)

/**
 * Tab chips in one fading, sideways-scrolling row. The chosen tab is always scrolled into view (on a 360 dp phone
 * only ~3 of the ✦ tabs fit, and a tab chosen earlier or from a test act was off screen).
 */
@Composable
fun PrismTabRow(tabs: List<Pair<String, String?>>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val state = rememberScrollState()
    val spans = remember { mutableStateMapOf<Int, Pair<Int, Int>>() }   // index → (left, width) in content px
    val pad = with(LocalDensity.current) { 28.dp.roundToPx() }
    LaunchedEffect(selected, spans[selected], state.viewportSize) {
        val (x, w) = spans[selected] ?: return@LaunchedEffect
        val view = state.viewportSize
        if (view <= 0) return@LaunchedEffect
        val target = when {
            x - pad < state.value -> x - pad
            x + w + pad > state.value + view -> x + w + pad - view
            else -> return@LaunchedEffect
        }
        state.animateScrollTo(target.coerceIn(0, state.maxValue))
    }
    Row(modifier.hScrollFade(state), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        tabs.forEachIndexed { i, (label, icon) ->
            Box(Modifier.onPlaced { spans[i] = it.positionInParent().x.toInt() to it.size.width }) {
                PrismChip(label, selected == i, { onSelect(i) }, icon = icon)
            }
        }
    }
}
