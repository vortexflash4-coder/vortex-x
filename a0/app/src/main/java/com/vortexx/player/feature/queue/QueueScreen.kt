package com.vortexx.player.feature.queue

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.ClearAll
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.KeyboardArrowUp
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.data.repo.PlaylistNameResult
import com.vortexx.player.data.repo.PlaylistRepository
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.playback.PlaybackUiState
import com.vortexx.player.playback.QueueEntry
import com.vortexx.player.ui.common.LocalSnackbarHostState
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.MediaArtwork
import com.vortexx.player.ui.designsystem.components.PlaylistNameDialog
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class QueueViewModel @Inject constructor(
    private val playback: PlaybackConnection,
    private val playlists: PlaylistRepository,
) : ViewModel() {
    val state: StateFlow<PlaybackUiState> = playback.state

    fun play(index: Int) = playback.skipToQueueIndex(index)
    fun remove(index: Int) = playback.removeFromQueue(index)
    fun move(from: Int, to: Int) = playback.moveQueueItem(from, to)
    fun clear() = playback.clearQueue()

    /** Saves library tracks in the queue as a playlist; returns an error message or null. */
    suspend fun saveAsPlaylist(name: String): String? {
        val songIds = state.value.queue.mapNotNull { it.songId }
        if (songIds.isEmpty()) return "The queue has no library tracks to save"
        return when (val result = playlists.validateName(name)) {
            is PlaylistNameResult.Invalid -> result.reason
            is PlaylistNameResult.Valid -> {
                playlists.create(result.name, songIds)
                null
            }
        }
    }
}

@Composable
fun QueueScreen(
    onBack: () -> Unit,
    viewModel: QueueViewModel = hiltViewModel(),
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val snackbar = LocalSnackbarHostState.current
    var confirmClear by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var saveError by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(state.currentIndex) {
        if (state.currentIndex > 0 && state.currentIndex < state.queue.size) listState.scrollToItem(state.currentIndex)
    }

    Column(Modifier.fillMaxSize()) {
        VortexTopBar(
            title = "Queue",
            subtitle = if (state.queue.isEmpty()) null else "${state.queue.size} tracks",
            onBack = onBack,
            actions = {
                if (state.queue.isNotEmpty()) {
                    IconButton(onClick = { saving = true; saveError = null }) {
                        Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = "Save queue as playlist")
                    }
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.ClearAll, contentDescription = "Clear queue")
                    }
                }
            },
        )
        if (state.queue.isEmpty()) {
            EmptyState(
                icon = Icons.AutoMirrored.Rounded.QueueMusic,
                title = "Queue is empty",
                message = "Play a song or use “Add to queue” from any song menu.",
            )
        } else {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = VortexSpacing.lg)) {
                items(state.queue, key = { "${it.index}:${it.mediaId}" }) { entry ->
                    QueueRow(
                        entry = entry,
                        isCurrent = entry.index == state.currentIndex,
                        isFirst = entry.index == 0,
                        isLast = entry.index == state.queue.lastIndex,
                        onPlay = { viewModel.play(entry.index) },
                        onMoveUp = { viewModel.move(entry.index, entry.index - 1) },
                        onMoveDown = { viewModel.move(entry.index, entry.index + 1) },
                        onRemove = { viewModel.remove(entry.index) },
                    )
                }
            }
        }
    }

    if (confirmClear) {
        AlertDialog(
            onDismissRequest = { confirmClear = false },
            containerColor = VortexColors.Surface,
            title = { Text("Clear the queue?") },
            text = { Text("Playback stops and all ${state.queue.size} tracks are removed from the queue.") },
            confirmButton = {
                TextButton(onClick = { viewModel.clear(); confirmClear = false }) { Text("Clear", color = VortexColors.AccentActive) }
            },
            dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("Cancel") } },
        )
    }
    if (saving) {
        PlaylistNameDialog(
            title = "Save queue as playlist",
            confirmLabel = "Save",
            initialName = "",
            error = saveError,
            onConfirm = { name ->
                scope.launch {
                    val error = viewModel.saveAsPlaylist(name)
                    if (error == null) {
                        saving = false
                        snackbar.showSnackbar("Queue saved as $name")
                    } else {
                        saveError = error
                    }
                }
            },
            onDismiss = { saving = false },
        )
    }
}

@Composable
private fun QueueRow(
    entry: QueueEntry,
    isCurrent: Boolean,
    isFirst: Boolean,
    isLast: Boolean,
    onPlay: () -> Unit,
    onMoveUp: () -> Unit,
    onMoveDown: () -> Unit,
    onRemove: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .background(if (isCurrent) VortexColors.AccentContainer.copy(alpha = 0.5f) else VortexColors.Background)
            .clickable(role = Role.Button, onClickLabel = "Play ${entry.title}", onClick = onPlay)
            .padding(start = VortexSpacing.lg, end = VortexSpacing.xs, top = VortexSpacing.xs, bottom = VortexSpacing.xs),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(
            request = entry.contentUri?.let { ArtworkRequest.Audio(it) },
            fallbackIcon = Icons.Rounded.MusicNote,
            modifier = Modifier.size(44.dp),
            cornerRadius = 10.dp,
        )
        Spacer(Modifier.width(VortexSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                color = if (isCurrent) VortexColors.AccentActive else VortexColors.TextPrimary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(entry.artist, style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary, maxLines = 1)
        }
        if (isCurrent) Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = VortexColors.AccentActive, modifier = Modifier.size(18.dp))
        IconButton(onClick = onMoveUp, enabled = !isFirst) { Icon(Icons.Rounded.KeyboardArrowUp, contentDescription = "Move up") }
        IconButton(onClick = onMoveDown, enabled = !isLast) { Icon(Icons.Rounded.KeyboardArrowDown, contentDescription = "Move down") }
        IconButton(onClick = onRemove) { Icon(Icons.Rounded.Close, contentDescription = "Remove from queue", tint = VortexColors.TextSecondary) }
    }
}
