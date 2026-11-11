# VORTEX X — A0 verification report

11 November 2026 · Stage A0 · **Phone verification pending. A1 blocked.**

GitHub repository access is now working. Both Android builds and all 61 JVM tests
pass in GitHub Actions. **The overall workflow is FAILED because the original
Phase 1 debug signing key is unavailable and the APK certificate does not match.**
This is a build-verified APK, not an approved update for the installed Phase 1 app.

## Actual final hosted results

Run: https://github.com/vortexflash4-coder/vortex-x/actions/runs/43005390295

Tested source commit: `0fa5a043209c25033c93e74e716352d9fd80af0b`.
Review: https://github.com/vortexflash4-coder/vortex-x/pull/1 (draft, not merged).

| Check | Actual result |
|---|---|
| `assembleDebug` | **PASS**, Gradle exit 0; APK produced |
| `testDebugUnitTest` | **PASS: 61 passed, 0 failed, 0 skipped**, exit 0 |
| JUnit/allocation report gate | **PASS**; both allocation tests ran |
| `assembleDebugAndroidTest` | **PASS**, exit 0; compiled and packaged only |
| `lintDebug` | Task completed; report has **0 errors, 37 warnings** |
| Package identity/version | `com.vortexx.player.debug`, version code **3** |
| APK cryptographic signature | Valid v2 signature, verified with build-tools **35.0.0** |
| Phase 1 certificate compatibility | **FAIL** — actual and expected certificates differ |
| Full Actions workflow | **FAIL** — required signing gate rejects phone delivery |
| Emulator/instrumentation execution | **NOT RUN** |
| Real-phone verification / data-preserving update | **NOT RUN / BLOCKED** |

The report uses Gradle exits, JUnit XML and the signing log. A completed Actions
step with `continue-on-error` is not itself evidence that its gate passed.

| JVM class | Passed | Failed | Skipped |
|---|---:|---:|---:|
| DspChainTest | 13 | 0 | 0 |
| DspHeadroomTest | 4 | 0 | 0 |
| DspStatusAndParametersTest | 5 | 0 | 0 |
| LibraryLogicTest | 10 | 0 | 0 |
| PoliciesAndFormatTest | 10 | 0 | 0 |
| SettingsTest | 8 | 0 | 0 |
| VortexAudioProcessorTest | 11 | 0 | 0 |
| **Total** | **61** | **0** | **0** |

All 53 original JVM scenarios remain covered, with eight added regression tests.
Two expectations were updated to match the reviewed behavior: battery PCM keeps
its adapter configured while copying raw bytes, and preamp attenuation extends
below −12 dB. No test was ignored or skipped.

## Allocation results and the failed measurement

The final adapter test warms **5,000 representative input buffers**, then measures
an unchanged **5,000-input-buffer loop** for each encoding. Inputs alternate
17/4,097 frames, use eight channels, change cached parameter snapshots and switch
battery/DSP gates. Large inputs are drained in bounded chunks. The limit remains
**less than 1,024 heap bytes** across the measured loop, with zero direct-buffer
pool growth. Missing/disabled counters fail; no assumption skips are used.

| Final measured path | Heap bytes | Direct-buffer growth | Gate |
|---|---:|---:|---|
| DSP core | **0**, after 40-byte counter overhead | Covered through adapter tests | PASS |
| PCM16 adapter | **256** | **0 bytes / 0 buffers** | PASS |
| Float adapter | **0** | **0 bytes / 0 buffers** | PASS |

An earlier hosted run **failed**: the PCM16 adapter measured **1,144 bytes** and
the suite finished **60 passed, 1 failed, 0 skipped**. Its XML and logs are retained
in the evidence bundle. The earlier warm-up ran only one constant DSP setting;
it did not warm the short-input, bulk-copy/battery or parameter-transition paths
used during measurement. Warm-up now exercises the measured pattern. **The
measured workload, threshold and direct-buffer assertions were not weakened.**
The exact allocation class/site for that 1,144-byte sample was not profiled.
These are steady-state HotSpot JVM measurements, not a cold-start or Android
allocation-free guarantee. Real-device profiling remains pending.

The earlier local run passed 61/61 with core **0**, PCM16 **256**, float **240**
heap bytes and zero direct-buffer growth. Those local results remain in the
history; they are not substituted for the final hosted measurements.

## Exact source and CI fixes

- `VortexAudioProcessor`: fixed **8,192-float** scratch storage and **32,768-byte**
  direct output capacity reserved during flush. Each call consumes at most
  **1,024 complete frames**. There is no dynamic scratch resize or output-buffer
  growth in the processing loop; pending output is not overwritten.
- Battery preference: the volatile gate is checked each PCM chunk. Battery mode
  copies raw PCM without EQ/preamp processing, even if hardware offload is not
  supported and the existing PCM sink stays open. The adapter stays configured,
  allowing DSP to resume without waiting for output reconfiguration. Re-entry
  clears stale filter state and fades from dry.
- Status: configured PCM availability is distinguished from active processing;
  battery PCM fallback is shown as bypassed. Hardware offload is reported only
  when Media3 actually opens an offloaded output.
