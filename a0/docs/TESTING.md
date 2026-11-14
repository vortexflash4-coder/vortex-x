# Testing

## Automated

| Suite | Location | Runs where | Covers |
|---|---|---|---|
| `LibraryLogicTest` | `app/src/test` | JVM / CI | sort, search, album/artist/genre/folder grouping, duplicate detection, folder merge |
| `PoliciesAndFormatTest` | `app/src/test` | JVM / CI | duration/size/resolution formatting, resume + continue-watching windows, error explanations (unsupported codec handling) |
| `SettingsTest` | `app/src/test` | JVM / CI | DataStore mapping, invalid-value fallback, mode visibility, settings search |
| `VortexDatabaseTest` | `app/src/androidTest` | emulator or device | playlist order + cascade delete, duplicate names, favourites, watch history (playlist persistence) |
| `PlaybackEngineTest` | `app/src/androidTest` | emulator or device | generated WAV playback; MP3 / FLAC / MP4 / unsupported fixtures when present |
| `AppNavigationTest` | `app/src/androidTest` | phone-size emulator or device | first launch with permissions, bottom navigation, settings mode switching |

CI (`android-ci.yml`) runs unit tests and compiles the instrumentation tests on every push. `instrumented-tests.yml` runs the device tests on an API 34 emulator when started manually.

### Playback fixtures

Fixture tests are skipped (not failed) when files are missing. To run them on a device:

```bash
adb shell mkdir -p /sdcard/Android/data/com.vortexx.player.debug/files/fixtures
adb push sample.mp3 sample.flac sample.mp4 unsupported.bin /sdcard/Android/data/com.vortexx.player.debug/files/fixtures/
./gradlew connectedDebugAndroidTest
```

`unsupported.bin` can be any file that is not media (e.g. random bytes) to confirm the error path.

## Manual — requires a real phone

| Scenario | Steps | Expected |
|---|---|---|
| First launch & permissions | Fresh install, open app | Explainer screen; "Allow" shows system dialog; denying twice offers Android settings; "Continue without access" still lets Explorer open files |
| Android 14 partial access | Choose "Select photos and videos" | Videos screen shows the limited-access notice with "Change" |
| Playback with screen locked | Play music, lock phone | Audio continues; lock-screen controls work |
| Notification controls | Pull down shade | Play/pause/next/previous work; tapping opens Now Playing |
| Bluetooth disconnect / reconnect | Play over Bluetooth, turn headset off | Playback pauses (if "Pause when headphones disconnect" is on); reconnecting and pressing play resumes |
| Headset buttons | Single / double press on wired or BT headset | Play-pause / next |
| Audio focus | Start a call or another media app | VORTEX X pauses; resumes or stays paused per Android focus rules |
| Sleep timer | Set 15 min, lock phone | Playback pauses at the deadline |
| FLAC playback | Play a FLAC file | Plays on Android 8.1+ (device decoder); Diagnostics shows FLAC decoder |
| Subtitle selection | Play MKV/MP4 with embedded subtitles, open ⚙ in player | Subtitle track menu lists tracks; selection applies |
| Multiple audio tracks | Video with 2+ audio tracks | Overlay shows count; ⚙ menu switches track |
| Unsupported codec | Play e.g. an AV1/HEVC file on a device without that decoder | Error card names the problem and code; no crash |
| Rotation | Rotate during video and on Now Playing | Video continues without re-buffering; Now Playing switches to side-by-side layout |
| Process recreation | Developer options → "Don't keep activities", background and return | App restores; music keeps playing from the service |
| Large library | 5 000+ songs | Smooth scrolling; search responsive |
| Tablet / foldable | Width ≥ 600 dp | Navigation rail with workstation tools |
| Font scaling | System font size largest | Text wraps; controls remain usable |

Record results per device (model, Android version) in the release checklist.
