package com.prism.cam.ui.kit

import android.graphics.RuntimeShader
import android.os.Build
import androidx.annotation.RequiresApi
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ShaderBrush
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Shader
import com.prism.cam.ui.theme.PrismThemeAccess

/**
 * Page background. Classic: the plain page colour. Glass: the flowing Prism pastel gradient
 * (desktop prism-bg.js idea: domain-warped fbm of ice / lilac / rose / mint / cream, caustic band, periwinkle
 * vignette, deep-indigo night that cross-fades) as an AGSL shader on Android 13+; static gradient below that.
 * Redrawn ~30×/s, only the draw phase (no recomposition). Never used behind the live camera picture.
 */
@Composable
fun PrismBackground(modifier: Modifier = Modifier) {
    val c = PrismThemeAccess.colors
    if (!c.isGlass) {
        Box(modifier.fillMaxSize().background(c.page))
        return
    }
    val night by animateFloatAsState(if (c.dark) 1f else 0f, tween(900), label = "night")
    if (Build.VERSION.SDK_INT >= 33) {
        ShaderBackground(modifier, night)
    } else {
        BlobBackground(modifier, night)
    }
}

/**
 * Android 8–12 (no AGSL): the same pastel family as soft light blobs drifting slowly over the base gradient
 * (each blob a radial gradient on a slow Lissajous path). ~30 redraws/s, draw phase only — cheap on any GPU.
 */
@Composable
private fun BlobBackground(modifier: Modifier, night: Float) {
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        var last = 0L
        while (true) withFrameMillis { now ->
            if (now - last >= 33) { last = now; t = (now - start) / 1000f }
        }
    }
    Box(
        modifier.fillMaxSize().drawBehind {
            fun mix(a: Color, b: Color) = androidx.compose.ui.graphics.lerp(a, b, night)
            drawRect(
                Brush.linearGradient(
                    0f to mix(Color(0xFFDBE6FF), Color(0xFF0B1030)), .45f to mix(Color(0xFFF1DCFF), Color(0xFF15123A)),
                    .75f to mix(Color(0xFFFFE3EC), Color(0xFF1C1130)), 1f to mix(Color(0xFFE0FBF2), Color(0xFF0B2228)),
                    start = Offset.Zero, end = Offset(size.width, size.height),
                ),
            )
            val r = maxOf(size.width, size.height) * .55f
            val blobs = arrayOf(
                Triple(Color(0xFFBAD4FF), .21f, 0f), Triple(Color(0xFFD6BFFF), .17f, 1.7f), Triple(Color(0xFFFFC2DE), .13f, 3.1f),
                Triple(Color(0xFFB3F2E6), .19f, 4.4f), Triple(Color(0xFFFFE3BD), .15f, 5.6f),
            )
            for ((col, speed, ph) in blobs) {
                val cx = size.width * (.5f + .42f * kotlin.math.sin(t * speed * .35f + ph))
                val cy = size.height * (.5f + .42f * kotlin.math.cos(t * speed * .27f + ph * 1.3f))
                val tint = if (night > 0f) mix(col, col.copy(red = col.red * .22f, green = col.green * .2f, blue = col.blue * .42f)) else col
                drawCircle(
                    Brush.radialGradient(listOf(tint.copy(alpha = .85f - .35f * night), tint.copy(alpha = 0f)), center = Offset(cx, cy), radius = r),
                    radius = r, center = Offset(cx, cy),
                )
            }
        },
    )
}

@RequiresApi(33)
@Composable
private fun ShaderBackground(modifier: Modifier, night: Float) {
    val shader = remember { RuntimeShader(PRISM_BG_AGSL) }
    val brush = remember(shader) { object : ShaderBrush() { override fun createShader(size: androidx.compose.ui.geometry.Size): Shader = shader } }
    var t by remember { mutableFloatStateOf(0f) }
    LaunchedEffect(Unit) {
        val start = withFrameMillis { it }
        var last = 0L
        while (true) withFrameMillis { now ->
            if (now - last >= 33) { last = now; t = (now - start) / 1000f }
        }
    }
    Box(
        modifier.fillMaxSize().drawBehind {
            shader.setFloatUniform("res", size.width, size.height)
            shader.setFloatUniform("t", t)
            shader.setFloatUniform("dark", night)
            drawRect(brush)
        },
    )
}

private const val PRISM_BG_AGSL = """
uniform float2 res;
uniform float t;
uniform float dark;

float hash(float2 p) { p = fract(p * float2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(float2 p) {
    float2 i = floor(p); float2 f = fract(p); float2 u = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + float2(1.0, 0.0)), u.x), mix(hash(i + float2(0.0, 1.0)), hash(i + float2(1.0, 1.0)), u.x), u.y);
}
float fbm(float2 p) {
    float v = 0.0; float a = 0.5;
    for (int i = 0; i < 4; i++) { v += a * noise(p); p = p * 2.03 + float2(1.7, 9.2); a *= 0.5; }
    return v;
}
half4 main(float2 fc) {
    float2 uv = fc / res;
    float2 p = uv * float2(res.x / res.y, 1.0) * 1.6;
    float tt = t * 0.035;
    float2 q = float2(fbm(p + float2(0.0, tt)), fbm(p + float2(5.2, 1.3) - tt));
    float2 r = float2(fbm(p + 3.0 * q + float2(1.7, 9.2) + tt * 1.3), fbm(p + 3.0 * q + float2(8.3, 2.8) - tt));
    float f = fbm(p + 2.5 * r);
    float3 ice = float3(0.729, 0.831, 1.0);
    float3 lilac = float3(0.839, 0.749, 1.0);
    float3 rose = float3(1.0, 0.761, 0.871);
    float3 mint = float3(0.702, 0.949, 0.902);
    float3 cream = float3(1.0, 0.890, 0.741);
    float3 c = mix(ice, lilac, smoothstep(0.2, 0.6, f));
    c = mix(c, rose, smoothstep(0.45, 0.85, r.x));
    c = mix(c, mint, smoothstep(0.55, 0.95, q.y) * 0.7);
    c = mix(c, cream, smoothstep(0.6, 1.0, r.y) * 0.5);
    float band = sin(uv.x * 3.0 + uv.y * 2.0 + f * 6.0 + t * 0.25) * 0.5 + 0.5;
    c += float3(pow(band, 8.0) * 0.08);
    c.r += pow(band, 10.0) * 0.012; c.b += pow(1.0 - band, 12.0) * 0.012;
    float v = smoothstep(1.2, 0.3, length(uv - 0.5) * 1.4);
    c = mix(float3(0.66, 0.72, 0.95), c, 0.75 + 0.25 * v);
    float3 night = float3(0.035, 0.047, 0.129) + c * float3(0.16, 0.15, 0.30) * (0.6 + 0.6 * f);
    c = mix(c, night, dark);
    c += (hash(fc + fract(t)) - 0.5) * (2.0 / 255.0);
    return half4(half3(c), 1.0);
}
"""
