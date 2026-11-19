package com.vortexx.player.audio.dsp

import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** A second-order filter (RBJ cookbook), with the shelf and bell shapes the Jhankar equalizer needs. */
internal class JhBiquad {
    var b0 = 1.0
    var b1 = 0.0
    var b2 = 0.0
    var a1 = 0.0
    var a2 = 0.0

    val isIdentity: Boolean
        get() = b0 == 1.0 && b1 == 0.0 && b2 == 0.0 && a1 == 0.0 && a2 == 0.0

    fun identity() {
        b0 = 1.0; b1 = 0.0; b2 = 0.0; a1 = 0.0; a2 = 0.0
    }

    fun peaking(rate: Double, hz: Double, q: Double, gainDb: Double) {
        if (gainDb == 0.0) return identity()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * hz / rate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha / a
        b0 = (1.0 + alpha * a) / a0
        b1 = -2.0 * c / a0
        b2 = (1.0 - alpha * a) / a0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha / a) / a0
    }

    fun lowShelf(rate: Double, hz: Double, q: Double, gainDb: Double) {
        if (gainDb == 0.0) return identity()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * hz / rate
        val c = cos(w0)
        val t = 2.0 * sqrt(a) * (sin(w0) / (2.0 * q))
        val a0 = (a + 1.0) + (a - 1.0) * c + t
        b0 = a * ((a + 1.0) - (a - 1.0) * c + t) / a0
        b1 = 2.0 * a * ((a - 1.0) - (a + 1.0) * c) / a0
        b2 = a * ((a + 1.0) - (a - 1.0) * c - t) / a0
        a1 = -2.0 * ((a - 1.0) + (a + 1.0) * c) / a0
        a2 = ((a + 1.0) + (a - 1.0) * c - t) / a0
    }

    fun highShelf(rate: Double, hz: Double, q: Double, gainDb: Double) {
        if (gainDb == 0.0) return identity()
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2.0 * PI * hz / rate
        val c = cos(w0)
        val t = 2.0 * sqrt(a) * (sin(w0) / (2.0 * q))
        val a0 = (a + 1.0) - (a - 1.0) * c + t
        b0 = a * ((a + 1.0) + (a - 1.0) * c + t) / a0
        b1 = -2.0 * a * ((a - 1.0) + (a + 1.0) * c) / a0
        b2 = a * ((a + 1.0) + (a - 1.0) * c - t) / a0
        a1 = 2.0 * ((a - 1.0) - (a + 1.0) * c) / a0
        a2 = ((a + 1.0) - (a - 1.0) * c - t) / a0
    }

    fun lowPass(rate: Double, hz: Double, q: Double) {
        val w0 = 2.0 * PI * hz / rate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 - c) / 2.0 / a0
        b1 = (1.0 - c) / a0
        b2 = (1.0 - c) / 2.0 / a0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    fun highPass(rate: Double, hz: Double, q: Double) {
        val w0 = 2.0 * PI * hz / rate
        val alpha = sin(w0) / (2.0 * q)
        val c = cos(w0)
        val a0 = 1.0 + alpha
        b0 = (1.0 + c) / 2.0 / a0
        b1 = -(1.0 + c) / a0
        b2 = (1.0 + c) / 2.0 / a0
        a1 = -2.0 * c / a0
        a2 = (1.0 - alpha) / a0
    }

    /** Magnitude response in dB at [hz], from the coefficients. */
    fun magnitudeDb(hz: Double, rate: Double): Double {
        val w = 2.0 * PI * hz / rate
        val c1 = cos(w)
        val s1 = sin(w)
        val c2 = cos(2 * w)
        val s2 = sin(2 * w)
        val nr = b0 + b1 * c1 + b2 * c2
        val ni = -(b1 * s1 + b2 * s2)
        val dr = 1.0 + a1 * c1 + a2 * c2
        val di = -(a1 * s1 + a2 * s2)
        return 10.0 * log10((nr * nr + ni * ni) / (dr * dr + di * di))
    }
}

/** A filter's wanted settings, as the engine and the curve on screen both read them. */
internal class FilterSpec {
    var hz = 1000.0
    var q = 1.0
    var db = 0.0
}

/**
 * The 16 gain filters of the Jhankar engine: the ten equalizer bands, the sub-bass shelf, the vocal-clarity
 * pair (mud cut and presence), the detail shelf and the two loudness-contour shelves. One table is the single
 * source for the audio thread, the headroom calculation and the response curve on screen.
 */
internal object JhankarFilters {
    const val COUNT = 16
    const val SUB = 10
    const val MUD = 11
    const val VOCAL = 12
    const val DETAIL = 13
    const val LOUD_LOW = 14
    const val LOUD_HIGH = 15

    const val LOW_SHELF = 0
    const val HIGH_SHELF = 1
    const val BELL = 2

