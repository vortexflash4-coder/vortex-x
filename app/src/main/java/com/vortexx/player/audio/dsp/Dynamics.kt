package com.vortexx.player.audio.dsp

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/**
 * Look-ahead limiter: no output sample is larger than [CEILING] (−0.5 dBFS).
 *
 * How: every frame is delayed by [latencyFrames] (5 ms). For each incoming
 * frame the gain it would need is computed; the gain applied to the delayed
 * frame is the lowest needed gain of the last look-ahead + hold window,
 * released slowly and then averaged over the look-ahead. The average only
 * contains values at or below what the delayed frame needs, so the result is
 * at or below the ceiling, and the gain reaches it as a 5 ms slope instead of
 * a step. The hold (12 ms) keeps the gain still between the two peaks of a
 * bass cycle down to about 30 Hz, so a steady bass note is turned down, not
 * bent. All channels share one gain, so the stereo picture does not move.
 *
 * It limits sample values. Peaks between samples can be slightly higher after
 * the phone's own converter; the 0.5 dB margin is there for that.
 *
 * Real-time rules: arrays are allocated in [configure]; [process] does not
 * allocate, lock or call into the platform.
 */
class LookaheadLimiter {

    /** Delay between input and output, in frames. */
    var latencyFrames: Int = 0
        private set

    private var channels = 0
    private var window = 0
    private var releaseCoefficient = 0.0

    private var delay = DoubleArray(0)
    private var delayFrame = 0

    private var smooth = DoubleArray(0)
    private var smoothIndex = 0
    private var smoothSum = 0.0
    private var reducedCount = 0

    // Sliding-window minimum: gains in increasing order with the frame each one arrived at.
    private var queueGain = DoubleArray(0)
    private var queueFrame = LongArray(0)
    private var queueHead = 0
    private var queueSize = 0

    private var held = 1.0
    private var frameNumber = 0L

    /** Prepares for a stream format. Allocates; never call from the audio callback's hot path. */
    fun configure(sampleRate: Int, channelCount: Int) {
        channels = channelCount
        latencyFrames = max(1, (LOOKAHEAD_SECONDS * sampleRate).roundToInt())
        window = latencyFrames + (HOLD_SECONDS * sampleRate).roundToInt() + 1
        releaseCoefficient = 1.0 - exp(-1.0 / (RELEASE_SECONDS * sampleRate))
        delay = DoubleArray(latencyFrames * channelCount)
        smooth = DoubleArray(latencyFrames)
        queueGain = DoubleArray(window + 1)
        queueFrame = LongArray(window + 1)
        reset()
    }

    /** Empties the delay and returns the gain to 1. */
    fun reset() {
        delay.fill(0.0)
        delayFrame = 0
        smooth.fill(1.0)
        smoothIndex = 0
        smoothSum = latencyFrames.toDouble()
        reducedCount = 0
        queueHead = 0
        queueSize = 0
        held = 1.0
        frameNumber = 0L
    }

