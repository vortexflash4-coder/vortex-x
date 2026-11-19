package com.vortexx.player

import com.vortexx.player.audio.dsp.DspChain
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.JhankarMeters
import com.vortexx.player.audio.dsp.JhankarParameters
import com.vortexx.player.audio.dsp.JhankarPresets
import com.vortexx.player.audio.dsp.JhankarReading
import com.vortexx.player.audio.dsp.LookaheadLimiter
import com.vortexx.player.audio.dsp.OutputProfile
import com.vortexx.player.audio.dsp.SpectrumAnalyzer
import com.vortexx.player.audio.dsp.SpectrumBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Jhankar engine inside the one DspChain, as the player runs it: bypass comparison, latency, the ceiling with
 * every preset and output profile, allocation and CPU, the analyzer reading the processed output, and the
 * before / after comparison of the original sound, the Jhankar sound and the Bluetooth tower profile.
 */
class JhankarChainTest {

    private val rate = 48_000

    private fun db(ratio: Double) = 20 * log10(ratio)

    private fun chain(
        audioLab: DspParameters,
        jhankar: JhankarParameters,
        meters: JhankarMeters? = null,
        spectrum: SpectrumBus? = null,
        channels: Int = 2,
        sampleRate: Int = rate,
    ): DspChain {
        val c = DspChain(null, null, null, meters, spectrum)
        c.configure(sampleRate, channels)
        c.apply(audioLab)
        c.applyJhankar(jhankar)
        return c
    }

    private fun run(chain: DspChain, input: FloatArray, channels: Int = 2, block: Int = 480): FloatArray {
        val output = input.copyOf()
        val chunk = FloatArray(block * channels)
        var offset = 0
        val frames = input.size / channels
        while (offset < frames) {
            val n = minOf(block, frames - offset)
            System.arraycopy(output, offset * channels, chunk, 0, n * channels)
            chain.process(chunk, n)
            System.arraycopy(chunk, 0, output, offset * channels, n * channels)
            offset += n
        }
        return output
    }

