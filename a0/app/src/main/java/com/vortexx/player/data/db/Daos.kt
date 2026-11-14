package com.vortexx.player.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

@Dao
interface FavoritesDao {
    @Query("SELECT mediaId FROM favorites WHERE mediaKind = :kind ORDER BY addedAt DESC")
    fun observeIds(kind: String): Flow<List<Long>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: FavoriteEntity)

    @Query("DELETE FROM favorites WHERE mediaKind = :kind AND mediaId = :mediaId")
    suspend fun delete(kind: String, mediaId: Long)

    @Query("SELECT EXISTS(SELECT 1 FROM favorites WHERE mediaKind = :kind AND mediaId = :mediaId)")
    suspend fun exists(kind: String, mediaId: Long): Boolean
}

@Dao
abstract class PlaylistDao {

    @Query(
        """
        SELECT p.id AS id, p.name AS name, p.updatedAt AS updatedAt, COUNT(i.id) AS itemCount
        FROM playlists p
        LEFT JOIN playlist_items i ON i.playlistId = p.id
        GROUP BY p.id
        ORDER BY p.updatedAt DESC
        """,
    )
    abstract fun observeSummaries(): Flow<List<PlaylistSummaryRow>>

    @Query("SELECT * FROM playlists WHERE id = :playlistId")
    abstract fun observePlaylist(playlistId: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlist_items WHERE playlistId = :playlistId ORDER BY position ASC, id ASC")
    abstract fun observeItems(playlistId: Long): Flow<List<PlaylistItemEntity>>

    @Query("SELECT COUNT(*) FROM playlists WHERE name = :name COLLATE NOCASE")
    abstract suspend fun countByName(name: String): Int

    @Insert
    abstract suspend fun insertPlaylist(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, updatedAt = :now WHERE id = :playlistId")
    abstract suspend fun rename(playlistId: Long, name: String, now: Long)

    @Query("DELETE FROM playlists WHERE id = :playlistId")
    abstract suspend fun deletePlaylist(playlistId: Long)

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_items WHERE playlistId = :playlistId")
    abstract suspend fun maxPosition(playlistId: Long): Int

    @Insert
    abstract suspend fun insertItems(items: List<PlaylistItemEntity>)

    @Query("DELETE FROM playlist_items WHERE id = :itemId")
    abstract suspend fun deleteItem(itemId: Long)

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :playlistId")
    abstract suspend fun touch(playlistId: Long, now: Long)

    @Transaction
    open suspend fun appendSongs(playlistId: Long, songIds: List<Long>, now: Long) {
        if (songIds.isEmpty()) return
        val start = maxPosition(playlistId) + 1
        insertItems(
            songIds.mapIndexed { offset, songId ->
                PlaylistItemEntity(playlistId = playlistId, songId = songId, position = start + offset, addedAt = now)
            },
        )
        touch(playlistId, now)
    }

    @Transaction
    open suspend fun createWithSongs(name: String, songIds: List<Long>, now: Long): Long {
        val id = insertPlaylist(PlaylistEntity(name = name, createdAt = now, updatedAt = now))
        appendSongs(id, songIds, now)
        return id
    }
}

@Dao
interface WatchProgressDao {
    @Query("SELECT * FROM watch_progress WHERE videoId = :videoId")
    suspend fun get(videoId: Long): WatchProgressEntity?

    @Query("SELECT * FROM watch_progress ORDER BY updatedAt DESC")
    fun observeAll(): Flow<List<WatchProgressEntity>>

    @Upsert
    suspend fun upsert(entity: WatchProgressEntity)

    @Query("DELETE FROM watch_progress")
    suspend fun clear()
}
