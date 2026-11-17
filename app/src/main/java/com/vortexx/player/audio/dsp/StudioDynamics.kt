package com.vortexx.player.audio.dsp

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.ln
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt

/** Ranges and starting values of the Dynamics tab (compressor, balance, room). */
object StudioTone {
    const val MIN_THRESHOLD_DB = -36f
    const val MAX_THRESHOLD_DB = 0f
    const val DEFAULT_THRESHOLD_DB = -18f

    const val MIN_RATIO = 1f
    const val MAX_RATIO = 10f
    const val DEFAULT_RATIO = 2.5f

    const val MIN_ATTACK_MS = 1
    const val MAX_ATTACK_MS = 100
    const val DEFAULT_ATTACK_MS = 12

    const val MIN_RELEASE_MS = 20
    const val MAX_RELEASE_MS = 1000
    const val DEFAULT_RELEASE_MS = 140

    /** −100 = only the left channel, 0 = centre, +100 = only the right channel. */
    const val MAX_BALANCE = 100

    const val MAX_MIX_PERCENT = 100
    const val DEFAULT_MIX_PERCENT = 8
}

/**
 * The room the reverb imitates. Feedback sets how long the tail lasts, damping how fast the highs die away.
 * [level] scales the room's sound so that, for noise that differs between the channels, it is as loud as
 * the input (measured by the tests): the Mix control is then a true balance between dry and room.
 */
enum class ReverbType(val label: String, val feedback: Double, val damping: Double, val level: Double) {
    Off("Off", 0.0, 0.0, 1.0),
    SmallRoom("Small room", 0.62, 0.50, 2.853),
    Studio("Studio", 0.78, 0.40, 2.555),
    Hall("Large hall", 0.89, 0.30, 2.129),
    ;

    companion object {
        /** The type stored under [name]; anything unknown is [Off]. */
        fun fromName(name: String?): ReverbType = entries.firstOrNull { it.name == name } ?: Off
    }
}

/** The compressor's rule: what comes out for a level that goes in. Shared by the audio thread and the curve on screen. */
object StudioCurve {
    /** Output level (dBFS) of a steady input level, for a hard-knee compressor without make-up gain. */
    fun outputDb(inputDb: Double, thresholdDb: Double, ratio: Double): Double =
        if (inputDb <= thresholdDb) inputDb else thresholdDb + (inputDb - thresholdDb) / ratio.coerceAtLeast(1.0)
}

/**
 * A full-band, stereo-linked compressor with explicit threshold, ratio, attack and release.
 *
 * The level is a peak envelope of the larger of the channels' sample magnitudes: it follows a louder sample
 * at once and falls back with the release time constant (63 % of the level in that time), so a steady tone
 * is seen at its peak and not at its ripple. The turn-down the rule asks for rises with the attack time
 * constant (the time to cover 63 % of a step, in decibels) and follows the falling level on release. There
 * is no make-up gain. With a steady tone the output level is [StudioCurve.outputDb].
 *
 * Real-time rules: nothing is allocated after construction.
 */
class StudioCompressor {

    private var sampleRate = 48_000
    private var thresholdDb = StudioTone.DEFAULT_THRESHOLD_DB.toDouble()
    private var thresholdLinear = dbToLinear(thresholdDb)
    private var slope = 1.0 - 1.0 / StudioTone.DEFAULT_RATIO
    private var attackMs = StudioTone.DEFAULT_ATTACK_MS
    private var releaseMs = StudioTone.DEFAULT_RELEASE_MS
    private var attackCoefficient = 0.0

    private var envelope = 0.0
    private var envelopeDecay = 0.0
    private var reductionDb = 0.0
    private var fallCoefficient = 0.0

    /** The gain applied to the last frame (1 = untouched). */
    var gain: Double = 1.0
        private set

    fun configure(sampleRate: Int) {
        this.sampleRate = sampleRate
        updateTimes()
        reset()
    }

    fun reset() {
        envelope = 0.0
        reductionDb = 0.0
        gain = 1.0
    }

    /** Sets the rule. Safe to call at any time; the turn-down moves smoothly to what the new rule asks. */
    fun set(thresholdDb: Double, ratio: Double, attackMs: Int, releaseMs: Int) {
        this.thresholdDb = thresholdDb
        thresholdLinear = dbToLinear(thresholdDb)
        slope = 1.0 - 1.0 / ratio.coerceAtLeast(1.0)
        this.attackMs = attackMs
        this.releaseMs = releaseMs
        updateTimes()
    }

