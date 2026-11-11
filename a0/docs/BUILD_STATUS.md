# Build status — VORTEX X

Last updated: 2026-11-11 (Stage A0 review)

## Evidence levels

| Level | Meaning |
|---|---|
| **Written** | The code exists in this repository. |
| **Compiled** | `assembleDebug` passed in the explicitly named environment; hosted CI is reported separately. |
| **Unit-tested** | JVM tests passed in the explicitly named environment (`testDebugUnitTest`); no skipped allocation gates. |
| **Device-tested** | The owner confirmed it on a real phone. |

## Stage A0 review (0.2.1-a0-review) — phone gate pending

Full evidence, limitations and exact changes: [`A0_REVIEW_REPORT.md`](A0_REVIEW_REPORT.md).
Real-device checklist and signing requirements: [`STAGE_A0.md`](STAGE_A0.md).

- Local Android-project JVM suite: **61 passed, 0 failed, 0 skipped**.
- Core allocation: **0 bytes** / 5,000 buffers; PCM16 adapter **256 bytes**;
  float adapter **240 bytes**; direct-buffer growth **0**. Both gates pass the
  documented <1,024-byte JVM threshold. These are not device measurements.
- Local `assembleDebug`: **PASS**, exit 0.
- Local `assembleDebugAndroidTest`: **PASS**, exit 0; compilation/packaging only.
- GitHub Actions: **NOT RUN**. Account connected, but repository access is absent.
- Phase 1 update compatibility: **FAIL** with the bundled A0 debug key. The
  original Phase 1 debug key is required; do not uninstall or clear app data.
- Instrumentation execution and real-phone verification: **NOT RUN**.
- A1: **BLOCKED** pending phone verification.

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
