package com.vortexx.player.audio.dsp

/** A named set of Audio Lab settings. [builtIn] presets ship with the app; the others are the user's. */
data class SoundPreset(val name: String, val parameters: DspParameters, val builtIn: Boolean)

/**
 * The presets that ship with the app, and the storage format of the user's own.
 * A preset sets the sound (preamp, EQ, bass, compressor, limiter, width). It
 * leaves the on/off switch and speaker protect alone: those depend on what
 * the phone is playing through, not on taste.
 */
object SoundPresets {
    const val MAX_USER_PRESETS = 12
    const val MAX_NAME_LENGTH = 24

    private fun preset(
        name: String,
        preampDb: Float = 0f,
        bands: List<Float>,
        bassDb: Float = 0f,
        punchDb: Float = 0f,
        compressor: Int,
        limiter: Boolean = true,
        widthPercent: Int = 100,
    ) = SoundPreset(
        name,
        DspParameters(
            preampDb = preampDb,
            bandGainsDb = bands,
            bassDb = bassDb,
            punchDb = punchDb,
            compressor = compressor,
            limiter = limiter,
            widthPercent = widthPercent,
        ),
        builtIn = true,
    )

    /** Bit-exact unity: no gain, filter, dynamics or width stage changes the samples. */
    val STUDIO_FLAT = SoundPreset("Studio Flat", DspParameters(), builtIn = true)

    // Band centres: 31, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz.
    // Bass shelf: 90 Hz. Kick band: 60 Hz.
    val HARD_DJ_BASS = preset(
        "Hard DJ Bass",
        preampDb = -1.5f,
        bands = listOf(2f, 1.5f, -2.5f, -2f, -1f, 0f, -1f, -1.5f, 1f, 1.5f),
        bassDb = 5f,
        punchDb = 4.5f,
        compressor = 35,
        widthPercent = 105,
    )
    val PUNCHY_CLUB = preset(
        "Punchy Club",
        bands = listOf(0f, 0f, -2f, -1.5f, 0f, 0f, 0f, -1f, 0f, 2f),
        bassDb = 3.5f,
        punchDb = 6f,
        compressor = 45,
        widthPercent = 115,
    )
    val VOCAL_BOOST = preset(
        "Vocal Boost",
        bands = listOf(0f, 0f, 0f, -2.5f, 0f, 0f, 2.5f, 3f, 0f, 0f),
        compressor = 30,
    )
    val CINEMA = preset(
        "Cinema",
        bands = listOf(0f, 0f, 0f, -1.5f, 0f, 0f, 1.5f, 0f, 0f, 1f),
        bassDb = 3f,
        punchDb = 2f,
        compressor = 25,
        widthPercent = 130,
    )

    val BUILT_IN: List<SoundPreset> = listOf(STUDIO_FLAT, HARD_DJ_BASS, PUNCHY_CLUB, VOCAL_BOOST, CINEMA)

    /** The preset (built-in first) whose sound equals [parameters], or null for a custom sound. */
    fun matching(parameters: DspParameters, user: List<SoundPreset>): SoundPreset? =
        BUILT_IN.firstOrNull { it.parameters.sameSoundAs(parameters) } ?: user.firstOrNull { it.parameters.sameSoundAs(parameters) }

    /** A name as it is stored: one line, trimmed, at most [MAX_NAME_LENGTH] characters; empty = not usable. */
    fun cleanName(raw: String): String =
        raw.replace('\\t', ' ').replace('\\n', ' ').replace('\\r', ' ').trim().take(MAX_NAME_LENGTH).trim()

    /** True when [name] can be saved: not empty and not the name of a built-in preset. */
    fun isUsableName(name: String): Boolean =
        name.isNotEmpty() && BUILT_IN.none { it.name.equals(name, ignoreCase = true) }

    /** [presets] with [name] set to [parameters]: replaces a preset of that name, else adds it; the oldest goes when full. */
    fun withSaved(presets: List<SoundPreset>, name: String, parameters: DspParameters): List<SoundPreset> {
        val saved = SoundPreset(name, parameters.copy(enabled = true, speakerProtect = false), builtIn = false)
        val others = presets.filterNot { it.name.equals(name, ignoreCase = true) }
        return (others + saved).takeLast(MAX_USER_PRESETS)
    }

    /** One preset per line: name, a tab, the encoded settings. */
    fun encodeUser(presets: List<SoundPreset>): String =
        presets.joinToString("\\n") { it.name + "\\t" + it.parameters.encode() }

    /** Reads what [encodeUser] wrote; lines that are not valid are left out. */
    fun decodeUser(raw: String?): List<SoundPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        val presets = ArrayList<SoundPreset>()
        for (line in raw.split('\\n')) {
            val tab = line.indexOf('\\t')
            if (tab <= 0) continue
            val name = cleanName(line.substring(0, tab))
            val parameters = DspParameters.decode(line.substring(tab + 1)) ?: continue
            if (!isUsableName(name) || presets.any { it.name.equals(name, ignoreCase = true) }) continue
            presets += SoundPreset(name, parameters, builtIn = false)
        }
        return presets.takeLast(MAX_USER_PRESETS)
    }
}
