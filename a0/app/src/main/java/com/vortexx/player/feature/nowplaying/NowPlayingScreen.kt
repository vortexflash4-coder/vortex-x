package com.vortexx.player.feature.nowplaying

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Bedtime
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Speed
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.data.repo.FavoritesRepository
import com.vortexx.player.domain.playback.PlaybackErrorExplainer
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.playback.PlaybackUiState
import com.vortexx.player.playback.PositionSnapshot
import com.vortexx.player.playback.RepeatSetting
import com.vortexx.player.playback.SleepTimer
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.InlineNotice
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.NoticeTone
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NowPlayingViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    private val favorites: FavoritesRepository,
    sleepTimer: SleepTimer,
) : ViewModel() {
    val state: StateFlow<PlaybackUiState> = playback.state
    val position: Flow<PositionSnapshot> = playback.positionUpdates(intervalMs = 250L)
    val sleepTimerActive: StateFlow<Boolean> = combine(sleepTimer.deadlineElapsedMs, playback.state) { deadline, _ -> deadline != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)
    val isFavorite: StateFlow<Boolean> = combine(playback.state, favorites.favoriteSongIdSet) { s, ids ->
        val id = s.nowPlaying?.songId
        id != null && id in ids
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), false)

    fun togglePlayPause() = playback.togglePlayPause()
    fun next() = playback.next()
    fun previous() = playback.previous()
    fun seekTo(ms: Long) = playback.seekTo(ms)
    fun toggleShuffle() = playback.setShuffle(!state.value.shuffleEnabled)
    fun cycleRepeat() = playback.cycleRepeat()
    fun retry() = playback.retry()
    fun skipFailed() = playback.skipFailed()

    fun toggleFavorite() {
        val id = state.value.nowPlaying?.songId ?: return
        viewModelScope.launch { favorites.toggleSong(id) }
    }
}

