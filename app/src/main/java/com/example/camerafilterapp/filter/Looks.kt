package com.example.camerafilterapp.filter

/** The filter catalogue, in display order. All looks are original to this app. */
object Looks {

    private val TEAL = Rgb(0.05f, 0.45f, 0.55f)
    private val ORANGE = Rgb(1f, 0.6f, 0.3f)
    private val GOLD = Rgb(1f, 0.8f, 0.45f)
    private val PEACH = Rgb(1f, 0.72f, 0.6f)
    private val PINK = Rgb(1f, 0.55f, 0.7f)
    private val PURPLE = Rgb(0.45f, 0.3f, 0.7f)
    private val NAVY = Rgb(0.15f, 0.25f, 0.55f)
    private val GREEN = Rgb(0.3f, 0.55f, 0.35f)
    private val CYAN = Rgb(0.4f, 0.75f, 0.8f)
    private val BROWN = Rgb(0.45f, 0.3f, 0.18f)
    private val CREAM = Rgb(1f, 0.92f, 0.75f)
    private val MAGENTA = Rgb(0.85f, 0.4f, 0.75f)

    private val LUMA_MIX = Rgb(0.2126f, 0.7152f, 0.0722f)
    private val RED_FILTER_MIX = Rgb(0.6f, 0.35f, 0.05f)

