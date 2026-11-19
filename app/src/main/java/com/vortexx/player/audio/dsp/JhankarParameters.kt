package com.vortexx.player.audio.dsp

/** Ranges and defaults of the Jhankar Sound Studio. Every control on screen takes its limits from here. */
object JhankarTone {
    const val BANDS = 10
    val DEFAULT_FREQ_HZ: FloatArray = floatArrayOf(31.5f, 63f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    const val MIN_FREQ_HZ = 20f
    const val MAX_FREQ_HZ = 20000f
    const val MIN_Q = 0.3f
    const val MAX_Q = 8f
    const val DEFAULT_Q = 1.1f
    const val MAX_BAND_DB = 12f
    const val MAX_PREAMP_DB = 12f
    const val MAX_SUB_DB = 6f
    const val MIN_OUTPUT_DB = -12f
    const val MAX_OUTPUT_DB = 6f
    const val MIN_WIDTH = 50
    const val MAX_WIDTH = 200
    const val MAX_MONO_HZ = 250
    const val MIN_MONO_HZ = 60
    const val DEFAULT_INTENSITY = 80
}

/** One band of the Jhankar equalizer: band 1 is a low shelf, band 10 a high shelf, the others are bell filters. */
data class JhankarBand(val freqHz: Float, val gainDb: Float, val q: Float)

/**
 * The complete setting of the Jhankar engine for one output profile. The engine is a stage group inside the one
 * [DspChain]; nothing here starts another audio session or another chain.
 *
 * [intensity] scales every enhancement (dynamic bass, bass harmonics, vocal clarity, detail, air, transient punch,
 * compression, loudness contour and the width beyond 100 %). The equalizer, the sub-bass shelf and the output gain
 * are exactly as set. [engine] is the master switch, kept once for all profiles by the engine and not stored per profile.
 */
data class JhankarParameters(
    val engine: Boolean = false,
    val intensity: Int = JhankarTone.DEFAULT_INTENSITY,
    val eqOn: Boolean = true,
    val preampDb: Float = 0f,
    val autoHeadroom: Boolean = true,
    val bands: List<JhankarBand> = FLAT_BANDS,
    val bassOn: Boolean = true,
    val dynamicBass: Int = 0,
    val subBassDb: Float = 0f,
    val bassHarmonics: Int = 0,
    val vocalClarity: Int = 0,
    val hfDetail: Int = 0,
    val airHarmonics: Int = 0,
    val stereoOn: Boolean = true,
    val widthPercent: Int = 100,
    val monoBelowHz: Int = 0,
    val transient: Int = 0,
    val compression: Int = 0,
    val loudness: Int = 0,
    val outputGainDb: Float = 0f,
) {
    fun withBand(index: Int, band: JhankarBand): JhankarParameters =
        copy(bands = bands.mapIndexed { i, b -> if (i == index) clampBand(band) else b })

    /** The same setting with every value brought into its range (and the bands into ten valid bands). */
    fun clamped(): JhankarParameters = copy(
        intensity = clampPercent(intensity),
        preampDb = clampPreamp(preampDb),
        bands = if (bands.size == JhankarTone.BANDS) bands.map { clampBand(it) } else FLAT_BANDS,
        dynamicBass = clampPercent(dynamicBass),
        subBassDb = clampSub(subBassDb),
        bassHarmonics = clampPercent(bassHarmonics),
        vocalClarity = clampPercent(vocalClarity),
        hfDetail = clampPercent(hfDetail),
        airHarmonics = clampPercent(airHarmonics),
        widthPercent = clampWidth(widthPercent),
        monoBelowHz = clampMono(monoBelowHz),
        transient = clampPercent(transient),
        compression = clampPercent(compression),
        loudness = clampPercent(loudness),
        outputGainDb = clampOutput(outputGainDb),
    )

    /** The same sound with the equalizer back to flat and no preamp (the other controls stay). */
    fun withFlatEq(): JhankarParameters = copy(preampDb = 0f, bands = FLAT_BANDS)

    /** Whether two settings sound the same (the master switch is not part of the sound). */
    fun sameSoundAs(other: JhankarParameters): Boolean = copy(engine = false) == other.copy(engine = false)

    fun encode(): String = listOf(
        FORMAT,
        flag(engine), intensity, flag(eqOn), preampDb, flag(autoHeadroom),
        flag(bassOn), dynamicBass, subBassDb, bassHarmonics,
        vocalClarity, hfDetail, airHarmonics,
        flag(stereoOn), widthPercent, monoBelowHz,
        transient, compression, loudness, outputGainDb,
        bands.joinToString("|") { "${it.freqHz},${it.gainDb},${it.q}" },
    ).joinToString(";")

    companion object {
        private const val FORMAT = "J1"
        private const val PARTS = 21

        val FLAT_BANDS: List<JhankarBand> =
            JhankarTone.DEFAULT_FREQ_HZ.map { JhankarBand(it, 0f, JhankarTone.DEFAULT_Q) }

        /**
         * The engine switched off, with every control neutral: what a host without Jhankar settings reports.
         * Declared after [FLAT_BANDS]: the default value of `bands` reads it while this object is being built.
         */
        val OFF = JhankarParameters()

        private fun flag(on: Boolean) = if (on) "1" else "0"

        fun clampFreq(hz: Float): Float =
            if (hz.isNaN()) 1000f else hz.coerceIn(JhankarTone.MIN_FREQ_HZ, JhankarTone.MAX_FREQ_HZ)

        fun clampQ(q: Float): Float = if (q.isNaN()) JhankarTone.DEFAULT_Q else q.coerceIn(JhankarTone.MIN_Q, JhankarTone.MAX_Q)

        fun clampBandDb(db: Float): Float = if (db.isNaN()) 0f else db.coerceIn(-JhankarTone.MAX_BAND_DB, JhankarTone.MAX_BAND_DB)

        fun clampPreamp(db: Float): Float = if (db.isNaN()) 0f else db.coerceIn(-JhankarTone.MAX_PREAMP_DB, JhankarTone.MAX_PREAMP_DB)

        fun clampSub(db: Float): Float = if (db.isNaN()) 0f else db.coerceIn(-JhankarTone.MAX_SUB_DB, JhankarTone.MAX_SUB_DB)

        fun clampOutput(db: Float): Float =
            if (db.isNaN()) 0f else db.coerceIn(JhankarTone.MIN_OUTPUT_DB, JhankarTone.MAX_OUTPUT_DB)

        fun clampPercent(value: Int): Int = value.coerceIn(0, 100)

        fun clampWidth(value: Int): Int = value.coerceIn(JhankarTone.MIN_WIDTH, JhankarTone.MAX_WIDTH)

        /** 0 = off, otherwise 60…250 Hz. */
        fun clampMono(hz: Int): Int = if (hz <= 0) 0 else hz.coerceIn(JhankarTone.MIN_MONO_HZ, JhankarTone.MAX_MONO_HZ)

        fun clampBand(band: JhankarBand): JhankarBand =
            JhankarBand(clampFreq(band.freqHz), clampBandDb(band.gainDb), clampQ(band.q))

        /** A settings line saved by [encode]; null when it is damaged. Out-of-range values are brought into range. */
        fun decode(raw: String?): JhankarParameters? {
            if (raw == null) return null
            val p = raw.trim().split(';')
            if (p.size != PARTS || p[0] != FORMAT) return null
            val bandTexts = p[20].split('|')
            if (bandTexts.size != JhankarTone.BANDS) return null
            val bands = ArrayList<JhankarBand>(JhankarTone.BANDS)
            for (text in bandTexts) {
                val v = text.split(',')
                if (v.size != 3) return null
                val f = v[0].toFloatOrNull() ?: return null
                val g = v[1].toFloatOrNull() ?: return null
                val q = v[2].toFloatOrNull() ?: return null
                bands += clampBand(JhankarBand(f, g, q))
            }
            return try {
                JhankarParameters(
                    engine = p[1] == "1",
                    intensity = clampPercent(p[2].toInt()),
                    eqOn = p[3] == "1",
                    preampDb = clampPreamp(p[4].toFloat()),
                    autoHeadroom = p[5] == "1",
                    bands = bands,
                    bassOn = p[6] == "1",
                    dynamicBass = clampPercent(p[7].toInt()),
                    subBassDb = clampSub(p[8].toFloat()),
                    bassHarmonics = clampPercent(p[9].toInt()),
                    vocalClarity = clampPercent(p[10].toInt()),
                    hfDetail = clampPercent(p[11].toInt()),
                    airHarmonics = clampPercent(p[12].toInt()),
                    stereoOn = p[13] == "1",
                    widthPercent = clampWidth(p[14].toInt()),
                    monoBelowHz = clampMono(p[15].toInt()),
                    transient = clampPercent(p[16].toInt()),
                    compression = clampPercent(p[17].toInt()),
                    loudness = clampPercent(p[18].toInt()),
                    outputGainDb = clampOutput(p[19].toFloat()),
                )
            } catch (_: NumberFormatException) {
                null
            }
        }
    }
}

/** The four places the phone can send sound to. Each keeps its own Jhankar setting. */
enum class OutputProfile(val label: String, val summary: String) {
    Tower("Bluetooth Tower", "Controlled bass, punchy dhol and kick, clear vocals, loudness management"),
    PartySpeaker("Party Speaker", "Mid-bass impact, clear percussion, calmer highs, distortion care"),
    PremiumStereo("Premium Stereo", "Balanced response, natural stereo, detailed vocals, light dynamics"),
    Headphone("Headphones", "Own tuning, lower peak level, individual EQ");

