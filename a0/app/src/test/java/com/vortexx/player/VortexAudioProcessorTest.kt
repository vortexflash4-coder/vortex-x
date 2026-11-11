package com.vortexx.player

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.vortexx.player.audio.dsp.DspHost
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.LoadMeter
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.dsp.ProcessorFormat
import com.vortexx.player.audio.media3.AudioEncodings
import com.vortexx.player.audio.media3.VortexAudioProcessor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

/** The Media3 processor around the DSP chain: formats, activation rules and the PCM conversion. */
@Suppress("DEPRECATION") // AudioProcessor.flush() is deprecated in Media3 1.11 in favour of flush(StreamMetadata).
class VortexAudioProcessorTest {

    private class FakeHost(
        override var parameters: DspParameters = DspParameters(),
        override var processingAllowed: Boolean = true,
    ) : DspHost {
        override val meters = MeterBus()
        override val load = LoadMeter()
        var lastFormat: ProcessorFormat? = null
        var lastReason: String? = null
        override fun onProcessorConfigured(format: ProcessorFormat?, reason: String?) {
            lastFormat = format
            lastReason = reason
        }
    }

    private fun pcm16(frames: Int, channels: Int, sampleRate: Int, freq: Double, amplitude: Double): ByteBuffer {
        val buffer = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (i in 0 until frames) {
            val v = (amplitude * 32767 * sin(2 * PI * freq * i / sampleRate)).toInt().toShort()
            for (c in 0 until channels) buffer.putShort(v)
        }
        buffer.flip()
        return buffer
    }

    private fun processAll(processor: VortexAudioProcessor, input: ByteBuffer): ByteBuffer {
        val copy = ByteBuffer.allocate(input.remaining()).order(ByteOrder.nativeOrder())
        while (input.hasRemaining()) {
            val before = input.position()
            processor.queueInput(input)
            copy.put(processor.getOutput())
            check(input.position() > before) { "No progress on complete PCM frames" }
        }
        copy.flip()
        return copy
    }

    private fun start(processor: VortexAudioProcessor, sampleRate: Int, channels: Int, encoding: Int): AudioProcessor.AudioFormat {
        val result = processor.configure(AudioProcessor.AudioFormat(sampleRate, channels, encoding))
        processor.flush()
        return result
    }

    @Test
    fun flat16BitStereoIsBitExact() {
        val host = FakeHost()
        val processor = VortexAudioProcessor(host)
        start(processor, 48_000, 2, C.ENCODING_PCM_16BIT)
        assertTrue(processor.isActive)
        val input = pcm16(2048, 2, 48_000, 997.0, 0.8)
        val reference = input.duplicate().order(ByteOrder.nativeOrder())
        val output = processAll(processor, input)
        assertEquals(reference.remaining(), output.remaining())
        while (reference.hasRemaining()) assertEquals(reference.getShort(), output.getShort())
        assertEquals(ProcessorFormat(48_000, 2, "PCM 16-bit"), host.lastFormat)
    }

    @Test
    fun floatPcmPassesThroughFlatExactly() {
        val processor = VortexAudioProcessor(FakeHost())
        start(processor, 96_000, 1, C.ENCODING_PCM_FLOAT)
        val input = ByteBuffer.allocateDirect(1024 * 4).order(ByteOrder.nativeOrder())
        for (i in 0 until 1024) input.putFloat((0.5 * sin(2 * PI * 440.0 * i / 96_000)).toFloat())
        input.flip()
        val reference = input.duplicate().order(ByteOrder.nativeOrder())
        val output = processAll(processor, input)
        while (reference.hasRemaining()) assertEquals(reference.getFloat(), output.getFloat(), 0f)
    }

    @Test
    fun eqBoostChangesThe16BitOutput() {
        val host = FakeHost(parameters = DspParameters().withBand(5, 6f))
        val processor = VortexAudioProcessor(host)
        start(processor, 44_100, 2, C.ENCODING_PCM_16BIT)
        val input = pcm16(44_100, 2, 44_100, 1000.0, 0.2)
        val output = processAll(processor, input)
        var peak = 0
        while (output.hasRemaining()) peak = maxOf(peak, kotlin.math.abs(output.getShort().toInt()))
        // 0.2 full scale boosted by +6 dB is about 0.4 full scale (13 100 of 32 767).
        assertTrue("peak $peak", peak in 12_000..14_500)
    }

