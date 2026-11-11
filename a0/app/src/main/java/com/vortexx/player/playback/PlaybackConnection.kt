package com.vortexx.player.playback

import android.content.ComponentName
import android.content.Context
import android.net.Uri
import androidx.core.content.ContextCompat
import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.google.common.util.concurrent.ListenableFuture
import com.vortexx.player.domain.model.Song
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.isActive
import javax.inject.Inject
import javax.inject.Singleton

/**
 * UI-side bridge to [PlaybackService] through a Media3 MediaController.
 * Must be used from the main thread. Commands issued before the controller
 * connects are queued and replayed once it is ready.
 */
@Singleton
class PlaybackConnection @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    private var controllerFuture: ListenableFuture<MediaController>? = null
    private var controller: MediaController? = null
    private val pending = ArrayDeque<(MediaController) -> Unit>()
    private var cachedQueue: List<QueueEntry> = emptyList()

    private val _state = MutableStateFlow(PlaybackUiState())
    val state: StateFlow<PlaybackUiState> = _state.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            publish(player, rebuildQueue = events.contains(Player.EVENT_TIMELINE_CHANGED))
        }
    }

    fun connect() {
        if (controllerFuture != null) return
        val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
        val future = MediaController.Builder(context, token).buildAsync()
        controllerFuture = future
        future.addListener(
            {
                if (controllerFuture !== future) return@addListener
                val connected = runCatching { future.get() }.getOrNull()
                if (connected == null) {
                    controllerFuture = null
                    _state.value = PlaybackUiState(isConnected = false)
                    return@addListener
                }
                controller = connected
                connected.addListener(listener)
                publish(connected, rebuildQueue = true)
                while (pending.isNotEmpty()) pending.removeFirst().invoke(connected)
            },
            ContextCompat.getMainExecutor(context),
        )
    }

    /** Releases the controller. Playback continues in the service if it is running. */
    fun release() {
        controller?.removeListener(listener)
        controllerFuture?.let { MediaController.releaseFuture(it) }
        controllerFuture = null
        controller = null
        pending.clear()
        _state.value = _state.value.copy(isConnected = false)
    }

    private fun withController(action: (MediaController) -> Unit) {
        val current = controller
        if (current != null) {
            action(current)
        } else {
            pending.addLast(action)
            connect()
        }
    }

    /** Polls the playback position while collected (stops automatically when the UI stops). */
    fun positionUpdates(intervalMs: Long = 250L): Flow<PositionSnapshot> = flow {
        while (currentCoroutineContext().isActive) {
            val c = controller
            emit(
                if (c == null) {
                    PositionSnapshot()
                } else {
                    PositionSnapshot(
                        positionMs = c.currentPosition.coerceAtLeast(0L),
                        bufferedMs = c.bufferedPosition.coerceAtLeast(0L),
                        durationMs = c.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
                    )
                },
            )
            delay(intervalMs)
        }
    }.distinctUntilChanged().flowOn(Dispatchers.Main.immediate)

    // ---- Commands -------------------------------------------------------------------------

    fun playSongs(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false) {
        if (songs.isEmpty()) return
        val items = songs.map(MediaItems::fromSong)
        val start = startIndex.coerceIn(0, items.lastIndex)
        withController { c ->
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(items, start, 0L)
            c.prepare()
            c.play()
        }
    }

    fun shuffleAll(songs: List<Song>) {
        if (songs.isEmpty()) return
        playSongs(songs, startIndex = songs.indices.random(), shuffle = true)
    }

    fun playExternalUri(uri: Uri, title: String) = withController { c ->
        c.setMediaItem(MediaItems.fromExternalUri(uri, title))
        c.prepare()
        c.play()
    }

    fun playNext(songs: List<Song>) = withController { c ->
        val items = songs.map(MediaItems::fromSong)
        if (c.mediaItemCount == 0) {
            c.setMediaItems(items)
            c.prepare()
        } else {
            c.addMediaItems(c.currentMediaItemIndex + 1, items)
        }
    }

    fun addToQueue(songs: List<Song>) = withController { c ->
        val wasEmpty = c.mediaItemCount == 0
        c.addMediaItems(songs.map(MediaItems::fromSong))
        if (wasEmpty) c.prepare()
    }

    fun togglePlayPause() = withController { c ->
        when {
            c.playbackState == Player.STATE_IDLE -> {
                c.prepare()
                c.play()
            }
            c.playbackState == Player.STATE_ENDED -> {
                c.seekToDefaultPosition(0)
                c.play()
            }
            c.isPlaying -> c.pause()
            else -> c.play()
        }
    }

    fun pause() {
        controller?.pause()
    }

    fun next() = withController { it.seekToNext() }

    fun previous() = withController { it.seekToPrevious() }

    fun seekTo(positionMs: Long) = withController { it.seekTo(positionMs.coerceAtLeast(0L)) }

    fun setShuffle(enabled: Boolean) = withController { it.shuffleModeEnabled = enabled }

    fun setRepeat(repeat: RepeatSetting) = withController {
        it.repeatMode = when (repeat) {
            RepeatSetting.Off -> Player.REPEAT_MODE_OFF
            RepeatSetting.All -> Player.REPEAT_MODE_ALL
            RepeatSetting.One -> Player.REPEAT_MODE_ONE
        }
    }

    fun cycleRepeat() {
        val next = when (_state.value.repeat) {
            RepeatSetting.Off -> RepeatSetting.All
            RepeatSetting.All -> RepeatSetting.One
            RepeatSetting.One -> RepeatSetting.Off
        }
        setRepeat(next)
    }

    fun setSpeed(speed: Float) = withController { it.setPlaybackSpeed(speed.coerceIn(0.25f, 4f)) }

    fun skipToQueueIndex(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            if (c.playbackState == Player.STATE_IDLE) c.prepare()
            c.play()
        }
    }

    fun removeFromQueue(index: Int) = withController { c ->
        if (index in 0 until c.mediaItemCount) c.removeMediaItem(index)
    }

    fun moveQueueItem(from: Int, to: Int) = withController { c ->
        val count = c.mediaItemCount
        if (from in 0 until count && to in 0 until count && from != to) c.moveMediaItem(from, to)
    }

    fun clearQueue() = withController { c ->
        c.stop()
        c.clearMediaItems()
    }

    /** Recovers from a playback error by re-preparing the current item. */
    fun retry() = withController { c ->
        c.prepare()
        c.play()
    }

    /** Skips past a failing item. */
    fun skipFailed() = withController { c ->
        if (c.hasNextMediaItem()) {
            c.seekToNextMediaItem()
            c.prepare()
            c.play()
        } else {
            c.stop()
        }
    }

    // ---- State mapping --------------------------------------------------------------------

    private fun publish(player: Player, rebuildQueue: Boolean) {
        if (rebuildQueue) cachedQueue = buildQueue(player)
        val item = player.currentMediaItem
        val metadata = player.mediaMetadata
        val nowPlaying = item?.let {
            NowPlaying(
                mediaId = it.mediaId,
                songId = MediaItems.songId(it.mediaId),
                title = (metadata.title ?: it.mediaMetadata.title)?.toString() ?: "Unknown title",
                artist = (metadata.artist ?: it.mediaMetadata.artist)?.toString() ?: "",
                album = (metadata.albumTitle ?: it.mediaMetadata.albumTitle)?.toString() ?: "",
                contentUri = MediaItems.contentUri(it),
            )
        }
        val error = player.playerError
        _state.value = PlaybackUiState(
            isConnected = true,
            nowPlaying = nowPlaying,
            isPlaying = player.isPlaying,
            isBuffering = player.playbackState == Player.STATE_BUFFERING,
            durationMs = player.duration.takeIf { it != C.TIME_UNSET } ?: 0L,
            shuffleEnabled = player.shuffleModeEnabled,
            repeat = when (player.repeatMode) {
                Player.REPEAT_MODE_ONE -> RepeatSetting.One
                Player.REPEAT_MODE_ALL -> RepeatSetting.All
                else -> RepeatSetting.Off
            },
            speed = player.playbackParameters.speed,
            hasNext = player.hasNextMediaItem(),
            hasPrevious = player.hasPreviousMediaItem(),
            queue = cachedQueue,
            currentIndex = if (item == null) -1 else player.currentMediaItemIndex,
            error = error?.let { PlaybackErrorInfo(it.errorCode, it.errorCodeName, it.message) },
        )
    }

    private fun buildQueue(player: Player): List<QueueEntry> =
        List(player.mediaItemCount) { index ->
            val item = player.getMediaItemAt(index)
            QueueEntry(
                index = index,
                mediaId = item.mediaId,
                songId = MediaItems.songId(item.mediaId),
                title = item.mediaMetadata.title?.toString() ?: "Unknown title",
                artist = item.mediaMetadata.artist?.toString() ?: "",
                contentUri = MediaItems.contentUri(item),
            )
        }
}