- Headroom: scan the **combined ten-filter response**, including overlap, at the
  active sample rate; add **1 dB** margin and round toward more attenuation.
  The preamp floor extends to **−48 dB**, with existing stored values retained.
- Clipping: a sample ceiling protects active PCM16/float output, while clip counts
  record pre-ceiling overs. The +12 dB ten-band tests recommend **−19.5 dB** at
  44.1/48/96 kHz and measured zero pre-ceiling clips for the tested near-full-scale
  tones with that headroom. Burst and changing-gain regressions also pass.
- EQ layout: horizontally scrollable bank with **56 dp-wide** faders and 168 dp
  tracks. TalkBack range controls remain present; unavailable bands are disabled.
  The compact-layout accessibility test compiles; it has not executed on a device.
- `PlaybackService`: add the required Media3 opt-in annotation to the output-mode
  observer. This clears all **8 lint opt-in errors** found in the first hosted
  report without changing playback logic. **37 warnings remain** in lint.
- CI: build directly from reviewed `a0/` source, attempt both APK builds and all
  JVM tests, reject missing/skipped allocation evidence, preserve raw reports,
  and keep signing compatibility as a required gate. The previous main workflow
  stopped with exit **127** because its ZIP lacked `ci-build.sh`.
- Signing diagnostics: use stable build-tools **35.0.0**, record public certificate
  output and distinguish an unreadable digest from a mismatch. The first hosted
  verifier selected a tool that produced no parsed digest; its blocked result
  is retained. The final tool verifies and reports the certificate correctly.

## APK identity and signing blocker

Build-only APK: `VortexX-A0-build-only.apk` (23,757,709 bytes).

SHA-256: `f62c935d51cf5ca5bccc957fc1e7162f7478b28a3ca29427a1ef67a514862f36`.

| Certificate | SHA-256 |
|---|---|
| Supplied Phase 1 APK | `84970dbb7b74f0f54fee68811e4bedd429393e0667ccb1f82752cc8be6d3706f` |
| Current A0 build | `0011cfbf999ddb460d382f89a839a1b5e286dbd515cbe5965500ec4cce115c9f` |

The original debug key was not restored in CI. A separate read-only inventory of
`VortexX-phase1-reviewed.zip` found **zero `.keystore`, `.jks`, `.p12` or `.pfx`
files**. Inventory run: https://github.com/vortexflash4-coder/vortex-x/actions/runs/43006141200.
The private key was also absent from the earlier source ZIPs examined. This does
not establish whether the owner retained a copy elsewhere.

A normal update preserving installed data needs the **original Phase 1 debug
private key**. Creating another key does not solve this mismatch. **Do not
uninstall Phase 1 or clear app data to install this build-only APK.**

CI accepts that original debug key through `VORTEX_PHASE1_DEBUG_KEYSTORE_BASE64`
or the existing `VORTEX_DEBUG_KEYSTORE_B64` secret. Debug-only passwords/alias
have their own variables. Release signing remains unconfigured. No production
credentials are loaded, no key contents are uploaded, and temporary key files
are removed. The old create-key workflow was not invoked.

## Phase 1 preservation and real-device gate

Debug identity remains `com.vortexx.player.debug`; release identity remains
`com.vortexx.player`. Version code is **3** / `0.2.1-a0-review-debug`. Manifest and
all nine data-layer files are unchanged from the supplied A0 source. Room schema,
DataStore names, preference keys, permissions and existing music/video features
were not migrated or removed by this review. Runtime regression checks remain
part of phone verification. Main is unchanged; the PR is still a draft.

The full real-device checklist is included as
`Stage-A0-fixes-and-real-device-checklist.md` in the evidence ZIP and in
`a0/docs/STAGE_A0.md` in the PR. It covers compatible update/data retention,
playback/queue/notification controls, bypass with supported and unsupported
offload, cold-start battery preference, sample-rate changes, compact controls
and TalkBack, routing, 30-minute playback/load/underruns, settings persistence
and existing video behavior.

Already queued AudioTrack samples delay audible changes. An immediate raw
battery switch can click on a large wet/dry difference; this needs phone testing.
The separate user bypass uses its 20 ms crossfade. Headroom is a one-shot
steady-state estimate: reapply after EQ/sample-rate changes. The sample ceiling
is hard saturation, not a limiter or a true-peak guarantee. Configure/flush can
allocate. Video DSP, battery savings, long playback and hardware-specific
behavior are not verified by these JVM/build results.

**A0 stays blocked on compatible signing and phone verification. A1 has not
started.**

## Source provenance

Latest tested application/test source: commit
`0fa5a043209c25033c93e74e716352d9fd80af0b`.
Build-relevant source digest: `84338df670ea6fe11c7495ce165ed8805549eed9d07e9eddf3b2607d9b5e4949`.

The evidence ZIP contains per-file hashes, final CI XML/logs/lint reports,
allocation-failure history, Phase 1 signing inventory, and the compiled
instrumentation APK explicitly labeled **NOT RUN**. Current source is in draft
PR #1; earlier source/archive and local validation are historical snapshots.