    const val SUB_HZ = 45.0
    const val MUD_HZ = 320.0
    const val VOCAL_HZ = 3000.0
    const val DETAIL_HZ = 9000.0
    const val LOUD_LOW_HZ = 110.0
    const val LOUD_HIGH_HZ = 6500.0
    const val BUTTERWORTH_Q = 0.7071067811865476

    /** dB at 100 % amount and 100 % intensity. */
    const val MUD_CUT_DB = 3.0
    const val VOCAL_DB = 4.5
    const val DETAIL_DB = 4.0
    const val LOUD_LOW_DB = 6.0
    const val LOUD_HIGH_DB = 3.0

    const val MAX_CENTRE_FRACTION = 0.45

    fun kind(i: Int): Int = when (i) {
        0, SUB, LOUD_LOW -> LOW_SHELF
        9, DETAIL, LOUD_HIGH -> HIGH_SHELF
        else -> BELL
    }

    fun spec(p: JhankarParameters, i: Int, out: FilterSpec) {
        val k = p.intensity / 100.0
        out.q = BUTTERWORTH_Q
        when {
            i < JhankarTone.BANDS -> {
                val b = p.bands[i]
                out.hz = b.freqHz.toDouble()
                out.q = b.q.toDouble()
                out.db = if (p.eqOn) b.gainDb.toDouble() else 0.0
            }
            i == SUB -> { out.hz = SUB_HZ; out.db = if (p.bassOn) p.subBassDb.toDouble() else 0.0 }
            i == MUD -> { out.hz = MUD_HZ; out.q = 1.0; out.db = -MUD_CUT_DB * k * p.vocalClarity / 100.0 }
            i == VOCAL -> { out.hz = VOCAL_HZ; out.q = 0.9; out.db = VOCAL_DB * k * p.vocalClarity / 100.0 }
            i == DETAIL -> { out.hz = DETAIL_HZ; out.db = DETAIL_DB * k * p.hfDetail / 100.0 }
            i == LOUD_LOW -> { out.hz = LOUD_LOW_HZ; out.db = LOUD_LOW_DB * k * p.loudness / 100.0 }
            else -> { out.hz = LOUD_HIGH_HZ; out.db = LOUD_HIGH_DB * k * p.loudness / 100.0 }
        }
        if (out.db == 0.0) out.db = 0.0 // -0.0 is a flat filter too
    }

    fun usable(hz: Double, rate: Double): Boolean = hz < MAX_CENTRE_FRACTION * rate

    fun design(co: JhBiquad, rate: Double, i: Int, hz: Double, q: Double, db: Double) {
        if (db == 0.0 || !usable(hz, rate)) return co.identity()
        when (kind(i)) {
            LOW_SHELF -> co.lowShelf(rate, hz, q, db)
            HIGH_SHELF -> co.highShelf(rate, hz, q, db)
            else -> co.peaking(rate, hz, q, db)
        }
    }
}

/**
 * The combined response of the 16 filters for one setting. Holds its working memory, so [load] and [db] do not
 * allocate (the audio thread uses it for the headroom calculation); [JhankarCurve] wraps it for the screen.
 */
internal class JhankarResponse {
    private val spec = FilterSpec()
    private val filters = Array(JhankarFilters.COUNT) { JhBiquad() }
    private var rate = 48_000.0

    fun load(p: JhankarParameters, sampleRate: Int) {
        rate = sampleRate.toDouble()
        for (i in 0 until JhankarFilters.COUNT) {
            JhankarFilters.spec(p, i, spec)
            JhankarFilters.design(filters[i], rate, i, spec.hz, spec.q, spec.db)
        }
    }

    fun db(hz: Double): Double {
        var sum = 0.0
        for (i in 0 until JhankarFilters.COUNT) {
            val f = filters[i]
            if (!f.isIdentity) sum += f.magnitudeDb(hz, rate)
        }
        return sum
    }

    /** The highest point of the response over 20 Hz … 0.45 × sample rate (a log grid plus each band's centre). */
    fun peakDb(p: JhankarParameters): Double {
        val top = JhankarFilters.MAX_CENTRE_FRACTION * rate
        var best = -1000.0
        val points = GRID_POINTS
        val ratio = top / GRID_LOW_HZ
        for (n in 0 until points) {
            val hz = GRID_LOW_HZ * ratio.pow(n / (points - 1.0))
            val v = db(hz)
            if (v > best) best = v
        }
        for (b in 0 until JhankarTone.BANDS) {
            val hz = p.bands[b].freqHz.toDouble()
            if (hz < top) {
                val v = db(hz)
                if (v > best) best = v
            }
        }
        return best
    }

