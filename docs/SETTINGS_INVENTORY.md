# Settings inventory

Every visible setting must have a row here before its UI is wired. Timing: **now** = applies immediately; **next stream** = applies when Media3 next opens the output (usually the next song). All values persist in DataStore (`vortex_settings`) and are restored after process death; nothing is written from the audio thread.

## Implemented (A0 build)
| ID | Screen / section | Label | Type, range | Default | Key | Consumer | Timing | Prerequisite | Errors | Reset scope | Verification |
|---|---|---|---|---|---|---|---|---|---|---|---|
| settings.mode | Settings · Detail level | Detail level | enum Basic/Advanced/Pro/Developer | Basic | settings_mode | Settings UI (which rows show) | now | — | unknown value → Basic | kept by "Reset to defaults" | SettingsTest (JVM) |
| library.hideShort | Settings · Library | Hide short audio | bool | on | hide_short_audio | MediaLibraryRepository duration filter | now (library re-emits) | audio permission | — | Reset to defaults | phone: song count changes |
| library.shortSec | Settings · Library | Shorter than | int {10, 30, 60} s | 30 | short_audio_threshold_sec | same filter | now | hideShort on | out-of-range → 30 | Reset to defaults | phone |
| playback.pauseOnNoisy | Settings / Control Center | Pause when headphones disconnect | bool | on | pause_on_headphones_disconnect | PlaybackService → setHandleAudioBecomingNoisy | now | — | — | Reset to defaults | phone: unplug |
| playback.skipSilence | Settings / Control Center | Skip silence | bool | off | skip_silence | PlaybackService → setSkipSilenceEnabled | now | — | — | Reset to defaults | phone |
| video.resume | Settings · Video | Resume videos | bool | on | resume_videos | VideoPlayerViewModel | next video open | — | missing history → start | Reset to defaults | phone |
| video.keepScreenOn | Settings · Video | Keep screen on | bool | on | keep_screen_on_video | VideoPlayer screen flag | next video open | — | — | Reset to defaults | phone |
| video.scaling | Settings · Video | Default scaling | enum Fit/Stretch/Crop | Fit | default_video_scaling | VideoPlayer resize mode | next video open | — | unknown → Fit | Reset to defaults | phone |
| dev.verboseLog | Settings · Developer | Verbose playback logging | bool | off | verbose_playback_logging | Media3 EventLogger (music + video) | now / next video | Developer mode | — | Reset to defaults | logcat |
| lab.enabled | Audio Lab | DSP on / Bypass | bool | on | dsp_enabled | AudioEngine → DspChain (20 ms crossfade) | now | PCM output | — | Lab "Flat" does not change it | DspChainTest, phone |
| lab.preamp | Audio Lab | Preamp | float −12…+12 dB, 0.1 dB | 0 | dsp_preamp_db | DspChain (ramped) | now | PCM output | clamped | Lab Flat | DspChainTest |
| lab.bands | Audio Lab | 31 Hz … 16 kHz | 10 × float −12…+12 dB | 0 | dsp_eq10_db | DspChain (ramped); bands ≥ 0.45 Fs off | now | PCM output | bad string → flat | Lab Flat | DspChainTest, EqResponseTest |
| lab.outputMode | Audio Lab | DSP (PCM) / Battery offload | enum | DSP | audio_output_mode | AudioEngine (bypass now) + offload prefs | bypass now; offload next stream | offload needs device + format support | offload refused → PCM with DSP bypassed, shown in Lab | not reset by Flat | DspStatusAndParametersTest, phone |

## Planned (full rows are written when each is implemented)
| ID | Label | Type | Consumer | Milestone |
|---|---|---|---|---|
| playback.queuePersist | Remember queue and position | bool | Media3 session restore | M1 |
| playback.gapless | Gapless (shown with what the format allows) | info/bool | Media3 | M1 |
| playback.sleep.endOfTrack | Sleep at end of track | bool | SleepTimer | M1 |
| appearance.theme | AMOLED / Dark / Light | enum | Theme | M1 |
| appearance.accent | Accent | enum (red default) | Theme | M1 |
| appearance.textScale / contrast / reduceMotion | Readability | float / bool / bool | Theme, animations | M1 |
| lab.presets | Presets save/import/export | list | AudioEngine + JSON file | M2 |
| lab.ab | A/B compare | action | AudioEngine snapshot | M2 |
| lab.compressor.* / limiter.* / width / balance / reverb.* | Dynamics & space | floats/enums | new DSP stages | M2 |
| output.profile | Studio / Balanced / Battery | enum | PlaybackEngine | M2 |
| video.subtitle.* | Track, size, position, background, delay | various | Media3 text renderer | M2 |
| video.gestures.* | Seek / volume / brightness gestures, seek step | enum/int | VideoPlayer | M2 |
| video.pip / lock | Picture-in-picture, lock | bool / action | Activity PiP | M1 |
| library.folders | Include/exclude folders | list | MediaLibraryRepository | M2 |
| downloads.* | Wi-Fi only, folder, concurrency | bool / uri / int | Media3 DownloadManager | M3 |
| sources.* | Connect/disconnect, timeouts, metered rule | per source | source adapters | M3 |
| backup.* | Export/import settings, playlists, favourites | file | versioned JSON | M2 |
