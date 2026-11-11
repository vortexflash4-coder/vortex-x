# VORTEX X — A0 verification report

Date: 11 November 2026 (Asia/Kolkata). **A0 NOT YET VERIFIED ON PHONE. A1 BLOCKED.**

Input: `VortexX-A0.zip`, SHA-256
`f2c0ff916f9f9aeb1fea8b88165e239778463ebd4d944df2fa2e18345a0fb7bd`.

## Actual validation results

| Gate | Actual result |
|---|---|
| `testDebugUnitTest` (local Android Gradle project, JDK 17) | **PASS: 61/61**, 0 failed, 0 skipped; exit 0 |
| JUnit evidence/allocation gate | **PASS** |
| `assembleDebug` (local) | **PASS**, exit 0; 23 MB debug APK produced |
| `assembleDebugAndroidTest` (local) | **PASS**, exit 0; instrumentation APK compiled and packaged, **not executed** |
| GitHub Actions builds/tests | **IN PROGRESS**: access is now confirmed; see the A0 verification workflow for current run results |
| Phase 1 signing compatibility | **FAIL** with the supplied A0 debug key |
| Instrumentation execution / real-phone checks | **NOT RUN**: no attached device |

The full app source was compiled by the Android project before running these
JVM tests. Local tooling: Temurin 17.0.21, Gradle 8.14.3 (same distribution as the
wrapper), AGP 8.11.1, Kotlin 2.1.20, compile SDK 36, build-tools 35.0.0, Media3
1.11.1. Hosted CI evidence is separate and remains absent.

| JVM class | Passed | Failed | Skipped |
|---|---:|---:|---:|
| LibraryLogicTest | 10 | 0 | 0 |
| PoliciesAndFormatTest | 10 | 0 | 0 |
| SettingsTest | 8 | 0 | 0 |
| DspChainTest | 13 | 0 | 0 |
| DspStatusAndParametersTest | 5 | 0 | 0 |
| VortexAudioProcessorTest | 11 | 0 | 0 |
| DspHeadroomTest | 4 | 0 | 0 |
| **Total** | **61** | **0** | **0** |

### Allocation measurements — do not round these to zero

Each measurement covers 5,000 input buffers after warm-up. Gate: less than
1,024 heap bytes across the entire measured loop. The adapter also requires
no growth in the direct-buffer pool count or bytes.

| Path | Measured heap bytes | Direct-buffer growth | Result |
|---|---:|---:|---|
| DSP core | 0 (40-byte counter overhead subtracted) | Not measured separately by core test | PASS |
| PCM16 adapter | 256 | 0 bytes, 0 buffers | PASS |
| Float adapter | 240 | 0 bytes, 0 buffers | PASS |

These are JVM measurements, not an assertion of universally zero allocation.
The small nonzero adapter counts were not attributed to a particular JVM site.
Adapter loops process eight-channel signal data, alternate 17/4,097-frame inputs,
change parameter snapshots and switch battery/DSP gates. Hardware/Android
profiling remains pending. No allocation test was skipped or disabled.

### DSP measurements

An earlier independent Kotlin/JUnit run passed 16 DSP-core tests. The final
Android-project JVM suite above includes all core tests plus the added burst
regression. The +12 dB ten-band response recommended **−19.5 dB** preamp at
44.1, 48 and 96 kHz. Tested near-full-scale tones at centres/between bands
produced **zero pre-ceiling clips** with that headroom. The previous largest-band
rule clips in the adjacent-boost regression; combined headroom passes. The
sample ceiling also contains multitone bursts and changing gain settings.

### Built APK identity

- APK SHA-256: `ad8a50a171df56c6c602e7279c1750206bc8c50b4af332b77c6322dea00213fb`
- Application ID: `com.vortexx.player.debug`; version code **3**.
- Candidate signer: `0011cfbf999ddb460d382f89a839a1b5e286dbd515cbe5965500ec4cce115c9f`.
- Installed baseline signer: `84970dbb7b74f0f54fee68811e4bedd429393e0667ccb1f82752cc8be6d3706f`.
- Verification script exit **1**, correctly rejecting the mismatch. This APK is
  **build-only**, not a verified phone update. Do not uninstall Phase 1 to use it.

