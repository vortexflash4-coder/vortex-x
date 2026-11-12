package com.vortexx.player.ai.lyrics

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import java.io.ByteArrayOutputStream
import java.nio.FloatBuffer
import java.nio.LongBuffer
import java.util.Base64
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.log10
import kotlin.math.sin

/**
 * Whisper's input features: 80-band log-mel spectrogram of 16 kHz audio,
 * 25 ms Hann window, 10 ms hop, computed the way the reference
 * implementation does (reflect padding, Slaney mel scale, log10, floor at
 * max − 8, then (x + 4) / 4).
 */
class WhisperFeatures {
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }
    private val cosTable = FloatArray(N_FFT) { cos(2.0 * PI * it / N_FFT).toFloat() }
    private val sinTable = FloatArray(N_FFT) { sin(2.0 * PI * it / N_FFT).toFloat() }
    private val filters = melFilters()
    private val frame = FloatArray(N_FFT)
    private val power = FloatArray(N_FFT / 2 + 1)

    /**
     * Features of the first [count] samples, zero-padded to exactly [frames]
     * frames. Returns [N_MELS] × [frames] values, mel band by mel band.
     */
    fun logMel(samples: FloatArray, count: Int, frames: Int): FloatArray {
        val length = frames * HOP
        val out = FloatArray(N_MELS * frames)
        var max = -Float.MAX_VALUE
        for (t in 0 until frames) {
            val start = t * HOP - N_FFT / 2
            for (i in 0 until N_FFT) {
                // Reflect at both ends of the padded signal, as torch.stft(center=True) does.
                var index = start + i
                if (index < 0) index = -index
                if (index >= length) index = 2 * (length - 1) - index
                val sample = if (index in 0 until count) samples[index] else 0f
                frame[i] = sample * window[i]
            }
            // Frames that lie wholly in the zero padding need no transform.
            if (start - N_FFT >= count) {
                for (m in 0 until N_MELS) out[m * frames + t] = SILENCE_LOG
                if (SILENCE_LOG > max) max = SILENCE_LOG
                continue
            }
            for (k in 0..N_FFT / 2) {
                var re = 0f
                var im = 0f
                var phase = 0
                for (i in 0 until N_FFT) {
                    re += frame[i] * cosTable[phase]
                    im -= frame[i] * sinTable[phase]
                    phase += k
                    if (phase >= N_FFT) phase -= N_FFT
                }
                power[k] = re * re + im * im
            }
            for (m in 0 until N_MELS) {
                val filter = filters[m]
                var energy = 0f
                for (k in filter.first until filter.first + filter.weights.size) energy += power[k] * filter.weights[k - filter.first]
                val value = log10(maxOf(energy, 1e-10f))
                out[m * frames + t] = value
                if (value > max) max = value
            }
        }
        val floor = max - 8f
        for (i in out.indices) out[i] = (maxOf(out[i], floor) + 4f) / 4f
        return out
    }

    private class MelFilter(val first: Int, val weights: FloatArray)

    /** librosa.filters.mel(sr=16000, n_fft=400, n_mels=80): Slaney scale, area-normalised triangles. */
    private fun melFilters(): Array<MelFilter> {
        val fMax = SAMPLE_RATE / 2.0
        val melMax = hzToMel(fMax)
        val edges = DoubleArray(N_MELS + 2) { melToHz(melMax * it / (N_MELS + 1)) }
        val binHz = SAMPLE_RATE.toDouble() / N_FFT
        return Array(N_MELS) { m ->
            val lower = edges[m]
            val centre = edges[m + 1]
            val upper = edges[m + 2]
            val norm = 2.0 / (upper - lower)
            val weights = ArrayList<Float>()
            var first = -1
            for (k in 0..N_FFT / 2) {
                val f = k * binHz
                val w = minOf((f - lower) / (centre - lower), (upper - f) / (upper - centre))
                if (w > 0.0) {
                    if (first < 0) first = k
                    weights.add((w * norm).toFloat())
                } else if (first >= 0) {
                    break
                }
            }
            MelFilter(if (first < 0) 0 else first, weights.toFloatArray())
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        const val N_FFT = 400
        const val HOP = 160
        const val N_MELS = 80

        /** log10 of the 1e-10 floor: the value of a silent band. */
        private const val SILENCE_LOG = -10f

        /** Frames in Whisper's full 30 s window. */
        const val MAX_FRAMES = 3000

        private const val F_SP = 200.0 / 3.0
        private const val MIN_LOG_HZ = 1000.0
        private const val MIN_LOG_MEL = MIN_LOG_HZ / F_SP
        private val LOG_STEP = ln(6.4) / 27.0

        private fun hzToMel(hz: Double): Double =
            if (hz >= MIN_LOG_HZ) MIN_LOG_MEL + ln(hz / MIN_LOG_HZ) / LOG_STEP else hz / F_SP

        private fun melToHz(mel: Double): Double =
            if (mel >= MIN_LOG_MEL) MIN_LOG_HZ * Math.exp(LOG_STEP * (mel - MIN_LOG_MEL)) else mel * F_SP
    }
}

