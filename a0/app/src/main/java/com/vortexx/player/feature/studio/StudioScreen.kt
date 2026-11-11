package com.vortexx.player.feature.studio

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.audio.dsp.DspParameters
import com.vortexx.player.audio.dsp.GraphicEqBands
import com.vortexx.player.audio.dsp.LoadReading
import com.vortexx.player.audio.dsp.MeterBus
import com.vortexx.player.audio.engine.AudioDiagnostics
import com.vortexx.player.audio.engine.AudioEngine
import com.vortexx.player.audio.engine.AudioPathState
import com.vortexx.player.audio.engine.DspStatus
import com.vortexx.player.audio.engine.DspStatusKind
import com.vortexx.player.audio.engine.DspStatusResolver
import com.vortexx.player.audio.engine.OutputModePreference
import com.vortexx.player.audio.engine.ProcessorState
import com.vortexx.player.core.capabilities.DeviceCapabilities
import com.vortexx.player.core.capabilities.EffectInfo
import com.vortexx.player.core.capabilities.EqualizerInfo
import com.vortexx.player.core.capabilities.SpatialAudioInfo
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.instrument.EqFaderBank
import com.vortexx.player.ui.designsystem.instrument.InstrumentText
import com.vortexx.player.ui.designsystem.instrument.PanelLabel
import com.vortexx.player.ui.designsystem.instrument.PrecisionSlider
import com.vortexx.player.ui.designsystem.instrument.ReadoutGrid
import com.vortexx.player.ui.designsystem.instrument.SegmentedSelector
import com.vortexx.player.ui.designsystem.instrument.StatusLed
import com.vortexx.player.ui.designsystem.instrument.StatusTone
import com.vortexx.player.ui.designsystem.instrument.StereoLevelMeter
import com.vortexx.player.ui.designsystem.instrument.formatDb
import com.vortexx.player.ui.designsystem.instrument.rememberMeterDisplay
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import javax.inject.Inject

/** Platform effect report kept from Phase 1, shown under "Device effects". */
data class DeviceEffectsReport(
    val equalizer: EqualizerInfo?,
    val effects: List<EffectInfo>,
    val spatial: SpatialAudioInfo,
)

@HiltViewModel
class StudioViewModel @Inject constructor(
    private val engine: AudioEngine,
    private val diagnostics: AudioDiagnostics,
    private val capabilities: DeviceCapabilities,
) : ViewModel() {
    val parameters: StateFlow<DspParameters> = engine.state
    val outputMode: StateFlow<OutputModePreference> = engine.outputMode
    val processor: StateFlow<ProcessorState> = engine.processor
    val path: StateFlow<AudioPathState> = diagnostics.state
    val meters: MeterBus get() = engine.meters

    private val _load = MutableStateFlow<LoadReading?>(null)
    val load: StateFlow<LoadReading?> = _load.asStateFlow()

    private val _report = MutableStateFlow<DeviceEffectsReport?>(null)
    val report: StateFlow<DeviceEffectsReport?> = _report.asStateFlow()

    init {
        viewModelScope.launch {
            while (isActive) {
                delay(LOAD_WINDOW_MS)
                _load.value = engine.load.drain()
            }
        }
        viewModelScope.launch {
            _report.value = withContext(Dispatchers.Default) {
                DeviceEffectsReport(
                    equalizer = capabilities.equalizer(),
                    effects = capabilities.audioEffects(),
                    spatial = capabilities.spatialAudio(),
                )
            }
        }
    }

    fun setEnabled(enabled: Boolean) = engine.setEnabled(enabled)
    fun setPreamp(db: Float) = engine.setPreamp(db)
    fun setBand(index: Int, db: Float) = engine.setBand(index, db)
    fun resetEq() = engine.resetEq()
    fun autoHeadroom() = engine.applyAutoHeadroom()
    fun setOutputMode(mode: OutputModePreference) = engine.setOutputMode(mode)
    fun resetClipCounter() = engine.meters.resetClipCounter()
    fun resetUnderruns() = diagnostics.resetUnderruns()

    private companion object {
        const val LOAD_WINDOW_MS = 1_000L
    }
}

/**
 * Stage A0 Audio Lab: the DSP proof. Every control here changes the samples
 * of music playback through VortexAudioProcessor; every readout comes from
 * Media3 or the processor itself.
 */
