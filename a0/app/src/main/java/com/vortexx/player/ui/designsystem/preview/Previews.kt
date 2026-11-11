package com.vortexx.player.ui.designsystem.preview

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.VideoLibrary
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.Video
import com.vortexx.player.ui.designsystem.components.EmptyState
import com.vortexx.player.ui.designsystem.components.InlineNotice
import com.vortexx.player.ui.designsystem.components.NoticeTone
import com.vortexx.player.ui.designsystem.components.PillChip
import com.vortexx.player.ui.designsystem.components.PrimaryButton
import com.vortexx.player.ui.designsystem.components.SecondaryButton
import com.vortexx.player.ui.designsystem.components.SectionHeader
import com.vortexx.player.ui.designsystem.components.SongRow
import com.vortexx.player.ui.designsystem.components.StatTile
import com.vortexx.player.ui.designsystem.components.ToggleRow
import com.vortexx.player.ui.designsystem.components.VortexTopBar
import com.vortexx.player.ui.designsystem.theme.VortexColors
import com.vortexx.player.ui.designsystem.theme.VortexSpacing
import com.vortexx.player.ui.designsystem.theme.VortexTheme

/** Phone, landscape phone, tablet and large-font previews in one annotation. */
@Preview(name = "Phone", device = "spec:width=411dp,height=891dp", showBackground = true, backgroundColor = 0xFF08090D)
@Preview(name = "Phone landscape", device = "spec:width=891dp,height=411dp", showBackground = true, backgroundColor = 0xFF08090D)
@Preview(name = "Tablet", device = "spec:width=1280dp,height=800dp,dpi=240", showBackground = true, backgroundColor = 0xFF08090D)
@Preview(name = "Large font", device = "spec:width=411dp,height=891dp", fontScale = 1.6f, showBackground = true, backgroundColor = 0xFF08090D)
annotation class VortexPreviews

/** Small component previews on the app background. */
@Preview(showBackground = true, backgroundColor = 0xFF08090D, widthDp = 380)
annotation class VortexComponentPreview

/** Wraps preview content in the theme and background. */
@Composable
fun PreviewSurface(content: @Composable () -> Unit) {
    VortexTheme {
        Box(
            Modifier
                .fillMaxSize()
                .background(VortexColors.Background),
        ) { content() }
    }
}

object PreviewData {
    val songs: List<Song> = listOf(
        song(1, "Midnight Circuit", "Neon Harbor", "Afterglow", 214_000),
        song(2, "Crimson Static", "Neon Harbor", "Afterglow", 187_000),
        song(3, "Low Orbit", "Sable Echo", "Gravity Wells", 302_000),
        song(4, "Glass Horizon", "Arden Vale", "Parallax", 245_000),
    )

    val videos: List<Video> = listOf(
        Video(11, "content://preview/video/11", "Coastline Drone 4K", "coast.mp4", 312_000, 820_000_000, 1_700_000_000, "video/mp4", 3840, 2160, "Movies"),
        Video(12, "content://preview/video/12", "Studio Session", "session.mkv", 1_845_000, 1_400_000_000, 1_700_100_000, "video/x-matroska", 1920, 1080, "Movies/Sessions"),
    )

    private fun song(id: Long, title: String, artist: String, album: String, duration: Long) = Song(
        id = id,
        contentUri = "content://preview/audio/$id",
        title = title,
        artist = artist,
        album = album,
        albumId = id,
        genre = "Electronic",
        durationMs = duration,
        sizeBytes = duration * 40,
        dateAddedSec = 1_700_000_000 + id,
        mimeType = "audio/flac",
        folder = "Music/$artist",
        displayName = "$title.flac",
        trackNumber = id.toInt(),
        year = 2024,
    )
}

@VortexComponentPreview
@Composable
private fun ComponentGalleryPreview() {
    PreviewSurface {
        Column {
            VortexTopBar(title = "Music", subtitle = "1,204 songs")
            SectionHeader(title = "Recently added", actionLabel = "See all", onAction = {})
            Row(Modifier.padding(horizontal = VortexSpacing.lg)) {
                PillChip(label = "Songs", selected = true, onClick = {})
                Spacer(Modifier.width(VortexSpacing.sm))
                PillChip(label = "Albums", selected = false, onClick = {})
            }
            Spacer(Modifier.height(VortexSpacing.md))
            Row(Modifier.padding(horizontal = VortexSpacing.lg)) {
                StatTile(value = "1,204", label = "Songs", icon = Icons.Rounded.LibraryMusic, modifier = Modifier.weight(1f))
                Spacer(Modifier.width(VortexSpacing.sm))
                StatTile(value = "86", label = "Videos", icon = Icons.Rounded.VideoLibrary, modifier = Modifier.weight(1f))
            }
            PreviewData.songs.take(2).forEachIndexed { index, song ->
                SongRow(song = song, isCurrent = index == 0, isFavorite = index == 1, onClick = {}, actions = null)
            }
            ToggleRow(title = "Skip silence", summary = "Shortens silent passages", checked = true, onCheckedChange = {})
            InlineNotice(text = "Access limited to selected videos", tone = NoticeTone.Caution, actionLabel = "Change", onAction = {}, modifier = Modifier.padding(VortexSpacing.lg))
            Row(Modifier.padding(horizontal = VortexSpacing.lg)) {
                PrimaryButton(text = "Play", onClick = {})
                Spacer(Modifier.width(VortexSpacing.sm))
                SecondaryButton(text = "Shuffle", onClick = {})
            }
        }
    }
}

@VortexPreviews
@Composable
private fun EmptyStatePreview() {
    PreviewSurface {
        EmptyState(
            icon = Icons.Rounded.MusicNote,
            title = "No music found",
            message = "Add audio files to your device, then refresh the library.",
            actionLabel = "Refresh",
            onAction = {},
        )
    }
}
