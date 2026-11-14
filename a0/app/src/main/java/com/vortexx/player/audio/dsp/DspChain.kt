package com.vortexx.player.audio.dsp

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * Stage A0 signal chain on interleaved float samples (−1…1):
 * preamp → 10 peaking filters → bypass crossfade → clip count / sample ceiling → meters.
 *
 * Real-time rules: arrays are allocated at construction or [configure]; [process] does not
 * allocate, lock or call into the platform. Parameter changes are smoothed:
 * gains move at most [GAIN_STEP_DB] per [SUB_BLOCK] frames and bypass is a
 * [BYPASS_RAMP_SECONDS] linear crossfade, so no change produces a step.
 * The filters always run, also while bypassed, so switching back in is
 * click-free. The chain has no look-ahead: algorithmic latency is 0 samples.
 */
class DspChain(private val meters: MeterBus? = null) {

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set

    private val bandCount = GraphicEqBands.COUNT
    private val coefficients = Array(bandCount) { BiquadCoefficients() }
    private val bandUsable = BooleanArray(bandCount)
    private val bandRunning = BooleanArray(bandCount)
    private val currentGainDb = DoubleArray(bandCount)
    private val targetGainDb = DoubleArray(bandCount)

    // Filter state, index = band * channelCount + channel (transposed direct form II).
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    private var currentPreampDb = 0.0
    private var targetPreampDb = 0.0
    private var currentPreampLinear = 1.0

    private var mix = 1.0
    private var targetMix = 1.0
    private var mixStep = 0.0

    private var applied: DspParameters? = null

