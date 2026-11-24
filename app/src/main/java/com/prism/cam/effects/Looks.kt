package com.prism.cam.effects

import android.graphics.ColorMatrix

/**
 * Colour looks. Each is an Android ColorMatrix (4×5, offsets 0..255) plus vignette / grain, so the very same numbers
 * drive the GPU shader (preview + video) and the CPU path (photos). Thai names are the source text (T()).
 */
data class Look(val id: String, val th: String, val m: FloatArray, val vignette: Float = 0f, val grain: Float = 0f)

object Looks {
    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)

    private fun cm(block: ColorMatrix.() -> Unit): FloatArray = ColorMatrix().apply(block).array.copyOf()
    private fun sat(s: Float) = ColorMatrix().apply { setSaturation(s) }
    private fun scale(r: Float, g: Float, b: Float) = ColorMatrix().apply { setScale(r, g, b, 1f) }
    private fun offset(r: Float, g: Float, b: Float) = ColorMatrix(floatArrayOf(1f, 0f, 0f, 0f, r, 0f, 1f, 0f, 0f, g, 0f, 0f, 1f, 0f, b, 0f, 0f, 0f, 1f, 0f))
    private fun contrast(c: Float) = ColorMatrix(floatArrayOf(c, 0f, 0f, 0f, 128f * (1 - c), 0f, c, 0f, 0f, 128f * (1 - c), 0f, 0f, c, 0f, 128f * (1 - c), 0f, 0f, 0f, 1f, 0f))

    val all: List<Look> = listOf(
        Look("none", "ธรรมชาติ", IDENTITY),
        Look("vivid", "สดใส", cm { set(sat(1.35f)); postConcat(contrast(1.08f)) }),
        Look("warm", "อบอุ่น", cm { set(scale(1.08f, 1.0f, .88f)); postConcat(offset(6f, 2f, -4f)); postConcat(sat(1.08f)) }, vignette = .18f),
        Look("cool", "เย็น", cm { set(scale(.92f, 1.0f, 1.10f)); postConcat(offset(-4f, 2f, 8f)) }),
        Look("prism", "Prism", cm { set(sat(.9f)); postConcat(scale(1.02f, .97f, 1.08f)); postConcat(offset(14f, 6f, 18f)); postConcat(contrast(.92f)) }, vignette = .12f),
        Look("film", "ฟิล์ม", cm { set(contrast(.86f)); postConcat(offset(12f, 8f, 2f)); postConcat(sat(.82f)); postConcat(scale(1.03f, 1.0f, .95f)) }, vignette = .32f, grain = .045f),
        Look("fade", "ซีด", cm { set(contrast(.78f)); postConcat(offset(18f, 18f, 22f)); postConcat(sat(.72f)) }),
        Look("teal", "ภาพยนตร์", cm {
            set(ColorMatrix(floatArrayOf(1.10f, -.05f, -.05f, 0f, 4f, -.04f, 1.02f, .02f, 0f, 0f, -.08f, .06f, 1.10f, 0f, 10f, 0f, 0f, 0f, 1f, 0f)))
            postConcat(contrast(1.10f))
        }, vignette = .25f),
        Look("mono", "ขาวดำ", cm { set(sat(0f)); postConcat(contrast(1.12f)) }, vignette = .2f),
        Look("noir", "ดำจัด", cm { set(sat(0f)); postConcat(contrast(1.45f)) }, vignette = .38f, grain = .05f),
        Look("sepia", "ซีเปีย", cm { setSaturation(0f); postConcat(scale(1.07f, .95f, .78f)); postConcat(offset(18f, 8f, -6f)) }, vignette = .22f),
    )

    fun of(id: String?) = all.firstOrNull { it.id == id } ?: all[0]
}
