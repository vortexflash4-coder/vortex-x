package com.vortexx.player.data.db

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

object MediaKind {
    const val SONG = "song"
    const val VIDEO = "video"
}

@Entity(tableName = "favorites", primaryKeys = ["mediaKind", "mediaId"])
data class FavoriteEntity(
    val mediaKind: String,
    val mediaId: Long,
    val addedAt: Long,
)

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long,
    val updatedAt: Long,
)

@Entity(
    tableName = "playlist_items",
    foreignKeys = [
        ForeignKey(
            entity = PlaylistEntity::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("playlistId")],
)
data class PlaylistItemEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val playlistId: Long,
    val songId: Long,
    val position: Int,
    val addedAt: Long,
)

@Entity(tableName = "watch_progress")
data class WatchProgressEntity(
    @PrimaryKey val videoId: Long,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

/** Projection row for the playlist overview. */
data class PlaylistSummaryRow(
    val id: Long,
    val name: String,
    val updatedAt: Long,
    val itemCount: Int,
)