    @Test
    fun unsupportedEncodingLeavesProcessorInactiveWithReason() {
        val host = FakeHost()
        val processor = VortexAudioProcessor(host)
        val result = start(processor, 48_000, 2, C.ENCODING_PCM_24BIT)
        assertEquals(AudioProcessor.AudioFormat.NOT_SET, result)
        assertFalse(processor.isActive)
        assertNull(host.lastFormat)
        assertTrue(host.lastReason!!.contains("16-bit or float"))
    }

    @Test
    fun batteryModeKeepsPcmAdapterAvailableButCopiesBytes() {
        val host = FakeHost(processingAllowed = false)
        val processor = VortexAudioProcessor(host)
        start(processor, 48_000, 2, C.ENCODING_PCM_16BIT)
        assertTrue(processor.isActive)
        val input = pcm16(2048, 2, 48_000, 1000.0, 0.6)
        val reference = input.duplicate().order(ByteOrder.nativeOrder())
        val output = processAll(processor, input)
        assertEquals(reference, output)
    }

    @Test
    fun formatChangeFrom44kStereoTo96kMonoKeepsWorking() {
        val host = FakeHost(parameters = DspParameters().withBand(5, 3f))
        val processor = VortexAudioProcessor(host)
        start(processor, 44_100, 2, C.ENCODING_PCM_16BIT)
        assertEquals(1024 * 2 * 2, processAll(processor, pcm16(1024, 2, 44_100, 1000.0, 0.1)).remaining())

        start(processor, 96_000, 1, C.ENCODING_PCM_16BIT)
        assertTrue(processor.isActive)
        assertEquals(1024 * 2, processAll(processor, pcm16(1024, 1, 96_000, 1000.0, 0.1)).remaining())
        assertNotNull(host.lastFormat)
        assertEquals(96_000, host.lastFormat!!.sampleRate)
        assertEquals(1, host.lastFormat!!.channelCount)
    }

    @Test
    fun batterySwitchBypassesNextChunkWithoutReconfigureAndResumes() {
        for (encoding in intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val host = FakeHost(parameters = DspParameters().withBand(5, 6f))
            val processor = VortexAudioProcessor(host)
            start(processor, 48_000, 1, encoding)
            fun signal(): ByteBuffer {
                if (encoding == C.ENCODING_PCM_16BIT) return pcm16(4096, 1, 48_000, 1000.0, 0.2)
                return ByteBuffer.allocateDirect(4096 * 4).order(ByteOrder.nativeOrder()).apply {
                    repeat(4096) { putFloat((0.2 * sin(2 * PI * 1000 * it / 48_000)).toFloat()) }
                    flip()
                }
            }
            val original = signal()
            assertFalse(original == processAll(processor, signal()))
            // No configure/flush: exactly the unsupported-offload PCM fallback.
            host.processingAllowed = false
            assertEquals(original, processAll(processor, signal()))
            host.parameters = host.parameters.withBand(4, 12f)
            assertEquals(original, processAll(processor, signal()))
            host.processingAllowed = true
            assertFalse(original == processAll(processor, signal()))
            processor.reset()
            host.processingAllowed = false
            start(processor, 48_000, 1, encoding)
            assertEquals(original, processAll(processor, signal()))
            host.processingAllowed = true
            assertFalse(original == processAll(processor, signal()))
        }
    }

    @Test
    fun largeBuffersUseBoundedChunksAndReuseOutputWithoutDroppingFrames() {
        val processor = VortexAudioProcessor(FakeHost())
        for (channels in intArrayOf(1, 2, 8)) {
            start(processor, 48_000, channels, C.ENCODING_PCM_16BIT)
            val input = pcm16(10_137, channels, 48_000, 997.0, 0.8)
            val original = input.duplicate()
            var previousOutput: ByteBuffer? = null
            var bytes = 0
            while (input.hasRemaining()) {
                processor.queueInput(input)
                val position = input.position()
                processor.queueInput(input) // Pending output must not be overwritten.
                assertEquals(position, input.position())
                val out = processor.getOutput()
                assertTrue(out.remaining() <= VortexAudioProcessor.MAX_FRAMES * channels * 2)
                if (previousOutput != null) org.junit.Assert.assertSame(previousOutput, out)
                previousOutput = out
                bytes += out.remaining()
                while (out.hasRemaining()) assertEquals(original.get(), out.get())
            }
            assertEquals(10_137 * channels * 2, bytes)
            processor.queueEndOfStream()
            assertTrue(processor.isEnded)
            processor.reset()
        }
    }