    /**
     * Takes one frame (one sample per channel) in [frame] and replaces it with
     * the limited frame from [latencyFrames] ago. Returns the gain it applied
     * (1 = untouched).
     */
    fun process(frame: DoubleArray): Double {
        val count = channels
        if (count == 0 || latencyFrames == 0) return 1.0
        var peak = 0.0
        for (c in 0 until count) {
            val magnitude = abs(frame[c])
            if (magnitude > peak) peak = magnitude
        }
        val needed = if (peak <= CEILING) 1.0 else CEILING / peak

        // Lowest needed gain of the last `window` frames.
        val capacity = queueGain.size
        while (queueSize > 0) {
            val last = (queueHead + queueSize - 1) % capacity
            if (queueGain[last] >= needed) queueSize-- else break
        }
        val slot = (queueHead + queueSize) % capacity
        queueGain[slot] = needed
        queueFrame[slot] = frameNumber
        queueSize++
        while (queueFrame[queueHead] <= frameNumber - window) {
            queueHead = (queueHead + 1) % capacity
            queueSize--
        }
        val lowest = queueGain[queueHead]
        frameNumber++

        // Down at once, up slowly.
        held = if (lowest < held) {
            lowest
        } else {
            val next = held + (lowest - held) * releaseCoefficient
            if (lowest >= 1.0 && 1.0 - next < SNAP_TO_UNITY) 1.0 else next
        }

        // Average over the look-ahead: turns the step down into a slope that ends when the peak arrives.
        val old = smooth[smoothIndex]
        if (old < 1.0) reducedCount--
        if (held < 1.0) reducedCount++
        smooth[smoothIndex] = held
        smoothSum += held - old
        smoothIndex++
        if (smoothIndex == latencyFrames) {
            smoothIndex = 0
            // Rebuild the running sum once per window so rounding cannot build up.
            var total = 0.0
            for (i in 0 until latencyFrames) total += smooth[i]
            smoothSum = total
        }
        val gain = if (reducedCount == 0) 1.0 else smoothSum / latencyFrames

        val base = delayFrame * count
        for (c in 0 until count) {
            val delayed = delay[base + c]
            delay[base + c] = frame[c]
            var out = delayed * gain
            // Rounding can leave the last bit above the ceiling; the ceiling is a promise.
            if (out > CEILING) out = CEILING else if (out < -CEILING) out = -CEILING
            frame[c] = out
        }
        delayFrame++
        if (delayFrame == latencyFrames) delayFrame = 0
        return gain
    }

    companion object {
        const val CEILING_DB = -0.5

        /** The ceiling as a sample value: 10^(−0.5 / 20). */
        const val CEILING = 0.9440608762859234
        const val LOOKAHEAD_SECONDS = 0.005
        const val HOLD_SECONDS = 0.012
        const val RELEASE_SECONDS = 0.150
        private const val SNAP_TO_UNITY = 1e-9
    }
}

/**
 * Three-band compressor: bass (below 120 Hz), middle, treble (above 2.5 kHz).
 * Each band is turned down when it gets loud and lifted a little overall, so
 * boosted bass stays strong without one drum hit overloading everything.
 *
 * The bands are split with 4th-order Linkwitz-Riley filters (two Butterworth
 * stages each) and the bass band passes one all-pass, so the three bands add
 * back to the input with a flat level at every frequency (phase is shifted,
 * level is not). All channels share one gain per band.
 *
 * One control, [setAmount] 0…100, sets threshold, ratio and make-up of the
 * three bands together. Real-time rules as for the rest of the chain.
 */
class MultibandCompressor {

    private var channels = 0
    private var amount = 0.0

    private val lowPass1 = BiquadCoefficients()
    private val highPass1 = BiquadCoefficients()
    private val lowPass2 = BiquadCoefficients()
    private val highPass2 = BiquadCoefficients()
    private val allPass2 = BiquadCoefficients()

    // Nine second-order sections per channel, two state values each.
    private var state = DoubleArray(0)

    private val low = DoubleArray(DspChain.MAX_CHANNELS)
    private val mid = DoubleArray(DspChain.MAX_CHANNELS)
    private val high = DoubleArray(DspChain.MAX_CHANNELS)

    private val envelope = DoubleArray(BANDS)
    private val threshold = DoubleArray(BANDS)
    private val slope = DoubleArray(BANDS)
    private val makeUp = DoubleArray(BANDS)
    private val attack = DoubleArray(BANDS)
    private val release = DoubleArray(BANDS)
    private val reduction = DoubleArray(BANDS)

