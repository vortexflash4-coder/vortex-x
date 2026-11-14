package com.vortexx.player.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/** Single source of truth for persisted user settings (DataStore Preferences). */
@Singleton
class SettingsRepository @Inject constructor(
    private val dataStore: DataStore<Preferences>,
) {
    val settings: Flow<AppSettings> = dataStore.data
        .catch { error -> if (error is IOException) emit(emptyPreferences()) else throw error }
        .map { it.toAppSettings() }
        .distinctUntilChanged()

    suspend fun setMode(mode: SettingsMode) {
        dataStore.edit { it[SettingsKeys.MODE] = mode.name }
    }

    suspend fun setHideShortAudio(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.HIDE_SHORT_AUDIO] = enabled }
    }

    suspend fun setShortAudioThreshold(seconds: Int) {
        require(seconds in AppSettings.SHORT_AUDIO_CHOICES) { "Unsupported threshold: $seconds" }
        dataStore.edit { it[SettingsKeys.SHORT_AUDIO_SEC] = seconds }
    }

    suspend fun setPauseOnHeadphonesDisconnect(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.PAUSE_ON_NOISY] = enabled }
    }

    suspend fun setSkipSilence(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.SKIP_SILENCE] = enabled }
    }

    suspend fun setResumeVideos(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.RESUME_VIDEOS] = enabled }
    }

    suspend fun setKeepScreenOnVideo(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.KEEP_SCREEN_ON] = enabled }
    }

    suspend fun setDefaultVideoScaling(scaling: VideoScaling) {
        dataStore.edit { it[SettingsKeys.VIDEO_SCALING] = scaling.name }
    }

    suspend fun setVerbosePlaybackLogging(enabled: Boolean) {
        dataStore.edit { it[SettingsKeys.VERBOSE_LOGGING] = enabled }
    }

    /**
     * Restores every Settings-screen value to its default, keeping the chosen
     * detail level. Audio Lab values live in the same store and are kept;
     * the Lab has its own reset.
     */
    suspend fun resetToDefaults() {
        dataStore.edit { prefs ->
            prefs.remove(SettingsKeys.HIDE_SHORT_AUDIO)
            prefs.remove(SettingsKeys.SHORT_AUDIO_SEC)
            prefs.remove(SettingsKeys.PAUSE_ON_NOISY)
            prefs.remove(SettingsKeys.SKIP_SILENCE)
            prefs.remove(SettingsKeys.RESUME_VIDEOS)
            prefs.remove(SettingsKeys.KEEP_SCREEN_ON)
            prefs.remove(SettingsKeys.VIDEO_SCALING)
            prefs.remove(SettingsKeys.VERBOSE_LOGGING)
        }
    }
}
