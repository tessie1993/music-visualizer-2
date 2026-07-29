# MusicViz — Feature To-Do (from user brief, 2026-07-23 reroll)

Priority per the brief: FIRST CHECK, THEN DO → customizations + milkdrop
first → everything else. GUI polish is a later pass.

## P0 — Verify & stabilize (before any new feature)
- [ ] Verify loading really works end-to-end: presets (scene + all params +
      shader restored), .milk presets (renders on device), custom shaders,
      texture import → visible in milkdrop.
- [ ] Verify EVERY customization has a visible effect on all three scene
      families — shader, particle, milkdrop (.milk) — and fix dead combos.
      Deliverable: docs/PARAM_MATRIX.md (param × family checklist).
- [ ] Flicker fix (user: high tone detection too high / high mids): raise
      beat threshold, longer refractory, stronger high-mid/treble smoothing.
      Expose "Beat threshold" + "Reactivity" sliders in Settings → Analysis.
- [ ] Remove existing (built-in) milkdrop presets: bundled .milk files and
      milkdrop rows in the built-in preset list. User imports stay.

## P1 — Milkdrop tab & preset UX
- [ ] Style sheet: separate MilkDrop tab (Particles | Shaders | MilkDrop).
- [ ] Move [Load .milk] and [Textures…] buttons into the MilkDrop tab.
- [ ] Remove-preset button (user presets only).
- [ ] Preset library as browser TREE: custom folders, add folder, rename,
      move preset; save-into-folder. Milkdrop saves write .milk too.
- [ ] Preset folder path option in Settings.

## P2 — Player panel & UI
- [ ] Canvas bottom bar REMOVED; its icons become row 4 of the player panel
      (Style · Customize · Presets · Library · Random). Panel hides on tap,
      honors top/bottom position setting. (DECIDED in-thread.)
- [x] UI opacity setting slider (glass panels/sheets). (0.13.x round:
      consumed by the glass translucency unit)
- [ ] Touch animation feedback (press glow/ripple).
- [x] Media player preferences: theme, panel placement and size. (0.13.x
      round: settings restructure + theme overhaul + playback settings)
- [ ] Crystal themes/colors + full-screen crystal overlay images with
      opacity: OPEN QUESTION — answered "ignore for now" in-thread, but the
      re-sent brief still lists it. Awaiting confirmation; only the opacity
      slider is in scope until then.

## P3 — Modulation & FX
- [ ] Randomizer of customizations + per-param LOCK to skip on randomize.
- [ ] 2× ADSR envelopes, each with MULTIPLE assignable targets: params or
      LFOs (DECIDED in-thread). Trigger source + LFO-target property: open
      question (defaults: per-ADSR beat/band-gate selector; LFO depth).
- [ ] More behavior settings.
- [ ] More music-inspired FX borrowing from synths (saw/tri shapes, filter
      sweeps, sample&hold, sidechain-duck-style pump, arpeggiated strobe).

## P4 — Media & analysis
- [ ] AIFF codec support (custom Media3 extractor; AIFF = big-endian PCM).
- [ ] Device media browser (folder tree), media library path detection,
      import path-dedupe so nothing is doubled.
- [ ] Save analysis data separately in a database (per-track timeline,
      BPM, key); analyzed badge in library.
- [ ] Analyze settings section; musical KEY detection (chromagram).
      Rekordbox as inspiration for the analysis feature set.
- [ ] Musically intelligent preset switch (section + energy driven).
- [ ] Morphing PRESETS (smooth param interpolation between presets —
      distinct from existing scene transitions).
- [ ] Playlist name (rename) and order (drag-reorder).

## P5 — Cloud & style cleanup
- [ ] Google Drive integration: add from Drive — download to device OR
      stream. Path-dedupe applies.
- [ ] Remove duplicate visual styles (proposal for approval: grid≈hexgrid,
      ripples≈waves, ring≈scope overlap) and add NEW styles inspired by
      milkdrop aesthetics.

## Answers (final)
1. UI overhaul (incl. crystal): DEFERRED until architecture is done.
2. ADSR: beat → attack, band energy → sustain (release when energy
   drops); everything adjustable; LFO target = depth default.
3. Browser: mirror VLC — full file browser, add-folder = real path,
   scan + analyze contents, dedupe imports.
4. Drive: one folder, scheduled last.
5. New styles: creative, milkdrop-research-grounded (flow / echotunnel /
   smear on a new feedback-texture enabler); dedupe grid, ripples, scope.
