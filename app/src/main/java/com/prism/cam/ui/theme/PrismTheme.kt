package com.prism.cam.ui.theme

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.prism.cam.R

/**
 * Prism suite design tokens, translated 1:1 from the desktop CSS (prism-size/app/renderer/css/style.css:
 * :root, html[data-theme=dark], html[data-look=classic]). Classic = flat solid panels, nothing loops.
 * Glass = frosted translucent panes over the flowing Prism gradient.
 */
enum class Look { Classic, Glass }

@Immutable
data class PrismColors(
    val dark: Boolean,
    val look: Look,
    val ink: Color,
    val ink2: Color,
    val ink3: Color,
    /** page / body colour (classic) or the base under the moving gradient (glass) */
    val page: Color,
    /** glass pane fill stops (135°) — classic uses [panel] instead */
    val glass: List<Color>,
    val glassStrong: List<Color>,
    /** 1.2 dp gradient rim of a glass pane */
    val rim: List<Color>,
    val panel: Color,
    val panelStrong: Color,
    /** classic hairline + control fill + pressed wash */
    val line: Color,
    val fill: Color,
    val hover: Color,
    val shadow: Color,
    /** track of a segmented switch */
    val segTrack: Color,
) {
    val isGlass get() = look == Look.Glass
}

object Prism {
    val accent = Color(0xFF7C8CFF)
    val accent2 = Color(0xFFFF9EC7)
    val accent3 = Color(0xFF7FE3D0)
    val danger = Color(0xFFFF6B8B)
    val ok = Color(0xFF3FCFA4)
    /** --grad: 120deg, #8ea2ff, #c79bff 45%, #ff9ec7 80%, #ffc79a */
    val gradStops = arrayOf(
        0f to Color(0xFF8EA2FF), 0.45f to Color(0xFFC79BFF), 0.80f to Color(0xFFFF9EC7), 1f to Color(0xFFFFC79A),
    )
    val grad: Brush get() = Brush.linearGradient(*gradStops)
    fun gradText(): Brush = Brush.horizontalGradient(*gradStops)

    /** Prism Cam brand (tools/brand/logos.js "cam"): deep, mid, light, accent */
    val camDeep = Color(0xFF0B6F73)
    val camMid = Color(0xFF1FB5A6)
    val camLight = Color(0xFF86E8D6)
    val camAccent = Color(0xFF6CC7FF)

    /** camera-screen controls are always white on the picture, whatever the theme */
    val onPicture = Color.White

    val ease = CubicBezierEasing(.22f, 1f, .36f, 1f)
    val spring = CubicBezierEasing(.34f, 1.56f, .64f, 1f)

    const val AUTHOR = "Thanakorn Damrikitjaroen"
    const val MODEL = "Claude Fable 5.2"
}