    /**
     * The average boost of the response for music: power-averaged over 30 Hz … 12 kHz with equal weight per octave
     * up to 4 kHz and falling off above it (the way a music spectrum falls). This is what the auto headroom takes
     * off the preamp, so a curve that lifts the bass and the top does not make the whole track louder than the
     * unprocessed one; the limiter holds the rare peaks that land on the loudest part of the curve.
     */
    fun musicAverageDb(): Double {
        val top = min(MUSIC_HIGH_HZ, JhankarFilters.MAX_CENTRE_FRACTION * rate)
        val ratio = top / MUSIC_LOW_HZ
        var weighted = 0.0
        var weights = 0.0
        for (n in 0 until MUSIC_POINTS) {
            val hz = MUSIC_LOW_HZ * ratio.pow(n / (MUSIC_POINTS - 1.0))
            val w = if (hz <= MUSIC_KNEE_HZ) 1.0 else (MUSIC_KNEE_HZ / hz).pow(2.0)
            weighted += w * 10.0.pow(db(hz) / 10.0)
            weights += w
        }
        return 10.0 * log10(weighted / weights)
    }

    private companion object {
        const val GRID_POINTS = 160
        const val GRID_LOW_HZ = 20.0
        const val MUSIC_POINTS = 96
        const val MUSIC_LOW_HZ = 30.0
        const val MUSIC_HIGH_HZ = 12_000.0
        const val MUSIC_KNEE_HZ = 4_000.0
    }
}

/** The response of the Jhankar filters as drawn in the equalizer, and the headroom that keeps them below full scale. */
object JhankarCurve {
    /** Response in dB of all Jhankar filters at [hz] for [sampleRate] (the EQ with the vocal, detail and loudness shaping). */
    fun responseDb(p: JhankarParameters, hz: Double, sampleRate: Int = 48_000): Double {
        val r = JhankarResponse()
        r.load(p, sampleRate)
        return r.db(hz)
    }

    /** The response at many frequencies at once (one pass, for drawing). */
    fun responseDb(p: JhankarParameters, hz: DoubleArray, sampleRate: Int = 48_000): DoubleArray {
        val r = JhankarResponse()
        r.load(p, sampleRate)
        return DoubleArray(hz.size) { r.db(hz[it]) }
    }

    /** The highest point of the response (0 or more is a boost somewhere). */
    fun peakDb(p: JhankarParameters, sampleRate: Int = 48_000): Double {
        val r = JhankarResponse()
        r.load(p, sampleRate)
        return r.peakDb(p)
    }

    /** What the auto headroom takes off the preamp: the music-weighted average boost of the response, never less than 0. */
    fun headroomDb(p: JhankarParameters, sampleRate: Int = 48_000): Double {
        if (!p.autoHeadroom) return 0.0
        val r = JhankarResponse()
        r.load(p, sampleRate)
        return max(0.0, r.musicAverageDb())
    }
}

/** What the Jhankar engine is doing right now: the loudest value of each over a buffer. */
class JhankarMeters {
    private val bassBoost = AtomicInteger(0)
    private val transient = AtomicInteger(0)
    private val reduction = AtomicInteger(0)

    fun publish(bassBoostDb: Float, transientDb: Float, compressionDb: Float) {
        raise(bassBoost, bassBoostDb)
        raise(transient, transientDb)
        raise(reduction, compressionDb)
    }

    fun drain(out: JhankarReading) {
        out.bassBoostDb = java.lang.Float.intBitsToFloat(bassBoost.getAndSet(0))
        out.transientDb = java.lang.Float.intBitsToFloat(transient.getAndSet(0))
        out.compressionDb = java.lang.Float.intBitsToFloat(reduction.getAndSet(0))
    }

    private fun raise(target: AtomicInteger, value: Float) {
        while (true) {
            val current = target.get()
            if (value <= java.lang.Float.intBitsToFloat(current)) return
            if (target.compareAndSet(current, java.lang.Float.floatToRawIntBits(value))) return
        }
    }
}

class JhankarReading {
    var bassBoostDb = 0f
    var transientDb = 0f
    var compressionDb = 0f
}

/**
 * The Jhankar engine's signal path, on interleaved frames in the one [DspChain]:
 *
 * preamp (with auto headroom) → 10-band parametric EQ → sub-bass shelf → vocal clarity (mud cut + presence) →
 * detail shelf → loudness contour → dynamic bass + bass harmonics → air harmonics → transient punch →
 * compressor with make-up → stereo width with mono bass → output gain → fade (engine on/off).
 *
 * Real-time rules: everything is allocated in [configure]; [beginBlock] and [process] do not allocate and
 * call nothing in the platform. Controls move at most a small step per 32-frame block, so no change is a step in
 * the sound. A stage that is at its neutral setting leaves the samples untouched; with the engine on and every
 * control neutral the output is bit-identical to the input.
 */
class JhankarStage(private val meters: JhankarMeters? = null) {

    var sampleRate = 0
        private set
    var channelCount = 0
        private set

    private class Filter {
        val co = JhBiquad()
        var curHz = 1000.0
        var curQ = 1.0
        var curDb = 0.0
        var tgtHz = 1000.0
        var tgtQ = 1.0
        var tgtDb = 0.0
        var running = false
        var z1 = DoubleArray(0)
        var z2 = DoubleArray(0)
    }

    /** A filter with its own memory, used for crossovers and detectors. */
    private class Section {
        val co = JhBiquad()
        var z1 = DoubleArray(0)
        var z2 = DoubleArray(0)

