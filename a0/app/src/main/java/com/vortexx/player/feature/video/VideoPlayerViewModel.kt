package com.vortexx.player.feature.video

import android.content.Context
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Tracks
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.navigation.toRoute
import com.vortexx.player.data.repo.WatchHistoryRepository
import com.vortexx.player.data.settings.SettingsRepository
import com.vortexx.player.data.settings.VideoScaling
import com.vortexx.player.di.ApplicationScope
import com.vortexx.player.domain.playback.PlaybackErrorExplainer
import com.vortexx.player.domain.playback.ResumePolicy
import com.vortexx.player.playback.PlaybackConnection
import com.vortexx.player.ui.navigation.VideoPlayerRoute
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class OrientationLock(val label: String) {
    Auto("Auto"),
    Landscape("Landscape"),
    Portrait("Portrait"),
}

data class VideoError(
    val title: String,
    val detail: String,
    val codeName: String,
    val canRetry: Boolean,
)

data class VideoUiState(
    val title: String = "",
    val isPlaying: Boolean = false,
    val scaling: VideoScaling = VideoScaling.Fit,
    val keepScreenOn: Boolean = true,
    val orientation: OrientationLock = OrientationLock.Auto,
    val resumedFromMs: Long? = null,
    val error: VideoError? = null,
    val audioTrackCount: Int = 0,
    val subtitleTrackCount: Int = 0,
)

/**
 * Owns the video ExoPlayer. Living in a ViewModel keeps playback (and the decoder)
 * alive across rotation and other configuration changes.
 */
@HiltViewModel
class VideoPlayerViewModel @Inject constructor(
    @ApplicationContext context: Context,
    savedStateHandle: SavedStateHandle,
    private val watchHistory: WatchHistoryRepository,
    private val settingsRepository: SettingsRepository,
    playbackConnection: PlaybackConnection,
    @ApplicationScope private val appScope: CoroutineScope,
) : ViewModel() {

    private val route = savedStateHandle.toRoute<VideoPlayerRoute>()

    val player: ExoPlayer = buildPlayer(context)

    private val _ui = MutableStateFlow(VideoUiState(title = route.title))
    val ui: StateFlow<VideoUiState> = _ui.asStateFlow()

    private val listener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {
            _ui.update { it.copy(isPlaying = isPlaying) }
            if (!isPlaying) saveProgress()
        }

        override fun onPlaybackStateChanged(playbackState: Int) {
            when (playbackState) {
                Player.STATE_READY -> _ui.update { it.copy(error = null) }
                Player.STATE_ENDED -> saveProgress()
                else -> Unit
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            val explanation = PlaybackErrorExplainer.explain(error.errorCode)
            _ui.update {
                it.copy(
                    error = VideoError(explanation.title, explanation.detail, error.errorCodeName, explanation.canRetry),
                )
            }
        }

        override fun onTracksChanged(tracks: Tracks) {
            val audio = tracks.groups.count { it.type == C.TRACK_TYPE_AUDIO && it.isSupported }
            val text = tracks.groups.count { it.type == C.TRACK_TYPE_TEXT && it.isSupported }
            _ui.update { it.copy(audioTrackCount = audio, subtitleTrackCount = text) }
        }
    }

    init {
        // Video takes over audio output; the music session is paused rather than mixed.
        playbackConnection.pause()
        player.addListener(listener)
        player.setMediaItem(MediaItem.fromUri(route.uri))

        viewModelScope.launch {
            val settings = settingsRepository.settings.first()
            _ui.update { it.copy(scaling = settings.defaultVideoScaling, keepScreenOn = settings.keepScreenOnVideo) }
            if (settings.verbosePlaybackLogging) enableEventLogging()
            if (settings.resumeVideos && route.videoId >= 0) {
                val saved = watchHistory.get(route.videoId)
                if (saved != null && ResumePolicy.shouldResume(saved.positionMs, saved.durationMs)) {
                    player.seekTo(saved.positionMs)
                    _ui.update { it.copy(resumedFromMs = saved.positionMs) }
                }
            }
            player.prepare()
            player.play()
        }

        viewModelScope.launch {
            while (isActive) {
                delay(5_000L)
                if (player.isPlaying) saveProgress()
            }
        }
    }

    fun togglePlayPause() {
        if (player.isPlaying) player.pause() else player.play()
    }

    fun pause() = player.pause()

    fun cycleScaling() {
        _ui.update {
            val next = VideoScaling.entries[(it.scaling.ordinal + 1) % VideoScaling.entries.size]
            it.copy(scaling = next)
        }
    }

    fun cycleOrientation() {
        _ui.update {
            val next = OrientationLock.entries[(it.orientation.ordinal + 1) % OrientationLock.entries.size]
            it.copy(orientation = next)
        }
    }

    fun startOver() {
        player.seekTo(0L)
        player.play()
        _ui.update { it.copy(resumedFromMs = null) }
    }

    fun dismissResumeHint() {
        _ui.update { it.copy(resumedFromMs = null) }
    }

    fun retry() {
        _ui.update { it.copy(error = null) }
        player.prepare()
        player.play()
    }

    private fun saveProgress() {
        if (route.videoId < 0) return
        val duration = player.duration.takeIf { it != C.TIME_UNSET && it > 0 } ?: return
        val position = if (player.playbackState == Player.STATE_ENDED) duration else player.currentPosition
        appScope.launch { watchHistory.save(route.videoId, position, duration) }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun enableEventLogging() {
        player.addAnalyticsListener(EventLogger(LOG_TAG))
    }

    override fun onCleared() {
        saveProgress()
        player.removeListener(listener)
        player.release()
    }

    companion object {
        const val LOG_TAG = "VortexVideo"

        @androidx.annotation.OptIn(UnstableApi::class)
        private fun buildPlayer(context: Context): ExoPlayer {
            val renderers = DefaultRenderersFactory(context).setEnableDecoderFallback(true)
            val attributes = AudioAttributes.Builder()
                .setUsage(C.USAGE_MEDIA)
                .setContentType(C.AUDIO_CONTENT_TYPE_MOVIE)
                .build()
            return ExoPlayer.Builder(context, renderers)
                .setAudioAttributes(attributes, /* handleAudioFocus = */ true)
                .setHandleAudioBecomingNoisy(true)
                .build()
        }
    }
}