    companion object {
        fun fromName(name: String?): OutputProfile = entries.firstOrNull { it.name == name } ?: PremiumStereo
    }
}

/** A named Jhankar sound. [builtIn] ones ship with the app, the others are the user's. */
data class JhankarPreset(val name: String, val note: String, val parameters: JhankarParameters, val builtIn: Boolean)

/** What a Jhankar preset edit did (the result codes are the Audio Lab's). */
data class JhankarPresetEdit(val presets: List<JhankarPreset>, val result: PresetResult)

object JhankarPresets {
    const val MAX_USER_PRESETS = 12
    const val MAX_NAME_LENGTH = 24

    private fun bands(gains: FloatArray, q: Float = JhankarTone.DEFAULT_Q): List<JhankarBand> =
        JhankarTone.DEFAULT_FREQ_HZ.mapIndexed { i, f -> JhankarBand(f, gains[i], q) }

    private fun built(
        name: String,
        note: String,
        gains: FloatArray,
        intensity: Int,
        dynamicBass: Int = 0,
        subBassDb: Float = 0f,
        bassHarmonics: Int = 0,
        vocalClarity: Int = 0,
        hfDetail: Int = 0,
        airHarmonics: Int = 0,
        widthPercent: Int = 100,
        monoBelowHz: Int = 0,
        transient: Int = 0,
        compression: Int = 0,
        loudness: Int = 0,
        outputGainDb: Float = 0f,
    ) = JhankarPreset(
        name,
        note,
        JhankarParameters(
            intensity = intensity,
            bands = bands(gains),
            dynamicBass = dynamicBass,
            subBassDb = subBassDb,
            bassHarmonics = bassHarmonics,
            vocalClarity = vocalClarity,
            hfDetail = hfDetail,
            airHarmonics = airHarmonics,
            widthPercent = widthPercent,
            monoBelowHz = monoBelowHz,
            transient = transient,
            compression = compression,
            loudness = loudness,
            outputGainDb = outputGainDb,
        ),
        builtIn = true,
    )

