# Sources, credentials and owner actions

Nothing here is connected yet. Terms and availability are re-checked when each adapter is built (M3).

## Needs nothing from the owner
- Local music and video (MediaStore) — working today.
- User-provided HTTPS media URLs (progressive MP3/AAC/MP4, HLS, DASH) through Media3 — planned M3.
- Local `.lrc` lyrics next to the audio file — planned M2.

## Candidate open/authorized sources (to verify licence and API terms before use)
| Candidate | What it could provide | Needs |
|---|---|---|
| Internet Archive public-domain / Creative Commons items | films, audio, per-item licence metadata | per-item licence check; no key for public items |
| Blender Foundation open movies (CC BY) | a few films with attribution | attribution in the app |
| Jamendo API (Creative Commons music) | catalogue, search, streams | developer client ID from the owner; terms check |
| Owner's own server (WebDAV / SMB / Jellyfin-style API) | the owner's films and music | server address and login, entered on the phone |

## Commercial services
Spotify, YouTube, Netflix, Prime Video, JioCinema, Gaana, JioSaavn and similar: only through their official SDK/API with an agreement that covers playback in a third-party app. A metadata API is not a streaming licence. None is planned without such an agreement.

## Credentials the owner may be asked for (only when that adapter is built)
- Developer/client IDs for an open-licence API the owner chooses.
- Server address and login for the owner's own media server (stored on the phone with Android Keystore encryption, never in the repo or backups).
- Release signing key and Play Console account — only when a public release is wanted.

## Never
Scraping protected services, bypassing DRM, invented entitlements, a fake "Vortex Library" backend, secrets inside the APK/repo/backups/logs.
