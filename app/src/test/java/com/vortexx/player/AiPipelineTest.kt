package com.vortexx.player

import com.vortexx.player.ai.AiStore
import com.vortexx.player.ai.LyricsText
import com.vortexx.player.ai.audio.Wav
import com.vortexx.player.ai.audio.WavWriter
import com.vortexx.player.ai.dsp.Fft
import com.vortexx.player.ai.dsp.Resampler
import com.vortexx.player.ai.lyrics.Lrc
import com.vortexx.player.ai.lyrics.LyricLine
import com.vortexx.player.ai.lyrics.PhraseSegmenter
import com.vortexx.player.ai.lyrics.WhisperFeatures
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/** The arithmetic under the AI features, without any model. */
class AiPipelineTest {

    private fun sine(frequency: Double, rate: Int, seconds: Double, amplitude: Float = 0.5f): FloatArray =
        FloatArray((rate * seconds).toInt()) { (amplitude * sin(2.0 * PI * frequency * it / rate)).toFloat() }

    private fun rms(samples: FloatArray, from: Int = 0, to: Int = samples.size): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return sqrt(sum / maxOf(1, to - from))
    }

    @Test
    fun fftFindsTheToneAndInvertsExactly() {
        val n = 4096
        val fft = Fft(n)
        val random = Random(7)
        val original = FloatArray(n) { random.nextFloat() * 2f - 1f }
        val re = original.copyOf()
        val im = FloatArray(n)
        fft.forward(re, im)
        fft.inverse(re, im)
        var worst = 0f
        for (i in 0 until n) worst = maxOf(worst, abs(re[i] - original[i]), abs(im[i]))
        DspMeasure.record("AI: FFT 4096 forward + inverse, largest error", DspMeasure.f(worst.toDouble(), 7), "< 0.0001", worst < 1e-4f)
        assertTrue(worst < 1e-4f)

        // A tone on bin 100 has all its energy there: magnitude N/2 × amplitude.
        val tone = FloatArray(n) { sin(2.0 * PI * 100 * it / n).toFloat() }
        val toneIm = FloatArray(n)
        fft.forward(tone, toneIm)
        val magnitude = sqrt(tone[100] * tone[100] + toneIm[100] * toneIm[100])
        assertEquals(n / 2f, magnitude, 1f)
        assertTrue(sqrt(tone[300] * tone[300] + toneIm[300] * toneIm[300]) < 1f)
    }

    private fun resample(input: FloatArray, from: Int, to: Int): FloatArray {
        val resampler = Resampler(from, to)
        val out = ArrayList<Float>()
        var position = 0
        // Uneven chunk sizes, as a decoder delivers them.
        val chunks = intArrayOf(1000, 4096, 333, 8192)
        var c = 0
        while (position < input.size) {
            val n = minOf(chunks[c++ % chunks.size], input.size - position)
            resampler.process(input.copyOfRange(position, position + n), n).forEach { out.add(it) }
            position += n
        }
        resampler.flush().forEach { out.add(it) }
        return out.toFloatArray()
    }

    @Test
    fun resamplerKeepsToneLevelAndRemovesWhatCannotBeKept() {
        val seconds = 1.0
        val kept = resample(sine(1000.0, 44_100, seconds), 44_100, 16_000)
        assertEquals(16_000.0, kept.size.toDouble(), 2.0)
        val level = rms(kept, 2000, kept.size - 2000) / (0.5 / sqrt(2.0))
        DspMeasure.record("AI: resampler 44.1 → 16 kHz, 1 kHz tone level", DspMeasure.f(level, 4) + "×", "1 ± 0.02", abs(level - 1.0) < 0.02)
        assertEquals(1.0, level, 0.02)

        // 10 kHz cannot exist at 16 kHz sampling; it must be removed, not folded back as a false tone.
        val removed = resample(sine(10_000.0, 44_100, seconds), 44_100, 16_000)
        val leakDb = 20.0 * log10(rms(removed, 2000, removed.size - 2000) / (0.5 / sqrt(2.0)) + 1e-12)
        DspMeasure.record("AI: resampler 44.1 → 16 kHz, 10 kHz tone left over", DspMeasure.f(leakDb, 1) + " dB", "< -40 dB", leakDb < -40.0)
        assertTrue("leak $leakDb dB", leakDb < -40.0)

        val up = resample(sine(1000.0, 48_000, seconds), 48_000, 44_100)
        assertEquals(44_100.0, up.size.toDouble(), 2.0)
        assertEquals(1.0, rms(up, 4000, up.size - 4000) / (0.5 / sqrt(2.0)), 0.02)
    }

    @Test
    fun wavFilesRoundTrip() {
        val file = File.createTempFile("vortex", ".wav")
        try {
            val samples = FloatArray(2000) { ((it % 200) - 100) / 128f }
            WavWriter(file, 44_100, 2).use { it.write(samples, samples.size) }
            val read = Wav.read(file)
            assertEquals(44_100, read.sampleRate)
            assertEquals(2, read.channels)
            assertEquals(1000, read.frames)
            for (i in samples.indices) assertEquals(samples[i].coerceIn(-1f, 1f), read.samples[i], 1f / 16000f)
            assertEquals(1000, read.mono().size)
        } finally {
            file.delete()
        }
    }

    @Test
    fun segmenterFindsThePhrasesAndSplitsLongOnes() {
        val rate = 16_000
        val audio = FloatArray(rate * 40)
        fun burst(fromSec: Double, toSec: Double) {
            for (i in (fromSec * rate).toInt() until (toSec * rate).toInt()) audio[i] = (0.3 * sin(2.0 * PI * 220 * i / rate)).toFloat()
        }
        burst(1.0, 3.0)
        burst(3.2, 4.0) // 0.2 s gap: same phrase as the one before
        burst(6.0, 6.1) // 0.1 s blip: too short to be a phrase
        burst(10.0, 36.0) // 26 s without a pause: must be cut
        val phrases = PhraseSegmenter.phrases(audio, rate)
        assertTrue("phrases: $phrases", phrases.size >= 3)
        assertEquals(1.0, phrases[0].start / rate.toDouble(), 0.25)
        assertEquals(4.0, phrases[0].end / rate.toDouble(), 0.25)
        assertTrue(phrases.none { it.start / rate in 5..7 })
        assertTrue(phrases.all { it.length <= 14.5 * rate })
        assertEquals(10.0, phrases[1].start / rate.toDouble(), 0.25)
        assertEquals(36.0, phrases.last().end / rate.toDouble(), 0.25)
        assertTrue(PhraseSegmenter.phrases(FloatArray(rate * 5), rate).isEmpty())
    }

    @Test
    fun lrcWritesReadsAndFindsTheCurrentLine() {
        val lines = listOf(LyricLine(0, "first"), LyricLine(61_230, "दूसरी लाइन"), LyricLine(125_000, "third"))
        val text = Lrc.format(lines, language = "hi", by = "test")
        assertTrue(text.contains("[01:01.23]दूसरी लाइन"))
        assertEquals("hi", Lrc.language(text))
        assertEquals(lines, Lrc.parse(text))
        assertEquals(-1, Lrc.indexAt(lines.drop(1), 1000))
        assertEquals(0, Lrc.indexAt(lines, 0))
        assertEquals(1, Lrc.indexAt(lines, 100_000))
        assertEquals(2, Lrc.indexAt(lines, 999_000))
        assertEquals(listOf(LyricLine(83_500, "x")), Lrc.parse("junk\n[1:23.5] x\n[00:10.00]\n"))
        assertNull(Lrc.language("[00:01.00]no tag"))
    }

    @Test
    fun whisperFeaturesHaveTheReferenceShapeAndRange() {
        val features = WhisperFeatures()
        val frames = 500
        val tone = sine(1000.0, 16_000, 3.0)
        val mel = features.logMel(tone, tone.size, frames)
        assertEquals(WhisperFeatures.N_MELS * frames, mel.size)
        val max = mel.max()
        val min = mel.min()
        // By construction the range is at most 8 in log10 before (x + 4) / 4, i.e. 2.0 after.
        assertTrue("range ${max - min}", max - min <= 2.0001f)
        // The loudest band for a 1 kHz tone is around mel band 25 (Slaney scale), far from both ends.
        var best = 0
        var bestValue = -Float.MAX_VALUE
        for (m in 0 until WhisperFeatures.N_MELS) {
            val v = mel[m * frames + 100]
            if (v > bestValue) {
                bestValue = v
                best = m
            }
        }
        DspMeasure.record("AI: Whisper features, loudest mel band of a 1 kHz tone", best.toString(), "20–30", best in 20..30)
        assertTrue("band $best", best in 20..30)
        // Frames in the zero padding carry the floor value.
        assertEquals(min, mel[10 * frames + 450], 1e-6f)
    }

    @Test
    fun lyricTextAndStoreKeys() {
        assertEquals("तेरे बिना", LyricsText.clean("  (संगीत) तेरे   बिना ♪ "))
        assertEquals("", LyricsText.clean("[Music] ♪♪ ..."))
        assertEquals("hello world", LyricsText.clean("hello\n world"))
        assertEquals("song-42", AiStore.keyFor(42L))
        assertEquals("song-42", AiStore.keyFor(42L, null))
        assertEquals("song-7", AiStore.keyFor(null, "file:///data/user/0/com.vortexx.player.debug/files/ai/song-7/music.wav"))
        assertNull(AiStore.keyFor(null, "content://media/external/audio/media/9"))
    }
}
