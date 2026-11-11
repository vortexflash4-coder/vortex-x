package com.vortexx.player.feature.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.RestartAlt
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.BuildConfig
import com.vortexx.player.data.library.ArtworkLoader
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.data.repo.WatchHistoryRepository
import com.vortexx.player.data.settings.AppSettings
import com.vortexx.player.data.settings.SettingDefinition
import com.vortexx.player.data.settings.SettingId
import com.vortexx.player.data.settings.SettingsCatalog
import com.vortexx.player.data.settings.SettingsMode
import com.vortexx.player.data.settings.SettingsRepository
import com.vortexx.player.data.settings.VideoScaling
import com.vortexx.player.ui.common.LocalSnackbarHostState
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.SearchField
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.components.SectionHeader
import com.vortexx.player.ui.designsystem.components.ToggleRow
import com.vortexx.player.ui.designsystem.components.VortexCard
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val repository: SettingsRepository,
    private val library: MediaLibraryRepository,
    private val watchHistory: WatchHistoryRepository,
    private val artworkLoader: ArtworkLoader,
) : ViewModel() {

    /** Null until DataStore has been read, so the UI never flashes defaults. */
    val settings: StateFlow<AppSettings?> = repository.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    fun setQuery(value: String) {
        _query.value = value
    }

    private fun edit(block: suspend (SettingsRepository) -> Unit) {
        viewModelScope.launch { block(repository) }
    }

    fun setMode(mode: SettingsMode) = edit { it.setMode(mode) }
    fun setHideShortAudio(v: Boolean) = edit { it.setHideShortAudio(v) }
    fun setShortAudioThreshold(seconds: Int) = edit { it.setShortAudioThreshold(seconds) }
    fun setPauseOnDisconnect(v: Boolean) = edit { it.setPauseOnHeadphonesDisconnect(v) }
    fun setSkipSilence(v: Boolean) = edit { it.setSkipSilence(v) }
    fun setResumeVideos(v: Boolean) = edit { it.setResumeVideos(v) }
    fun setKeepScreenOn(v: Boolean) = edit { it.setKeepScreenOnVideo(v) }
    fun setDefaultScaling(scaling: VideoScaling) = edit { it.setDefaultVideoScaling(scaling) }
    fun setVerboseLogging(v: Boolean) = edit { it.setVerbosePlaybackLogging(v) }
    fun resetToDefaults() = edit { it.resetToDefaults() }

    fun refreshLibrary() {
        artworkLoader.clear()
        library.refresh()
    }

    fun clearWatchHistory() {
        viewModelScope.launch { watchHistory.clear() }
    }
}