        fun size(channels: Int) {
            z1 = DoubleArray(channels)
            z2 = DoubleArray(channels)
        }

        fun clear() {
            z1.fill(0.0)
            z2.fill(0.0)
        }

        fun tick(c: Int, x: Double): Double {
            val y = co.b0 * x + z1[c]
            var s1 = co.b1 * x - co.a1 * y + z2[c]
            var s2 = co.b2 * x - co.a2 * y
            if (abs(s1) < DENORMAL_FLOOR) s1 = 0.0
            if (abs(s2) < DENORMAL_FLOOR) s2 = 0.0
            z1[c] = s1
            z2[c] = s2
            return y
        }
    }

    private val filters = Array(JhankarFilters.COUNT) { Filter() }
    private val spec = FilterSpec()
    private val response = JhankarResponse()

    private val bassLow = Section()
    private val harmHighA = Section()
    private val harmHighB = Section()
    private val airHigh = Section()
    private val airLow = Section()
    private val airPost = Section()
    private val detectHigh = Section()
    private val sideHigh = Section()

    private val sections = arrayOf(bassLow, harmHighA, harmHighB, airHigh, airLow, airPost, detectHigh, sideHigh)

    private val compressor = StudioCompressor()

    private var params: JhankarParameters? = null

    // Wanted values (from the parameters) and current values (moving toward them).
    private var mixTarget = 0.0
    private var mix = 0.0
    private var mixStep = 0.0

    private var preTargetDb = 0.0
    private var preDb = 0.0
    private var preLinear = 1.0
    private var preStart = 1.0
    private var preDelta = 0.0
    private var outTargetDb = 0.0
    private var outDb = 0.0
    private var outLinear = 1.0
    private var outStart = 1.0
    private var outDelta = 0.0

    private var dynTarget = 0.0
    private var dyn = 0.0
    private var harmTarget = 0.0
    private var harm = 0.0
    private var airTarget = 0.0
    private var air = 0.0
    private var transTarget = 0.0
    private var trans = 0.0
    private var compTarget = 0.0
    private var comp = 0.0
    private var widthTarget = 1.0
    private var width = 1.0
    private var monoHz = 0
    private var monoTarget = 0.0
    private var monoMix = 0.0
    private var appliedMonoHz = 0

    private var compMix = 0.0
    private var makeupLinear = 1.0

    // Detectors and dynamic state.
    private var lowPower = 0.0
    private var fullPower = 0.0
    private var lowEnvelope = 0.0
    private var bassGain = 1.0
    private var bassTarget = 1.0
    private var airEnvelope = 0.0
    private var fastEnvelope = 0.0
    private var slowEnvelope = 0.0
    private var transientGain = 1.0

    private var powerCoefficient = 0.0
    private var lowDecay = 0.0
    private var airDecay = 0.0
    private var bassGainCoefficient = 0.0
    private var fastSmoothing = 0.0
    private var slowSmoothing = 0.0
    private var transientAttack = 0.0
    private var transientRelease = 0.0

    private val lowBand = DoubleArray(MAX_CHANNELS)
    private val dryCopy = DoubleArray(MAX_CHANNELS)

    private var blockPosition = 0

    // Meter accumulators for the current buffer.
    private var meterBass = 1.0
    private var meterTransient = 1.0
    private var meterCompression = 1.0

    /** True until the first setting has been taken over at its target (no glide from a default at stream start). */
    private var snapNext = true

    /** True while the stage has to run: switched on, or still fading out. */
    val isActive: Boolean
        get() = mix > 0.0 || mixTarget > 0.0

    /** Prepares for a stream format. Allocates; never call from the audio callback. */
    fun configure(sampleRate: Int, channelCount: Int) {
        require(sampleRate > 0) { "sampleRate must be positive" }
        require(channelCount in 1..MAX_CHANNELS) { "channelCount must be 1..$MAX_CHANNELS" }
        this.sampleRate = sampleRate
        this.channelCount = channelCount
        val rate = sampleRate.toDouble()
        for (f in filters) {
            f.z1 = DoubleArray(channelCount)
            f.z2 = DoubleArray(channelCount)
        }
        for (s in sections) s.size(channelCount)
        bassLow.co.lowPass(rate, BASS_BAND_HZ, JhankarFilters.BUTTERWORTH_Q)
        harmHighA.co.highPass(rate, HARM_HIGH_HZ, JhankarFilters.BUTTERWORTH_Q)
        harmHighB.co.highPass(rate, HARM_HIGH_HZ, JhankarFilters.BUTTERWORTH_Q)
        airHigh.co.highPass(rate, AIR_LOW_HZ, JhankarFilters.BUTTERWORTH_Q)
        airLow.co.lowPass(rate, AIR_HIGH_HZ, JhankarFilters.BUTTERWORTH_Q)
        airPost.co.highPass(rate, AIR_POST_HZ, JhankarFilters.BUTTERWORTH_Q)
        detectHigh.co.highPass(rate, DETECT_HZ, JhankarFilters.BUTTERWORTH_Q)
        appliedMonoHz = 0

        mixStep = 1.0 / (RAMP_SECONDS * rate)
        powerCoefficient = coefficient(POWER_MS, rate)
        lowDecay = decay(LOW_RELEASE_MS, rate)
        airDecay = decay(AIR_RELEASE_MS, rate)
        bassGainCoefficient = coefficient(BASS_GAIN_MS, rate)
        fastSmoothing = coefficient(FAST_MS, rate)
        slowSmoothing = coefficient(SLOW_MS, rate)
        transientAttack = coefficient(TRANSIENT_ATTACK_MS, rate)
        transientRelease = coefficient(TRANSIENT_RELEASE_MS, rate)
        compressor.configure(sampleRate)
        clearState()
        if (params != null) retarget(snap = false)
        for (f in filters) retune(f)
    }

