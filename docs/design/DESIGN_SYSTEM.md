# VORTEX X design system (canonical)

Source: master prompt §3 and the 16 reference images. Where the images disagree, this file wins.
Board: `docs/design/brand/board.html` (rendered preview; not an app screenshot). Draft mark: `docs/design/brand/vortex-x-mark.svg`.

## Colour tokens
| Token | Value | Use |
|---|---|---|
| bg | #08090B | AMOLED background |
| surface | #14161A | Cards, sheets, mini player |
| surfaceRaised | #1B1D22 | Pressed/raised states (derived) |
| line | #272A30 | 1 dp dividers and borders |
| red | #F43F46 | Selected state, current playback, focus, primary action |
| redPressed | #D8333A | Pressed primary (derived) |
| text | #F4F5F7 | Primary text and icons |
| textSecondary | #969CA8 | Secondary text, inactive icons |
| success | #38C98A | "Processing", connected (semantic only) |
| warning | #F2B33D | Limited/partial states (semantic only) |
Meters: white up to −6 dBFS, red above, red LED for clip; gain reduction in its own colour. Most of every screen stays black/graphite/white.

## Type
Font: Montserrat (SIL Open Font License 1.1) bundled in `res/font` with its licence file, chosen for the wide geometric look of the references; fallback is the platform sans. ("VORTEX Sans" in image 01 is not a real font.)
| Role | sp | Weight |
|---|---|---|
| Screen hero | 30 (28–32) | ExtraBold |
| Section heading | 21 | Bold |
| Title (row/card) | 16–17 | SemiBold |
| Body | 16 | Regular |
| Secondary | 14 | Regular |
| Metadata / chips | 12–13, +0.5 sp tracking | Medium |
Numbers in readouts use tabular figures. Text must survive 200 % font scale (wrap, never clip).

## Space, shape, size
4/8 dp rhythm; screen margin 20 dp (16 dp at ≤ 360 dp width); gaps 12–16 dp. Radii: chips 20 dp (pill), cards 16 dp, artwork 12–20 dp, sheets 24 dp. Borders 1 dp `line`. Touch targets ≥ 48 dp, primary 56 dp, play/pause 64–80 dp. EQ columns 56 dp in a horizontally scrolling bank with a visible scroll indicator.

## Navigation
Bottom bar: **Home | Music | Video | Downloads | You** (fixed order, state kept per tab). Cinema is a tab inside Video. Settings opens from You and from contextual gear actions. Mini player sits above the bar; hidden in full-screen video.

## Components (native Compose, reused everywhere)
BrandHeader (mark + wordmark + screen title + actions) · BottomBar · MiniPlayer · PlayButton (primary circle) · ProgressTrack/SeekBar (red fill, white thumb) · Switch (red track when on) · Chip / FilterChip · SegmentedSelector · SectionHeader with "See all" · MediaRow (artwork, title, subtitle, duration, overflow) · MediaCard (square/landscape, progress line) · SettingRow (icon, label, value, chevron/switch/slider) · StatusChip (LOCAL • FLAC, route) · EmptyState / SourceUnavailableState · Meter, EqFader, ResponseGraph (M2).

## Icons
Outlined Material Symbols from `material-icons-extended` (Apache 2.0), stroke weight matched to the references. No third-party brand logos.

## Motion
150–250 ms fades and position changes; shared artwork continuity mini player ↔ Now Playing; press feedback on every control. Reduce-motion setting and the system animator scale turn these into cross-fades. No visualizers running behind ordinary pages.

## Brand mark and launcher assets (to produce in M1)
One vector path set from the draft mark: two ribbon arms forming a V, small play aperture in negative space. Deliverables: `ic_vortex_mark.xml` (vector), wordmark as outlined paths (Montserrat ExtraBold, X in red), adaptive icon (foreground mark inside the 66 dp safe zone of 108 dp, background #08090B→#14161A, monochrome layer for themed icons), Play Store 512 px PNG, notification small icon (white silhouette). All produced from the same path.

## Resolved inconsistencies from the images
- Bottom bars differ between images (Home/Music/Video/Downloads/You vs Home/Video/Audio/Library/Profile vs …Settings): use D5 order.
- Logo outlines differ: one mark (above).
- Accent shades vary (#F43F46 vs brighter reds): one red token plus derived pressed state.
- Example songs, films, sizes and meter numbers are illustrations only (D7).
