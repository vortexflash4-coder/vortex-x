package com.vortexx.player.feature.diagnostics

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ContentCopy
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.core.capabilities.DeviceCapabilities
import com.vortexx.player.core.capabilities.DeviceSummary
import com.vortexx.player.core.capabilities.FormatSupport
import com.vortexx.player.core.capabilities.OutputDevice
import com.vortexx.player.core.capabilities.SpatialAudioInfo
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.dataOrNull
import com.vortexx.player.ui.common.LocalSnackbarHostState
import com.vortexx.player.ui.designsystem.components.InfoLine
import com.vortexx.player.ui.designsystem.components.InlineNotice
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.NoticeTone
import com.vortexx.player.ui.designsystem.components.SectionHeader
import com.vortexx.player.ui.designsystem.components.VortexCard
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.flow.first
import com.vortexx.player.domain.model.LibraryState
import javax.inject.Inject

data class DiagnosticsReport(
    val device: DeviceSummary,
    val outputs: List<OutputDevice>,
    val spatial: SpatialAudioInfo,
    val hdrTypes: List<String>,
    val immersive: List<FormatSupport>,
    val audio: List<FormatSupport>,
    val video: List<FormatSupport>,
    val songCount: Int?,
    val videoCount: Int?,
    val duplicateGroups: Int?,
) {
    /** Honest one-line verdict about object-based (Atmos-family) audio on this device. */
    val immersiveVerdict: String
        get() {
            val joc = immersive.firstOrNull { it.mime == "audio/eac3-joc" }?.supported == true
            val ac4 = immersive.firstOrNull { it.mime == "audio/ac4" }?.supported == true
            val passthrough = outputs.any { o -> o.encodings.any { it == "E-AC-3 JOC" || it == "Dolby TrueHD" || it == "Dolby MAT" } }
            return when {
                joc || ac4 -> "This device has a decoder for object-based Dolby-family audio. Native immersive playback still depends on the content and the output device."
                passthrough -> "No on-device object-audio decoder, but the current output accepts encoded object audio (passthrough)."
                else -> "No object-based audio decoder or passthrough output detected. Immersive tracks fall back to their stereo or multichannel bed."
            }
        }

    fun asText(): String = buildString {
        appendLine("VORTEX X diagnostics")
        appendLine("Device: ${device.manufacturer} ${device.model}, Android ${device.androidRelease} (SDK ${device.sdkInt})")
        device.soc?.let { appendLine("SoC: $it") }
        appendLine("ABIs: ${device.abis.joinToString()}")
        appendLine("HDR display: ${hdrTypes.ifEmpty { listOf("none reported") }.joinToString()}")
        appendLine("Spatializer: ${spatial.level}; available=${spatial.available}; enabled=${spatial.enabled}")
        appendLine("Immersive: $immersiveVerdict")
        appendLine("Outputs:")
        outputs.forEach { appendLine("  - ${it.typeName} ${it.productName} encodings=${it.encodings.joinToString()}") }
        listOf("Immersive formats" to immersive, "Audio formats" to audio, "Video formats" to video).forEach { (title, list) ->
            appendLine("$title:")
            list.forEach { f -> appendLine("  - ${f.label} (${f.mime}): ${formatVerdict(f)}") }
        }
        if (songCount != null) appendLine("Library: $songCount songs, ${videoCount ?: 0} videos, ${duplicateGroups ?: 0} likely duplicate groups")
    }
}

fun formatVerdict(format: FormatSupport): String = when {
    !format.supported -> "No decoder"
    format.hasHardware -> {
        val best = format.decoders.filter { it.hardware == true }.maxByOrNull { (it.maxWidth ?: 0) * (it.maxHeight ?: 0) }
        if (best?.maxWidth != null) "Hardware, up to ${best.maxWidth}×${best.maxHeight}" else "Hardware"
    }
    else -> {
        val best = format.decoders.maxByOrNull { (it.maxWidth ?: 0) * (it.maxHeight ?: 0) }
        if (best?.maxWidth != null) "Software, up to ${best.maxWidth}×${best.maxHeight}" else "Software"
    }
}

@HiltViewModel
class DiagnosticsViewModel @Inject constructor(
    private val capabilities: DeviceCapabilities,
    private val library: MediaLibraryRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    private val _report = MutableStateFlow<DiagnosticsReport?>(null)
    val report: StateFlow<DiagnosticsReport?> = _report.asStateFlow()

    init {
        viewModelScope.launch {
            // Subscribing briefly makes sure the library has been read at least once.
            val songs = withTimeoutOrNull(3_000L) { library.songs.first { it !is LibraryState.Loading } }?.dataOrNull()
            val videos = withTimeoutOrNull(3_000L) { library.videos.first { it !is LibraryState.Loading } }?.dataOrNull()
            _report.value = withContext(Dispatchers.Default) {
                DiagnosticsReport(
                    device = capabilities.device(),
                    outputs = capabilities.outputDevices(),
                    spatial = capabilities.spatialAudio(),
                    hdrTypes = capabilities.hdrTypes(),
                    immersive = capabilities.immersiveFormats(),
                    audio = capabilities.audioFormats(),
                    video = capabilities.videoFormats(),
                    songCount = songs?.size,
                    videoCount = videos?.size,
                    duplicateGroups = songs?.let { LibraryLogic.duplicateGroups(it).size },
                )
            }
        }
    }

    fun copyReport(): Boolean {
        val text = _report.value?.asText() ?: return false
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("VORTEX X diagnostics", text))
        return true
    }
}

