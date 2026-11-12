package com.vortexx.player.ai.dsp

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.sin

/** In-place radix-2 complex FFT of one fixed power-of-two size. Not thread-safe. */
class Fft(val size: Int) {
    private val cosTable = FloatArray(size / 2)
    private val sinTable = FloatArray(size / 2)
    private val reversed = IntArray(size)

    init {
        require(size >= 2 && (size and (size - 1)) == 0) { "FFT size must be a power of two" }
        for (i in 0 until size / 2) {
            val angle = -2.0 * PI * i / size
            cosTable[i] = cos(angle).toFloat()
            sinTable[i] = sin(angle).toFloat()
        }
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) reversed[i] = Integer.reverse(i) ushr (32 - bits)
    }

    fun forward(re: FloatArray, im: FloatArray) = transform(re, im, inverse = false)

    /** Inverse transform, including the 1/N scale. */
    fun inverse(re: FloatArray, im: FloatArray) {
        transform(re, im, inverse = true)
        val scale = 1f / size
        for (i in 0 until size) {
            re[i] *= scale
            im[i] *= scale
        }
    }

    private fun transform(re: FloatArray, im: FloatArray, inverse: Boolean) {
        val n = size
        for (i in 0 until n) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]
                re[i] = re[j]
                re[j] = tr
                val ti = im[i]
                im[i] = im[j]
                im[j] = ti
            }
        }
        var length = 2
        while (length <= n) {
            val half = length / 2
            val tableStep = n / length
            var start = 0
            while (start < n) {
                var k = 0
                for (j in start until start + half) {
                    val wr = cosTable[k]
                    val wi = if (inverse) -sinTable[k] else sinTable[k]
                    val xr = re[j + half] * wr - im[j + half] * wi
                    val xi = re[j + half] * wi + im[j + half] * wr
                    re[j + half] = re[j] - xr
                    im[j + half] = im[j] - xi
                    re[j] += xr
                    im[j] += xi
                    k += tableStep
                }
                start += length
            }
            length = length shl 1
        }
    }
}

/**
 * Streaming windowed-sinc resampler for one channel. Feed input with
 * [process], finish with [flush]. The low-pass sits just below the lower of
 * the two Nyquist frequencies, so 44.1 kHz music can be taken down to 16 kHz
 * for speech recognition without aliasing.
 */
class Resampler(inRate: Int, outRate: Int, quality: Int = 16) {
    private val step = inRate.toDouble() / outRate
    private val cutoff = minOf(1.0, outRate.toDouble() / inRate) * 0.92
    private val halfWidth = ceil(quality / cutoff).toInt()
    private val table = FloatArray(halfWidth * PHASES + 2)
    private var buffer = FloatArray(8192)
    private var bufferStart = 0L
    private var bufferLength = 0
    private var outIndex = 0L
    private var totalIn = 0L

    init {
        for (i in table.indices) {
            val t = i.toDouble() / PHASES
            val x = PI * cutoff * t
            val sinc = if (i == 0) 1.0 else sin(x) / x
            val window = if (t >= halfWidth) 0.0 else 0.5 + 0.5 * cos(PI * t / halfWidth)
            table[i] = (cutoff * sinc * window).toFloat()
        }
    }

    /** Adds [count] input samples and returns the output samples that are now complete. */
    fun process(input: FloatArray, count: Int): FloatArray {
        if (bufferLength + count > buffer.size) buffer = buffer.copyOf(maxOf(buffer.size * 2, bufferLength + count))
        System.arraycopy(input, 0, buffer, bufferLength, count)
        bufferLength += count
        totalIn += count
        return drain(final = false)
    }

    /** Returns the remaining output once all input has been given. */
    fun flush(): FloatArray = drain(final = true)

    private fun drain(final: Boolean): FloatArray {
        val end = bufferStart + bufferLength
        var out = FloatArray((bufferLength / step).toInt() + 4)
        var produced = 0
        while (true) {
            val position = outIndex * step
            val centre = floor(position).toLong()
            if (final) {
                if (position >= totalIn) break
            } else if (centre + halfWidth >= end) {
                break
            }
            var sum = 0.0
            var norm = 0.0
            var i = centre - halfWidth + 1
            val last = centre + halfWidth
            while (i <= last) {
                val distance = abs(position - i) * PHASES
                val k = distance.toInt()
                if (k < table.size - 1) {
                    val h = table[k] + (table[k + 1] - table[k]) * (distance - k).toFloat()
                    norm += h
                    if (i >= bufferStart && i < end) sum += buffer[(i - bufferStart).toInt()] * h
                }
                i++
            }
            if (produced == out.size) out = out.copyOf(out.size * 2 + 16)
            out[produced++] = if (norm != 0.0) (sum / norm).toFloat() else 0f
            outIndex++
        }
        val keepFrom = maxOf(bufferStart, floor(outIndex * step).toLong() - halfWidth + 1)
        val drop = (keepFrom - bufferStart).toInt().coerceIn(0, bufferLength)
        if (drop > 0) {
            System.arraycopy(buffer, drop, buffer, 0, bufferLength - drop)
            bufferLength -= drop
            bufferStart += drop
        }
        return if (produced == out.size) out else out.copyOf(produced)
    }

    private companion object {
        const val PHASES = 256
    }
}
