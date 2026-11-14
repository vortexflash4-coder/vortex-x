package com.vortexx.player.data.repo

import com.vortexx.player.data.db.FavoriteEntity
import com.vortexx.player.data.db.FavoritesDao
import com.vortexx.player.data.db.MediaKind
import com.vortexx.player.data.db.PlaylistDao
import com.vortexx.player.data.db.PlaylistEntity
import com.vortexx.player.data.db.PlaylistItemEntity
import com.vortexx.player.data.db.PlaylistSummaryRow
import com.vortexx.player.data.db.WatchProgressDao
import com.vortexx.player.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesRepository @Inject constructor(
    private val dao: FavoritesDao,
) {
    val favoriteSongIds: Flow<List<Long>> = dao.observeIds(MediaKind.SONG)
    val favoriteSongIdSet: Flow<Set<Long>> = favoriteSongIds.map { it.toSet() }

    /** Returns the new favourite state. */
    suspend fun toggleSong(songId: Long): Boolean {
        return if (dao.exists(MediaKind.SONG, songId)) {
            dao.delete(MediaKind.SONG, songId)
            false
        } else {
            dao.insert(FavoriteEntity(MediaKind.SONG, songId, System.currentTimeMillis()))
            true
        }
    }
}

sealed interface PlaylistNameResult {
    data class Valid(val name: String) : PlaylistNameResult
    data class Invalid(val reason: String) : PlaylistNameResult
}

@Singleton
class PlaylistRepository @Inject constructor(
    private val dao: PlaylistDao,
) {
    val playlists: Flow<List<PlaylistSummaryRow>> = dao.observeSummaries()

    fun playlist(id: Long): Flow<PlaylistEntity?> = dao.observePlaylist(id)

    fun items(id: Long): Flow<List<PlaylistItemEntity>> = dao.observeItems(id)

    suspend fun validateName(raw: String, ignoreExisting: Boolean = false): PlaylistNameResult {
        val name = raw.trim()
        return when {
            name.isEmpty() -> PlaylistNameResult.Invalid("Enter a name")
            name.length > MAX_NAME -> PlaylistNameResult.Invalid("Use $MAX_NAME characters or fewer")
            !ignoreExisting && dao.countByName(name) > 0 -> PlaylistNameResult.Invalid("A playlist with this name already exists")
            else -> PlaylistNameResult.Valid(name)
        }
    }

    suspend fun create(name: String, songIds: List<Long> = emptyList()): Long =
        dao.createWithSongs(name, songIds, System.currentTimeMillis())

    suspend fun rename(id: Long, name: String) = dao.rename(id, name, System.currentTimeMillis())

    suspend fun delete(id: Long) = dao.deletePlaylist(id)

    suspend fun addSongs(id: Long, songIds: List<Long>) = dao.appendSongs(id, songIds, System.currentTimeMillis())

    suspend fun removeItem(playlistId: Long, itemId: Long) {
        dao.deleteItem(itemId)
        dao.touch(playlistId, System.currentTimeMillis())
    }

    companion object {
        const val MAX_NAME = 60
    }
}

@Singleton
class WatchHistoryRepository @Inject constructor(
    private val dao: WatchProgressDao,
) {
    val all: Flow<List<WatchProgressEntity>> = dao.observeAll()

    suspend fun get(videoId: Long): WatchProgressEntity? = dao.get(videoId)

    suspend fun save(videoId: Long, positionMs: Long, durationMs: Long) {
        if (videoId < 0) return
        dao.upsert(WatchProgressEntity(videoId, positionMs.coerceAtLeast(0), durationMs.coerceAtLeast(0), System.currentTimeMillis()))
    }

    suspend fun clear() = dao.clear()
}
