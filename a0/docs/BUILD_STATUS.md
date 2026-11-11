# Build status — VORTEX X

Last updated: 2026-11-11 (Stage A0 review)

## Evidence levels

| Level | Meaning |
|---|---|
| **Written** | The code exists in this repository. |
| **Compiled** | `assembleDebug` passed in the explicitly named environment; hosted CI is reported separately. |
| **Unit-tested** | JVM tests passed in the explicitly named environment (`testDebugUnitTest`); no skipped allocation gates. |
| **Device-tested** | The owner confirmed it on a real phone. |

## Active install target

The owner has uninstalled Phase 1. The review workflow now selects **fresh-install**,
checks the pinned A0 debug key, and retains the separate **phase1-upgrade** mode.
Earlier Phase 1 mismatch results below remain historical facts. A fresh-install
CI run is in progress; phone verification and A1 remain pending. No recovery of
the uninstalled app's settings, favourites or playlists is claimed.

## Stage A0 review — hosted build/tests pass, phone gate blocked

- GitHub Actions: https://github.com/vortexflash4-coder/vortex-x/actions/runs/43005390295
- Tested source: `0fa5a043209c25033c93e74e716352d9fd80af0b`.
- `assembleDebug`: PASS. `assembleDebugAndroidTest`: PASS, compiled only.
- JVM: **61 passed, 0 failed, 0 skipped**, including both allocation gates.
- Final heap measurements: core **0**, PCM16 **256**, float **0** bytes per
  5,000 measured input buffers; direct growth **0**. Representative warm-up is
  5,000 inputs; the <1,024-byte gate is unchanged. The earlier 1,144-byte failure
  is retained in the evidence and review report.
- Lint: **0 errors, 37 warnings** after fixing the missing Media3 opt-in.
- Overall CI: **FAIL** at the required Phase 1 signing-compatibility gate.
- Original debug key not available in CI; Phase 1 source ZIP contains no keystore.
- Phone verification: PENDING. **A1 BLOCKED**. No uninstall, data clear or merge.

See `A0_REVIEW_REPORT.md` for actual results and `STAGE_A0.md` for the phone checklist.

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

