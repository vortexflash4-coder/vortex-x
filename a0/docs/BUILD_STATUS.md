# VORTEX X build status — 11 November 2026

## Current A0 fresh-install result — CI SUCCESS

The owner uninstalled Phase 1. The active target is a new A0 installation with
its pinned stable debug key. Old Phase 1 app-data restoration is not claimed.

Run: https://github.com/vortexflash4-coder/vortex-x/actions/runs/43014067553
Tested commit: `78c22823b519adffef09ad9a1f82d2196c6c3caf`.

- Full CI: **SUCCESS** for `fresh-install`.
- JVM: **61 passed, 0 failed, 0 skipped**; both allocation gates ran.
- Signing-target fixtures: **8 passed** (separate simulated-output gate tests).
- `assembleDebug` and `assembleDebugAndroidTest`: **PASS**; instrumentation compiled only.
- APK signature/package: **PASS**, pinned A0 key, `com.vortexx.player.debug`, version code 3.
- Lint: **0 errors, 37 warnings**.
- Heap per measured 5,000 inputs after representative warm-up: core **0**, PCM16 **256**, float **0** bytes; direct growth **0**.
- The earlier 1,144-byte allocation failure remains in evidence; threshold unchanged.
- Phone verification: **PENDING**. **A1 NOT STARTED**.

The CLI default and original certificate file still enforce `phase1-upgrade`
compatibility when that mode is selected. Earlier mismatch failures are history,
not a fresh-install failure. Release signing remains separate and unconfigured.

Current details: `A0_REVIEW_REPORT.md`.
Current owner checklist: `FRESH_INSTALL_PHONE_CHECKLIST.md`.

## Historical Phase 1 record retained from the supplied source

## Phase 1 — first CI run

**First CI run green — 2026-11-11.** Repository `vortexflash4-coder/vortex-x` (private), workflow `build-from-zip.yml`, commit `949e9e2`, built from `VortexX-phase1-reviewed.zip`.

| Check | Result |
|---|---|
| `assembleDebug` | success — `app-debug.apk` 22.4 MB (artifact zip 21.5 MB) |
| `testDebugUnitTest` | success — 28 tests, 28 passed, 0 failed, 0 skipped |
| `assembleDebugAndroidTest` | success (compiled only; instrumented tests not run) |
| Lint | not run in the zip workflow |
| Errors | none |

Run annotations (warnings, not failures): Node.js 20 deprecation for the actions used, `setup-java@v4` deprecation, and `ubuntu-latest` moving to Ubuntu 26 from 2026-10-19. Action versions to be updated after checking the current releases.

| Feature | Written | Compiled | Unit-tested | Device-tested |
|---|---|---|---|---|
| Design system (tokens, components, previews) | Yes | Yes | — | No |
| Navigation (bottom bar / rail, 12 destinations) | Yes | Yes | — | Partial: bottom bar + Settings seen (2026-11-11) |
| Media permissions + first-launch screen | Yes | Yes | — | Partial: access granted, library readable (2026-11-11) |
| Music library (MediaStore, search, sort, grouping) | Yes | Yes | Yes (`LibraryLogicTest`) | Partial: a local song played from the library (2026-11-11) |
| Background playback (MediaSessionService, notification, focus, noisy) | Yes | Yes | — | Partial: plays in app with mini player; lock screen / notification / headset not yet checked |
| Queue (jump, reorder, remove, clear, save) | Yes | Yes | — | No |
| Playlists and favourites (Room) | Yes | Yes | Instrumented tests compiled, not run (`VortexDatabaseTest`) | No |
| Video player (PlayerView, scaling, rotation lock, resume) | Yes | Yes | Resume rules: Yes (`PoliciesAndFormatTest`) | No |
| Media Explorer (folders, open file via picker) | Yes | Yes | Folder merge: Yes | No |
| Audio Studio (capability report only; replaced by Audio Lab in A0) | Yes | Yes | — | No |
| Diagnostics (decoders, outputs, spatializer, HDR) | Yes | Yes | — | No |
| Settings (4 levels, search, reset) | Yes | Yes | Yes (`SettingsTest`) | Partial: Pro level and its toggles shown (2026-11-11) |
| Playback error explanations | Yes | Yes | Yes | No |
| CI job summary (APK built/not built, test counts, Errors box) | Yes | Yes | Worked in the real run | — |

## Next

- APK installed on the owner's phone on 2026-11-11 and runs. Finish "Device-tested" from `docs/TESTING.md` (manual table).
- Instrumented tests (`VortexDatabaseTest`, `PlaybackEngineTest`, `AppNavigationTest`) are compiled but have not run on a device or emulator.

