package com.vortexx.player.feature.explorer

import android.content.ContentResolver
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.KeyboardArrowRight
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.UploadFile
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
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
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.MediaFolder
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.Video
import com.vortexx.player.domain.model.dataOrNull
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.ui.common.LocalSnackbarHostState
import com.vortexx.player.ui.common.SongActionsHost
import com.vortexx.player.ui.common.rememberSongActions
import com.vortexx.player.ui.designsystem.components.AccentIconBadge
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.PrimaryButton
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.components.SectionHeader
import com.vortexx.player.ui.designsystem.components.SongRow
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import com.vortexx.player.ui.navigation.ExplorerFolderRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

data class ExplorerUi(val loading: Boolean = true, val folders: List<MediaFolder> = emptyList())

sealed interface OpenedFile {
    data class Audio(val uri: Uri, val name: String) : OpenedFile
    data class Video(val uri: Uri, val name: String) : OpenedFile
    data class Unsupported(val name: String, val mime: String?) : OpenedFile
}

@HiltViewModel
class ExplorerViewModel @Inject constructor(
    private val library: MediaLibraryRepository,
    private val playback: PlaybackConnection,
) : ViewModel() {

    val uiState: StateFlow<ExplorerUi> = combine(library.songs, library.videos) { songs, videos ->
        val loading = songs is LibraryState.Loading || videos is LibraryState.Loading
        ExplorerUi(
            loading = loading && songs.dataOrNull() == null && videos.dataOrNull() == null,
            folders = LibraryLogic.mediaFolders(songs.dataOrNull().orEmpty(), videos.dataOrNull().orEmpty()),
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), ExplorerUi())

    fun refresh() = library.refresh()

    /** Classifies a document picked through the Storage Access Framework. */
    suspend fun inspect(context: Context, uri: Uri): OpenedFile = withContext(Dispatchers.IO) {
        val resolver = context.contentResolver
        runCatching { resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION) }
        val name = displayName(resolver, uri) ?: uri.lastPathSegment ?: "Selected file"
        val mime = resolver.getType(uri)
        when {
            mime?.startsWith("audio/") == true -> OpenedFile.Audio(uri, name)
            mime?.startsWith("video/") == true -> OpenedFile.Video(uri, name)
            else -> OpenedFile.Unsupported(name, mime)
        }
    }

    fun playAudio(uri: Uri, name: String) = playback.playExternalUri(uri, name)

    private fun displayName(resolver: ContentResolver, uri: Uri): String? =
        runCatching {
            resolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
}

@Composable
fun ExplorerScreen(
    onOpenFolder: (String) -> Unit,
    onOpenNowPlaying: () -> Unit,
    onPlayExternalVideo: (String, String) -> Unit,
    viewModel: ExplorerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = LocalSnackbarHostState.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()

    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            when (val file = viewModel.inspect(context, uri)) {
                is OpenedFile.Audio -> {
                    viewModel.playAudio(file.uri, file.name)
                    onOpenNowPlaying()
                }
                is OpenedFile.Video -> onPlayExternalVideo(file.uri.toString(), file.name)
                is OpenedFile.Unsupported -> snackbar.showSnackbar("${file.name} is not an audio or video file (${file.mime ?: "unknown type"})")
            }
        }
    }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(title = "Media Explorer", subtitle = "Folders on this device")
        LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
            item(key = "actions") {
                Row(
                    Modifier
                        .fillMaxWidth()
                        .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
                    horizontalArrangement = Arrangement.spacedBy(VortexSpacing.sm),
                ) {
                    PrimaryButton(
                        text = "Open a file",
                        onClick = { picker.launch(arrayOf("audio/*", "video/*")) },
                        icon = Icons.Rounded.UploadFile,
                        modifier = Modifier.weight(1f),
                    )
                    SecondaryButton(
                        text = "Rescan",
                        onClick = viewModel::refresh,
                        icon = Icons.Rounded.Refresh,
                        modifier = Modifier.weight(1f),
                    )
                }
            }
            item(key = "header") { SectionHeader("Media folders") }
            when {
                state.loading -> item(key = "loading") { LoadingState("Scanning folders…", Modifier.heightIn(min = 200.dp)) }
                state.folders.isEmpty() -> item(key = "empty") {
                    EmptyState(
                        icon = Icons.Rounded.FolderOpen,
                        title = "No media folders",
                        message = "Grant media access or open individual files with “Open a file”.",
                        modifier = Modifier.heightIn(min = 280.dp),
                    )
                }
                else -> items(state.folders, key = { it.path }) { folder ->
                    FolderRow(folder) { onOpenFolder(folder.path) }
                }
            }
        }
    }
}

