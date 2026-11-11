package com.vortexx.player.feature.music

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.Category
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortexx.player.core.permissions.MediaPermissions
import com.vortexx.player.core.permissions.rememberPermissionGroupState
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.CollectionType
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.SongCollection
import com.vortexx.player.ui.common.SongActionsHost
import com.vortexx.player.ui.common.SongActionsState
import com.vortexx.player.ui.common.rememberSongActions
import com.vortexx.player.ui.designsystem.components.AccentIconBadge
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.ErrorState
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.PermissionState
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.PrimaryButton
import com.vortexx.player.ui.designsystem.components.SearchField
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.components.SongRow
import com.vortexx.player.ui.designsystem.components.SortMenuButton
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing

@Composable
fun MusicLibraryScreen(
    onOpenCollection: (CollectionType, String, String) -> Unit,
    viewModel: MusicLibraryViewModel = hiltViewModel(),
) {
    val permission = rememberPermissionGroupState(
        permissions = MediaPermissions.audio,
        isGranted = { it.audio },
        onResult = { granted -> if (granted) viewModel.refresh() },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(MusicTab.Songs) }
    val songActions = rememberSongActions()

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = "Music",
            subtitle = if (state.status == ScreenStatus.Ready) {
                "${LibraryLogic.pluralize(state.totalSongs, "song")} • ${Format.longDuration(state.totalDurationMs)}"
            } else {
                null
            },
            actions = {
                if (tab == MusicTab.Songs && permission.granted) {
                    SortMenuButton(spec = sort, onChange = viewModel::setSort)
                }
                IconButton(onClick = viewModel::refresh) {
                    Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library")
                }
            },
        )

        if (!permission.granted) {
            PermissionState(
                title = "Allow access to music",
                message = "VORTEX X needs the Music & audio permission to list the songs on this device.",
                permanentlyDenied = permission.permanentlyDenied,
                onRequest = permission::request,
                onOpenSettings = permission::openAppSettings,
            )
            return@Column
        }

        SearchField(
            value = query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search songs, artists, albums",
            modifier = Modifier.padding(horizontal = VortexSpacing.lg),
        )
        Row(
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.md),
            horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
        ) {
            MusicTab.entries.forEach { t ->
                PillChip(label = t.label, selected = t == tab, onClick = { tab = t })
            }
        }

        when (val status = state.status) {
            ScreenStatus.Loading -> LoadingState("Reading your music library…")
            is ScreenStatus.Failed -> ErrorState(
                title = "Couldn't read the music library",
                message = status.message,
                onRetry = viewModel::refresh,
            )
            ScreenStatus.Ready -> when {
                state.totalSongs == 0 -> EmptyState(
                    icon = Icons.Rounded.LibraryMusic,
                    title = "No music found",
                    message = "Copy audio files to this device, then refresh. Short clips are hidden by default (Settings › Library).",
                    actionLabel = "Refresh",
                    onAction = viewModel::refresh,
                )
                else -> when (tab) {
                    MusicTab.Songs -> SongsTab(
                        songs = state.songs,
                        query = query,
                        songActions = songActions,
                        onPlay = viewModel::play,
                        onShuffle = viewModel::shuffle,
                    )
                    MusicTab.Albums -> AlbumGrid(state.albums) { onOpenCollection(it.type, it.key, it.title) }
                    MusicTab.Artists -> CollectionList(state.artists, Icons.Rounded.Person) { onOpenCollection(it.type, it.key, it.title) }
                    MusicTab.Genres -> CollectionList(state.genres, Icons.Rounded.Category) { onOpenCollection(it.type, it.key, it.title) }
                    MusicTab.Folders -> CollectionList(state.folders, Icons.Rounded.Folder) { onOpenCollection(it.type, it.key, it.title) }
                }
            }
        }
    }
    SongActionsHost(songActions)
}

@Composable
private fun SongsTab(
    songs: List<Song>,
    query: String,
    songActions: SongActionsState,
    onPlay: (List<Song>, Int) -> Unit,
    onShuffle: (List<Song>) -> Unit,
) {
    val favoriteIds by songActions.viewModel.favoriteIds.collectAsStateWithLifecycle()
    val nowPlayingId by songActions.viewModel.nowPlayingSongId.collectAsStateWithLifecycle()
    if (songs.isEmpty()) {
        EmptyState(icon = Icons.Rounded.LibraryMusic, title = "No matches", message = "Nothing matches “$query”.")
        return
    }
    val menu = remember(songActions) { songActions.menu() }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
        item(key = "header", contentType = "header") {
            PlayShuffleRow(onPlay = { onPlay(songs, 0) }, onShuffle = { onShuffle(songs) })
        }
        itemsIndexed(songs, key = { _, song -> song.id }, contentType = { _, _ -> "song" }) { index, song ->
            SongRow(
                song = song,
                isCurrent = song.id == nowPlayingId,
                isFavorite = song.id in favoriteIds,
                onClick = { onPlay(songs, index) },
                actions = menu,
            )
        }
    }
}

@Composable
fun PlayShuffleRow(onPlay: () -> Unit, onShuffle: () -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .fillMaxWidth()
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
    ) {
        PrimaryButton(text = "Play all", onClick = onPlay, icon = Icons.Rounded.PlayArrow, modifier = Modifier.weight(1f))
        SecondaryButton(text = "Shuffle", onClick = onShuffle, icon = Icons.Rounded.Shuffle, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun AlbumGrid(albums: List<SongCollection>, onOpen: (SongCollection) -> Unit) {
    if (albums.isEmpty()) {
        EmptyState(icon = Icons.Rounded.Album, title = "No albums", message = "No albums match your search.")
        return
    }
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 156.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(VortexSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.md),
        verticalArrangement = Arrangement.spacedBy(VortexSpacing.lg),
    ) {
        items(albums, key = { it.key }) { album ->
            Column(
                Modifier
                    .clickable(role = Role.Button, onClickLabel = "Open ${album.title}") { onOpen(album) },
            ) {
                MediaArtwork(
                    request = album.coverSong?.let { ArtworkRequest.Audio(it.contentUri) },
                    fallbackIcon = Icons.Rounded.Album,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f),
                    cornerRadius = 16.dp,
                )
                Spacer(Modifier.height(VortexSpacing.sm))
                Text(album.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    "${album.subtitle} • ${album.songs.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = VortexColors.TextSecondary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun CollectionList(
    collections: List<SongCollection>,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    onOpen: (SongCollection) -> Unit,
) {
    if (collections.isEmpty()) {
        EmptyState(icon = icon, title = "Nothing here", message = "No entries match your search.")
        return
    }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
        items(collections, key = { it.key }) { collection ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .heightIn(min = 64.dp)
                    .clickable(role = Role.Button) { onOpen(collection) }
                    .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AccentIconBadge(icon)
                Spacer(Modifier.width(VortexSpacing.md))
                Column(Modifier.weight(1f)) {
                    Text(collection.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        "${collection.subtitle} • ${Format.longDuration(collection.totalDurationMs)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexColors.TextSecondary,
                        maxLines = 1,
                    )
                }
                Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VortexColors.TextSecondary)
            }
        }
    }
}
