package com.vortexx.player.ui.app

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Analytics
import androidx.compose.material.icons.rounded.Equalizer
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.playback.PlaybackUiState
import com.vortexx.player.playback.PositionSnapshot
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexRadius
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import com.vortexx.player.ui.navigation.ControlCenterRoute
import com.vortexx.player.ui.navigation.DiagnosticsRoute
import com.vortexx.player.ui.navigation.ExplorerRoute
import com.vortexx.player.ui.navigation.HomeRoute
import com.vortexx.player.ui.navigation.MusicRoute
import com.vortexx.player.ui.navigation.PlaylistsRoute
import com.vortexx.player.ui.navigation.SettingsRoute
import com.vortexx.player.ui.navigation.StudioRoute
import com.vortexx.player.ui.navigation.VideosRoute
import kotlin.reflect.KClass

data class TopLevelDestination(
    val route: Any,
    val routeClass: KClass<*>,
    val label: String,
    val icon: ImageVector,
)

/** Compact screens: five bottom-bar destinations. */
val PrimaryDestinations = listOf(
    TopLevelDestination(HomeRoute, HomeRoute::class, "Home", Icons.Rounded.Home),
    TopLevelDestination(MusicRoute, MusicRoute::class, "Music", Icons.Rounded.LibraryMusic),
    TopLevelDestination(VideosRoute, VideosRoute::class, "Videos", Icons.Rounded.VideoLibrary),
    TopLevelDestination(PlaylistsRoute, PlaylistsRoute::class, "Playlists", Icons.AutoMirrored.Rounded.QueueMusic),
    TopLevelDestination(ExplorerRoute, ExplorerRoute::class, "Explorer", Icons.Rounded.FolderOpen),
)

/** Larger screens also show the workstation tools in the rail. */
val RailExtraDestinations = listOf(
    TopLevelDestination(StudioRoute, StudioRoute::class, "Audio Lab", Icons.Rounded.Equalizer),
    TopLevelDestination(ControlCenterRoute, ControlCenterRoute::class, "Control", Icons.Rounded.Tune),
    TopLevelDestination(DiagnosticsRoute, DiagnosticsRoute::class, "Diagnostics", Icons.Rounded.Analytics),
    TopLevelDestination(SettingsRoute, SettingsRoute::class, "Settings", Icons.Rounded.Settings),
)

@Composable
fun VortexBottomBar(
    destinations: List<TopLevelDestination>,
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationBar(containerColor = VortexColors.Surface, tonalElevation = 0.dp) {
        destinations.forEach { destination ->
            NavigationBarItem(
                selected = isSelected(destination),
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label, maxLines = 1) },
                colors = NavigationBarItemDefaults.colors(
                    selectedIconColor = VortexColors.AccentActive,
                    selectedTextColor = VortexColors.TextPrimary,
                    indicatorColor = VortexColors.AccentContainer,
                    unselectedIconColor = VortexColors.TextSecondary,
                    unselectedTextColor = VortexColors.TextSecondary,
                ),
            )
        }
    }
}

@Composable
fun VortexNavRail(
    destinations: List<TopLevelDestination>,
    isSelected: (TopLevelDestination) -> Boolean,
    onSelect: (TopLevelDestination) -> Unit,
) {
    NavigationRail(
        containerColor = VortexColors.Surface,
        header = {
            Text(
                "VX",
                style = MaterialTheme.typography.titleLarge,
                color = VortexColors.Accent,
                modifier = Modifier.padding(vertical = VortexSpacing.md),
            )
        },
    ) {
        destinations.forEach { destination ->
            NavigationRailItem(
                selected = isSelected(destination),
                onClick = { onSelect(destination) },
                icon = { Icon(destination.icon, contentDescription = null) },
                label = { Text(destination.label, maxLines = 1) },
                colors = NavigationRailItemDefaults.colors(
                    selectedIconColor = VortexColors.AccentActive,
                    selectedTextColor = VortexColors.TextPrimary,
                    indicatorColor = VortexColors.AccentContainer,
                    unselectedIconColor = VortexColors.TextSecondary,
                    unselectedTextColor = VortexColors.TextSecondary,
                ),
            )
        }
    }
}

/** Persistent mini player shown above the navigation bar while something is loaded. */
@Composable
fun MiniPlayer(
    state: PlaybackUiState,
    position: PositionSnapshot,
    onOpen: () -> Unit,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nowPlaying = state.nowPlaying ?: return
    val progress = if (position.durationMs > 0) {
        (position.positionMs.toFloat() / position.durationMs.toFloat()).coerceIn(0f, 1f)
    } else {
        0f
    }
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = VortexSpacing.sm, vertical = VortexSpacing.xs)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(VortexRadius.md))
            .background(VortexColors.Card)
            .clickable(role = Role.Button, onClickLabel = "Open player", onClick = onOpen),
    ) {
        Row(
            modifier = Modifier.padding(start = VortexSpacing.sm, end = VortexSpacing.xs, top = VortexSpacing.sm, bottom = VortexSpacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MediaArtwork(
                request = nowPlaying.contentUri?.let { ArtworkRequest.Audio(it) },
                fallbackIcon = Icons.Rounded.MusicNote,
                modifier = Modifier.size(44.dp),
                cornerRadius = 10.dp,
            )
            Spacer(Modifier.width(VortexSpacing.md))
            Column(Modifier.weight(1f)) {
                Text(nowPlaying.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    text = if (state.error != null) "Playback error — tap for details" else nowPlaying.artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state.error != null) VortexColors.AccentActive else VortexColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            IconButton(onClick = onPlayPause) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    contentDescription = if (state.isPlaying) "Pause" else "Play",
                    tint = VortexColors.TextPrimary,
                )
            }
            IconButton(onClick = onNext, enabled = state.hasNext) {
                Icon(Icons.Rounded.SkipNext, contentDescription = "Next track")
            }
        }
        Box(Modifier.padding(horizontal = VortexSpacing.md).padding(bottom = VortexSpacing.xs)) {
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp),
                color = VortexColors.Accent,
                trackColor = VortexColors.Divider,
                strokeCap = StrokeCap.Round,
                gapSize = 0.dp,
                drawStopIndicator = {},
            )
        }
    }
}