    /** Clears the memory of every filter and detector (after a seek or a format change). */
    fun resetState() = clearState()

    private fun clearState() {
        for (f in filters) {
            f.z1.fill(0.0)
            f.z2.fill(0.0)
        }
        for (s in sections) s.clear()
        compressor.reset()
        lowPower = 0.0
        fullPower = 0.0
        lowEnvelope = 0.0
        bassGain = 1.0
        bassTarget = 1.0
        airEnvelope = 0.0
        fastEnvelope = 0.0
        slowEnvelope = 0.0
        transientGain = 1.0
    }

    /** Takes a new setting. Cheap when [p] is the one already applied (reference check). */
    fun set(p: JhankarParameters) {
        if (p === params) return
        val first = params == null
        val wasOff = mix == 0.0 && mixTarget == 0.0
        params = p
        retarget(snap = first)
        if (wasOff && mixTarget > 0.0) clearState()
    }

    private fun retarget(snap: Boolean) {
        val p = params ?: return
        val rate = sampleRate
        if (rate == 0) return
        val snapNow = snap || snapNext
        snapNext = false
        for (i in 0 until JhankarFilters.COUNT) {
            JhankarFilters.spec(p, i, spec)
            val f = filters[i]
            f.tgtHz = spec.hz
            f.tgtQ = spec.q
            f.tgtDb = spec.db
        }
        val k = p.intensity / 100.0
        dynTarget = if (p.bassOn) k * p.dynamicBass / 100.0 else 0.0
        harmTarget = if (p.bassOn) k * p.bassHarmonics / 100.0 else 0.0
        airTarget = k * p.airHarmonics / 100.0
        transTarget = k * p.transient / 100.0
        compTarget = k * p.compression / 100.0
        widthTarget = if (p.stereoOn) 1.0 + (p.widthPercent - 100) / 100.0 * k else 1.0
        monoHz = if (p.stereoOn) p.monoBelowHz else 0
        monoTarget = if (monoHz > 0) 1.0 else 0.0
        outTargetDb = p.outputGainDb.toDouble()
        response.load(p, rate)
        val boost = if (p.autoHeadroom) max(0.0, response.musicAverageDb()) else 0.0
        preTargetDb = (if (p.eqOn) p.preampDb.toDouble() else 0.0) - boost
        mixTarget = if (p.engine) 1.0 else 0.0
        if (snapNow) {
            for (f in filters) {
                f.curHz = f.tgtHz; f.curQ = f.tgtQ; f.curDb = f.tgtDb
                retune(f)
            }
            preDb = preTargetDb
            preLinear = JhankarMath.linear(preDb)
            outDb = outTargetDb
            outLinear = JhankarMath.linear(outDb)
            dyn = dynTarget; harm = harmTarget; air = airTarget; trans = transTarget
            comp = compTarget; width = widthTarget
            mix = mixTarget
            monoMix = monoTarget
            if (monoHz > 0) setSideCrossover()
            updateCompressor()
            compMix = if (comp > 0.0) 1.0 else 0.0
        }
    }

    private fun retune(f: Filter) {
        val index = filterIndex(f)
        val rate = sampleRate.toDouble()
        JhankarFilters.design(f.co, rate, index, f.curHz, f.curQ, f.curDb)
        if (!f.co.isIdentity) f.running = true
    }

    private fun filterIndex(f: Filter): Int {
        for (i in 0 until JhankarFilters.COUNT) if (filters[i] === f) return i
        return 0
    }

