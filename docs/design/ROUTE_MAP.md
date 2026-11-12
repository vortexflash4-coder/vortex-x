# Route map — references → native screens

Status today: A0 build (Phase 1 screens + Audio Lab A0). Milestones as in PROJECT_STATE.md. "Data" says where every value comes from; nothing is sample content.

| Ref | Screen / route | Data source | Today | Milestone |
|---|---|---|---|---|
| 01 | Brand, components, launcher icon | design tokens, vector mark | draft board only | M1 |
| 02 | Home `home` — featured, continue watching, playlists, on-device entry | local library, watch history, playlists; featured = transparent local rule (e.g. most recently added album) until a real source exists | Phase 1 Home | M1 (local), M3 (online rows) |
| 03 | Music `music` — Songs/Albums/Artists/Folders, list/grid, sort, search, multi-select, folders | MediaStore + Room | Phase 1 library (no grid/multi-select) | M1 |
| 04 | Now Playing `nowplaying` — artwork, source + route chips, seek, transport, favourite, Audio Lab card, lyrics/queue/sleep | Media3 session, AudioDiagnostics, PlaybackEngine status | Phase 1 Now Playing | M1 (waveform only if computed, D8) |
| 05 | Lyrics + Queue `nowplaying/lyrics`, `queue` | local .lrc/.txt beside the file or embedded; real queue | queue only | M1 queue polish, M2 lyrics |
| 06 | Audio Lab EQ `audiolab/eq` | DspChain, EqResponse, MeterBus | A0 Lab (no graph/presets/A-B) | M2 |
| 07 | Audio Lab Dynamics/Space `audiolab/dynamics`, `audiolab/space` | new DSP stages, measured in CI | — | M2 |
| 08 | Online music `music/online` | authorized source adapters only | — | M3 (SOURCE-DEPENDENT) |
| 09 | Cinema `video/cinema` — Discover, Watchlist, On device | on-device videos now; online sources later | Videos grid | M1 (On device), M3 |
| 10 | Film / video detail `video/detail/{id}` | file metadata, resume position, tracks; synopsis/trailer only from a source that provides them | — | M1 (local detail), M3 |
| 11 | Video player `video/player` — portrait panel + immersive, speed, audio, subtitles, fit, lock, PiP, chapters | Media3 tracks; chapters only from container metadata | Phase 1 player | M1 (shell, lock, PiP), M2 |
| 12 | Video & subtitles settings | Media3 text renderer, SubtitleConfiguration, external file picker | scaling only | M2 |
| 13 | Downloads `downloads` — queue, progress, Wi-Fi rule, sources | Media3 DownloadManager/DownloadService | — | M3 |
| 14 | You → Settings hub `settings` — search opens the setting | settings inventory | Phase 1 Settings | M1 |
| 15 | Audio output & performance `settings/audio-output` | route/format from AudioDiagnostics; Studio/Balanced/Battery | DSP/Battery selector in Lab | M2 |
| 16 | Appearance & controls `settings/appearance` | theme, accent, text scale, contrast, motion, gestures, haptics | — | M1 (theme/scale), M2 (gestures) |

Extra routes allowed: album/artist/folder detail, playlist detail, search, source detail, diagnostics, about/licences.
