package com.vortexx.player.feature.home

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Schedule
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.data.repo.WatchHistoryRepository
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.Video
import com.vortexx.player.domain.model.dataOrNull
import com.vortexx.player.domain.playback.ResumePolicy
import com.vortexx.player.feature.video.VideoCard
import com.vortexx.player.feature.video.VideoItemUi
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.ui.designsystem.components.AccentIconBadge
import com.vortexx.player.ui.designsystem.components.InlineNotice
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.PrimaryButton
import com.vortexx.player.ui.designsystem.components.SectionHeader
import com.vortexx.player.ui.designsystem.components.StatTile
import com.vortexx.player.ui.designsystem.components.VortexCard
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import com.vortexx.player.ui.navigation.ControlCenterRoute
import com.vortexx.player.ui.navigation.DiagnosticsRoute
import com.vortexx.player.ui.navigation.ExplorerRoute
import com.vortexx.player.ui.navigation.QueueRoute
import com.vortexx.player.ui.navigation.SettingsRoute
import com.vortexx.player.ui.navigation.StudioRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class HomeUiState(
    val loading: Boolean = true,
    val songCount: Int = 0,
    val videoCount: Int = 0,
    val musicDurationMs: Long = 0L,
    val allSongs: List<Song> = emptyList(),
    val recentlyAdded: List<Song> = emptyList(),
    val continueWatching: List<VideoItemUi> = emptyList(),
    val libraryError: String? = null,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    library: MediaLibraryRepository,
    watchHistory: WatchHistoryRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = combine(library.songs, library.videos, watchHistory.all) { songs, videos, history ->
        val songList = songs.dataOrNull().orEmpty()
        val videoList = videos.dataOrNull().orEmpty()
        val videosById = videoList.associateBy { it.id }
        val continueWatching = history.mapNotNull { entry ->
            val video = videosById[entry.videoId] ?: return@mapNotNull null
            val fraction = ResumePolicy.continueWatchingFraction(entry.positionMs, video.durationMs) ?: return@mapNotNull null
            VideoItemUi(video, fraction)
        }.take(10)
        HomeUiState(
            loading = songs is LibraryState.Loading && videos is LibraryState.Loading,
            songCount = songList.size,
            videoCount = videoList.size,
            musicDurationMs = songList.sumOf { it.durationMs },
            allSongs = songList,
            recentlyAdded = songList.sortedByDescending { it.dateAddedSec }.take(12),
            continueWatching = continueWatching,
            libraryError = (songs as? LibraryState.Failed)?.message ?: (videos as? LibraryState.Failed)?.message,
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), HomeUiState())

    fun shuffleAll() = playback.shuffleAll(uiState.value.allSongs)

    fun playRecent(index: Int) = playback.playSongs(uiState.value.recentlyAdded, index)
}

private data class ToolTile(val title: String, val subtitle: String, val icon: ImageVector, val route: Any)

private val Tools = listOf(
    ToolTile("Audio Lab", "Preamp, 10-band EQ, live meters", Icons.Rounded.Equalizer, StudioRoute),
    ToolTile("Control Center", "Speed, sleep timer, repeat", Icons.Rounded.Tune, ControlCenterRoute),
    ToolTile("Queue", "Reorder what plays next", Icons.AutoMirrored.Rounded.QueueMusic, QueueRoute),
    ToolTile("Media Explorer", "Folders & open any file", Icons.Rounded.FolderOpen, ExplorerRoute),
    ToolTile("Diagnostics", "Decoders, HDR, spatial audio", Icons.Rounded.Analytics, DiagnosticsRoute),
    ToolTile("Settings", "Profiles & preferences", Icons.Rounded.Settings, SettingsRoute),
)