    /**
     * Called at the start of every block of up to [SUB_BLOCK] frames: moves every control one small step toward
     * its target, retunes what moved, and takes the bass-balance reading for this block.
     */
    fun beginBlock(frames: Int) {
        val rate = sampleRate.toDouble()
        for (i in 0 until JhankarFilters.COUNT) {
            val f = filters[i]
            if (f.curDb == f.tgtDb && f.curHz == f.tgtHz && f.curQ == f.tgtQ) continue
            if (f.curDb == 0.0) {
                // Nothing is heard from a flat filter: it can take its new place at once.
                f.curHz = f.tgtHz
                f.curQ = f.tgtQ
            } else {
                f.curHz = glide(f.curHz, f.tgtHz)
                f.curQ = slewAbs(f.curQ, f.tgtQ, Q_STEP)
            }
            f.curDb = slewAbs(f.curDb, f.tgtDb, GAIN_STEP_DB)
            JhankarFilters.design(f.co, rate, i, f.curHz, f.curQ, f.curDb)
            if (!f.co.isIdentity) f.running = true
        }
        dyn = slewAbs(dyn, dynTarget, AMOUNT_STEP)
        harm = slewAbs(harm, harmTarget, AMOUNT_STEP)
        air = slewAbs(air, airTarget, AMOUNT_STEP)
        trans = slewAbs(trans, transTarget, AMOUNT_STEP)
        width = slewAbs(width, widthTarget, WIDTH_STEP)
        if (comp != compTarget) {
            if (comp == 0.0) compressor.reset()
            comp = slewAbs(comp, compTarget, AMOUNT_STEP)
            updateCompressor()
        }
        if (monoHz != appliedMonoHz && monoHz > 0) setSideCrossover()
        preStart = preLinear
        if (preDb != preTargetDb) {
            preDb = slewAbs(preDb, preTargetDb, GAIN_STEP_DB)
            preLinear = JhankarMath.linear(preDb)
        }
        preDelta = (preLinear - preStart) / frames
        outStart = outLinear
        if (outDb != outTargetDb) {
            outDb = slewAbs(outDb, outTargetDb, GAIN_STEP_DB)
            outLinear = JhankarMath.linear(outDb)
        }
        outDelta = (outLinear - outStart) / frames
        updateBassTarget()
        blockPosition = 0
        settleIdleFilters()
    }

    private fun setSideCrossover() {
        sideHigh.co.highPass(sampleRate.toDouble(), monoHz.toDouble(), JhankarFilters.BUTTERWORTH_Q)
        appliedMonoHz = monoHz
    }

    private fun updateCompressor() {
        val a = comp
        val threshold = COMP_THRESHOLD_TOP_DB - COMP_THRESHOLD_SPAN_DB * a
        val ratio = 1.0 + COMP_RATIO_FIRST + COMP_RATIO_SPAN * a
        compressor.set(threshold, ratio, COMP_ATTACK_MS, COMP_RELEASE_MS)
        val reduction = COMP_REFERENCE_DB - StudioCurve.outputDb(COMP_REFERENCE_DB, threshold, ratio)
        makeupLinear = JhankarMath.linear(reduction * COMP_MAKEUP_SHARE)
    }

    /**
     * The bass balance: how far the 0…110 Hz band lies below the whole signal, against the level a strong
     * Hindi mix has. Only a shortfall is made up, never more than [MAX_BASS_BOOST_DB], and never so far that
     * the bass band itself passes [BASS_PEAK_LIMIT_DB]. Level does not matter, only balance, so a quiet track and
     * a loud one with the same balance get the same boost.
     */
    private fun updateBassTarget() {
        if (dyn <= 0.0 || fullPower < SILENCE_POWER) {
            bassTarget = 1.0
            return
        }
        val relativeDb = 10.0 * log10(max(lowPower, 1e-20) / fullPower)
        var wanted = (BASS_TARGET_DB - relativeDb).coerceIn(0.0, MAX_BASS_BOOST_DB) * dyn
        val peakDb = 20.0 * log10(lowEnvelope + 1e-12)
        wanted = min(wanted, max(0.0, BASS_PEAK_LIMIT_DB - peakDb))
        bassTarget = JhankarMath.linear(wanted)
    }

    /** Processes one frame of [channels] samples in place. Call [beginBlock] first for every block. */
    fun process(frame: DoubleArray, channels: Int) {
        if (mix == 0.0 && mixTarget == 0.0) return
        if (mix != mixTarget) mix = if (mix < mixTarget) min(mixTarget, mix + mixStep) else max(mixTarget, mix - mixStep)
        for (c in 0 until channels) dryCopy[c] = frame[c]

        blockPosition++
        val pre = preStart + preDelta * blockPosition
        val output = outStart + outDelta * blockPosition

        // Preamp, the 16 gain filters.
        for (c in 0 until channels) {
            var x = frame[c] * pre
            for (b in 0 until JhankarFilters.COUNT) {
                val f = filters[b]
                if (!f.running) continue
                val co = f.co
                val y = co.b0 * x + f.z1[c]
                var s1 = co.b1 * x - co.a1 * y + f.z2[c]
                var s2 = co.b2 * x - co.a2 * y
                if (abs(s1) < DENORMAL_FLOOR) s1 = 0.0
                if (abs(s2) < DENORMAL_FLOOR) s2 = 0.0
                f.z1[c] = s1
                f.z2[c] = s2
                x = y
            }
            frame[c] = x
        }

        bassStage(frame, channels)
        if (air > 0.0) airStage(frame, channels)
        transientStage(frame, channels)
        if (comp > 0.0 || compMix > 0.0) compressorStage(frame, channels)
        if (channels == 2) stereoStage(frame)
        if (output != 1.0) for (c in 0 until channels) frame[c] *= output

        if (mix < 1.0) for (c in 0 until channels) frame[c] = dryCopy[c] + (frame[c] - dryCopy[c]) * mix
    }

