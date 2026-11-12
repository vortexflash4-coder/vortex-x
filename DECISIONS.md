# Decisions

D1 (2026-11-12) — Canonical A0 source is the VortexX-A0r5 tree on `main`, not `a0/` on the Codex branch. Reason: it is the build the owner downloaded; it already contains the three fixes found in PR #1 (battery-mode bypass of a playing PCM stream, combined-curve auto headroom, 56 dp scrollable faders) plus bounded processor chunks, a float sample ceiling, latency readouts and 68 JVM tests. PR #1 stays open as review history; nothing from it is discarded.

D2 — Debug signer continuity follows the APK actually installed on the owner's phone. If it is build #4/#5 (versionName 0.2.0-a0), keep the Actions-cache key (`vortex-debug-key-v1`). If it is the Codex build (0.2.1-a0-review), switch CI to that branch's keystore. Decided once the owner reports the Settings footer (gate check 0).

D3 — Next versionCode is at least 4 (both A0 lines used 2 and 3), so any later APK upgrades either installation.

D4 — No private keys in the repository or zips. The phone route keeps its key in the Actions cache with a weekly keep-alive run; Route B uses the secret `VORTEX_DEBUG_KEYSTORE_B64`. (The Codex branch contains a committed debug keystore; it is a debug key in a private repository and is not used for release.)

D5 — Navigation: Home | Music | Video | Downloads | You. Cinema lives inside Video; Settings is reached from You and contextual actions. The references disagree here; this is the canonical order.

D6 — Palette from the master prompt (#08090B, #14161A, #272A30, #F43F46, #F4F5F7, #969CA8) replaces the earlier #E71938 accent.

D7 — Online catalogues, films and "Vortex Library" are concept labels until a real authorized source is connected. Empty/unavailable states are shown instead of sample content.

D8 — Seek waveform appears only when computed from the decoded file; otherwise the plain progress track is used.

D9 — App size follows real features. No padding to reach ~360 MB; actual APK/installed sizes are reported.
