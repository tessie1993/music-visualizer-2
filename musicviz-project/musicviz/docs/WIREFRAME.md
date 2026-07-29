# MusicViz — Wireframes (low-fi)

**Canonical drawing: [`wireframe.html`](wireframe.html)** — a single
self-contained grayscale page (no external resources) with all 14 screens as
labeled ~390×844 phone frames. Open it in any browser; it is intended as
input for image-generator UI-style passes. This file keeps the intent notes;
when the two disagree, wireframe.html wins for structure.

Structure only; GUI/visual design is a later pass. Sheets slide from the
bottom, glass background (opacity = Settings › Appearance slider). The app is
a bottom-nav shell (Home · Library · Visuals · Settings) with a persistent
mini-player docked above the nav bar; Now Playing is a fullscreen overlay
expanded from the mini-player (see docs/NAVIGATION.md).

## Screen inventory (matches wireframe.html frames)

| # | Frame | Notes |
|---|-------|-------|
| 01 | Boot splash | Ripple-rings intro around the "MusicViz" wordmark — new in 0.13 |
| 02 | Home | Resume card · [Shuffle all] · Recently/Most played chip rows · mini player · nav bar |
| 03 | Library / Tracks | Search icon · tab row Tracks/Albums/Artists/Playlists/Folders · rows: title + artist · key · BPM · edit-info icon (new) |
| 04 | Library / Albums | Two-column cover grid (X-box covers), drill-in to album track list, ‹ back pops |
| 05 | Library / Playlists | Expandable rows, inline ⌃/⌄ reorder, rename dialog, play |
| 06 | Track-info editor | Bottom sheet: Title / Artist / Album / Genre chips / Year / Track # / Comment — new |
| 07 | Search overlay | Debounced field, grouped results Tracks / Playlists / Presets (new grouping), tap plays/applies |
| 08 | Visuals / Styles | Sub-tabs Particles · Shaders · Fluid · MilkDrop; style cards incl. new "Water" |
| 09 | Visuals / Customize | Slider groups with per-param locks + randomize; Water section and "Water ripples (all styles)" section — new |
| 10 | Now Playing | Fullscreen visual, top title/artist header, translucent glass transport card (seek/shuffle/repeat), quality badge row "FLAC · Lossless" — new |
| 11 | Settings overview | Collapsible sections: Appearance / Playback / Library / Visuals & Analysis / Export & About — new structure |
| 12 | Settings / Appearance | Theme grid (18 themes, scroll), bar-opacity slider, corner style, accent + dim sliders |
| 13 | Settings / Playback | Speed and pitch sliders, EQ bands + presets, sleep timer chips — new |
| 14 | Now Playing + ripples | "Water ripples (all styles)" enabled: refraction rings over any visual, annotated |

## Intent notes (kept from earlier passes)

### Shell
- Mini-player (title — artist · ▶/❚❚ · ▶▶, thin progress line) is hidden
  until media loads; tapping it expands the Now Playing overlay. The single
  VisualizerView is owned by the shell so renderer state survives
  collapse/expand.
- System back unwinds overlays: Now Playing → Search → Library drill-in →
  non-Home tab → exit.

### Visuals hub
- Everything visual lives in one hub (Presets · Styles · Customize ·
  Textures); changes apply live to the shared renderer ("same content, two
  doors" with Now Playing).
- Styles sub-tabs: Particles · Shaders · Fluid · MilkDrop. Water joins the
  Fluid family in 0.13. MilkDrop keeps [Load .milk], [Textures…], and the
  user .milk list; built-in milk presets stay removed.
- Customize sub-tabs: Motion · Shape · Behavior · Color · FX · Fluid
  (+ GLSL on shader scenes, + Water in 0.13). Per-param locks, [⚄ Randomize
  unlocked], LFO 1–3 and ADSR 1–2 editors in FX. The "Water ripples (all
  styles)" group mirrors the FlowField (all styles) pattern: an overlay any
  style can enable.
- Preset browser: user folder tree ([+ folder], apply, ♥ viz-playlist, 🗑),
  built-ins filtered to the current scene, [Save current as…] with folder
  choice.

### Library
- Permission gate first ("Allow music access"), then MediaStore-backed tabs.
- Track rows join analysis results (key/BPM) onto device rows; the ✎
  edit-info icon opens the 0.13 tag editor sheet (frame 06).
- Folders tab keeps library-root management ([Add folder] / [Rescan]);
  Google Drive still "coming soon".

### Now Playing
- Deliberately minimal: collapse chip, title/artist, one glass transport
  card (seek · shuffle/prev/play/next/repeat), [Visuals] shortcut and
  [Auto: off/random/smart]. Tap canvas toggles controls.
- 0.13 adds the source-quality badge row (e.g. "FLAC · Lossless ·
  24-bit / 96 kHz") inside the transport card.

### Settings (0.13 restructure)
- Five collapsible sections replace the flat list:
  - Appearance: theme cards · bar opacity · corner style · accent/dim.
  - Playback: speed · pitch · EQ · sleep timer (new).
  - Library: media folders · rescan · preset mirror folder (SAF).
  - Visuals & Analysis: analysis cache view/clear · preset morph beats ·
    beat threshold.
  - Export & About: [Export video…] host dialog · version.

## Style rules used by wireframe.html
Pure grayscale (#fff / #f4f4f4 / #ddd / #999 / #333), 1.5 px outlines,
rounded rects, X-crossed boxes for image/visualizer areas, short ALL-CAPS
labels, a caption under every frame, no drop shadows, no external assets.
