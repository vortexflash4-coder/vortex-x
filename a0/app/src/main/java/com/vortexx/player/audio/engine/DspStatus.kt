package com.vortexx.player.audio.engine

/** Why the DSP is or is not touching the samples being played right now. */
enum class DspStatusKind { Active, BypassedByUser, Passthrough, Offload, BatteryMode, FormatNotSupported, Idle }

data class DspStatus(val kind: DspStatusKind, val label: String, val detail: String) {
    /** True only when processed samples are actually reaching the output. */
    val isProcessing: Boolean get() = kind == DspStatusKind.Active
}

/**
 * Decides the status shown in the Audio Lab from what Media3 reports about
 * the opened output and what the processor reports about itself. The DSP is
 * shown as active only for PCM output with an active processor.
 */
object DspStatusResolver {

    fun resolve(
        outputOpen: Boolean,
        outputIsPcm: Boolean,
        outputOffload: Boolean,
        processorActive: Boolean,
        processorReason: String?,
        userEnabled: Boolean,
        mode: OutputModePreference,
    ): DspStatus = when {
        !outputOpen -> DspStatus(DspStatusKind.Idle, "Idle", "No audio output is open. Play something to see the live path.")
        outputOffload -> DspStatus(
            DspStatusKind.Offload,
            "Bypassed: offload",
            "The hardware decodes and plays this stream. Media3 runs no audio processors in offload, so the Audio Lab is not applied.",
        )
        !outputIsPcm -> DspStatus(
            DspStatusKind.Passthrough,
            "Bypassed: passthrough",
            "Encoded audio goes to the output unchanged, so the Audio Lab is not applied.",
        )
        mode == OutputModePreference.Offload -> DspStatus(
            DspStatusKind.BatteryMode,
            "Off: battery mode",
            "PCM fallback: DSP is bypassed from the next processing block. Already queued output may still be audible briefly.",
        )
        !processorActive -> DspStatus(
            DspStatusKind.FormatNotSupported,
            "Not applied",
            processorReason ?: "The processor is not active for this stream.",
        )
        !userEnabled -> DspStatus(
            DspStatusKind.BypassedByUser,
            "Bypassed",
            "The Audio Lab is switched off. Samples pass through unchanged (bit-exact for 16-bit PCM).",
        )
        else -> DspStatus(DspStatusKind.Active, "Active", "Preamp and 10-band EQ are processing the samples you hear.")
    }
}
