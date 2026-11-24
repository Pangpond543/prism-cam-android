package com.prism.cam.ui.kit

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.cam.ui.theme.Look
import com.prism.cam.ui.theme.Prism
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * The Prism mark: three stacked translucent rounded squares (0° / 30° / 60°, scale 1 / .82 / .62) in Prism Cam teal,
 * white camera glyph on top (tools/brand/logos.js "cam"). Glass: the squares swing 0→90→0 every 7 s with the
 * suite ease (CSS @keyframes prism, delays 0 / -2.3 / -4.6 s). Classic: still.
 */
@Composable
fun PrismLogo(size: Dp = 26.dp, modifier: Modifier = Modifier, animate: Boolean = PrismThemeAccess.colors.look == Look.Glass) {
    var ms by remember { mutableLongStateOf(0L) }
    if (animate) {
        LaunchedEffect(Unit) {
            val start = withFrameMillis { it }
            while (true) withFrameMillis { ms = it - start }
        }
    }
    val fills = remember {
        listOf(
            listOf(Color(0xCC1FB5A6), Color(0x996CC7FF)),
            listOf(Color(0xC786E8D6), Color(0x991FB5A6)),
            listOf(Color(0xCC6CC7FF), Color(0xBFFFFFFF)),
        )
    }
    val bases = floatArrayOf(0f, 30f, 60f)
    val scales = floatArrayOf(1f, .82f, .62f)
    val delays = floatArrayOf(0f, 2300f, 4600f)
    val big = size >= 48.dp
    Canvas(modifier.size(size)) {
        val s = this.size.minDimension
        val corner = s * (if (big) 26f / 84f else 9f / 26f)
        val border = s * (if (big) 2f / 84f else 1.5f / 26f)
        for (i in 0..2) {
            val swing = if (animate) swing(((ms + delays[i]) % 7000f) / 7000f) else 0f
            val k = scales[i]
            rotate(bases[i] + swing, pivot = center) {
                scale(k, pivot = center) {
                    // soft teal shadow under the first (largest) square
                    if (i == 0) drawRoundRect(
                        color = Prism.camMid.copy(alpha = .22f), topLeft = Offset(0f, s * .06f),
                        size = Size(s, s), cornerRadius = CornerRadius(corner),
                    )
                    drawRoundRect(
                        brush = Brush.linearGradient(fills[i], start = Offset.Zero, end = Offset(s, s)),
                        cornerRadius = CornerRadius(corner),
                    )
                    drawRoundRect(
                        color = Color.White.copy(alpha = .95f), cornerRadius = CornerRadius(corner),
                        style = Stroke(border / k),
                    )
                }
            }
        }
        // camera glyph (100×100 box, logos.js), with a deep-teal drop shadow
        val u = s / 100f
        fun glyph(dy: Float, white: Color, hole: Color) = translate(0f, dy) {
            val top = Path().apply { moveTo(38 * u, 33 * u); lineTo(43 * u, 27 * u); lineTo(57 * u, 27 * u); lineTo(62 * u, 33 * u); close() }
            drawPath(top, white)
            drawRoundRect(white, topLeft = Offset(26 * u, 33 * u), size = Size(48 * u, 36 * u), cornerRadius = CornerRadius(9 * u))
            drawCircle(hole, radius = 11 * u, center = Offset(50 * u, 51 * u))
            drawCircle(white, radius = 5.5f * u, center = Offset(50 * u, 51 * u))
        }
        glyph(s * .02f, Prism.camDeep.copy(alpha = .55f), Prism.camDeep.copy(alpha = .55f))
        glyph(0f, Color.White, Prism.camDeep)
    }
}

/** keyframes 0%,100% → 0°, 50% → 90°, each half eased with cubic-bezier(.22,1,.36,1) */
private fun swing(t: Float): Float = if (t < .5f) 90f * Prism.ease.transform(t * 2f) else 90f * (1f - Prism.ease.transform((t - .5f) * 2f))
