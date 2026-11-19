package com.vortexx.player

import com.vortexx.player.audio.dsp.JhankarBand
import com.vortexx.player.audio.dsp.JhankarCurve
import com.vortexx.player.audio.dsp.JhankarMeters
import com.vortexx.player.audio.dsp.JhankarParameters
import com.vortexx.player.audio.dsp.JhankarPresets
import com.vortexx.player.audio.dsp.JhankarReading
import com.vortexx.player.audio.dsp.JhankarStage
import com.vortexx.player.audio.dsp.JhankarTone
import com.vortexx.player.audio.dsp.OutputProfile
import com.vortexx.player.audio.dsp.PresetResult
import com.vortexx.player.audio.dsp.SpectrumAnalyzer
import com.vortexx.player.audio.dsp.SpectrumBus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Jhankar engine measured on real sample buffers: every control does what its label says, the equalizer
 * on screen is the equalizer the audio thread runs, dynamic bass follows balance and not loudness, nothing
 * steps the sound, and everything neutral leaves the samples untouched.
 */
class JhankarEngineTest {

    private val rate = 48_000
    private val on = JhankarParameters(engine = true, intensity = 100)

    private fun db(ratio: Double) = 20 * log10(ratio)

    private fun stage(p: JhankarParameters, sampleRate: Int = rate, channels: Int = 2, meters: JhankarMeters? = null): JhankarStage {
        val s = JhankarStage(meters)
        s.configure(sampleRate, channels)
        s.set(p)
        return s
    }

    private fun run(stage: JhankarStage, input: DoubleArray, channels: Int = 2): DoubleArray {
        val out = DoubleArray(input.size)
        val frame = DoubleArray(8)
        val frames = input.size / channels
        var f = 0
        while (f < frames) {
            val n = min(32, frames - f)
            stage.beginBlock(n)
            for (i in 0 until n) {
                for (c in 0 until channels) frame[c] = input[(f + i) * channels + c]
                stage.process(frame, channels)
                for (c in 0 until channels) out[(f + i) * channels + c] = frame[c]
            }
            f += n
        }
        stage.endBuffer()
        return out
    }

