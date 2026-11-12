package com.vortexx.player

import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import com.vortexx.player.ai.audio.Wav
import com.vortexx.player.ai.dsp.Resampler
import com.vortexx.player.ai.karaoke.SpleeterSeparator
import com.vortexx.player.ai.lyrics.WhisperTranscriber
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import kotlin.math.sqrt

/**
 * Runs the real models on the build machine. CI downloads them
 * (.github/scripts/ci-models.sh) together with short test recordings and,
 * when possible, reference results from the sherpa-onnx project's own
 * implementation. Without the files (a local build) these tests are skipped.
 */
class AiModelsTest {

    private val models = File("src/main/assets/ai")
    private val testFiles = File("build/ai-test")

    private fun open(name: String): OrtSession {
        val file = File(models, name)
        assumeTrue("model $name not downloaded", file.length() > 1_000_000)
        return OrtEnvironment.getEnvironment().createSession(file.absolutePath, OrtSession.SessionOptions())
    }

    @Test
    fun spleeterSeparatesTheTestSong() {
        val song = File(testFiles, "song.wav")
        assumeTrue("test song not downloaded", song.length() > 100_000)
        val wav = Wav.read(song)
        assumeTrue("test song is not 44.1 kHz stereo", wav.sampleRate == SpleeterSeparator.SAMPLE_RATE && wav.channels == 2)
        val frames = wav.frames
        val vocalsModel = open("spleeter-vocals.onnx")
        val musicModel = open("spleeter-accompaniment.onnx")
        val vocalsLeft = FloatArray(frames)
        val vocalsRight = FloatArray(frames)
        var read = 0
        var written = 0
        var worstSum = 0f
        val began = System.nanoTime()
        try {
            val separator = SpleeterSeparator(OrtEnvironment.getEnvironment(), vocalsModel, musicModel)
            DspMeasure.record("AI: Spleeter model input shape (as declared by the model)", separator.modelInputShape.replace('|', '/'), "4 dimensions with 2, 512, 1024", true)
            separator.separate(
                totalFrames = frames.toLong(),
                source = SpleeterSeparator.Source { l, r, offset, count ->
                    val n = minOf(count, frames - read)
                    for (i in 0 until n) {
                        l[offset + i] = wav.samples[2 * (read + i)]
                        r[offset + i] = wav.samples[2 * (read + i) + 1]
                    }
                    read += n
                    n
                },
                sink = SpleeterSeparator.Sink { vl, vr, ml, mr, offset, count ->
                    for (i in 0 until count) {
                        vocalsLeft[written + i] = vl[offset + i]
                        vocalsRight[written + i] = vr[offset + i]
                        // The mixture handed back must be the input, sample for sample.
                        val expected = wav.samples[2 * (written + i)]
                        worstSum = maxOf(worstSum, kotlin.math.abs(ml[offset + i] - expected))
                    }
                    written += count
                },
            )
        } finally {
            vocalsModel.close()
            musicModel.close()
        }
        val seconds = (System.nanoTime() - began) / 1e9
        val audioSeconds = frames / SpleeterSeparator.SAMPLE_RATE.toDouble()
        DspMeasure.record("AI: Spleeter on CI machine, time for ${DspMeasure.f(audioSeconds, 1)} s of music", DspMeasure.f(seconds, 1) + " s", "report", true)

        DspMeasure.record("AI: Spleeter frames written", "$written of $frames", "all", written == frames)
        assertTrue(written == frames)
        assertTrue("mixture alignment error $worstSum", worstSum < 1e-6f)

        // The vocal stem must be a real part of the mix: neither nothing nor everything.
        var mixEnergy = 0.0
        var vocalEnergy = 0.0
        var musicEnergy = 0.0
        for (i in 0 until frames) {
            val m = wav.samples[2 * i].toDouble()
            val v = vocalsLeft[i].toDouble()
            mixEnergy += m * m
            vocalEnergy += v * v
            musicEnergy += (m - v) * (m - v)
        }
        val vocalShare = vocalEnergy / (mixEnergy + 1e-12)
        val musicShare = musicEnergy / (mixEnergy + 1e-12)
        DspMeasure.record("AI: Spleeter vocal stem energy ÷ mix energy (test song)", DspMeasure.f(vocalShare, 3), "0.02–0.98", vocalShare in 0.02..0.98)
        DspMeasure.record("AI: Spleeter music stem energy ÷ mix energy (test song)", DspMeasure.f(musicShare, 3), "0.02–0.98", musicShare in 0.02..0.98)
        assertTrue("vocal share $vocalShare", vocalShare in 0.02..0.98)
        assertTrue("music share $musicShare", musicShare in 0.02..0.98)

        // Same stem from the sherpa-onnx project's own code, when CI could produce it.
        val reference = File(testFiles, "song-vocals-ref.wav")
        if (reference.length() > 100_000) {
            val ref = Wav.read(reference)
            if (ref.sampleRate == wav.sampleRate && ref.channels == 2) {
                val n = minOf(frames, ref.frames)
                // Skip the first and last second: the two implementations pad the ends differently.
                val from = SpleeterSeparator.SAMPLE_RATE
                val to = n - SpleeterSeparator.SAMPLE_RATE
                var dot = 0.0
                var a2 = 0.0
                var b2 = 0.0
                for (i in from until to) {
                    val a = vocalsLeft[i].toDouble()
                    val b = ref.samples[2 * i].toDouble()
                    dot += a * b
                    a2 += a * a
                    b2 += b * b
                }
                val correlation = dot / (sqrt(a2 * b2) + 1e-12)
                DspMeasure.record("AI: Spleeter vocals vs sherpa-onnx reference, correlation", DspMeasure.f(correlation, 4), "> 0.90", correlation > 0.90)
                assertTrue("correlation with reference $correlation", correlation > 0.90)
            }
        } else {
            DspMeasure.record("AI: Spleeter vocals vs sherpa-onnx reference", "reference not available", "report", true)
        }
    }

