package com.vortexx.player.feature.music

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import androidx.navigation.toRoute
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.CollectionType
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.SongCollection
import com.vortexx.player.ui.common.SongActionsHost
import com.vortexx.player.ui.common.rememberSongActions
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.ErrorState
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.SongRow
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import com.vortexx.player.ui.navigation.CollectionRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

sealed interface CollectionUiState {
    data object Loading : CollectionUiState
    data class Ready(val collection: SongCollection) : CollectionUiState
    data object Missing : CollectionUiState
    data class Failed(val message: String) : CollectionUiState
}

@HiltViewModel
class CollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: MediaLibraryRepository,
) : ViewModel() {
    private val route = savedStateHandle.toRoute<CollectionRoute>()
    val title: String = route.title
    private val type: CollectionType = CollectionType.entries.firstOrNull { it.name == route.type } ?: CollectionType.Album

    val uiState: StateFlow<CollectionUiState> = library.songs
        .map { state ->
            when (state) {
                LibraryState.Loading -> CollectionUiState.Loading
                is LibraryState.Failed -> CollectionUiState.Failed(state.message)
                is LibraryState.Ready -> LibraryLogic.collection(state.data, type, route.key)
                    ?.let { CollectionUiState.Ready(it) }
                    ?: CollectionUiState.Missing
            }
        }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), CollectionUiState.Loading)
}

@Composable
fun CollectionScreen(
    onBack: () -> Unit,
    viewModel: CollectionViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val songActions = rememberSongActions()
    val favoriteIds by songActions.viewModel.favoriteIds.collectAsStateWithLifecycle()
    val nowPlayingId by songActions.viewModel.nowPlayingSongId.collectAsStateWithLifecycle()
    val menu = remember(songActions) { songActions.menu() }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(title = viewModel.title, onBack = onBack)
        when (val s = state) {
            CollectionUiState.Loading -> LoadingState("Loading…")
            is CollectionUiState.Failed -> ErrorState("Couldn't load this collection", s.message)
            CollectionUiState.Missing -> EmptyState(
                icon = Icons.Rounded.LibraryMusic,
                title = "Nothing here anymore",
                message = "These tracks were removed from the device or are hidden by the short-clip filter.",
            )
            is CollectionUiState.Ready -> {
                val collection = s.collection
                LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
                    item(key = "hero") {
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .background(VortexColors.HeroGlow)
                                .padding(VortexSpacing.lg),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MediaArtwork(
                                request = collection.coverSong?.let { ArtworkRequest.Audio(it.contentUri) },
                                fallbackIcon = Icons.Rounded.Album,
                                modifier = Modifier.size(112.dp),
                                cornerRadius = 16.dp,
                            )
                            Spacer(Modifier.width(VortexSpacing.lg))
                            Column(Modifier.weight(1f)) {
                                Text(collection.title, style = MaterialTheme.typography.titleLarge, maxLines = 2)
                                Text(collection.subtitle, style = MaterialTheme.typography.bodyMedium, color = VortexColors.TextSecondary)
                                Text(
                                    "${LibraryLogic.pluralize(collection.songs.size, "track")} • ${Format.longDuration(collection.totalDurationMs)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VortexColors.TextSecondary,
                                )
                            }
                        }
                    }
                    item(key = "actions") {
                        PlayShuffleRow(
                            onPlay = { songActions.viewModel.play(collection.songs, 0) },
                            onShuffle = { songActions.viewModel.shuffle(collection.songs) },
                        )
                    }
                    itemsIndexed(collection.songs, key = { _, song -> song.id }) { index, song ->
                        SongRow(
                            song = song,
                            isCurrent = song.id == nowPlayingId,
                            isFavorite = song.id in favoriteIds,
                            onClick = { songActions.viewModel.play(collection.songs, index) },
                            actions = menu,
                        )
                    }
                }
            }
        }
    }
    SongActionsHost(songActions)
}
