package com.vortexx.player.data.settings

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/** How much of the settings tree is visible. Higher modes include lower ones. */
enum class SettingsMode(val label: String, val description: String) {
    Basic("Basic", "Simple controls for everyday listening and watching"),
    Advanced("Advanced", "Detailed playback, audio, video and library controls"),
    Pro("Pro", "Output, rendering and performance controls"),
    Developer("Developer", "Read-only codec reports, logging and diagnostics"),
}

/** Video scaling. Fit = letterbox, Stretch = fill ignoring aspect, Crop = zoom to fill. */
enum class VideoScaling(val label: String) {
    Fit("Fit"),
    Stretch("Stretch"),
    Crop("Crop"),
}

data class AppSettings(
    val mode: SettingsMode = SettingsMode.Basic,
    val hideShortAudio: Boolean = true,
    val shortAudioThresholdSec: Int = DEFAULT_SHORT_AUDIO_SEC,
    val pauseOnHeadphonesDisconnect: Boolean = true,
    val skipSilence: Boolean = false,
    val resumeVideos: Boolean = true,
    val keepScreenOnVideo: Boolean = true,
    val defaultVideoScaling: VideoScaling = VideoScaling.Fit,
    val verbosePlaybackLogging: Boolean = false,
) {
    /** Minimum duration for audio to appear in the library. */
    val minAudioDurationMs: Long get() = if (hideShortAudio) shortAudioThresholdSec * 1000L else 0L

    companion object {
        const val DEFAULT_SHORT_AUDIO_SEC = 30
        val SHORT_AUDIO_CHOICES = listOf(10, 30, 60)
    }
}

internal object SettingsKeys {
    val MODE = stringPreferencesKey("settings_mode")
    val HIDE_SHORT_AUDIO = booleanPreferencesKey("hide_short_audio")
    val SHORT_AUDIO_SEC = intPreferencesKey("short_audio_threshold_sec")
    val PAUSE_ON_NOISY = booleanPreferencesKey("pause_on_headphones_disconnect")
    val SKIP_SILENCE = booleanPreferencesKey("skip_silence")
    val RESUME_VIDEOS = booleanPreferencesKey("resume_videos")
    val KEEP_SCREEN_ON = booleanPreferencesKey("keep_screen_on_video")
    val VIDEO_SCALING = stringPreferencesKey("default_video_scaling")
    val VERBOSE_LOGGING = booleanPreferencesKey("verbose_playback_logging")
}

/**
 * Maps stored preferences to [AppSettings], validating every value.
 * Unknown enum names and out-of-range numbers fall back to defaults.
 */
fun Preferences.toAppSettings(): AppSettings {
    val defaults = AppSettings()
    val threshold = this[SettingsKeys.SHORT_AUDIO_SEC]
        ?.takeIf { it in AppSettings.SHORT_AUDIO_CHOICES }
        ?: defaults.shortAudioThresholdSec
    return AppSettings(
        mode = enumOrDefault(this[SettingsKeys.MODE], defaults.mode),
        hideShortAudio = this[SettingsKeys.HIDE_SHORT_AUDIO] ?: defaults.hideShortAudio,
        shortAudioThresholdSec = threshold,
        pauseOnHeadphonesDisconnect = this[SettingsKeys.PAUSE_ON_NOISY] ?: defaults.pauseOnHeadphonesDisconnect,
        skipSilence = this[SettingsKeys.SKIP_SILENCE] ?: defaults.skipSilence,
        resumeVideos = this[SettingsKeys.RESUME_VIDEOS] ?: defaults.resumeVideos,
        keepScreenOnVideo = this[SettingsKeys.KEEP_SCREEN_ON] ?: defaults.keepScreenOnVideo,
        defaultVideoScaling = enumOrDefault(this[SettingsKeys.VIDEO_SCALING], defaults.defaultVideoScaling),
        verbosePlaybackLogging = this[SettingsKeys.VERBOSE_LOGGING] ?: defaults.verbosePlaybackLogging,
    )
}

internal inline fun <reified E : Enum<E>> enumOrDefault(name: String?, default: E): E =
    if (name == null) default else enumValues<E>().firstOrNull { it.name == name } ?: default