@Composable
fun NowPlayingScreen(
    onBack: () -> Unit,
    onOpenQueue: () -> Unit,
    onOpenControlCenter: () -> Unit,
    onBrowseMusic: () -> Unit,
    viewModel: NowPlayingViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val position by viewModel.position.collectAsStateWithLifecycle(initialValue = PositionSnapshot())
    val isFavorite by viewModel.isFavorite.collectAsStateWithLifecycle()
    val sleepActive by viewModel.sleepTimerActive.collectAsStateWithLifecycle()

    Box(
        Modifier
            .fillMaxSize()
            .background(VortexColors.Background)
            .background(VortexColors.HeroGlow),
    ) {
        val nowPlaying = state.nowPlaying
        if (nowPlaying == null) {
            Column(Modifier.statusBarsPadding()) {
                IconButton(onClick = onBack) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player") }
                EmptyState(
                    icon = Icons.Rounded.MusicNote,
                    title = "Nothing playing",
                    message = "Pick a song from your library to start playback.",
                    actionLabel = "Browse music",
                    onAction = onBrowseMusic,
                )
            }
            return@Box
        }

        BoxWithConstraints(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            val landscape = maxWidth > maxHeight && maxWidth >= 560.dp
            val artwork: @Composable (Modifier) -> Unit = { mod ->
                MediaArtwork(
                    request = nowPlaying.contentUri?.let { ArtworkRequest.Audio(it) },
                    fallbackIcon = Icons.Rounded.MusicNote,
                    modifier = mod.aspectRatio(1f),
                    cornerRadius = 20.dp,
                    contentDescription = "Artwork for ${nowPlaying.title}",
                )
            }
            val controls: @Composable (Modifier) -> Unit = { mod ->
                PlayerControls(
                    state = state,
                    position = position,
                    isFavorite = isFavorite,
                    sleepActive = sleepActive,
                    viewModel = viewModel,
                    onOpenQueue = onOpenQueue,
                    onOpenControlCenter = onOpenControlCenter,
                    modifier = mod,
                )
            }

            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Close player")
                    }
                    Text(
                        "NOW PLAYING",
                        style = MaterialTheme.typography.labelSmall,
                        color = VortexColors.TextSecondary,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.weight(1f),
                    )
                    IconButton(onClick = onOpenQueue) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = "Open queue")
                    }
                }
                if (landscape) {
                    Row(
                        Modifier
                            .fillMaxSize()
                            .padding(horizontal = VortexSpacing.xl, vertical = VortexSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.xl),
                    ) {
                        artwork(Modifier.fillMaxHeight())
                        controls(Modifier.weight(1f).verticalScroll(rememberScrollState()))
                    }
                } else {
                    Column(
                        Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState())
                            .padding(horizontal = VortexSpacing.xl),
                        horizontalAlignment = Alignment.CenterHorizontally,
                    ) {
                        Spacer(Modifier.height(VortexSpacing.lg))
                        artwork(Modifier.widthIn(max = 380.dp).fillMaxWidth())
                        Spacer(Modifier.height(VortexSpacing.xl))
                        controls(Modifier.widthIn(max = 480.dp).fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun PlayerControls(
    state: PlaybackUiState,
    position: PositionSnapshot,
    isFavorite: Boolean,
    sleepActive: Boolean,
    viewModel: NowPlayingViewModel,
    onOpenQueue: () -> Unit,
    onOpenControlCenter: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowPlaying = state.nowPlaying ?: return
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(nowPlaying.title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(
                    listOf(nowPlaying.artist, nowPlaying.album).filter { it.isNotBlank() }.joinToString(" • "),
                    style = MaterialTheme.typography.bodyLarge,
                    color = VortexColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            if (nowPlaying.songId != null) {
                IconButton(onClick = viewModel::toggleFavorite) {
                    Icon(
                        if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        contentDescription = if (isFavorite) "Remove from favourites" else "Add to favourites",
                        tint = if (isFavorite) VortexColors.Accent else VortexColors.TextSecondary,
                    )
                }
            }
        }

        val error = state.error
        if (error != null) {
            val explanation = PlaybackErrorExplainer.explain(error.code)
            Spacer(Modifier.height(VortexSpacing.md))
            InlineNotice(
                text = "${explanation.title}. ${explanation.detail} (${error.codeName})",
                tone = NoticeTone.Error,
                actionLabel = if (explanation.canRetry) "Retry" else "Skip",
                onAction = if (explanation.canRetry) viewModel::retry else viewModel::skipFailed,
            )
        }

        Spacer(Modifier.height(VortexSpacing.lg))
        SeekBar(position = position, durationMs = state.durationMs, onSeek = viewModel::seekTo)

        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = viewModel::toggleShuffle, modifier = Modifier.semantics { stateDescription = if (state.shuffleEnabled) "On" else "Off" }) {
                Icon(
                    Icons.Rounded.Shuffle,
                    contentDescription = "Shuffle",
                    tint = if (state.shuffleEnabled) VortexColors.AccentActive else VortexColors.TextSecondary,
                )
            }
            IconButton(onClick = viewModel::previous, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(34.dp))
            }
            FilledIconButton(
                onClick = viewModel::togglePlayPause,
                modifier = Modifier.size(76.dp),
                shape = CircleShape,
                colors = IconButtonDefaults.filledIconButtonColors(
                    containerColor = VortexColors.Accent,
                    contentColor = VortexColors.TextPrimary,
                ),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    modifier = Modifier.size(40.dp),
                )
            }
            IconButton(onClick = viewModel::next, enabled = state.hasNext || state.repeat != RepeatSetting.Off, modifier = Modifier.size(56.dp)) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next", modifier = Modifier.size(34.dp))
            }
            IconButton(
                onClick = viewModel::cycleRepeat,
                modifier = Modifier.semantics {
                    stateDescription = when (state.repeat) {
                        RepeatSetting.Off -> "Off"
                        RepeatSetting.All -> "Repeat all"
                        RepeatSetting.One -> "Repeat one"
                    }
                },
            ) {
                Icon(
                    if (state.repeat == RepeatSetting.One) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    contentDescription = "Repeat",
                    tint = if (state.repeat == RepeatSetting.Off) VortexColors.TextSecondary else VortexColors.AccentActive,
                )
            }
        }

        Spacer(Modifier.height(VortexSpacing.md))
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            TextButton(onClick = onOpenControlCenter) {
                Icon(Icons.Rounded.Speed, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(VortexSpacing.xs))
                Text(Format.speed(state.speed))
            }
            TextButton(onClick = onOpenControlCenter) {
                Icon(
                    Icons.Rounded.Bedtime,
                    contentDescription = null,
                    tint = if (sleepActive) VortexColors.AccentActive else VortexColors.TextSecondary,
                    modifier = Modifier.size(18.dp),
                )
                Spacer(Modifier.size(VortexSpacing.xs))
                Text(if (sleepActive) "Timer on" else "Sleep")
            }
            TextButton(onClick = onOpenControlCenter) {
                Icon(Icons.Rounded.Tune, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(VortexSpacing.xs))
                Text("Controls")
            }
            TextButton(onClick = onOpenQueue) {
                Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, modifier = Modifier.size(18.dp))
                Spacer(Modifier.size(VortexSpacing.xs))
                Text("Queue")
            }
        }
    }
}

/** Slider that only seeks when the drag finishes, so playback isn't flooded with seeks. */
@Composable
private fun SeekBar(position: PositionSnapshot, durationMs: Long, onSeek: (Long) -> Unit) {
    val duration = (if (position.durationMs > 0) position.durationMs else durationMs).coerceAtLeast(0L)
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val liveFraction = if (duration > 0) (position.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    val shown = dragFraction ?: liveFraction
    val shownMs = (shown * duration).toLong()

    Column {
        Slider(
            value = shown,
            onValueChange = { dragFraction = it },
            onValueChangeFinished = {
                dragFraction?.let { onSeek((it * duration).toLong()) }
                dragFraction = null
            },
            enabled = duration > 0,
            colors = SliderDefaults.colors(
                thumbColor = VortexColors.TextPrimary,
                activeTrackColor = VortexColors.Accent,
                inactiveTrackColor = VortexColors.Divider,
            ),
            modifier = Modifier.semantics {
                contentDescription = "Seek"
                stateDescription = "${Format.duration(shownMs)} of ${Format.duration(duration)}"
            },
        )
        Row(Modifier.fillMaxWidth()) {
            Text(Format.duration(shownMs), style = MaterialTheme.typography.labelMedium, color = VortexColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(Format.duration(duration), style = MaterialTheme.typography.labelMedium, color = VortexColors.TextSecondary)
        }
    }
}