@Composable
fun HomeScreen(
    onNavigate: (Any) -> Unit,
    onPlayVideo: (Video) -> Unit,
    onOpenNowPlaying: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val columns = when {
            maxWidth >= 840.dp -> 3
            else -> 2
        }
        LazyColumn(
            Modifier.fillMaxSize(),
            contentPadding = PaddingValues(bottom = VortexSpacing.xl),
        ) {
            item(key = "hero") {
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(VortexColors.HeroGlow)
                        .statusBarsPadding()
                        .padding(start = VortexSpacing.lg, end = VortexSpacing.xs, top = VortexSpacing.md, bottom = VortexSpacing.lg),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("VORTEX X", style = MaterialTheme.typography.displaySmall)
                            Text("Cinema-grade playback for your library", style = MaterialTheme.typography.bodyMedium, color = VortexColors.TextSecondary)
                        }
                        IconButton(onClick = { onNavigate(SettingsRoute) }) {
                            Icon(Icons.Rounded.Settings, contentDescription = "Settings")
                        }
                    }
                    Spacer(Modifier.height(VortexSpacing.lg))
                    Row(
                        Modifier.padding(end = VortexSpacing.md),
                        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
                    ) {
                        StatTile(state.songCount.toString(), "Songs", Icons.Rounded.LibraryMusic, Modifier.weight(1f))
                        StatTile(state.videoCount.toString(), "Videos", Icons.Rounded.VideoLibrary, Modifier.weight(1f))
                        StatTile(Format.longDuration(state.musicDurationMs), "Of music", Icons.Rounded.Schedule, Modifier.weight(1f))
                    }
                    Spacer(Modifier.height(VortexSpacing.md))
                    PrimaryButton(
                        text = "Shuffle all music",
                        onClick = {
                            viewModel.shuffleAll()
                            onOpenNowPlaying()
                        },
                        icon = Icons.Rounded.Shuffle,
                        enabled = state.songCount > 0,
                        modifier = Modifier.padding(end = VortexSpacing.md),
                    )
                }
            }

            val error = state.libraryError
            if (error != null) {
                item(key = "error") {
                    InlineNotice(
                        text = "Library could not be read: $error",
                        modifier = Modifier.padding(horizontal = VortexSpacing.lg),
                    )
                }
            }

            if (state.continueWatching.isNotEmpty()) {
                item(key = "cw-header") { SectionHeader("Continue watching") }
                item(key = "cw-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = VortexSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.md),
                    ) {
                        items(state.continueWatching, key = { it.video.id }) { item ->
                            VideoCard(item, onClick = { onPlayVideo(item.video) }, modifier = Modifier.width(240.dp))
                        }
                    }
                }
            }

            if (state.recentlyAdded.isNotEmpty()) {
                item(key = "recent-header") { SectionHeader("Recently added") }
                item(key = "recent-row") {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = VortexSpacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.md),
                    ) {
                        items(state.recentlyAdded.size, key = { state.recentlyAdded[it].id }) { index ->
                            val song = state.recentlyAdded[index]
                            Column(
                                Modifier
                                    .width(132.dp)
                                    .clickable(role = Role.Button, onClickLabel = "Play ${song.title}") {
                                        viewModel.playRecent(index)
                                    },
                            ) {
                                MediaArtwork(
                                    request = ArtworkRequest.Audio(song.contentUri),
                                    fallbackIcon = Icons.Rounded.MusicNote,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f),
                                    cornerRadius = 14.dp,
                                )
                                Spacer(Modifier.height(VortexSpacing.xs))
                                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song.artist, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }

            item(key = "tools-header") { SectionHeader("Workstation") }
            Tools.chunked(columns).forEachIndexed { rowIndex, row ->
                item(key = "tools-$rowIndex") {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs),
                        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
                    ) {
                        row.forEach { tool ->
                            VortexCard(
                                modifier = Modifier.weight(1f),
                                onClick = { onNavigate(tool.route) },
                            ) {
                                AccentIconBadge(tool.icon)
                                Spacer(Modifier.height(VortexSpacing.sm))
                                Text(tool.title, style = MaterialTheme.typography.titleSmall, maxLines = 1)
                                Text(tool.subtitle, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary, maxLines = 2)
                            }
                        }
                        repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                    }
                }
            }
        }
    }
}