    private fun tone(freq: Double, frames: Int, amplitude: Double, channels: Int = 2): FloatArray {
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * freq * i / rate)).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    private fun level(buffer: FloatArray, channel: Int, hz: Double, from: Int, to: Int): Double {
        var re = 0.0
        var im = 0.0
        var sum = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            val x = buffer[(from + i) * 2 + channel] * w
            val a = 2 * PI * hz * (from + i) / rate
            re += x * cos(a)
            im += x * sin(a)
            sum += w
        }
        return 2 * sqrt(re * re + im * im) / sum
    }

    private fun bandEnergy(buffer: FloatArray, low: Double, high: Double): Double {
        var total = 0.0
        var hz = low
        val frames = buffer.size / 2
        while (hz < high) {
            val a = level(buffer, 0, hz, rate, frames)
            total += a * a
            hz *= 1.1
        }
        return total
    }

    private fun peak(buffer: FloatArray): Double = buffer.maxOf { abs(it.toDouble()) }

    private fun rms(buffer: FloatArray): Double = sqrt(buffer.sumOf { it.toDouble() * it } / buffer.size)

    /** A Hindi-style stereo mix: dhol thump, tabla slap, a voice-like band and hi-hat noise. */
    private fun hindiMix(seconds: Int, gain: Double = 1.0): FloatArray {
        val random = Random(21)
        val frames = rate * seconds
        val out = FloatArray(frames * 2)
        var voiceL = 0.0
        var voiceR = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val beat = t % 0.5
            val dhol = 0.55 * exp(-beat / 0.09) * sin(2 * PI * 80.0 * t) * (1 + 0.3 * sin(2 * PI * 160.0 * t))
            val tabla = 0.3 * exp(-((t + 0.25) % 0.5) / 0.012) * sin(2 * PI * 3000.0 * t)
            voiceL = 0.97 * voiceL + 0.03 * random.nextGaussian()
            voiceR = 0.97 * voiceR + 0.03 * random.nextGaussian()
            val hat = 0.06 * exp(-((t + 0.125) % 0.25) / 0.02) * random.nextGaussian()
            out[i * 2] = ((dhol + tabla + 1.2 * voiceL + hat) * gain).toFloat()
            out[i * 2 + 1] = ((dhol + tabla + 1.2 * voiceR + hat) * gain).toFloat()
        }
        return out
    }

    private val limiterOn = DspParameters(enabled = true, limiter = true)
    private val plain = DspParameters(enabled = true)

    @Test
    fun theEngineOffIsExactlyTheAudioLabAlone() {
        val random = Random(2)
        val input = FloatArray(rate * 2 * 2) { (random.nextGaussian() * 0.2).toFloat() }
        val labOnly = DspParameters(enabled = true, bassDb = 4f)
        val alone = run(chain(labOnly, JhankarParameters.OFF), input)
        val withOff = run(chain(labOnly, JhankarPresets.WEDDING_DJ.parameters.copy(engine = false)), input)
        val changed = alone.indices.count { alone[it] != withOff[it] }
        DspMeasure.record("Jhankar engine off with every control set vs the Audio Lab alone: changed samples", "$changed of ${alone.size}", "0", changed == 0)
        assertEquals(0, changed)

        // On, then off again: once the 20 ms fade is over the sound is the unprocessed one, bit for bit.
        val c = chain(plain, JhankarPresets.WEDDING_DJ.parameters.copy(engine = true))
        val first = run(c, input.copyOfRange(0, rate * 2))
        c.applyJhankar(JhankarPresets.WEDDING_DJ.parameters.copy(engine = false))
        val second = run(c, input.copyOfRange(rate * 2, input.size))
        assertFalse(first.contentEquals(input.copyOfRange(0, rate * 2)))
        val tail = rate / 2 * 2
        var differ = 0
        for (i in rate * 2 - tail until second.size) if (second[i] != input[rate * 2 + i]) differ++
        DspMeasure.record("Jhankar engine switched off again: changed samples after the fade", "$differ", "0", differ == 0)
        assertEquals(0, differ)
    }

    @Test
    fun noPresetAndNoOutputProfileCanPassTheCeiling() {
        val random = Random(5)
        val loud = FloatArray(rate * 6 * 2) { (random.nextGaussian() * 0.6).toFloat().coerceIn(-1f, 1f) }
        val all = JhankarPresets.BUILT_IN.map { it.name to it.parameters } + OutputProfile.entries.map { it.label to JhankarPresets.defaultFor(it) }
        var highest = 0.0
        var worst = ""
        for ((name, p) in all) {
            val out = run(chain(limiterOn, p.copy(engine = true)), loud)
            assertFalse("$name made NaN", out.any { it.isNaN() })
            val m = peak(out)
            if (m > highest) { highest = m; worst = name }
        }
        DspMeasure.record(
            "Every Jhankar preset (10) and output profile (4) on 6 s of loud noise with the limiter: highest output sample ($worst)",
            DspMeasure.f(db(highest), 3) + " dBFS", "≤ -0.5 dBFS", highest <= Math.pow(10.0, LookaheadLimiter.CEILING_DB / 20.0) + 1e-6,
        )
        assertTrue("highest $highest ($worst)", highest <= Math.pow(10.0, LookaheadLimiter.CEILING_DB / 20.0) + 1e-6)
    }

    @Test
    fun theEngineAddsNoDelayOfItsOwn() {
        val impulse = FloatArray(4800 * 2)
        impulse[0] = 0.5f
        impulse[1] = 0.5f
        val p = JhankarPresets.DEEP_BASS.parameters.copy(engine = true, compression = 0, transient = 0)
        val out = run(chain(plain, p), impulse)
        val first = (0 until 4800).first { abs(out[it * 2]) > 1e-4 }
        DspMeasure.record("Jhankar engine: first output sample of an impulse (algorithmic latency)", "$first samples", "0 samples (the limiter's 5 ms is the chain's)", first == 0)
        assertEquals(0, first)
        val without = chain(limiterOn, JhankarParameters.OFF)
        val with = chain(limiterOn, p)
        run(without, FloatArray(rate / 10 * 2))
        run(with, FloatArray(rate / 10 * 2))
        assertEquals(without.latencyFrames, with.latencyFrames)
    }

    @Test
    fun theAnalyzerShowsTheProcessedOutput() {
        val bus = SpectrumBus()
        val boosted = JhankarParameters(engine = true, intensity = 100, autoHeadroom = false)
            .withBand(5, com.vortexx.player.audio.dsp.JhankarBand(1000f, 6f, 1.1f))
        val c = chain(plain, boosted, spectrum = bus)
        run(c, tone(1000.0, rate, 0.25))
        val analyzer = SpectrumAnalyzer()
        val bands = FloatArray(analyzer.bands)
        analyzer.analyze(bus, bands)
        val top = bands.indices.maxByOrNull { bands[it] }!!
        // -12 dBFS in, +6 dB set: the analyzer must read about -6 dBFS (the input alone would read -12).
        DspMeasure.record("Analyzer reads the processed output: 1 kHz at -12 dBFS with a +6 dB Jhankar band", DspMeasure.f(bands[top].toDouble()) + " dBFS", "-6 ± 1.5 dBFS", abs(bands[top] + 6.0) <= 1.5)
        assertEquals(-6.0, bands[top].toDouble(), 1.5)
        assertEquals(rate, bus.sampleRate)
    }

    @Test
    fun theMetersReportTheEngineThroughTheChain() {
        val meters = JhankarMeters()
        val p = JhankarParameters(engine = true, intensity = 100, dynamicBass = 100, compression = 100)
        val c = chain(plain, p, meters = meters)
        val reading = JhankarReading()
        run(c, FloatArray(rate * 2 * 2) { i -> (0.2 * sin(2 * PI * 1000.0 * (i / 2) / rate) + 0.008 * sin(2 * PI * 60.0 * (i / 2) / rate)).toFloat() })
        meters.drain(reading)
        assertTrue("bass ${reading.bassBoostDb}", reading.bassBoostDb > 2f)
        assertTrue("compression ${reading.compressionDb}", reading.compressionDb > 0.5f)
    }

    @Test
    fun theJhankarStageDoesNotAllocateAndFitsTheCpuBudget() {
        val counter = AllocationCounter.create()
        if (counter == null) DspMeasure.record("Heap allocated with the Jhankar engine on", "not measured (no allocation counter on this JVM)", "< 1024 bytes", true)
        assumeTrue("Per-thread allocation counter not available on this JVM", counter != null)
        val meters = JhankarMeters()
        val chain = DspChain(null, null, null, meters, SpectrumBus())
        chain.configure(rate, 2)
        val lab = DspParameters(enabled = true, limiter = true, speakerProtect = true, compressor = 30, widthPercent = 110)
        chain.apply(lab)
        val tower = JhankarPresets.defaultFor(OutputProfile.Tower).copy(engine = true)
        val party = JhankarPresets.defaultFor(OutputProfile.PartySpeaker).copy(engine = true)
        chain.applyJhankar(tower)
        val buffer = tone(440.0, 1024, 0.5)
        repeat(500) { chain.process(buffer, 1024, it.toLong()) }
        val before = counter!!.bytes()
        repeat(5_000) {
            if (it == 1_000) chain.applyJhankar(party) // a profile change (a route change) in the middle
            if (it == 3_000) chain.applyJhankar(tower)
            chain.process(buffer, 1024, it.toLong())
        }
        val allocated = (counter.bytes() - before - counter.overheadBytes()).coerceAtLeast(0)
        DspMeasure.record("Heap allocated with the Jhankar engine on (5000 buffers incl. two profile changes)", "$allocated bytes", "< 1024 bytes", allocated < 1024)
        assertTrue("allocated $allocated bytes", allocated < 1024)

        val buffers = 4_000
        val started = System.nanoTime()
        repeat(buffers) { chain.process(buffer, 1024, it.toLong()) }
        val elapsed = System.nanoTime() - started
        val load = elapsed * 100.0 / (buffers.toLong() * 1024 * 1_000_000_000L / rate)
        DspMeasure.record("CI machine: CPU time for 48 kHz stereo, Audio Lab + Jhankar engine (Bluetooth tower profile)", DspMeasure.f(load, 3) + " % of real time", "< 25 % (loose; phone figure comes from the Lab)", load < 25.0)
        assertTrue("took ${DspMeasure.f(load)} %", load < 25.0)
    }

    @Test
    fun beforeAndAfterOriginalJhankarAndBluetoothTower() {
        val input = hindiMix(8)
        val inRms = rms(input)
        class Result(val name: String, val out: FloatArray, val clipped: Int)

        fun render(jhankar: JhankarParameters): Result {
            val c = chain(limiterOn, jhankar)
            val out = input.copyOf()
            val chunk = FloatArray(480 * 2)
            var clipped = 0
            var offset = 0
            while (offset < out.size / 2) {
                val n = minOf(480, out.size / 2 - offset)
                System.arraycopy(out, offset * 2, chunk, 0, n * 2)
                clipped += c.process(chunk, n)
                System.arraycopy(chunk, 0, out, offset * 2, n * 2)
                offset += n
            }
            return Result("", out, clipped)
        }
        val original = render(JhankarParameters.OFF)
        val jhankar = render(JhankarPresets.JHANKAR_ORIGINAL.parameters.copy(engine = true))
        val tower = render(JhankarPresets.defaultFor(OutputProfile.Tower).copy(engine = true))

        // The limiter looks 5 ms ahead, so every render is the same length and the same delay; the first second is skipped.
        fun describe(r: Result): String {
            val o = r.out
            val tail = o.copyOfRange(rate * 2, o.size)
            val ref = input.copyOfRange(rate * 2, input.size)
            val rmsDb = db(rms(tail) / rms(ref))
            val bass = 10 * log10(bandEnergy(o, 40.0, 120.0) / bandEnergy(original.out, 40.0, 120.0))
            val vocal = 10 * log10(bandEnergy(o, 2000.0, 4500.0) / bandEnergy(original.out, 2000.0, 4500.0))
            val air = 10 * log10(bandEnergy(o, 9000.0, 16000.0) / bandEnergy(original.out, 9000.0, 16000.0))
            val crest = db(peak(tail) / rms(tail))
            return "RMS ${DspMeasure.f(rmsDb)} dB, peak ${DspMeasure.f(db(peak(o)))} dBFS, crest ${DspMeasure.f(crest)} dB, 40-120 Hz ${DspMeasure.f(bass)} dB, 2-4.5 kHz ${DspMeasure.f(vocal)} dB, 9-16 kHz ${DspMeasure.f(air)} dB, over 0 dBFS: ${r.clipped}"
        }
        DspMeasure.record("Report, original (Audio Lab limiter only)", "input RMS ${DspMeasure.f(db(inRms))} dBFS, ${describe(original)}", "reference", true)
        DspMeasure.record("Report, Jhankar Original preset", describe(jhankar), "report", true)
        DspMeasure.record("Report, Bluetooth Tower profile", describe(tower), "report", true)

        for (r in listOf(jhankar, tower)) {
            assertEquals("no sample over 0 dBFS", 0, r.clipped)
            assertTrue(peak(r.out) <= Math.pow(10.0, LookaheadLimiter.CEILING_DB / 20.0) + 1e-6)
        }
        val bassJ = 10 * log10(bandEnergy(jhankar.out, 40.0, 120.0) / bandEnergy(original.out, 40.0, 120.0))
        val bassT = 10 * log10(bandEnergy(tower.out, 40.0, 120.0) / bandEnergy(original.out, 40.0, 120.0))
        val vocalJ = 10 * log10(bandEnergy(jhankar.out, 2000.0, 4500.0) / bandEnergy(original.out, 2000.0, 4500.0))
        assertTrue("jhankar bass $bassJ", bassJ > 1.0)
        assertTrue("tower bass $bassT", bassT > bassJ - 1.0)
        assertTrue("jhankar presence $vocalJ", vocalJ > 1.0)
        // Not merely louder: with the output level matched to the original, the bass band is still up.
        val levelJ = db(rms(jhankar.out.copyOfRange(rate * 2, jhankar.out.size)) / rms(original.out.copyOfRange(rate * 2, original.out.size)))
        val matched = bassJ - levelJ
        DspMeasure.record("Report, Jhankar Original loudness-matched: bass change with the level matched to the original", DspMeasure.f(matched) + " dB (level change ${DspMeasure.f(levelJ)} dB)", "> +1 dB", matched > 1.0)
        assertTrue("matched $matched", matched > 1.0)
        assertTrue(max(levelJ, 0.0) < 6.0)
    }
}
