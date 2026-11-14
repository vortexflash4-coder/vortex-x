package com.vortexx.player.feature.controlcenter

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.settings.AppSettings
import com.vortexx.player.data.settings.SettingsRepository
import com.vortexx.player.domain.playback.PlaybackOptions
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.playback.PlaybackUiState
import com.vortexx.player.playback.RepeatSetting
import com.vortexx.player.playback.SleepTimer
import com.vortexx.player.ui.designsystem.components.InlineNotice
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.SectionHeader
import com.vortexx.player.ui.designsystem.components.ToggleRow
import com.vortexx.player.ui.designsystem.components.VortexCard
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class ControlCenterViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    private val sleepTimer: SleepTimer,
    private val settings: SettingsRepository,
) : ViewModel() {
    val playbackState: StateFlow<PlaybackUiState> = playback.state
    val sleepDeadline: StateFlow<Long?> = sleepTimer.deadlineElapsedMs
    val appSettings: StateFlow<AppSettings> = settings.settings
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), AppSettings())

    fun setSpeed(speed: Float) = playback.setSpeed(speed)
    fun setShuffle(enabled: Boolean) = playback.setShuffle(enabled)
    fun setRepeat(repeat: RepeatSetting) = playback.setRepeat(repeat)
    fun startSleepTimer(minutes: Int) = sleepTimer.start(minutes)
    fun cancelSleepTimer() = sleepTimer.cancel()
    fun remainingSleepMs(): Long? = sleepTimer.remainingMs()

    fun setSkipSilence(enabled: Boolean) {
        viewModelScope.launch { settings.setSkipSilence(enabled) }
    }

    fun setPauseOnDisconnect(enabled: Boolean) {
        viewModelScope.launch { settings.setPauseOnHeadphonesDisconnect(enabled) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ControlCenterScreen(
    onBack: () -> Unit,
    viewModel: ControlCenterViewModel = hiltViewModel(),
) {
    val playback by viewModel.playbackState.collectAsStateWithLifecycle()
    val deadline by viewModel.sleepDeadline.collectAsStateWithLifecycle()
    val settings by viewModel.appSettings.collectAsStateWithLifecycle()

    // Ticks once per second only while a timer is running.
    var remainingMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(deadline) {
        while (deadline != null) {
            remainingMs = viewModel.remainingSleepMs() ?: 0L
            delay(1_000L)
        }
    }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(title = "Control Center", subtitle = "Playback controls that apply right now", onBack = onBack)
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = VortexSpacing.xl),
        ) {
            if (playback.nowPlaying == null) {
                InlineNotice(
                    text = "Nothing is playing. Speed, shuffle and repeat apply to the next playback session.",
                    modifier = Modifier.padding(horizontal = VortexSpacing.lg),
                )
            }

            SectionHeader("Playback speed")
            ControlCard {
                Text("Current: ${Format.speed(playback.speed)}", style = MaterialTheme.typography.titleMedium)
                Text(
                    "Pitch is corrected automatically by the player.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexColors.TextSecondary,
                )
                Spacer(Modifier.height(VortexSpacing.md))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm), verticalArrangement = Arrangement.spacedBy(VortexSpacing.sm)) {
                    PlaybackOptions.speeds.forEach { speed ->
                        PillChip(
                            label = Format.speed(speed),
                            selected = kotlin.math.abs(playback.speed - speed) < 0.01f,
                            onClick = { viewModel.setSpeed(speed) },
                            icon = if (speed == 1f) Icons.Rounded.Speed else null,
                        )
                    }
                }
            }

            SectionHeader("Sleep timer")
            ControlCard {
                Text(
                    if (deadline == null) "Off" else "Pauses in ${Format.duration(remainingMs)}",
                    style = MaterialTheme.typography.titleMedium,
                    color = if (deadline == null) VortexColors.TextPrimary else VortexColors.AccentActive,
                )
                Text(
                    "Runs in the playback service, so it works with the screen off.",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexColors.TextSecondary,
                )
                Spacer(Modifier.height(VortexSpacing.md))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm), verticalArrangement = Arrangement.spacedBy(VortexSpacing.sm)) {
                    PillChip(label = "Off", selected = deadline == null, onClick = viewModel::cancelSleepTimer)
                    PlaybackOptions.sleepTimerMinutes.forEach { minutes ->
                        PillChip(
                            label = "$minutes min",
                            selected = false,
                            onClick = { viewModel.startSleepTimer(minutes) },
                            icon = if (minutes == 30) Icons.Rounded.Bedtime else null,
                        )
                    }
                }
            }

            SectionHeader("Order")
            ControlCard {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm), verticalArrangement = Arrangement.spacedBy(VortexSpacing.sm)) {
                    PillChip(
                        label = if (playback.shuffleEnabled) "Shuffle on" else "Shuffle off",
                        selected = playback.shuffleEnabled,
                        onClick = { viewModel.setShuffle(!playback.shuffleEnabled) },
                        icon = Icons.Rounded.Shuffle,
                    )
                    PillChip(label = "No repeat", selected = playback.repeat == RepeatSetting.Off, onClick = { viewModel.setRepeat(RepeatSetting.Off) })
                    PillChip(label = "Repeat all", selected = playback.repeat == RepeatSetting.All, onClick = { viewModel.setRepeat(RepeatSetting.All) }, icon = Icons.Rounded.Repeat)
                    PillChip(label = "Repeat one", selected = playback.repeat == RepeatSetting.One, onClick = { viewModel.setRepeat(RepeatSetting.One) }, icon = Icons.Rounded.RepeatOne)
                }
            }

            SectionHeader("Music behaviour")
            VortexCard(modifier = Modifier.padding(horizontal = VortexSpacing.lg), contentPadding = androidx.compose.foundation.layout.PaddingValues(0.dp)) {
                ToggleRow(
                    title = "Skip silence",
                    summary = "Shortens silent passages (saved as a setting)",
                    checked = settings.skipSilence,
                    onCheckedChange = viewModel::setSkipSilence,
                )
                ToggleRow(
                    title = "Pause when headphones disconnect",
                    summary = "Wired or Bluetooth",
                    checked = settings.pauseOnHeadphonesDisconnect,
                    onCheckedChange = viewModel::setPauseOnDisconnect,
                )
            }
        }
    }
}

@Composable
private fun ControlCard(content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit) {
    VortexCard(
        modifier = Modifier
            .padding(horizontal = VortexSpacing.lg)
            .widthIn(max = 720.dp)
            .fillMaxWidth(),
        content = content,
    )
}
