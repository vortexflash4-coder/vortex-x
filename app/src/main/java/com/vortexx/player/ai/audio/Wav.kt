package com.vortexx.player.ai.audio

import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt

/** Decoded WAV: [samples] are interleaved, −1…1. */
class WavData(val sampleRate: Int, val channels: Int, val samples: FloatArray) {
    val frames: Int get() = if (channels == 0) 0 else samples.size / channels

    /** One channel per frame: the mean of the channels. */
    fun mono(): FloatArray {
        if (channels == 1) return samples
        val out = FloatArray(frames)
        for (i in out.indices) {
            var sum = 0f
            for (c in 0 until channels) sum += samples[i * channels + c]
            out[i] = sum / channels
        }
        return out
    }
}

object Wav {
    /** Reads a 16-bit PCM WAV file completely (meant for the short files this app writes). */
    fun read(file: File): WavData {
        val bytes = file.readBytes()
        val buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        if (bytes.size < 12 || String(bytes, 0, 4, Charsets.US_ASCII) != "RIFF" || String(bytes, 8, 4, Charsets.US_ASCII) != "WAVE") {
            throw IOException("Not a WAV file: ${file.name}")
        }
        var position = 12
        var sampleRate = 0
        var channels = 0
        var bits = 0
        var format = 0
        while (position + 8 <= bytes.size) {
            val id = String(bytes, position, 4, Charsets.US_ASCII)
            val size = buffer.getInt(position + 4)
            val body = position + 8
            if (id == "fmt ") {
                format = buffer.getShort(body).toInt() and 0xFFFF
                channels = buffer.getShort(body + 2).toInt()
                sampleRate = buffer.getInt(body + 4)
                bits = buffer.getShort(body + 14).toInt()
            } else if (id == "data") {
                // 1 = PCM, 0xFFFE = extensible (PCM inside for the files we meet).
                if ((format != 1 && format != 0xFFFE) || bits != 16 || channels <= 0) {
                    throw IOException("Only 16-bit PCM WAV is supported: ${file.name}")
                }
                val available = minOf(size.toLong() and 0xFFFFFFFFL, (bytes.size - body).toLong()).toInt()
                val count = available / 2
                val samples = FloatArray(count)
                for (i in 0 until count) samples[i] = buffer.getShort(body + 2 * i) / 32768f
                return WavData(sampleRate, channels, samples)
            }
            if (size < 0) break
            position = body + size + (size and 1)
        }
        throw IOException("No audio data in ${file.name}")
    }
}

/** Writes 16-bit PCM WAV; the header is completed on [close]. */
class WavWriter(file: File, private val sampleRate: Int, private val channels: Int) : Closeable {
    private val out = RandomAccessFile(file, "rw")
    private var dataBytes = 0L
    private var scratch = ByteArray(0)

    init {
        out.setLength(0)
        out.write(ByteArray(HEADER))
    }

    val framesWritten: Long get() = dataBytes / (2L * channels)

    /** Writes [count] interleaved samples (−1…1), clipping anything outside. */
    fun write(samples: FloatArray, count: Int) {
        if (scratch.size < count * 2) scratch = ByteArray(count * 2)
        for (i in 0 until count) {
            val v = (samples[i] * 32767f).roundToInt().coerceIn(-32768, 32767)
            scratch[2 * i] = v.toByte()
            scratch[2 * i + 1] = (v shr 8).toByte()
        }
        out.write(scratch, 0, count * 2)
        dataBytes += count * 2L
    }

    override fun close() {
        val header = ByteBuffer.allocate(HEADER).order(ByteOrder.LITTLE_ENDIAN)
        header.put("RIFF".toByteArray(Charsets.US_ASCII))
        header.putInt((36 + dataBytes).toInt())
        header.put("WAVE".toByteArray(Charsets.US_ASCII))
        header.put("fmt ".toByteArray(Charsets.US_ASCII))
        header.putInt(16)
        header.putShort(1)
        header.putShort(channels.toShort())
        header.putInt(sampleRate)
        header.putInt(sampleRate * channels * 2)
        header.putShort((channels * 2).toShort())
        header.putShort(16)
        header.put("data".toByteArray(Charsets.US_ASCII))
        header.putInt(dataBytes.toInt())
        out.seek(0)
        out.write(header.array())
        out.close()
    }

    private companion object {
        const val HEADER = 44
    }
}
