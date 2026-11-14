package com.vortexx.player.feature.video

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Movie
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import com.vortexx.player.core.permissions.MediaPermissions
import com.vortexx.player.core.permissions.VideoAccess
import com.vortexx.player.core.permissions.rememberPermissionGroupState
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.data.library.MediaLibraryRepository
import com.vortexx.player.data.repo.WatchHistoryRepository
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.SortField
import com.vortexx.player.domain.model.SortSpec
import com.vortexx.player.domain.model.Video
import com.vortexx.player.domain.playback.ResumePolicy
import com.vortexx.player.feature.music.ScreenStatus
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.ErrorState
import com.vortexx.player.ui.designsystem.components.InlineNotice
import com.vortexx.player.ui.designsystem.components.LoadingState
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.NoticeTone
import com.vortexx.player.ui.designsystem.components.PermissionState
import com.vortexx.player.ui.designsystem.components.SearchField
import com.vortexx.player.ui.designsystem.components.SortMenuButton
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject

data class VideoItemUi(val video: Video, val watchedFraction: Float?)

data class VideoLibraryUiState(
    val status: ScreenStatus = ScreenStatus.Loading,
    val total: Int = 0,
    val items: List<VideoItemUi> = emptyList(),
)

@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val library: MediaLibraryRepository,
    watchHistory: WatchHistoryRepository,
) : ViewModel() {
    private val _query = MutableStateFlow("")
    val query: StateFlow<String> = _query.asStateFlow()
    private val _sort = MutableStateFlow(SortSpec(SortField.DateAdded, ascending = false))
    val sort: StateFlow<SortSpec> = _sort.asStateFlow()

    val uiState: StateFlow<VideoLibraryUiState> = combine(
        library.videos,
        watchHistory.all,
        _query,
        _sort,
    ) { videos, history, query, sort ->
        when (videos) {
            LibraryState.Loading -> VideoLibraryUiState(ScreenStatus.Loading)
            is LibraryState.Failed -> VideoLibraryUiState(ScreenStatus.Failed(videos.message))
            is LibraryState.Ready -> {
                val progress = history.associateBy { it.videoId }
                val list = LibraryLogic.sortVideos(LibraryLogic.filterVideos(videos.data, query), sort)
                VideoLibraryUiState(
                    status = ScreenStatus.Ready,
                    total = videos.data.size,
                    items = list.map { video ->
                        val p = progress[video.id]
                        VideoItemUi(video, p?.let { ResumePolicy.continueWatchingFraction(it.positionMs, video.durationMs) })
                    },
                )
            }
        }
    }
        .flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000L), VideoLibraryUiState())

    fun setQuery(value: String) {
        _query.value = value
    }

    fun setSort(spec: SortSpec) {
        _sort.value = spec
    }

    fun refresh() = library.refresh()
}

