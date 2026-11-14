package com.vortexx.player.feature.music

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.SongCollection
import com.vortexx.player.domain.model.SortSpec
import com.vortexx.player.playback.PlaybackConnection
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

enum class MusicTab(val label: String) {
    Songs("Songs"),
    Albums("Albums"),
    Artists("Artists"),
    Genres("Genres"),
    Folders("Folders"),
}

sealed interface ScreenStatus {
    data object Loading : ScreenStatus
    data object Ready : ScreenStatus
    data class Failed(val message: String) : ScreenStatus
}

data class MusicLibraryUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val totalSongs: Int = 0,
    val totalDurationMs: Long = 0L,
    val songs: List<Song> = emptyList(),
    val albums: List<SongCollection> = emptyList(),
    val artists: List<SongCollection> = emptyList(),
    val genres: List<SongCollection> = emptyList(),
    val folders: List<SongCollection> = emptyList(),
)

internal data class Groups(
    val all: List<Song>,
    val albums: List<SongCollection>,
    val artists: List<SongCollection>,
    val genres: List<SongCollection>,
    val folders: List<SongCollection>,
)

@OptIn(FlowPreview::class)
@HiltViewModel
class MusicLibraryViewModel @Inject constructor(
    private val library: MediaLibraryRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {

    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()

    private val _sort = MutableStateFlow(SortSpec())
    val sort: StateFlow<SortSpec> = _sort.asStateFlow()

    // Grouping is recomputed only when the library itself changes, not per keystroke.
    private val groups = library.songs.map<LibraryState<List<Song>>, LibraryState<Groups>> { state ->
        when (state) {
            is LibraryState.Ready -> {
                val songs = state.data
                LibraryState.Ready(
                    Groups(
                        all = songs,
                        albums = LibraryLogic.albums(songs),
                        artists = LibraryLogic.artists(songs),
                        genres = LibraryLogic.genres(songs),
                        folders = LibraryLogic.folders(songs),
                    ),
                )
            }
            is LibraryState.Failed -> state
            LibraryState.Loading -> LibraryState.Loading
        }
    }.flowOn(Dispatchers.Default)

    val uiState: StateFlow<MusicLibraryUiState> = combine(
        groups,
        _query.debounce(150L),
        _sort,
    ) { groupState, query, sort ->
        when (groupState) {
            LibraryState.Loading -> MusicLibraryUiState(status = ScreenStatus.Loading)
            is LibraryState.Failed -> MusicLibraryUiState(status = ScreenStatus.Failed(groupState.message))
            is LibraryState.Ready -> {
                val g = groupState.data
                MusicLibraryUiState(
                    status = ScreenStatus.Ready,
                    totalSongs = g.all.size,
                    totalDurationMs = g.all.sumOf { it.durationMs },
                    songs = LibraryLogic.sortSongs(LibraryLogic.filterSongs(g.all, query), sort),
                    albums = g.albums.matching(query),
                    artists = g.artists.matching(query),
                    genres = g.genres.matching(query),
                    folders = g.folders.matching(query),
                )
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), MusicLibraryUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSort(spec: SortSpec) {
        _sort.value = spec
    }

    fun refresh() = library.refresh()

    fun play(songs: List<Song>, index: Int) = playback.playSongs(songs, index)

    fun shuffle(songs: List<Song>) = playback.shuffleAll(songs)

    private fun List<SongCollection>.matching(query: String): List<SongCollection> {
        val q = query.trim()
        if (q.isEmpty()) return this
        return filter { it.title.contains(q, ignoreCase = true) || it.subtitle.contains(q, ignoreCase = true) }
    }
}
