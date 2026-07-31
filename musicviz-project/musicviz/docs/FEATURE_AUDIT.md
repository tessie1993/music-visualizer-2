# Feature audit — the "offline music player + visual studio" brief vs the code

Written against the tree at v1.1.0 (versionCode 25) by inventorying
`app/src/main` directly. Docs were deliberately ignored while doing it, so
everything below is what the source actually does. 92 Kotlin files, ~22,200
lines in `main`.

## The headline

The framing in the brief — "offline music player + visual studio" — is half
true today. **The visual studio is close to complete. The music player is
missing its foundation.**

The visualizer side is genuinely deep: 20 shader scenes, 5 particle scenes, 3
fluid scenes, MilkDrop via libprojectM, a ~110-field parameter system that
round-trips through presets, LFO + ADSR modulation, palette authoring, a live
GLSL editor, three separate auto-switching mechanisms, deterministic offline
export at six aspect ratios. Most of the brief's "Visualizer experience" and
"Creator and export tools" sections are asking for a *second* layer on top of
something that already exists.

The player side is a `ViewModel` wrapping ExoPlayer. `AndroidManifest.xml`
declares exactly one component — `MainActivity`. There is no `<service>`, no
`MediaSession`, no notification, no media-button handling. **Playback stops
when the activity goes away.**

That single gap is the root of at least eight separate items on the brief's
list: background playback, notification controls, lock-screen controls,
headset buttons, Bluetooth/AVRCP, Android Auto, background export with a
completion notification, and "keep playing during navigation prompts". They
are not eight features. They are one piece of architecture (a foreground
`MediaSessionService` on `media3-session`, which the project does not yet
depend on) plus thin wiring.

For a Play Store music player this is also the thing reviewers and users test
in the first thirty seconds. It should be built before anything else on the
list, and the v1 bundle in the brief is right to put it at #1.

## What exists, honestly

Strong and shippable:

- **Playback engine** — Media3/ExoPlayer with a PCM tap for analysis, plus a
  hand-written AIFF extractor. Speed, pitch, skip-silence, sleep timer with a
  fade, 5-band EQ + bass boost + loudness, audio-quality readout. Audio focus
  is handled (delegated to ExoPlayer).
- **Library** — MediaStore scan plus user-added SAF folder roots with
  recursive scanning, dedupe on insert, rescan, background analysis into a
  Room database (BPM, musical key via chromagram + Krumhansl, sections,
  energy curve), a metadata editor, search across title/artist/album/folder/
  genre, and play history.
- **Visualizer** — see above. This is the mature part of the codebase.
- **Export** — deterministic offline render from a precomputed timeline,
  H.264 + MediaMuxer, 6 ratios, 720p/1080p/4K, 30/60 fps with automatic
  fallback, SAF or MediaStore output, system share sheet.

## What the brief asks for that is genuinely absent

Ordered by how much it costs to close, cheapest first within each block.

**Blocking, architectural (do first):**
- Foreground `MediaSessionService` + notification + lock-screen + media
  buttons + Bluetooth + Android Auto. One unit of work; unlocks eight list
  items. Needs `media3-session`, `FOREGROUND_SERVICE_MEDIA_PLAYBACK`,
  `POST_NOTIFICATIONS`, `WAKE_LOCK` — none currently declared.

**Cheap and high-value (do next):**
- **Photosensitivity / "Safe visuals".** There is *nothing* here today, and
  the app ships `strobe`, `flash`, `glitch` and full-screen invert as creative
  parameters. For a strobing visualizer this is both an accessibility duty and
  a Play Store content concern, and it is a clamp on a handful of existing
  params plus a settings toggle — not new rendering. This is the most
  underrated item on the brief's list.
- **Queue editing.** `playNext`/`enqueue` exist; reorder and remove do not
  (no `moveMediaItem`/`removeMediaItem` call anywhere). Worth noting:
  `LibraryScreen.kt:282` already tells the user to "build a queue in Now
  Playing and save it" and **that save path does not exist**. That is a
  visible broken promise, and the cheapest credibility fix in the app.
- **Favorites.** Nothing exists — the only `Favorite` reference is a heart
  icon reused for "add to visual playlist". A boolean on tracks and presets
  plus a filter is the whole feature, and it is the precondition for the
  brief's smart playlists.
- **Sort and filter.** Sorting is hardcoded (`TITLE COLLATE NOCASE ASC`).
  The metadata to sort by is *already stored* per track — `bpm`, `key`,
  `year`, `genre`, `album`, `durationMs`. This is UI over existing data.
- **Smart playlists.** Recently added / most played / never played come free
  from `HistoryStore`; high-energy / chill / by-BPM / by-key come free from
  the analysis DB. Again: the data is there, the surface is not.
- **Backup / restore.** Only Android's OS-level auto-backup is configured.
  An explicit export-to-ZIP of presets, playlists, palettes and settings is
  a file walk, and it matters because users will have hand-built presets.

**Moderate:**
- Gapless, crossfade, ReplayGain/normalization, mono, balance.
- Album art (nothing loads embedded artwork at all today), lyrics, bookmarks.
- Per-track / per-album preset memory. The preset system and the analysis DB
  both exist; this is a join between them and is the single highest-leverage
  *visualizer* item on the brief, because it makes the whole customization
  system feel like it remembers you.
- Export: in/out trimming, fades, still-image export, export history,
  batch export. All sit on a working exporter.
- Global performance profiles. Today only the fluid family has quality tiers
  and FPS-based auto-downgrade; shader/particle/MilkDrop scenes have none, and
  there is no user-facing max-FPS or resolution scale for live rendering.
- Onboarding. There is no first-run flow, no tutorial, no demo track, no
  "what's new", and the permission rationale is a single static line with no
  denial-recovery path.

**Expensive / defer, and the brief already says so:**
- Google Drive, Chromecast, community preset sharing, microphone mode,
  AI-generated visuals. Agreed — permissions, infrastructure and moderation
  burden out of proportion to a first release.

## Where I would push back on the brief

1. **"Auto Visual DJ that changes scenes at musical sections" already
   exists.** `IntelligenceMode` + `SceneSuggester` + `FeatureTimeline.
   detectSections` do section-boundary, energy-matched preset switching
   today, and preset morphing over N beats is implemented. The item to write
   is not "build it" but "verify it on real music and tune it".

2. **Several "visual" asks are cheaper as library asks.** Preset tags,
   duplicate-preset, favorites-only random and preset import/export are all
   small additions to `PresetStore`, which already has folders, JSON
   round-tripping and a mirror-to-SAF path. Grouping them with the heavier
   creative features undersells how close they are.

3. **The v1 bundle is well chosen, with one reorder.** Move visual safety
   controls up next to background playback. Everything else in that list
   depends on work that is either already done or genuinely large;
   safety is neither, and shipping a strobing visualizer without it is the
   one item that can cause a problem you cannot fix with a patch release.

4. **Undo/redo for customization is worth more than it looks.** With ~110
   parameters, a randomizer and a live GLSL editor, the current recovery
   story is per-parameter locks and a "Revert" button that exists only in the
   shader editor. The brief lists undo/redo and "one-tap reset" separately
   and late; in practice they are what make the deep customization safe to
   explore.

## Cross-reference

`docs/FEATURES_TODO.md` and `todo.md` track the *previous* brief and are
largely complete against it. This document covers the newer, broader brief and
does not supersede them; where the two overlap (intelligent preset switch,
morphing, playlists, library scanning) the older files are the record of what
was built and why.
