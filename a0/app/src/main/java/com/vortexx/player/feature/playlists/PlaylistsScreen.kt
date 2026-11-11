package com.vortexx.player.feature.playlists

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.data.db.PlaylistSummaryRow
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.data.repo.FavoritesRepository
import com.vortexx.player.data.repo.PlaylistNameResult
import com.vortexx.player.data.repo.PlaylistRepository
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.Song
import com.vortexx.player.feature.music.PlayShuffleRow
import com.vortexx.player.ui.common.SongActionsHost
import com.vortexx.player.ui.common.rememberSongActions
import com.vortexx.player.ui.designsystem.components.AccentIconBadge
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.PlaylistNameDialog
import com.vortexx.player.ui.designsystem.components.SongRow
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class FavoritesUi(val loading: Boolean, val songs: List<Song>, val missing: Int)

@HiltViewModel
class PlaylistsViewModel @Inject constructor(
    library: MediaLibraryRepository,
    favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    val playlistRows: StateFlow<List<PlaylistSummaryRow>?> = playlists.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    val favoriteSongs: StateFlow<FavoritesUi> = combine(library.songs, favorites.favoriteSongIds) { songs, ids ->
        when (songs) {
            is LibraryState.Ready -> {
                val byId = songs.data.associateBy { it.id }
                val resolved = ids.mapNotNull { byId[it] }
                FavoritesUi(loading = false, songs = resolved, missing = ids.size - resolved.size)
            }
            else -> FavoritesUi(loading = songs is LibraryState.Loading, songs = emptyList(), missing = 0)
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), FavoritesUi(true, emptyList(), 0))

    suspend fun create(name: String): String? = when (val r = playlists.validateName(name)) {
        is PlaylistNameResult.Invalid -> r.reason
        is PlaylistNameResult.Valid -> {
            playlists.create(r.name)
            null
        }
    }

    suspend fun rename(id: Long, currentName: String, name: String): String? {
        if (name.trim().equals(currentName, ignoreCase = false)) return null
        return when (val r = playlists.validateName(name, ignoreExisting = name.trim().equals(currentName, ignoreCase = true))) {
            is PlaylistNameResult.Invalid -> r.reason
            is PlaylistNameResult.Valid -> {
                playlists.rename(id, r.name)
                null
            }
        }
    }

    fun delete(id: Long) {
        viewModelScope.launch { playlists.delete(id) }
    }
}

private enum class PlaylistTab(val label: String) { Playlists("Playlists"), Favorites("Favourites") }

