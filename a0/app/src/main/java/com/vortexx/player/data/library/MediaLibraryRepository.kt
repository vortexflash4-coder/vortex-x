package com.vortexx.player.data.library

import android.content.ContentResolver
import android.content.ContentUris
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import com.vortexx.player.data.settings.SettingsRepository
import com.vortexx.player.di.ApplicationScope
import com.vortexx.player.domain.library.LibraryLogic
import com.vortexx.player.domain.model.LibraryState
import com.vortexx.player.domain.model.Song
import com.vortexx.player.domain.model.Video
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Indexes shared audio and video through MediaStore (scoped-storage friendly).
 * Results refresh automatically when MediaStore changes, when [refresh] is
 * called (e.g. after a permission grant), or when library settings change.
 */
@OptIn(ExperimentalCoroutinesApi::class, FlowPreview::class)
@Singleton
class MediaLibraryRepository @Inject constructor(
    @ApplicationContext private val context: Context,
    settingsRepository: SettingsRepository,
    @ApplicationScope appScope: CoroutineScope,
) {
    private val resolver: ContentResolver = context.contentResolver
    private val manualRefresh = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    fun refresh() {
        manualRefresh.tryEmit(Unit)
    }

    private fun triggers(uri: Uri): Flow<Unit> =
        merge(observeUri(uri), manualRefresh)
            .onStart { emit(Unit) }
            .debounce(250L)

    val songs: StateFlow<LibraryState<List<Song>>> =
        combine(
            triggers(MediaStore.Audio.Media.EXTERNAL_CONTENT_URI),
            settingsRepository.settings.map { it.minAudioDurationMs }.distinctUntilChanged(),
        ) { _, minDuration -> minDuration }
            .mapLatest<Long, LibraryState<List<Song>>> { minDuration ->
                runCatching { querySongs(minDuration) }.fold(
                    onSuccess = { LibraryState.Ready(it) },
                    onFailure = { LibraryState.Failed(it.message ?: it.javaClass.simpleName) },
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), LibraryState.Loading)

    val videos: StateFlow<LibraryState<List<Video>>> =
        triggers(MediaStore.Video.Media.EXTERNAL_CONTENT_URI)
            .mapLatest<Unit, LibraryState<List<Video>>> {
                runCatching { queryVideos() }.fold(
                    onSuccess = { LibraryState.Ready(it) },
                    onFailure = { LibraryState.Failed(it.message ?: it.javaClass.simpleName) },
                )
            }
            .flowOn(Dispatchers.IO)
            .stateIn(appScope, SharingStarted.WhileSubscribed(5_000L), LibraryState.Loading)

    private fun observeUri(uri: Uri): Flow<Unit> = callbackFlow {
        val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
            override fun onChange(selfChange: Boolean) {
                trySend(Unit)
            }
        }
        resolver.registerContentObserver(uri, true, observer)
        awaitClose { resolver.unregisterContentObserver(observer) }
    }

    private fun querySongs(minDurationMs: Long): List<Song> {
        val collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI
        val projection = buildList {
            add(MediaStore.Audio.Media._ID)
            add(MediaStore.Audio.Media.TITLE)
            add(MediaStore.Audio.Media.ARTIST)
            add(MediaStore.Audio.Media.ALBUM)
            add(MediaStore.Audio.Media.ALBUM_ID)
            add(MediaStore.Audio.Media.DURATION)
            add(MediaStore.Audio.Media.SIZE)
            add(MediaStore.Audio.Media.DATE_ADDED)
            add(MediaStore.Audio.Media.MIME_TYPE)
            add(MediaStore.Audio.Media.DISPLAY_NAME)
            add(MediaStore.Audio.Media.TRACK)
            add(MediaStore.Audio.Media.YEAR)
            add(folderColumn())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) add(MediaStore.Audio.Media.GENRE)
        }.toTypedArray()

        val selection = "${MediaStore.Audio.Media.IS_MUSIC} != 0 AND ${MediaStore.Audio.Media.DURATION} >= ?"
        val args = arrayOf(minDurationMs.toString())
        val songs = ArrayList<Song>()

        resolver.query(collection, projection, selection, args, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE)
            val artistCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST)
            val albumCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM)
            val albumIdCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ALBUM_ID)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.MIME_TYPE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DISPLAY_NAME)
            val trackCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TRACK)
            val yearCol = c.getColumnIndexOrThrow(MediaStore.Audio.Media.YEAR)
            val folderCol = c.getColumnIndexOrThrow(folderColumn())
            val genreCol = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                c.getColumnIndex(MediaStore.Audio.Media.GENRE)
            } else {
                -1
            }

            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.stringOrEmpty(nameCol)
                val title = c.stringOrEmpty(titleCol).ifBlank { displayName.substringBeforeLast('.') }
                songs += Song(
                    id = id,
                    contentUri = ContentUris.withAppendedId(collection, id).toString(),
                    title = title,
                    artist = c.stringOrEmpty(artistCol).cleanUnknown(LibraryLogic.UNKNOWN_ARTIST),
                    album = c.stringOrEmpty(albumCol).cleanUnknown(LibraryLogic.UNKNOWN_ALBUM),
                    albumId = c.getLong(albumIdCol),
                    genre = if (genreCol >= 0) c.getString(genreCol)?.takeIf { it.isNotBlank() } else null,
                    durationMs = c.getLong(durationCol),
                    sizeBytes = c.getLong(sizeCol),
                    dateAddedSec = c.getLong(dateCol),
                    mimeType = c.getString(mimeCol),
                    folder = folderFrom(c.stringOrEmpty(folderCol)),
                    displayName = displayName,
                    // MediaStore encodes disc number in the thousands digit.
                    trackNumber = c.getInt(trackCol) % 1000,
                    year = c.getInt(yearCol),
                )
            }
        }
        return songs
    }

    private fun queryVideos(): List<Video> {
        val collection = MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        val projection = arrayOf(
            MediaStore.Video.Media._ID,
            MediaStore.Video.Media.TITLE,
            MediaStore.Video.Media.DISPLAY_NAME,
            MediaStore.Video.Media.DURATION,
            MediaStore.Video.Media.SIZE,
            MediaStore.Video.Media.DATE_ADDED,
            MediaStore.Video.Media.MIME_TYPE,
            MediaStore.Video.Media.WIDTH,
            MediaStore.Video.Media.HEIGHT,
            folderColumn(),
        )
        val videos = ArrayList<Video>()
        resolver.query(collection, projection, null, null, null)?.use { c ->
            val idCol = c.getColumnIndexOrThrow(MediaStore.Video.Media._ID)
            val titleCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.TITLE)
            val nameCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DISPLAY_NAME)
            val durationCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DURATION)
            val sizeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.SIZE)
            val dateCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.DATE_ADDED)
            val mimeCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.MIME_TYPE)
            val widthCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.WIDTH)
            val heightCol = c.getColumnIndexOrThrow(MediaStore.Video.Media.HEIGHT)
            val folderCol = c.getColumnIndexOrThrow(folderColumn())
            while (c.moveToNext()) {
                val id = c.getLong(idCol)
                val displayName = c.stringOrEmpty(nameCol)
                videos += Video(
                    id = id,
                    contentUri = ContentUris.withAppendedId(collection, id).toString(),
                    title = c.stringOrEmpty(titleCol).ifBlank { displayName.substringBeforeLast('.') },
                    displayName = displayName,
                    durationMs = c.getLong(durationCol),
                    sizeBytes = c.getLong(sizeCol),
                    dateAddedSec = c.getLong(dateCol),
                    mimeType = c.getString(mimeCol),
                    width = c.getInt(widthCol),
                    height = c.getInt(heightCol),
                    folder = folderFrom(c.stringOrEmpty(folderCol)),
                )
            }
        }
        return videos
    }

    /** RELATIVE_PATH on Android 10+, the absolute DATA path on older releases. */
    @Suppress("DEPRECATION")
    private fun folderColumn(): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.MediaColumns.RELATIVE_PATH else MediaStore.MediaColumns.DATA

    private fun folderFrom(raw: String): String {
        if (raw.isEmpty()) return ""
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            raw.trimEnd('/')
        } else {
            val parent = File(raw).parent ?: return ""
            parent.removePrefix("/storage/emulated/0/").removePrefix("/storage/emulated/0")
        }
    }

    private fun Cursor.stringOrEmpty(index: Int): String = if (index >= 0) getString(index) ?: "" else ""

    private fun String.cleanUnknown(fallback: String): String =
        if (isBlank() || this == MediaStore.UNKNOWN_STRING) fallback else this
}
