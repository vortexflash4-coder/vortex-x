package com.vortexx.player.audio.dsp

/**
 * What the audio-thread processor needs from the rest of the app. Every
 * member must be safe to call from the playback thread without blocking.
 */
interface DspHost {
    /** Latest parameter snapshot (a volatile read). */
    val parameters: DspParameters

    /** Volatile gate checked each PCM chunk; false copies bytes without running DSP. */
    val processingAllowed: Boolean

    val meters: MeterBus
    val load: LoadMeter

    /** Called when the processor (de)activates for a stream format; [format] null = not processing. */
    fun onProcessorConfigured(format: ProcessorFormat?, reason: String?)
}

/** The PCM format the processor is working on. */
data class ProcessorFormat(
    val sampleRate: Int,
    val channelCount: Int,
    val encodingName: String,
)
