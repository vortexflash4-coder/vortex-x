package com.vortexx.player.domain.model

/**
 * Pure-Kotlin media models. URIs are kept as strings so domain logic stays
 * testable on the JVM without Android framework classes.
 */
data class Song(
    val id: Long,
    val contentUri: String,
    val title: String,
    val artist: String,
    val album: String,
    val albumId: Long,
    val genre: String?,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mimeType: String?,
    val folder: String,
    val displayName: String,
    val trackNumber: Int,
    val year: Int,
) {
    val albumArtUri: String get() = "content://media/external/audio/albumart/$albumId"
}

data class Video(
    val id: Long,
    val contentUri: String,
    val title: String,
    val displayName: String,
    val durationMs: Long,
    val sizeBytes: Long,
    val dateAddedSec: Long,
    val mimeType: String?,
    val width: Int,
    val height: Int,
    val folder: String,
)

enum class CollectionType { Album, Artist, Genre, Folder }

/** A derived grouping of songs (album, artist, genre or folder). */
data class SongCollection(
    val type: CollectionType,
    val key: String,
    val title: String,
    val subtitle: String,
    val songs: List<Song>,
) {
    val totalDurationMs: Long get() = songs.sumOf { it.durationMs }
    val coverSong: Song? get() = songs.firstOrNull()
}

data class MediaFolder(
    val path: String,
    val name: String,
    val songCount: Int,
    val videoCount: Int,
)

enum class SortField { Name, DateAdded, Duration, Size }

data class SortSpec(
    val field: SortField = SortField.Name,
    val ascending: Boolean = true,
)

/** Loading/ready/failure wrapper used by library flows. */
sealed interface LibraryState<out T> {
    data object Loading : LibraryState<Nothing>
    data class Ready<T>(val data: T) : LibraryState<T>
    data class Failed(val message: String) : LibraryState<Nothing>
}

fun <T> LibraryState<T>.dataOrNull(): T? = (this as? LibraryState.Ready<T>)?.data