    @Test
    fun floatOutputCeilingProtectsSimultaneousBoostsAndCountsOvers() {
        val host = FakeHost(parameters = DspParameters(bandGainsDb = List(10) { 12f }))
        val processor = VortexAudioProcessor(host)
        start(processor, 48_000, 1, C.ENCODING_PCM_FLOAT)
        val input = ByteBuffer.allocateDirect(48_000 * 4).order(ByteOrder.nativeOrder())
        repeat(48_000) { input.putFloat((0.99 * sin(2 * PI * 707 * it / 48_000)).toFloat()) }
        input.flip()
        val output = processAll(processor, input)
        while (output.hasRemaining()) {
            val value = output.getFloat()
            assertTrue(value.isFinite() && value in -1f..1f)
        }
        val reading = com.vortexx.player.audio.dsp.MeterReading()
        host.meters.drain(reading, System.nanoTime())
        assertTrue(reading.clippedTotal > 0)
    }

    @Test
    fun processorPathDoesNotAllocateAcrossSizesFormatsAndModeChanges() {
        val counter = checkNotNull(AllocationCounter.create()) { "Allocation counter unavailable: gate NOT measured" }
        check(counter.bytes() >= 0) { "Allocation counter disabled" }
        val presets = arrayOf(DspParameters().withBand(5, 6f), DspParameters().withBand(4, -3f))
        for (encoding in intArrayOf(C.ENCODING_PCM_16BIT, C.ENCODING_PCM_FLOAT)) {
            val host = FakeHost(parameters = presets[0])
            val processor = VortexAudioProcessor(host)
            start(processor, 96_000, 8, encoding)
            val frameBytes = 8 * if (encoding == C.ENCODING_PCM_FLOAT) 4 else 2
            val input = ByteBuffer.allocateDirect(4097 * frameBytes).order(ByteOrder.nativeOrder())
            repeat(4097 * 8) { i ->
                val sample = (0.1 * sin(2 * PI * 997 * (i / 8) / 96_000)).toFloat()
                if (encoding == C.ENCODING_PCM_FLOAT) input.putFloat(sample)
                else input.putShort((sample * 32767).toInt().toShort())
            }
            input.flip()
            // Include native/direct buffer counts: heap accounting alone would miss native growth.
            val pool = DirectBufferCounter.create()
            repeat(1000) {
                input.clear()
                while (input.hasRemaining()) { processor.queueInput(input); val out = processor.getOutput(); out.position(out.limit()) }
            }
            val nativeBytes = pool.memoryUsed
            val nativeCount = pool.count
            val before = counter.bytes()
            repeat(5000) { iteration ->
                input.clear().limit((if (iteration % 2 == 0) 4097 else 17) * frameBytes)
                host.processingAllowed = iteration % 7 != 0
                host.parameters = presets[iteration % 2]
                while (input.hasRemaining()) { processor.queueInput(input); val out = processor.getOutput(); out.position(out.limit()) }
            }
            val allocated = (counter.bytes() - before - counter.overheadBytes()).coerceAtLeast(0)
            assertEquals(nativeBytes, pool.memoryUsed)
            assertEquals(nativeCount, pool.count)
            println("Processor allocation encoding=$encoding over 5000 buffers: $allocated heap bytes; direct growth=0")
            assertTrue("processor allocated $allocated bytes", allocated < 1024)
        }
    }

    @Test
    fun outputBufferMillisecondsFromTrackConfig() {
        // 48 kHz stereo 16-bit: 192 000 bytes per second, so 96 000 bytes = 500 ms.
        assertEquals(500, AudioEncodings.bufferMillis(C.ENCODING_PCM_16BIT, 96_000, 48_000, 2))
        assertNull(AudioEncodings.bufferMillis(C.ENCODING_E_AC3, 96_000, 48_000, 6))
        assertTrue(AudioEncodings.isLinearPcm(C.ENCODING_PCM_FLOAT))
        assertFalse(AudioEncodings.isLinearPcm(C.ENCODING_AC3))
    }
}

/** Android's compile SDK omits java.lang.management; access JVM counters reflectively. */
private class DirectBufferCounter(private val bean: Any, private val type: Class<*>) {
    private val bytesMethod = type.getMethod("getMemoryUsed")
    private val countMethod = type.getMethod("getCount")
    val memoryUsed: Long get() = bytesMethod.invoke(bean) as Long
    val count: Long get() = countMethod.invoke(bean) as Long

    companion object {
        fun create(): DirectBufferCounter {
            val factory = Class.forName("java.lang.management.ManagementFactory")
            val type = Class.forName("java.lang.management.BufferPoolMXBean")
            val pools = factory.getMethod("getPlatformMXBeans", Class::class.java).invoke(null, type) as List<*>
            val bean = pools.first { type.getMethod("getName").invoke(it) == "direct" }
            return DirectBufferCounter(checkNotNull(bean), type)
        }
    }
}
