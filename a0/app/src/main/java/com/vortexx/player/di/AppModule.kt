package com.vortexx.player.di

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStoreFile
import androidx.room.Room
import com.vortexx.player.data.db.FavoritesDao
import com.vortexx.player.data.db.PlaylistDao
import com.vortexx.player.data.db.VortexDatabase
import com.vortexx.player.data.db.WatchProgressDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Qualifier
import javax.inject.Singleton

/** Process-wide scope for work that must outlive a screen (e.g. final progress saves). */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    @ApplicationScope
    fun provideApplicationScope(): CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): VortexDatabase =
        Room.databaseBuilder(context, VortexDatabase::class.java, VortexDatabase.NAME).build()

    @Provides
    fun provideFavoritesDao(database: VortexDatabase): FavoritesDao = database.favoritesDao()

    @Provides
    fun providePlaylistDao(database: VortexDatabase): PlaylistDao = database.playlistDao()

    @Provides
    fun provideWatchProgressDao(database: VortexDatabase): WatchProgressDao = database.watchProgressDao()

    @Provides
    @Singleton
    fun provideSettingsDataStore(@ApplicationContext context: Context): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            produceFile = { context.preferencesDataStoreFile("vortex_settings") },
        )
}