@Composable
fun StudioScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: StudioViewModel = hiltViewModel(),
) {
    val params by viewModel.parameters.collectAsStateWithLifecycle()
    val mode by viewModel.outputMode.collectAsStateWithLifecycle()
    val processor by viewModel.processor.collectAsStateWithLifecycle()
    val path by viewModel.path.collectAsStateWithLifecycle()
    val load by viewModel.load.collectAsStateWithLifecycle()
    val report by viewModel.report.collectAsStateWithLifecycle()
    val meter = rememberMeterDisplay(viewModel.meters)

    val output = path.output
    val status = DspStatusResolver.resolve(
        outputOpen = output != null,
        outputIsPcm = output?.isPcm ?: true,
        outputOffload = output?.offload ?: false,
        processorActive = processor.format != null,
        processorReason = processor.inactiveReason,
        userEnabled = params.enabled,
        mode = mode,
    )
    val sampleRate = processor.format?.sampleRate ?: output?.sampleRate ?: 0
    val headroomDb = remember(params.bandGainsDb, sampleRate) {
        params.recommendedPreampDb(if (sampleRate > 0) sampleRate else 48_000)
    }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(title = "Audio Lab", subtitle = "Preamp · 10-band EQ · meters (Stage A0)", onBack = onBack)
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.xl)) {
            item(key = "status") { StatusBlock(status) }

            item(key = "meters-label") {
                PanelLabel("Output level") {
                    Text("clipped ${meter.clippedTotal}", style = InstrumentText.readoutSmall)
                }
            }
            item(key = "meters") {
                StereoLevelMeter(meter, onClearClip = viewModel::resetClipCounter)
            }

            item(key = "mode-label") { PanelLabel("Processing") }
            item(key = "power") {
                SegmentedSelector(
                    options = listOf(true, false),
                    selected = params.enabled,
                    label = { if (it) "DSP on" else "Bypass" },
                    onSelect = viewModel::setEnabled,
                )
            }
            item(key = "mode") {
                Column {
                    Spacer(Modifier.height(VortexSpacing.sm))
                    SegmentedSelector(
                        options = OutputModePreference.entries.toList(),
                        selected = mode,
                        label = { it.label },
                        onSelect = viewModel::setOutputMode,
                    )
                    Text(
                        mode.summary,
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs),
                    )
                }
            }

            item(key = "preamp-label") {
                PanelLabel("Preamp") {
                    TextButton(onClick = viewModel::autoHeadroom) {
                        Text("Auto headroom ${formatDb(headroomDb)}", style = InstrumentText.readoutSmall)
                    }
                }
            }
            item(key = "preamp") {
                PrecisionSlider(
                    label = "Preamp",
                    value = params.preampDb,
                    range = GraphicEqBands.MIN_PREAMP_DB..GraphicEqBands.MAX_PREAMP_DB,
                    step = 0.5f,
                    unit = "dB",
                    onChange = viewModel::setPreamp,
                )
            }

            item(key = "eq-label") {
                PanelLabel("10-band graphic EQ · octave") {
                    TextButton(onClick = viewModel::resetEq) { Text("Flat", style = InstrumentText.readoutSmall) }
                }
            }
            item(key = "eq") {
                Column {
                EqFaderBank(params.bandGainsDb, sampleRate, viewModel::setBand)
                Text(
                    "Swipe sideways for all 10 bands. Drag vertically; double-tap resets. Reapply Auto headroom after EQ or sample-rate changes. Clip count records peaks caught by the sample ceiling; headroom reduces distortion.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
                )
                }
            }

            item(key = "path-label") {
                PanelLabel("Signal path") {
                    TextButton(onClick = viewModel::resetUnderruns) { Text("Reset counters", style = InstrumentText.readoutSmall) }
                }
            }
            item(key = "path") { ReadoutGrid(pathRows(path, processor, load)) }

            item(key = "device-label") { PanelLabel("Device effects · report only") }
            item(key = "device") {
                Column {
                val r = report
                ReadoutGrid(
                    if (r == null) listOf("Status" to "Inspecting…") else deviceRows(r),
                )
                Spacer(Modifier.height(VortexSpacing.md))
                SecondaryButton(
                    text = "Full diagnostics",
                    onClick = onOpenDiagnostics,
                    icon = Icons.Rounded.Analytics,
                    modifier = Modifier.padding(horizontal = VortexSpacing.lg),
                )
                }
            }
            item(key = "scope") {
                Text(
                    "The Audio Lab processes music playback. Video playback is not processed yet. VORTEX X's processing is its own stereo/multichannel DSP and is not Dolby or Atmos processing.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexColors.TextSecondary,
                    modifier = Modifier.padding(VortexSpacing.lg),
                )
            }
        }
    }
}