@Composable
fun VideoLibraryScreen(
    onPlayVideo: (Video) -> Unit,
    viewModel: VideoLibraryViewModel = hiltViewModel(),
) {
    val permission = rememberPermissionGroupState(
        permissions = MediaPermissions.video,
        isGranted = { it.video != VideoAccess.None },
        onResult = { granted -> if (granted) viewModel.refresh() },
    )
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val query by viewModel.query.collectAsStateWithLifecycle()
    val sort by viewModel.sort.collectAsStateWithLifecycle()
    val context = androidx.compose.ui.platform.LocalContext.current
    val partial = permission.granted && MediaPermissions.access(context).video == VideoAccess.Partial

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = "Videos",
            subtitle = if (state.status == ScreenStatus.Ready) LibraryLogic.pluralize(state.total, "video") else null,
            actions = {
                if (permission.granted) SortMenuButton(spec = sort, onChange = viewModel::setSort)
                IconButton(onClick = viewModel::refresh) { Icon(Icons.Rounded.Refresh, contentDescription = "Refresh library") }
            },
        )
        if (!permission.granted) {
            PermissionState(
                title = "Allow access to videos",
                message = "VORTEX X needs the Photos & videos permission to list videos on this device.",
                permanentlyDenied = permission.permanentlyDenied,
                onRequest = permission::request,
                onOpenSettings = permission::openAppSettings,
            )
            return@Column
        }
        SearchField(
            value = query,
            onValueChange = viewModel::setQuery,
            placeholder = "Search videos and folders",
            modifier = Modifier.padding(horizontal = VortexSpacing.lg),
        )
        if (partial) {
            InlineNotice(
                text = "You've allowed access to selected videos only.",
                tone = NoticeTone.Caution,
                actionLabel = "Change",
                onAction = permission::request,
                modifier = Modifier.padding(horizontal = VortexSpacing.lg, vertical = VortexSpacing.sm),
            )
        }
        when (val status = state.status) {
            ScreenStatus.Loading -> LoadingState("Reading your videos…")
            is ScreenStatus.Failed -> ErrorState("Couldn't read videos", status.message, onRetry = viewModel::refresh)
            ScreenStatus.Ready -> if (state.total == 0) {
                EmptyState(
                    icon = Icons.Rounded.VideoLibrary,
                    title = "No videos found",
                    message = "Videos saved on this device appear here. You can also open a file from Media Explorer.",
                    actionLabel = "Refresh",
                    onAction = viewModel::refresh,
                )
            } else if (state.items.isEmpty()) {
                EmptyState(icon = Icons.Rounded.Movie, title = "No matches", message = "Nothing matches “$query”.")
            } else {
                VideoGrid(items = state.items, onPlay = onPlayVideo)
            }
        }
    }
}

@Composable
private fun VideoGrid(items: List<VideoItemUi>, onPlay: (Video) -> Unit) {
    LazyVerticalGrid(
        columns = GridCells.Adaptive(minSize = 220.dp),
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(VortexSpacing.lg),
        horizontalArrangement = Arrangement.spacedBy(VortexSpacing.md),
        verticalArrangement = Arrangement.spacedBy(VortexSpacing.lg),
    ) {
        items(items, key = { it.video.id }) { item -> VideoCard(item, onClick = { onPlay(item.video) }) }
        item(span = { GridItemSpan(maxLineSpan) }) { Spacer(Modifier.height(VortexSpacing.sm)) }
    }
}

@Composable
fun VideoCard(item: VideoItemUi, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val video = item.video
    Column(
        modifier.clickable(role = Role.Button, onClickLabel = "Play ${video.title}", onClick = onClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f),
        ) {
            MediaArtwork(
                request = ArtworkRequest.VideoFrame(video.contentUri),
                fallbackIcon = Icons.Rounded.Movie,
                modifier = Modifier.fillMaxSize(),
                cornerRadius = 14.dp,
            )
            Row(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(VortexSpacing.sm),
                horizontalArrangement = Arrangement.spacedBy(VortexSpacing.xs),
            ) {
                Format.resolutionBadge(video.width, video.height)?.let { Badge(it, highlight = it == "4K") }
                Badge(Format.duration(video.durationMs))
            }
            val fraction = item.watchedFraction
            if (fraction != null) {
                LinearProgressIndicator(
                    progress = { fraction },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp)
                        .height(3.dp),
                    color = VortexColors.Accent,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round,
                    gapSize = 0.dp,
                    drawStopIndicator = {},
                )
            }
        }
        Spacer(Modifier.height(VortexSpacing.sm))
        Text(video.title, style = MaterialTheme.typography.titleSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        Text(
            "${LibraryLogic.folderDisplayName(video.folder)} • ${Format.bytes(video.sizeBytes)}",
            style = MaterialTheme.typography.bodySmall,
            color = VortexColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

@Composable
private fun Badge(text: String, highlight: Boolean = false) {
    Text(
        text,
        style = MaterialTheme.typography.labelSmall,
        color = VortexColors.TextPrimary,
        modifier = Modifier
            .clip(RoundedCornerShape(6.dp))
            .background(if (highlight) VortexColors.Accent else Color.Black.copy(alpha = 0.7f))
            .padding(horizontal = 6.dp, vertical = 2.dp),
    )
}
