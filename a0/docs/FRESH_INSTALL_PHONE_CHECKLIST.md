# Stage A0 — fresh-install phone checklist

Current owner state: Phase 1 was uninstalled. Fresh install uses the fixed A0
debug key. Old app-data restoration is not claimed. Record phone model, Android
version, installed app version and APK SHA-256 before marking results.

| Check | Expected / record |
|---|---|
| Install | APK installs and opens; stop and record any retained-data/package conflict |
| Media/library | Permissions work; existing shared media is listed and a local track plays |
| Audio Lab | Active PCM path, moving meters, decoder/format/route information |
| EQ/preamp | Audible +6 dB comparison at comfortable volume; Flat resets settings |
| User bypass | Ten on/off comparisons without obvious clicks; allow output-buffer delay |
| Battery fallback | EQ/preamp stop affecting unsupported-offload PCM without restart; DSP resumes |
| Supported offload | If supported, status follows the actual opened output; record N/A otherwise |
| Compact controls | Swipe to all ten bands; separate targets, drag/reset and TalkBack adjustment |
| Transitions | Pause/resume, seek, track/sample-rate changes and mode toggles do not crash or stick |
| Background/controls | Lock-screen and notification controls, audio focus and disconnect behavior |
| Persistence | Newly created settings, favourites and playlists survive closing/reopening |
| Existing video | Open/play/resume/rotation/scaling behave correctly; video DSP is outside A0 |
| Routes | Speaker, wired/USB/Bluetooth available routes; record route updates/errors |
| Clipping/headroom | Multiple boosts and Auto headroom work; note pre-ceiling clips after settling |
| Stability | 30-minute playback; record underrun delta, DSP average/worst load and battery/temperature |

Mark actual pass/fail or unavailable hardware with a reason. Do not mark tests
passed from compilation alone. A1 stays blocked until A0 phone verification.
