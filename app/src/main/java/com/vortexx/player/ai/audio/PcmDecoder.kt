package com.vortexx.player.ai.audio

import android.content.Context
import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.net.Uri
import java.io.IOException
import java.nio.ByteOrder
import kotlin.coroutines.cancellation.CancellationException
import kotlin.math.roundToInt

/**
 * Decodes the audio of a file to 16-bit PCM with the phone's own decoders
 * (MediaExtractor + MediaCodec). Blocking: call it from a background thread.
 */
class PcmDecoder(private val context: Context) {

    class Format(val sampleRate: Int, val channels: Int, val durationUs: Long)

    /**
     * Decodes the first audio track of [uri]. [onFormat] is called once before
     * the first samples; [onPcm] receives interleaved samples (array, count).
     * Throws [IOException] when the file has no audio the phone can decode.
     */
    fun decode(
        uri: Uri,
        isCancelled: () -> Boolean,
        onFormat: (Format) -> Unit,
        onPcm: (ShortArray, Int) -> Unit,
    ) {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(context, uri, null)
            var track = -1
            for (i in 0 until extractor.trackCount) {
                val mime = extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)
                if (mime != null && mime.startsWith("audio/")) {
                    track = i
                    break
                }
            }
            if (track < 0) throw IOException("This file has no audio track")
            extractor.selectTrack(track)
            val input = extractor.getTrackFormat(track)
            val mime = input.getString(MediaFormat.KEY_MIME) ?: throw IOException("Unknown audio format")
            val durationUs = if (input.containsKey(MediaFormat.KEY_DURATION)) input.getLong(MediaFormat.KEY_DURATION) else -1L
            val codec = try {
                MediaCodec.createDecoderByType(mime)
            } catch (e: Exception) {
                throw IOException("This phone has no decoder for $mime", e)
            }
            try {
                codec.configure(input, null, null, 0)
                codec.start()
                val info = MediaCodec.BufferInfo()
                var sampleRate = input.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                var channels = input.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var announced = false
                var inputDone = false
                var outputDone = false
                var samples = ShortArray(0)
                while (!outputDone) {
                    if (isCancelled()) throw CancellationException("Cancelled")
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(TIMEOUT_US)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index) ?: throw IOException("Decoder gave no input buffer")
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0L, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, TIMEOUT_US)
                    if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        val output = codec.outputFormat
                        sampleRate = output.getInteger(MediaFormat.KEY_SAMPLE_RATE)
                        channels = output.getInteger(MediaFormat.KEY_CHANNEL_COUNT)
                        encoding = if (output.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                            output.getInteger(MediaFormat.KEY_PCM_ENCODING)
                        } else {
                            AudioFormat.ENCODING_PCM_16BIT
                        }
                    } else if (index >= 0) {
                        if (!announced) {
                            onFormat(Format(sampleRate, channels, durationUs))
                            announced = true
                        }
                        if (info.size > 0) {
                            val buffer = codec.getOutputBuffer(index) ?: throw IOException("Decoder gave no output buffer")
                            buffer.position(info.offset)
                            buffer.limit(info.offset + info.size)
                            buffer.order(ByteOrder.nativeOrder())
                            val count: Int
                            if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                                val floats = buffer.asFloatBuffer()
                                count = floats.remaining()
                                if (samples.size < count) samples = ShortArray(count)
                                for (i in 0 until count) {
                                    samples[i] = (floats.get(i) * 32767f).roundToInt().coerceIn(-32768, 32767).toShort()
                                }
                            } else {
                                val shorts = buffer.asShortBuffer()
                                count = shorts.remaining()
                                if (samples.size < count) samples = ShortArray(count)
                                shorts.get(samples, 0, count)
                            }
                            onPcm(samples, count)
                        }
                        codec.releaseOutputBuffer(index, false)
                        if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                    }
                }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
        } finally {
            extractor.release()
        }
    }

    private companion object {
        const val TIMEOUT_US = 10_000L
    }
}