@Composable
fun DiagnosticsScreen(
    onBack: () -> Unit,
    viewModel: DiagnosticsViewModel = hiltViewModel(),
) {
    val report by viewModel.report.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = "Diagnostics",
            subtitle = "Read-only audio and video capability report",
            onBack = onBack,
            actions = {
                IconButton(
                    onClick = {
                        val copied = viewModel.copyReport()
                        scope.launch { snackbar.showSnackbar(if (copied) "Report copied" else "Report not ready yet") }
                    },
                ) { Icon(Icons.Rounded.ContentCopy, contentDescription = "Copy report") }
            },
        )
        val r = report
        if (r == null) {
            LoadingState("Querying decoders and outputs…")
            return@Column
        }
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.xl)) {
            item { SectionHeader("Device") }
            item {
                Card {
                    InfoLine("Model", "${r.device.manufacturer} ${r.device.model}")
                    InfoLine("Android", "${r.device.androidRelease} (API ${r.device.sdkInt})")
                    r.device.soc?.let { InfoLine("SoC", it) }
                    InfoLine("ABIs", r.device.abis.joinToString())
                    InfoLine("HDR display", r.hdrTypes.ifEmpty { listOf("None reported") }.joinToString())
                }
            }

            item { SectionHeader("Immersive and spatial audio") }
            item {
                Card {
                    InlineNotice(text = r.immersiveVerdict, tone = NoticeTone.Info)
                    Spacer(Modifier.height(VortexSpacing.sm))
                    InfoLine("Spatializer", r.spatial.level)
                    if (r.spatial.apiAvailable) {
                        InfoLine("Available on output", yesNo(r.spatial.available))
                        InfoLine("Enabled by user", yesNo(r.spatial.enabled))
                    }
                    r.immersive.forEach { InfoLine(it.label, formatVerdict(it), valueColor = verdictColor(it)) }
                    Spacer(Modifier.height(VortexSpacing.sm))
                    Text(
                        "Stereo MP3/AAC is never labelled as immersive. Device spatialisation, when enabled, is processing by Android — not decoding of object audio.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexColors.TextSecondary,
                    )
                }
            }

            item { SectionHeader("Audio outputs") }
            item {
                Card {
                    if (r.outputs.isEmpty()) InfoLine("Outputs", "None reported")
                    r.outputs.forEachIndexed { index, o ->
                        if (index > 0) Spacer(Modifier.height(VortexSpacing.sm))
                        Text(listOf(o.typeName, o.productName).filter { it.isNotBlank() }.joinToString(" — "), style = MaterialTheme.typography.titleSmall)
                        InfoLine("Sample rates", o.sampleRates.ifEmpty { listOf(0) }.joinToString { if (it == 0) "any" else "$it Hz" })
                        InfoLine("Channels", o.channelCounts.ifEmpty { listOf(0) }.joinToString { if (it == 0) "any" else "$it" })
                        InfoLine("Encodings", o.encodings.ifEmpty { listOf("any PCM") }.joinToString())
                    }
                }
            }

            item { SectionHeader("Audio decoders") }
            item { Card { r.audio.forEach { InfoLine(it.label, formatVerdict(it), valueColor = verdictColor(it)) } } }

            item { SectionHeader("Video decoders") }
            item { Card { r.video.forEach { InfoLine(it.label, formatVerdict(it), valueColor = verdictColor(it)) } } }

            if (r.songCount != null) {
                item { SectionHeader("Library") }
                item {
                    Card {
                        InfoLine("Songs indexed", r.songCount.toString())
                        InfoLine("Videos indexed", (r.videoCount ?: 0).toString())
                        InfoLine("Likely duplicate groups", (r.duplicateGroups ?: 0).toString())
                    }
                }
            }
        }
    }
}

@Composable
private fun Card(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    VortexCard(
        modifier = Modifier
            .padding(horizontal = VortexSpacing.lg)
            .widthIn(max = 840.dp)
            .fillMaxWidth(),
        content = content,
    )
}

private fun yesNo(value: Boolean) = if (value) "Yes" else "No"

private fun verdictColor(format: FormatSupport) = when {
    !format.supported -> VortexColors.TextSecondary
    format.hasHardware -> VortexColors.Success
    else -> VortexColors.TextPrimary
}
