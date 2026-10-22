package com.example.camerafilterapp.filter

import kotlin.math.PI
import kotlin.math.pow
import kotlin.math.sin

enum class LookCategory(val label: String) {
    SOCIAL("Social"),
    FILM("Film"),
    PORTRAIT("Portrait"),
    MOOD("Mood"),
    VIVID("Vivid"),
    MONO("Mono"),
    RETRO("Retro"),
}

/** Linear RGB triple in 0..1, used for tints and tones. */
data class Rgb(val r: Float, val g: Float, val b: Float) {
    internal val luma get() = 0.2126f * r + 0.7152f * g + 0.0722f * b
}

/** A screen-blended coloured glow entering from [x], [y] (0..1, origin top-left). */
data class LightLeak(val color: Rgb, val strength: Float, val x: Float, val y: Float)

/**
 * A colour grade plus optional per-pixel effects.
 *
 * Everything in the "grade" section is a pure per-pixel colour function, baked into a 3D LUT by
 * [LutCache] and applied on the GPU with one lookup. A look can instead take its LUT from a
 * designer-made image ([lutImage]), in which case the grade parameters are ignored. The "effects"
 * section depends on pixel position or randomness, so it runs directly in the fragment shader.
 *
 * Ranges are roughly -1..1 unless noted; 0 means "no change".
 */
data class Look(
    val id: String,
    val name: String,
    val category: LookCategory?,
    // Grade
    val exposure: Float = 0f, // stops
    val temperature: Float = 0f, // cool .. warm
    val tint: Float = 0f, // green .. magenta
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val lift: Rgb = Rgb(0f, 0f, 0f), // colour wheel: shadows, -0.2..0.2
    val gamma: Rgb = Rgb(1f, 1f, 1f), // colour wheel: midtones, ~0.8..1.2
    val gain: Rgb = Rgb(1f, 1f, 1f), // colour wheel: highlights, ~0.8..1.2
    val shadowTone: Rgb? = null,
    val shadowToneStrength: Float = 0f,
    val highlightTone: Rgb? = null,
    val highlightToneStrength: Float = 0f,
    val mono: Rgb? = null, // channel-mixer weights; non-null converts to black and white
    val fade: Float = 0f, // 0..0.3, lifts blacks
    val lutImage: String? = null, // asset path of a PNG lookup image (see LutCache); replaces the grade
    // Effects (shader only)
    val clarity: Float = 0f, // 0..1, sharpens fine detail so the image reads crisp
    val softness: Float = 0f, // 0..1, edge-preserving smoothing (skin, fine texture) plus a soft highlight glow
    val vignette: Float = 0f, // 0..1
    val grain: Float = 0f, // 0..0.2
    val aberration: Float = 0f, // 0..0.01, chromatic fringe at the edges
    val lightLeak: LightLeak? = null,
) {
    val isIdentity: Boolean get() = this == ORIGINAL

    /** Applies the grade to [rgb] (0..1) in place. */
    fun grade(rgb: FloatArray) {
        var r = rgb[0]
        var g = rgb[1]
        var b = rgb[2]

        val e = 2f.pow(exposure)
        r *= e; g *= e; b *= e

        r *= 1f + TEMPERATURE_GAIN * temperature
        b *= 1f - TEMPERATURE_GAIN * temperature
        g *= 1f - TINT_GAIN * tint

        r = wheel(r, lift.r, gamma.r, gain.r)
        g = wheel(g, lift.g, gamma.g, gain.g)
        b = wheel(b, lift.b, gamma.b, gain.b)

        var l = luma(r, g, b)
        val shadowW = (1f - l).coerceIn(0f, 1f).let { it * it }
        val highlightW = l.coerceIn(0f, 1f).let { it * it }
        val tonal = (shadows * shadowW + highlights * highlightW) * TONAL_GAIN
        r += tonal; g += tonal; b += tonal

        r = sCurve(r); g = sCurve(g); b = sCurve(b)

        l = luma(r, g, b)
        val currentSat = maxOf(r, g, b) - minOf(r, g, b)
        val satFactor = 1f + saturation + vibrance * (1f - currentSat.coerceIn(0f, 1f))
        r = l + (r - l) * satFactor
        g = l + (g - l) * satFactor
        b = l + (b - l) * satFactor

        if (mono != null) {
            val v = r * mono.r + g * mono.g + b * mono.b
            r = v; g = v; b = v
        }

        l = luma(r, g, b).coerceIn(0f, 1f)
        if (shadowTone != null) {
            val w = (1f - smoothstep(0f, 0.6f, l)) * shadowToneStrength * SPLIT_TONE_GAIN
            r += (shadowTone.r - shadowTone.luma) * w
            g += (shadowTone.g - shadowTone.luma) * w
            b += (shadowTone.b - shadowTone.luma) * w
        }
        if (highlightTone != null) {
            val w = smoothstep(0.4f, 1f, l) * highlightToneStrength * SPLIT_TONE_GAIN
            r += (highlightTone.r - highlightTone.luma) * w
            g += (highlightTone.g - highlightTone.luma) * w
            b += (highlightTone.b - highlightTone.luma) * w
        }

        r = fade + r.coerceIn(0f, 1f) * (1f - fade)
        g = fade + g.coerceIn(0f, 1f) * (1f - fade)
        b = fade + b.coerceIn(0f, 1f) * (1f - fade)

        rgb[0] = r; rgb[1] = g; rgb[2] = b
    }

    private fun wheel(x: Float, lift: Float, gamma: Float, gain: Float): Float {
        val v = gain * (x + lift * (1f - x))
        return v.coerceAtLeast(0f).pow(1f / gamma)
    }

    /** Smooth S-curve that keeps 0 and 1 fixed; the strength is clamped below 1 so it stays monotonic. */
    private fun sCurve(x: Float): Float {
        val c = x.coerceIn(0f, 1f)
        val k = (contrast * CONTRAST_GAIN).coerceIn(-0.95f, 0.95f)
        return c - k * sin(2.0 * PI * c).toFloat() / (2f * PI.toFloat())
    }

    private fun luma(r: Float, g: Float, b: Float) = 0.2126f * r + 0.7152f * g + 0.0722f * b

    private fun smoothstep(e0: Float, e1: Float, x: Float): Float {
        val t = ((x - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    companion object {
        val ORIGINAL = Look(id = "original", name = "Original", category = null)

        // Response of the grade controls. Tuned so a value of about 0.3 is clearly visible on a phone screen.
        private const val TEMPERATURE_GAIN = 0.3f // 0.25 warms red/blue by 7.5% each
        private const val TINT_GAIN = 0.2f
        private const val TONAL_GAIN = 0.5f
        private const val CONTRAST_GAIN = 2f
        private const val SPLIT_TONE_GAIN = 2f
    }
}

/** User-adjustable settings applied on top of a look. */
data class FilterSettings(
    val look: Look = Look.ORIGINAL,
    val intensity: Float = 1f, // 0..1, blends between original and graded
    val brightness: Float = 0f, // MIN_BRIGHTNESS..MAX_BRIGHTNESS, additive
) {
    companion object {
        const val MIN_BRIGHTNESS = -0.3f
        const val MAX_BRIGHTNESS = 0.3f
    }
}
