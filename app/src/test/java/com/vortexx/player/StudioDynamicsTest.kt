package com.vortexx.player

import com.vortexx.player.audio.dsp.DspChain
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.LookaheadLimiter
import com.vortexx.player.audio.dsp.ReverbType
import com.vortexx.player.audio.dsp.RoomReverb
import com.vortexx.player.audio.dsp.StudioCompressor
import com.vortexx.player.audio.dsp.StudioCurve
import com.vortexx.player.audio.dsp.StudioMeters
import com.vortexx.player.audio.dsp.StudioReading
import com.vortexx.player.audio.dsp.StudioTone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The Dynamics tab's compressor, balance and room, measured on real sample buffers: that each control does
 * what its label says, that the transfer curve drawn on screen is the rule the audio thread follows,
 * and that nothing changes while a stage is at its neutral setting.
 */
class StudioDynamicsTest {

    private val rate = 48_000

    private fun db(ratio: Double) = 20 * log10(ratio)

    private fun sine(freq: Double, frames: Int, amplitude: Double, channels: Int = 2): FloatArray {
        val out = FloatArray(frames * channels)
        for (i in 0 until frames) {
            val v = (amplitude * sin(2 * PI * freq * i / rate)).toFloat()
            for (c in 0 until channels) out[i * channels + c] = v
        }
        return out
    }

