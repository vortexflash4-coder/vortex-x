package com.vortexx.player.ui.common

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.data.db.PlaylistSummaryRow
import com.vortexx.player.data.repo.FavoritesRepository
import com.vortexx.player.data.repo.PlaylistNameResult
import com.vortexx.player.data.repo.PlaylistRepository
import com.vortexx.player.domain.model.Song
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.ui.designsystem.components.AddToPlaylistDialog
import com.vortexx.player.ui.designsystem.components.PlaylistNameDialog
import com.vortexx.player.ui.designsystem.components.SongMenuActions
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/** App-wide snackbar host, provided by the root scaffold. */
val LocalSnackbarHostState = staticCompositionLocalOf { SnackbarHostState() }

/** Shared queue / favourite / playlist actions for every song list. */
@HiltViewModel
class SongActionsViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    private val favorites: FavoritesRepository,
    private val playlists: PlaylistRepository,
) : ViewModel() {

    val favoriteIds: StateFlow<Set<Long>> = favorites.favoriteSongIdSet
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptySet())

    val playlistSummaries: StateFlow<List<PlaylistSummaryRow>> = playlists.playlists
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), emptyList())

    val nowPlayingSongId: StateFlow<Long?> = playback.state
        .map { it.nowPlaying?.songId }
        .distinctUntilChanged()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), null)

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    val messages: SharedFlow<String> = _messages.asSharedFlow()

    fun play(songs: List<Song>, index: Int) = playback.playSongs(songs, index)

    fun shuffle(songs: List<Song>) = playback.shuffleAll(songs)

    fun playNext(song: Song) {
        playback.playNext(listOf(song))
        _messages.tryEmit("Playing next: ${song.title}")
    }

    fun addToQueue(song: Song) {
        playback.addToQueue(listOf(song))
        _messages.tryEmit("Added to queue: ${song.title}")
    }

    fun addAllToQueue(songs: List<Song>) {
        if (songs.isEmpty()) return
        playback.addToQueue(songs)
        _messages.tryEmit("Added ${songs.size} tracks to queue")
    }

    fun toggleFavorite(song: Song) {
        viewModelScope.launch {
            val isFavorite = favorites.toggleSong(song.id)
            _messages.tryEmit(if (isFavorite) "Added to favourites" else "Removed from favourites")
        }
    }

    fun addToPlaylist(playlist: PlaylistSummaryRow, songs: List<Song>) {
        viewModelScope.launch {
            playlists.addSongs(playlist.id, songs.map { it.id })
            _messages.tryEmit("Added to ${playlist.name}")
        }
    }

    /** Returns a validation message, or null when the playlist was created. */
    suspend fun createPlaylist(name: String, songs: List<Song>): String? =
        when (val result = playlists.validateName(name)) {
            is PlaylistNameResult.Invalid -> result.reason
            is PlaylistNameResult.Valid -> {
                playlists.create(result.name, songs.map { it.id })
                _messages.tryEmit("Created ${result.name}")
                null
            }
        }
}

@Stable
class SongActionsState internal constructor(val viewModel: SongActionsViewModel) {
    var playlistTarget: List<Song>? by mutableStateOf(null)
        private set
    var creatingPlaylist: Boolean by mutableStateOf(false)
    var createError: String? by mutableStateOf(null)

    fun requestAddToPlaylist(songs: List<Song>) {
        if (songs.isEmpty()) return
        playlistTarget = songs
        creatingPlaylist = false
        createError = null
    }

    fun dismiss() {
        playlistTarget = null
        creatingPlaylist = false
        createError = null
    }

    fun menu(onRemove: ((Song) -> Unit)? = null, removeLabel: String = "Remove"): SongMenuActions =
        SongMenuActions(
            onPlayNext = viewModel::playNext,
            onAddToQueue = viewModel::addToQueue,
            onToggleFavorite = viewModel::toggleFavorite,
            onAddToPlaylist = { requestAddToPlaylist(listOf(it)) },
            onRemove = onRemove,
            removeLabel = removeLabel,
        )
}

@Composable
fun rememberSongActions(viewModel: SongActionsViewModel = hiltViewModel()): SongActionsState =
    remember(viewModel) { SongActionsState(viewModel) }

/** Renders playlist dialogs and forwards confirmation messages to the snackbar. */
@Composable
fun SongActionsHost(state: SongActionsState) {
    val snackbar = LocalSnackbarHostState.current
    val viewModel = state.viewModel
    val scope = rememberCoroutineScope()
    val playlists by viewModel.playlistSummaries.collectAsStateWithLifecycle()

    LaunchedEffect(viewModel) {
        viewModel.messages.collect { message ->
            snackbar.currentSnackbarData?.dismiss()
            launch { snackbar.showSnackbar(message) }
        }
    }

    val target = state.playlistTarget ?: return
    if (state.creatingPlaylist) {
        PlaylistNameDialog(
            title = "New playlist",
            confirmLabel = "Create",
            initialName = "",
            error = state.createError,
            onConfirm = { name ->
                scope.launch {
                    val error = viewModel.createPlaylist(name, target)
                    if (error == null) state.dismiss() else state.createError = error
                }
            },
            onDismiss = state::dismiss,
        )
    } else {
        AddToPlaylistDialog(
            playlists = playlists,
            onPick = { playlist ->
                viewModel.addToPlaylist(playlist, target)
                state.dismiss()
            },
            onCreateNew = { state.creatingPlaylist = true },
            onDismiss = state::dismiss,
        )
    }
}
