package com.vortexx.player.audio.dsp

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The latest output samples (the mono mix of what is sent to the speaker), written by the audio thread after the
 * whole chain and read by the screen. The writer never waits and never allocates; the reader copies what is
 * there, so a reading can at worst mix two neighbouring buffers, which a spectrum picture does not show.
 */
class SpectrumBus {
    private val ring = FloatArray(SIZE)
    private var position = 0

    @Volatile
    private var published = 0

    /** The sample rate of the samples, set when the chain is configured. */
    @Volatile
    var sampleRate: Int = 48_000

    /** Audio thread: appends [frames] frames of [buffer] (interleaved, [channels] wide) as one mono signal. */
    fun write(buffer: FloatArray, frames: Int, channels: Int) {
        var pos = position
        if (channels == 1) {
            for (i in 0 until frames) {
                ring[pos and MASK] = buffer[i]
                pos++
            }
        } else {
            for (i in 0 until frames) {
                val b = i * channels
                ring[pos and MASK] = (buffer[b] + buffer[b + 1]) * 0.5f
                pos++
            }
        }
        position = pos
        published = pos
    }

    /** Copies the newest `out.size` samples, oldest first. Samples that have not been written yet read as 0. */
    fun latest(out: FloatArray) {
        val end = published
        val start = end - out.size
        for (i in out.indices) {
            val index = start + i
            out[i] = if (index < 0) 0f else ring[index and MASK]
        }
    }

    companion object {
        const val SIZE = 8192
        private const val MASK = SIZE - 1
    }
}

/**
 * A real spectrum analyzer: a Hann-windowed FFT of the latest [size] output samples, shown in [bands]
 * logarithmic bands from 20 Hz to 20 kHz. A full-scale sine reads 0 dB. Holds its working memory, so one analyzer
 * can be reused for every screen refresh without allocating.
 */
class SpectrumAnalyzer(private val size: Int = 2048, val bands: Int = 48) {

    init {
        require(size >= 64 && size and (size - 1) == 0) { "size must be a power of two" }
    }

    private val window = DoubleArray(size) { 0.5 - 0.5 * cos(2.0 * PI * it / size) }
    private val windowSum = window.sum()
    private val re = DoubleArray(size)
    private val im = DoubleArray(size)
    private val samples = FloatArray(size)
    private val cosTable = DoubleArray(size / 2) { cos(2.0 * PI * it / size) }
    private val sinTable = DoubleArray(size / 2) { sin(2.0 * PI * it / size) }
    private val reversed = IntArray(size).also { table ->
        val bits = Integer.numberOfTrailingZeros(size)
        for (i in 0 until size) table[i] = Integer.reverse(i) ushr (32 - bits)
    }
    private val magnitude = DoubleArray(size / 2 + 1)

    /** Lower edge of band [i] in Hz; band [bands] has the upper edge 20 kHz. */
    fun edgeHz(i: Int): Double = LOW_HZ * (HIGH_HZ / LOW_HZ).pow(i.toDouble() / bands)

    /** Geometric centre of band [i] in Hz. */
    fun centreHz(i: Int): Double = sqrt(edgeHz(i) * edgeHz(i + 1))

    /** Fills [outDb] (length [bands]) with the level of each band in dBFS (−100 = nothing), from the bus's newest samples. */
    fun analyze(bus: SpectrumBus, outDb: FloatArray) {
        bus.latest(samples)
        analyze(samples, bus.sampleRate, outDb)
    }

    /** The same for [input] (at least [size] samples; the newest [size] are used). */
    fun analyze(input: FloatArray, sampleRate: Int, outDb: FloatArray) {
        val offset = input.size - size
        for (i in 0 until size) {
            re[i] = (if (offset + i >= 0) input[offset + i] else 0f) * window[i]
            im[i] = 0.0
        }
        transform()
        val scale = 2.0 / windowSum
        for (k in 0..size / 2) magnitude[k] = sqrt(re[k] * re[k] + im[k] * im[k]) * scale
        val binHz = sampleRate.toDouble() / size
        for (band in 0 until bands) {
            val low = edgeHz(band)
            val high = edgeHz(band + 1)
            val first = ceil(low / binHz).toInt()
            val last = floor(high / binHz).toInt().let { if (high / binHz == it.toDouble()) it - 1 else it }
            var level = 0.0
            if (low >= sampleRate / 2.0) {
                level = 0.0
            } else if (first <= last) {
                for (k in max(first, 1)..min(last, size / 2)) if (magnitude[k] > level) level = magnitude[k]
            } else {
                // The band is narrower than a bin: read between the two nearest bins.
                val position = sqrt(low * high) / binHz
                val k = floor(position).toInt().coerceIn(0, size / 2 - 1)
                val t = position - k
                level = magnitude[k] * (1 - t) + magnitude[k + 1] * t
            }
            outDb[band] = if (level < FLOOR_LINEAR) FLOOR_DB else (20.0 * log10(level)).toFloat()
        }
    }

    private fun transform() {
        for (i in 0 until size) {
            val j = reversed[i]
            if (j > i) {
                val tr = re[i]; re[i] = re[j]; re[j] = tr
                val ti = im[i]; im[i] = im[j]; im[j] = ti
            }
        }
        var length = 2
        while (length <= size) {
            val half = length / 2
            val step = size / length
            var i = 0
            while (i < size) {
                for (k in 0 until half) {
                    val wr = cosTable[k * step]
                    val wi = -sinTable[k * step]
                    val a = i + k
                    val b = a + half
                    val tr = re[b] * wr - im[b] * wi
                    val ti = re[b] * wi + im[b] * wr
                    re[b] = re[a] - tr
                    im[b] = im[a] - ti
                    re[a] += tr
                    im[a] += ti
                }
                i += length
            }
            length *= 2
        }
    }

    companion object {
        const val LOW_HZ = 20.0
        const val HIGH_HZ = 20_000.0
        const val FLOOR_DB = -100f
        private const val FLOOR_LINEAR = 1e-5
    }
}
