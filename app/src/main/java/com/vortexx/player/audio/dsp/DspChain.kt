package com.vortexx.player.audio.dsp

import kotlin.math.abs
import kotlin.math.min
import kotlin.math.pow

/**
 * The Audio Lab's signal chain on interleaved float samples (−1…1):
 *
 * preamp → 10-band EQ → bass shelf → kick band → speaker protect →
 * 3-band compressor → stereo width → bypass crossfade → limiter →
 * clip count → meters.
 *
 * Real-time rules: every array is allocated in [configure]; [process] does not
 * allocate, lock or call into the platform. Nothing changes as a step: gains
 * move at most [GAIN_STEP_DB] per [SUB_BLOCK] frames, and every stage that is
 * switched on or off (bypass, speaker protect, compressor, limiter) fades over
 * [BYPASS_RAMP_SECONDS]. A stage at its neutral setting does not run at all,
 * so the default settings leave every sample bit-identical.
 *
 * Latency: 0 samples, except while the limiter is on; it looks 5 ms ahead and
 * the whole signal is that much later ([latencyFrames]).
 */
class DspChain(
    private val meters: MeterBus? = null,
    private val dynamics: DynamicsMeters? = null,
) {

    var sampleRate: Int = 0
        private set
    var channelCount: Int = 0
        private set

    // Gain filters: the 10 EQ bands, then the bass shelf and the kick band.
    private val eqCount = GraphicEqBands.COUNT
    private val filterCount = eqCount + 2
    private val bassIndex = eqCount
    private val punchIndex = eqCount + 1
    private val coefficients = Array(filterCount) { BiquadCoefficients() }
    private val bandUsable = BooleanArray(filterCount)
    private val bandRunning = BooleanArray(filterCount)
    private val currentGainDb = DoubleArray(filterCount)
    private val targetGainDb = DoubleArray(filterCount)

    // Filter state, index = filter * channelCount + channel (transposed direct form II).
    private var z1 = DoubleArray(0)
    private var z2 = DoubleArray(0)

    private var currentPreampDb = 0.0
    private var targetPreampDb = 0.0
    private var currentPreampLinear = 1.0

    private var mix = 1.0
    private var targetMix = 1.0
    private var mixStep = 0.0

    // Speaker protect: a high-pass, faded in and out. Two state values per channel.
    private val protectFilter = BiquadCoefficients()
    private var protectState = DoubleArray(0)
    private var protectMix = 0.0
    private var protectTarget = 0.0

    private val compressor = MultibandCompressor()
    private var compressorMix = 0.0
    private var compressorTarget = 0.0
    private var compressorAmount = 0.0
    private var compressorAmountTarget = 0.0

    private var width = 1.0
    private var widthTarget = 1.0

    private val limiter = LookaheadLimiter()
    private var limiterRunning = false
    private var limiterMix = 0.0
    private var limiterTarget = 0.0
    private var limiterWarmUp = 0

    private val dryFrame = DoubleArray(MAX_CHANNELS)
    private val wetFrame = DoubleArray(MAX_CHANNELS)
    private val outFrame = DoubleArray(MAX_CHANNELS)
    private val directFrame = DoubleArray(MAX_CHANNELS)

    private var applied: DspParameters? = null

    /** Frames by which the output is later than the input right now (0 unless the limiter is in). */
    val latencyFrames: Int
        get() = if (limiterRunning && limiterWarmUp == 0 && limiterMix >= 0.5) limiter.latencyFrames else 0

    /** Prepares for a stream format. Allocates; never call from the audio callback. */
    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..MAX_CHANNELS) { "channelCount must be 1..$MAX_CHANNELS" }
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        z1 = DoubleArray(filterCount * channelCount)
        z2 = DoubleArray(filterCount * channelCount)
        mixStep = 1.0 / (BYPASS_RAMP_SECONDS * sampleRate)
        for (b in 0 until filterCount) {
            bandUsable[b] = b >= eqCount || GraphicEqBands.isUsable(b, sampleRate)
            retune(b)
        }
        protectFilter.setHighPass(sampleRate, BassTone.PROTECT_HZ, MultibandCompressor.BUTTERWORTH_Q)
        protectState = DoubleArray(2 * channelCount)
        compressor.configure(sampleRate, channelCount)
        limiter.configure(sampleRate, channelCount)
        if (limiterRunning) limiterWarmUp = 0
    }

    /** Clears filter, compressor and limiter memory (after a seek or a format change). */
    fun resetState() {
        z1.fill(0.0)
        z2.fill(0.0)
        protectState.fill(0.0)
        compressor.reset()
        limiter.reset()
    }

    /**
     * Takes a new parameter snapshot. Cheap when [params] is the snapshot
     * already applied (reference check), so it can be called every buffer.
     */
    fun apply(params: DspParameters) {
        if (params === applied) return
        val firstApply = applied == null
        applied = params
        for (b in 0 until eqCount) targetGainDb[b] = params.bandGainsDb[b].toDouble()
        targetGainDb[bassIndex] = params.bassDb.toDouble()
        targetGainDb[punchIndex] = params.punchDb.toDouble()
        targetPreampDb = params.preampDb.toDouble()
        targetMix = if (params.enabled) 1.0 else 0.0

        val protect = if (params.speakerProtect) 1.0 else 0.0
        if (protect == 1.0 && protectMix == 0.0 && protectTarget == 0.0) protectState.fill(0.0)
        protectTarget = protect

        if (params.compressor > 0) {
            compressorAmountTarget = params.compressor.toDouble()
            if (compressorMix == 0.0 && compressorTarget == 0.0) {
                // Coming from off: start clean at the wanted amount, then fade in.
                compressorAmount = compressorAmountTarget
                compressor.setAmount(compressorAmount)
                compressor.reset()
            }
            compressorTarget = 1.0
        } else {
            compressorTarget = 0.0
        }

        widthTarget = params.widthPercent.coerceIn(0, BassTone.MAX_WIDTH_PERCENT) / 100.0

        val wantLimiter = params.enabled && params.limiter
        limiterTarget = if (wantLimiter) 1.0 else 0.0
        if (wantLimiter && !limiterRunning) {
            // Fill the look-ahead first, then fade from the direct signal to the limited one.
            limiter.reset()
            limiterRunning = true
            limiterMix = 0.0
            limiterWarmUp = limiter.latencyFrames
        }

        if (firstApply) {
            // Nothing has been heard yet: start at the target instead of ramping.
            for (b in 0 until filterCount) {
                currentGainDb[b] = targetGainDb[b]
                retune(b)
            }
            currentPreampDb = targetPreampDb
            currentPreampLinear = dbToLinear(currentPreampDb)
            mix = targetMix
            protectMix = protectTarget
            compressorMix = compressorTarget
            width = widthTarget
            if (wantLimiter) {
                limiterMix = 1.0
                limiterWarmUp = 0
            }
        }
    }

    /**
     * Processes [frames] interleaved frames of [buffer] in place.
     * Returns the number of output samples outside −1…1 (they will clip).
     */
    fun process(buffer: FloatArray, frames: Int, nowNanos: Long = 0L): Int {
        val channels = channelCount
        if (channels == 0 || frames <= 0) return 0
        var clipped = 0
        var peakLeft = 0f
        var peakRight = 0f
        var squaresLeft = 0.0
        var squaresRight = 0.0
        var limiterLowest = 1.0
        var lowLowest = 1.0
        var midLowest = 1.0
        var highLowest = 1.0

        var frame = 0
        while (frame < frames) {
            val n = min(SUB_BLOCK, frames - frame)
            stepGains()
            stepShaping()
            val preStart = currentPreampLinear
            val preEnd = stepPreamp()
            val preDelta = (preEnd - preStart) / n

            for (i in 0 until n) {
                val pre = preStart + preDelta * (i + 1)
                if (mix != targetMix) mix = approach(mix, targetMix)
                if (protectMix != protectTarget) protectMix = approach(protectMix, protectTarget)
                if (compressorMix != compressorTarget) compressorMix = approach(compressorMix, compressorTarget)
                val protecting = protectMix > 0.0
                val base = (frame + i) * channels

                for (c in 0 until channels) {
                    val dry = buffer[base + c].toDouble()
                    var wet = dry * pre
                    for (b in 0 until filterCount) {
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
                    if (protecting) {
                        val k = c * 2
                        val co = protectFilter
                        val y = co.b0 * wet + protectState[k]
                        var s1 = co.b1 * wet - co.a1 * y + protectState[k + 1]
                        var s2 = co.b2 * wet - co.a2 * y
                        if (abs(s1) < DENORMAL_FLOOR) s1 = 0.0
                        if (abs(s2) < DENORMAL_FLOOR) s2 = 0.0
                        protectState[k] = s1
                        protectState[k + 1] = s2
                        wet += (y - wet) * protectMix
                    }
                    dryFrame[c] = dry
                    wetFrame[c] = wet
                }

                if (compressorMix > 0.0) {
                    compressor.process(wetFrame, compressorMix)
                    val low = compressor.reduction(MultibandCompressor.LOW)
                    val mid = compressor.reduction(MultibandCompressor.MID)
                    val high = compressor.reduction(MultibandCompressor.HIGH)
                    if (low < lowLowest) lowLowest = low
                    if (mid < midLowest) midLowest = mid
                    if (high < highLowest) highLowest = high
                }

                if (channels == 2 && width != 1.0) {
                    val left = wetFrame[0]
                    val right = wetFrame[1]
                    val centre = (left + right) * 0.5
                    val side = (left - right) * 0.5 * width
                    wetFrame[0] = centre + side
                    wetFrame[1] = centre - side
                }

                val wetShare = mix
                val dryShare = 1.0 - mix
                for (c in 0 until channels) outFrame[c] = dryFrame[c] * dryShare + wetFrame[c] * wetShare

                if (limiterRunning) {
                    for (c in 0 until channels) directFrame[c] = outFrame[c]
                    val gain = limiter.process(outFrame)
                    if (limiterWarmUp > 0) {
                        limiterWarmUp--
                        for (c in 0 until channels) outFrame[c] = directFrame[c]
                    } else {
                        if (limiterMix != limiterTarget) limiterMix = approach(limiterMix, limiterTarget)
                        if (limiterMix < 1.0) {
                            for (c in 0 until channels) outFrame[c] = directFrame[c] + (outFrame[c] - directFrame[c]) * limiterMix
                        }
                        if (gain < limiterLowest) limiterLowest = gain
                        if (limiterMix == 0.0 && limiterTarget == 0.0) limiterRunning = false
                    }
                }

                for (c in 0 until channels) {
                    val out = outFrame[c].toFloat()
                    if (out > 1f || out < -1f) clipped++
                    buffer[base + c] = out
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
        dynamics?.publish(limiterLowest.toFloat(), lowLowest.toFloat(), midLowest.toFloat(), highLowest.toFloat())
        return clipped
    }

    /** Current (smoothed) gain of a filter: 0…9 the EQ bands, 10 the bass shelf, 11 the kick band. */
    fun currentGainDb(band: Int): Double = currentGainDb[band]

    /** Whether a band is usable at the configured sample rate. */
    fun isBandUsable(band: Int): Boolean = bandUsable[band]

    private fun approach(current: Double, target: Double): Double =
        if (current < target) min(target, current + mixStep) else maxOf(target, current - mixStep)

    private fun stepGains() {
        for (b in 0 until filterCount) {
            val current = currentGainDb[b]
            val target = targetGainDb[b]
            if (current == target) continue
            val delta = target - current
            currentGainDb[b] = if (abs(delta) <= GAIN_STEP_DB) target else current + if (delta > 0) GAIN_STEP_DB else -GAIN_STEP_DB
            retune(b)
        }
    }

    /** Moves the compressor amount and the stereo width one step toward their targets. */
    private fun stepShaping() {
        if (compressorTarget == 1.0 && compressorAmount != compressorAmountTarget) {
            val delta = compressorAmountTarget - compressorAmount
            compressorAmount = if (abs(delta) <= AMOUNT_STEP) compressorAmountTarget else compressorAmount + if (delta > 0) AMOUNT_STEP else -AMOUNT_STEP
            compressor.setAmount(compressorAmount)
        }
        if (width != widthTarget) {
            val delta = widthTarget - width
            width = if (abs(delta) <= WIDTH_STEP) widthTarget else width + if (delta > 0) WIDTH_STEP else -WIDTH_STEP
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
        val gain = currentGainDb[band]
        if (!bandUsable[band] || gain == 0.0) {
            coefficientSet.setIdentity()
            return
        }
        when {
            band < eqCount -> coefficientSet.setPeaking(sampleRate, GraphicEqBands.CENTRES_HZ[band], GraphicEqBands.OCTAVE_Q, gain)
            band == bassIndex -> coefficientSet.setLowShelf(sampleRate, BassTone.SHELF_HZ, gain)
            else -> coefficientSet.setPeaking(sampleRate, BassTone.PUNCH_HZ, BassTone.PUNCH_Q, gain)
        }
        bandRunning[band] = true
    }

    /**
     * A filter that has returned to exactly 0 dB is an identity filter; after one
     * more sub-block its memory has drained (identity passes it out within two
     * samples), so it can stop running without a discontinuity.
     */
    private fun settleIdleBands() {
        val channels = channelCount
        for (b in 0 until filterCount) {
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
        private const val AMOUNT_STEP = 2.0
        private const val WIDTH_STEP = 0.02
        private const val DENORMAL_FLOOR = 1e-30

        fun dbToLinear(db: Double): Double = if (db == 0.0) 1.0 else 10.0.pow(db / 20.0)
    }
}
