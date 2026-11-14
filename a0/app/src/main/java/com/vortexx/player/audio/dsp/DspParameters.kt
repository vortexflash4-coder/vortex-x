package com.vortexx.player.audio.dsp

/**
 * One immutable snapshot of the user's DSP settings. The UI publishes a new
 * instance per change; the audio thread compares references once per buffer.
 */
data class DspParameters(
    val enabled: Boolean = true,
    val preampDb: Float = 0f,
    val bandGainsDb: List<Float> = FLAT,
) {
    init {
        require(bandGainsDb.size == GraphicEqBands.COUNT) { "Expected ${GraphicEqBands.COUNT} bands" }
    }

    val isFlat: Boolean get() = preampDb == 0f && bandGainsDb.all { it == 0f }

    /** Highest individual boost; not a safe estimate for cascaded filters. */
    val maxBoostDb: Float get() = bandGainsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f

    /** UI/control thread only: scans the combined response, including overlaps.
     * Adds 1 dB margin and rounds attenuation upward. This is a steady-state
     * estimate; transients/parameter ramps are also covered by the sample ceiling.
     * Reapply after changing bands; the sample rate defaults only before playback.
     */
    fun recommendedPreampDb(sampleRate: Int = 48_000): Float {
        require(sampleRate > 0)
        if (bandGainsDb.none { it > 0f }) return 0f
        val filters = Array(GraphicEqBands.COUNT) { band ->
            BiquadCoefficients().apply {
                if (GraphicEqBands.isUsable(band, sampleRate)) {
                    setPeaking(sampleRate, GraphicEqBands.CENTRES_HZ[band], GraphicEqBands.OCTAVE_Q, bandGainsDb[band].toDouble())
                }
            }
        }
        fun response(hz: Double): Double {
            var db = 0.0
            for (filter in filters) db += filter.magnitudeDb(hz, sampleRate)
            return db
        }
        var peak = 0.0
        val upper = sampleRate * 0.499
        val logSpan = kotlin.math.ln(upper)
        for (i in 0..2048) {
            peak = maxOf(peak, response(kotlin.math.exp(logSpan * i / 2048)))
        }
        for (hz in GraphicEqBands.CENTRES_HZ) if (hz < upper) peak = maxOf(peak, response(hz))
        if (peak < 0.0001) return 0f
        return clampPreamp((-kotlin.math.ceil((peak + 1.0) * 10.0) / 10.0).toFloat())
    }

    fun withBand(index: Int, gainDb: Float): DspParameters =
        copy(bandGainsDb = bandGainsDb.mapIndexed { i, g -> if (i == index) clampGain(gainDb) else g })

    fun withPreamp(gainDb: Float): DspParameters = copy(preampDb = clampPreamp(gainDb))

    companion object {
        val FLAT: List<Float> = List(GraphicEqBands.COUNT) { 0f }

        fun clampGain(db: Float): Float = roundTenth(db.coerceIn(-GraphicEqBands.MAX_GAIN_DB, GraphicEqBands.MAX_GAIN_DB))

        fun clampPreamp(db: Float): Float = roundTenth(db.coerceIn(GraphicEqBands.MIN_PREAMP_DB, GraphicEqBands.MAX_PREAMP_DB))

        /** 0.1 dB resolution keeps stored values readable and comparisons exact. */
        private fun roundTenth(db: Float): Float = Math.round(db * 10f) / 10f

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
    }
}