    val all: List<Look> = listOf(
        Look.ORIGINAL,

        // Social: bright, clean everyday looks in the style of social-app filters. Little grain and
        // light clarity, since they are mostly used on faces and shared at small sizes.
        Look(
            "bright", "Bright", LookCategory.SOCIAL,
            exposure = 0.2f, contrast = 0.12f, vibrance = 0.3f, highlights = -0.15f, shadows = 0.15f, clarity = 0.2f,
        ),
        Look(
            "glow", "Glow", LookCategory.SOCIAL,
            exposure = 0.15f, temperature = 0.15f, contrast = -0.08f, highlights = -0.2f, shadows = 0.2f,
            highlightTone = PEACH, highlightToneStrength = 0.3f, fade = 0.03f, clarity = 0.05f,
        ),
        Look(
            "crisp", "Crisp", LookCategory.SOCIAL,
            contrast = 0.3f, saturation = 0.2f, temperature = -0.12f, highlights = -0.1f,
            highlightTone = CYAN, highlightToneStrength = 0.15f, shadowTone = NAVY, shadowToneStrength = 0.15f,
            clarity = 0.45f,
        ),
        Look(
            "warmth", "Warmth", LookCategory.SOCIAL,
            temperature = 0.3f, tint = 0.05f, vibrance = 0.25f, contrast = 0.1f, shadows = 0.05f,
            highlightTone = GOLD, highlightToneStrength = 0.25f, clarity = 0.2f,
        ),
        Look(
            "rosy", "Rosy", LookCategory.SOCIAL,
            exposure = 0.12f, tint = 0.22f, temperature = 0.05f, saturation = 0.05f, shadows = 0.1f,
            highlightTone = PINK, highlightToneStrength = 0.35f, clarity = 0.1f,
        ),
        Look(
            "breeze", "Breeze", LookCategory.SOCIAL,
            exposure = 0.12f, temperature = -0.2f, saturation = -0.1f, contrast = -0.05f,
            highlightTone = CYAN, highlightToneStrength = 0.2f, shadowTone = Rgb(0.5f, 0.6f, 0.9f),
            shadowToneStrength = 0.2f, fade = 0.05f, clarity = 0.15f,
        ),
        Look(
            "vibe", "Vibe", LookCategory.SOCIAL,
            saturation = 0.3f, contrast = 0.3f, highlights = -0.2f, shadows = 0.15f, vignette = 0.25f, clarity = 0.4f,
        ),
        Look(
            "matte", "Matte", LookCategory.SOCIAL,
            contrast = -0.1f, saturation = -0.15f, shadows = 0.1f,
            shadowTone = TEAL, shadowToneStrength = 0.2f, highlightTone = CREAM, highlightToneStrength = 0.2f,
            fade = 0.1f, clarity = 0.15f,
        ),
        Look(
            "sunkissed", "Sunkissed", LookCategory.SOCIAL,
            exposure = 0.05f, temperature = 0.35f, vibrance = 0.2f, contrast = 0.1f, highlights = -0.15f,
            highlightTone = ORANGE, highlightToneStrength = 0.2f, clarity = 0.15f,
            lightLeak = LightLeak(GOLD, 0.25f, 1f, 0f),
        ),

        // Film
        Look(
            "golden_film", "Golden", LookCategory.FILM,
            temperature = 0.35f, contrast = 0.1f, highlights = -0.2f, vibrance = 0.2f,
            shadowTone = TEAL, shadowToneStrength = 0.15f, highlightTone = GOLD, highlightToneStrength = 0.3f,
            fade = 0.03f, grain = 0.025f, vignette = 0.15f, clarity = 0.25f,
        ),
        Look(
            "faded_film", "Faded", LookCategory.FILM,
            contrast = -0.15f, saturation = -0.25f, shadows = 0.15f,
            lift = Rgb(0.02f, 0.04f, 0.08f), fade = 0.1f, grain = 0.03f, clarity = 0.15f,
        ),
        Look(
            "cine", "Cine", LookCategory.FILM,
            contrast = 0.25f, saturation = 0.05f, highlights = -0.15f,
            shadowTone = TEAL, shadowToneStrength = 0.45f, highlightTone = ORANGE, highlightToneStrength = 0.35f,
            vignette = 0.3f, grain = 0.015f, clarity = 0.3f,
        ),
        Look(
            "expired", "Expired", LookCategory.FILM,
            contrast = 0.2f, saturation = 0.2f,
            lift = Rgb(0f, 0.06f, 0.02f), gain = Rgb(1.05f, 1f, 0.9f),
            shadowTone = GREEN, shadowToneStrength = 0.25f, highlightTone = MAGENTA, highlightToneStrength = 0.15f,
            fade = 0.04f, grain = 0.05f,
            lightLeak = LightLeak(ORANGE, 0.35f, 1f, 0f),
        ),
        Look(
            "slide", "Slide", LookCategory.FILM,
            contrast = 0.3f, vibrance = 0.3f, temperature = 0.1f, highlights = -0.15f,
            gamma = Rgb(1.03f, 1f, 0.97f), vignette = 0.2f, grain = 0.015f, clarity = 0.4f,
        ),

        // Portrait (little or no clarity: sharpening skin is unflattering)
        Look(
            "peach", "Peach", LookCategory.PORTRAIT,
            exposure = 0.1f, temperature = 0.2f, tint = 0.2f, highlights = -0.2f, vibrance = 0.15f,
            highlightTone = PEACH, highlightToneStrength = 0.45f, shadowTone = Rgb(0.6f, 0.45f, 0.5f),
            shadowToneStrength = 0.12f, clarity = 0.1f,
        ),
        Look(
            "soft", "Soft", LookCategory.PORTRAIT,
            exposure = 0.15f, contrast = -0.12f, saturation = -0.05f, temperature = 0.1f, shadows = 0.2f,
            fade = 0.02f, vignette = 0.1f,
        ),
        Look(
            "porcelain", "Porcelain", LookCategory.PORTRAIT,
            exposure = 0.15f, temperature = -0.15f, saturation = -0.2f, highlights = -0.1f, shadows = 0.2f,
            highlightTone = Rgb(0.85f, 0.9f, 1f), highlightToneStrength = 0.15f, clarity = 0.1f,
        ),
        Look(
            "honey", "Honey", LookCategory.PORTRAIT,
            temperature = 0.4f, saturation = 0.1f, contrast = 0.1f,
            highlightTone = GOLD, highlightToneStrength = 0.3f, vignette = 0.15f, clarity = 0.15f,
        ),

        // Mood
        Look(
            "moody_blue", "Blue Hour", LookCategory.MOOD,
            exposure = -0.1f, temperature = -0.35f, saturation = -0.2f, contrast = 0.2f,
            shadowTone = NAVY, shadowToneStrength = 0.35f, vignette = 0.35f, clarity = 0.3f,
        ),
        Look(
            "dusk", "Dusk", LookCategory.MOOD,
            contrast = 0.15f, saturation = 0.1f, highlights = -0.1f,
            shadowTone = PURPLE, shadowToneStrength = 0.35f, highlightTone = PINK, highlightToneStrength = 0.35f,
            vignette = 0.25f, clarity = 0.2f,
        ),
        Look(
            "forest", "Forest", LookCategory.MOOD,
            contrast = 0.2f, saturation = -0.2f, temperature = -0.08f,
            gain = Rgb(0.98f, 1.03f, 0.95f), shadowTone = GREEN, shadowToneStrength = 0.35f,
            vignette = 0.3f, grain = 0.02f, clarity = 0.35f,
        ),
        Look(
            "midnight", "Midnight", LookCategory.MOOD,
            exposure = -0.1f, temperature = -0.25f, contrast = 0.25f, saturation = -0.15f, shadows = 0.1f,
            shadowTone = NAVY, shadowToneStrength = 0.4f, vignette = 0.4f, grain = 0.025f, clarity = 0.3f,
        ),

        // Vivid
        Look(
            "pop", "Pop", LookCategory.VIVID,
            saturation = 0.4f, vibrance = 0.2f, contrast = 0.25f, highlights = -0.15f, shadows = 0.1f, clarity = 0.5f,
        ),
        Look(
            "tropic", "Tropic", LookCategory.VIVID,
            exposure = 0.08f, vibrance = 0.55f, temperature = -0.08f, tint = -0.1f, contrast = 0.15f,
            highlights = -0.2f, shadows = 0.1f, gamma = Rgb(0.97f, 1.02f, 1.04f),
            highlightTone = CYAN, highlightToneStrength = 0.25f, clarity = 0.4f,
        ),
        Look(
            "candy", "Candy", LookCategory.VIVID,
            exposure = 0.1f, saturation = 0.2f, temperature = -0.05f,
            highlightTone = PINK, highlightToneStrength = 0.25f, shadowTone = Rgb(0.5f, 0.6f, 0.9f),
            shadowToneStrength = 0.2f, fade = 0.05f, clarity = 0.2f,
        ),
        Look(
            "sunset", "Sunset", LookCategory.VIVID,
            temperature = 0.45f, tint = 0.12f, saturation = 0.15f, contrast = 0.15f,
            highlightTone = ORANGE, highlightToneStrength = 0.25f,
            lightLeak = LightLeak(Rgb(1f, 0.45f, 0.2f), 0.45f, 0f, 0f), vignette = 0.2f, clarity = 0.25f,
        ),

        // Mono
        Look("mono", "Mono", LookCategory.MONO, mono = LUMA_MIX, contrast = 0.15f, clarity = 0.3f),
        Look(
            "noir", "Noir", LookCategory.MONO,
            mono = RED_FILTER_MIX, contrast = 0.4f, vignette = 0.4f, grain = 0.04f, clarity = 0.5f,
        ),
        Look(
            "silver", "Silver", LookCategory.MONO,
            mono = LUMA_MIX, contrast = -0.05f, fade = 0.06f,
            highlightTone = Rgb(0.85f, 0.9f, 1f), highlightToneStrength = 0.08f, grain = 0.02f, clarity = 0.2f,
        ),
        Look(
            "sepia", "Sepia", LookCategory.MONO,
            mono = LUMA_MIX, contrast = 0.1f,
            shadowTone = BROWN, shadowToneStrength = 0.45f, highlightTone = CREAM, highlightToneStrength = 0.35f,
            fade = 0.03f, vignette = 0.25f, clarity = 0.2f,
        ),

        // Retro (grain and softness are part of these looks, so less clarity)
        Look(
            "instant", "Instant", LookCategory.RETRO,
            contrast = -0.05f, saturation = -0.1f,
            shadowTone = Rgb(0.2f, 0.5f, 0.45f), shadowToneStrength = 0.25f,
            highlightTone = CREAM, highlightToneStrength = 0.3f, fade = 0.08f, vignette = 0.35f, grain = 0.025f,
        ),
        Look(
            "vhs", "VHS", LookCategory.RETRO,
            saturation = 0.3f, temperature = -0.1f, tint = -0.1f, contrast = 0.1f, fade = 0.05f,
            aberration = 0.005f, grain = 0.06f, vignette = 0.2f,
        ),
        Look(
            "seventies", "70s", LookCategory.RETRO,
            saturation = -0.1f, contrast = 0.05f, gain = Rgb(1.06f, 1f, 0.82f),
            highlightTone = GOLD, highlightToneStrength = 0.25f, fade = 0.07f, grain = 0.04f,
            lightLeak = LightLeak(Rgb(1f, 0.5f, 0.15f), 0.3f, 0f, 1f),
        ),
        Look(
            "digicam", "Digicam", LookCategory.RETRO,
            exposure = 0.1f, contrast = 0.25f, saturation = 0.1f, temperature = -0.2f, highlights = 0.1f,
            aberration = 0.003f, grain = 0.015f, clarity = 0.35f,
        ),
        Look(
            "lomo", "Lomo", LookCategory.RETRO,
            saturation = 0.3f, contrast = 0.3f,
            lift = Rgb(0f, 0.02f, 0.06f), gain = Rgb(1.05f, 1.02f, 0.9f),
            vignette = 0.6f, grain = 0.03f, clarity = 0.25f,
        ),
        Look(
            "leak", "Light Leak", LookCategory.RETRO,
            temperature = 0.2f, fade = 0.04f, grain = 0.025f,
            lightLeak = LightLeak(Rgb(1f, 0.35f, 0.25f), 0.6f, 1f, 0.3f),
        ),
    )

    private val byId = all.associateBy { it.id }

    fun byId(id: String): Look = byId[id] ?: Look.ORIGINAL
}