    /** Prepares for a stream format. Allocates; never call from the audio callback. */
    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..MAX_CHANNELS) { "channelCount must be 1..$MAX_CHANNELS" }
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        z1 = DoubleArray(bandCount * channelCount)
        z2 = DoubleArray(bandCount * channelCount)
        mixStep = 1.0 / (BYPASS_RAMP_SECONDS * sampleRate)
        for (b in 0 until bandCount) {
            bandUsable[b] = GraphicEqBands.isUsable(b, sampleRate)
            retune(b)
        }
    }

    /** Clears filter memory (after a seek or a format change). */
    fun resetState() {
        z1.fill(0.0)
        z2.fill(0.0)
    }

    /** Re-enter from a bit-exact battery bypass without replaying old filter memory. */
    fun resumeFromDry() {
        resetState()
        mix = 0.0
    }

    /**
     * Takes a new parameter snapshot. Cheap when [params] is the snapshot
     * already applied (reference check), so it can be called every buffer.
     */
    fun apply(params: DspParameters) {
        if (params === applied) return
        val firstApply = applied == null
        applied = params
        for (b in 0 until bandCount) targetGainDb[b] = params.bandGainsDb[b].toDouble()
        targetPreampDb = params.preampDb.toDouble()
        targetMix = if (params.enabled) 1.0 else 0.0
        if (firstApply) {
            // Nothing has been heard yet: start at the target instead of ramping.
            for (b in 0 until bandCount) {
                currentGainDb[b] = targetGainDb[b]
                retune(b)
            }
            currentPreampDb = targetPreampDb
            currentPreampLinear = dbToLinear(currentPreampDb)
            mix = targetMix
        }
    }

    /**
     * Processes [frames] interleaved frames of [buffer] in place.
     * Returns pre-ceiling samples outside −1…1. Active output is saturated to this range.
     */
    fun process(buffer: FloatArray, frames: Int, nowNanos: Long = 0L): Int {
        val channels = channelCount
        if (channels == 0 || frames <= 0) return 0
        var clipped = 0
        var peakLeft = 0f
        var peakRight = 0f
        var squaresLeft = 0.0
        var squaresRight = 0.0

        var frame = 0
        while (frame < frames) {
            val n = min(SUB_BLOCK, frames - frame)
            stepGains()
            val preStart = currentPreampLinear
            val preEnd = stepPreamp()
            val preDelta = (preEnd - preStart) / n

            for (i in 0 until n) {
                val pre = preStart + preDelta * (i + 1)
                if (mix != targetMix) {
                    mix = if (mix < targetMix) {
                        min(targetMix, mix + mixStep)
                    } else {
                        maxOf(targetMix, mix - mixStep)
                    }
                }
                val wetShare = mix
                val dryShare = 1.0 - mix
                val base = (frame + i) * channels
                for (c in 0 until channels) {
                    val index = base + c
                    val dry = buffer[index].toDouble()
                    var wet = dry * pre
                    for (b in 0 until bandCount) {
                        if (!bandRunning[b]) continue
                        val k = b * channels + c
                        val co = coefficients[b]
                        val y = co.b0 * wet + z1[k]
                        var s1 = co.b1 * wet - co.a1 * y + z2[k]
                        var s2 = co.b2 * wet - co.a2 * y
                        if (abs(s1) < DENORMAL_FLOOR) s1 = 0.0
                        if (abs(s2) < DENORMAL_FLOOR) s2 = 0.0
                        z1[k] = s1
                        z2[k] = s2
                        wet = y
                    }
                    val mixed = (dry * dryShare + wet * wetShare).toFloat()
                    val over = mixed > 1f || mixed < -1f
                    if (over) clipped++
                    // Sample safety ceiling for both PCM encodings. This is hard
                    // saturation, not an A1 limiter or a true-peak guarantee.
                    // A fully dry bypass remains bit-exact, including float overs.
                    val out = if (wetShare > 0.0 && over) mixed.coerceIn(-1f, 1f) else mixed
                    buffer[index] = out
                    val magnitude = abs(out)
                    if (c == 0) {
                        if (magnitude > peakLeft) peakLeft = magnitude
                        squaresLeft += out * out
                    } else if (c == 1) {
                        if (magnitude > peakRight) peakRight = magnitude
                        squaresRight += out * out
                    }
                }
            }
            currentPreampLinear = preEnd
            settleIdleBands()
            frame += n
        }
        if (channels == 1) {
            peakRight = peakLeft
            squaresRight = squaresLeft
        }
        meters?.publish(peakLeft, peakRight, squaresLeft, squaresRight, frames, clipped, nowNanos)
        return clipped
    }

    /** Current (smoothed) gain of a band, for tests and diagnostics. */
    fun currentGainDb(band: Int): Double = currentGainDb[band]

    /** Whether a band is usable at the configured sample rate. */
    fun isBandUsable(band: Int): Boolean = bandUsable[band]

    private fun stepGains() {
        for (b in 0 until bandCount) {
            val current = currentGainDb[b]
            val target = targetGainDb[b]
            if (current == target) continue
            val delta = target - current
            currentGainDb[b] = if (abs(delta) <= GAIN_STEP_DB) target else current + if (delta > 0) GAIN_STEP_DB else -GAIN_STEP_DB
            retune(b)
        }
    }

    /** Moves the preamp one step toward its target and returns the linear gain at the end of the sub-block. */
    private fun stepPreamp(): Double {
        val delta = targetPreampDb - currentPreampDb
        if (delta == 0.0) return currentPreampLinear
        currentPreampDb = if (abs(delta) <= GAIN_STEP_DB) targetPreampDb else currentPreampDb + if (delta > 0) GAIN_STEP_DB else -GAIN_STEP_DB
        return dbToLinear(currentPreampDb)
    }

    private fun retune(band: Int) {
        val coefficientSet = coefficients[band]
        if (!bandUsable[band] || currentGainDb[band] == 0.0) {
            coefficientSet.setIdentity()
        } else {
            coefficientSet.setPeaking(sampleRate, GraphicEqBands.CENTRES_HZ[band], GraphicEqBands.OCTAVE_Q, currentGainDb[band])
            bandRunning[band] = true
        }
    }

    /**
     * A band that has returned to exactly 0 dB is an identity filter; after one
     * more sub-block its memory has drained (identity passes it out within two
     * samples), so it can stop running without a discontinuity.
     */
    private fun settleIdleBands() {
        val channels = channelCount
        for (b in 0 until bandCount) {
            if (!bandRunning[b]) continue
            if (currentGainDb[b] != 0.0 || targetGainDb[b] != 0.0 || !coefficients[b].isIdentity) continue
            var drained = true
            for (c in 0 until channels) {
                val k = b * channels + c
                if (z1[k] != 0.0 || z2[k] != 0.0) {
                    drained = false
                    break
                }
            }
            if (drained) bandRunning[b] = false
        }
    }

    companion object {
        const val MAX_CHANNELS = 8
        const val SUB_BLOCK = 32
        const val GAIN_STEP_DB = 0.25
        const val BYPASS_RAMP_SECONDS = 0.02
        private const val DENORMAL_FLOOR = 1e-30

        fun dbToLinear(db: Double): Double = if (db == 0.0) 1.0 else 10.0.pow(db / 20.0)
    }
}