    // Band centres: 31.5, 63, 125, 250, 500, 1k, 2k, 4k, 8k, 16k Hz.
    val JHANKAR_ORIGINAL = built(
        "Jhankar Original", "Full, warm low end with clear vocals and sparkle",
        floatArrayOf(3.5f, 3f, 1.5f, -1.5f, -1f, 0f, 1.5f, 2.5f, 2f, 2.5f),
        intensity = 75, dynamicBass = 45, subBassDb = 1f, bassHarmonics = 30, vocalClarity = 35, hfDetail = 25,
        airHarmonics = 20, widthPercent = 115, monoBelowHz = 120, transient = 25, compression = 30, loudness = 15,
    )
    val HINDI_90S = built(
        "Hindi 90s Remastered", "Restores the top and the vocal of older recordings",
        floatArrayOf(-1f, 1f, 2f, -0.5f, -1f, 0.5f, 2.5f, 3f, 1.5f, 2f),
        intensity = 70, dynamicBass = 35, bassHarmonics = 25, vocalClarity = 55, hfDetail = 35, airHarmonics = 20,
        widthPercent = 110, monoBelowHz = 120, transient = 20, compression = 25, loudness = 10,
    )
    val BOLLYWOOD_LIVE = built(
        "Bollywood Live Stage", "Big stage: wide image, lively top, punchy beat",
        floatArrayOf(2.5f, 3f, 1f, -1f, 0f, 1f, 2f, 2f, 2.5f, 3f),
        intensity = 80, dynamicBass = 40, bassHarmonics = 30, vocalClarity = 30, hfDetail = 30, airHarmonics = 30,
        widthPercent = 135, monoBelowHz = 120, transient = 30, compression = 35, loudness = 15,
    )
    val DHOLAK_TABLA = built(
        "Dholak & Tabla Punch", "Thump of the dhol, slap and attack of the tabla",
        floatArrayOf(1f, 2f, 3f, -0.5f, -1.5f, 0f, 2f, 3.5f, 2f, 1f),
        intensity = 80, dynamicBass = 30, bassHarmonics = 20, vocalClarity = 20, hfDetail = 25, widthPercent = 110,
        monoBelowHz = 120, transient = 65, compression = 30,
    )
    val CRYSTAL_VOCALS = built(
        "Crystal Clear Vocals", "Voice in front: less mud, more presence, no harshness",
        floatArrayOf(-2f, -1f, -1f, -2.5f, -1.5f, 1.5f, 2.5f, 2f, 2.5f, 3f),
        intensity = 75, dynamicBass = 10, vocalClarity = 70, hfDetail = 45, airHarmonics = 25, widthPercent = 105,
        transient = 15, compression = 25,
    )
    val DEEP_BASS = built(
        "Deep Bass Jhankar", "Deep, tight bass that stays clean at volume",
        floatArrayOf(5f, 5f, 3f, -0.5f, -1.5f, 0f, 1f, 1.5f, 1.5f, 2f),
        intensity = 85, dynamicBass = 70, subBassDb = 2.5f, bassHarmonics = 50, vocalClarity = 25, hfDetail = 15,
        widthPercent = 105, monoBelowHz = 140, transient = 25, compression = 40, loudness = 10,
    )
    val WEDDING_DJ = built(
        "Wedding DJ Tower", "Loud, controlled and punchy for a tower at a function",
        floatArrayOf(4f, 4.5f, 2.5f, -1f, -1.5f, 0.5f, 2f, 2.5f, 2f, 1.5f),
        intensity = 85, dynamicBass = 60, subBassDb = 1.5f, bassHarmonics = 45, vocalClarity = 35, hfDetail = 20,
        airHarmonics = 15, widthPercent = 120, monoBelowHz = 130, transient = 35, compression = 55, loudness = 35,
        outputGainDb = -1f,
    )
    val STUDIO_MASTER = built(
        "Studio Master", "Gentle polish, dynamics kept",
        floatArrayOf(1f, 1f, 0.5f, -0.5f, 0f, 0f, 0.5f, 1f, 1f, 1.5f),
        intensity = 50, dynamicBass = 15, vocalClarity = 15, hfDetail = 15, widthPercent = 105, transient = 10, compression = 20,
    )
    val BALANCED_HIFI = built(
        "Balanced Hi-Fi", "Neutral and natural with a light touch",
        floatArrayOf(0.5f, 1f, 0.5f, 0f, 0f, 0f, 0f, 0.5f, 0.5f, 1f),
        intensity = 40, dynamicBass = 10, vocalClarity = 10, hfDetail = 10, widthPercent = 105,
    )
    val CUSTOM_PROFESSIONAL = built(
        "Custom Professional", "A clean starting point for your own sound",
        floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
        intensity = 60, dynamicBass = 20, vocalClarity = 20, hfDetail = 15, widthPercent = 105, compression = 20,
    )