@Composable
fun SettingsScreen(
    onBack: () -> Unit,
    onOpenDiagnostics: () -> Unit,
    viewModel: SettingsViewModel = hiltViewModel(),
) {
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val snackbar = LocalSnackbarHostState.current
    val scope = rememberCoroutineScope()
    var confirmReset by remember { mutableStateOf(false) }
    var confirmClearHistory by remember { mutableStateOf(false) }

    Column(Modifier.fillMaxSize()) {
        val current = settings
        VortexTopBar(
            title = "Settings",
            subtitle = current?.mode?.let { "Detail level: ${it.label}" },
            onBack = onBack,
        )
        if (current == null) {
            LoadingState("Loading settings…")
            return@Column
        }

        val visible = SettingsCatalog.visible(current.mode, query)
        val grouped = visible.groupBy { it.category }
        // The search field is the list's first item, so content scrolls with it
        // instead of sliding underneath a fixed field.
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.xl)) {
            item(key = "search") {
                SearchField(
                    value = query,
                    onValueChange = viewModel::setQuery,
                    placeholder = "Search settings",
                    modifier = Modifier.padding(start = VortexSpacing.lg, end = VortexSpacing.lg, bottom = VortexSpacing.sm),
                )
            }
            if (visible.isEmpty()) {
                item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.SearchOff,
                        title = "No matching settings",
                        message = if (current.mode != SettingsMode.Developer) {
                            "Try another word, or switch to a higher detail level to see more settings."
                        } else {
                            "Try another word."
                        },
                        modifier = Modifier.heightIn(min = 320.dp),
                    )
                }
            }
            grouped.forEach { (category, definitions) ->
                item(key = "header-${category.name}") { SectionHeader(category.title) }
                item(key = "card-${category.name}") {
                    VortexCard(
                        modifier = Modifier
                            .padding(horizontal = VortexSpacing.lg)
                            .widthIn(max = 840.dp)
                            .fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = VortexSpacing.xs),
                    ) {
                        definitions.forEachIndexed { index, definition ->
                            if (index > 0) HorizontalDivider(color = VortexColors.Divider.copy(alpha = 0.6f))
                            SettingRow(
                                definition = definition,
                                settings = current,
                                viewModel = viewModel,
                                onRefreshLibrary = {
                                    viewModel.refreshLibrary()
                                    scope.launch { snackbar.showSnackbar("Library refresh started") }
                                },
                                onClearHistory = { confirmClearHistory = true },
                                onOpenDiagnostics = onOpenDiagnostics,
                            )
                        }
                    }
                }
            }
            item(key = "footer") {
                Column(Modifier.padding(VortexSpacing.lg)) {
                    Text(
                        "More categories — audio processing, spatial audio, subtitles, Bluetooth, streaming, power, accessibility, privacy and backup — appear here as each feature ships, so every visible control works.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexColors.TextSecondary,
                    )
                    Spacer(Modifier.height(VortexSpacing.md))
                    SecondaryButton(text = "Reset to defaults", onClick = { confirmReset = true }, icon = Icons.Rounded.RestartAlt)
                    Spacer(Modifier.height(VortexSpacing.md))
                    Text(
                        "VORTEX X ${BuildConfig.VERSION_NAME}",
                        style = MaterialTheme.typography.labelMedium,
                        color = VortexColors.TextSecondary,
                    )
                }
            }
        }
    }

    if (confirmReset) {
        AlertDialog(
            onDismissRequest = { confirmReset = false },
            containerColor = VortexColors.Surface,
            title = { Text("Reset all settings?") },
            text = { Text("Every setting returns to its default. Your detail level, playlists, favourites and watch history are kept.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.resetToDefaults()
                    confirmReset = false
                    scope.launch { snackbar.showSnackbar("Settings reset") }
                }) { Text("Reset", color = VortexColors.AccentActive) }
            },
            dismissButton = { TextButton(onClick = { confirmReset = false }) { Text("Cancel") } },
        )
    }
    if (confirmClearHistory) {
        AlertDialog(
            onDismissRequest = { confirmClearHistory = false },
            containerColor = VortexColors.Surface,
            title = { Text("Clear watch history?") },
            text = { Text("Resume positions and Continue watching are removed. Videos themselves are not affected.") },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.clearWatchHistory()
                    confirmClearHistory = false
                    scope.launch { snackbar.showSnackbar("Watch history cleared") }
                }) { Text("Clear", color = VortexColors.AccentActive) }
            },
            dismissButton = { TextButton(onClick = { confirmClearHistory = false }) { Text("Cancel") } },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingRow(
    definition: SettingDefinition,
    settings: AppSettings,
    viewModel: SettingsViewModel,
    onRefreshLibrary: () -> Unit,
    onClearHistory: () -> Unit,
    onOpenDiagnostics: () -> Unit,
) {
    when (definition.id) {
        SettingId.DetailLevel -> ChoiceRow(definition, note = settings.mode.description) {
            SettingsMode.entries.forEach { mode ->
                PillChip(mode.label, selected = settings.mode == mode, onClick = { viewModel.setMode(mode) })
            }
        }
        SettingId.HideShortAudio -> ToggleRow(definition.title, definition.summary, settings.hideShortAudio, viewModel::setHideShortAudio)
        SettingId.ShortAudioThreshold -> ChoiceRow(
            definition,
            enabled = settings.hideShortAudio,
            disabledReason = "Turn on “Hide short audio clips” first",
        ) {
            AppSettings.SHORT_AUDIO_CHOICES.forEach { seconds ->
                PillChip(
                    "$seconds s",
                    selected = settings.shortAudioThresholdSec == seconds,
                    onClick = { if (settings.hideShortAudio) viewModel.setShortAudioThreshold(seconds) },
                )
            }
        }
        SettingId.RefreshLibrary -> ActionRow(definition, onRefreshLibrary)
        SettingId.PauseOnHeadphonesDisconnect -> ToggleRow(definition.title, definition.summary, settings.pauseOnHeadphonesDisconnect, viewModel::setPauseOnDisconnect)
        SettingId.SkipSilence -> ToggleRow(definition.title, definition.summary, settings.skipSilence, viewModel::setSkipSilence)
        SettingId.ResumeVideos -> ToggleRow(definition.title, definition.summary, settings.resumeVideos, viewModel::setResumeVideos)
        SettingId.KeepScreenOn -> ToggleRow(definition.title, definition.summary, settings.keepScreenOnVideo, viewModel::setKeepScreenOn)
        SettingId.DefaultVideoScaling -> ChoiceRow(definition) {
            VideoScaling.entries.forEach { scaling ->
                PillChip(scaling.label, selected = settings.defaultVideoScaling == scaling, onClick = { viewModel.setDefaultScaling(scaling) })
            }
        }
        SettingId.ClearWatchHistory -> ActionRow(definition, onClearHistory)
        SettingId.OpenDiagnostics -> ActionRow(definition, onOpenDiagnostics)
        SettingId.VerboseLogging -> ToggleRow(definition.title, definition.summary, settings.verbosePlaybackLogging, viewModel::setVerboseLogging)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChoiceRow(
    definition: SettingDefinition,
    enabled: Boolean = true,
    disabledReason: String? = null,
    note: String? = null,
    chips: @Composable () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.md),
    ) {
        Text(definition.title, style = MaterialTheme.typography.titleSmall, color = if (enabled) VortexColors.TextPrimary else VortexColors.TextSecondary)
        Text(
            if (!enabled && disabledReason != null) disabledReason else definition.summary,
            style = MaterialTheme.typography.bodySmall,
            color = VortexColors.TextSecondary,
        )
        Spacer(Modifier.height(VortexSpacing.sm))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm), verticalArrangement = Arrangement.spacedBy(VortexSpacing.sm)) {
            chips()
        }
        if (note != null) {
            Spacer(Modifier.height(VortexSpacing.sm))
            Text(note, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary)
        }
    }
}

@Composable
private fun ActionRow(definition: SettingDefinition, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.md),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(definition.title, style = MaterialTheme.typography.titleSmall)
            Text(definition.summary, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary)
        }
        Spacer(Modifier.width(VortexSpacing.sm))
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VortexColors.TextSecondary)
    }
}