    private fun bassStage(frame: DoubleArray, channels: Int) {
        var lowSquares = 0.0
        var fullSquares = 0.0
        var lowPeak = 0.0
        for (c in 0 until channels) {
            val x = frame[c]
            val lo = bassLow.tick(c, x)
            lowBand[c] = lo
            lowSquares += lo * lo
            fullSquares += x * x
            val a = abs(lo)
            if (a > lowPeak) lowPeak = a
        }
        lowPower += (lowSquares / channels - lowPower) * powerCoefficient
        fullPower += (fullSquares / channels - fullPower) * powerCoefficient
        lowEnvelope = if (lowPeak > lowEnvelope) lowPeak else lowEnvelope * lowDecay
        if (bassGain != bassTarget) {
            bassGain += (bassTarget - bassGain) * bassGainCoefficient
            if (abs(bassGain - bassTarget) < 1e-9) bassGain = bassTarget
        }
        if (bassGain > meterBass) meterBass = bassGain
        if (bassGain == 1.0 && harm == 0.0) return
        val extra = bassGain - 1.0
        val envelope = max(lowEnvelope, 1e-6)
        for (c in 0 until channels) {
            var y = frame[c] + extra * lowBand[c]
            if (harm > 0.0) {
                // The harmonics of the bass: the band is shaped at its own level (so the amount does not depend
                // on loudness), the fundamental is filtered away and only what a small speaker can play stays.
                val normalised = lowBand[c] / envelope
                val shaped = (tanh(HARM_DRIVE * (normalised + HARM_BIAS)) - HARM_OFFSET) * envelope
                y += harm * HARM_GAIN * harmHighB.tick(c, harmHighA.tick(c, shaped))
            }
            frame[c] = y
        }
    }

    private fun airStage(frame: DoubleArray, channels: Int) {
        var peak = 0.0
        for (c in 0 until channels) {
            val band = airLow.tick(c, airHigh.tick(c, frame[c]))
            lowBand[c] = band
            val a = abs(band)
            if (a > peak) peak = a
        }
        airEnvelope = if (peak > airEnvelope) peak else airEnvelope * airDecay
        val envelope = max(airEnvelope, 1e-6)
        for (c in 0 until channels) {
            val shaped = tanh(AIR_DRIVE * lowBand[c] / envelope) * envelope
            frame[c] += air * AIR_GAIN * airPost.tick(c, shaped)
        }
    }

    private fun transientStage(frame: DoubleArray, channels: Int) {
        var mono = 0.0
        for (c in 0 until channels) mono += frame[c]
        val level = abs(detectHigh.tick(0, mono / channels))
        fastEnvelope += (level - fastEnvelope) * fastSmoothing
        slowEnvelope += (level - slowEnvelope) * slowSmoothing
        var wanted = 1.0
        if (trans > 0.0 && slowEnvelope > TRANSIENT_FLOOR) {
            val over = ((fastEnvelope / slowEnvelope - TRANSIENT_DEAD_ZONE) / TRANSIENT_SPAN).coerceIn(0.0, 1.0)
            wanted = 1.0 + trans * over * (TRANSIENT_MAX_LINEAR - 1.0)
        }
        transientGain += (wanted - transientGain) * if (wanted > transientGain) transientAttack else transientRelease
        if (abs(transientGain - 1.0) < 1e-9) transientGain = 1.0
        if (transientGain > meterTransient) meterTransient = transientGain
        if (transientGain != 1.0) for (c in 0 until channels) frame[c] *= transientGain
    }

    private fun compressorStage(frame: DoubleArray, channels: Int) {
        val target = if (comp > 0.0) 1.0 else 0.0
        if (compMix != target) compMix = if (compMix < target) min(target, compMix + mixStep) else max(target, compMix - mixStep)
        compressor.process(frame, channels, compMix)
        val g = compressor.gain
        if (g < meterCompression) meterCompression = g
        val makeup = 1.0 + (makeupLinear - 1.0) * compMix
        if (makeup != 1.0) for (c in 0 until channels) frame[c] *= makeup
    }

    private fun stereoStage(frame: DoubleArray) {
        if (monoMix != monoTarget) monoMix = if (monoMix < monoTarget) min(monoTarget, monoMix + mixStep) else max(monoTarget, monoMix - mixStep)
        if (width == 1.0 && monoMix == 0.0) return
        val mid = (frame[0] + frame[1]) * 0.5
        var side = (frame[0] - frame[1]) * 0.5
        if (monoMix > 0.0) {
            val kept = sideHigh.tick(0, side)
            side += (kept - side) * monoMix
        }
        side *= width
        frame[0] = mid + side
        frame[1] = mid - side
    }