    private fun updateTimes() {
        attackCoefficient = 1.0 - exp(-1.0 / (max(attackMs, 1) * 0.001 * sampleRate))
        envelopeDecay = exp(-1.0 / (max(releaseMs, 1) * 0.001 * sampleRate))
        fallCoefficient = 1.0 - exp(-1.0 / (FALL_SMOOTHING_MS * 0.001 * sampleRate))
    }

    /**
     * Compresses one frame of [channels] samples in place. [mix] 0…1 fades between the untouched frame and
     * the compressed one (used when the compressor is switched on and off).
     */
    fun process(frame: DoubleArray, channels: Int, mix: Double) {
        var peak = 0.0
        for (c in 0 until channels) {
            val magnitude = abs(frame[c])
            if (magnitude > peak) peak = magnitude
        }
        envelope = if (peak > envelope) peak else envelope * envelopeDecay
        if (envelope < ENVELOPE_FLOOR) envelope = 0.0
        var wanted = 0.0
        if (envelope > thresholdLinear) wanted = LN_TO_DB * ln(envelope / thresholdLinear) * slope
        if (wanted > 0.0 || reductionDb > 0.0) {
            // Up with the attack time; down along the falling level, with a few ms of smoothing so that
            // moving the threshold or ratio never steps the sound.
            reductionDb += (wanted - reductionDb) * if (wanted > reductionDb) attackCoefficient else fallCoefficient
            if (reductionDb < SNAP_DB) reductionDb = 0.0
        }
        gain = if (reductionDb == 0.0) 1.0 else exp(-reductionDb * DB_TO_LN)
        val applied = 1.0 + (gain - 1.0) * mix
        if (applied != 1.0) for (c in 0 until channels) frame[c] *= applied
    }

    private companion object {
        const val LN_TO_DB = 8.685889638065035 // 20 / ln(10)
        const val DB_TO_LN = 0.11512925464970229 // ln(10) / 20
        const val SNAP_DB = 1e-9
        const val ENVELOPE_FLOOR = 1e-12
        const val FALL_SMOOTHING_MS = 3.0

        fun dbToLinear(db: Double): Double = 10.0.pow(db / 20.0)
    }
}

/**
 * A small room: eight damped feedback combs and four all-passes per channel (the classic Schroeder/Moorer
 * design, tuned for 44.1 kHz and scaled to the stream's rate), the right channel slightly longer than the
 * left so the room has width. [process] returns only the room's sound, normalised so that for noise it is
 * about as loud as the input; the caller crossfades it with the dry signal.
 *
 * Streams with one or two channels get the room; with more channels it is not used.
 * Real-time rules: all buffers are allocated in [configure].
 */
class RoomReverb {

    private var channels = 0
    private var combBuffers: Array<DoubleArray> = emptyArray()
    private val combPosition = IntArray(NETWORKS * COMBS)
    private val combStore = DoubleArray(NETWORKS * COMBS)
    private var allPassBuffers: Array<DoubleArray> = emptyArray()
    private val allPassPosition = IntArray(NETWORKS * ALL_PASSES)

    private var feedback = ReverbType.Studio.feedback
    private var damp = ReverbType.Studio.damping
    private var outputGain = ReverbType.Studio.level

    /** True when this stream's channel count can have the room. */
    val usable: Boolean get() = channels in 1..NETWORKS

    fun configure(sampleRate: Int, channelCount: Int) {
        channels = channelCount
        val scale = sampleRate / 44_100.0
        val spread = (SPREAD * scale).roundToInt()
        combBuffers = Array(NETWORKS * COMBS) { index ->
            val network = index / COMBS
            DoubleArray(max(8, (COMB_LENGTHS[index % COMBS] * scale).roundToInt() + network * spread))
        }
        allPassBuffers = Array(NETWORKS * ALL_PASSES) { index ->
            val network = index / ALL_PASSES
            DoubleArray(max(8, (ALL_PASS_LENGTHS[index % ALL_PASSES] * scale).roundToInt() + network * spread))
        }
        reset()
    }

