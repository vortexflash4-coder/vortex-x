package com.vortexx.player.audio.dsp

/** The bass section of the Audio Lab: where its filters sit and how far they go. */
object BassTone {
    /** Corner of the bass shelf: the full boost is reached well below it, half at the corner. */
    const val SHELF_HZ = 90.0

    /** Centre of the kick-drum band. */
    const val PUNCH_HZ = 60.0
    const val PUNCH_Q = 1.2
    const val MAX_BASS_DB = 12f
    const val MAX_PUNCH_DB = 9f

    /** Speaker protect removes what lies below this (12 dB per octave). */
    const val PROTECT_HZ = 80.0
    const val MAX_WIDTH_PERCENT = 200
}

/**
 * One immutable snapshot of the user's DSP settings. The UI publishes a new
 * instance per change; the audio thread compares references once per buffer.
 * The defaults change nothing: with them the chain is bit-transparent.
 */
data class DspParameters(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
    val bandGainsDb: List<Float> = FLAT,
    /** Bass shelf boost, 0…[BassTone.MAX_BASS_DB] dB. */
    val bassDb: Float = 0f,
    /** Kick band boost, 0…[BassTone.MAX_PUNCH_DB] dB. */
    val punchDb: Float = 0f,
    /** Removes the lowest bass, which a phone speaker cannot play and which makes it rattle. */
    val speakerProtect: Boolean = false,
    /** Three-band compressor amount, 0 (off) … 100. */
    val compressor: Int = 0,
    /** Look-ahead limiter that keeps every sample at or below the ceiling. */
    val limiter: Boolean = false,
    /** Stereo width: 100 = unchanged, 0 = mono, 200 = widest. Stereo streams only. */
    val widthPercent: Int = 100,
) {
    init {
        require(bandGainsDb.size == GraphicEqBands.COUNT) { "Expected ${GraphicEqBands.COUNT} bands" }
    }

    /** True when nothing would change the samples. */
    val isFlat: Boolean
        get() = preampDb == 0f && bandGainsDb.all { it == 0f } && bassDb == 0f && punchDb == 0f &&
            !speakerProtect && compressor == 0 && !limiter && widthPercent == 100

    /** Highest positive boost of any band, used to suggest headroom. */
    val maxBoostDb: Float get() = bandGainsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f

    fun withBand(index: Int, gainDb: Float): DspParameters =
        copy(bandGainsDb = bandGainsDb.mapIndexed { i, g -> if (i == index) clampGain(gainDb) else g })

    fun withPreamp(gainDb: Float): DspParameters = copy(preampDb = clampPreamp(gainDb))

    /** The sound of this snapshot without the two switches that belong to the device, not the taste. */
    fun sameSoundAs(other: DspParameters): Boolean =
        copy(enabled = true, speakerProtect = false) == other.copy(enabled = true, speakerProtect = false)

    /** One line for storage: "1;preamp;bands;bass;punch;protect;compressor;limiter;width". */
    fun encode(): String = listOf(
        FORMAT,
        preampDb.toString(),
        formatBands(bandGainsDb),
        bassDb.toString(),
        punchDb.toString(),
        if (speakerProtect) "1" else "0",
        compressor.toString(),
        if (limiter) "1" else "0",
        widthPercent.toString(),
    ).joinToString(";")

    companion object {
        private const val FORMAT = "1"
        val FLAT: List<Float> = List(GraphicEqBands.COUNT) { 0f }

        fun clampGain(db: Float): Float = roundTenth(db.coerceIn(-GraphicEqBands.MAX_GAIN_DB, GraphicEqBands.MAX_GAIN_DB))

        fun clampPreamp(db: Float): Float = roundTenth(db.coerceIn(-GraphicEqBands.MAX_PREAMP_DB, GraphicEqBands.MAX_PREAMP_DB))

        fun clampBass(db: Float): Float = roundTenth(db.coerceIn(0f, BassTone.MAX_BASS_DB))

        fun clampPunch(db: Float): Float = roundTenth(db.coerceIn(0f, BassTone.MAX_PUNCH_DB))

        fun clampCompressor(amount: Int): Int = amount.coerceIn(0, 100)

        fun clampWidth(percent: Int): Int = percent.coerceIn(0, BassTone.MAX_WIDTH_PERCENT)

        /** 0.1 dB resolution keeps stored values readable and comparisons exact. */
        private fun roundTenth(db: Float): Float = if (db.isNaN()) 0f else Math.round(db * 10f) / 10f

        /** Parses "g1,g2,…" as stored in preferences; anything invalid gives a flat EQ. */
        fun parseBands(raw: String?): List<Float> {
            if (raw.isNullOrBlank()) return FLAT
            val parts = raw.split(',')
            if (parts.size != GraphicEqBands.COUNT) return FLAT
            val values = parts.map { it.trim().toFloatOrNull() ?: return FLAT }
            if (values.any { it.isNaN() }) return FLAT
            return values.map(::clampGain)
        }

        fun formatBands(bands: List<Float>): String = bands.joinToString(",")

        /** Reads a line written by [encode]; null when it is not one. Every value is clamped to its range. */
        fun decode(raw: String?): DspParameters? {
            val parts = raw?.split(';') ?: return null
            if (parts.size != 9 || parts[0] != FORMAT) return null
            return DspParameters(
                preampDb = clampPreamp(parts[1].toFloatOrNull() ?: return null),
                bandGainsDb = parseBands(parts[2]),
                bassDb = clampBass(parts[3].toFloatOrNull() ?: return null),
                punchDb = clampPunch(parts[4].toFloatOrNull() ?: return null),
                speakerProtect = parts[5] == "1",
                compressor = clampCompressor(parts[6].toIntOrNull() ?: return null),
                limiter = parts[7] == "1",
                widthPercent = clampWidth(parts[8].toIntOrNull() ?: return null),
            )
        }
    }
}
