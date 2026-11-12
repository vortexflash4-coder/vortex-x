package com.vortexx.player.ai.karaoke

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import ai.onnxruntime.TensorInfo
import com.vortexx.player.ai.dsp.Fft
import java.nio.FloatBuffer
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sqrt

/**
 * Vocal / music separation with the Spleeter 2-stems model (Deezer, MIT) as
 * exported to ONNX by the sherpa-onnx project. Works on 44.1 kHz stereo.
 *
 * Method (Spleeter's own): STFT with a 4096-sample Hann window and a 1024
 * hop; the two networks estimate the vocal and the accompaniment magnitude of
 * the lowest 1024 bins for 512 frames at a time; a ratio mask of the two
 * estimates is applied to the mixture and transformed back. Everything the
 * vocal estimate does not claim, including all content above 11 kHz, stays in
 * the music: music = mixture − vocals, sample by sample.
 *
 * Audio is processed in blocks of [SEGMENT] frames, so memory use does not
 * depend on the length of the song.
 */
class SpleeterSeparator(
    private val env: OrtEnvironment,
    private val vocals: OrtSession,
    private val accompaniment: OrtSession,
) {
    /** Supplies 44.1 kHz stereo audio. Fill from index [offset]; return frames read (less than [count] only at the end). */
    fun interface Source {
        fun read(left: FloatArray, right: FloatArray, offset: Int, count: Int): Int
    }

    /** Receives finished audio: [count] frames starting at [offset] in each array. */
    fun interface Sink {
        fun write(vocalsLeft: FloatArray, vocalsRight: FloatArray, mixLeft: FloatArray, mixRight: FloatArray, offset: Int, count: Int)
    }

    private val layout = TensorLayout.of(declaredShape(vocals))
    private val inputName = vocals.inputNames.first()
    private val accompanimentInput = accompaniment.inputNames.first()
    private val fft = Fft(N_FFT)
    private val window = FloatArray(N_FFT) { (0.5 - 0.5 * cos(2.0 * PI * it / N_FFT)).toFloat() }

    private val inLeft = FloatArray(HISTORY + SEGMENT)
    private val inRight = FloatArray(HISTORY + SEGMENT)
    private val outLeft = FloatArray(HISTORY + SEGMENT)
    private val outRight = FloatArray(HISTORY + SEGMENT)
    private val specRe = FloatArray(2 * FRAMES * BINS)
    private val specIm = FloatArray(2 * FRAMES * BINS)
    private val magnitude = FloatArray(2 * FRAMES * BINS)
    private val re = FloatArray(N_FFT)
    private val im = FloatArray(N_FFT)

    /** Describes the model input the way the network declares it, e.g. "[2, -1, 512, 1024]". */
    val modelInputShape: String = declaredShape(vocals)?.joinToString(prefix = "[", postfix = "]") ?: "unknown"

    /**
     * Separates [totalFrames] frames read from [source]. [onProgress] gets 0…1
     * after every block; return true from [isCancelled] to stop early.
     */
    fun separate(
        totalFrames: Long,
        source: Source,
        sink: Sink,
        onProgress: (Float) -> Unit = {},
        isCancelled: () -> Boolean = { false },
    ) {
        if (totalFrames <= 0) return
        java.util.Arrays.fill(inLeft, 0f)
        java.util.Arrays.fill(inRight, 0f)
        java.util.Arrays.fill(outLeft, 0f)
        java.util.Arrays.fill(outRight, 0f)
        // Block b holds the original frames [b·SEGMENT − HISTORY, (b+1)·SEGMENT) at buffer index 0….
        val blocks = ((totalFrames + HISTORY + SEGMENT - 1) / SEGMENT).toInt()
        var sourceEnded = false
        for (block in 0 until blocks) {
            if (isCancelled()) return
            var filled = 0
            while (!sourceEnded && filled < SEGMENT) {
                val got = source.read(inLeft, inRight, HISTORY + filled, SEGMENT - filled)
                if (got <= 0) sourceEnded = true else filled += got
            }
            java.util.Arrays.fill(inLeft, HISTORY + filled, HISTORY + SEGMENT, 0f)
            java.util.Arrays.fill(inRight, HISTORY + filled, HISTORY + SEGMENT, 0f)

            analyse(inLeft, 0)
            analyse(inRight, 1)
            val vocalEstimate = run(vocals, inputName)
            val musicEstimate = run(accompaniment, accompanimentInput)
            synthesise(vocalEstimate, musicEstimate, outLeft, 0)
            synthesise(vocalEstimate, musicEstimate, outRight, 1)

            // The first SEGMENT buffer positions are final: no later frame reaches them.
            val firstFrame = block.toLong() * SEGMENT - HISTORY
            val from = if (firstFrame < 0) (-firstFrame).toInt() else 0
            val to = minOf(SEGMENT.toLong(), totalFrames - firstFrame).toInt()
            if (to > from) sink.write(outLeft, outRight, inLeft, inRight, from, to - from)

            System.arraycopy(outLeft, SEGMENT, outLeft, 0, HISTORY)
            System.arraycopy(outRight, SEGMENT, outRight, 0, HISTORY)
            java.util.Arrays.fill(outLeft, HISTORY, HISTORY + SEGMENT, 0f)
            java.util.Arrays.fill(outRight, HISTORY, HISTORY + SEGMENT, 0f)
            System.arraycopy(inLeft, SEGMENT, inLeft, 0, HISTORY)
            System.arraycopy(inRight, SEGMENT, inRight, 0, HISTORY)
            onProgress((block + 1f) / blocks)
        }
    }

    /** STFT of one channel of the block into [specRe]/[specIm] and the magnitude tensor. */
    private fun analyse(input: FloatArray, channel: Int) {
        for (frame in 0 until FRAMES) {
            val start = frame * HOP
            for (i in 0 until N_FFT) {
                re[i] = input[start + i] * window[i]
                im[i] = 0f
            }
            fft.forward(re, im)
            val base = (channel * FRAMES + frame) * BINS
            for (k in 0 until BINS) {
                val r = re[k]
                val j = im[k]
                specRe[base + k] = r
                specIm[base + k] = j
                magnitude[layout.index(channel, frame, k)] = sqrt(r * r + j * j)
            }
        }
    }

    private fun run(session: OrtSession, name: String): FloatArray {
        OnnxTensor.createTensor(env, FloatBuffer.wrap(magnitude), layout.shape).use { input ->
            session.run(mapOf(name to input)).use { result ->
                val buffer = (result.get(0) as OnnxTensor).floatBuffer
                check(buffer.remaining() == magnitude.size) {
                    "Spleeter output has ${buffer.remaining()} values, expected ${magnitude.size}"
                }
                val out = FloatArray(buffer.remaining())
                buffer.get(out)
                return out
            }
        }
    }

    /** Masks the mixture with the vocal ratio mask and overlap-adds the vocal signal into [output]. */
    private fun synthesise(vocalEstimate: FloatArray, musicEstimate: FloatArray, output: FloatArray, channel: Int) {
        for (frame in 0 until FRAMES) {
            java.util.Arrays.fill(re, 0f)
            java.util.Arrays.fill(im, 0f)
            val base = (channel * FRAMES + frame) * BINS
            for (k in 0 until BINS) {
                val index = layout.index(channel, frame, k)
                val v = vocalEstimate[index]
                val a = musicEstimate[index]
                val v2 = v * v
                val mask = (v2 + EPSILON / 2f) / (v2 + a * a + EPSILON)
                re[k] = specRe[base + k] * mask
                im[k] = specIm[base + k] * mask
            }
            for (k in 1 until BINS) {
                re[N_FFT - k] = re[k]
                im[N_FFT - k] = -im[k]
            }
            fft.inverse(re, im)
            val start = frame * HOP
            for (i in 0 until N_FFT) output[start + i] += re[i] * window[i] * OVERLAP_SCALE
        }
    }

    /** Where channel, frame and bin sit in the model's 4-D input. */
    internal class TensorLayout(val shape: LongArray, private val channelStride: Int, private val frameStride: Int, private val binStride: Int) {
        fun index(channel: Int, frame: Int, bin: Int): Int = channel * channelStride + frame * frameStride + bin * binStride

        companion object {
            /** [declared] is the model's own input shape; unknown shapes use sherpa-onnx's [2, splits, 512, 1024]. */
            fun of(declared: LongArray?): TensorLayout {
                var channelAxis = 0
                var frameAxis = 2
                var binAxis = 3
                if (declared != null && declared.size == 4) {
                    val c = declared.indexOf(2L)
                    val t = declared.indexOf(FRAMES.toLong())
                    val f = declared.indexOf(BINS.toLong())
                    if (c >= 0 && t >= 0 && f >= 0) {
                        channelAxis = c
                        frameAxis = t
                        binAxis = f
                    }
                }
                val dims = LongArray(4) { 1L }
                dims[channelAxis] = 2L
                dims[frameAxis] = FRAMES.toLong()
                dims[binAxis] = BINS.toLong()
                val strides = IntArray(4)
                var stride = 1
                for (axis in 3 downTo 0) {
                    strides[axis] = stride
                    stride *= dims[axis].toInt()
                }
                return TensorLayout(dims, strides[channelAxis], strides[frameAxis], strides[binAxis])
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 44_100
        const val N_FFT = 4096
        const val HOP = 1024
        const val FRAMES = 512
        const val BINS = 1024

        /** Frames of audio per block (about 11.9 s). */
        const val SEGMENT = FRAMES * HOP
        private const val HISTORY = N_FFT
        private const val EPSILON = 1e-10f

        /** 1 / Σ w² for a periodic Hann window at 75 % overlap. */
        private const val OVERLAP_SCALE = 1f / 1.5f

        private fun declaredShape(session: OrtSession): LongArray? =
            (session.inputInfo.values.firstOrNull()?.info as? TensorInfo)?.shape
    }
}