@Composable
fun PlaylistsScreen(
    onOpenPlaylist: (Long) -> Unit,
    viewModel: PlaylistsViewModel = hiltViewModel(),
) {
    val rows by viewModel.playlistRows.collectAsStateWithLifecycle()
    val favorites by viewModel.favoriteSongs.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableStateOf(PlaylistTab.Playlists) }
    var creating by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<PlaylistSummaryRow?>(null) }
    var deleting by remember { mutableStateOf<PlaylistSummaryRow?>(null) }
    var dialogError by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()
    val songActions = rememberSongActions()

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = "Playlists",
            actions = {
                IconButton(onClick = { creating = true; dialogError = null }) {
                    Icon(Icons.Rounded.Add, contentDescription = "New playlist")
                }
            },
        )
        Row(
            Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
            horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
        ) {
            PlaylistTab.entries.forEach { t -> PillChip(t.label, selected = tab == t, onClick = { tab = t }) }
        }

        when (tab) {
            PlaylistTab.Playlists -> {
                val list = rows
                when {
                    list == null -> LoadingState("Loading playlists…")
                    list.isEmpty() -> EmptyState(
                        icon = Icons.AutoMirrored.Rounded.QueueMusic,
                        title = "No playlists yet",
                        message = "Create one here, or use “Add to playlist” from any song menu.",
                        actionLabel = "New playlist",
                        onAction = { creating = true; dialogError = null },
                    )
                    else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
                        items(list, key = { it.id }) { row ->
                            PlaylistRow(
                                row = row,
                                onOpen = { onOpenPlaylist(row.id) },
                                onRename = { renaming = row; dialogError = null },
                                onDelete = { deleting = row },
                            )
                        }
                    }
                }
            }
            PlaylistTab.Favorites -> FavoritesTab(favorites, songActions)
        }
    }

    SongActionsHost(songActions)

    if (creating) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            initialName = "",
            error = dialogError,
            onConfirm = { name ->
                scope.launch {
                    val error = viewModel.create(name)
                    if (error == null) creating = false else dialogError = error
                }
            },
            onDismiss = { creating = false },
        )
    }
    renaming?.let { row ->
        PlaylistNameDialog(
            title = "Rename playlist",
            confirmLabel = "Rename",
            initialName = row.name,
            error = dialogError,
            onConfirm = { name ->
                scope.launch {
                    val error = viewModel.rename(row.id, row.name, name)
                    if (error == null) renaming = null else dialogError = error
                }
            },
            onDismiss = { renaming = null },
        )
    }
    deleting?.let { row ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            containerColor = VortexColors.Surface,
            title = { Text("Delete “${row.name}”?") },
            text = { Text("The playlist is removed. Your music files are not affected.") },
            confirmButton = {
                TextButton(onClick = { viewModel.delete(row.id); deleting = null }) { Text("Delete", color = VortexColors.AccentActive) }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun PlaylistRow(row: PlaylistSummaryRow, onOpen: () -> Unit, onRename: () -> Unit, onDelete: () -> Unit) {
    var menu by remember { mutableStateOf(false) }
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onOpen)
            .padding(start = VortexSpacing.lg, end = VortexSpacing.xs, top = VortexSpacing.sm, bottom = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentIconBadge(Icons.AutoMirrored.Rounded.QueueMusic)
        Spacer(Modifier.width(VortexSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(row.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(if (row.itemCount == 1) "1 track" else "${row.itemCount} tracks", style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary)
        }
        Box {
            IconButton(onClick = { menu = true }) { Icon(Icons.Rounded.MoreVert, contentDescription = "Options for ${row.name}") }
            DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                DropdownMenuItem(text = { Text("Rename") }, leadingIcon = { Icon(Icons.Rounded.Edit, null) }, onClick = { menu = false; onRename() })
                DropdownMenuItem(text = { Text("Delete") }, leadingIcon = { Icon(Icons.Rounded.Delete, null) }, onClick = { menu = false; onDelete() })
            }
        }
    }
}

@Composable
private fun FavoritesTab(favorites: FavoritesUi, songActions: com.vortexx.player.ui.common.SongActionsState) {
    val favoriteIds by songActions.viewModel.favoriteIds.collectAsStateWithLifecycle()
    val nowPlayingId by songActions.viewModel.nowPlayingSongId.collectAsStateWithLifecycle()
    val menu = remember(songActions) { songActions.menu() }
    when {
        favorites.loading -> LoadingState("Loading favourites…")
        favorites.songs.isEmpty() -> EmptyState(
            icon = Icons.Rounded.Favorite,
            title = "No favourites yet",
            message = "Tap the heart in the player or use a song's menu to add favourites.",
        )
        else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
            item(key = "actions") {
                PlayShuffleRow(
                    onPlay = { songActions.viewModel.play(favorites.songs, 0) },
                    onShuffle = { songActions.viewModel.shuffle(favorites.songs) },
                )
            }
            if (favorites.missing > 0) {
                item(key = "missing") {
                    Text(
                        "${favorites.missing} favourite(s) are no longer on this device.",
                        style = MaterialTheme.typography.bodySmall,
                        color = VortexColors.TextSecondary,
                        modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs),
                    )
                }
            }
            itemsIndexed(favorites.songs, key = { _, s -> s.id }) { index, song ->
                SongRow(
                    song = song,
                    isCurrent = song.id == nowPlayingId,
                    isFavorite = song.id in favoriteIds,
                    onClick = { songActions.viewModel.play(favorites.songs, index) },
                    actions = menu,
                )
            }
        }
    }
}
