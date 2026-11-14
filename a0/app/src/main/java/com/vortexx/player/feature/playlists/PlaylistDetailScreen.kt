package com.vortexx.player.feature.playlists

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.data.repo.PlaylistRepository
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.Song
import com.vortexx.player.feature.music.PlayShuffleRow
import com.vortexx.player.ui.common.SongActionsHost
import com.vortexx.player.ui.common.rememberSongActions
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.SongRow
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import com.vortexx.player.ui.navigation.PlaylistDetailRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistEntryUi(val itemId: Long, val song: Song)

data class PlaylistDetailUi(
    val loading: Boolean = true,
    val exists: Boolean = true,
    val name: String = "",
    val entries: List<PlaylistEntryUi> = emptyList(),
    val missing: Int = 0,
) {
    val songs: List<Song> get() = entries.map { it.song }
}

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: MediaLibraryRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    private val playlistId = savedStateHandle.toRoute<PlaylistDetailRoute>().playlistId

    val uiState: StateFlow<PlaylistDetailUi> = combine(
        playlists.playlist(playlistId),
        playlists.items(playlistId),
        library.songs,
    ) { playlist, items, songs ->
        when {
            playlist == null -> PlaylistDetailUi(loading = false, exists = false)
            songs !is LibraryState.Ready -> PlaylistDetailUi(loading = true, name = playlist.name)
            else -> {
                val byId = songs.data.associateBy { it.id }
                val entries = items.mapNotNull { item -> byId[item.songId]?.let { PlaylistEntryUi(item.id, it) } }
                PlaylistDetailUi(
                    loading = false,
                    name = playlist.name,
                    entries = entries,
                    missing = items.size - entries.size,
                )
            }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), PlaylistDetailUi())

    fun remove(itemId: Long) {
        viewModelScope.launch { playlists.removeItem(playlistId, itemId) }
    }
}

@Composable
fun PlaylistDetailScreen(
    onBack: () -> Unit,
    viewModel: PlaylistDetailViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val songActions = rememberSongActions()
    val favoriteIds by songActions.viewModel.favoriteIds.collectAsStateWithLifecycle()
    val nowPlayingId by songActions.viewModel.nowPlayingSongId.collectAsStateWithLifecycle()

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = state.name.ifEmpty { "Playlist" },
            subtitle = if (state.entries.isEmpty()) {
                null
            } else {
                "${LibraryLogic.pluralize(state.entries.size, "track")} • ${Format.longDuration(state.entries.sumOf { it.song.durationMs })}"
            },
            onBack = onBack,
        )
        when {
            !state.exists -> EmptyState(Icons.AutoMirrored.Rounded.QueueMusic, "Playlist not found", "It may have been deleted.")
            state.loading -> LoadingState("Loading playlist…")
            state.entries.isEmpty() -> EmptyState(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = "This playlist is empty",
                message = if (state.missing > 0) {
                    "${state.missing} track(s) are no longer on this device."
                } else {
                    "Add songs with “Add to playlist…” from any song menu."
                },
            )
            else -> {
                val songs = state.songs
                val entries = state.entries
                val menuByItem = remember(entries, songActions) {
                    entries.associate { entry ->
                        entry.itemId to songActions.menu(onRemove = { viewModel.remove(entry.itemId) }, removeLabel = "Remove from playlist")
                    }
                }
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
                    item(key = "actions") {
                        PlayShuffleRow(
                            onPlay = { songActions.viewModel.play(songs, 0) },
                            onShuffle = { songActions.viewModel.shuffle(songs) },
                        )
                    }
                    if (state.missing > 0) {
                        item(key = "missing") {
                            Text(
                                "${state.missing} track(s) are unavailable — the files were moved or deleted.",
                                style = MaterialTheme.typography.bodySmall,
                                color = VortexColors.TextSecondary,
                                modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.xs),
                            )
                        }
                    }
                    itemsIndexed(entries, key = { _, e -> e.itemId }) { index, entry ->
                        SongRow(
                            song = entry.song,
                            isCurrent = entry.song.id == nowPlayingId,
                            isFavorite = entry.song.id in favoriteIds,
                            onClick = { songActions.viewModel.play(songs, index) },
                            actions = menuByItem[entry.itemId],
                        )
                    }
                }
            }
        }
    }
    SongActionsHost(songActions)
}
