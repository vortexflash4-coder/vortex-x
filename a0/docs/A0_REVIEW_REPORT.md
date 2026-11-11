# VORTEX X — A0 fresh-install verification

11 November 2026 · **Fresh-install CI SUCCESS. Real-phone verification pending.**

The owner reported uninstalling Phase 1. The active delivery target is now a new
A0 installation, using the fixed A0 debug key. The old Phase 1 key is not needed
for this fresh install. Restoration of the uninstalled app's data is not claimed.

## Current actual results

CI: https://github.com/vortexflash4-coder/vortex-x/actions/runs/43014067553

Tested commit: `78c22823b519adffef09ad9a1f82d2196c6c3caf`.
Source/review: https://github.com/vortexflash4-coder/vortex-x/pull/1.

| Check | Actual result |
|---|---|
| Full GitHub Actions workflow | **SUCCESS** for `fresh-install` |
| JVM tests | **61 passed, 0 failed, 0 skipped** |
| Allocation gates | **PASS**; core 0, PCM16 256, float 0 heap bytes per measured 5,000 inputs; direct growth 0 |
| `assembleDebug` | **PASS** |
| `assembleDebugAndroidTest` | **PASS**, compiled only; instrumentation not executed |
| Signing-target contract fixtures | **8 passed**; simulated SDK output tests gate selection separately from JVM tests |
| Real APK signature/package | **PASS**; valid v2 signature, pinned A0 debug key, `com.vortexx.player.debug`, version code 3 |
| Lint | **0 errors, 37 warnings** |
| Actual phone behavior | **PENDING**; no successful installation or playback confirmation received |
| A1 | **NOT STARTED** |

## APK

Filename: **VortexX-A0-debug.apk**. Size: **23,757,709 bytes**.

SHA-256: `f62c935d51cf5ca5bccc957fc1e7162f7478b28a3ca29427a1ef67a514862f36`.

Pinned signer: `0011cfbf999ddb460d382f89a839a1b5e286dbd515cbe5965500ec4cce115c9f`.

The application binary is unchanged from the preceding A0 build. This CI change
verifies the correct installation scenario after Phase 1 was removed. The APK
contains the existing A0 upgrade: real ten-band PCM EQ, preamp, live meters,
bypass/battery behavior, combined-filter headroom and a scrollable fader bank.
It is a phone-test candidate, not a claim that device acceptance is complete.

## Fresh install and legacy upgrade remain separate

- `fresh-install` explicitly requires the pinned stable A0 debug certificate and
  keeps package identity `com.vortexx.player.debug`. Future debug updates must
  retain that same key to preserve data created in the new A0 installation.
- `phase1-upgrade` still requires the original Phase 1 certificate. Its original
  fingerprint file is unchanged, and the verifier CLI still defaults to this
  conservative mode when no target is supplied.
- The new fixtures confirm that fresh install accepts only the A0 key, the old
  upgrade mode still rejects that key, the original Phase 1 key is accepted only
  for that old target, and missing certificates, wrong packages and old version
  codes cannot pass.
- The workflow defaults to fresh install for the current owner-reported state.
  It does not load old-key secrets for this target. Release signing remains
  unconfigured, separate from debug credentials.

Earlier red Phase 1-compatibility results remain valid historical results. They
were not changed into green data-preserving upgrade claims. The owner has already
uninstalled that app, so old settings/favourites/playlists may have been removed;
no recovery or restoration has been demonstrated.

## A0 implementation and verification limits

The reviewed DSP/UI fixes remain unchanged: fixed 8,192-float scratch space and
32,768-byte output capacity, bounded 1,024-frame processing, next-chunk raw PCM
battery bypass even when offload is unsupported, combined ten-filter headroom
with a 1 dB margin, active-output sample clipping protection, and horizontally
scrollable 56 dp faders. Core headroom tests recommend −19.5 dB for all ten bands
at +12 dB and pass at 44.1/48/96 kHz. No A1 processing features were added.

The allocation measurement is after 5,000 representative warm-up inputs, then
5,000 measured inputs per adapter encoding. Its **<1,024-byte heap threshold**,
measured workload and zero direct-buffer-growth checks are unchanged. The prior
**1,144-byte failure** and the earlier 60-pass/1-fail report remain in the evidence
history. The exact class/site of that earlier sample was not profiled; these
results do not guarantee allocation-free cold start or Android runtime behavior.

Lint's eight missing Media3 opt-in errors were fixed earlier; 37 warnings remain.
The compact-fader accessibility test is compiled but not executed. Hardware
offload, audible transition clicks/delay, routes, battery/load/underruns, long
playback and existing video behavior still need phone checks. The sample ceiling
is hard saturation, not an A1 limiter or true-peak guarantee. Auto headroom is a
one-shot action that must be reapplied after EQ/sample-rate changes.

## Phone handoff

1. Install `VortexX-A0-debug.apk`. If Android reports a package/signature conflict
   or retained app data, record the error instead of clearing data automatically.
2. Grant media access, play a local song, then open Audio Lab. Confirm Active
   status and moving meters.
3. At comfortable volume, change 63 Hz or 1 kHz to +6 dB and compare DSP/Bypass.
   Confirm a clear effect, then test Battery mode and return to DSP.
4. Complete the fuller phone checklist in `Stage-A0-phone-checklist.md` in the
   evidence ZIP: controls, routes, saved settings, video, load and long playback.

**A0 phone acceptance remains pending; A1 remains blocked until it is recorded.**

The evidence bundle includes current raw CI logs/XML/lint reports and the prior
validation history. Current source remains in draft PR #1; main is not merged.
