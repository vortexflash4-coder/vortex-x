# Changelog

## 0.2.1-a0-review — 2026-11-11

- Bounded PCM buffers; next-chunk battery bypass across unsupported-offload fallback.
- Combined-response headroom and sample ceiling; scrollable 56 dp faders.
- 61 local JVM tests passed (0 failed/skipped), including both allocation gates.
- Both local Android APK assemblies passed. GitHub Actions has not run.
- Phase 1 signing mismatch correctly blocks phone delivery; original debug key required.
- Phone verification and A1 remain blocked. See A0_REVIEW_REPORT.md and STAGE_A0.md.

## 0.2.0-a0 — 2026-11-11 — Stage A0: Media3 1.11.1 and DSP proof

Status: **Written** (not compiled; see `docs/BUILD_STATUS.md`).

### Added
- `audio/dsp/`: `DspChain` (preamp, 10 peaking filters at ISO octave centres, gain ramps of 0.25 dB per 32 frames, 20 ms bypass crossfade, clip counter), `BiquadCoefficients` (RBJ cookbook), `DspParameters`, lock-free `MeterBus` and `LoadMeter`, `DspHost`.
- `audio/media3/`: `VortexAudioProcessor` (16-bit and float PCM, 1–8 channels), `VortexRenderersFactory` (adds it to `DefaultAudioSink`), `AudioEncodings`.
- `audio/engine/`: `AudioEngine` (settings, persistence, DSP/battery mode), `AudioDiagnostics` (Media3 analytics + audio-device callback), `DspStatusResolver`.
- Audio Lab A0 screen (replaces the Audio Studio report): status, stereo peak/RMS meters with clip light, bypass, output mode, preamp, 10 faders, signal path, device report.
- Instrument controls: `EqFader`, `PrecisionSlider`, `StereoLevelMeter`, `SegmentedSelector`, `StatusLed`, `ReadoutGrid`.
- `app/vortex-debug.keystore` and its signing config.
- JVM tests: `DspChainTest` (13), `VortexAudioProcessorTest` (7), `DspStatusAndParametersTest` (5).
- `docs/STAGE_A0.md`.

### Changed
- Media3 1.6.1 → 1.11.1.
- `PlaybackService` builds the player with `VortexRenderersFactory`, attaches the diagnostics listener, tracks routes and applies the offload preference.
- Settings: the search field is the list's first item; the top bar shows "Detail level: …" and the level's description sits under the level chips; "Reset to defaults" no longer clears Audio Lab values.
- Top bar subtitles wrap to two lines. Navigation keeps clear of side display cutouts. List screens are width-capped and centred on tablets.
- Theme: true black background (#000000) and darker surfaces.
- Version 0.1.0-phase1 → 0.2.0-a0 (versionCode 2).

## 2026-11-11 — Phase 1 review pass

Status: **Written** (not compiled; see `docs/BUILD_STATUS.md`).

### Changed
- `.github/workflows/android-ci.yml`: every Gradle step now writes its full log to `ci-logs/`. The run always ends with a job summary and a logs artifact, even when the build fails. The run is still marked failed when `assembleDebug`, `testDebugUnitTest` or `assembleDebugAndroidTest` fails. Unit tests and the instrumentation-test compile run only after the APK builds, so the Errors box shows the first real failure.
- Artifacts: `vortex-x-debug-apk` (unchanged). `vortex-x-logs-and-reports` replaces `vortex-x-reports` and now also contains the Gradle logs.
- `feature/music/CollectionScreen.kt`: `SongActionsHost` is called directly instead of inside an empty `Box` (no behaviour change; removed unused import).
- `README.md`: phone-only build instructions now cover the zip-upload route.

### Added
- `.github/scripts/ci-summary.sh`: job summary with "APK built" (name + size) or "APK was not built", unit-test counts with failed test names, and an Errors box trimmed to `file:line`. Up to 10 errors also appear as annotations on the run page.
- `docs/phone-build/build-from-zip.yml`: workflow for a repository that contains only the project ZIP. Uploading the ZIP from a phone browser builds the APK with the same checks and summary.
- `docs/BUILD_STATUS.md`, `docs/CHANGELOG.md`.

### Static review result
No definite compile error found in the 57 Kotlin files, the manifest or the resources. This does not prove the project compiles; the first CI run decides.