    /** Prepares for a stream format and keeps the amount. Allocates. */
    fun configure(sampleRate: Int, channelCount: Int) {
        channels = channelCount
        // Keep the upper split below a quarter of the sample rate for very low rates.
        val upper = min(HIGH_SPLIT_HZ, 0.25 * sampleRate)
        val lower = min(LOW_SPLIT_HZ, upper / 4.0)
        lowPass1.setLowPass(sampleRate, lower, BUTTERWORTH_Q)
        highPass1.setHighPass(sampleRate, lower, BUTTERWORTH_Q)
        lowPass2.setLowPass(sampleRate, upper, BUTTERWORTH_Q)
        highPass2.setHighPass(sampleRate, upper, BUTTERWORTH_Q)
        allPass2.setAllPass(sampleRate, upper, BUTTERWORTH_Q)
        state = DoubleArray(channelCount * STATE_PER_CHANNEL)
        for (band in 0 until BANDS) {
            attack[band] = 1.0 - exp(-1.0 / (ATTACK_SECONDS[band] * sampleRate))
            release[band] = 1.0 - exp(-1.0 / (RELEASE_SECONDS[band] * sampleRate))
        }
        setAmount(amount)
        reset()
    }

    /** Clears the filters and the level memory. */
    fun reset() {
        state.fill(0.0)
        envelope.fill(0.0)
        reduction.fill(1.0)
    }

    /** Sets how hard all three bands work, 0…100. */
    fun setAmount(amount: Double) {
        val a = amount.coerceIn(0.0, 100.0)
        this.amount = a
        for (band in 0 until BANDS) {
            val thresholdDb = thresholdDb(band, a)
            val ratio = ratio(band, a)
            threshold[band] = 10.0.pow(thresholdDb / 20.0)
            slope[band] = 1.0 - 1.0 / ratio
            makeUp[band] = 10.0.pow(makeUpDb(band, a) / 20.0)
        }
    }

    /** Sets one band directly, in place of the values [setAmount] derives. A ratio of 1 with 0 dB make-up leaves the band alone. */
    fun setBand(band: Int, thresholdDb: Double, ratio: Double, makeUpDb: Double) {
        threshold[band] = 10.0.pow(thresholdDb / 20.0)
        slope[band] = 1.0 - 1.0 / ratio.coerceAtLeast(1.0)
        makeUp[band] = 10.0.pow(makeUpDb / 20.0)
    }

    /** The gain the band's compressor applied to the last frame (1 = untouched), without make-up. */
    fun reduction(band: Int): Double = reduction[band]

    /**
     * Compresses one frame in place. [mix] 0…1 fades between the untouched
     * frame and the compressed one (used when switching on and off).
     */
    fun process(frame: DoubleArray, mix: Double) {
        val count = channels
        var peakLow = 0.0
        var peakMid = 0.0
        var peakHigh = 0.0
        for (c in 0 until count) {
            val k = c * STATE_PER_CHANNEL
            val x = frame[c]
            var l = section(lowPass1, k, x)
            l = section(lowPass1, k + 2, l)
            var rest = section(highPass1, k + 4, x)
            rest = section(highPass1, k + 6, rest)
            var m = section(lowPass2, k + 8, rest)
            m = section(lowPass2, k + 10, m)
            var h = section(highPass2, k + 12, rest)
            h = section(highPass2, k + 14, h)
            l = section(allPass2, k + 16, l)
            low[c] = l
            mid[c] = m
            high[c] = h
            val magnitudeLow = abs(l)
            val magnitudeMid = abs(m)
            val magnitudeHigh = abs(h)
            if (magnitudeLow > peakLow) peakLow = magnitudeLow
            if (magnitudeMid > peakMid) peakMid = magnitudeMid
            if (magnitudeHigh > peakHigh) peakHigh = magnitudeHigh
        }
        val gainLow = bandGain(LOW, peakLow)
        val gainMid = bandGain(MID, peakMid)
        val gainHigh = bandGain(HIGH, peakHigh)
        for (c in 0 until count) {
            val compressed = low[c] * gainLow + mid[c] * gainMid + high[c] * gainHigh
            frame[c] = frame[c] + (compressed - frame[c]) * mix
        }
    }

    private fun section(co: BiquadCoefficients, index: Int, x: Double): Double {
        val y = co.b0 * x + state[index]
        var s1 = co.b1 * x - co.a1 * y + state[index + 1]
        var s2 = co.b2 * x - co.a2 * y
        if (abs(s1) < DENORMAL_FLOOR) s1 = 0.0
        if (abs(s2) < DENORMAL_FLOOR) s2 = 0.0
        state[index] = s1
        state[index + 1] = s2
        return y
    }