/** What one transcription produced. [language] is the Whisper code used ("hi", "en", …). */
class Transcript(val text: String, val language: String, val tokenCount: Int)

/**
 * Greedy Whisper decoding with ONNX Runtime, for the encoder/decoder pair
 * exported by the sherpa-onnx project (the decoder carries its self-attention
 * cache as explicit inputs and outputs). One call handles up to 30 s of audio.
 */
class WhisperTranscriber(
    private val env: OrtEnvironment,
    private val encoder: OrtSession,
    private val decoder: OrtSession,
    tokenLines: Sequence<String>,
) {
    private val features = WhisperFeatures()
    private val meta: Map<String, String> = runCatching { encoder.metadata.customMetadata }.getOrDefault(emptyMap())
    private val tokenBytes = HashMap<Int, ByteArray>()

    private val eot = metaInt("eot", 50257)
    private val sot = metaInt("sot", 50258)
    private val transcribe = metaInt("transcribe", 50359)
    private val noTimestamps = metaInt("no_timestamps", 50363)
    private val multilingual = (meta["is_multilingual"] ?: "1") != "0"
    private val languageCodes: List<String> = meta["all_language_codes"]?.split(',')?.map { it.trim() }?.filter { it.isNotEmpty() } ?: DEFAULT_LANGUAGES
    private val languageTokens: List<Int> =
        meta["all_language_tokens"]?.split(',')?.mapNotNull { it.trim().toIntOrNull() }?.takeIf { it.size == languageCodes.size }
            ?: List(languageCodes.size) { sot + 1 + it }

    private val encoderInput = encoder.inputNames.first()
    private val fixedFrames: Int = ((encoder.inputInfo[encoderInput]?.info as? TensorInfo)?.shape?.getOrNull(2) ?: -1L).toInt()
    private val names = DecoderNames(decoder.inputNames.toList(), decoder.outputNames.toList())
    private val cacheShape: LongArray = readCacheShape()
    private val zeroCache = FloatArray(cacheShape.fold(1L) { a, b -> a * b }.toInt())

    init {
        for (line in tokenLines) {
            val space = line.lastIndexOf(' ')
            if (space <= 0) continue
            val id = line.substring(space + 1).trim().toIntOrNull() ?: continue
            tokenBytes[id] = runCatching { Base64.getDecoder().decode(line.substring(0, space).trim()) }
                .getOrElse { line.substring(0, space).toByteArray(Charsets.UTF_8) }
        }
    }

    /** Languages this model knows, as Whisper codes. Empty for English-only models. */
    val languages: List<String> get() = if (multilingual) languageCodes else emptyList()

    /** The model's declared inputs and metadata, for diagnostics and the CI log. */
    fun describe(): String = buildString {
        append("encoder in=").append(describeInputs(encoder)).append(" out=").append(encoder.outputNames)
        append("; decoder in=").append(describeInputs(decoder)).append(" out=").append(decoder.outputNames)
        append("; meta=").append(meta.filterKeys { it != "all_language_tokens" && it != "all_language_codes" && it != "non_speech_tokens" })
    }

    /**
     * Transcribes the first [count] samples of 16 kHz mono [samples] (at most
     * 30 s are used). [language] is a Whisper code or null to let the model
     * pick. Returns an empty text when the model hears no words.
     */
    fun transcribe(samples: FloatArray, count: Int, language: String? = null): Transcript {
        val used = minOf(count, WhisperFeatures.MAX_FRAMES * WhisperFeatures.HOP)
        val frames = if (fixedFrames > 0) {
            fixedFrames
        } else {
            // Variable-length export: the audio plus three seconds of silence, as sherpa-onnx pads it.
            minOf(WhisperFeatures.MAX_FRAMES, used / WhisperFeatures.HOP + TAIL_FRAMES)
        }
        val mel = features.logMel(samples, used, frames)
        OnnxTensor.createTensor(env, FloatBuffer.wrap(mel), longArrayOf(1, WhisperFeatures.N_MELS.toLong(), frames.toLong())).use { input ->
            encoder.run(mapOf(encoderInput to input)).use { encoded ->
                val crossK = encoded.get(0) as OnnxTensor
                val crossV = encoded.get(1) as OnnxTensor
                var languageIndex = if (language != null) languageCodes.indexOf(language) else -1
                if (multilingual && languageIndex < 0) languageIndex = detectLanguage(crossK, crossV)
                val start = if (multilingual) {
                    longArrayOf(sot.toLong(), languageTokens[languageIndex.coerceAtLeast(0)].toLong(), transcribe.toLong(), noTimestamps.toLong())
                } else {
                    longArrayOf(sot.toLong(), noTimestamps.toLong())
                }
                val tokens = decode(start, crossK, crossV)
                val bytes = ByteArrayOutputStream()
                for (token in tokens) tokenBytes[token]?.let { bytes.write(it) }
                val text = String(bytes.toByteArray(), Charsets.UTF_8).replace('�', ' ').trim()
                val code = if (multilingual) languageCodes[languageIndex.coerceAtLeast(0)] else "en"
                return Transcript(text, code, tokens.size)
            }
        }
    }

    private fun detectLanguage(crossK: OnnxTensor, crossV: OnnxTensor): Int {
        step(longArrayOf(sot.toLong()), 0L, null, null, crossK, crossV).use { result ->
            val logits = (result.get(names.logitsIndex) as OnnxTensor).floatBuffer
            var best = 0
            var bestValue = -Float.MAX_VALUE
            for (i in languageTokens.indices) {
                val value = logits.get(languageTokens[i])
                if (value > bestValue) {
                    bestValue = value
                    best = i
                }
            }
            return best
        }
    }

    private fun decode(start: LongArray, crossK: OnnxTensor, crossV: OnnxTensor): List<Int> {
        val out = ArrayList<Int>()
        var result = step(start, 0L, null, null, crossK, crossV)
        try {
            var offset = start.size.toLong()
            var next = argmax(result, start.size - 1)
            var repeats = 0
            val limit = minOf(MAX_TOKENS.toLong(), cacheShape[2] - 1)
            while (next != eot && out.size < MAX_TOKENS && offset < limit) {
                repeats = if (out.isNotEmpty() && out.last() == next) repeats + 1 else 0
                if (repeats >= MAX_REPEATS) break
                out.add(next)
                val following = step(
                    longArrayOf(next.toLong()),
                    offset,
                    result.get(names.selfKOutIndex) as OnnxTensor,
                    result.get(names.selfVOutIndex) as OnnxTensor,
                    crossK,
                    crossV,
                )
                result.close()
                result = following
                offset++
                next = argmax(result, 0)
            }
        } finally {
            result.close()
        }
        return out
    }

    /** One decoder call. Null caches mean "start of the sequence" (all zeros). */
    private fun step(tokens: LongArray, offset: Long, selfK: OnnxTensor?, selfV: OnnxTensor?, crossK: OnnxTensor, crossV: OnnxTensor): OrtSession.Result {
        val tokenTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(tokens), longArrayOf(1, tokens.size.toLong()))
        val offsetTensor = OnnxTensor.createTensor(env, LongBuffer.wrap(longArrayOf(offset)), longArrayOf(1))
        val zeroK = if (selfK == null) OnnxTensor.createTensor(env, FloatBuffer.wrap(zeroCache), cacheShape) else null
        val zeroV = if (selfV == null) OnnxTensor.createTensor(env, FloatBuffer.wrap(zeroCache), cacheShape) else null
        try {
            val inputs = HashMap<String, OnnxTensor>()
            inputs[names.tokens] = tokenTensor
            inputs[names.selfK] = selfK ?: zeroK!!
            inputs[names.selfV] = selfV ?: zeroV!!
            inputs[names.crossK] = crossK
            inputs[names.crossV] = crossV
            inputs[names.offset] = offsetTensor
            return decoder.run(inputs)
        } finally {
            tokenTensor.close()
            offsetTensor.close()
            zeroK?.close()
            zeroV?.close()
        }
    }

    /** Most likely text token at [position] of the logits; special tokens above end-of-text are never chosen. */
    private fun argmax(result: OrtSession.Result, position: Int): Int {
        val tensor = result.get(names.logitsIndex) as OnnxTensor
        val vocabulary = tensor.info.shape.last().toInt()
        val logits = tensor.floatBuffer
        val base = position * vocabulary
        var best = eot
        var bestValue = -Float.MAX_VALUE
        for (i in 0..minOf(eot, vocabulary - 1)) {
            val value = logits.get(base + i)
            if (value > bestValue) {
                bestValue = value
                best = i
            }
        }
        return best
    }

    private fun metaInt(key: String, fallback: Int): Int = meta[key]?.trim()?.toIntOrNull() ?: fallback

    private fun readCacheShape(): LongArray {
        val declared = (decoder.inputInfo[names.selfK]?.info as? TensorInfo)?.shape
        val layers = metaInt("n_text_layer", declared?.getOrNull(0)?.toInt() ?: -1)
        val context = metaInt("n_text_ctx", declared?.getOrNull(2)?.toInt() ?: 448)
        val state = metaInt("n_text_state", declared?.getOrNull(3)?.toInt() ?: -1)
        check(layers > 0 && context > 0 && state > 0) { "Whisper decoder does not declare its cache size (${describe()})" }
        return longArrayOf(layers.toLong(), 1L, context.toLong(), state.toLong())
    }

    private fun describeInputs(session: OrtSession): String =
        session.inputInfo.entries.joinToString(prefix = "{", postfix = "}") { (name, node) ->
            name + (node.info as? TensorInfo)?.shape?.joinToString(prefix = "[", postfix = "]")
        }

    /** Decoder input and output names, found by what they contain rather than by position. */
    private class DecoderNames(inputs: List<String>, outputs: List<String>) {
        val tokens = inputs.firstOrNull { it.contains("token") } ?: inputs[0]
        val selfK = inputs.firstOrNull { it.contains("self_k") } ?: inputs[1]
        val selfV = inputs.firstOrNull { it.contains("self_v") } ?: inputs[2]
        val crossK = inputs.firstOrNull { it.contains("cross_k") } ?: inputs[3]
        val crossV = inputs.firstOrNull { it.contains("cross_v") } ?: inputs[4]
        val offset = inputs.firstOrNull { it.contains("offset") } ?: inputs[5]
        val logitsIndex = outputs.indexOfFirst { it.contains("logits") }.coerceAtLeast(0)
        val selfKOutIndex = outputs.indexOfFirst { it.contains("self_k") }.let { if (it < 0) 1 else it }
        val selfVOutIndex = outputs.indexOfFirst { it.contains("self_v") }.let { if (it < 0) 2 else it }
    }

    companion object {
        /** Zero frames added after the audio for variable-length models (3 s). */
        const val TAIL_FRAMES = 300
        private const val MAX_TOKENS = 220
        private const val MAX_REPEATS = 8

        /** Whisper's language order (tokens follow start-of-transcript in this order). */
        val DEFAULT_LANGUAGES: List<String> = (
            "en,zh,de,es,ru,ko,fr,ja,pt,tr,pl,ca,nl,ar,sv,it,id,hi,fi,vi,he,uk,el,ms,cs,ro,da,hu,ta,no,th,ur,hr,bg,lt,la,mi,ml,cy,sk,te,fa,lv,bn," +
                "sr,az,sl,kn,et,mk,br,eu,is,hy,ne,mn,bs,kk,sq,sw,gl,mr,pa,si,km,sn,yo,so,af,oc,ka,be,tg,sd,gu,am,yi,lo,uz,fo,ht,ps,tk,nn,mt,sa,lb," +
                "my,bo,tl,mg,as,tt,haw,ln,ha,ba,jw,su"
            ).split(',')
    }
}
