package com.prism.cam.ui.kit

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * Prism line icons: 24×24, stroke 1.8, round caps/joins, no fill — the same set as the desktop apps
 * (PrismIconData.kt, generated). Mobile-only icons in [EXTRA] follow the same style.
 */
object PrismIcons {
    private val EXTRA: Map<String, List<PrismIconData.P>> = mapOf(
        "camswitch" to listOf(
            PrismIconData.P("M4 8.5A2.5 2.5 0 0 1 6.5 6h1.7l1.3-2h5l1.3 2h1.7A2.5 2.5 0 0 1 20 8.5v8a2.5 2.5 0 0 1-2.5 2.5h-11A2.5 2.5 0 0 1 4 16.5z", false),
            PrismIconData.P("M9.2 12.2a2.9 2.9 0 0 1 4.9-1.9M14.4 9.1v1.5h-1.5M14.8 13.3a2.9 2.9 0 0 1-4.9 1.9M9.6 16.4v-1.5h1.5", false),
        ),
        "boltoff" to listOf(
            PrismIconData.P("M13 2.5 4.5 13.5h6.5l-1 8 8.5-11h-6.5z", false),
            PrismIconData.P("M3.5 3.5l17 17", false),
        ),
        "boltauto" to listOf(
            PrismIconData.P("M11 2.5 2.5 13.5H9l-1 8 8.5-11H10z", false),
            PrismIconData.P("M15.6 21l2.4-6.2 2.4 6.2M16.4 19h3.2", false),
        ),
        "micoff" to listOf(
            PrismIconData.P("M9 9V6.5a3 3 0 0 1 5.6-1.5M15 10.5v1a3 3 0 0 1-4.6 2.5M6 11.5a6 6 0 0 0 9.6 4.8M18 11.5a6 6 0 0 1-.4 2.1M12 17.5V21M9 21h6M4 4l16 16", false),
        ),
        "dots" to listOf(
            PrismIconData.P("M4.8 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0zM10.7 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0zM16.6 12a1.3 1.3 0 1 0 2.6 0a1.3 1.3 0 1 0-2.6 0z", true),
        ),
        "grid3" to listOf(PrismIconData.P("M9 4v16M15 4v16M4 9h16M4 15h16", false)),
        "translate" to listOf(PrismIconData.P("M4 6h9M8.5 4v2M6 6c.6 3.2 2.8 5.6 6 6.6M11 6c-.7 3.6-3 6.2-6.5 7.4M13 20l3.5-8 3.5 8M14.2 17.4h4.6", false)),
    )

    private val cache = HashMap<String, ImageVector>()

    fun vector(name: String, stroke: Float = 1.8f): ImageVector {
        val key = "$name@$stroke"
        cache[key]?.let { return it }
        val parts = EXTRA[name] ?: PrismIconData.all[name] ?: PrismIconData.all.getValue("x")
        val b = ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
        for (p in parts) {
            b.addPath(
                pathData = PathParser().parsePathString(p.d).toNodes(),
                fill = if (p.filled) SolidColor(Color.Black) else null,
                stroke = SolidColor(Color.Black),
                strokeLineWidth = stroke,
                strokeLineCap = StrokeCap.Round,
                strokeLineJoin = StrokeJoin.Round,
            )
        }
        return b.build().also { cache[key] = it }
    }
}

@Composable
fun PrismIcon(
    name: String,
    modifier: Modifier = Modifier,
    size: Dp = 20.dp,
    tint: Color = PrismThemeAccess.colors.ink,
    stroke: Float = 1.8f,
) {
    Image(
        painter = rememberVectorPainter(PrismIcons.vector(name, stroke)),
        contentDescription = null,
        colorFilter = ColorFilter.tint(tint),
        modifier = modifier.size(size),
    )
}