    val BUILT_IN: List<JhankarPreset> = listOf(
        JHANKAR_ORIGINAL, HINDI_90S, BOLLYWOOD_LIVE, DHOLAK_TABLA, CRYSTAL_VOCALS,
        DEEP_BASS, WEDDING_DJ, STUDIO_MASTER, BALANCED_HIFI, CUSTOM_PROFESSIONAL,
    )

    /** What each output starts with, until the user changes it. */
    fun defaultFor(profile: OutputProfile): JhankarParameters = when (profile) {
        OutputProfile.Tower -> WEDDING_DJ.parameters.copy(
            intensity = 80, outputGainDb = -1f, widthPercent = 115, subBassDb = 1f,
        )
        OutputProfile.PartySpeaker -> BOLLYWOOD_LIVE.parameters.copy(
            bands = bands(floatArrayOf(-1f, 1f, 3f, 0f, -1.5f, 0.5f, 2f, 1.5f, 0f, -0.5f)),
            intensity = 80, dynamicBass = 45, subBassDb = -3f, bassHarmonics = 60, widthPercent = 105,
            hfDetail = 10, airHarmonics = 5, compression = 60, outputGainDb = -1f,
        )
        OutputProfile.PremiumStereo -> BALANCED_HIFI.parameters.copy(
            intensity = 55, widthPercent = 110, vocalClarity = 25, hfDetail = 20, compression = 15, transient = 10,
        )
        OutputProfile.Headphone -> CRYSTAL_VOCALS.parameters.copy(
            bands = bands(floatArrayOf(2f, 2f, 1f, -1f, -0.5f, 0f, 0.5f, -1f, 0.5f, 1.5f)),
            intensity = 55, dynamicBass = 20, vocalClarity = 30, hfDetail = 20, airHarmonics = 5, widthPercent = 100,
            monoBelowHz = 0, transient = 10, compression = 25, loudness = 0, outputGainDb = -4f,
        )
    }

