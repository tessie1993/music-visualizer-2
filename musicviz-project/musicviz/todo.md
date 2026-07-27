# MusicViz — Claude Cowork TODO (self-contained handoff)

## Project context (read once)
Native Android music visualizer: Kotlin + Jetpack Compose + GL ES 3 +
libprojectM v4.1.7 (JNI, arm64-v8a). Repo root: /home/claude/musicviz
(git). compileSdk 36, JDK 21, Gradle 8.13, currently v0.9.5.
Full plan/risks: docs/PLAN.md · nav: docs/NAVIGATION.md · wireframes:
docs/WIREFRAME.md · features: docs/FEATURES_TODO.md.

Architecture digest:
- analysis/  FftProcessor, FeatureExtractor (beats = mean+2.0σ spectral
  flux, ~250 ms refractory — the flicker fix target), BandSmoother,
  AnalysisEngine → StateFlow<AudioFeatures>, OfflineAnalyzer,
  FeatureTimeline (bpm, sections).
- render/    VisualizerRenderer (supersampled FBO A/B, transition +
  composite pass owning screen FX + particle/milkdrop geo via uPost*
  uniforms), Lfo.kt (LfoEngine: configs / tick() / companion apply() —
  the ADSR engine must mirror this shape), scene/ (ShaderScene ×20 frags
  with shared prelude view()/pal()/grade(), ParticleSceneBase ×5,
  ProjectMScene + PMBridge JNI, SceneParams = 57-field data class).
- export/    VideoExporter + FxCompositor (mirrors live composite; every
  live-path modulator must also run here), AudioTranscoder.
- ui/        PlayerViewModel (~1000 lines — split as it grows),
  VisualizerScreen, CustomizeDialog, SettingsDialog, BrowserDialog,
  MusicLibraryDialog, PresetStore (JSON), BuiltInPresets, TextureStore.
- res/raw/   shader sources; composite_frag.glsl is the FX chain.

## Working rules
- FIRST CHECK, THEN DO: P0 gates everything below it.
- Verify gate after every change set, run split to avoid timeouts:
  1) ./gradlew ktlintFormat   2) ./gradlew ktlintCheck testDebugUnitTest
  3) ./gradlew assembleDebug  4) ./gradlew lint
  with ANDROID_HOME=/home/claude/android-sdk.
- Ship each round: /mnt/user-data/outputs/musicviz-project.zip (exclude
  .gradle/build/app-build/.kotlin/.git/local.properties) + musicviz-
  debug.apk; bump versionCode/versionName.
- Parallel Claude sessions may edit this tree: `git diff` uncommitted
  work you didn't write before committing; audit + integrate, never
  blind-discard, and never delete line ranges without diffing the
  surrounding declarations first.
- GL output can't be verified headless: list what needs on-device
  confirmation (docs/DEVICE_CHECKS.md) in each round summary.
UI OVERHAUL IS GATED: no visual redesign until P0–P4 architecture is done.

