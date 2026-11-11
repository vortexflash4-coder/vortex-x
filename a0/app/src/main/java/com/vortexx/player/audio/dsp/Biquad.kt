package com.vortexx.player.audio.dsp

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Normalised second-order filter coefficients (a0 = 1), designed with the
 * RBJ "Audio EQ Cookbook" formulas. Mutable so a running filter can be
 * re-tuned without allocating.
 */
class BiquadCoefficients {
    var b0 = 1.0
        private set
    var b1 = 0.0
        private set
    var b2 = 0.0
        private set
    var a1 = 0.0
        private set
    var a2 = 0.0
        private set

    /** True when the filter passes samples through unchanged. */
    val isIdentity: Boolean
        get() = b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0

    fun setIdentity() {
        b0 = 1.0
        b1 = 0.0
        b2 = 0.0
        a1 = 0.0
        a2 = 0.0
    }

    /**
     * Peaking EQ. The magnitude at [centreHz] is exactly [gainDb]; 0 dB gives
     * an exact identity filter so a flat EQ is bit-transparent.
     */
    fun setPeaking(sampleRate: Int, centreHz: Double, q: Double, gainDb: Double) {
        if (gainDb == 0.0) {
            setIdentity()
            return
        }
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * centreHz / sampleRate
        val alpha = sin(w0) / (2.0 * q)
        val cosW0 = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = (-2.0 * cosW0) / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = (-2.0 * cosW0) / a0
        a2 = (1.0 - alpha / a) / a0
    }

    /** Magnitude response in dB at [frequencyHz], from the transfer function (no signal needed). */
    fun magnitudeDb(frequencyHz: Double, sampleRate: Int): Double {
        val w = 2.0 * PI * frequencyHz / sampleRate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val numRe = b0 + b1 * c1 + b2 * c2
        val numIm = -(b1 * s1 + b2 * s2)
        val denRe = 1.0 + a1 * c1 + a2 * c2
        val denIm = -(a1 * s1 + a2 * s2)
        val num = sqrt(numRe * numRe + numIm * numIm)
        val den = sqrt(denRe * denRe + denIm * denIm)
        return 20.0 * kotlin.math.log10(num / den)
    }
}

/** Band layout for the 10-band graphic EQ (ISO 266 octave centres). */
object GraphicEqBands {
    val CENTRES_HZ: DoubleArray = doubleArrayOf(31.5, 63.0, 125.0, 250.0, 500.0, 1000.0, 2000.0, 4000.0, 8000.0, 16000.0)
    val LABELS: List<String> = listOf("31", "63", "125", "250", "500", "1k", "2k", "4k", "8k", "16k")
    const val COUNT = 10

    /** Q for a one-octave bandwidth: sqrt(2) / (2 - 1). */
    const val OCTAVE_Q = 1.4142135623730951
    const val MAX_GAIN_DB = 12f
    const val MAX_PREAMP_DB = 12f
    const val MIN_PREAMP_DB = -48f

    /** Bands at or above this fraction of the sample rate are switched off (too close to Nyquist). */
    const val MAX_CENTRE_FRACTION = 0.45

    fun isUsable(bandIndex: Int, sampleRate: Int): Boolean =
        CENTRES_HZ[bandIndex] < MAX_CENTRE_FRACTION * sampleRate
}