@Composable
private fun FolderRow(folder: MediaFolder, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AccentIconBadge(Icons.Rounded.Folder)
        Spacer(Modifier.width(VortexSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(folder.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val parts = buildList {
                if (folder.songCount > 0) add(LibraryLogic.pluralize(folder.songCount, "song"))
                if (folder.videoCount > 0) add(LibraryLogic.pluralize(folder.videoCount, "video"))
            }
            Text(
                "${parts.joinToString(" • ")} — ${folder.path.ifEmpty { "/" }}",
                style = MaterialTheme.typography.bodySmall,
                color = VortexColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Icon(Icons.AutoMirrored.Rounded.KeyboardArrowRight, contentDescription = null, tint = VortexColors.TextSecondary)
    }
}

data class FolderContentsUi(
    val loading: Boolean = true,
    val songs: List<Song> = emptyList(),
    val videos: List<Video> = emptyList(),
)

@HiltViewModel
class ExplorerFolderViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    library: MediaLibraryRepository,
) : ViewModel() {
    val path: String = savedStateHandle.toRoute<ExplorerFolderRoute>().path

    val uiState: StateFlow<FolderContentsUi> = combine(library.songs, library.videos) { songs, videos ->
        val key = path.ifBlank { "/" }
        FolderContentsUi(
            loading = songs is LibraryState.Loading && videos is LibraryState.Loading,
            songs = songs.dataOrNull().orEmpty().filter { it.folder.ifBlank { "/" } == key }.sortedBy { it.displayName.lowercase() },
            videos = videos.dataOrNull().orEmpty().filter { it.folder.ifBlank { "/" } == key }.sortedBy { it.displayName.lowercase() },
        )
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), FolderContentsUi())
}

@Composable
fun ExplorerFolderScreen(
    onBack: () -> Unit,
    onPlayVideo: (Video) -> Unit,
    viewModel: ExplorerFolderViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val songActions = rememberSongActions()
    val favoriteIds by songActions.viewModel.favoriteIds.collectAsStateWithLifecycle()
    val nowPlayingId by songActions.viewModel.nowPlayingSongId.collectAsStateWithLifecycle()
    val menu = remember(songActions) { songActions.menu() }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = LibraryLogic.folderDisplayName(viewModel.path),
            subtitle = viewModel.path.ifEmpty { "/" },
            onBack = onBack,
        )
        when {
            state.loading -> LoadingState("Loading folder…")
            state.songs.isEmpty() && state.videos.isEmpty() -> EmptyState(
                icon = Icons.Rounded.FolderOpen,
                title = "Folder is empty",
                message = "No indexed media remains in this folder.",
            )
            else -> LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
                if (state.videos.isNotEmpty()) {
                    item(key = "videos-header") { SectionHeader("Videos") }
                    items(state.videos, key = { "v${it.id}" }) { video ->
                        Row(
                            Modifier
                                .fillMaxWidth()
                                .clickable(role = Role.Button) { onPlayVideo(video) }
                                .padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            MediaArtwork(
                                request = ArtworkRequest.VideoFrame(video.contentUri),
                                fallbackIcon = Icons.Rounded.Movie,
                                modifier = Modifier.size(width = 96.dp, height = 54.dp),
                                cornerRadius = 8.dp,
                            )
                            Spacer(Modifier.width(VortexSpacing.md))
                            Column(Modifier.weight(1f)) {
                                Text(video.displayName, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    "${Format.duration(video.durationMs)} • ${Format.bytes(video.sizeBytes)}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = VortexColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
                if (state.songs.isNotEmpty()) {
                    item(key = "songs-header") { SectionHeader("Audio") }
                    items(state.songs.size, key = { "s${state.songs[it].id}" }) { index ->
                        val song = state.songs[index]
                        SongRow(
                            song = song,
                            isCurrent = song.id == nowPlayingId,
                            isFavorite = song.id in favoriteIds,
                            onClick = { songActions.viewModel.play(state.songs, index) },
                            actions = menu,
                        )
                    }
                }
            }
        }
    }
    SongActionsHost(songActions)
}
