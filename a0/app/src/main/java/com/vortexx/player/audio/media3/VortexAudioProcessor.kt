package com.vortexx.player.audio.media3

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import com.vortexx.player.audio.dsp.DspChain
import com.vortexx.player.audio.dsp.DspHost
import com.vortexx.player.audio.dsp.ProcessorFormat
import java.nio.ByteBuffer

/**
 * VORTEX X's DSP inside Media3's PCM pipeline (DefaultAudioSink). Accepts
 * 16-bit and float PCM at any sample rate, 1–8 channels, and returns the same
 * format, so nothing else in the sink changes. For any other input it reports
 * itself inactive and audio passes untouched; the reason is published at flush so the
 * Audio Lab never shows processing that is not happening.
 *
 * Media3 does not route encoded passthrough or offloaded audio through audio
 * processors at all; the playback engine reports those modes separately.
 */
@UnstableApi
class VortexAudioProcessor(private val host: DspHost) : BaseAudioProcessor() {

    private val chain = DspChain(host.meters)
    // Bounded scratch space: large decoder buffers are consumed in chunks. Never
    // resize on queueInput, even on the first buffer or after a format change.
    private val work = FloatArray(MAX_FRAMES * DspChain.MAX_CHANNELS)
    private var batteryBypassed = false

    private var pendingFormat: AudioProcessor.AudioFormat? = null
    private var pendingReason: String? = null

    private var activeEncoding = C.ENCODING_INVALID
    private var activeSampleRate = 0
    private var activeChannels = 0

    override fun onConfigure(inputAudioFormat: AudioProcessor.AudioFormat): AudioProcessor.AudioFormat {
        // Keep the PCM adapter in the chain in battery mode. Unsupported offload
        // may leave the same PCM sink open; a configure-only gate would get stuck
        // either processing or bypassed when the preference changes.
        val encoding = inputAudioFormat.encoding
        if (encoding != C.ENCODING_PCM_16BIT && encoding != C.ENCODING_PCM_FLOAT) {
            return inactive("DSP needs 16-bit or float PCM; stream is ${AudioEncodings.name(encoding)}")
        }
        if (inputAudioFormat.channelCount !in 1..DspChain.MAX_CHANNELS) {
            return inactive("DSP supports 1–${DspChain.MAX_CHANNELS} channels; stream has ${inputAudioFormat.channelCount}")
        }
        if (inputAudioFormat.sampleRate <= 0) {
            return inactive("Unknown sample rate")
        }
        pendingFormat = inputAudioFormat
        pendingReason = null
        return inputAudioFormat
    }

    private fun inactive(reason: String): AudioProcessor.AudioFormat {
        pendingFormat = null
        pendingReason = reason
        return AudioProcessor.AudioFormat.NOT_SET
    }

    // Media3 1.11 also offers onFlush(StreamMetadata); its default implementation calls this one.
    @Suppress("OVERRIDE_DEPRECATION", "DEPRECATION")
    override fun onFlush() {
        // BaseAudioProcessor grows its direct buffer here, never in queueInput.
        // reset() releases that buffer, so reserve again after every flush.
        replaceOutputBuffer(MAX_OUTPUT_BYTES).limit(0)
        batteryBypassed = false
        val format = pendingFormat
        if (format == null) {
            activeEncoding = C.ENCODING_INVALID
            activeSampleRate = 0
            activeChannels = 0
            host.onProcessorConfigured(null, pendingReason)
            return
        }
        if (format.sampleRate != chain.sampleRate || format.channelCount != chain.channelCount) {
            chain.configure(format.sampleRate, format.channelCount)
        } else {
            chain.resetState()
        }
        activeEncoding = format.encoding
        activeSampleRate = format.sampleRate
        activeChannels = format.channelCount
        host.onProcessorConfigured(
            ProcessorFormat(format.sampleRate, format.channelCount, AudioEncodings.name(format.encoding)),
            null,
        )
    }

    override fun onReset() {
        pendingFormat = null
        pendingReason = null
        activeEncoding = C.ENCODING_INVALID
        activeSampleRate = 0
        activeChannels = 0
        batteryBypassed = false
        chain.resetState()
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0 || hasPendingOutput()) return
        val channels = activeChannels
        check(channels > 0) { "queueInput requires a supported, flushed PCM format" }
        val started = System.nanoTime()
        val isFloat = activeEncoding == C.ENCODING_PCM_FLOAT
        val bytesPerSample = if (isFloat) 4 else 2
        val frames = minOf(MAX_FRAMES, remaining / (bytesPerSample * channels))
        if (frames == 0) return
        val samples = frames * channels
        val output = replaceOutputBuffer(samples * bytesPerSample)
        if (!host.processingAllowed) {
            // Applies at the very next chunk, regardless of offload support or
            // reconfiguration. Already queued AudioTrack samples cannot be undone.
            if (!batteryBypassed) chain.resetState()
            batteryBypassed = true
            val originalLimit = inputBuffer.limit()
            inputBuffer.limit(inputBuffer.position() + samples * bytesPerSample)
            output.put(inputBuffer)
            inputBuffer.limit(originalLimit)
            output.flip()
            return
        }
        val buffer = work

        if (isFloat) {
            for (i in 0 until samples) buffer[i] = inputBuffer.getFloat()
        } else {
            for (i in 0 until samples) buffer[i] = inputBuffer.getShort() * INT16_TO_FLOAT
        }

        chain.apply(host.parameters)
        if (batteryBypassed) {
            chain.resumeFromDry()
            batteryBypassed = false
        }
        chain.process(buffer, frames, started)

        if (isFloat) {
            for (i in 0 until samples) output.putFloat(buffer[i])
        } else {
            for (i in 0 until samples) {
                val value = Math.round(buffer[i] * FLOAT_TO_INT16)
                val clamped = if (value > Short.MAX_VALUE) Short.MAX_VALUE.toInt() else if (value < Short.MIN_VALUE) Short.MIN_VALUE.toInt() else value
                output.putShort(clamped.toShort())
            }
        }
        output.flip()
        host.load.record(System.nanoTime() - started, frames, activeSampleRate)
    }

    companion object {
        const val MAX_FRAMES = 1024
        private const val MAX_OUTPUT_BYTES = MAX_FRAMES * DspChain.MAX_CHANNELS * 4
        const val FLOAT_TO_INT16 = 32768f
        const val INT16_TO_FLOAT = 1f / 32768f
    }
}
