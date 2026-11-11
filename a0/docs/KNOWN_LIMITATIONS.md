# Known limitations (Phase 1)

## Build
- A0 verification status is recorded in `A0_REVIEW_REPORT.md`. GitHub Actions and real-phone checks are separate gates.
- The bundled A0 debug key does not match the supplied Phase 1 APK. The original Phase 1 debug key is required for a data-preserving update. Never uninstall to bypass this gate.
- Lint runs report-only in CI until the first clean pass.

## Playback
- Queue and playback position are **not** restored after the app process is killed (Phase 2: persistent queue).
- No playback resumption from a media button when the app is not running (needs `MediaButtonReceiver` + `onPlaybackResumption`, Phase 2).
- Gapless playback depends on the format metadata (MP3 with LAME/iTunes info, AAC); it is not forced.
- Formats are limited to the device's MediaCodec decoders plus ExoPlayer's built-in extractors. No FFmpeg/Opus/FLAC native extensions are bundled. FLAC needs Android 8.1+ decoders on most devices.
- Opening a file from another app ("Open with VORTEX X") is not registered yet; use Media Explorer → Open a file.

## Video
- Picture-in-picture, brightness/volume swipe gestures, double-tap seek, pinch-to-zoom, A–B repeat, external SRT loading and subtitle styling are Phase 2.
- Speed, audio-track and subtitle-track menus come from Media3's built-in PlayerView controls (functional, default styling).
- Watch history and resume apply only to MediaStore videos, not files opened through the picker.

## Audio processing
- The Audio Studio is a capability report only. No equalizer, bass boost, virtualizer, loudness or normalisation is applied in Phase 1.
- Immersive-audio support is reported, not implemented: decoder presence does not guarantee native object-audio playback on every output.

## Library
- Playlists and favourites reference MediaStore IDs; if a file is re-indexed with a new ID, it shows as "unavailable".
- Albums are grouped by MediaStore album ID; compilation albums split by folder on some devices.

## UI
- UI strings are in Kotlin, English only. Extract to `strings.xml` before localisation.
- Typography uses the system sans-serif family (no bundled font).
