# VORTEX X — project state

Last updated: 2026-11-12 (design milestone). Read this first when resuming.

## Where the source lives
- Canonical source: this tree, delivered as `VortexX-A0r5.zip` and uploaded to `main` of
  github.com/vortexflash4-coder/vortex-x. CI: `.github/workflows/vortex.yml` (repo) runs `.github/scripts/ci-build.sh` (inside the zip) and pushes each run's summary, logs and JUnit XML to the branch `ci-results`.
- A second A0 line exists on branch `codex/a0-verification-20261111` (PR #1, source under `a0/`): versionName `0.2.1-a0-review`, versionCode 3, signed with a debug keystore committed in that branch. It was built from the first A0 package. See DECISIONS.md D1–D3.

## Gate A0 (real phone) — NOT YET ACCEPTED
The owner instruction stands: Stage A1 does not start until A0 is verified on the owner's phone.
Recorded so far (from owner screenshots, 2026-11-12): the APK from VORTEX X build #5 was downloaded; Now Playing plays a local MP3 ("Sheesha Chahe Toot Bhi Jaaye"). Which APK is installed is not yet confirmed (Settings footer shows the version).

| # | Gate check | Result |
|---|---|---|
| 0 | Installed build (Settings → bottom line shows version) | NOT TESTED |
| 1 | App installs and opens; library loads | NOT TESTED (playback seen, not confirmed as a test) |
| 2 | Audio Lab shows "DSP · Active" while a song plays; meters move | NOT TESTED |
| 3 | 63 Hz +9 dB is clearly audible; Flat restores the sound | NOT TESTED |
| 4 | Battery offload: EQ stops within a moment, no click; status "Off: battery mode" | NOT TESTED |
| 5 | Faders are readable and the bank scrolls sideways | NOT TESTED |
| 6 | Close and reopen: EQ, preamp, bypass and mode are kept | NOT TESTED |
| 7 | 10 minutes of playback with screen off: no crash, no dropouts | NOT TESTED |

A1 work started on 2026-11-12 before the master prompt arrived (Now Playing redesign, Home deck, title clean-up). It was removed from this tree and parked outside the repository; it is not in any package. It will be re-reviewed against the 16 references after the gate.

## CI evidence (A0r5, VORTEX X build #4 and #5, 2026-11-12)
assembleDebug ✅ · testDebugUnitTest 68/68, 0 skipped ✅ · assembleDebugAndroidTest ✅ (compiled, not executed) · lint not run in the phone workflow · APK 22.7 MB. Allocation: processor rounds 1184 / 0 / 0 bytes over 5000 buffers each after 5000 warm-up buffers (gate: lowest round < 1024); DSP chain 0 bytes. Signing: CI debug key kept in the Actions cache (build #4 created it, #5 reused it).
Not yet in CI: APK signature/package/version verification step (planned, see FEATURE_STATUS).

## Next checkpoint
1. Owner runs the 8 gate checks above and reports PASS/FAIL per number.
2. Fix any A0 failure within A0 scope; record results here.
3. On acceptance: Build milestone 1 (brand, components, navigation shell, local library, Now Playing, Video shell, Settings hub) per docs/design/ROUTE_MAP.md.

## Implementation sequence (after the gate)
- **M1** brand vector + adaptive icon + font; tokens in code; bottom nav Home | Music | Video | Downloads | You; mini player; Home (local sources only), Music library (03), Now Playing (04), Queue (05 without lyrics source), Video list/player shell (11), Settings hub (14) with search that opens the setting. Real screenshots from CI (Roborazzi or instrumentation) where possible.
- **M2** expanded DSP: presets save/import/export, A/B, response graph from coefficients (06); compressor, sample-peak limiter, width, balance, reverb (07) — each with measured CI tests before it is shown. Studio/Balanced/Battery (15). Full setting-to-engine wiring (docs/SETTINGS_INVENTORY.md).
- **M3** sources: user-provided HTTPS URLs, then documented open-licence sources, Media3 downloads (13), film/video detail (09/10) only with real sources. Commercial services only with their official SDKs and agreements.
- **M4** visual comparison against the references at 320/360/393/412 dp, accessibility, lifecycle/route stability, performance on the owner's phone, size report.