    private fun run(chain: DspChain, input: FloatArray, channels: Int, block: Int = 480): FloatArray {
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

    private fun chain(p: DspParameters, channels: Int = 2): DspChain {
        val c = DspChain()
        c.configure(rate, channels)
        c.apply(p)
        return c
    }

    private fun peak(buffer: FloatArray, fromFrame: Int, toFrame: Int, channels: Int, channel: Int): Double {
        var best = 0.0
        for (i in fromFrame until toFrame) best = maxOf(best, abs(buffer[i * channels + channel].toDouble()))
        return best
    }

    private fun rms(buffer: FloatArray, fromFrame: Int, toFrame: Int, channels: Int, channel: Int): Double {
        var sum = 0.0
        for (i in fromFrame until toFrame) {
            val v = buffer[i * channels + channel].toDouble()
            sum += v * v
        }
        return sqrt(sum / (toFrame - fromFrame))
    }

    private val compressing = DspParameters(studio = true, thresholdDb = -18f, ratio = 4f, attackMs = 5, releaseMs = 50)

    @Test
    fun theCurveOnScreenIsTheRuleOfTheAudioThread() {
        assertEquals(-30.0, StudioCurve.outputDb(-30.0, -18.0, 4.0), 0.0)
        assertEquals(-18.0, StudioCurve.outputDb(-18.0, -18.0, 4.0), 0.0)
        assertEquals(-15.0, StudioCurve.outputDb(-6.0, -18.0, 4.0), 1e-12)
        assertEquals(0.0, StudioCurve.outputDb(0.0, -12.0, 1.0), 0.0) // ratio 1:1 is no compression
        assertEquals(-12.0, StudioCurve.outputDb(0.0, -12.0, 1e9), 1e-6) // a very high ratio is a wall

        // Steady 1 kHz tone at -6 dBFS: the loudest output sample follows the rule.
        val input = sine(1000.0, rate * 2, 0.5)
        val output = run(chain(compressing), input, 2)
        val measured = db(peak(output, rate, rate * 2, 2, 0))
        val expected = StudioCurve.outputDb(db(0.5), -18.0, 4.0)
        DspMeasure.record(
            "Studio compressor: 1 kHz at -6 dBFS, threshold -18 dB, ratio 4:1 (rule: -15 dBFS)",
            DspMeasure.f(measured) + " dBFS", "-15 ± 1 dBFS", abs(measured - expected) < 1.0,
        )
        assertEquals(expected, measured, 1.0)
        // Both channels get the same turn-down (the level is linked).
        assertEquals(peak(output, rate, rate * 2, 2, 0), peak(output, rate, rate * 2, 2, 1), 1e-9)
    }

    @Test
    fun belowTheThresholdOrAtRatioOneNothingChanges() {
        val quiet = sine(1000.0, rate, 0.05) // -26 dBFS, under -18
        assertTrue(quiet.contentEquals(run(chain(compressing), quiet, 2)))
        val loud = sine(1000.0, rate, 0.8)
        val ratioOne = compressing.copy(ratio = 1f)
        val out = run(chain(ratioOne), loud, 2)
        DspMeasure.record("Studio compressor at ratio 1:1: changed samples", "${loud.indices.count { loud[it] != out[it] }} of ${loud.size}", "0", loud.contentEquals(out))
        assertTrue(loud.contentEquals(out))
    }

    @Test
    fun anyOtherSettingChangesNothingWhileTheCompressorIsOff() {
        val loud = sine(1000.0, rate, 0.8)
        val off = DspParameters(studio = false, thresholdDb = -36f, ratio = 10f, attackMs = 1, releaseMs = 20)
        assertTrue(off.isFlat)
        assertTrue(loud.contentEquals(run(chain(off), loud, 2)))
        assertTrue(DspParameters().isFlat)
        assertFalse(DspParameters(studio = true).isFlat)
        assertFalse(DspParameters(balance = 10).isFlat)
        assertFalse(DspParameters(reverb = ReverbType.Studio).isFlat)
        // A room with nothing heard is still a room: the type matters even at 0 %.
        assertTrue(DspParameters(reverbMixPercent = 50).isFlat)
    }

    @Test
    fun attackAndReleaseAreTheTimesToCover63PercentOfAStep() {
        val compressor = StudioCompressor()
        compressor.configure(rate)
        val attackMs = 20
        val releaseMs = 100
        compressor.set(-18.0, 4.0, attackMs, releaseMs)
        val frame = DoubleArray(2)
        // A constant level of 0.5 (-6.02 dBFS) is 11.98 dB over the threshold; at 4:1 the rule asks for 3/4 of that.
        val wantedDb = (db(0.5) + 18.0) * 0.75
        var frames = 0
        var reached = -1
        while (frames < rate) {
            frame[0] = 0.5; frame[1] = 0.5
            compressor.process(frame, 2, 1.0)
            frames++
            val reductionDb = -db(compressor.gain)
            if (reached < 0 && reductionDb >= wantedDb * (1 - 1 / Math.E)) reached = frames
        }
        val attackMeasured = reached * 1000.0 / rate
        DspMeasure.record("Studio compressor attack 20 ms: time to 63 % of the turn-down", DspMeasure.f(attackMeasured) + " ms", "20 ± 1 ms", abs(attackMeasured - attackMs) <= 1.0)
        assertEquals(attackMs.toDouble(), attackMeasured, 1.0)
        assertEquals(wantedDb, -db(compressor.gain), 0.01)

        // Silence: the level falls back with the release time constant (63 % of it in that time), so the sound
        // is untouched again when the level has fallen to the threshold: release x ln(level / threshold).
        frames = 0
        var untouched = -1
        while (frames < rate) {
            frame[0] = 0.0; frame[1] = 0.0
            compressor.process(frame, 2, 1.0)
            frames++
            if (untouched < 0 && -db(compressor.gain) <= 0.05) untouched = frames
        }
        val releaseMeasured = untouched * 1000.0 / rate
        val releaseExpected = releaseMs * Math.log(0.5 / Math.pow(10.0, -18.0 / 20.0))
        DspMeasure.record(
            "Studio compressor release 100 ms: time until the sound is untouched again (rule: 138 ms)",
            DspMeasure.f(releaseMeasured) + " ms", "138 ± 6 ms", abs(releaseMeasured - releaseExpected) <= 6.0,
        )
        assertEquals(releaseExpected, releaseMeasured, 6.0)
        for (i in 0 until rate) { frame[0] = 0.0; frame[1] = 0.0; compressor.process(frame, 2, 1.0) }
        assertEquals(1.0, compressor.gain, 0.0) // back to exactly untouched
    }

    @Test
    fun switchingTheCompressorOnAndOffNeverStepsTheSound() {
        val c = DspChain()
        c.configure(rate, 2)
        c.apply(compressing.copy(studio = false))
        val input = sine(200.0, rate * 2, 0.8)
        val half = input.size / 2
        val a = run(c, input.copyOfRange(0, half), 2)
        c.apply(compressing)
        val b = run(c, input.copyOfRange(half, input.size), 2)
        val joined = a + b
        var largestStep = 0.0
        var largestOwnStep = 0.0
        for (i in 1 until joined.size / 2) {
            largestStep = maxOf(largestStep, abs(joined[i * 2].toDouble() - joined[(i - 1) * 2]))
            largestOwnStep = maxOf(largestOwnStep, abs(input[i * 2].toDouble() - input[(i - 1) * 2]))
        }
        val ratio = largestStep / largestOwnStep
        DspMeasure.record("Studio compressor switched on mid-music: largest step ÷ music's own largest step", DspMeasure.f(ratio, 3) + "×", "≤ 1.05×", ratio <= 1.05)
        assertTrue("step $ratio", ratio <= 1.05)
    }

    @Test
    fun balanceTurnsDownOnlyTheOtherSide() {
        val input = sine(1000.0, rate, 0.5)
        val centre = run(chain(DspParameters(balance = 0)), input, 2)
        assertTrue(input.contentEquals(centre))

        val right = run(chain(DspParameters(balance = 100)), input, 2)
        assertEquals(0.0, peak(right, 0, rate, 2, 0), 0.0)
        assertEquals(peak(input, 0, rate, 2, 1), peak(right, 0, rate, 2, 1), 0.0)

        val left = run(chain(DspParameters(balance = -100)), input, 2)
        assertEquals(0.0, peak(left, 0, rate, 2, 1), 0.0)
        assertEquals(peak(input, 0, rate, 2, 0), peak(left, 0, rate, 2, 0), 0.0)

        val half = run(chain(DspParameters(balance = 50)), input, 2)
        val measured = peak(half, 0, rate, 2, 0) / peak(input, 0, rate, 2, 0)
        DspMeasure.record("Balance +50: gain of the left channel (right untouched)", DspMeasure.f(measured, 4), "0.5000 ± 0.001", abs(measured - 0.5) < 0.001)
        assertEquals(0.5, measured, 0.001)
        assertEquals(peak(input, 0, rate, 2, 1), peak(half, 0, rate, 2, 1), 0.0)

        // A mono stream has no sides: balance does nothing.
        val mono = sine(1000.0, rate, 0.5, channels = 1)
        assertTrue(mono.contentEquals(run(chain(DspParameters(balance = 100), channels = 1), mono, 1)))
    }

    @Test
    fun theRoomIsAsLoudAsTheInputAtFullMixAndSilentAtNone() {
        val random = Random(7)
        val frames = rate * 8
        val noise = FloatArray(frames * 2) { (random.nextGaussian() * 0.1).toFloat() }
        assertTrue(noise.contentEquals(run(chain(DspParameters(reverb = ReverbType.Studio, reverbMixPercent = 0)), noise, 2)))
        assertTrue(noise.contentEquals(run(chain(DspParameters(reverb = ReverbType.Off, reverbMixPercent = 100)), noise, 2)))
        for (type in listOf(ReverbType.SmallRoom, ReverbType.Studio, ReverbType.Hall)) {
            val out = run(chain(DspParameters(reverb = type, reverbMixPercent = 100)), noise, 2)
            val level = db(rms(out, rate, frames, 2, 0) / rms(noise, rate, frames, 2, 0))
            DspMeasure.record("Room ${type.label} at 100 % mix: level of noise against the input", DspMeasure.f(level) + " dB", "0 ± 1.5 dB", abs(level) < 1.5)
            assertEquals(0.0, level, 1.5)
        }
    }

    @Test
    fun theRoomsDifferInHowLongTheyRing() {
        val tails = HashMap<ReverbType, Double>()
        for (type in listOf(ReverbType.SmallRoom, ReverbType.Studio, ReverbType.Hall)) {
            val room = RoomReverb()
            room.configure(rate, 2)
            room.setType(type)
            val frame = DoubleArray(2)
            val wet = DoubleArray(2)
            val length = rate * 6
            val h = DoubleArray(length)
            for (i in 0 until length) {
                frame[0] = if (i == 0) 1.0 else 0.0
                frame[1] = frame[0]
                room.process(frame, wet)
                h[i] = wet[0]
            }
            // Schroeder backward integration; the time from -5 to -25 dB, times three, is the decay to -60 dB.
            val edc = DoubleArray(length)
            var sum = 0.0
            for (i in length - 1 downTo 0) { sum += h[i] * h[i]; edc[i] = sum }
            fun timeAt(level: Double): Double {
                for (i in 0 until length) if (10 * log10(edc[i] / edc[0]) <= level) return i.toDouble() / rate
                return Double.NaN
            }
            val rt60 = (timeAt(-25.0) - timeAt(-5.0)) * 3
            tails[type] = rt60
            DspMeasure.record("Room ${type.label}: time for the tail to fall 60 dB", DspMeasure.f(rt60) + " s", "0.2…2.5 s", rt60 in 0.2..2.5)
            assertTrue("${type.label}: $rt60 s", rt60 in 0.2..2.5)
        }
        assertTrue(tails[ReverbType.SmallRoom]!! < tails[ReverbType.Studio]!!)
        assertTrue(tails[ReverbType.Studio]!! < tails[ReverbType.Hall]!!)
    }

    @Test
    fun theRoomWorksAtOtherSampleRatesAndForMonoAndLeavesManyChannelsAlone() {
        val random = Random(3)
        for (sampleRate in intArrayOf(22_050, 44_100, 96_000)) {
            val c = DspChain()
            c.configure(sampleRate, 2)
            c.apply(DspParameters(reverb = ReverbType.Hall, reverbMixPercent = 100))
            val noise = FloatArray(sampleRate * 2 * 2) { (random.nextGaussian() * 0.1).toFloat() }
            val chunk = noise.copyOf()
            c.process(chunk, noise.size / 2)
            assertFalse(chunk.any { it.isNaN() })
            assertNotEquals(0.0, rms(chunk, sampleRate, sampleRate * 2, 2, 0), 0.0)
        }
        val mono = FloatArray(rate) { (random.nextGaussian() * 0.1).toFloat() }
        val monoOut = run(chain(DspParameters(reverb = ReverbType.Studio, reverbMixPercent = 100), channels = 1), mono, 1)
        assertFalse(mono.contentEquals(monoOut))
        val six = FloatArray(rate * 6) { (random.nextGaussian() * 0.1).toFloat() }
        val sixOut = run(chain(DspParameters(reverb = ReverbType.Studio, reverbMixPercent = 100), channels = 6), six, 6)
        assertTrue(six.contentEquals(sixOut))
    }

    @Test
    fun aSeekOrFormatChangeClearsTheTail() {
        val random = Random(5)
        val c = chain(DspParameters(reverb = ReverbType.Hall, reverbMixPercent = 100))
        val noise = FloatArray(rate * 2) { (random.nextGaussian() * 0.3).toFloat() }
        run(c, noise, 2)
        c.resetState()
        val silence = FloatArray(rate * 2)
        val out = run(c, silence, 2)
        assertTrue(out.all { it == 0f })
    }

    @Test
    fun switchingTheRoomOffFadesItInsteadOfCuttingIt() {
        fun afterNoise(offAtEnd: Boolean): FloatArray {
            val random = Random(9)
            val c = DspChain()
            c.configure(rate, 2)
            c.apply(DspParameters(reverb = ReverbType.Hall, reverbMixPercent = 100))
            run(c, FloatArray(rate * 2) { (random.nextGaussian() * 0.2).toFloat() }, 2)
            if (offAtEnd) c.apply(DspParameters(reverb = ReverbType.Off, reverbMixPercent = 100))
            return run(c, FloatArray(rate * 2), 2)
        }
        val fading = afterNoise(offAtEnd = true)
        val ringing = afterNoise(offAtEnd = false)
        // The tail is gone after the fade (20 ms), and the room that is left on keeps ringing.
        assertEquals(0.0, peak(fading, rate / 10, rate, 2, 0), 0.0)
        assertTrue(peak(ringing, rate / 10, rate, 2, 0) > 0.01)
        // The fade is a fade: no step in it is larger than the tail's own largest step.
        var fadeStep = 0.0
        var ownStep = 0.0
        for (i in 1 until rate / 20) {
            fadeStep = maxOf(fadeStep, abs(fading[i * 2].toDouble() - fading[(i - 1) * 2]))
            ownStep = maxOf(ownStep, abs(ringing[i * 2].toDouble() - ringing[(i - 1) * 2]))
        }
        DspMeasure.record("Room switched off: largest step of the fade ÷ the tail's own largest step", DspMeasure.f(fadeStep / ownStep, 3) + "×", "≤ 1.05×", fadeStep / ownStep <= 1.05)
        assertTrue("step ${fadeStep / ownStep}", fadeStep / ownStep <= 1.05)
    }

    @Test
    fun everythingAtItsExtremeStillKeepsTheCeilingAndNeverBlowsUp() {
        val random = Random(11)
        val params = DspParameters(
            preampDb = 6f,
            bassDb = 12f,
            limiter = true,
            punchDb = 6f,
            studio = true,
            thresholdDb = -6f,
            ratio = 2f,
            attackMs = 1,
            releaseMs = 20,
            widthPercent = 200,
            balance = 30,
            reverb = ReverbType.Hall,
            reverbMixPercent = 100,
        )
        val c = chain(params)
        val loud = FloatArray(rate * 20 * 2) { (random.nextGaussian() * 0.6).toFloat().coerceIn(-1f, 1f) }
        val out = run(c, loud, 2)
        var highest = 0.0
        for (v in out) {
            assertFalse(v.isNaN() || v.isInfinite())
            highest = maxOf(highest, abs(v.toDouble()))
        }
        val dbfs = db(highest)
        DspMeasure.record(
            "Everything at maximum incl. studio compressor, balance and room, 20 s of loud noise: highest output sample",
            DspMeasure.f(dbfs, 3) + " dBFS", "≤ -0.5 dBFS", dbfs <= LookaheadLimiter.CEILING_DB + 1e-6,
        )
        assertTrue("highest sample $dbfs dBFS", dbfs <= LookaheadLimiter.CEILING_DB + 1e-6)
    }

    @Test
    fun theMetersReportWhatTheCompressorDoes() {
        val meters = StudioMeters()
        val c = DspChain(null, null, meters)
        c.configure(rate, 2)
        c.apply(compressing)
        run(c, sine(1000.0, rate / 2, 0.5), 2) // lets the attack settle
        val reading = StudioReading()
        meters.drain(reading)
        run(c, sine(1000.0, rate / 2, 0.5), 2)
        meters.drain(reading)
        // The highest input sample is the tone's peak; the output is lower, and the gain shows the turn-down.
        assertEquals(0.5, reading.inputPeak.toDouble(), 0.001)
        assertTrue(reading.outputPeak < reading.inputPeak)
        assertTrue(reading.gain < 1f)
        val expectedOut = Math.pow(10.0, StudioCurve.outputDb(db(0.5), -18.0, 4.0) / 20.0)
        DspMeasure.record(
            "Studio meters: input peak / output peak while compressing a -6 dBFS tone (rule: -15 dBFS out)",
            DspMeasure.f(db(reading.inputPeak.toDouble())) + " / " + DspMeasure.f(db(reading.outputPeak.toDouble())) + " dBFS",
            "-6 / -15 ± 1.5 dBFS", abs(db(reading.outputPeak.toDouble()) - db(expectedOut)) < 1.5,
        )
        assertEquals(db(expectedOut), db(reading.outputPeak.toDouble()), 1.5)
        // A second drain starts a new measurement.
        meters.drain(reading)
        assertEquals(0f, reading.inputPeak, 0f)
        assertEquals(1f, reading.gain, 0f)
    }

    @Test
    fun settingsAreClampedAndStoredAndOldLinesStillRead() {
        assertEquals(-36f, DspParameters.clampThreshold(-99f), 0f)
        assertEquals(0f, DspParameters.clampThreshold(5f), 0f)
        assertEquals(1f, DspParameters.clampRatio(0.2f), 0f)
        assertEquals(10f, DspParameters.clampRatio(99f), 0f)
        assertEquals(StudioTone.DEFAULT_RATIO, DspParameters.clampRatio(Float.NaN), 0f)
        assertEquals(1, DspParameters.clampAttack(-4))
        assertEquals(100, DspParameters.clampAttack(900))
        assertEquals(20, DspParameters.clampRelease(1))
        assertEquals(1000, DspParameters.clampRelease(99_999))
        assertEquals(-100, DspParameters.clampBalance(-500))
        assertEquals(100, DspParameters.clampMix(150))

        val mine = DspParameters(
            bassDb = 4f, limiter = true, widthPercent = 120, studio = true, thresholdDb = -24f, ratio = 3.5f,
            attackMs = 8, releaseMs = 220, balance = -30, reverb = ReverbType.SmallRoom, reverbMixPercent = 15,
        )
        assertEquals(mine, DspParameters.decode(mine.encode()))
        assertEquals(17, mine.encode().split(';').size)

        // A line saved by the version before the studio controls reads with those controls off.
        val old = DspParameters.decode("1;-2.0;0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0,0.0;5.0;3.0;1;40;1;110")!!
        assertEquals(-2f, old.preampDb, 0f)
        assertEquals(40, old.compressor)
        assertFalse(old.studio)
        assertEquals(0, old.balance)
        assertEquals(ReverbType.Off, old.reverb)

        // Damaged new lines are refused or clamped, never crash; an unknown room is "off".
        assertEquals(null, DspParameters.decode("2;0;0"))
        val clamped = DspParameters.decode(mine.encode().replace("SmallRoom", "Cave").replace(";-24.0;", ";-99.0;"))!!
        assertEquals(ReverbType.Off, clamped.reverb)
        assertEquals(-36f, clamped.thresholdDb, 0f)

        // The compressor's own numbers do not make a saved sound differ while the compressor is off.
        val a = DspParameters(bassDb = 3f)
        assertTrue(a.sameSoundAs(a.copy(thresholdDb = -30f, ratio = 8f)))
        assertFalse(a.copy(studio = true).sameSoundAs(a.copy(studio = true, ratio = 8f)))
        assertTrue(a.sameSoundAs(a.copy(reverbMixPercent = 80)))
        assertFalse(a.copy(reverb = ReverbType.Hall).sameSoundAs(a.copy(reverb = ReverbType.Hall, reverbMixPercent = 80)))
    }

    @Test
    fun theStudioStagesDoNotAllocateAndFitTheCpuBudget() {
        val counter = AllocationCounter.create()
        if (counter == null) DspMeasure.record("Heap allocated with studio compressor, balance and room on", "not measured (no allocation counter on this JVM)", "< 1024 bytes", true)
        assumeTrue("Per-thread allocation counter not available on this JVM", counter != null)
        val chain = DspChain(null, null, StudioMeters())
        chain.configure(rate, 2)
        val on = DspParameters(
            limiter = true, studio = true, balance = 20, reverb = ReverbType.Studio, reverbMixPercent = 30,
            speakerProtect = true, compressor = 40, widthPercent = 120,
        )
        chain.apply(on)
        val buffer = sine(440.0, 1024, 0.5)
        repeat(500) { chain.process(buffer, 1024, it.toLong()) }
        chain.apply(on.copy(thresholdDb = -24f, ratio = 6f, reverb = ReverbType.Hall, balance = -40))
        val before = counter!!.bytes()
        repeat(5_000) { chain.process(buffer, 1024, it.toLong()) }
        val allocated = (counter.bytes() - before - counter.overheadBytes()).coerceAtLeast(0)
        DspMeasure.record("Heap allocated with studio compressor, balance and room on (5000 buffers, incl. a change)", "$allocated bytes", "< 1024 bytes", allocated < 1024)
        assertTrue("allocated $allocated bytes", allocated < 1024)

        val buffers = 4_000
        val started = System.nanoTime()
        repeat(buffers) { chain.process(buffer, 1024, it.toLong()) }
        val elapsed = System.nanoTime() - started
        val load = elapsed * 100.0 / (buffers.toLong() * 1024 * 1_000_000_000L / rate)
        DspMeasure.record("CI machine: CPU time for 48 kHz stereo with all stages incl. studio compressor and room", DspMeasure.f(load, 3) + " % of real time", "< 25 % (loose; phone figure comes from the Lab)", load < 25.0)
        assertTrue("took ${DspMeasure.f(load)} %", load < 25.0)
    }
}
