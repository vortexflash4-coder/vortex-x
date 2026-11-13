package com.vortexx.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.vortexx.player.audio.dsp.BassTone
import com.vortexx.player.audio.dsp.BiquadCoefficients
import com.vortexx.player.audio.dsp.DspChain
import com.vortexx.player.audio.dsp.DspHost
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.DynamicsMeters
import com.vortexx.player.audio.dsp.DynamicsReading
import com.vortexx.player.audio.dsp.LoadMeter
import com.vortexx.player.audio.dsp.LookaheadLimiter
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.dsp.MultibandCompressor
import com.vortexx.player.audio.dsp.ProcessorFormat
import com.vortexx.player.audio.dsp.SoundPreset
import com.vortexx.player.audio.dsp.SoundPresets
import com.vortexx.player.audio.media3.VortexAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bass, compressor, limiter and width stages, measured on real sample
 * buffers: what the limiter promises (no sample above the ceiling, bass not
 * bent), what the compressor does, and that nothing changes while a stage is
 * at its neutral setting.
 */
@Suppress("DEPRECATION") // AudioProcessor.flush() is deprecated in Media3 1.11 in favour of flush(StreamMetadata).
class DynamicsTest {

    private val rate = 48_000

    private fun sine(freq: Double, frames: Int, amplitude: Double): DoubleArray =
        DoubleArray(frames) { amplitude * sin(2 * PI * freq * it / rate) }

