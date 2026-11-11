# Architecture

## Layers

```
UI (Compose screens)  ──►  ViewModels (StateFlow)  ──►  Repositories  ──►  Platform / storage
                                     │
                                     └──►  PlaybackConnection ──MediaController──► PlaybackService (MediaSession + ExoPlayer)
```

- **domain/** — pure Kotlin: `Song`, `Video`, `SongCollection`, `LibraryLogic` (sort, filter, group, duplicates), `ResumePolicy`, `PlaybackErrorExplainer`. No Android types, so it is unit-tested on the JVM.
- **data/** — `MediaLibraryRepository` (MediaStore + ContentObserver → `StateFlow<LibraryState>`), `ArtworkLoader` (LRU memory cache, `loadThumbnail` on Android 10+, `MediaMetadataRetriever` before), Room (`favorites`, `playlists`, `playlist_items`, `watch_progress`), DataStore settings with validation.
- **playback/** — `PlaybackService` owns the music ExoPlayer inside a `MediaSession`; Media3 supplies the notification, lock-screen controls and media-button handling. `PlaybackConnection` is the UI-side `MediaController` wrapper; it queues commands until connected and exposes `PlaybackUiState` plus a lifecycle-aware position flow.
- **feature/** — one package per destination; each has a ViewModel and stateless-ish composables.
- **ui/designsystem/** — tokens (`VortexColors`, `VortexSpacing`, `VortexRadius`, typography, shapes) and components (cards, chips, buttons, state views, artwork, song row, dialogs) with previews.

## Key decisions

| Decision | Why |
|---|---|
| Single `:app` module with package boundaries | Phase 1 priority is a build that succeeds first time. Packages map 1:1 to future Gradle modules (`:core:designsystem`, `:playback`, `:library`, `:feature:*`) for Phase 5. |
| Music and video use separate ExoPlayer instances | Music must survive in the background via the session service; video lives in its ViewModel so the decoder survives rotation and is released when the screen closes. Audio focus stops the two from mixing. |
| `configChanges` on `MainActivity` | Avoids tearing down the video surface on rotation; Compose re-lays out. ViewModels still handle process recreation. |
| URIs in `RequestMetadata` | `MediaItem.localConfiguration` is not sent from controller to session; `onAddMediaItems` restores it. |
| Sleep timer lives in the service | It must fire with the screen off and the UI controller released. |
| Settings catalog is declarative | Visibility by mode and search are pure functions (tested); no control is shown without behaviour behind it. |
| No `MANAGE_EXTERNAL_STORAGE` | MediaStore + Storage Access Framework cover the use cases and keep Play policy simple. |

## Threading

- MediaStore queries and grouping: `Dispatchers.IO` / `Default`.
- `MediaController` and ExoPlayer: main thread only.
- Thumbnail decoding: IO, capped at 4 concurrent loads.
- Position polling only while the UI is started (`collectAsStateWithLifecycle`).

## Memory

- Artwork cache ≤ 1/8 of heap (max 64 MB), trimmed in `VortexApp.onTrimMemory`.
- Large lists are `LazyColumn`/`LazyVerticalGrid` with stable keys and content types.
