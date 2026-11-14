package com.vortexx.player.domain.library

import com.vortexx.player.domain.model.CollectionType
import com.vortexx.player.domain.model.MediaFolder
import com.vortexx.player.domain.model.SongCollection
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.SortField
import com.vortexx.player.domain.model.SortSpec
import com.vortexx.player.domain.model.Video
import java.util.Locale

/**
 * Pure functions for searching, sorting, grouping and duplicate detection.
 * Everything here is covered by JVM unit tests (LibraryLogicTest).
 */
object LibraryLogic {

    const val UNKNOWN_ARTIST = "Unknown artist"
    const val UNKNOWN_ALBUM = "Unknown album"
    const val UNKNOWN_GENRE = "Unknown genre"

    private val nameComparator: Comparator<String> =
        Comparator { a, b -> a.lowercase(Locale.ROOT).compareTo(b.lowercase(Locale.ROOT)) }

    fun sortSongs(songs: List<Song>, spec: SortSpec): List<Song> {
        val comparator: Comparator<Song> = when (spec.field) {
            SortField.Name -> compareBy(nameComparator) { it.title }
            SortField.DateAdded -> compareBy { it.dateAddedSec }
            SortField.Duration -> compareBy { it.durationMs }
            SortField.Size -> compareBy { it.sizeBytes }
        }
        val withTieBreak = comparator.thenBy { it.id }
        return if (spec.ascending) songs.sortedWith(withTieBreak) else songs.sortedWith(withTieBreak.reversed())
    }

    fun sortVideos(videos: List<Video>, spec: SortSpec): List<Video> {
        val comparator: Comparator<Video> = when (spec.field) {
            SortField.Name -> compareBy(nameComparator) { it.title }
            SortField.DateAdded -> compareBy { it.dateAddedSec }
            SortField.Duration -> compareBy { it.durationMs }
            SortField.Size -> compareBy { it.sizeBytes }
        }
        val withTieBreak = comparator.thenBy { it.id }
        return if (spec.ascending) videos.sortedWith(withTieBreak) else videos.sortedWith(withTieBreak.reversed())
    }

    fun filterSongs(songs: List<Song>, query: String): List<Song> {
        val q = query.trim()
        if (q.isEmpty()) return songs
        return songs.filter { song ->
            song.title.contains(q, ignoreCase = true) ||
                song.artist.contains(q, ignoreCase = true) ||
                song.album.contains(q, ignoreCase = true) ||
                (song.genre?.contains(q, ignoreCase = true) == true)
        }
    }

    fun filterVideos(videos: List<Video>, query: String): List<Video> {
        val q = query.trim()
        if (q.isEmpty()) return videos
        return videos.filter { it.title.contains(q, ignoreCase = true) || it.folder.contains(q, ignoreCase = true) }
    }

    fun albums(songs: List<Song>): List<SongCollection> =
        songs.groupBy { it.albumId }
            .map { (albumId, group) ->
                val ordered = group.sortedWith(compareBy<Song> { it.trackNumber }.thenBy(nameComparator) { it.title })
                val first = ordered.first()
                val artists = group.map { it.artist }.distinct()
                SongCollection(
                    type = CollectionType.Album,
                    key = albumId.toString(),
                    title = first.album.ifBlank { UNKNOWN_ALBUM },
                    subtitle = if (artists.size == 1) artists.first() else "Various artists",
                    songs = ordered,
                )
            }
            .sortedWith(compareBy(nameComparator) { it.title })

    fun artists(songs: List<Song>): List<SongCollection> =
        groupByLabel(songs, CollectionType.Artist) { it.artist.ifBlank { UNKNOWN_ARTIST } }

    fun genres(songs: List<Song>): List<SongCollection> =
        groupByLabel(songs, CollectionType.Genre) { it.genre?.takeIf { g -> g.isNotBlank() } ?: UNKNOWN_GENRE }

    fun folders(songs: List<Song>): List<SongCollection> =
        groupByLabel(songs, CollectionType.Folder) { it.folder.ifBlank { "/" } }

    private fun groupByLabel(
        songs: List<Song>,
        type: CollectionType,
        label: (Song) -> String,
    ): List<SongCollection> =
        songs.groupBy(label)
            .map { (name, group) ->
                val ordered = group.sortedWith(compareBy(nameComparator) { it.title })
                SongCollection(
                    type = type,
                    key = name,
                    title = if (type == CollectionType.Folder) folderDisplayName(name) else name,
                    subtitle = pluralize(group.size, "song"),
                    songs = ordered,
                )
            }
            .sortedWith(compareBy(nameComparator) { it.title })

    fun collection(songs: List<Song>, type: CollectionType, key: String): SongCollection? = when (type) {
        CollectionType.Album -> albums(songs).firstOrNull { it.key == key }
        CollectionType.Artist -> artists(songs).firstOrNull { it.key == key }
        CollectionType.Genre -> genres(songs).firstOrNull { it.key == key }
        CollectionType.Folder -> folders(songs).firstOrNull { it.key == key }
    }

    /** Merges audio and video folders for the Media Explorer. */
    fun mediaFolders(songs: List<Song>, videos: List<Video>): List<MediaFolder> {
        val songCounts = songs.groupingBy { it.folder.ifBlank { "/" } }.eachCount()
        val videoCounts = videos.groupingBy { it.folder.ifBlank { "/" } }.eachCount()
        return (songCounts.keys + videoCounts.keys)
            .map { path ->
                MediaFolder(
                    path = path,
                    name = folderDisplayName(path),
                    songCount = songCounts[path] ?: 0,
                    videoCount = videoCounts[path] ?: 0,
                )
            }
            .sortedWith(compareBy(nameComparator) { it.name })
    }

    fun folderDisplayName(path: String): String {
        val trimmed = path.trimEnd('/')
        if (trimmed.isEmpty()) return "Device root"
        return trimmed.substringAfterLast('/')
    }

    /**
     * Groups likely duplicates: same normalised title + artist and durations
     * within two seconds. Returns only groups with more than one entry.
     */
    fun duplicateGroups(songs: List<Song>): List<List<Song>> {
        val byKey = songs.groupBy { normalise(it.title) + "\u0000" + normalise(it.artist) }
        val result = mutableListOf<List<Song>>()
        for (group in byKey.values) {
            if (group.size < 2) continue
            val sorted = group.sortedBy { it.durationMs }
            var cluster = mutableListOf(sorted.first())
            for (song in sorted.drop(1)) {
                if (song.durationMs - cluster.last().durationMs <= 2_000L) {
                    cluster.add(song)
                } else {
                    if (cluster.size > 1) result.add(cluster)
                    cluster = mutableListOf(song)
                }
            }
            if (cluster.size > 1) result.add(cluster)
        }
        return result
    }

    private fun normalise(value: String): String =
        value.lowercase(Locale.ROOT).filter { it.isLetterOrDigit() }

    fun pluralize(count: Int, noun: String): String = if (count == 1) "1 $noun" else "$count ${noun}s"
}