    private fun tones(frames: Int, channels: Int, sampleRate: Int, vararg tone: Pair<Double, Double>): DoubleArray {
        val out = DoubleArray(frames * channels)
        for (i in 0 until frames) {
            var v = 0.0
            for ((hz, amp) in tone) v += amp * sin(2 * PI * hz * i / sampleRate)
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    /** Amplitude of the [hz] component of a channel over frames [from, to) (Hann-windowed, so other tones do not leak in). */
    private fun level(buffer: DoubleArray, channels: Int, channel: Int, hz: Double, from: Int, to: Int, sampleRate: Int = rate): Double {
        var re = 0.0
        var im = 0.0
        var sum = 0.0
        val n = to - from
        for (i in 0 until n) {
            val w = 0.5 - 0.5 * cos(2 * PI * i / n)
            val x = buffer[(from + i) * channels + channel] * w
            val a = 2 * PI * hz * (from + i) / sampleRate
            re += x * cos(a)
            im += x * sin(a)
            sum += w
        }
        return 2 * sqrt(re * re + im * im) / sum
    }

    private fun peak(buffer: DoubleArray, channels: Int, channel: Int, from: Int, to: Int): Double {
        var best = 0.0
        for (i in from until to) best = max(best, abs(buffer[i * channels + channel]))
        return best
    }

    private fun largestStep(buffer: DoubleArray, channels: Int, from: Int, to: Int): Double {
        var best = 0.0
        for (i in from + 1 until to) best = max(best, abs(buffer[i * channels] - buffer[(i - 1) * channels]))
        return best
    }

    private fun withGain(band: Int, gainDb: Float, freq: Float? = null, q: Float? = null): List<JhankarBand> =
        JhankarParameters.FLAT_BANDS.mapIndexed { i, b ->
            if (i == band) JhankarBand(freq ?: b.freqHz, gainDb, q ?: b.q) else b
        }

    @Test
    fun everythingNeutralLeavesTheSamplesAsTheyWere() {
        val random = Random(1)
        val input = DoubleArray(rate * 2) { random.nextGaussian() * 0.2 }
        val neutral = run(stage(on.copy(autoHeadroom = true)), input)
        val changed = input.indices.count { neutral[it] != input[it] }
        DspMeasure.record("Jhankar engine on, every control neutral: changed samples", "$changed of ${input.size}", "0", changed == 0)
        assertEquals(0, changed)

        // Engine off with everything set: also untouched.
        val everything = JhankarPresets.WEDDING_DJ.parameters.copy(engine = false)
        val off = run(stage(everything), input)
        val offChanged = input.indices.count { off[it] != input[it] }
        DspMeasure.record("Jhankar engine off, every control set: changed samples", "$offChanged of ${input.size}", "0", offChanged == 0)
        assertEquals(0, offChanged)
    }

    @Test
    fun theAudioMatchesTheEqualizerCurveAtEverySampleRate() {
        var worst = 0.0
        var checks = 0
        for (sampleRate in intArrayOf(44_100, 48_000, 96_000)) {
            for (band in 0 until JhankarTone.BANDS) {
                val centre = JhankarTone.DEFAULT_FREQ_HZ[band].toDouble()
                if (centre >= 0.45 * sampleRate) continue
                for (gain in floatArrayOf(6f, -6f, 10f)) {
                    val p = on.copy(autoHeadroom = false, bands = withGain(band, gain))
                    val input = tones(sampleRate * 2, 2, sampleRate, centre to 0.1)
                    val out = run(stage(p, sampleRate), input)
                    val measured = db(level(out, 2, 0, centre, sampleRate, sampleRate * 2, sampleRate) / 0.1)
                    val curve = JhankarCurve.responseDb(p, centre, sampleRate)
                    worst = max(worst, abs(measured - curve))
                    checks++
                    if (band in 1..8) assertEquals("bell centre gain", gain.toDouble(), curve, 0.01)
                }
            }
        }
        DspMeasure.record("Jhankar EQ: audio vs the curve on screen, largest error ($checks checks, 44.1/48/96 kHz)", DspMeasure.f(worst, 3) + " dB", "≤ 0.15 dB", worst <= 0.15)
        assertTrue("worst $worst dB", worst <= 0.15)
    }

    @Test
    fun frequencyAndQAreRealControls() {
        // The same +8 dB band: a narrow Q leaves the neighbour alone, a wide Q lifts it; moving the frequency moves the peak.
        val wide = on.copy(autoHeadroom = false, bands = withGain(5, 8f, q = 0.5f))
        val narrow = on.copy(autoHeadroom = false, bands = withGain(5, 8f, q = 4f))
        val neighbour = 1500.0
        val wideAt = JhankarCurve.responseDb(wide, neighbour)
        val narrowAt = JhankarCurve.responseDb(narrow, neighbour)
        val measuredWide = db(level(run(stage(wide), tones(rate * 2, 2, rate, neighbour to 0.1)), 2, 0, neighbour, rate, rate * 2) / 0.1)
        val measuredNarrow = db(level(run(stage(narrow), tones(rate * 2, 2, rate, neighbour to 0.1)), 2, 0, neighbour, rate, rate * 2) / 0.1)
        DspMeasure.record("Jhankar EQ: +8 dB band at 1 kHz, gain at 1.5 kHz with Q 0.5 / Q 4", DspMeasure.f(measuredWide) + " / " + DspMeasure.f(measuredNarrow) + " dB", "wide > narrow + 2 dB", measuredWide > measuredNarrow + 2)
        assertTrue(measuredWide > measuredNarrow + 2)
        assertEquals(wideAt, measuredWide, 0.15)
        assertEquals(narrowAt, measuredNarrow, 0.15)

        val moved = on.copy(autoHeadroom = false, bands = withGain(5, 8f, freq = 2200f))
        val atNew = db(level(run(stage(moved), tones(rate * 2, 2, rate, 2200.0 to 0.1)), 2, 0, 2200.0, rate, rate * 2) / 0.1)
        val atOld = db(level(run(stage(moved), tones(rate * 2, 2, rate, 1000.0 to 0.1)), 2, 0, 1000.0, rate, rate * 2) / 0.1)
        DspMeasure.record("Jhankar EQ: band moved from 1 kHz to 2.2 kHz, gain at 2.2 kHz / at 1 kHz", DspMeasure.f(atNew) + " / " + DspMeasure.f(atOld) + " dB", "8 ± 0.2 / < 3 dB", abs(atNew - 8) < 0.2 && atOld < 3)
        assertEquals(8.0, atNew, 0.2)
        assertTrue(atOld < 3)
    }

    @Test
    fun theEqualizerOffSwitchAndPreampWork() {
        val boosted = on.copy(autoHeadroom = false, preampDb = -3f, bands = withGain(5, 6f))
        val input = tones(rate * 2, 2, rate, 1000.0 to 0.1)
        val onOut = run(stage(boosted), input)
        val withEq = db(level(onOut, 2, 0, 1000.0, rate, rate * 2) / 0.1)
        assertEquals(3.0, withEq, 0.1) // +6 dB band, -3 dB preamp
        val offOut = run(stage(boosted.copy(eqOn = false)), input)
        assertTrue(input.indices.all { input[it] == offOut[it] })
    }

    @Test
    fun autoHeadroomKeepsTheAverageLevelOfMusicWhereItWas() {
        val bands = JhankarParameters.FLAT_BANDS.mapIndexed { i, b -> if (i == 2 || i == 3) JhankarBand(b.freqHz, 9f, b.q) else b }
        val p = on.copy(bands = bands)
        val peakDb = JhankarCurve.peakDb(p)
        val headroom = JhankarCurve.headroomDb(p)
        assertTrue("headroom $headroom must be below the peak $peakDb", headroom in 1.0..peakDb)
        assertEquals(0.0, JhankarCurve.headroomDb(p.copy(autoHeadroom = false)), 0.0)

        // Pink-like noise (equal energy per octave, 30 Hz - 12 kHz): the level through the stage against the level in.
        val random = Random(12)
        val frames = rate * 8
        val input = DoubleArray(frames * 2)
        val freqs = DoubleArray(120) { 30.0 * Math.pow(400.0, it / 119.0) }
        val phases = DoubleArray(freqs.size) { random.nextDouble() * 2 * PI }
        for (i in 0 until frames) {
            var v = 0.0
            for (k in freqs.indices) v += sin(2 * PI * freqs[k] * i / rate + phases[k]) / sqrt(freqs[k])
            input[i * 2] = v * 0.08
            input[i * 2 + 1] = v * 0.08
        }
        fun rms(b: DoubleArray) = sqrt(b.sumOf { it * it } / b.size)
        val withHeadroom = run(stage(p), input)
        val without = run(stage(p.copy(autoHeadroom = false)), input)
        val a = db(rms(withHeadroom) / rms(input))
        val b = db(rms(without) / rms(input))
        DspMeasure.record(
            "Jhankar auto headroom: EQ with +9 dB at 125 and 250 Hz (curve peak ${DspMeasure.f(peakDb)} dB, headroom ${DspMeasure.f(headroom)} dB), level of pink-like music with / without headroom",
            DspMeasure.f(a) + " / " + DspMeasure.f(b) + " dB", "with: 0 ± 1 dB, without: > 2 dB", abs(a) <= 1.0 && b > 2.0,
        )
        assertEquals(0.0, a, 1.0)
        assertTrue("without $b", b > 2.0)
    }

    @Test
    fun vocalClarityShapesThePresenceAndTheMud() {
        val p = on.copy(autoHeadroom = false, vocalClarity = 100, hfDetail = 100)
        val presence = JhankarCurve.responseDb(p, 3000.0)
        val mud = JhankarCurve.responseDb(p, 320.0)
        val air = JhankarCurve.responseDb(p, 14000.0)
        val measured = db(level(run(stage(p), tones(rate * 2, 2, rate, 3000.0 to 0.1)), 2, 0, 3000.0, rate, rate * 2) / 0.1)
        val measuredMud = db(level(run(stage(p), tones(rate * 2, 2, rate, 320.0 to 0.1)), 2, 0, 320.0, rate, rate * 2) / 0.1)
        DspMeasure.record("Jhankar vocal clarity 100 %: gain at 3 kHz / 320 Hz (mud) / 14 kHz (detail)", DspMeasure.f(measured) + " / " + DspMeasure.f(measuredMud) + " / " + DspMeasure.f(air) + " dB", "> +4 / < -2.5 / > +3 dB", measured > 4 && measuredMud < -2.5 && air > 3)
        assertTrue(measured > 4.0 && presence > 4.0)
        assertTrue(measuredMud < -2.5 && mud < -2.5)
        assertTrue(air > 3.0)
        // Intensity scales it; 0 intensity is nothing.
        assertEquals(0.0, JhankarCurve.responseDb(p.copy(intensity = 0), 3000.0), 1e-9)
    }

    @Test
    fun dynamicBassFollowsTheBalanceAndNotTheLoudness() {
        val p = on.copy(dynamicBass = 100)
        fun boostOf(input: DoubleArray): Double {
            val out = run(stage(p), input)
            val from = rate * 4
            val to = rate * 5
            return db(level(out, 2, 0, 60.0, from, to) / level(input, 2, 0, 60.0, from, to))
        }
        val seconds = rate * 5
        val light = tones(seconds, 2, rate, 1000.0 to 0.2, 60.0 to 0.01) // bass 26 dB under the rest
        val lightQuiet = tones(seconds, 2, rate, 1000.0 to 0.02, 60.0 to 0.001) // same balance, 20 dB quieter
        val heavy = tones(seconds, 2, rate, 1000.0 to 0.05, 60.0 to 0.3) // bass already strong
        val hot = tones(seconds, 2, rate, 1000.0 to 0.9, 2000.0 to 0.9, 3000.0 to 0.9, 4000.0 to 0.9, 60.0 to 0.5) // light balance, bass peak near the limit
        val a = boostOf(light)
        val b = boostOf(lightQuiet)
        val c = boostOf(heavy)
        val d = boostOf(hot)
        DspMeasure.record("Jhankar dynamic bass: 60 Hz boost for a bass-light track / the same 20 dB quieter", DspMeasure.f(a) + " / " + DspMeasure.f(b) + " dB", "equal ± 0.3 dB, ≥ 5.5 dB", abs(a - b) <= 0.3 && a >= 5.5)
        DspMeasure.record("Jhankar dynamic bass: boost for a bass-heavy track / for a light track with the bass near full scale", DspMeasure.f(c) + " / " + DspMeasure.f(d) + " dB", "≤ 0.5 / bass stays ≤ -3 dBFS", c <= 0.5 && 0.5 * Math.pow(10.0, d / 20.0) <= 0.708 * 1.05)
        assertTrue("light $a", a >= 5.5)
        assertEquals("level independence", a, b, 0.3)
        assertTrue("heavy $c", c <= 0.5)
        assertTrue("hot ${0.5 * Math.pow(10.0, d / 20.0)}", 0.5 * Math.pow(10.0, d / 20.0) <= 0.708 * 1.05)
        // Silence gets no boost, and the bass switch turns it all off.
        assertEquals(0.0, run(stage(p), DoubleArray(rate * 2)).sum(), 0.0)
        val bassOff = run(stage(p.copy(bassOn = false)), light)
        assertTrue(light.indices.all { light[it] == bassOff[it] })
    }

    @Test
    fun bassHarmonicsAddOnlyHarmonicsAtAnyLevel() {
        val p = on.copy(bassHarmonics = 100)
        fun ratios(amplitude: Double): Pair<Double, Double> {
            val input = tones(rate * 3, 2, rate, 60.0 to amplitude)
            val out = run(stage(p), input)
            val third = level(out, 2, 0, 180.0, rate, rate * 3)
            val second = level(out, 2, 0, 120.0, rate, rate * 3)
            val fundamental = level(out, 2, 0, 60.0, rate, rate * 3) / level(input, 2, 0, 60.0, rate, rate * 3)
            return Pair(db(max(third, second) / amplitude), db(fundamental))
        }
        val (loud, fundamentalLoud) = ratios(0.3)
        val (quiet, _) = ratios(0.03)
        DspMeasure.record("Jhankar bass harmonics 100 %: harmonic level under the 60 Hz tone at -10 dBFS / at -30 dBFS", DspMeasure.f(loud) + " / " + DspMeasure.f(quiet) + " dB", "-24…-6 dB, equal ± 1.5 dB", loud in -24.0..-6.0 && abs(loud - quiet) <= 1.5)
        assertTrue("harmonics $loud", loud in -24.0..-6.0)
        assertEquals(loud, quiet, 1.5)
        assertEquals("the fundamental stays within 1 dB", 0.0, fundamentalLoud, 1.0)
        // Switched off: no harmonic at all.
        val none = run(stage(on), tones(rate * 3, 2, rate, 60.0 to 0.3))
        assertTrue(level(none, 2, 0, 180.0, rate, rate * 3) < 1e-6)
    }

    @Test
    fun airAddsHighHarmonicsOfTheDetailBand() {
        val input = tones(rate * 2, 2, rate, 6000.0 to 0.2)
        val withAir = run(stage(on.copy(airHarmonics = 100)), input)
        val without = run(stage(on), input)
        val added = level(withAir, 2, 0, 18000.0, rate, rate * 2)
        val before = level(without, 2, 0, 18000.0, rate, rate * 2)
        val fundamental = db(level(withAir, 2, 0, 6000.0, rate, rate * 2) / 0.2)
        DspMeasure.record("Jhankar air 100 %: 18 kHz harmonic of a 6 kHz tone, level / before; 6 kHz level change", DspMeasure.f(db(added)) + " dBFS / " + DspMeasure.f(db(max(before, 1e-9))) + " dBFS; " + DspMeasure.f(fundamental) + " dB", "> -50 dBFS, none before", added > 0.003 && before < 1e-6)
        assertTrue("added $added", added > 0.003)
        assertTrue(before < 1e-6)
        assertTrue("fundamental change $fundamental", abs(fundamental) < 2.5)
    }

    @Test
    fun transientPunchLiftsAttacksAndLeavesSteadySoundAlone() {
        val frames = rate * 4
        val bursts = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val t = (i % (rate / 4)).toDouble() / rate // a burst every 250 ms
            val v = 0.3 * exp(-t / 0.015) * sin(2 * PI * 2000.0 * t) + 0.02 * sin(2 * PI * 1000.0 * i / rate)
            bursts[i * 2] = v
            bursts[i * 2 + 1] = v
        }
        val p = on.copy(transient = 100)
        val out = run(stage(p), bursts)
        val gain = db(peak(out, 2, 0, rate, frames) / peak(bursts, 2, 0, rate, frames))
        DspMeasure.record("Jhankar transient punch 100 %: attack peak of a 2 kHz burst train", "+" + DspMeasure.f(gain) + " dB", "+2.5…+6.5 dB", gain in 2.5..6.5)
        assertTrue("gain $gain", gain in 2.5..6.5)

        val steady = tones(rate * 3, 2, rate, 1000.0 to 0.3)
        val steadyOut = run(stage(p), steady)
        val change = db(level(steadyOut, 2, 0, 1000.0, rate, rate * 3) / 0.3)
        DspMeasure.record("Jhankar transient punch 100 %: change of a steady 1 kHz tone", DspMeasure.f(change, 3) + " dB", "± 0.1 dB", abs(change) <= 0.1)
        assertEquals(0.0, change, 0.1)
        val none = run(stage(p.copy(transient = 0)), bursts)
        assertTrue(bursts.indices.all { bursts[it] == none[it] })
    }

    @Test
    fun theCompressorFollowsItsRuleAndMakesUpHalfTheLoss() {
        val p = on.copy(compression = 100)
        val input = tones(rate * 3, 2, rate, 1000.0 to 0.5) // -6 dBFS
        val out = run(stage(p), input)
        val measured = db(peak(out, 2, 0, rate * 2, rate * 3))
        // Threshold -16 dB, ratio 3:1 -> -12.67 dBFS; make-up: half of the 10.67 dB it takes off a full-scale peak,
        // so what is below the threshold comes out louder and only the peaks are held (the limiter ends the chain).
        val expected = -16.0 + 10.0 / 3.0 + 0.5 * (0.0 - (-16.0 + 16.0 / 3.0))
        DspMeasure.record("Jhankar compressor 100 %: 1 kHz at -6 dBFS (rule with make-up: ${DspMeasure.f(expected)} dBFS)", DspMeasure.f(measured) + " dBFS", DspMeasure.f(expected) + " ± 0.7 dBFS", abs(measured - expected) <= 0.7)
        assertEquals(expected, measured, 0.7)
        val gentle = run(stage(on.copy(compression = 30)), input)
        assertTrue(db(peak(gentle, 2, 0, rate * 2, rate * 3)) > measured)
    }

    @Test
    fun stereoWidthAndMonoBassWorkOnMidAndSide() {
        val frames = rate * 2
        val left = DoubleArray(frames * 2)
        for (i in 0 until frames) left[i * 2] = 0.5 * sin(2 * PI * 1000.0 * i / rate)
        val wide = run(stage(on.copy(widthPercent = 150)), left)
        // Mid 0.25 stays, side 0.25 becomes 0.375: left 0.625, right -0.125.
        assertEquals(0.625, level(wide, 2, 0, 1000.0, rate, frames), 0.005)
        assertEquals(0.125, level(wide, 2, 1, 1000.0, rate, frames), 0.005)
        val narrow = run(stage(on.copy(widthPercent = 50)), left)
        assertEquals(0.375, level(narrow, 2, 0, 1000.0, rate, frames), 0.005)
        assertEquals(0.125, level(narrow, 2, 1, 1000.0, rate, frames), 0.005)

        // Pure side signals (left and right opposite): 60 Hz is cut by mono bass, 1 kHz is not.
        val side = DoubleArray(frames * 2)
        for (i in 0 until frames) {
            val v = 0.3 * sin(2 * PI * 60.0 * i / rate) + 0.3 * sin(2 * PI * 1000.0 * i / rate)
            side[i * 2] = v
            side[i * 2 + 1] = -v
        }
        val mono = run(stage(on.copy(monoBelowHz = 120)), side)
        val low = db(level(mono, 2, 0, 60.0, rate, frames) / 0.3)
        val mid = db(level(mono, 2, 0, 1000.0, rate, frames) / 0.3)
        DspMeasure.record("Jhankar mono bass 120 Hz: side content at 60 Hz / at 1 kHz", DspMeasure.f(low) + " / " + DspMeasure.f(mid, 3) + " dB", "≤ -10 / ± 0.1 dB", low <= -10 && abs(mid) <= 0.1)
        assertTrue("low $low", low <= -10.0)
        assertEquals(0.0, mid, 0.1)

        // The stereo switch turns both off, mono streams have no sides.
        val off = run(stage(on.copy(widthPercent = 150, monoBelowHz = 120, stereoOn = false)), left)
        assertTrue(left.indices.all { left[it] == off[it] })
        val monoIn = tones(rate, 1, rate, 1000.0 to 0.4)
        val monoOut = run(stage(on.copy(widthPercent = 200), channels = 1), monoIn, channels = 1)
        assertTrue(monoIn.indices.all { monoIn[it] == monoOut[it] })
    }

    @Test
    fun aSilentChannelStaysSilentWithoutTheWidthStage() {
        val frames = rate * 3
        val input = DoubleArray(frames * 2)
        for (i in 0 until frames) input[i * 2] = 0.4 * sin(2 * PI * 80.0 * i / rate) + 0.1 * sin(2 * PI * 3000.0 * i / rate)
        val p = JhankarPresets.WEDDING_DJ.parameters.copy(engine = true, widthPercent = 100, monoBelowHz = 0)
        val out = run(stage(p), input)
        var leak = 0.0
        for (i in 0 until frames) leak = max(leak, abs(out[i * 2 + 1]))
        DspMeasure.record("Jhankar engine, Wedding DJ Tower without width: leakage into a silent right channel", "peak ${DspMeasure.f(leak, 6)}", "0", leak == 0.0)
        assertEquals(0.0, leak, 0.0)
    }

    @Test
    fun outputGainIsExactAndNeverSteps() {
        val p = on.copy(outputGainDb = -6f)
        val input = tones(rate * 2, 2, rate, 1000.0 to 0.5)
        val out = run(stage(p), input)
        assertEquals(0.5012, level(out, 2, 0, 1000.0, rate, rate * 2) / 0.5 * 0.5 / 0.5 * 1.0 / 1.0 * 1.0, 0.002)
        // Changed while playing: no step in the sound.
        val s = stage(on)
        val first = run(s, tones(rate, 2, rate, 200.0 to 0.5))
        s.set(on.copy(outputGainDb = -12f))
        val second = run(s, tones(rate, 2, rate, 200.0 to 0.5))
        val joined = first + second
        val step = largestStep(joined, 2, 0, joined.size / 2)
        val own = largestStep(tones(rate * 2, 2, rate, 200.0 to 0.5), 2, 0, rate * 2)
        DspMeasure.record("Jhankar output gain 0 → -12 dB mid-music: largest step ÷ music's own largest step", DspMeasure.f(step / own, 3) + "×", "≤ 1.05×", step / own <= 1.05)
        assertTrue(step / own <= 1.05)
    }

    @Test
    fun theEngineAndEveryControlChangeWithoutAStep() {
        val tone = tones(rate * 2, 2, rate, 200.0 to 0.4)
        val strong = JhankarPresets.DEEP_BASS.parameters.copy(engine = true)
        val steady = run(stage(strong), tone)
        // A click would be a jump several times larger than the slope of the sound itself; the fade and the
        // compressor's attack only move the level, so the largest step stays close to the larger of the two sounds' own.
        val own = max(largestStep(steady, 2, rate / 2, rate * 2), largestStep(tone, 2, rate / 2, rate * 2))

        val s = stage(strong.copy(engine = false))
        val a = run(s, tone.copyOfRange(0, tone.size / 2))
        s.set(strong)
        val b = run(s, tone.copyOfRange(tone.size / 2, tone.size))
        val joined = a + b
        val ratio = largestStep(joined, 2, 0, joined.size / 2) / own
        DspMeasure.record("Jhankar engine switched on mid-music (Deep Bass Jhankar): largest step ÷ the larger of the dry and processed sound's own largest step", DspMeasure.f(ratio, 3) + "×", "≤ 1.25×", ratio <= 1.25)
        assertTrue("on step $ratio", ratio <= 1.25)

        val t = stage(on.copy(autoHeadroom = false))
        val c = run(t, tone.copyOfRange(0, tone.size / 2))
        t.set(on.copy(autoHeadroom = false, bands = withGain(5, 12f, freq = 200f)))
        val d = run(t, tone.copyOfRange(tone.size / 2, tone.size))
        val moved = c + d
        val finalOwn = largestStep(run(stage(on.copy(autoHeadroom = false, bands = withGain(5, 12f, freq = 200f))), tone), 2, rate / 2, rate * 2)
        val ratio2 = largestStep(moved, 2, 0, moved.size / 2) / finalOwn
        DspMeasure.record("Jhankar EQ: +12 dB band set at 200 Hz mid-music: largest step ÷ final sound's own largest step", DspMeasure.f(ratio2, 3) + "×", "≤ 1.1×", ratio2 <= 1.1)
        assertTrue("eq step $ratio2", ratio2 <= 1.1)
    }

    @Test
    fun streamsOfAnyFormatStayFiniteAndMonoAndManyChannelsWork() {
        val random = Random(4)
        val p = JhankarPresets.WEDDING_DJ.parameters.copy(engine = true)
        for ((sampleRate, channels) in listOf(22_050 to 2, 44_100 to 2, 96_000 to 2, 48_000 to 1, 48_000 to 6)) {
            val input = DoubleArray(sampleRate * 2 * channels) { random.nextGaussian() * 0.3 }
            val out = run(stage(p, sampleRate, channels), input, channels)
            assertFalse("$sampleRate Hz x $channels", out.any { it.isNaN() || it.isInfinite() })
            assertTrue(out.maxOf { abs(it) } < 12.0)
        }
        // A seek or a format change clears every tail.
        val s = stage(p)
        run(s, DoubleArray(rate * 2) { random.nextGaussian() * 0.3 })
        s.resetState()
        val silence = run(s, DoubleArray(rate))
        assertTrue(silence.all { it == 0.0 })
    }

    @Test
    fun theTenPresetsAreRightAndNoneIsExcessive() {
        val names = JhankarPresets.BUILT_IN.map { it.name }
        assertEquals(
            listOf(
                "Jhankar Original", "Hindi 90s Remastered", "Bollywood Live Stage", "Dholak & Tabla Punch", "Crystal Clear Vocals",
                "Deep Bass Jhankar", "Wedding DJ Tower", "Studio Master", "Balanced Hi-Fi", "Custom Professional",
            ),
            names,
        )
        var worstBass = -100.0
        var worstTreble = -100.0
        var worstMud = -100.0
        for (preset in JhankarPresets.BUILT_IN + OutputProfile.entries.map { JhankarPresets.JHANKAR_ORIGINAL.copy(parameters = JhankarPresets.defaultFor(it)) }) {
            val p = preset.parameters
            for (hz in doubleArrayOf(31.5, 40.0, 63.0, 80.0, 100.0)) worstBass = max(worstBass, JhankarCurve.responseDb(p, hz))
            for (hz in doubleArrayOf(3000.0, 4000.0, 5000.0, 6000.0, 8000.0, 12000.0)) worstTreble = max(worstTreble, JhankarCurve.responseDb(p, hz))
            for (hz in doubleArrayOf(250.0, 315.0, 400.0)) worstMud = max(worstMud, JhankarCurve.responseDb(p, hz))
        }
        DspMeasure.record("Jhankar presets (10 + 4 output profiles): strongest bass (31-100 Hz) / treble (3-12 kHz) / mud region (250-400 Hz)", DspMeasure.f(worstBass) + " / " + DspMeasure.f(worstTreble) + " / " + DspMeasure.f(worstMud) + " dB", "≤ 8 / ≤ 5.5 / ≤ 0.5 dB", worstBass <= 8 && worstTreble <= 5.5 && worstMud <= 0.5)
        assertTrue("bass $worstBass", worstBass <= 8.0)
        assertTrue("treble $worstTreble", worstTreble <= 5.5)
        assertTrue("mud $worstMud", worstMud <= 0.5)
    }

    @Test
    fun settingsAreClampedStoredAndDamagedLinesAreRefused() {
        val mine = JhankarPresets.WEDDING_DJ.parameters.copy(engine = true, outputGainDb = -2.5f, monoBelowHz = 150)
        assertEquals(mine, JhankarParameters.decode(mine.encode()))
        assertNull(JhankarParameters.decode(null))
        assertNull(JhankarParameters.decode("J1;1;80"))
        assertNull(JhankarParameters.decode(mine.encode().replace("J1", "J9")))
        assertNull(JhankarParameters.decode(mine.encode().replace(";80;", ";abc;").replace(";85;", ";abc;")))
        val wild = mine.encode().split(';').toMutableList().also { it[2] = "999"; it[4] = "99"; it[19] = "-99"; it[14] = "9000"; it[15] = "5" }
        val clamped = JhankarParameters.decode(wild.joinToString(";"))!!
        assertEquals(100, clamped.intensity)
        assertEquals(JhankarTone.MAX_PREAMP_DB, clamped.preampDb, 0f)
        assertEquals(JhankarTone.MIN_OUTPUT_DB, clamped.outputGainDb, 0f)
        assertEquals(JhankarTone.MAX_WIDTH, clamped.widthPercent)
        assertEquals(JhankarTone.MIN_MONO_HZ, clamped.monoBelowHz)
        assertEquals(JhankarTone.MAX_Q, JhankarParameters.clampQ(99f), 0f)
        assertEquals(JhankarTone.DEFAULT_Q, JhankarParameters.clampQ(Float.NaN), 0f)
        assertEquals(1000f, JhankarParameters.clampFreq(Float.NaN), 0f)
        assertEquals(JhankarTone.MAX_FREQ_HZ, JhankarParameters.clampFreq(99999f), 0f)
        assertEquals(12f, mine.withBand(3, JhankarBand(250f, 99f, 1f)).bands[3].gainDb, 0f)
        assertTrue(mine.sameSoundAs(mine.copy(engine = false)))
        assertFalse(mine.sameSoundAs(mine.copy(intensity = 10)))
        assertEquals(0, JhankarParameters().withFlatEq().bands.count { it.gainDb != 0f })
    }

    @Test
    fun userPresetsSaveRenameDeleteAndComeBackFromText() {
        var list = emptyList<com.vortexx.player.audio.dsp.JhankarPreset>()
        val sound = JhankarPresets.DEEP_BASS.parameters.copy(engine = true)
        var edit = JhankarPresets.save(list, "  My Tower  ", sound)
        assertEquals(PresetResult.Saved, edit.result)
        list = edit.presets
        assertEquals("My Tower", list.single().name)
        assertFalse(list.single().parameters.engine)
        assertEquals(PresetResult.Overwritten, JhankarPresets.save(list, "my tower", sound.copy(intensity = 40)).result)
        assertEquals(PresetResult.BadName, JhankarPresets.save(list, "Studio Master", sound).result)
        assertEquals(PresetResult.BadName, JhankarPresets.save(list, "   ", sound).result)
        edit = JhankarPresets.rename(list, "My Tower", "Tower Night")
        assertEquals(PresetResult.Renamed, edit.result)
        list = edit.presets
        assertEquals(PresetResult.NotFound, JhankarPresets.rename(list, "Nope", "x").result)
        val back = JhankarPresets.decodeUser(JhankarPresets.encodeUser(list))
        assertEquals(list, back)
        for (i in 1 until JhankarPresets.MAX_USER_PRESETS) list = JhankarPresets.save(list, "P$i", sound).presets
        assertEquals(JhankarPresets.MAX_USER_PRESETS, list.size)
        assertEquals(PresetResult.Full, JhankarPresets.save(list, "One too many", sound).result)
        assertEquals(PresetResult.Deleted, JhankarPresets.delete(list, "p1").result)
        assertNotNull(JhankarPresets.matching(JhankarPresets.WEDDING_DJ.parameters, list))
    }

    @Test
    fun theSpectrumAnalyzerReadsRealLevels() {
        val analyzer = SpectrumAnalyzer()
        for (sampleRate in intArrayOf(44_100, 48_000)) {
            val bus = SpectrumBus()
            bus.sampleRate = sampleRate
            val frames = 4096
            val buffer = FloatArray(frames * 2)
            for (i in 0 until frames) {
                val v = (0.5 * sin(2 * PI * 1000.0 * i / sampleRate)).toFloat() // -6 dBFS
                buffer[i * 2] = v
                buffer[i * 2 + 1] = v
            }
            bus.write(buffer, frames, 2)
            val out = FloatArray(analyzer.bands)
            analyzer.analyze(bus, out)
            var top = 0
            for (i in out.indices) if (out[i] > out[top]) top = i
            val centre = analyzer.centreHz(top)
            DspMeasure.record("Spectrum analyzer at $sampleRate Hz: 1 kHz tone at -6 dBFS, loudest band centre / level", DspMeasure.f(centre, 0) + " Hz / " + DspMeasure.f(out[top].toDouble()) + " dBFS", "1 kHz band, -6 ± 1.5 dBFS", abs(db(centre / 1000.0)) < 1.0 && abs(out[top] + 6.0) <= 1.5)
            assertTrue("centre $centre", centre in 850.0..1180.0)
            assertEquals(-6.0, out[top].toDouble(), 1.5)
            // Far from the tone the bands are at the floor.
            assertTrue(out[0] < -60f && out[analyzer.bands - 1] < -60f)
        }
        val silent = FloatArray(analyzer.bands)
        val bus = SpectrumBus()
        analyzer.analyze(bus, silent)
        assertTrue(silent.all { it == SpectrumAnalyzer.FLOOR_DB })
        // Two tones show as two peaks at their own levels.
        val two = FloatArray(4096 * 2)
        for (i in 0 until 4096) {
            val v = (0.25 * sin(2 * PI * 100.0 * i / 48_000) + 0.1 * sin(2 * PI * 8000.0 * i / 48_000)).toFloat()
            two[i * 2] = v
            two[i * 2 + 1] = v
        }
        bus.write(two, 4096, 2)
        analyzer.analyze(bus, silent)
        val low = silent.indices.maxByOrNull { if (analyzer.centreHz(it) < 400) silent[it] else -200f }!!
        val high = silent.indices.maxByOrNull { if (analyzer.centreHz(it) > 3000) silent[it] else -200f }!!
        assertEquals(-12.0, silent[low].toDouble(), 1.5)
        assertEquals(-20.0, silent[high].toDouble(), 1.5)
    }

    @Test
    fun theMetersShowWhatTheEngineIsDoing() {
        val meters = JhankarMeters()
        val p = on.copy(dynamicBass = 100, compression = 100, transient = 100)
        val s = stage(p, meters = meters)
        run(s, tones(rate * 4, 2, rate, 1000.0 to 0.3, 60.0 to 0.01))
        val reading = JhankarReading()
        meters.drain(reading)
        assertTrue("bass ${reading.bassBoostDb}", reading.bassBoostDb > 3f)
        assertTrue("compression ${reading.compressionDb}", reading.compressionDb > 1f)
        meters.drain(reading)
        assertEquals(0f, reading.bassBoostDb, 0f)
    }

    @Test
    fun beforeAndAfterOnAHindiStyleMix() {
        // Dhol thump (80 Hz), tabla slap (3 kHz), a voice-like band (700-2500 Hz noise) and hi-hat noise, in stereo.
        val random = Random(21)
        val frames = rate * 8
        val input = DoubleArray(frames * 2)
        var voiceL = 0.0
        var voiceR = 0.0
        for (i in 0 until frames) {
            val t = i.toDouble() / rate
            val beat = (t % 0.5)
            val dhol = 0.35 * exp(-beat / 0.09) * sin(2 * PI * 80.0 * t) * (1 + 0.3 * sin(2 * PI * 160.0 * t))
            val tabla = 0.2 * exp(-((t + 0.25) % 0.5) / 0.012) * sin(2 * PI * 3000.0 * t)
            voiceL = 0.97 * voiceL + 0.03 * random.nextGaussian()
            voiceR = 0.97 * voiceR + 0.03 * random.nextGaussian()
            val hat = 0.04 * exp(-((t + 0.125) % 0.25) / 0.02) * random.nextGaussian()
            input[i * 2] = dhol + tabla + 0.9 * voiceL + hat
            input[i * 2 + 1] = dhol + tabla + 0.9 * voiceR + hat
        }
        val p = JhankarPresets.JHANKAR_ORIGINAL.parameters.copy(engine = true)
        val out = run(stage(p), input)

        fun bandEnergy(buffer: DoubleArray, low: Double, high: Double): Double {
            // Energy through a 4th-order-ish band: two second-order sections each way, evaluated by a DFT on a coarse grid.
            var total = 0.0
            var hz = low
            while (hz < high) {
                val a = level(buffer, 2, 0, hz, rate, frames)
                total += a * a
                hz *= 1.1
            }
            return total
        }
        val bassGain = 10 * log10(bandEnergy(out, 40.0, 120.0) / bandEnergy(input, 40.0, 120.0))
        val vocalGain = 10 * log10(bandEnergy(out, 2000.0, 4500.0) / bandEnergy(input, 2000.0, 4500.0))
        val airGain = 10 * log10(bandEnergy(out, 9000.0, 16000.0) / bandEnergy(input, 9000.0, 16000.0))
        val rmsIn = sqrt(input.sumOf { it * it } / input.size)
        val rmsOut = sqrt(out.sumOf { it * it } / out.size)
        val crestIn = db(peak(input, 2, 0, 0, frames) / rmsIn)
        val crestOut = db(peak(out, 2, 0, 0, frames) / rmsOut)
        DspMeasure.record("Before/after, Jhankar Original on a dhol + tabla + vocal mix: energy 40-120 Hz / 2-4.5 kHz / 9-16 kHz", DspMeasure.f(bassGain) + " / " + DspMeasure.f(vocalGain) + " / " + DspMeasure.f(airGain) + " dB", "bass > +1, presence > +1, air > +0.5 dB", bassGain > 1 && vocalGain > 1 && airGain > 0.5)
        DspMeasure.record("Before/after, same mix: RMS change / crest factor before → after", DspMeasure.f(db(rmsOut / rmsIn)) + " dB / " + DspMeasure.f(crestIn) + " → " + DspMeasure.f(crestOut) + " dB", "report", true)
        assertTrue("bass $bassGain", bassGain > 1.0)
        assertTrue("presence $vocalGain", vocalGain > 1.0)
        assertTrue("air $airGain", airGain > 0.5)
        assertTrue("the preset must not make the music quieter: ${db(rmsOut / rmsIn)} dB", db(rmsOut / rmsIn) > -1.5)
        // The improvement is tonal, not only loudness: with the output matched to the input level the bass is still up.
        val matched = bassGain - db(rmsOut / rmsIn)
        DspMeasure.record("Before/after, loudness-matched: bass energy change with the output level matched to the input", DspMeasure.f(matched - 0.0) + " dB (bass band minus overall level change)", "> +1.5 dB", matched > 1.5)
        assertTrue("matched $matched", matched > 1.5)
    }
}