    private fun rms(samples: DoubleArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / (to - from))
    }

    private fun db(ratio: Double): Double = 20 * log10(ratio)

    /** Runs a mono signal through a limiter, one frame at a time. */
    private fun limit(input: DoubleArray): DoubleArray {
        val limiter = LookaheadLimiter()
        limiter.configure(rate, 1)
        val frame = DoubleArray(1)
        return DoubleArray(input.size) {
            frame[0] = input[it]
            limiter.process(frame)
            frame[0]
        }
    }

    /** Harmonics 2…15 relative to the fundamental, over a whole number of cycles at the end of [signal]. */
    private fun distortion(signal: DoubleArray, freq: Double): Double {
        val cycles = (freq * 0.9).toInt()
        val length = (cycles * rate / freq).toInt()
        val from = signal.size - length
        fun level(f: Double): Double {
            var re = 0.0
            var im = 0.0
            for (i in 0 until length) {
                val phase = 2 * PI * f * i / rate
                re += signal[from + i] * cos(phase)
                im -= signal[from + i] * sin(phase)
            }
            return 2 * sqrt(re * re + im * im) / length
        }
        var harmonics = 0.0
        for (k in 2..15) harmonics += level(freq * k).let { it * it }
        return sqrt(harmonics) / level(freq)
    }

    @Test
    fun limiterNeverLetsASampleAboveTheCeiling() {
        // 45 Hz at four times full scale, with bursts of noise on top: far more than any setting can produce.
        var seed = 99L
        val frames = rate * 2
        val limiter = LookaheadLimiter()
        limiter.configure(rate, 2)
        val frame = DoubleArray(2)
        var highest = 0.0
        var lowestGain = 1.0
        var loudestInput = 0.0
        for (i in 0 until frames) {
            seed = seed * 6364136223846793005L + 1442695040888963407L
            val noise = ((seed ushr 33).toInt() / Int.MAX_VALUE.toDouble()) * 2.0 - 1.0
            val burst = if (sin(2 * PI * 2 * i / rate) > 0) 3.0 * noise else 0.0
            frame[0] = 4.0 * sin(2 * PI * 45 * i / rate) + burst
            frame[1] = 2.0 * sin(2 * PI * 70 * i / rate) - burst
            loudestInput = maxOf(loudestInput, abs(frame[0]), abs(frame[1]))
            val gain = limiter.process(frame)
            lowestGain = minOf(lowestGain, gain)
            highest = maxOf(highest, abs(frame[0]), abs(frame[1]))
        }
        DspMeasure.record(
            "Limiter: input up to ${DspMeasure.f(db(loudestInput), 1)} dBFS, highest output sample",
            DspMeasure.f(db(highest), 3) + " dBFS",
            "≤ ${LookaheadLimiter.CEILING_DB} dBFS",
            highest <= LookaheadLimiter.CEILING,
        )
        assertTrue("highest output $highest", highest <= LookaheadLimiter.CEILING)
        assertTrue("the limiter must have worked: lowest gain $lowestGain", lowestGain < 0.3)
    }

    @Test
    fun limiterTurnsBassDownInsteadOfBendingIt() {
        // A steady bass note 12 dB over the ceiling. Clipping squares it off; the limiter must only make it quieter.
        for (freq in doubleArrayOf(40.0, 60.0, 100.0)) {
            val input = sine(freq, rate * 2, LookaheadLimiter.CEILING * 4.0)
            val limited = limit(input)
            val clipped = DoubleArray(input.size) { input[it].coerceIn(-LookaheadLimiter.CEILING, LookaheadLimiter.CEILING) }
            val limiterDistortion = distortion(limited, freq) * 100
            val clipDistortion = distortion(clipped, freq) * 100
            DspMeasure.record(
                "Limiter: ${freq.toInt()} Hz note 12 dB too loud, distortion (plain clipping gives ${DspMeasure.f(clipDistortion, 1)} %)",
                DspMeasure.f(limiterDistortion, 4) + " %",
                "< 0.1 %",
                limiterDistortion < 0.1,
            )
            assertTrue("clipping reference $clipDistortion %", clipDistortion > 20.0)
            assertTrue("limiter distortion $limiterDistortion % at $freq Hz", limiterDistortion < 0.1)
            val level = db(rms(limited, rate, rate * 2) * sqrt(2.0) / LookaheadLimiter.CEILING)
            assertEquals("the note must sit at the ceiling", 0.0, level, 0.05)
        }
    }

    @Test
    fun limiterLeavesMusicBelowTheCeilingUntouched() {
        val input = sine(440.0, rate, 0.5)
        val output = limit(input)
        val delay = (LookaheadLimiter.LOOKAHEAD_SECONDS * rate).toInt()
        var changed = 0
        for (i in delay until input.size) if (output[i] != input[i - delay]) changed++
        DspMeasure.record("Limiter: music below the ceiling, changed samples (5 ms later)", "$changed of ${input.size - delay}", "0", changed == 0)
        assertEquals(0, changed)
        for (i in 0 until delay) assertEquals(0.0, output[i], 0.0)

        // After a moment that was too loud, the gain returns to exactly 1.
        val loudThenQuiet = sine(100.0, rate / 10, 3.0) + sine(440.0, rate * 4, 0.3)
        val recovered = limit(loudThenQuiet)
        var lastChanged = -1
        for (i in delay until recovered.size) if (recovered[i] != loudThenQuiet[i - delay]) lastChanged = i
        val seconds = (lastChanged - rate / 10) / rate.toDouble()
        DspMeasure.record("Limiter: time after a loud moment until samples are exact again", DspMeasure.f(seconds, 2) + " s", "< 3 s", seconds < 3.0)
        assertTrue("still changing after $seconds s", seconds < 3.0)
    }

    @Test
    fun compressorBandsAddBackToAFlatLevel() {
        var worst = 0.0
        for (sampleRate in intArrayOf(44_100, 48_000, 96_000)) {
            for (freq in doubleArrayOf(25.0, 60.0, 120.0, 250.0, 1000.0, 2500.0, 5000.0, 12_000.0)) {
                val compressor = MultibandCompressor()
                compressor.configure(sampleRate, 1)
                for (band in 0 until MultibandCompressor.BANDS) compressor.setBand(band, 0.0, 1.0, 0.0)
                val frames = sampleRate
                val frame = DoubleArray(1)
                var inSquares = 0.0
                var outSquares = 0.0
                for (i in 0 until frames) {
                    val x = 0.25 * sin(2 * PI * freq * i / sampleRate)
                    frame[0] = x
                    compressor.process(frame, 1.0)
                    if (i >= frames / 2) {
                        inSquares += x * x
                        outSquares += frame[0] * frame[0]
                    }
                }
                worst = maxOf(worst, abs(db(sqrt(outSquares / inSquares))))
            }
        }
        DspMeasure.record("Compressor band split: largest level error after adding the 3 bands (24 checks)", DspMeasure.f(worst, 4) + " dB", "≤ 0.05 dB", worst <= 0.05)
        assertTrue("band split error $worst dB", worst <= 0.05)
    }

    private fun compressedLevelDb(freq: Double, amplitude: Double): Double {
        val compressor = MultibandCompressor()
        compressor.configure(rate, 1)
        compressor.setBand(MultibandCompressor.LOW, -20.0, 4.0, 0.0)
        compressor.setBand(MultibandCompressor.MID, 0.0, 1.0, 0.0)
        compressor.setBand(MultibandCompressor.HIGH, 0.0, 1.0, 0.0)
        val frames = rate * 2
        val frame = DoubleArray(1)
        var inSquares = 0.0
        var outSquares = 0.0
        for (i in 0 until frames) {
            val x = amplitude * sin(2 * PI * freq * i / rate)
            frame[0] = x
            compressor.process(frame, 1.0)
            if (i >= rate) {
                inSquares += x * x
                outSquares += frame[0] * frame[0]
            }
        }
        return db(sqrt(outSquares / inSquares))
    }

    @Test
    fun compressorTurnsOnlyTheLoudBandDown() {
        // Bass band: threshold −20 dBFS, ratio 4. A 50 Hz note peaking at −6 dBFS is 14 dB over:
        // the rule gives 10.5 dB down. The level follower sits a little under the peak, so slightly less.
        val loudBass = compressedLevelDb(50.0, 0.5)
        DspMeasure.record("Compressor: 50 Hz at −6 dBFS, threshold −20 dBFS, ratio 4 (rule: −10.5 dB)", DspMeasure.f(loudBass, 2) + " dB", "−10.5 ± 2 dB", abs(loudBass + 10.5) <= 2.0)
        assertEquals(-10.5, loudBass, 2.0)

        val quietBass = compressedLevelDb(50.0, 0.05) // −26 dBFS: under the threshold
        DspMeasure.record("Compressor: 50 Hz at −26 dBFS (under the threshold)", DspMeasure.f(quietBass, 3) + " dB", "0 ± 0.05 dB", abs(quietBass) <= 0.05)
        assertEquals(0.0, quietBass, 0.05)

        val loudMiddle = compressedLevelDb(1000.0, 0.5) // another band: must be left alone
        DspMeasure.record("Compressor: 1 kHz at −6 dBFS while only the bass band is set", DspMeasure.f(loudMiddle, 3) + " dB", "0 ± 0.05 dB", abs(loudMiddle) <= 0.05)
        assertEquals(0.0, loudMiddle, 0.05)

        // The amount control moves threshold, ratio and make-up in the stated direction.
        assertTrue(MultibandCompressor.thresholdDb(MultibandCompressor.LOW, 100.0) < MultibandCompressor.thresholdDb(MultibandCompressor.LOW, 0.0))
        assertTrue(MultibandCompressor.ratio(MultibandCompressor.LOW, 100.0) > MultibandCompressor.ratio(MultibandCompressor.LOW, 0.0))
        assertEquals(9.0, MultibandCompressor.makeUpDb(MultibandCompressor.LOW, 100.0), 1e-9)
    }

    /** Steady-state gain in dB of a chain set to [p] for a quiet tone at [freq]. */
    private fun chainGainDb(freq: Double, p: DspParameters): Double {
        val chain = DspChain()
        chain.configure(rate, 1)
        chain.apply(p)
        val input = FloatArray(rate) { (0.02 * sin(2 * PI * freq * it / rate)).toFloat() }
        val output = input.copyOf()
        chain.process(output, rate)
        var inSquares = 0.0
        var outSquares = 0.0
        for (i in rate / 2 until rate) {
            inSquares += input[i] * input[i]
            outSquares += output[i] * output[i]
        }
        return db(sqrt(outSquares / inSquares))
    }

    @Test
    fun bassShelfKickBandAndSpeakerProtectMeasureAsDesigned() {
        val shelf = BiquadCoefficients().apply { setLowShelf(rate, BassTone.SHELF_HZ, 9.0) }
        var worst = 0.0
        for (freq in doubleArrayOf(30.0, 60.0, 90.0, 200.0, 1000.0)) {
            worst = maxOf(worst, abs(chainGainDb(freq, DspParameters(bassDb = 9f)) - shelf.magnitudeDb(freq, rate)))
        }
        val deep = chainGainDb(30.0, DspParameters(bassDb = 9f))
        val middle = chainGainDb(1000.0, DspParameters(bassDb = 9f))
        DspMeasure.record("Bass boost +9 dB: gain at 30 Hz / at 1 kHz", "${DspMeasure.f(deep, 2)} / ${DspMeasure.f(middle, 2)} dB", "> 8.5 / 0 ± 0.1 dB", deep > 8.5 && abs(middle) <= 0.1)
        DspMeasure.record("Bass boost: measured gain vs designed curve, largest error (5 frequencies)", DspMeasure.f(worst, 3) + " dB", "≤ 0.1 dB", worst <= 0.1)
        assertTrue(deep > 8.5)
        assertEquals(0.0, middle, 0.1)
        assertTrue("shelf error $worst dB", worst <= 0.1)

        val kick = chainGainDb(BassTone.PUNCH_HZ, DspParameters(punchDb = 6f))
        DspMeasure.record("Kick punch +6 dB: gain at ${BassTone.PUNCH_HZ.toInt()} Hz", DspMeasure.f(kick, 3) + " dB", "6 ± 0.1 dB", abs(kick - 6.0) <= 0.1)
        assertEquals(6.0, kick, 0.1)

        val rumble = chainGainDb(30.0, DspParameters(speakerProtect = true))
        val body = chainGainDb(200.0, DspParameters(speakerProtect = true))
        DspMeasure.record("Speaker protect: 30 Hz / 200 Hz", "${DspMeasure.f(rumble, 1)} / ${DspMeasure.f(body, 2)} dB", "< −15 / 0 ± 0.5 dB", rumble < -15.0 && abs(body) <= 0.5)
        assertTrue("30 Hz only $rumble dB down", rumble < -15.0)
        assertEquals(0.0, body, 0.5)
    }

    @Test
    fun widthZeroIsMonoHundredIsUntouchedTwoHundredDoublesTheSides() {
        fun run(width: Int): FloatArray {
            val chain = DspChain()
            chain.configure(rate, 2)
            chain.apply(DspParameters(widthPercent = width))
            val buffer = FloatArray(2048) { if (it % 2 == 0) (0.4 * sin(2 * PI * 500 * (it / 2) / rate)).toFloat() else 0f }
            chain.process(buffer, 1024)
            return buffer
        }
        val source = run(100)
        val reference = FloatArray(2048) { if (it % 2 == 0) (0.4 * sin(2 * PI * 500 * (it / 2) / rate)).toFloat() else 0f }
        assertTrue("100 % must not change a sample", source.contentEquals(reference))
        val mono = run(0)
        val wide = run(200)
        var monoError = 0f
        var wideError = 0f
        for (i in 0 until 1024) {
            val left = reference[2 * i]
            monoError = maxOf(monoError, abs(mono[2 * i] - left / 2), abs(mono[2 * i + 1] - left / 2))
            wideError = maxOf(wideError, abs(wide[2 * i] - left * 1.5f), abs(wide[2 * i + 1] + left / 2))
        }
        DspMeasure.record("Stereo width 0 % / 200 % on a left-only tone: largest error", "${DspMeasure.f(monoError.toDouble(), 6)} / ${DspMeasure.f(wideError.toDouble(), 6)}", "≤ 0.00001", monoError <= 1e-5f && wideError <= 1e-5f)
        assertTrue(monoError <= 1e-5f)
        assertTrue(wideError <= 1e-5f)
    }

    /** A club-style test signal: kick drum every half second, a 55 Hz bass line and a melody, peaking at 0.98. */
    private fun clubSignal(frames: Int): FloatArray {
        val left = DoubleArray(frames)
        val right = DoubleArray(frames)
        val melody = DoubleArray(frames) {
            val t = it / rate.toDouble()
            0.2 * sin(2 * PI * 440 * t) + 0.12 * sin(2 * PI * 1320 * t + 1.0) + 0.08 * if (sin(2 * PI * 3000 * t) >= 0) 1.0 else -1.0
        }
        for (i in 0 until frames) {
            val t = i / rate.toDouble()
            val sinceKick = (i % (rate / 2)) / rate.toDouble()
            val kick = if (sinceKick < 0.25) 0.9 * sin(2 * PI * (55 + 60 * exp(-sinceKick * 30)) * sinceKick) * exp(-sinceKick * 9) else 0.0
            val bass = 0.35 * sin(2 * PI * 55 * t) * (0.6 + 0.4 * sin(2 * PI * 2 * t))
            left[i] = kick + bass + melody[i]
            right[i] = kick + bass + 0.8 * melody[(i - 37 + frames) % frames]
        }
        var peak = 0.0
        for (i in 0 until frames) peak = maxOf(peak, abs(left[i]), abs(right[i]))
        return FloatArray(frames * 2) { ((if (it % 2 == 0) left[it / 2] else right[it / 2]) * 0.98 / peak).toFloat() }
    }

    private class Played(val clipped: Int, val peak: Float, val rms: Double, val lowestLimiterGain: Float)

    private fun play(input: FloatArray, parameters: DspParameters): Played {
        val meters = DynamicsMeters()
        val chain = DspChain(dynamics = meters)
        chain.configure(rate, 2)
        chain.apply(parameters)
        val output = input.copyOf()
        val frames = input.size / 2
        val chunk = FloatArray(1024 * 2)
        var clipped = 0
        var done = 0
        while (done < frames) {
            val n = minOf(1024, frames - done)
            System.arraycopy(output, done * 2, chunk, 0, n * 2)
            clipped += chain.process(chunk, n)
            System.arraycopy(chunk, 0, output, done * 2, n * 2)
            done += n
        }
        var peak = 0f
        var squares = 0.0
        for (i in output.indices) {
            peak = maxOf(peak, abs(output[i]))
            if (i >= output.size / 4) squares += output[i] * output[i]
        }
        val reading = DynamicsReading()
        meters.drain(reading)
        return Played(clipped, peak, sqrt(squares / (output.size - output.size / 4)), reading.limiterGain)
    }

    @Test
    fun hardDjBassPresetStaysCleanAndLoudAtFullScale() {
        val input = clubSignal(rate * 2)
        var inSquares = 0.0
        for (i in input.size / 4 until input.size) inSquares += input[i] * input[i]
        val inputRms = sqrt(inSquares / (input.size - input.size / 4))
        val preset = SoundPresets.HARD_DJ_BASS.parameters

        // The same boosts with nothing guarding the output: this is the distortion the user hears.
        val unguarded = play(input, preset.copy(compressor = 0, limiter = false))
        DspMeasure.record(
            "Hard DJ Bass boosts, compressor and limiter off: samples that clip (peak ${DspMeasure.f(db(unguarded.peak.toDouble()), 1)} dBFS)",
            "${unguarded.clipped} of ${input.size}",
            "> 0 (the problem)",
            unguarded.clipped > 0,
        )
        assertTrue(unguarded.clipped > 0)

        val guarded = play(input, preset)
        val ceiling = LookaheadLimiter.CEILING.toFloat()
        DspMeasure.record("Hard DJ Bass preset, full-scale club signal: samples that clip", "${guarded.clipped} of ${input.size}", "0", guarded.clipped == 0)
        DspMeasure.record("Hard DJ Bass preset: highest output sample", DspMeasure.f(db(guarded.peak.toDouble()), 3) + " dBFS", "≤ ${LookaheadLimiter.CEILING_DB} dBFS", guarded.peak <= ceiling)
        val louder = db(guarded.rms / inputRms)
        DspMeasure.record("Hard DJ Bass preset: loudness (RMS) against the input", DspMeasure.f(louder, 1) + " dB", "≥ 0 dB (not quieter)", louder >= 0.0)
        DspMeasure.record("Hard DJ Bass preset: most the limiter turned down", DspMeasure.f(db(guarded.lowestLimiterGain.toDouble()), 1) + " dB", "report", true)
        assertEquals(0, guarded.clipped)
        assertTrue("peak ${guarded.peak}", guarded.peak <= ceiling)
        assertTrue("quieter than the input by ${-louder} dB", louder >= 0.0)
        assertTrue("the limiter meter must show the work", guarded.lowestLimiterGain < 1f)
    }

    private fun largestStep(buffer: FloatArray, from: Int, to: Int): Float {
        var worst = 0f
        for (i in from + 1 until to) worst = maxOf(worst, abs(buffer[i] - buffer[i - 1]))
        return worst
    }

    @Test
    fun switchingTheLimiterAndSpeakerProtectIsClickFree() {
        val total = rate / 2
        val half = total / 2
        // 1100 Hz: the limiter's 5 ms delay is then half a cycle off, the hardest case for its fade.
        val music = FloatArray(total) { (0.25 * sin(2 * PI * 1100 * it / rate)).toFloat() }
        val ownStep = largestStep(music, 0, half)
        var worst = 0f
        for (target in listOf(DspParameters(limiter = true), DspParameters(speakerProtect = true))) {
            // On, then off again.
            val chain = DspChain()
            chain.configure(rate, 1)
            chain.apply(DspParameters())
            val a = music.copyOfRange(0, half)
            chain.process(a, half)
            chain.apply(target)
            val b = music.copyOfRange(half, total)
            chain.process(b, total - half)
            worst = maxOf(worst, largestStep(a + b, half - 1, total))
            chain.apply(DspParameters())
            val c = music.copyOf()
            chain.process(c, total)
            worst = maxOf(worst, largestStep(c, 0, total))
        }
        DspMeasure.record("Limiter / speaker protect switched on and off: largest step ÷ music's own largest step", DspMeasure.f((worst / ownStep).toDouble(), 3) + "×", "≤ 1.05×", worst <= ownStep * 1.05f)
        assertTrue("step $worst against the music's own $ownStep", worst <= ownStep * 1.05f)
    }

    private class Host(override var parameters: DspParameters) : DspHost {
        override val processingAllowed = true
        override val meters = MeterBus()
        override val load = LoadMeter()
        var positionUs = DspHost.UNKNOWN_POSITION_US
        override fun onProcessorConfigured(format: ProcessorFormat?, reason: String?) = Unit
        override fun onProcessedPosition(positionUs: Long) {
            this.positionUs = positionUs
        }
    }

    private fun shorts(buffer: ByteBuffer): ShortArray {
        val copy = buffer.duplicate().order(ByteOrder.nativeOrder())
        return ShortArray(copy.remaining() / 2) { copy.getShort() }
    }

    @Test
    fun limiterDelayIsCountedAndTheLastFramesStillComeOut() {
        val host = Host(DspParameters(limiter = true))
        val processor = VortexAudioProcessor(host)
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val frames = 4_800
        val delay = (LookaheadLimiter.LOOKAHEAD_SECONDS * rate).toInt()
        val input = ByteBuffer.allocateDirect(frames * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (0.3 * 32767 * sin(2 * PI * 440 * i / rate)).toInt().toShort()
            input.putShort(v)
            input.putShort((-v).toShort())
        }
        input.flip()
        val sent = shorts(input)
        processor.queueInput(input)
        val first = shorts(processor.getOutput())
        assertEquals(sent.size, first.size)
        assertEquals((frames - delay) * 1_000_000L / rate, host.positionUs)

        processor.queueEndOfStream()
        assertFalse("the limiter still holds $delay frames", processor.isEnded)
        val tail = shorts(processor.getOutput())
        assertEquals(delay * 2, tail.size)
        assertTrue(processor.isEnded)
        assertEquals(frames * 1_000_000L / rate, host.positionUs)

        val heard = first + tail
        var silentStart = 0
        for (i in 0 until delay * 2) if (heard[i] == 0.toShort()) silentStart++
        var changed = 0
        for (i in sent.indices) if (heard[i + delay * 2] != sent[i]) changed++
        DspMeasure.record("Media3 processor with limiter, 16-bit: frames out after end of stream / changed samples", "${heard.size / 2 - delay} of $frames / $changed", "all / 0", heard.size / 2 - delay == frames && changed == 0)
        assertEquals(delay * 2, silentStart)
        assertEquals(0, changed)
    }

    @Test
    fun presetsAndTheirStorage() {
        assertEquals(listOf("Studio Flat", "Hard DJ Bass", "Punchy Club", "Vocal Boost", "Cinema"), SoundPresets.BUILT_IN.map { it.name })
        assertTrue(SoundPresets.STUDIO_FLAT.parameters.isFlat)
        for (preset in SoundPresets.BUILT_IN) {
            val p = preset.parameters
            assertEquals(p, DspParameters.decode(p.encode()))
            assertEquals(preset, SoundPresets.matching(p.copy(enabled = false, speakerProtect = true), emptyList()))
            // Every preset that boosts anything has the limiter on.
            if (!p.isFlat) assertTrue(preset.name, p.limiter)
        }
        assertNull(SoundPresets.matching(SoundPresets.CINEMA.parameters.copy(bassDb = 1f), emptyList()))
        assertNull(DspParameters.decode("nonsense"))
        assertNull(DspParameters.decode(null))
        assertEquals(12f, DspParameters.decode("1;0;0,0,0,0,0,0,0,0,0,0;99;0;0;500;0;900")!!.bassDb)
        assertEquals(100, DspParameters.decode("1;0;0,0,0,0,0,0,0,0,0,0;99;0;0;500;0;900")!!.compressor)
        assertEquals(200, DspParameters.decode("1;0;0,0,0,0,0,0,0,0,0,0;99;0;0;500;0;900")!!.widthPercent)

        assertEquals("My bass", SoundPresets.cleanName("  My\tbass \n"))
        assertFalse(SoundPresets.isUsableName(SoundPresets.cleanName("   ")))
        assertFalse(SoundPresets.isUsableName("cinema"))
        val mine = DspParameters(bassDb = 7f, limiter = true, speakerProtect = true, enabled = false)
        var user = SoundPresets.withSaved(emptyList(), "My bass", mine)
        user = SoundPresets.withSaved(user, "my BASS", mine.copy(bassDb = 8f))
        assertEquals(1, user.size)
        assertEquals(8f, user[0].parameters.bassDb)
        assertFalse(user[0].parameters.speakerProtect)
        assertEquals(user, SoundPresets.decodeUser(SoundPresets.encodeUser(user)))
        assertEquals(user[0], SoundPresets.matching(mine.copy(bassDb = 8f), user))
        repeat(20) { user = SoundPresets.withSaved(user, "Sound $it", mine) }
        assertEquals(SoundPresets.MAX_USER_PRESETS, user.size)
        assertEquals(emptyList<SoundPreset>(), SoundPresets.decodeUser("no tab here\n\tno name\nCinema\t" + mine.encode()))
    }

    @Test
    fun everythingOnDoesNotAllocateAndRunsFast() {
        val everything = SoundPresets.HARD_DJ_BASS.parameters.copy(speakerProtect = true)
        val chain = DspChain(MeterBus(), DynamicsMeters())
        chain.configure(rate, 2)
        chain.apply(everything)
        val source = clubSignal(1024)
        val buffer = source.copyOf()
        repeat(2_000) {
            System.arraycopy(source, 0, buffer, 0, source.size)
            chain.process(buffer, 1024, it.toLong())
        }
        val buffers = 3_000
        val started = System.nanoTime()
        repeat(buffers) {
            System.arraycopy(source, 0, buffer, 0, source.size)
            chain.process(buffer, 1024, it.toLong())
        }
        val load = (System.nanoTime() - started) * 100.0 / (buffers.toLong() * 1024 * 1_000_000_000L / rate)
        DspMeasure.record("CI machine: CPU time for 48 kHz stereo, Hard DJ Bass + speaker protect (all stages)", DspMeasure.f(load, 3) + " % of real time", "< 25 % (loose; phone figure comes from the Lab)", load < 25.0)
        assertTrue("processing took ${DspMeasure.f(load)} % of real time", load < 25.0)

        val counter = AllocationCounter.create()
        if (counter == null) DspMeasure.record("Heap allocated with all stages on", "not measured (no allocation counter on this JVM)", "< 1024 bytes", true)
        assumeTrue("Per-thread allocation counter not available on this JVM", counter != null)
        chain.apply(everything.copy(compressor = 80, widthPercent = 150)) // a change mid-stream
        val before = counter!!.bytes()
        repeat(5_000) {
            System.arraycopy(source, 0, buffer, 0, source.size)
            chain.process(buffer, 1024, it.toLong())
        }
        val allocated = (counter.bytes() - before - counter.overheadBytes()).coerceAtLeast(0)
        DspMeasure.record("Heap allocated while processing 5000 stereo buffers with all stages on (incl. a change)", "$allocated bytes", "< 1024 bytes", allocated < 1024)
        assertTrue("processing allocated $allocated bytes", allocated < 1024)
    }
}
