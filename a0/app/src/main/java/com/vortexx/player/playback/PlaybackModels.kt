package com.vortexx.player.playback

import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.vortexx.player.domain.model.Song

enum class RepeatSetting { Off, All, One }

data class NowPlaying(
    val mediaId: String,
    val songId: Long?,
    val title: String,
    val artist: String,
    val album: String,
    val contentUri: String?,
)

data class QueueEntry(
    val index: Int,
    val mediaId: String,
    val songId: Long?,
    val title: String,
    val artist: String,
    val contentUri: String?,
)

data class PlaybackErrorInfo(
    val code: Int,
    val codeName: String,
    val message: String?,
)

data class PlaybackUiState(
    val isConnected: Boolean = false,
    val nowPlaying: NowPlaying? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val durationMs: Long = 0L,
    val shuffleEnabled: Boolean = false,
    val repeat: RepeatSetting = RepeatSetting.Off,
    val speed: Float = 1f,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val queue: List<QueueEntry> = emptyList(),
    val currentIndex: Int = -1,
    val error: PlaybackErrorInfo? = null,
)

data class PositionSnapshot(
    val positionMs: Long = 0L,
    val bufferedMs: Long = 0L,
    val durationMs: Long = 0L,
)

/**
 * MediaItem conventions shared by the UI and the session.
 * The playable URI travels in RequestMetadata because MediaItem.localConfiguration
 * is not transferred from a MediaController to the MediaSession.
 */
object MediaItems {
    private const val SONG_PREFIX = "song:"
    private const val URI_PREFIX = "uri:"

    fun fromSong(song: Song): MediaItem {
        val uri = Uri.parse(song.contentUri)
        return MediaItem.Builder()
            .setMediaId(SONG_PREFIX + song.id)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(song.title)
                    .setArtist(song.artist)
                    .setAlbumTitle(song.album)
                    .setGenre(song.genre)
                    .setArtworkUri(Uri.parse(song.albumArtUri))
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()
    }

    /** A file opened through the Storage Access Framework. */
    fun fromExternalUri(uri: Uri, title: String): MediaItem =
        MediaItem.Builder()
            .setMediaId(URI_PREFIX + uri)
            .setUri(uri)
            .setRequestMetadata(MediaItem.RequestMetadata.Builder().setMediaUri(uri).build())
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setIsPlayable(true)
                    .setIsBrowsable(false)
                    .build(),
            )
            .build()

    fun songId(mediaId: String): Long? =
        if (mediaId.startsWith(SONG_PREFIX)) mediaId.removePrefix(SONG_PREFIX).toLongOrNull() else null

    fun contentUri(item: MediaItem): String? =
        (item.requestMetadata.mediaUri ?: item.localConfiguration?.uri)?.toString()
}
