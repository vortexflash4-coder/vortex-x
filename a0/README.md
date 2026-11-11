# VORTEX X — Phase 1 foundation + Stage A0 (DSP proof)

Native Android music and video player. Kotlin, Jetpack Compose, Material 3, AndroidX Media3 (ExoPlayer + MediaSession), Hilt, Room, DataStore, Coroutines/StateFlow.

> **A0 review status:** see [review results](docs/A0_REVIEW_REPORT.md) for actual build/test evidence. Phone signing compatibility and device checks are required; A1 remains blocked. Do not uninstall Phase 1 to install a mismatching APK.

## What Phase 1 contains

| Area | Status |
|---|---|
| Design system | Colour/type/shape/spacing tokens, reusable components, multi-device preview annotation (`@VortexPreviews`) |
| Navigation | 12 destinations; bottom bar on phones, navigation rail ≥ 600 dp; adaptive Now Playing layout |
| Permissions | First-launch explainer, per-library permission states, permanent-denial → Android settings, Android 14 partial video access notice |
| Music library | MediaStore index with live refresh, search, sort (name/date/duration/size), songs/albums/artists/genres/folders, album art cache |
| Music playback | Media3 `MediaSessionService`: background playback, notification + lock-screen controls, Bluetooth/headset buttons, audio focus, pause on headphone unplug, gapless (format-dependent), shuffle/repeat, speed, sleep timer |
| Queue | View, jump, reorder, remove, clear, save as playlist |
| Playlists & favourites | Room-backed create/rename/delete, add from any song menu, favourites |
| Video | Thumbnail grid with resolution badges and watched progress, full-screen PlayerView with speed / audio track / subtitle menus, Fit/Stretch/Crop, rotation lock, resume with "Start over", continue watching, explained errors |
| Media Explorer | Folder browser (audio + video), open any file through the system picker, rescan |
| Audio Lab (Stage A0) | VORTEX X's own DSP in Media3's audio pipeline: preamp, 10-band EQ, stereo peak/RMS meters, click-free bypass, clip detection, live signal-path report (decoder, formats, output mode, buffer, route, underruns). See docs/STAGE_A0.md |
| Diagnostics | Decoders (HW/SW, max resolution), immersive formats (AC-3, E-AC-3, E-AC-3 JOC, AC-4…), output encodings, Spatializer, HDR display types, copyable report |
| Settings | Basic / Advanced / Pro / Developer levels, search, dependency-aware controls, reset — every visible control is wired to real behaviour |

## Getting the APK (phone-only workflow)

**Route A — upload the ZIP (browser only, no git):**

1. Create an empty GitHub repository.
2. **Actions → set up a workflow yourself**, paste the whole of `docs/phone-build/build-from-zip.yml`, **Commit**.
3. **Code → Add file → Upload files**, pick the project ZIP, **Commit**. The **Build APK from uploaded zip** run starts by itself.

**Route B — push the project folder** (let Claude push it once GitHub is connected, or Termux: `pkg install git`, unzip, `git init`, `git add .`, `git commit`, `git push`). The **Android CI** workflow runs on every push (or tap **Run workflow**).

Either way:

- Open the run. Its **Summary** page says **APK built** (with size) or **APK was not built**, shows unit-test counts, and has an **Errors** box with `file:line` for every compiler error.
- Green run: download the **vortex-x-debug-apk** artifact (a zip containing `app-debug.apk`) and install it (allow installs from your browser/file manager when asked).
- Full Gradle logs and test reports are in the **vortex-x-logs-and-reports** artifact.

## Building on a computer

Requirements: JDK 17, Android SDK with platform 36.

```bash
./gradlew assembleDebug            # APK at app/build/outputs/apk/debug/
./gradlew testDebugUnitTest        # JVM unit tests
./gradlew connectedDebugAndroidTest  # instrumentation tests (device/emulator)
```

## Project layout

```
app/src/main/java/com/vortexx/player/
  core/          permissions, capability probing, formatting
  data/          MediaStore library, artwork cache, Room DB, DataStore settings, repositories
  domain/        pure models and logic (sorting, grouping, duplicates, resume/error policies)
  playback/      PlaybackService (MediaSessionService), PlaybackConnection (MediaController)
  ui/            design system, app chrome, navigation, shared song actions
  feature/       home, music, nowplaying, queue, controlcenter, video, playlists,
                 explorer, studio, diagnostics, settings
```

More detail: [docs/BUILD_STATUS.md](docs/BUILD_STATUS.md) · [docs/CHANGELOG.md](docs/CHANGELOG.md) · [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) · [docs/TESTING.md](docs/TESTING.md) · [docs/KNOWN_LIMITATIONS.md](docs/KNOWN_LIMITATIONS.md) · [docs/RELEASE_CHECKLIST.md](docs/RELEASE_CHECKLIST.md)

## Dependency versions

Pinned in `gradle/libs.versions.toml` to stable, mutually compatible releases: AGP 8.11.1, Gradle 8.14.3, Kotlin 2.1.20, KSP 2.1.20-2.0.1, Compose BOM 2025.06.00, Media3 1.11.1, Hilt 2.56.2, Room 2.7.1, Navigation 2.9.0. `compileSdk`/`targetSdk` 36, `minSdk` 26.
