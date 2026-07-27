# MusicViz bug-fix patch set (2026-07-21)

Fixes 1-2 (preset params saved + full SceneParams serialization) were already
present in this source tree. Applied in this pass:

3.  Presets now persist the edited custom shader
    - VisualizerRenderer: new `customShaderFor(sceneId)`; shader map is now a
      ConcurrentHashMap (written on UI thread, read on GL thread)
    - VisualizerScreen: PresetSaveDialog passes the active custom shader
4.  Offline analysis timing drift (~4% -> exports came out short, audio cut)
    - OfflineAnalyzer: frame timestamps derived from the absolute sample
      position instead of truncated `1000/60 = 16 ms` steps
    - FeatureTimeline.featuresAt: indexes by the frames' real spacing
5.  Crash in AUTO intelligence mode: `applyIntelligence()` (touches ExoPlayer)
    is now dispatched to Main after background analysis
6.  Long-export OOM: AudioTranscoder streams AAC to a cache temp file instead
    of a ByteArrayOutputStream; VideoExporter reads samples from the file and
    deletes it in its finally block (`Result.release()`); sample offsets Long
7.  MainActivity: `takePersistableUriPermission` wrapped in runCatching
    (some providers throw SecurityException)
8.  Cancelled exports delete the pending MediaStore entry instead of
    publishing a truncated, audio-less MP4
9.  BPM autocorrelation normalized per-lag (was biased toward doubled BPM)
10. Live FeatureExtractor hop rate = 1000/16 Hz to match the delay(16) loop
11. projectM PCM cursor advances to the copied window's exact end
    (PcmRingBuffer.lastCopyEndIndex) - no more skipped samples
12. Scene switch clears FBO A so the old scene doesn't ghost under trails
13. OfflineAnalyzer + AudioTranscoder detect KEY_PCM_ENCODING and handle
    float-PCM decoder output (conversion / feedFloat path)
14. analyzePlaylist advances progress even when a track fails to decode

Known remaining (low priority, by design or edge case):
- Playback has no foreground service; long background playback may be killed
- AudioTranscoder assumes decoder output channel count == source (rare HE-AAC
  parametric-stereo mismatch would skew timestamps)
- Indentation inside AudioTranscoder's try block may differ from ktlint style;
  run `./gradlew ktlintFormat` once

# Feature update (same date)

- Themes: +6 (Ocean, Violet, Ember, Candy, Slate, Paper) -> 12 total
- Scenes: +7 - shader: starfield, waves, hexgrid, spiral, aurora, solar;
  particle: orbits (band-driven orbital rings). All support the full
  Customize panel, transitions, presets, export and Random mode.
- Built-in presets: 4 curated Customize bundles (Chill / Punchy / Hypno /
  Vivid) x every scene (~100 presets), shown beside saved presets in the
  Styles browser, usable in the visual playlist and Random mode.
  Built-ins can't be deleted; names use "scene · Look".
- Random mode: QuickBar toggle + full settings in Styles browser -
  interval (5-300 s), switch-on-musical-moments, source filters
  (Styles / Presets / MilkDrop), shuffle-colors-per-switch, "Next random
  now". Mutually exclusive with the visual playlist.
- More options: +8 palettes (18 total), +2 particle shapes (Hex, Bubble),
  more symmetry folds (5,7,9,16), +2 transitions (Slide, Zoom), export
  frame-rate choice (30/60 fps), wider Customize slider ranges.
