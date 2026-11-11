# Current owner install target: fresh A0 installation

Phase 1 was uninstalled by the owner. Fresh-install CI now passes with the pinned
A0 debug key; the old Phase 1 key is not required for this new installation.
Package identity remains `com.vortexx.player.debug`. Old app-data restoration is
not claimed. Use **FRESH_INSTALL_PHONE_CHECKLIST.md** for the current phone test.
The legacy upgrade instructions below apply only to an existing Phase 1 install.

CI: https://github.com/vortexflash4-coder/vortex-x/actions/runs/43014067553
**A0 phone acceptance is pending. A1 has not started.**

# Stage A0 review — phone verification gate

A0 is not complete until the Android builds, JVM/allocation gates, signing check,
and real-phone checklist are recorded as passing. A1 is blocked.
See `A0_REVIEW_REPORT.md` for actual results; source changes are not test evidence.

## Exact changes

- `VortexAudioProcessor`: one fixed 8,192-float scratch array; output capacity
  32,768 bytes reserved during flush. Each call consumes at most 1,024 complete
  frames. Large decoder buffers are drained incrementally without resizing the
  array or direct output buffer in `queueInput`. Pending output is retained until
  read. Reconfiguration remains a lifecycle operation that can allocate.
- Battery mode checks the volatile processing gate for each chunk and copies PCM
  bytes unchanged, without EQ, preamp, meter or load processing. The PCM adapter
  remains configured even when battery mode is initially selected, so fallback
  works in both directions without relying on hardware offload or reconfiguration.
  Re-entry clears stale filter state and fades in from dry. Actual offload remains
  a Media3/device decision. Already queued AudioTrack samples cannot be recalled;
  audible changes still include output-buffer latency. An abrupt switch to raw
  battery bypass can click on a waveform with a large wet/dry difference; measure
  this on the phone. The separate user bypass retains its 20 ms crossfade.
- Status resolver reports battery PCM bypass even while the PCM adapter is
  configured; it does not claim hardware offload unless the output reports it.
- Auto headroom scans the *combined* ten-filter response at the active sample
  rate (2,049 logarithmic points plus exact centres), adds 1 dB and rounds toward
  more attenuation. Preamp range is now −48 to +12 dB; existing stored values
  remain valid. This is a one-shot action: reapply after EQ/sample-rate changes.
  Computation is outside the audio callback; the UI caches the recommendation.
- A sample ceiling protects active DSP output in both float and PCM16 paths.
  The clip counter records pre-ceiling overs. This is hard saturation, not a
  limiter, compressor, or true-peak/inter-sample protection. Headroom is an
  estimate for steady-state response, not a guarantee for every transient.
  Full dry battery bypass is byte-exact; user bypass settles to dry in 20 ms.
- EQ bank scrolls horizontally with 56 dp-wide faders and 168 dp-high tracks;
  TalkBack SetProgress semantics are retained and unavailable bands are disabled.
- Allocation counters must exist and return nonnegative values. Missing counters
  fail the test instead of skipping. Adapter tests also check direct-buffer
  count/bytes, varying chunk sizes, PCM16/float, eight channels and mode changes.
- Both CI workflows attempt JVM and instrumentation builds independently of
  assembly. A JUnit gate rejects missing reports, any skipped/failed test, and
  either absent allocation test. Signing compatibility is a separate required
  gate. Only a passing candidate is uploaded as a phone APK.

## Package, data and signing

Package stays `com.vortexx.player.debug` for debug and `com.vortexx.player` for
release. Reviewed version is code 3 / `0.2.1-a0-review-debug`. No database,
DataStore name, preference-key, permission, manifest or library migration changes
are introduced by this review. No uninstall, clear-data or migration operation
has been performed.

The supplied Phase 1 APK's SHA-256 signer is:
`84970dbb7b74f0f54fee68811e4bedd429393e0667ccb1f82752cc8be6d3706f`.

The A0 ZIP's bundled debug key is **different**:
`0011cfbf999ddb460d382f89a839a1b5e286dbd515cbe5965500ec4cce115c9f`.

**Do not uninstall Phase 1 to work around this mismatch.** Its original private
DEBUG key is required for an ordinary data-preserving update. A certificate or
APK alone cannot recover that private key. The bundled key is kept solely for
build/emulator use, and is never configured for release signing.

Supply the original key using `VORTEX_DEBUG_KEYSTORE` (absolute path) and optional
`VORTEX_DEBUG_STORE_PASSWORD`, `VORTEX_DEBUG_KEY_ALIAS`, and
`VORTEX_DEBUG_KEY_PASSWORD`. Default values are Android's standard debug values.
CI accepts only the dedicated `VORTEX_PHASE1_DEBUG_KEYSTORE_BASE64` secret for this
key. Production/upload credentials must remain separate. The workflow removes
its temporary debug keystore and never uploads it. Do not change the expected
fingerprint to make a mismatching key pass.

## Required commands and evidence

