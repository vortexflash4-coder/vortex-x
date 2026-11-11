package com.vortexx.player.data.db

import androidx.room.Database
import androidx.room.RoomDatabase

@Database(
    entities = [
        FavoriteEntity::class,
        PlaylistEntity::class,
        PlaylistItemEntity::class,
        WatchProgressEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class VortexDatabase : RoomDatabase() {
    abstract fun favoritesDao(): FavoritesDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun watchProgressDao(): WatchProgressDao

    companion object {
        const val NAME = "vortex.db"
    }
}