## Current external blockers

1. GitHub access is confirmed for `vortexflash4-coder/vortex-x`. The reviewed
   source is being committed under `a0/` on `codex/a0-verification-20261111` with
   a dedicated A0 verification workflow. The earlier repository-access blocker
   is resolved. Current Actions outcomes must be read from the workflow run.
2. The supplied Phase 1 APK signer differs from the A0 ZIP's bundled debug key.
   An ordinary update that preserves installed data requires the original Phase 1
   DEBUG private key. It was not present in either source ZIP examined. The
   inaccessible repository's secrets/caches have not been inspected.
3. There is no connected real phone. Device behavior and performance remain
   unverified. No uninstall, data-clear, or phone update has been performed.

## Failures found and corrected during verification

- Initial full unit-test compilation failed because the new direct-buffer
  measurement imported JVM management APIs absent from Android's compile SDK.
  The test now accesses the same counters through reflection, preserving both
  measurements and thresholds. The failed log is retained in the evidence.
- All 53 original JVM tests remain covered. Two expectations were deliberately
  updated: battery PCM keeps the adapter configured while copying raw bytes, and
  preamp attenuation now extends below −12 dB. Eight regression tests are added;
  no test uses an assumption skip for the allocation gate.

## Exact fixes

See `STAGE_A0.md` for implementation detail and limitations:

- Fixed-size PCM scratch/output buffers and bounded 1,024-frame processing.
- Immediate next-chunk raw PCM battery bypass, including offload fallback and
  initially saved battery mode; return to DSP without waiting for reconfigure.
- Combined-response Auto headroom with 1 dB margin and a −48 dB preamp floor.
- Sample safety ceiling for active float/PCM16 output; pre-ceiling clip counting.
- Horizontally scrollable 56 dp EQ faders with TalkBack range controls.
- Non-skippable DSP and adapter allocation gates, including direct-buffer growth.
- CI reports actual XML totals and fails on skipped/missing allocation tests.
- Dedicated debug signing overrides and a Phase 1 certificate/package gate.

## Preserved Phase 1 scope

Debug package remains `com.vortexx.player.debug`; release identity remains
`com.vortexx.player`. Version code rises to 3 for the reviewed A0 build. Manifest
and all nine data-layer files match the supplied A0 source byte for byte. Room
schema, preference keys/store names, media permissions and Phase 1 library/video
features are not migrated or removed. Runtime regression checks are still needed.

Production signing remains unconfigured; no production credentials are used.
The bundled key is an isolated debug/build fallback and is not update-compatible
with the supplied Phase 1 APK.

## Non-failing build warnings

The Kotlin test compile reports a nullable reflection-return type warning in the
existing allocation helper; the runtime counter is checked and both allocation
tests passed. The debug APK packages `libandroidx.graphics.path.so` and
`libdatastore_shared_counter.so` without stripping debug symbols. Neither warning
failed assembly. Lint was not run locally. These logs are retained in evidence.

## Remaining limitations and decision

An app build is not a phone verification. Core allocation measurements cover the
measured hot paths after warm-up, not all Media3/Android allocations or device
GC. Filter configure/flush can allocate. Hardware offload support, audible switch
delay/clicks, TalkBack behavior, long playback, battery impact and underruns need
the device checklist. The sample ceiling is hard clipping protection, not a
limiter or true-peak guarantee. Auto headroom is one-shot and must be reapplied
after EQ/sample-rate changes. Music DSP only; video functionality is retained
without adding video DSP.

Complete the real-device checklist in `STAGE_A0.md` before authorizing A1.

## Source provenance

Build-relevant source digest: `90b5a088c6414923ac001743a26c5d4f373aed5bcec3a34b8b167daded790e78`.
File hashes and APK hash are included in `provenance.json` in the evidence ZIP.
The source ZIP contains the reviewed code and updated CI workflows.
