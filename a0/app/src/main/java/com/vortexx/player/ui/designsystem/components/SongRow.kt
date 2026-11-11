package com.vortexx.player.ui.designsystem.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.vortexx.player.core.util.Format
import com.vortexx.player.data.db.PlaylistSummaryRow
import com.vortexx.player.data.library.ArtworkRequest
import com.vortexx.player.domain.model.Song
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing

/** Callbacks for the per-song overflow menu. */
data class SongMenuActions(
    val onPlayNext: (Song) -> Unit,
    val onAddToQueue: (Song) -> Unit,
    val onToggleFavorite: (Song) -> Unit,
    val onAddToPlaylist: (Song) -> Unit,
    val onRemove: ((Song) -> Unit)? = null,
    val removeLabel: String = "Remove",
)

@Composable
fun SongRow(
    song: Song,
    isCurrent: Boolean,
    isFavorite: Boolean,
    onClick: () -> Unit,
    actions: SongMenuActions?,
    modifier: Modifier = Modifier,
) {
    var menuOpen by remember { mutableStateOf(false) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clickable(role = Role.Button, onClickLabel = "Play ${song.title}", onClick = onClick)
            .padding(start = VortexSpacing.lg, end = VortexSpacing.xs, top = VortexSpacing.sm, bottom = VortexSpacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        MediaArtwork(
            request = ArtworkRequest.Audio(song.contentUri),
            fallbackIcon = Icons.Rounded.MusicNote,
            modifier = Modifier.size(48.dp),
            cornerRadius = 10.dp,
        )
        Spacer(Modifier.width(VortexSpacing.md))
        Column(Modifier.weight(1f)) {
            Text(
                text = song.title,
                style = MaterialTheme.typography.titleSmall,
                color = if (isCurrent) VortexColors.AccentActive else VortexColors.TextPrimary,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = "${song.artist} • ${Format.duration(song.durationMs)}",
                style = MaterialTheme.typography.bodySmall,
                color = VortexColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (isCurrent) {
            Icon(Icons.Rounded.GraphicEq, contentDescription = "Now playing", tint = VortexColors.AccentActive, modifier = Modifier.size(18.dp))
        } else if (isFavorite) {
            Icon(Icons.Rounded.Favorite, contentDescription = "Favourite", tint = VortexColors.Accent, modifier = Modifier.size(16.dp))
        }
        if (actions != null) {
            Box {
                IconButton(onClick = { menuOpen = true }) {
                    Icon(Icons.Rounded.MoreVert, contentDescription = "More options for ${song.title}", tint = VortexColors.TextSecondary)
                }
                DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                    DropdownMenuItem(
                        text = { Text("Play next") },
                        leadingIcon = { Icon(Icons.Rounded.SkipNext, contentDescription = null) },
                        onClick = { menuOpen = false; actions.onPlayNext(song) },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to queue") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null) },
                        onClick = { menuOpen = false; actions.onAddToQueue(song) },
                    )
                    DropdownMenuItem(
                        text = { Text(if (isFavorite) "Remove from favourites" else "Add to favourites") },
                        leadingIcon = {
                            Icon(if (isFavorite) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, contentDescription = null)
                        },
                        onClick = { menuOpen = false; actions.onToggleFavorite(song) },
                    )
                    DropdownMenuItem(
                        text = { Text("Add to playlist…") },
                        leadingIcon = { Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, contentDescription = null) },
                        onClick = { menuOpen = false; actions.onAddToPlaylist(song) },
                    )
                    val remove = actions.onRemove
                    if (remove != null) {
                        HorizontalDivider(color = VortexColors.Divider)
                        DropdownMenuItem(
                            text = { Text(actions.removeLabel, color = VortexColors.AccentActive) },
                            onClick = { menuOpen = false; remove(song) },
                        )
                    }
                }
            }
        }
    }
}

/** Pick an existing playlist or create a new one. */
@Composable
fun AddToPlaylistDialog(
    playlists: List<PlaylistSummaryRow>,
    onPick: (PlaylistSummaryRow) -> Unit,
    onCreateNew: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexColors.Surface,
        title = { Text("Add to playlist") },
        text = {
            LazyColumn(Modifier.heightIn(max = 360.dp)) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable(onClick = onCreateNew)
                            .padding(vertical = VortexSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Rounded.Add, contentDescription = null, tint = VortexColors.AccentActive)
                        Spacer(Modifier.width(VortexSpacing.md))
                        Text("New playlist", style = MaterialTheme.typography.titleSmall, color = VortexColors.AccentActive)
                    }
                }
                items(playlists, key = { it.id }) { playlist ->
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { onPick(playlist) }
                            .padding(vertical = VortexSpacing.md),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.AutoMirrored.Rounded.QueueMusic, contentDescription = null, tint = VortexColors.TextSecondary)
                        Spacer(Modifier.width(VortexSpacing.md))
                        Column(Modifier.weight(1f)) {
                            Text(playlist.name, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            Text("${playlist.itemCount} tracks", style = MaterialTheme.typography.bodySmall, color = VortexColors.TextSecondary)
                        }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/**
 * Name entry for creating or renaming a playlist. [error] comes from
 * PlaylistRepository.validateName so the rules live in one place.
 */
@Composable
fun PlaylistNameDialog(
    title: String,
    confirmLabel: String,
    initialName: String,
    error: String?,
    onConfirm: (String) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initialName) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = VortexColors.Surface,
        title = { Text(title) },
        text = {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it.take(80) },
                singleLine = true,
                label = { Text("Playlist name") },
                isError = error != null,
                supportingText = { if (error != null) Text(error) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = VortexColors.Accent,
                    focusedLabelColor = VortexColors.AccentActive,
                    cursorColor = VortexColors.AccentActive,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(name) }, enabled = name.isNotBlank()) { Text(confirmLabel) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