@Composable
private fun StatusBlock(status: DspStatus) {
    val tone = when (status.kind) {
        DspStatusKind.Active -> StatusTone.Live
        DspStatusKind.Idle -> StatusTone.Neutral
        DspStatusKind.BypassedByUser, DspStatusKind.BatteryMode -> StatusTone.Warning
        DspStatusKind.Passthrough, DspStatusKind.Offload, DspStatusKind.FormatNotSupported -> StatusTone.Off
    }
    Column(Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StatusLed("DSP · ${status.label}", tone)
        }
        Spacer(Modifier.height(VortexSpacing.xs))
        Text(status.detail, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary)
    }
}

private fun pathRows(path: AudioPathState, processor: ProcessorState, load: LoadReading?): List<Pair<String, String>> {
    val input = path.input
    val output = path.output
    val route = path.route
    val rows = mutableListOf<Pair<String, String>>()
    rows += "Decoder" to (path.decoderName ?: "—")
    rows += "Input" to if (input == null) {
        "—"
    } else {
        listOfNotNull(
            input.mimeType?.removePrefix("audio/"),
            input.codecs?.takeIf { it.isNotBlank() },
            if (input.sampleRate > 0) hz(input.sampleRate) else null,
            if (input.channelCount > 0) "${input.channelCount} ch" else null,
            if (input.bitrate > 0) "${input.bitrate / 1000} kbps" else null,
        ).joinToString(" · ")
    }
    rows += "DSP format" to (processor.format?.let { "${it.encodingName} · ${hz(it.sampleRate)} · ${it.channelCount} ch" } ?: (processor.inactiveReason ?: "—"))
    rows += "Output" to if (output == null) {
        "Not open"
    } else {
        listOfNotNull(
            output.encodingName,
            if (output.sampleRate > 0) hz(output.sampleRate) else null,
            if (output.channelCount > 0) "${output.channelCount} ch" else null,
            if (output.offload) "offload" else null,
            if (output.tunneling) "tunneling" else null,
        ).joinToString(" · ")
    }
    rows += "Output buffer" to (output?.bufferMillis?.let { "$it ms (changes are heard after up to this delay)" } ?: "—")
    rows += "Route" to if (route == null) {
        "—"
    } else {
        listOf(route.typeName, route.productName).filter { it.isNotBlank() }.joinToString(" · ") + if (route.exact) "" else " (estimated)"
    }
    rows += "Route changes" to path.routeChanges.toString()
    rows += "Underruns" to path.underruns.toString()
    rows += "DSP load" to (load?.let { String.format(Locale.ROOT, "%.2f %% avg · %.1f %% worst buffer", it.averagePercent, it.worstBufferPercent) } ?: "idle")
    if (path.offloadSupport.isNotEmpty()) {
        rows += "Offload support" to path.offloadSupport.joinToString(" · ") { "${it.codec} ${if (it.supported) "yes" else "no"}" }
    }
    path.sinkError?.let { rows += "Last output error" to it }
    return rows
}

private fun deviceRows(report: DeviceEffectsReport): List<Pair<String, String>> {
    val rows = mutableListOf<Pair<String, String>>()
    val eq = report.equalizer
    rows += "Platform EQ" to if (eq == null) "Not available" else "${eq.bandCount} bands · ${eq.presetNames.size} presets"
    rows += "Effects" to if (report.effects.isEmpty()) "None reported" else report.effects.distinctBy { it.kind }.joinToString(" · ") { it.kind }
    rows += "Spatializer" to report.spatial.level + if (report.spatial.apiAvailable) {
        " · available ${if (report.spatial.available) "yes" else "no"} · enabled ${if (report.spatial.enabled) "yes" else "no"}"
    } else {
        ""
    }
    return rows
}

private fun hz(sampleRate: Int): String =
    if (sampleRate % 1000 == 0) "${sampleRate / 1000} kHz" else String.format(Locale.ROOT, "%.1f kHz", sampleRate / 1000.0)