```bash
./gradlew --stacktrace testDebugUnitTest
python3 .github/scripts/verify-jvm-reports.py
./gradlew --stacktrace assembleDebug
./gradlew --stacktrace assembleDebugAndroidTest
python3 .github/scripts/verify-apk.py
```

For the ZIP-based repository, replace its root workflow with the included
`docs/phone-build/build-from-zip.yml` before uploading the reviewed ZIP; an older
workflow will not enforce the new signing/allocation gates.

Record GitHub run URL, commit, APK hash, signer, XML totals (passed/failed/skipped),
allocation measurements and individual command exit codes. Compilation of an
instrumentation APK does not mean instrumented tests ran. Optional emulator run:
`./gradlew connectedDebugAndroidTest`.

## Legacy Phase 1-upgrade phone checklist — only for an existing Phase 1 install

Record phone model, Android build, app version, APK SHA-256, CI run URL, and output
route before testing. Use comfortable listening volume. Keep Phase 1 installed.

| # | Action | Expected / record |
|---|---|---|
| 1 | Compare candidate signer with Phase 1, then install **over** Phase 1 | Matching signer and package; no uninstall/data clearing. Stop on signature error. |
| 2 | Compare library, playlists, favourites, settings and video resume | Existing data retained; permissions unchanged. |
| 3 | Play local music; use mini player, queue/reorder/next, seek and repeat | Phase 1 controls work, no crash or loss of queue. |
| 4 | Lock screen, notification controls, headset button, audio focus interruption, disconnect headphones | Expected background playback and pause behavior retained. |
| 5 | Flat EQ at 0 dB; then 1 kHz +6 dB at safe volume | Flat sounds unchanged; boosted tone changes audibly. Capture loopback if available. |
| 6 | User bypass on/off 10 times while playing | No obvious clicks; allow displayed output-buffer delay. |
| 7 | While boosted, select Battery on a route/codec **without offload** | PCM continues; status Off: battery mode; EQ/preamp changes have no effect. Return to DSP without restart and verify effect resumes. |
| 8 | Repeat with a stream/route that **supports offload** | Status follows actual output (offload only if opened); resume DSP and verify processing. Record codec/route/support. |
| 9 | Cold-start in saved Battery mode, play unsupported-offload PCM, then select DSP | Raw playback initially; EQ becomes effective without changing the song. |
| 10 | Toggle Battery/DSP rapidly; pause/resume, seek and switch tracks during toggles | No stuck state, stale filter burst, dropped queue or crash. Record clicks and audible transition delay. |
| 11 | Set 500 Hz, 1 kHz, 2 kHz +12 dB, then all ten +12; use Auto headroom | Recommended attenuation exceeds largest single boost; after settling no new clips on test tones. Counter/reset works. Record loud-music transients separately. |
| 12 | Change sample rates 44.1/48/96 kHz, mono/stereo; reapply Auto headroom | Correct format; supported bands work; unusable high bands disabled. |
| 13 | On compact screen, swipe bank to 16 kHz, drag/double-tap each end; TalkBack and large font | Separate controls; accessible values and adjustment, no overlap; portrait/landscape usable. |
| 14 | Connect/disconnect Bluetooth/wired route; test speaker again | Playback behavior, route diagnostics and counters remain correct. |
| 15 | 30 minutes DSP playback with screen off, plus same track/route in Battery mode | Record starting/ending underruns, load average/worst, temperature and battery delta; no crash. No battery savings claim before this. |
| 16 | Restart app; open video and test resume/rotation/scaling | DSP settings persist; music and existing video functions work; video DSP remains out of A0 scope. |

Gate outcome: **PENDING**. Record pass/fail per row, measurements and any unsupported
hardware scenario explicitly. A1 cannot begin until owner confirms phone A0.

## Hosted verification update

The final CI run passed 61 JVM tests with 0 skipped, both APK assemblies, and lint with 0 errors / 37 warnings. The full job fails at Phase 1 signing compatibility. Adapter allocation warm-up now exercises 5,000 inputs covering the same paths as the unchanged 5,000-input measured loop; the <1,024-byte threshold and direct-buffer assertions are unchanged. Final heap measurements: core 0, PCM16 256, float 0 bytes. The earlier 1,144-byte failure is retained in the review evidence. These results describe warmed JVM processing, not Android cold-start allocation behavior. No phone verification or A1 work has started.


## Fresh-install target after Phase 1 uninstall

The owner reported uninstalling Phase 1 on 11 November 2026. The active review
workflow now defaults to `fresh-install`, verifies the pinned stable A0 debug
certificate, and produces a phone-test APK without requiring the old key.
Package identity stays `com.vortexx.player.debug`. Restoration of the uninstalled
Phase 1 app data is not claimed. The original Phase 1 certificate file and
`phase1-upgrade` verifier mode remain intact for genuine data-preserving updates.
The verifier CLI still defaults to `phase1-upgrade`; fresh install is explicitly
selected by the workflow. Production signing is unchanged. A1 remains blocked
until the A0 phone checklist is completed.
