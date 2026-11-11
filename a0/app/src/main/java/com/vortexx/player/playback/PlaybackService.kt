package com.vortexx.player.playback

import android.app.PendingIntent
import android.content.Intent
import android.os.SystemClock
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.util.EventLogger
import androidx.media3.session.DefaultMediaNotificationProvider
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.vortexx.player.MainActivity
import com.vortexx.player.R
import com.vortexx.player.audio.engine.AudioDiagnostics
import com.vortexx.player.audio.engine.AudioEngine
import com.vortexx.player.audio.engine.OutputModePreference
import com.vortexx.player.audio.media3.VortexAudioProcessor
import com.vortexx.player.audio.media3.VortexRenderersFactory
import com.vortexx.player.data.settings.SettingsRepository
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Hosts the music ExoPlayer inside a MediaSession. Media3 provides the media
 * notification, lock-screen controls, Bluetooth/headset media buttons and
 * foreground-service handling from this session.
 */
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    @Inject lateinit var sleepTimer: SleepTimer
    @Inject lateinit var settingsRepository: SettingsRepository
    @Inject lateinit var audioEngine: AudioEngine
    @Inject lateinit var audioDiagnostics: AudioDiagnostics

    private var mediaSession: MediaSession? = null
    private var eventLogger: EventLogger? = null
    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @androidx.annotation.OptIn(UnstableApi::class)
    override fun onCreate() {
        super.onCreate()
        val player = buildPlayer()
        mediaSession = MediaSession.Builder(this, player)
            .setSessionActivity(sessionActivity())
            .setCallback(SessionCallback())
            .build()
        setMediaNotificationProvider(
            DefaultMediaNotificationProvider.Builder(this).build().apply {
                setSmallIcon(R.drawable.ic_stat_vortex)
            },
        )
        player.addAnalyticsListener(audioDiagnostics.analyticsListener())
        audioDiagnostics.startRouteTracking()
        observeSettings(player)
        observeSleepTimer(player)
        observeOutputMode(player)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    /** Keep playing after the task is swiped away only if something is actually playing. */
    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0 ||
            player.playbackState == Player.STATE_ENDED
        ) {
            stopSelf()
        }
    }

    override fun onDestroy() {
        serviceScope.cancel()
        audioDiagnostics.stopRouteTracking()
        mediaSession?.run {
            player.release()
            release()
        }
        mediaSession = null
        eventLogger = null
        super.onDestroy()
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun buildPlayer(): ExoPlayer {
        // Media3's renderers with the VORTEX X processor in the PCM chain. Decoder fallback lets
        // ExoPlayer try a secondary decoder if the primary one fails to initialise.
        val renderersFactory = VortexRenderersFactory(this, VortexAudioProcessor(audioEngine))
            .setEnableDecoderFallback(true)
        val audioAttributes = AudioAttributes.Builder()
            .setUsage(C.USAGE_MEDIA)
            .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
            .build()
        return ExoPlayer.Builder(this, renderersFactory)
            .setAudioAttributes(audioAttributes, /* handleAudioFocus = */ true)
            .setHandleAudioBecomingNoisy(true)
            .setWakeMode(C.WAKE_MODE_LOCAL)
            .build()
    }

    private fun sessionActivity(): PendingIntent {
        val intent = Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
            .putExtra(MainActivity.EXTRA_OPEN_NOW_PLAYING, true)
        return PendingIntent.getActivity(
            this,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun observeSettings(player: ExoPlayer) {
        serviceScope.launch {
            settingsRepository.settings.collect { settings ->
                player.setSkipSilenceEnabled(settings.skipSilence)
                player.setHandleAudioBecomingNoisy(settings.pauseOnHeadphonesDisconnect)
                updateEventLogging(player, settings.verbosePlaybackLogging)
            }
        }
    }

    @androidx.annotation.OptIn(UnstableApi::class)
    private fun updateEventLogging(player: ExoPlayer, enabled: Boolean) {
        val current = eventLogger
        if (enabled && current == null) {
            val logger = EventLogger(LOG_TAG)
            player.addAnalyticsListener(logger)
            eventLogger = logger
        } else if (!enabled && current != null) {
            player.removeAnalyticsListener(current)
            eventLogger = null
        }
    }

    /**
     * DSP mode keeps offload disabled so decoded PCM reaches the processor.
     * Battery mode allows hardware offload where the phone supports it; the
     * processor then declines the stream and the Audio Lab reports the mode Media3 actually opened.
     */
    private fun observeOutputMode(player: ExoPlayer) {
        serviceScope.launch {
            audioEngine.outputMode.collect { mode ->
                val offload = TrackSelectionParameters.AudioOffloadPreferences.Builder()
                    .setAudioOffloadMode(
                        if (mode == OutputModePreference.Offload) {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_ENABLED
                        } else {
                            TrackSelectionParameters.AudioOffloadPreferences.AUDIO_OFFLOAD_MODE_DISABLED
                        },
                    )
                    .setIsGaplessSupportRequired(true)
                    .build()
                player.trackSelectionParameters = player.trackSelectionParameters
                    .buildUpon()
                    .setAudioOffloadPreferences(offload)
                    .build()
            }
        }
    }

    private fun observeSleepTimer(player: Player) {
        serviceScope.launch {
            sleepTimer.deadlineElapsedMs.collectLatest { deadline ->
                if (deadline == null) return@collectLatest
                val remaining = deadline - SystemClock.elapsedRealtime()
                if (remaining > 0) delay(remaining)
                player.pause()
                sleepTimer.cancel()
            }
        }
    }

    private class SessionCallback : MediaSession.Callback {
        /** Restores the playable URI that controllers send in RequestMetadata. */
        override fun onAddMediaItems(
            mediaSession: MediaSession,
            controller: MediaSession.ControllerInfo,
            mediaItems: MutableList<MediaItem>,
        ): ListenableFuture<MutableList<MediaItem>> {
            val resolved = mediaItems.map { item ->
                val uri = item.requestMetadata.mediaUri ?: item.localConfiguration?.uri
                if (uri != null) item.buildUpon().setUri(uri).build() else item
            }.toMutableList()
            return Futures.immediateFuture(resolved)
        }
    }

    companion object {
        const val LOG_TAG = "VortexAudio"
    }
}