    private fun words(text: String): List<String> =
        text.lowercase().replace(Regex("[^a-z0-9' ]"), " ").split(' ').filter { it.isNotBlank() }

    /** Share of the reference words that also appear, in order, in [heard] (longest common subsequence). */
    private fun wordMatch(reference: List<String>, heard: List<String>): Double {
        if (reference.isEmpty()) return 0.0
        val table = Array(reference.size + 1) { IntArray(heard.size + 1) }
        for (i in 1..reference.size) {
            for (j in 1..heard.size) {
                table[i][j] = if (reference[i - 1] == heard[j - 1]) table[i - 1][j - 1] + 1 else maxOf(table[i - 1][j], table[i][j - 1])
            }
        }
        return table[reference.size][heard.size].toDouble() / reference.size
    }

    @Test
    fun whisperTranscribesTheTestRecording() {
        val speech = File(testFiles, "speech.wav")
        assumeTrue("test speech not downloaded", speech.length() > 10_000)
        val tokens = File(models, "whisper-tokens.txt")
        assumeTrue("tokens not downloaded", tokens.length() > 1000)
        val wav = Wav.read(speech)
        var samples = wav.mono()
        if (wav.sampleRate != 16_000) {
            val resampler = Resampler(wav.sampleRate, 16_000)
            samples = resampler.process(samples, samples.size) + resampler.flush()
        }
        val encoder = open("whisper-encoder.onnx")
        val decoder = open("whisper-decoder.onnx")
        try {
            val whisper = WhisperTranscriber(OrtEnvironment.getEnvironment(), encoder, decoder, tokens.readLines().asSequence())
            println("WHISPER-MODEL " + whisper.describe())
            val began = System.nanoTime()
            val english = whisper.transcribe(samples, samples.size, "en")
            val seconds = (System.nanoTime() - began) / 1e9
            println("WHISPER-TEXT " + english.text)
            DspMeasure.record(
                "AI: Whisper on CI machine, ${DspMeasure.f(samples.size / 16_000.0, 1)} s of speech",
                DspMeasure.f(seconds, 1) + " s, ${english.tokenCount} tokens",
                "report",
                true,
            )
            val heard = words(english.text)
            DspMeasure.record("AI: Whisper transcript of the test recording", english.text.take(160).replace('|', '/'), "words", heard.size >= 3)
            assertTrue("no words heard: '${english.text}'", heard.size >= 3)

            // Against the sherpa-onnx project's own decoder on the same file, else the published transcript.
            val referenceFile = File(testFiles, "speech-ref.txt").takeIf { it.length() > 0 }
            val reference = referenceFile?.readText()
                ?: File(testFiles, "speech-trans.txt").takeIf { it.length() > 0 }?.readLines()?.firstOrNull()?.substringAfter(' ')
            if (reference != null) {
                val match = wordMatch(words(reference), heard)
                val source = if (referenceFile != null) "sherpa-onnx reference" else "published transcript"
                DspMeasure.record("AI: Whisper words matching the $source", DspMeasure.f(match * 100, 1) + " %", "≥ 85 %", match >= 0.85)
                assertTrue("only ${match * 100} % of the reference words: '${english.text}' vs '$reference'", match >= 0.85)
            }

            // Language detection on English speech must say English.
            val auto = whisper.transcribe(samples, samples.size, null)
            DspMeasure.record("AI: Whisper detected language of the English test recording", auto.language, "en", auto.language == "en")
            assertTrue("detected ${auto.language}", auto.language == "en")
        } finally {
            encoder.close()
            decoder.close()
        }
    }
}