    fun reset() {
        for (buffer in combBuffers) buffer.fill(0.0)
        for (buffer in allPassBuffers) buffer.fill(0.0)
        combPosition.fill(0)
        combStore.fill(0.0)
        allPassPosition.fill(0)
    }

    fun setType(type: ReverbType) {
        if (type == ReverbType.Off) return
        feedback = type.feedback
        damp = type.damping
        outputGain = type.level
    }

    /** Reads [channels] samples of [frame] and writes the room's sound for each channel into [wet]. */
    fun process(frame: DoubleArray, wet: DoubleArray) {
        val count = channels
        if (count !in 1..NETWORKS) return
        val input = (if (count == 2) frame[0] + frame[1] else 2.0 * frame[0]) * INPUT_GAIN
        for (network in 0 until count) {
            var sum = 0.0
            val base = network * COMBS
            for (i in 0 until COMBS) {
                val buffer = combBuffers[base + i]
                val position = combPosition[base + i]
                val output = buffer[position]
                var store = output * (1.0 - damp) + combStore[base + i] * damp
                if (abs(store) < DENORMAL_FLOOR) store = 0.0
                combStore[base + i] = store
                var next = input + store * feedback
                if (abs(next) < DENORMAL_FLOOR) next = 0.0
                buffer[position] = next
                combPosition[base + i] = if (position + 1 == buffer.size) 0 else position + 1
                sum += output
            }
            val allBase = network * ALL_PASSES
            for (i in 0 until ALL_PASSES) {
                val buffer = allPassBuffers[allBase + i]
                val position = allPassPosition[allBase + i]
                val buffered = buffer[position]
                val output = buffered - sum
                var next = sum + buffered * ALL_PASS_FEEDBACK
                if (abs(next) < DENORMAL_FLOOR) next = 0.0
                buffer[position] = next
                allPassPosition[allBase + i] = if (position + 1 == buffer.size) 0 else position + 1
                sum = output
            }
            wet[network] = sum * outputGain
        }
    }

    companion object {
        const val NETWORKS = 2
        private const val COMBS = 8
        private const val ALL_PASSES = 4
        private const val SPREAD = 23
        private const val INPUT_GAIN = 0.015
        private const val ALL_PASS_FEEDBACK = 0.5
        private const val DENORMAL_FLOOR = 1e-30
        private val COMB_LENGTHS = intArrayOf(1116, 1188, 1277, 1356, 1422, 1491, 1557, 1617)
        private val ALL_PASS_LENGTHS = intArrayOf(556, 441, 341, 225)
    }
}

/**
 * Lock-free hand-over of the compressor's levels from the audio thread to the UI: the highest sample
 * going in and coming out, and the strongest turn-down, since the last drain. Same rules as [MeterBus].
 */
class StudioMeters {
    private val inPeak = AtomicInteger(ZERO)
    private val outPeak = AtomicInteger(ZERO)
    private val gain = AtomicInteger(UNITY)

    /** Audio thread: the values of one buffer. */
    fun publish(inputPeak: Float, outputPeak: Float, lowestGain: Float) {
        raise(inPeak, inputPeak)
        raise(outPeak, outputPeak)
        while (true) {
            val current = gain.get()
            if (lowestGain >= Float.fromBits(current)) return
            if (gain.compareAndSet(current, lowestGain.toRawBits())) return
        }
    }

    /** UI thread: moves the values into [out] and starts a new measurement. */
    fun drain(out: StudioReading) {
        out.inputPeak = Float.fromBits(inPeak.getAndSet(ZERO))
        out.outputPeak = Float.fromBits(outPeak.getAndSet(ZERO))
        out.gain = Float.fromBits(gain.getAndSet(UNITY))
    }

    private fun raise(target: AtomicInteger, value: Float) {
        while (true) {
            val current = target.get()
            if (value <= Float.fromBits(current)) return
            if (target.compareAndSet(current, value.toRawBits())) return
        }
    }

    private companion object {
        val ZERO = 0f.toRawBits()
        val UNITY = 1f.toRawBits()
    }
}

/** Reusable reading filled by [StudioMeters.drain]. Peaks are linear (1 = full scale), the gain is linear (1 = untouched). */
class StudioReading {
    var inputPeak = 0f
    var outputPeak = 0f
    var gain = 1f
}
