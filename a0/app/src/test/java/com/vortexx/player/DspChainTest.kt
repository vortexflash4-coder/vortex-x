package com.vortexx.player

import com.vortexx.player.audio.dsp.DspChain
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.GraphicEqBands
import com.vortexx.player.audio.dsp.LoadMeter
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.dsp.MeterReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Stage A0 DSP gate: measured behaviour of the preamp + 10-band EQ chain.
 * Every test feeds real sample buffers through DspChain.process.
 */
class DspChainTest {

    private fun sine(freq: Double, sampleRate: Int, frames: Int, amplitude: Double, channels: Int = 1, phaseFrames: Int = 0): FloatArray {
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * freq * (i + phaseFrames) / sampleRate)).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    private fun rms(buffer: FloatArray, from: Int, to: Int, channels: Int = 1, channel: Int = 0): Double {
        var sum = 0.0
        var n = 0
        var i = from
        while (i < to) {
            val v = buffer[i * channels + channel].toDouble()
            sum += v * v
            n++
            i++
        }
        return sqrt(sum / n)
    }

    private fun params(band: Int = -1, gain: Float = 0f, preamp: Float = 0f, enabled: Boolean = true): DspParameters {
        var p = DspParameters(enabled = enabled, preampDb = preamp)
        if (band >= 0) p = p.withBand(band, gain)
        return p
    }

    /** Steady-state gain in dB at [freq] for a chain set to [p]. */
    private fun measuredGainDb(sampleRate: Int, freq: Double, p: DspParameters): Double {
        val chain = DspChain()
        chain.configure(sampleRate, 1)
        chain.apply(p)
        val frames = sampleRate // 1 s: the first half settles, the second half is measured
        val input = sine(freq, sampleRate, frames, 0.1)
        val output = input.copyOf()
        var offset = 0
        val block = 512
        val chunk = FloatArray(block)
        while (offset < frames) {
            val n = minOf(block, frames - offset)
            System.arraycopy(output, offset, chunk, 0, n)
            chain.process(chunk, n)
            System.arraycopy(chunk, 0, output, offset, n)
            offset += n
        }
        val cycles = (0.5 * freq).toInt().coerceAtLeast(1)
        val window = (cycles * sampleRate / freq).toInt()
        val from = frames - window
        return 20 * log10(rms(output, from, frames) / rms(input, from, frames))
    }

    @Test
    fun everyBandHitsItsGainAtCentre_44_1_48_96kHz() {
        val failures = mutableListOf<String>()
        for (rate in intArrayOf(44_100, 48_000, 96_000)) {
            for (band in 0 until GraphicEqBands.COUNT) {
                for (gain in floatArrayOf(6f, -6f, 12f)) {
                    val measured = measuredGainDb(rate, GraphicEqBands.CENTRES_HZ[band], params(band, gain))
                    if (abs(measured - gain) > 0.5) failures += "$rate Hz band ${GraphicEqBands.LABELS[band]}: set $gain dB, measured ${"%.2f".format(measured)} dB"
                }
            }
        }
        assertTrue("Band gain errors > 0.5 dB:\n" + failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun flatChainIsBitTransparent() {
        val chain = DspChain()
        chain.configure(48_000, 2)
        chain.apply(DspParameters())
        var seed = 12345L
        val input = FloatArray(4096) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            ((seed ushr 33).toInt() / Int.MAX_VALUE.toFloat()) * 0.9f
        }
        val buffer = input.copyOf()
        chain.process(buffer, buffer.size / 2)
        assertTrue("flat EQ changed samples", input.contentEquals(buffer))
    }

    @Test
    fun bypassIsBitTransparentEvenWithEqSet() {
        val chain = DspChain()
        chain.configure(44_100, 2)
        chain.apply(params(5, 12f, preamp = 6f, enabled = false))
        val input = sine(1000.0, 44_100, 2048, 0.5, channels = 2)
        val buffer = input.copyOf()
        chain.process(buffer, 2048)
        assertTrue("bypass changed samples", input.contentEquals(buffer))
    }

    @Test
    fun stereoChannelsStayIndependentAndMonoWorks() {
        val chain = DspChain()
        chain.configure(48_000, 2)
        chain.apply(params(5, 12f))
        val frames = 4800
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) buffer[i * 2] = (0.1 * sin(2 * PI * 1000.0 * i / 48_000)).toFloat()
        chain.process(buffer, frames)
        for (i in 0 until frames) assertEquals("right channel must stay silent", 0f, buffer[i * 2 + 1], 0f)
        assertTrue("left channel boosted", rms(buffer, frames / 2, frames, 2, 0) > 0.1 * 0.707 * 3)

        val mono = DspChain()
        mono.configure(48_000, 1)
        mono.apply(params(5, 6f))
        val monoBuffer = sine(1000.0, 48_000, frames, 0.1)
        mono.process(monoBuffer, frames)
        val gain = 20 * log10(rms(monoBuffer, frames / 2, frames) / (0.1 / sqrt(2.0)))
        assertEquals(6.0, gain, 0.5)
    }

    @Test
    fun formatChangeRetunesFilters() {
        val chain = DspChain()
        chain.configure(44_100, 1)
        chain.apply(params(5, 6f))
        val first = sine(1000.0, 44_100, 44_100, 0.1)
        chain.process(first, first.size)
        chain.configure(96_000, 1)
        val second = sine(1000.0, 96_000, 96_000, 0.1)
        chain.process(second, second.size)
        val gain = 20 * log10(rms(second, 48_000, 96_000) / (0.1 / sqrt(2.0)))
        assertEquals("1 kHz band after switching 44.1 -> 96 kHz", 6.0, gain, 0.5)
    }

    private fun maxStep(buffer: FloatArray, from: Int, to: Int): Float {
        var m = 0f
        for (i in maxOf(1, from) until to) m = maxOf(m, abs(buffer[i] - buffer[i - 1]))
        return m
    }

    @Test
    fun bypassToggleIsClickFree() {
        val rate = 48_000
        val chain = DspChain()
        chain.configure(rate, 1)
        chain.apply(params(5, 6f))
        val total = rate / 2
        val buffer = sine(1000.0, rate, total, 0.25)
        val half = total / 2
        val first = buffer.copyOfRange(0, half)
        chain.process(first, half)
        val wetSlope = maxStep(first, half / 2, half)
        chain.apply(params(5, 6f, enabled = false))
        val second = buffer.copyOfRange(half, total)
        chain.process(second, total - half)
        val joined = first + second
        val transition = maxStep(joined, half - 1, half + 2 * (rate / 50))
        assertTrue("bypass step ${"%.4f".format(transition)} exceeds wet slope ${"%.4f".format(wetSlope)} by >5%", transition <= wetSlope * 1.05f)
    }

    @Test
    fun gainAndPreampChangesAreRamped() {
        val rate = 48_000
        val chain = DspChain()
        chain.configure(rate, 1)
        chain.apply(params())
        val buffer = sine(1000.0, rate, rate, 0.2)
        val quarter = rate / 4
        val a = buffer.copyOfRange(0, quarter)
        chain.process(a, quarter)
        chain.apply(params(5, 12f, preamp = -6f))
        val b = buffer.copyOfRange(quarter, rate)
        chain.process(b, rate - quarter)
        val finalSlope = maxStep(b, b.size / 2, b.size)
        val transition = maxStep(a + b, quarter - 1, quarter + rate / 10)
        // A click is a step on the order of the signal amplitude (~0.4 here); a ramp stays within 10 % of the final slope.
        assertTrue("ramp step ${"%.4f".format(transition)} exceeds final slope ${"%.4f".format(finalSlope)} by >10%", transition <= finalSlope * 1.10f)
        assertEquals(12.0, chain.currentGainDb(5), 0.0)
    }

    @Test
    fun zeroAlgorithmicLatency() {
        val chain = DspChain()
        chain.configure(48_000, 1)
        chain.apply(params(5, 6f))
        val impulse = FloatArray(64).also { it[0] = 1f }
        chain.process(impulse, 64)
        assertTrue("response must start at sample 0, got ${impulse[0]}", impulse[0] > 0.5f)

        val flat = DspChain()
        flat.configure(48_000, 1)
        flat.apply(DspParameters())
        val flatImpulse = FloatArray(8).also { it[0] = 1f }
        flat.process(flatImpulse, 8)
        assertEquals(1f, flatImpulse[0], 0f)
    }

    @Test
    fun clipCounterSeesOversAndHeadroomRemovesThem() {
        val hot = DspChain()
        hot.configure(48_000, 1)
        hot.apply(params(5, 6f))
        val loud = sine(1000.0, 48_000, 4800, 0.9)
        assertTrue("+6 dB on a 0.9 sine must clip", hot.process(loud, 4800) > 0)

        val safe = DspChain()
        safe.configure(48_000, 1)
        safe.apply(params(5, 6f, preamp = -6f))
        val sameSignal = sine(1000.0, 48_000, 4800, 0.7)
        assertEquals("preamp -6 dB leaves headroom", 0, safe.process(sameSignal, 4800))
    }

    @Test
    fun metersReportPeakAndRmsPerChannel() {
        val bus = MeterBus()
        val chain = DspChain(bus)
        chain.configure(48_000, 2)
        chain.apply(DspParameters())
        val frames = 48_000
        val buffer = FloatArray(frames * 2)
        for (i in 0 until frames) {
            val s = sin(2 * PI * 1000.0 * i / 48_000)
            buffer[i * 2] = (0.5 * s).toFloat()
            buffer[i * 2 + 1] = (0.25 * s).toFloat()
        }
        chain.process(buffer, frames, nowNanos = 1L)
        val reading = MeterReading()
        bus.drain(reading, nowNanos = 2L)
        assertEquals(0.5f, reading.peakLeft, 0.005f)
        assertEquals(0.25f, reading.peakRight, 0.005f)
        assertEquals(0.5f / sqrt(2f), reading.rmsLeft, 0.004f)
        assertEquals(0.25f / sqrt(2f), reading.rmsRight, 0.002f)
        assertEquals(frames.toLong(), reading.framesSinceLast)
    }

    @Test
    fun bandsNearNyquistAreSwitchedOff() {
        val chain = DspChain()
        chain.configure(22_050, 1)
        assertFalse("16 kHz band must be off at 22.05 kHz", chain.isBandUsable(9))
        assertTrue(chain.isBandUsable(0))
        chain.apply(params(9, 12f))
        val input = sine(5000.0, 22_050, 2048, 0.5)
        val buffer = input.copyOf()
        chain.process(buffer, 2048)
        assertTrue("disabled band must not change the signal", input.contentEquals(buffer))
    }

    @Test
    fun loadMeterReportsShareOfRealTime() {
        val load = LoadMeter()
        load.record(elapsedNanos = 1_000_000L, frames = 480, sampleRate = 48_000) // 1 ms for 10 ms of audio
        val reading = load.drain()!!
        assertEquals(10.0, reading.averagePercent, 0.01)
        assertEquals(10.0, reading.worstBufferPercent, 0.1)
    }

    /**
     * Measures heap allocation on the processing path with the JVM's per-thread
     * allocation counter (HotSpot). Missing/disabled counters FAIL this gate.
     */
    @Test
    fun processingPathDoesNotAllocate() {
        val counter = AllocationCounter.create()
        checkNotNull(counter) { "Per-thread allocation counter unavailable; allocation gate was NOT measured" }
        check(counter.bytes() >= 0) { "Per-thread allocation counter is disabled" }
        val bus = MeterBus()
        val chain = DspChain(bus)
        chain.configure(48_000, 2)
        chain.apply(params(5, 6f, preamp = -3f))
        val buffer = sine(440.0, 48_000, 1024, 0.3, channels = 2)
        repeat(500) { chain.process(buffer, 1024, it.toLong()) } // warm-up (JIT)
        chain.apply(params(3, -4f, preamp = -3f)) // a parameter change mid-stream
        val before = counter.bytes()
        repeat(5_000) { chain.process(buffer, 1024, it.toLong()) }
        val after = counter.bytes()
        val overhead = counter.overheadBytes()
        val allocated = (after - before - overhead).coerceAtLeast(0)
        println("DSP allocation over 5000 buffers: $allocated bytes (counter overhead $overhead)")
        assertTrue("processing allocated $allocated bytes over 5000 buffers", allocated < 1024)
    }
}

/** Reads com.sun.management.ThreadMXBean#getCurrentThreadAllocatedBytes by reflection (not in android.jar). */
internal class AllocationCounter private constructor(private val bean: Any, private val method: java.lang.reflect.Method) {
    fun bytes(): Long = method.invoke(bean) as Long

    /** Bytes the measurement call itself allocates, so it can be subtracted. */
    fun overheadBytes(): Long {
        repeat(50) { bytes() }
        val a = bytes()
        val b = bytes()
        return b - a
    }

    companion object {
        fun create(): AllocationCounter? = runCatching {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val bean = factory.getMethod("getThreadMXBean").invoke(null)
            val type = Class.forName("com.sun.management.ThreadMXBean")
            val method = type.getMethod("getCurrentThreadAllocatedBytes")
            val counter = AllocationCounter(bean, method)
            repeat(100) { counter.bytes() }
            counter
        }.getOrNull()
    }
}
