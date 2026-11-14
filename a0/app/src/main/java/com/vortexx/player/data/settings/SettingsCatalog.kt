package com.vortexx.player.data.settings

/**
 * Declarative catalog of every visible setting. The UI renders controls from
 * these ids; each id is wired to real behaviour (see SettingsScreen).
 * Only settings that work today are listed: no placeholder switches.
 */
enum class SettingsCategory(val title: String) {
    General("General and interface"),
    Music("Music playback"),
    Video("Video playback"),
    Library("Library and storage"),
    Developer("Developer diagnostics"),
}

enum class SettingId {
    DetailLevel,
    HideShortAudio,
    ShortAudioThreshold,
    RefreshLibrary,
    PauseOnHeadphonesDisconnect,
    SkipSilence,
    ResumeVideos,
    KeepScreenOn,
    DefaultVideoScaling,
    ClearWatchHistory,
    OpenDiagnostics,
    VerboseLogging,
}

data class SettingDefinition(
    val id: SettingId,
    val category: SettingsCategory,
    val title: String,
    val summary: String,
    val minMode: SettingsMode,
    val keywords: List<String> = emptyList(),
)

object SettingsCatalog {

    val all: List<SettingDefinition> = listOf(
        SettingDefinition(
            SettingId.DetailLevel, SettingsCategory.General,
            "Settings detail level", "Choose how many controls are shown",
            SettingsMode.Basic, listOf("mode", "basic", "advanced", "pro", "developer"),
        ),
        SettingDefinition(
            SettingId.HideShortAudio, SettingsCategory.Library,
            "Hide short audio clips", "Keeps ringtones and voice notes out of the music library",
            SettingsMode.Basic, listOf("ringtone", "notification", "clip", "filter"),
        ),
        SettingDefinition(
            SettingId.ShortAudioThreshold, SettingsCategory.Library,
            "Short clip threshold", "Audio shorter than this is hidden",
            SettingsMode.Advanced, listOf("duration", "seconds", "minimum"),
        ),
        SettingDefinition(
            SettingId.RefreshLibrary, SettingsCategory.Library,
            "Refresh library now", "Re-reads music and videos from device storage",
            SettingsMode.Basic, listOf("scan", "rescan", "reload", "mediastore"),
        ),
        SettingDefinition(
            SettingId.PauseOnHeadphonesDisconnect, SettingsCategory.Music,
            "Pause when headphones disconnect", "Stops audio leaking to the speaker when a wired or Bluetooth headset drops",
            SettingsMode.Basic, listOf("bluetooth", "headset", "noisy", "unplug"),
        ),
        SettingDefinition(
            SettingId.SkipSilence, SettingsCategory.Music,
            "Skip silence", "Shortens silent passages during music playback",
            SettingsMode.Advanced, listOf("silence", "podcast", "gap"),
        ),
        SettingDefinition(
            SettingId.ResumeVideos, SettingsCategory.Video,
            "Resume where you left off", "Videos continue from the last watched position",
            SettingsMode.Basic, listOf("continue", "position", "history"),
        ),
        SettingDefinition(
            SettingId.KeepScreenOn, SettingsCategory.Video,
            "Keep screen on while playing", "Prevents the display from sleeping during video",
            SettingsMode.Basic, listOf("display", "sleep", "wake"),
        ),
        SettingDefinition(
            SettingId.DefaultVideoScaling, SettingsCategory.Video,
            "Default video scaling", "Fit, stretch or crop when a video opens",
            SettingsMode.Advanced, listOf("aspect", "ratio", "zoom", "fill", "crop", "stretch"),
        ),
        SettingDefinition(
            SettingId.ClearWatchHistory, SettingsCategory.Video,
            "Clear watch history", "Forgets resume positions and Continue watching",
            SettingsMode.Advanced, listOf("history", "privacy", "reset"),
        ),
        SettingDefinition(
            SettingId.OpenDiagnostics, SettingsCategory.Developer,
            "Audio and video diagnostics", "Decoder, HDR, spatial audio and output reports",
            SettingsMode.Pro, listOf("codec", "decoder", "hdr", "dolby", "spatial", "report"),
        ),
        SettingDefinition(
            SettingId.VerboseLogging, SettingsCategory.Developer,
            "Verbose playback logging", "Writes detailed player events to Logcat (tags VortexAudio, VortexVideo)",
            SettingsMode.Developer, listOf("log", "logcat", "debug", "eventlogger"),
        ),
    )

    /** Settings visible for [mode], optionally filtered by a search [query]. */
    fun visible(mode: SettingsMode, query: String): List<SettingDefinition> {
        val q = query.trim()
        return all.filter { it.minMode.ordinal <= mode.ordinal }
            .filter { def ->
                q.isEmpty() ||
                    def.title.contains(q, ignoreCase = true) ||
                    def.summary.contains(q, ignoreCase = true) ||
                    def.category.title.contains(q, ignoreCase = true) ||
                    def.keywords.any { it.contains(q, ignoreCase = true) }
            }
    }
}