    /** The preset (built-in first) whose sound equals [parameters], or null for a custom sound. */
    fun matching(parameters: JhankarParameters, user: List<JhankarPreset>): JhankarPreset? =
        BUILT_IN.firstOrNull { it.parameters.sameSoundAs(parameters) } ?: user.firstOrNull { it.parameters.sameSoundAs(parameters) }

    fun cleanName(raw: String): String =
        raw.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ').trim().take(MAX_NAME_LENGTH).trim()

    fun isUsableName(name: String): Boolean =
        name.isNotEmpty() && BUILT_IN.none { it.name.equals(name, ignoreCase = true) }

    private fun indexOfName(presets: List<JhankarPreset>, name: String): Int =
        presets.indexOfFirst { it.name.equals(name, ignoreCase = true) }

    fun saveOutcome(presets: List<JhankarPreset>, rawName: String): PresetResult {
        val name = cleanName(rawName)
        if (!isUsableName(name)) return PresetResult.BadName
        if (indexOfName(presets, name) >= 0) return PresetResult.Overwritten
        return if (presets.size >= MAX_USER_PRESETS) PresetResult.Full else PresetResult.Saved
    }

    /** Saves [parameters] (without the master switch) under [rawName]; a preset is never deleted to make room. */
    fun save(presets: List<JhankarPreset>, rawName: String, parameters: JhankarParameters): JhankarPresetEdit {
        val result = saveOutcome(presets, rawName)
        if (!result.done) return JhankarPresetEdit(presets, result)
        val name = cleanName(rawName)
        val sound = parameters.copy(engine = false)
        val index = indexOfName(presets, name)
        val edited = if (index >= 0) {
            presets.mapIndexed { i, p -> if (i == index) JhankarPreset(p.name, "Your saved sound", sound, builtIn = false) else p }
        } else {
            presets + JhankarPreset(name, "Your saved sound", sound, builtIn = false)
        }
        return JhankarPresetEdit(edited, result)
    }

    fun renameOutcome(presets: List<JhankarPreset>, oldName: String, rawNew: String): PresetResult {
        val from = indexOfName(presets, oldName)
        if (from < 0) return PresetResult.NotFound
        val name = cleanName(rawNew)
        if (!isUsableName(name)) return PresetResult.BadName
        val other = indexOfName(presets, name)
        return if (other >= 0 && other != from) PresetResult.NameTaken else PresetResult.Renamed
    }

    fun rename(presets: List<JhankarPreset>, oldName: String, rawNew: String): JhankarPresetEdit {
        val result = renameOutcome(presets, oldName, rawNew)
        if (!result.done) return JhankarPresetEdit(presets, result)
        val from = indexOfName(presets, oldName)
        val name = cleanName(rawNew)
        return JhankarPresetEdit(presets.mapIndexed { i, p -> if (i == from) p.copy(name = name) else p }, result)
    }

    fun delete(presets: List<JhankarPreset>, name: String): JhankarPresetEdit {
        val index = indexOfName(presets, name)
        if (index < 0) return JhankarPresetEdit(presets, PresetResult.NotFound)
        return JhankarPresetEdit(presets.filterIndexed { i, _ -> i != index }, PresetResult.Deleted)
    }

    fun encodeUser(presets: List<JhankarPreset>): String =
        presets.joinToString("\n") { it.name + "\t" + it.parameters.encode() }

    fun decodeUser(raw: String?): List<JhankarPreset> {
        if (raw.isNullOrBlank()) return emptyList()
        val presets = ArrayList<JhankarPreset>()
        for (line in raw.split('\n')) {
            val tab = line.indexOf('\t')
            if (tab <= 0) continue
            val name = cleanName(line.substring(0, tab))
            val parameters = JhankarParameters.decode(line.substring(tab + 1)) ?: continue
            if (!isUsableName(name) || presets.any { it.name.equals(name, ignoreCase = true) }) continue
            presets += JhankarPreset(name, "Your saved sound", parameters, builtIn = false)
        }
        return presets.take(MAX_USER_PRESETS)
    }
}