## P0 — Verify & stabilize (blocks everything)
- [x] Preset roundtrip test: unit test asserting PresetStore save→load
      preserves ALL SceneParams fields (reflection over the data class so
      new fields can't silently drop). Fix any gap.
- [x] Apply-path verify: applying a preset restores scene + params +
      custom shader + (milkdrop) .milk path. Add log markers; document the
      on-device check for .milk render + texture "Use"
      (`adb logcat -s projectM-jni`) in docs/DEVICE_CHECKS.md.
- [x] docs/PARAM_MATRIX.md: table of every SceneParams field ×
      {shader, particle, milkdrop} with HOW it takes effect (in-shader /
      composite uPost* / scene code). Fix every dead combo. Acceptance:
      no param is a no-op on any family unless documented why.
- [x] Flicker fix (user diagnosis: high tones/high mids): in
      analysis/FeatureExtractor raise beat threshold mean+2.0σ→2.5σ,
      refractory 250→350 ms; add extra smoothing pole on treble/high-mid
      bands in BandSmoother. Settings→Analysis: "Beat threshold" +
      "Reactivity" sliders (persisted GuiPrefs, wired to AnalysisEngine
      live). Acceptance: steady tone produces no beat spam; sliders
      audibly change sensitivity.
- [x] Remove built-in milkdrop presets: delete assets/presets/*.milk,
      remove milkdrop rows from BuiltInPresets, "Next preset" cycles the
      user's imported/saved .milk files only (empty state: hint to Load).

## P1 — Milkdrop tab + preset architecture
- [x] Style sheet → 3 tabs: Particles | Shaders | MilkDrop
      (ui/ style chooser; keep sheet-over-canvas pattern).
- [x] Move [Load .milk] and [Textures…] buttons INTO the MilkDrop tab;
      remove them from their current locations. MilkDrop tab also lists
      user .milk files with [apply], plus [Next preset ▸].
- [x] Remove-preset: [🗑] on user preset rows (confirm dialog); built-ins
      excluded. Deletes JSON (and paired .milk for milkdrop presets).
- [x] Preset TREE: PresetStore folders = subdirectories under the preset
      root. UI: expandable tree, [+ folder], [rename], [move preset],
      Save-current targets the selected folder. Migration: existing flat
      presets → "Unsorted/". Milkdrop saves still write .milk alongside.
- [x] Settings→Paths: preset root location (SAF picker; default stays
      internal; store treeUri, copy-on-change optional).

## P2 — Modulation & FX (ADSR spec is DECIDED)
- [x] Per-param [lock 🔒] in Customize; [⚄ Randomize] randomizes UNLOCKED
      params only, within sane per-param ranges (define ranges in
      SceneParams companion). Locks persist per scene.
- [x] render/Envelope.kt — 2× ADSR engine. DECIDED behavior:
      * Attack triggers on BEAT (detected beat fires attack→decay).
      * Sustain is driven by band ENERGY: while selected band (bass/mid/
        treble/rms) ≥ threshold, hold sustain; sustain level can track
        energy (mode: hold | track). Release starts when energy drops
        below threshold (small hysteresis).
      * Everything ADJUSTABLE: A/D/S/R times, band, threshold, retrigger
        on/off, per-target amount (±).
      * MULTI-TARGET: each ADSR maps to many targets; a target is a
        SceneParams param OR an LFO property (depth default, rate
        selectable).
      Mirror LfoEngine's shape (configs, tick(), companion apply());
      persist in a store like LfoStore; tick in renderer AND export loop.
- [x] Customize "Mod" tab: LFO 1-3 + ADSR 1-2 editors, target pickers
      with [+ add target], amount sliders, live value meters.
- [x] Synth-inspired FX (new SceneParams + composite/prelude support):
      filter sweep (LP cutoff sweep on beat), sidechain pump (brightness
      duck on bass hits), S&H stutter (frame-hold on treble spikes),
      saw/tri LFO shapes if missing. More Behavior tab params.

## FLUID — Fluid dynamics scene (spec: FLUID_SIM_2.md v2 — supersedes v1)
Working rules from the spec apply: clean-room for GPL-source ideas (never open
that repo), MIT headers on ported shaders, THIRD_PARTY_NOTICES maintained,
verify gate + on-device checklist every phase.
- [x] F0 Foundations: FluidBuffers (half-float renderability probe with
      R16F→RG16F→RGBA16F cascade + log line, Fbo/DoubleFbo, aspect-correct
      resolution helper); 11 shaders ported/written to 300 es (sim-space
      coords, boundary sampling in every pass, alpha=-dx^2 Jacobi, manual
      bilerp advection with half-texel centers, capsule splat with velocity
      blending); THIRD_PARTY_NOTICES; FluidMathTest (resolution mapping,
      CPU 8x8 Jacobi >90% divergence reduction, segDist degenerate guard).
- [x] F1 Core sim: FluidSim.step() in v2 pass order (advect vel → forces →
      curl → vorticity → project → dye), pressure damped never cleared,
      dt clamp 1/60, blend off during sim; FluidScene registered behind
      SceneIds.FLUID with 2 debug band-driven stirrers; P1 display = dye
      copy with soft HDR rolloff; Styles → Fluid tab entry; export factory
      branch. ON-DEVICE (run before F2): format-probe logcat line; 5-min
      soak (no NaN white/black-out); rotation preserves ink; pause freezes
      sim; repeated style-switching leaks no GL objects.
- [x] F2 Emitters: audio emitter system (BeatSplat center/ring/random/
      spectrumArc, BandStirrer ×0-4, TrebleSparkle, BassPump), continuous
      modulation (curl←mid, bloom←energy, fade←quiet, radius←beatAdsr),
      per-channel dye decay (chromatic aging, opt-in zero = pure fade),
      palette + key-hue color (hueOffset = keyIndex/12).
- [x] F3 Particle layer: state-in-texture (ceil(sqrt N)^2 RGBA16F ping-pong),
      one-quad update kernel, static texel-coord VBO → GL_POINTS, drag
      inertia v += (flow−v)·drag (default 0.4-0.6), speed coloring, respawn.
- [x] F4 Look chain: soft-knee bloom (knee=T·K+1e-4) with mip up/down
      ONE,ONE accumulation, 16-step sunrays march to (0.5,0.5) in scene-FBO
      UV space, pseudo-normal shading (normal z = display texelSize length),
      procedural noise dither (generated at init; upstream blue-noise PNG
      not bundled), keyword display variants; display blend
      ONE,ONE_MINUS_SRC_ALPHA; labeled "Glow (fluid)" vs composite bloom.
- [x] F5 Customize → Fluid tab: SceneParams fluid*/flow* fields (spec §11.1;
      reflection roundtrip auto-covers), quality chips (re-init on change),
      locks/randomize, LFO/ADSR routing targets: fluidCurl, fluidSplatRadius,
      fluidSplatForce, fluidBloomIntensity, fluidDensityDissipation,
      flowStrength.
- [x] F6 Adaptive quality: rolling-FPS monitor (Welford, 2.5 s hysteresis,
      stall readings discarded, downgrade-only latch) driving one tier enum
      over sim res / dye res / particle count / iterations; reallocation
      only at frame boundary with copy-preserving grids.
- [x] F7 FlowField + extension points: 64-grid velocity-only service (reuse
      FLUID's own field when that scene is active — never both), fluidWarp
      composite slot (uv -= flow·k·strength, 1×1 zero texture when off,
      export parity via FxCompositor + export-context FlowField),
      particle-scene advection (16×16 CPU readback), uFlow sampler for
      shader scenes + GLSL tab doc, user force/dye injection shader editors
      (last-good fallback). ON-DEVICE (F4-F7): each look toggle recompiles
      correctly; banding gone with dither; quality chips re-init cleanly
      mid-playback; forced Low tier holds; single stall never downgrades;
      fluidWarp visibly bends a MilkDrop preset AND a particle scene incl.
      a 10 s export; broken injection shader keeps last good program.

## P3 — Media architecture (VLC-mirror is DECIDED)
- [x] AIFF playback: custom Media3 AiffExtractor (FORM/COMM/SSND chunks,
      big-endian PCM → 16-bit LE), registered via ExtractorsFactory;
      unit test with a small bundled .aiff.
- [ ] Library scanner (mirror VLC): user adds FOLDER PATHS (SAF tree
      picker per root, displayed as a normal file tree). Scanner reads
      audio files recursively in each root, registers them, and queues
      background ANALYSIS. Dedupe: canonical (docId|path)+size — a file
      already registered is never doubled, including Drive downloads.
- [ ] Library sheet → Folders tab: full tree browser of the added roots
      (VLC-style: folders first, audio files with play/add). Tracks tab
      stays flat; path detection auto-suggests common music dirs on
      first run (Music/, Download/, DCIM excluded).
- [ ] Analysis database (Room): TrackAnalysis(id = uri+size+mtime hash,
      bpm, key, sections, energy curve blob, analyzedAt). OfflineAnalyzer
      writes it; player + export read it (skip re-analysis). Settings→
      Analysis: DB size + [clear]. Analyzed badge (key·BPM) on track rows.
- [x] Key detection: chromagram (12-bin fold of FFT magnitudes) +
      Krumhansl-Schmuckler correlation → key + major/minor; stored in DB;
      shown in badge. Rekordbox is the reference for the analysis
      feature-set (key, BPM, beat grid, energy) — not its UI.

## P4 — Intelligence & playlists
- [x] Intelligent preset switch: third [⚄] mode. Uses FeatureTimeline
      detectSections + energy: on section boundary pick a preset from an
      energy-matched pool (quiet→Chill-like, peak→Strobe-like); never
      repeats last N.
- [x] Preset MORPHING: applying a preset lerps SceneParams current→target
      over N beats (default 4, setting) using the paramFade infra;
      scene/shader switches still use existing transitions.
- [x] Playlists: rename + reorder via up/down buttons (drag deferred; visual playlist
      gets reorder too).

## P5 — Drive & styles (Drive DECIDED: one folder, last)
- [ ] Google Drive: Sign-In + Drive REST, user picks ONE folder; list
      audio inside; per file [download to device] (into a library root,
      dedupe applies) or [stream] (auth'd ExoPlayer data source).
- [ ] Style dedupe + new milkdrop-inspired scenes (research done, see
      docs/PLAN.md "MilkDrop-inspired scenes"):
      * REMOVE: grid (hexgrid covers it), ripples (waves covers it),
        scope (ring + liss cover the oscilloscope family).
      * ENABLER: feedback texture for shader scenes — ShaderScene gains
        uPrevFrame (previous frame of its own FBO, ping-pong) so warp
        effects persist frame-to-frame like MilkDrop's feedback buffer.
      * ADD "flow": Flexi-style fluid ink — feedback displaced along
        color-gradient curl, waveform injected as ink strokes.
      * ADD "echotunnel": Rovastar-style zoom+rotate feedback (video-echo)
        with a radial waveform ring stamped each frame.
      * ADD "smear": Geiss-style motion-blur feedback — parametric warp
        (zoom/rot/dx/dy driven by bass/mid) over decaying trails with hue
        rotation.
      All three must honor the full customization set (they are
      ShaderScenes, so the prelude applies) + presets + export.

## P6 — UI overhaul (GATED: only after P0–P4 are done)
- [ ] Player panel restructure: canvas bottom bar deleted; icons become
      panel row 4; tap hides whole panel; top/bottom setting moves it as
      one unit. (Structural nav — do first within P6.)
- [ ] UI opacity slider; touch press feedback; theme polish; player
      panel size setting. Crystal themes/overlays remain DEFERRED until
      the user re-opens them after the overhaul.

## Always
- [ ] Update docs (NAVIGATION.md, WIREFRAME.md, PARAM_MATRIX.md) when
      behavior changes; keep README changelog per round.
