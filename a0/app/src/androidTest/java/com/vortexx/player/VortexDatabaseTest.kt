package com.vortexx.player

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.vortexx.player.data.db.FavoriteEntity
import com.vortexx.player.data.db.MediaKind
import com.vortexx.player.data.db.VortexDatabase
import com.vortexx.player.data.db.WatchProgressEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** Playlist, favourite and watch-history persistence. Runs on any emulator or device. */
@RunWith(AndroidJUnit4::class)
class VortexDatabaseTest {

    private lateinit var db: VortexDatabase

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(ApplicationProvider.getApplicationContext(), VortexDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun playlistKeepsInsertionOrderAcrossAppends() = runTest {
        val dao = db.playlistDao()
        val id = dao.createWithSongs("Road trip", listOf(5L, 3L), now = 1L)
        dao.appendSongs(id, listOf(9L), now = 2L)
        val items = dao.observeItems(id).first()
        assertEquals(listOf(5L, 3L, 9L), items.map { it.songId })
        assertEquals(listOf(0, 1, 2), items.map { it.position })
        val summary = dao.observeSummaries().first().single()
        assertEquals(3, summary.itemCount)
        assertEquals(2L, summary.updatedAt)
    }

    @Test
    fun deletingPlaylistCascadesToItems() = runTest {
        val dao = db.playlistDao()
        val id = dao.createWithSongs("Temp", listOf(1L, 2L), now = 1L)
        dao.deletePlaylist(id)
        assertTrue(dao.observeItems(id).first().isEmpty())
        assertNull(dao.observePlaylist(id).first())
    }

    @Test
    fun playlistNamesAreCaseInsensitiveForDuplicates() = runTest {
        val dao = db.playlistDao()
        dao.createWithSongs("Chill", emptyList(), now = 1L)
        assertEquals(1, dao.countByName("chill"))
    }

    @Test
    fun favouritesToggle() = runTest {
        val dao = db.favoritesDao()
        dao.insert(FavoriteEntity(MediaKind.SONG, 42L, addedAt = 1L))
        assertTrue(dao.exists(MediaKind.SONG, 42L))
        assertEquals(listOf(42L), dao.observeIds(MediaKind.SONG).first())
        dao.delete(MediaKind.SONG, 42L)
        assertFalse(dao.exists(MediaKind.SONG, 42L))
    }

    @Test
    fun watchProgressUpsertsAndClears() = runTest {
        val dao = db.watchProgressDao()
        dao.upsert(WatchProgressEntity(7L, positionMs = 1_000, durationMs = 60_000, updatedAt = 1))
        dao.upsert(WatchProgressEntity(7L, positionMs = 30_000, durationMs = 60_000, updatedAt = 2))
        assertEquals(30_000L, dao.get(7L)?.positionMs)
        dao.clear()
        assertNull(dao.get(7L))
    }
}