fun prismColors(dark: Boolean, look: Look): PrismColors = when {
    !dark && look == Look.Glass -> PrismColors(
        dark = false, look = look,
        ink = Color(0xFF232A47), ink2 = Color(0xFF4A5376), ink3 = Color(0xFF7B84A6),
        page = Color(0xFFE9EEFC),
        glass = listOf(Color(0x66FFFFFF), Color(0x1AFFFFFF), Color(0x38FFFFFF)),
        glassStrong = listOf(Color(0xB8FFFFFF), Color(0x61FFFFFF), Color(0x80FFFFFF)),
        rim = listOf(Color(0xFFFFFFFF), Color(0x40FFFFFF), Color(0x14FFFFFF), Color(0xD9FFFFFF)),
        panel = Color(0x8CFFFFFF), panelStrong = Color(0xD9FFFFFF),
        line = Color(0x9EFFFFFF), fill = Color(0x9EFFFFFF), hover = Color(0xD9FFFFFF),
        shadow = Color(0x615668A8), segTrack = Color(0x6BFFFFFF),
    )
    dark && look == Look.Glass -> PrismColors(
        dark = true, look = look,
        ink = Color(0xFFEEF1FF), ink2 = Color(0xFFB6BFE6), ink3 = Color(0xFF8690BD),
        page = Color(0xFF0B0F24),
        glass = listOf(Color(0x337887DC), Color(0x29283060), Color(0x1F7887DC)),
        glassStrong = listOf(Color(0xE0343C70), Color(0xD11E244C), Color(0xDB2C3464)),
        rim = listOf(Color(0x80FFFFFF), Color(0x14FFFFFF), Color(0x08FFFFFF), Color(0x52FFFFFF)),
        panel = Color(0x1FFFFFFF), panelStrong = Color(0x33FFFFFF),
        line = Color(0x2EFFFFFF), fill = Color(0x14FFFFFF), hover = Color(0x21FFFFFF),
        shadow = Color(0xB3000000), segTrack = Color(0x14FFFFFF),
    )
    !dark -> PrismColors(
        dark = false, look = look,
        ink = Color(0xFF232A47), ink2 = Color(0xFF4A5376), ink3 = Color(0xFF7B84A6),
        page = Color(0xFFF2F3F5),
        glass = listOf(Color.White, Color.White, Color.White),
        glassStrong = listOf(Color.White, Color.White, Color.White),
        rim = listOf(Color(0x17141828), Color(0x17141828)),
        panel = Color.White, panelStrong = Color.White,
        line = Color(0x17141828), fill = Color(0xFFEEF0F4), hover = Color(0x0F141828),
        shadow = Color(0x29141828), segTrack = Color(0xFFEEF0F4),
    )
    else -> PrismColors(
        dark = true, look = look,
        ink = Color(0xFFEEF1FF), ink2 = Color(0xFFB6BFE6), ink3 = Color(0xFF8690BD),
        page = Color(0xFF1E1F22),
        glass = listOf(Color(0xFF2B2D31), Color(0xFF2B2D31), Color(0xFF2B2D31)),
        glassStrong = listOf(Color(0xFF313338), Color(0xFF313338), Color(0xFF313338)),
        rim = listOf(Color(0x12FFFFFF), Color(0x12FFFFFF)),
        panel = Color(0xFF2B2D31), panelStrong = Color(0xFF313338),
        line = Color(0x12FFFFFF), fill = Color(0xFF1E1F22), hover = Color(0x12FFFFFF),
        shadow = Color(0x8C000000), segTrack = Color(0xFF1E1F22),
    )
}

/** Loopless Thai face (ไม่มีหัว) — IBM Plex Sans Thai, bundled (OFL, licenses/). Latin comes from it too. */
val PrismFont = FontFamily(
    Font(R.font.plex_thai_regular, FontWeight.Normal),
    Font(R.font.plex_thai_medium, FontWeight.Medium),
    Font(R.font.plex_thai_semibold, FontWeight.SemiBold),
    Font(R.font.plex_thai_bold, FontWeight.Bold),
)

@Immutable
data class PrismType(
    val body: TextStyle,
    val label: TextStyle,
    val caption: TextStyle,
    val title: TextStyle,
    val hero: TextStyle,
)

private val baseType = PrismType(
    body = TextStyle(fontFamily = PrismFont, fontSize = 15.sp, lineHeight = 23.sp),
    label = TextStyle(fontFamily = PrismFont, fontSize = 14.sp, lineHeight = 21.sp, fontWeight = FontWeight.SemiBold),
    caption = TextStyle(fontFamily = PrismFont, fontSize = 12.5.sp, lineHeight = 19.sp),
    title = TextStyle(fontFamily = PrismFont, fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.SemiBold),
    hero = TextStyle(fontFamily = PrismFont, fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.Bold),
)

val LocalPrismColors = staticCompositionLocalOf { prismColors(false, Look.Classic) }
val LocalPrismType = staticCompositionLocalOf { baseType }

@Composable
fun PrismTheme(dark: Boolean, look: Look, content: @Composable () -> Unit) {
    CompositionLocalProvider(
        LocalPrismColors provides prismColors(dark, look),
        LocalPrismType provides baseType,
        content = content,
    )
}

object PrismThemeAccess {
    val colors: PrismColors @Composable get() = LocalPrismColors.current
    val type: PrismType @Composable get() = LocalPrismType.current
}
