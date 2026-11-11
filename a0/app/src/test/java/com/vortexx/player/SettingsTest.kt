package com.vortexx.player

import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.preferencesOf
import androidx.datastore.preferences.core.stringPreferencesKey
import com.vortexx.player.data.settings.AppSettings
import com.vortexx.player.data.settings.SettingId
import com.vortexx.player.data.settings.SettingsCatalog
import com.vortexx.player.data.settings.SettingsMode
import com.vortexx.player.data.settings.VideoScaling
import com.vortexx.player.data.settings.toAppSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SettingsTest {

    @Test
    fun emptyPreferencesGiveDefaults() {
        assertEquals(AppSettings(), emptyPreferences().toAppSettings())
    }

    @Test
    fun storedValuesAreRead() {
        val prefs = preferencesOf(
            stringPreferencesKey("settings_mode") to "Pro",
            booleanPreferencesKey("skip_silence") to true,
            stringPreferencesKey("default_video_scaling") to "Crop",
            intPreferencesKey("short_audio_threshold_sec") to 60,
        )
        val settings = prefs.toAppSettings()
        assertEquals(SettingsMode.Pro, settings.mode)
        assertTrue(settings.skipSilence)
        assertEquals(VideoScaling.Crop, settings.defaultVideoScaling)
        assertEquals(60_000L, settings.minAudioDurationMs)
    }

    @Test
    fun invalidValuesFallBackToDefaults() {
        val prefs = preferencesOf(
            stringPreferencesKey("settings_mode") to "Wizard",
            stringPreferencesKey("default_video_scaling") to "Melt",
            intPreferencesKey("short_audio_threshold_sec") to 7,
        )
        val settings = prefs.toAppSettings()
        assertEquals(SettingsMode.Basic, settings.mode)
        assertEquals(VideoScaling.Fit, settings.defaultVideoScaling)
        assertEquals(AppSettings.DEFAULT_SHORT_AUDIO_SEC, settings.shortAudioThresholdSec)
    }

    @Test
    fun disablingShortAudioFilterShowsEverything() {
        assertEquals(0L, AppSettings(hideShortAudio = false).minAudioDurationMs)
    }

    @Test
    fun basicModeHidesAdvancedAndDeveloperSettings() {
        val ids = SettingsCatalog.visible(SettingsMode.Basic, "").map { it.id }
        assertTrue(SettingId.HideShortAudio in ids)
        assertFalse(SettingId.SkipSilence in ids)
        assertFalse(SettingId.VerboseLogging in ids)
    }

    @Test
    fun developerModeShowsEverything() {
        assertEquals(SettingsCatalog.all.size, SettingsCatalog.visible(SettingsMode.Developer, "").size)
    }

    @Test
    fun searchMatchesKeywords() {
        val ids = SettingsCatalog.visible(SettingsMode.Developer, "bluetooth").map { it.id }
        assertEquals(listOf(SettingId.PauseOnHeadphonesDisconnect), ids)
    }

    @Test
    fun everyCatalogIdIsUnique() {
        val ids = SettingsCatalog.all.map { it.id }
        assertEquals(ids.size, ids.toSet().size)
        assertEquals(SettingId.entries.size, ids.size)
    }
}
