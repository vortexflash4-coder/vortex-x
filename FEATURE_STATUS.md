# Feature status

Labels: **VERIFIED** (implemented and checked on the owner's phone) · **IMPLEMENTED, NOT VERIFIED** (in the build; CI compiled/tested where stated, not phone-checked) · **SOURCE-DEPENDENT** (needs a real source, account or licence) · **NOT IMPLEMENTED**.
No feature is VERIFIED yet: the A0 phone gate is open (PROJECT_STATE.md).

| Area | Feature | Status | Evidence |
|---|---|---|---|
| Library | MediaStore songs/videos, albums, artists, genres, folders, search, sort, live refresh | IMPLEMENTED, NOT VERIFIED | Phase 1 build; owner saw songs play |
| Library | Multi-select, grid view, folder include/exclude, playlist import/export | NOT IMPLEMENTED | M1/M2 |
| Playback | Media3 service, background, notification, headset buttons, focus, unplug pause | IMPLEMENTED, NOT VERIFIED | Phase 1 |
| Playback | Queue view/reorder/remove/save as playlist; shuffle/repeat; speed; sleep timer | IMPLEMENTED, NOT VERIFIED | Phase 1 |
| Playback | Queue persistence across process death | NOT IMPLEMENTED | M1 |
| Playback | Lyrics (local .lrc, synced highlight) | NOT IMPLEMENTED | M1/M2 |
| Audio Lab | Preamp, 10-band EQ, ramps, click-free bypass, clip counter, peak/RMS meters, DSP load | IMPLEMENTED, NOT VERIFIED | CI 68/68; DSP table 27/27 PASS |
| Audio Lab | Auto headroom from combined curve | IMPLEMENTED, NOT VERIFIED | EqResponseTest (10.85 dB peak; 0 clips with −10.9 dB) |
| Audio Lab | DSP / Battery mode, status from Media3's opened output, immediate bypass in Battery | IMPLEMENTED, NOT VERIFIED | DspStatusAndParametersTest |
| Audio Lab | Buffer size vs measured change-to-ear and output-start latency | IMPLEMENTED, NOT VERIFIED | LatencyTrackerTest; phone values pending |
| Audio Lab | Presets, A/B, response graph, compressor, limiter, width, balance, reverb | NOT IMPLEMENTED | M2 |
| Output | Studio / Balanced / Battery definitions, route presets | NOT IMPLEMENTED (DSP/Battery only) | M2 |
| Video | Grid, player, speed, tracks, Fit/Stretch/Crop, rotation lock, resume | IMPLEMENTED, NOT VERIFIED | Phase 1 |
| Video | PiP, lock, gestures, external subtitles, subtitle delay/style, chapters | NOT IMPLEMENTED | M1/M2 |
| Online | User HTTPS URLs, open-licence sources | NOT IMPLEMENTED | M3 |
| Online | Commercial music/film catalogues | SOURCE-DEPENDENT | needs official API/SDK + agreement |
| Downloads | Media3 download queue, offline playback | NOT IMPLEMENTED | M3 |
| Design | Brand mark, tokens, nav Home/Music/Video/Downloads/You | NOT IMPLEMENTED (draft mark + token board only) | docs/design |
| Settings | Basic/Advanced/Pro/Developer, search, reset | IMPLEMENTED, NOT VERIFIED | Phase 1 |
| Settings | Searchable hub that opens the matching setting; appearance/gestures/backup | NOT IMPLEMENTED | M1/M2 |
| CI | assembleDebug, unit tests, androidTest compile, DSP table, results branch | IMPLEMENTED | runs #4, #5 |
| CI | APK signature/package/version verification; lint in phone workflow; instrumentation execution | NOT IMPLEMENTED | next CI change (instrumentation: no emulator yet) |
