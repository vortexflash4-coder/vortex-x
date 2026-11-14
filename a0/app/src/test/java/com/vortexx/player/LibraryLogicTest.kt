package com.vortexx.player

import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.CollectionType
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.SortField
import com.vortexx.player.domain.model.SortSpec
import com.vortexx.player.domain.model.Video
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryLogicTest {

    private fun song(
        id: Long,
        title: String,
        artist: String = "Artist",
        album: String = "Album",
        albumId: Long = 1,
        duration: Long = 180_000,
        size: Long = 5_000_000,
        added: Long = 1_000,
        folder: String = "Music",
        genre: String? = "Rock",
        track: Int = 1,
    ) = Song(
        id = id, contentUri = "content://audio/$id", title = title, artist = artist, album = album,
        albumId = albumId, genre = genre, durationMs = duration, sizeBytes = size, dateAddedSec = added,
        mimeType = "audio/mpeg", folder = folder, displayName = "$title.mp3", trackNumber = track, year = 2024,
    )

    private val library = listOf(
        song(1, "beta", artist = "Zed", album = "Second", albumId = 2, duration = 200_000, size = 3, added = 30, track = 2),
        song(2, "Alpha", artist = "Amy", album = "First", albumId = 1, duration = 100_000, size = 9, added = 10, track = 1),
        song(3, "gamma", artist = "Zed", album = "Second", albumId = 2, duration = 300_000, size = 6, added = 20, track = 1, folder = "Music/Live", genre = null),
    )

    @Test
    fun sortByNameIsCaseInsensitive() {
        val sorted = LibraryLogic.sortSongs(library, SortSpec(SortField.Name, ascending = true))
        assertEquals(listOf("Alpha", "beta", "gamma"), sorted.map { it.title })
    }

    @Test
    fun sortByDateDescending() {
        val sorted = LibraryLogic.sortSongs(library, SortSpec(SortField.DateAdded, ascending = false))
        assertEquals(listOf(1L, 3L, 2L), sorted.map { it.id })
    }

    @Test
    fun sortByDurationAndSize() {
        assertEquals(listOf(2L, 1L, 3L), LibraryLogic.sortSongs(library, SortSpec(SortField.Duration)).map { it.id })
        assertEquals(listOf(1L, 3L, 2L), LibraryLogic.sortSongs(library, SortSpec(SortField.Size)).map { it.id })
    }

    @Test
    fun filterMatchesTitleArtistAlbumAndGenre() {
        assertEquals(listOf(1L, 3L), LibraryLogic.filterSongs(library, "zed").map { it.id })
        assertEquals(listOf(2L), LibraryLogic.filterSongs(library, "FIRST").map { it.id })
        assertEquals(2, LibraryLogic.filterSongs(library, "rock").size)
        assertEquals(3, LibraryLogic.filterSongs(library, "   ").size)
    }

    @Test
    fun albumsGroupByAlbumIdAndOrderTracks() {
        val albums = LibraryLogic.albums(library)
        assertEquals(listOf("First", "Second"), albums.map { it.title })
        val second = albums.first { it.title == "Second" }
        assertEquals(listOf("gamma", "beta"), second.songs.map { it.title })
        assertEquals("Zed", second.subtitle)
    }

    @Test
    fun genresUseFallbackForMissingGenre() {
        val genres = LibraryLogic.genres(library).map { it.title }
        assertTrue(LibraryLogic.UNKNOWN_GENRE in genres)
        assertTrue("Rock" in genres)
    }

    @Test
    fun collectionLookupByTypeAndKey() {
        val folder = LibraryLogic.collection(library, CollectionType.Folder, "Music/Live")
        assertNotNull(folder)
        assertEquals("Live", folder!!.title)
        assertEquals(1, folder.songs.size)
    }

    @Test
    fun duplicateDetectionRequiresSimilarDuration() {
        val dupes = listOf(
            song(10, "Same Song", artist = "Band", duration = 180_000),
            song(11, "same song!", artist = "band", duration = 181_500),
            song(12, "Same Song", artist = "Band", duration = 240_000),
            song(13, "Other", artist = "Band", duration = 180_000),
        )
        val groups = LibraryLogic.duplicateGroups(dupes)
        assertEquals(1, groups.size)
        assertEquals(setOf(10L, 11L), groups.first().map { it.id }.toSet())
    }

    @Test
    fun mediaFoldersMergeAudioAndVideoCounts() {
        val videos = listOf(
            Video(1, "content://video/1", "Clip", "clip.mp4", 1000, 10, 1, "video/mp4", 1920, 1080, "Music"),
            Video(2, "content://video/2", "Film", "film.mkv", 1000, 10, 1, "video/x-matroska", 3840, 2160, "Movies"),
        )
        val folders = LibraryLogic.mediaFolders(library, videos).associateBy { it.path }
        assertEquals(2, folders.getValue("Music").songCount)
        assertEquals(1, folders.getValue("Music").videoCount)
        assertEquals(0, folders.getValue("Movies").songCount)
        assertEquals("Live", folders.getValue("Music/Live").name)
    }

    @Test
    fun folderDisplayNameHandlesRootAndTrailingSlash() {
        assertEquals("Device root", LibraryLogic.folderDisplayName(""))
        assertEquals("Camera", LibraryLogic.folderDisplayName("DCIM/Camera/"))
    }
}