    /** Hands the loudest value of each meter over this buffer to the meters, and starts the next buffer. */
    fun endBuffer() {
        meters?.publish(
            (20.0 * log10(meterBass)).toFloat(),
            (20.0 * log10(meterTransient.coerceAtLeast(1.0))).toFloat(),
            (-20.0 * log10(meterCompression)).toFloat(),
        )
        meterBass = 1.0
        meterTransient = 1.0
        meterCompression = 1.0
    }

    /** A filter that has returned to exactly 0 dB has drained after one more block and can stop running. */
    private fun settleIdleFilters() {
        val channels = channelCount
        for (f in filters) {
            if (!f.running) continue
            if (f.curDb != 0.0 || f.tgtDb != 0.0 || !f.co.isIdentity) continue
            var drained = true
            for (c in 0 until channels) {
                if (f.z1[c] != 0.0 || f.z2[c] != 0.0) {
                    drained = false
                    break
                }
            }
            if (drained) f.running = false
        }
    }

    /** Current (moving) gain in dB of filter [index] (0…9 the bands, then sub, mud, vocal, detail, loudness low and high). */
    fun currentGainDb(index: Int): Double = filters[index].curDb

    /** The bass gain the dynamic bass is applying right now, in dB. */
    val bassBoostDb: Double
        get() = 20.0 * log10(bassGain)

    private fun glide(current: Double, target: Double): Double {
        if (current == target) return current
        val next = if (current < target) current * FREQ_STEP else current / FREQ_STEP
        return if ((current < target) == (next >= target)) target else next
    }

    private fun slewAbs(current: Double, target: Double, step: Double): Double {
        val delta = target - current
        return if (abs(delta) <= step) target else current + if (delta > 0) step else -step
    }

    private fun coefficient(ms: Double, rate: Double): Double = 1.0 - kotlin.math.exp(-1.0 / (ms * 0.001 * rate))

    private fun decay(ms: Double, rate: Double): Double = kotlin.math.exp(-1.0 / (ms * 0.001 * rate))

    private object JhankarMath {
        fun linear(db: Double): Double = if (db == 0.0) 1.0 else 10.0.pow(db / 20.0)
    }

    companion object {
        const val MAX_CHANNELS = DspChain.MAX_CHANNELS
        const val SUB_BLOCK = DspChain.SUB_BLOCK

        private const val RAMP_SECONDS = 0.02
        private const val GAIN_STEP_DB = 0.25
        private const val Q_STEP = 0.1
        private const val FREQ_STEP = 1.03
        private const val AMOUNT_STEP = 0.02
        private const val WIDTH_STEP = 0.02
        private const val DENORMAL_FLOOR = 1e-30
        private const val SILENCE_POWER = 1e-9

        private const val BASS_BAND_HZ = 110.0
        private const val POWER_MS = 300.0
        private const val LOW_RELEASE_MS = 120.0
        private const val BASS_GAIN_MS = 40.0
        private const val BASS_TARGET_DB = -6.0
        private const val MAX_BASS_BOOST_DB = 8.0
        private const val BASS_PEAK_LIMIT_DB = -3.0

        private const val HARM_HIGH_HZ = 110.0
        private const val HARM_DRIVE = 3.0
        private const val HARM_BIAS = 0.12
        private val HARM_OFFSET = tanh(HARM_DRIVE * HARM_BIAS)
        private const val HARM_GAIN = 1.0

        private const val AIR_LOW_HZ = 4500.0
        private const val AIR_HIGH_HZ = 8000.0
        private const val AIR_POST_HZ = 9500.0
        private const val AIR_DRIVE = 2.5
        private const val AIR_GAIN = 0.3
        private const val AIR_RELEASE_MS = 60.0

        private const val DETECT_HZ = 250.0
        private const val FAST_MS = 4.0
        private const val SLOW_MS = 50.0
        private const val TRANSIENT_DEAD_ZONE = 1.25
        private const val TRANSIENT_SPAN = 0.75
        private const val TRANSIENT_ATTACK_MS = 1.0
        private const val TRANSIENT_RELEASE_MS = 35.0
        private const val TRANSIENT_FLOOR = 1e-4
        private const val TRANSIENT_MAX_LINEAR = 2.0

        private const val COMP_THRESHOLD_TOP_DB = -6.0
        private const val COMP_THRESHOLD_SPAN_DB = 10.0
        private const val COMP_RATIO_FIRST = 0.3
        private const val COMP_RATIO_SPAN = 1.7
        private const val COMP_ATTACK_MS = 12
        private const val COMP_RELEASE_MS = 180
        // Make-up is a share of what the rule takes off a full-scale peak, so the part of the music below the
        // threshold comes out louder and only the peaks are held; the limiter at the end of the chain keeps the ceiling.
        private const val COMP_REFERENCE_DB = 0.0
        private const val COMP_MAKEUP_SHARE = 0.5
    }
}