    /** Follows the band's level and returns its gain including make-up. */
    private fun bandGain(band: Int, peak: Double): Double {
        var level = envelope[band]
        level += (peak - level) * if (peak > level) attack[band] else release[band]
        if (level < DENORMAL_FLOOR) level = 0.0
        envelope[band] = level
        val limit = threshold[band]
        val gain = if (level > limit) exp(slope[band] * ln(limit / level)) else 1.0
        reduction[band] = gain
        return gain * makeUp[band]
    }

    companion object {
        const val BANDS = 3
        const val LOW = 0
        const val MID = 1
        const val HIGH = 2
        const val LOW_SPLIT_HZ = 120.0
        const val HIGH_SPLIT_HZ = 2500.0
        const val BUTTERWORTH_Q = 0.7071067811865476
        private const val STATE_PER_CHANNEL = 18
        private const val DENORMAL_FLOOR = 1e-30
        private val ATTACK_SECONDS = doubleArrayOf(0.012, 0.006, 0.002)
        private val RELEASE_SECONDS = doubleArrayOf(0.160, 0.120, 0.080)

        /** Level above which the band is turned down, in dBFS, for [amount] 0…100. */
        fun thresholdDb(band: Int, amount: Double): Double {
            val a = amount / 100.0
            return when (band) {
                LOW -> -10.0 - 14.0 * a
                MID -> -8.0 - 12.0 * a
                else -> -10.0 - 12.0 * a
            }
        }

        /** How many dB of input above the threshold give 1 dB of output. */
        fun ratio(band: Int, amount: Double): Double {
            val a = amount / 100.0
            return when (band) {
                LOW -> 1.5 + 2.5 * a
                MID -> 1.3 + 1.7 * a
                else -> 1.5 + 2.0 * a
            }
        }

        /** Lift applied to the whole band: half of what a full-scale peak is turned down by. */
        fun makeUpDb(band: Int, amount: Double): Double =
            0.5 * -thresholdDb(band, amount) * (1.0 - 1.0 / ratio(band, amount))
    }
}

/**
 * Lock-free hand-over of the dynamics gains from the audio thread to the UI:
 * the lowest gain (strongest reduction) of the limiter and of each compressor
 * band since the last drain. Same rules as [MeterBus].
 */
class DynamicsMeters {
    private val limiter = AtomicInteger(UNITY)
    private val bands = arrayOf(AtomicInteger(UNITY), AtomicInteger(UNITY), AtomicInteger(UNITY))

    /** Audio thread: lowest gains of one buffer (1 = untouched). */
    fun publish(limiterGain: Float, lowGain: Float, midGain: Float, highGain: Float) {
        lower(limiter, limiterGain)
        lower(bands[MultibandCompressor.LOW], lowGain)
        lower(bands[MultibandCompressor.MID], midGain)
        lower(bands[MultibandCompressor.HIGH], highGain)
    }

    /** UI thread: moves the values into [out] and starts a new measurement. */
    fun drain(out: DynamicsReading) {
        out.limiterGain = Float.fromBits(limiter.getAndSet(UNITY))
        out.lowGain = Float.fromBits(bands[MultibandCompressor.LOW].getAndSet(UNITY))
        out.midGain = Float.fromBits(bands[MultibandCompressor.MID].getAndSet(UNITY))
        out.highGain = Float.fromBits(bands[MultibandCompressor.HIGH].getAndSet(UNITY))
    }

    private fun lower(target: AtomicInteger, value: Float) {
        while (true) {
            val current = target.get()
            if (value >= Float.fromBits(current)) return
            if (target.compareAndSet(current, value.toRawBits())) return
        }
    }

    private companion object {
        val UNITY = 1f.toRawBits()
    }
}

/** Reusable reading filled by [DynamicsMeters.drain]. Gains are linear, 1 = untouched. */
class DynamicsReading {
    var limiterGain = 1f
    var lowGain = 1f
    var midGain = 1f
    var highGain = 1f
}
