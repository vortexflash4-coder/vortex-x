package com.vortexx.player.audio.media3

import androidx.media3.common.C

/** Human-readable names for Media3 audio encodings (C.ENCODING_*). */
object AudioEncodings {

    fun name(encoding: Int): String = when (encoding) {
        C.ENCODING_PCM_8BIT -> "PCM 8-bit"
        C.ENCODING_PCM_16BIT -> "PCM 16-bit"
        C.ENCODING_PCM_24BIT -> "PCM 24-bit"
        C.ENCODING_PCM_32BIT -> "PCM 32-bit"
        C.ENCODING_PCM_FLOAT -> "PCM float"
        C.ENCODING_AC3 -> "AC-3"
        C.ENCODING_E_AC3 -> "E-AC-3"
        C.ENCODING_E_AC3_JOC -> "E-AC-3 JOC"
        C.ENCODING_AC4 -> "AC-4"
        C.ENCODING_DTS -> "DTS"
        C.ENCODING_DTS_HD -> "DTS-HD"
        C.ENCODING_DOLBY_TRUEHD -> "Dolby TrueHD"
        C.ENCODING_MP3 -> "MP3"
        C.ENCODING_AAC_LC -> "AAC-LC"
        C.ENCODING_INVALID -> "Invalid"
        else -> "Encoding $encoding"
    }

    fun isLinearPcm(encoding: Int): Boolean = when (encoding) {
        C.ENCODING_PCM_8BIT,
        C.ENCODING_PCM_16BIT,
        C.ENCODING_PCM_24BIT,
        C.ENCODING_PCM_32BIT,
        C.ENCODING_PCM_FLOAT,
        -> true
        else -> false
    }

    /** Bytes per sample for linear PCM, or 0 for encoded formats. */
    fun bytesPerSample(encoding: Int): Int = when (encoding) {
        C.ENCODING_PCM_8BIT -> 1
        C.ENCODING_PCM_16BIT -> 2
        C.ENCODING_PCM_24BIT -> 3
        C.ENCODING_PCM_32BIT, C.ENCODING_PCM_FLOAT -> 4
        else -> 0
    }

    /** Output buffer length in milliseconds for PCM, or null when it cannot be derived (encoded output). */
    fun bufferMillis(encoding: Int, bufferSizeBytes: Int, sampleRate: Int, channelCount: Int): Int? {
        val bytes = bytesPerSample(encoding)
        if (bytes == 0 || bufferSizeBytes <= 0 || sampleRate <= 0 || channelCount <= 0) return null
        val frames = bufferSizeBytes.toLong() / (bytes.toLong() * channelCount)
        return (frames * 1000 / sampleRate).toInt()
    }
}
