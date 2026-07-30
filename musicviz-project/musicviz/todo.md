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
  ProjectMScene + PMBridge JNI, SceneParams = 117-field data class,
  ParamRandomizer), fluid/ (FluidScene, CurlFlowScene, WaterScene + the
  pure CPU mirrors FluidMath/FluidHue/CurlFlowMath/WaterMath/RippleMath).
  Which family reads which param: docs/PARAM_MATRIX.md.
- export/    VideoExporter + FxCompositor (mirrors live composite; every
  live-path modulator must also run here), AudioTranscoder.
- ui/        PlayerViewModel (~1000 lines — split as it grows),
  VisualizerScreen, VisualsHub (the ONE customization surface; hosts the
  tab composables that live in CustomizeDialog.kt), SettingsDialog,
  PresetStore (JSON), BuiltInPresets, PaletteStore/PaletteMaker,
  TextureStore.
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
- [x] F8 Launch variants: six built-in fluid presets (Inkdrop / Vortex /
      Spectrum / Nebula / Lava / Storm) in the preset browser, each leaning
      on a different subsystem; audited import of externally-built F5-F7.
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

## FLUID-R — Fluid & particle REBUILD (v0.13.0, user-directed)
Rebuilt from scratch around spawn/catch progression ("journey"), replacing
the static-pattern implementation. Research: Pavel/Enhanced sources re-read,
curl-noise + GPU-particle-lifecycle + emitter/attractor patterns surveyed.
- [x] R1 FluidChoreography.kt: spawn (≤8) + catch (≤4) anchors on five path
      families (Orbit/Lissajous/Rose/Bloom-phyllotaxis/Drift); song progress
      slides the journey arc, sections re-seat by golden angle, beats advance
      the bloom floret; rate-limited follow (never teleports). Headless:
      FluidChoreographyTest (continuity/progression/bounds/pack).
- [x] R2 Particle lifecycle: MRT state (pos+vel | age/ttl/emitter/seed),
      births at spawn points, catch capture + ttl recycle at the CURRENT
      choreography, fade-in/out, softened inverse-square attraction with
      soft cap (FluidMath.attractorForce, tested). DPI-compensated sprites.
- [x] R3 Emitters anchored to choreography: orbiting stirrers, anchor-fired
      beat patterns, catch-point suction splats (dye drains into the wells);
      stirrer re-enable kick fixed, splat budget priority-ordered, sparkle
      dt-scaled + wired to fluidSparkle.
- [x] R4 CurlFlow on the same choreography (field + attractors compose);
      linear 96-res field, cached uniforms, wall-clock respawn hash.
- [x] R5 Progress plumbing: AudioFeatures.progress/sectionIndex/sectionCount;
      live enrichment (PlayerViewModel.enrichFeatures via EnginePlumbing),
      export parity via FeatureTimeline.progressionAt (deterministic,
      FluidLifecycleMathTest).
- [x] R6 Customization: Journey section in the Fluid tab (FLUID + CURLFLOW),
      8 new SceneParams fields through PresetStore/param-fade/morphing,
      LFO/ADSR targets Catch pull + Catch radius, randomizer ranges,
      6 fluid presets retuned + "fluid · Journey" + "curlflow · Streams".
- [x] R7 Core sim fixes: pre-resize splat-queue burst, injection error
      masking, LINEAR sampler for dye-advect velocity read.
- [ ] R8 ON-DEVICE: docs/DEVICE_CHECKS.md item 13 (journey progression,
      catch drain, export parity, soft births) — container had no Android
      SDK this round; run the full ./gradlew gate before release.

## ORGANIC MOTION (spec: docs/ORGANIC_MOTION.md, from the research report)
- [x] O1 Feedback echo-trails: renderer trail pass gains zoom + sine-warp
      (trailZoom/trailWarp params, Customize sliders, export parity via
      FxCompositor/VideoExporter) - the MilkDrop "warp shader has a memory"
      liquid-echo feel on EVERY scene.
- [x] O2 Curl-noise flow-field scene ("curlflow"): 64-res divergence-free
      velocity field (curl of time-morphing FBM, Bridson 2007) driving the
      existing GPU particle layer; mids = morph rate, treble = fine
      turbulence, beats = amplitude + brightness impulse; cosine-palette
      coloring; divergence-free property proven headless
      (CurlFieldMathTest against a CPU mirror of the shader).
- [ ] O3 Reaction-diffusion scene (Gray-Scott ping-pong, beat-perturbed
      feed/kill) - ambient "living texture" for slow passages.
- [ ] O4 Physarum slime-mold scene (agents-in-texture, sense/turn/deposit,
      blur+decay trail map).
- [ ] O5 Metaball gel scene (smoothMin SDF blobs, band-driven radii).
- [ ] O6 Raymarched fractal scenes (Mandelbulb/KIFS) - heaviest; gated
      behind the high quality tier, reduced-res render + upscale.
- [ ] O7 Shared audio-mapping layer: extract the bass/mid/treble/beat ->
      spawn/speed/detail/burst conventions into one reusable component.

## P3 — Media architecture (VLC-mirror is DECIDED)
- [x] AIFF playback: custom Media3 AiffExtractor (FORM/COMM/SSND chunks,
      big-endian PCM → 16-bit LE), registered via ExtractorsFactory;
      unit test with a small bundled .aiff.
- [x] Library scanner (mirror VLC): user adds FOLDER PATHS (SAF tree
      picker per root, displayed as a normal file tree). Scanner reads
      audio files recursively in each root, registers them, and queues
      background ANALYSIS. Dedupe: canonical (docId|path)+size — a file
      already registered is never doubled, including Drive downloads.
- [ ] Library sheet → Folders tab: full tree browser of the added roots
      (VLC-style: folders first, audio files with play/add). Tracks tab
      stays flat; path detection auto-suggests common music dirs on
      first run (Music/, Download/, DCIM excluded).
- [x] Analysis database (Room): TrackAnalysis(id = uri+size+mtime hash,
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
- [x] UI opacity slider consumption (glass translucency), theme polish
      (theme overhaul), player panel settings. (0.13.x round)
- [ ] Touch press feedback. Crystal themes/overlays remain DEFERRED until
      the user re-opens them after the overhaul.

## Known gaps — visual/customization audit (v0.14.0)
Re-derived against the merged tree; the full evidence is in
docs/PARAM_MATRIX.md ("Notes" + "Divergences worth knowing"). Everything
listed here is a real gap that was NOT closed in this round, with the reason
it was left and what closing it involves.

- [ ] **Beat sensitivity does not reach the offline analyzer.**
      `OfflineAnalyzer.StreamingPipeline` builds its own `FeatureExtractor`
      (analysis/OfflineAnalyzer.kt:137) and never assigns
      `beatThresholdSigma` / `beatMinIntervalMs`, so every cached beat grid —
      and therefore every export and every section-driven intelligence
      decision — runs at the shipped defaults (2.5σ / 333 ms) no matter where
      the user leaves the sliders. Pre-existing; predates the v0.14 beat
      work. Closing it is NOT a one-liner: the two values have to be
      threaded from GuiPrefs into `OfflineAnalyzer.analyze`, AND
      `AnalysisCache` must stop serving a timeline analysed at other
      settings — its key is a SHA-1 of the URI alone
      (analysis/AnalysisCache.kt:36-40) with no sensitivity in the key or the
      header. Either fold both values into the key or bump `VERSION`, store
      them in the header and reject mismatches. Decide first whether beat
      grids SHOULD follow a live-visuals slider at all, or whether the
      offline detector wants its own (probably stricter) setting.
- [ ] **`pulse` ("Beat pulse") has no reader on MilkDrop or the fluid
      family.** Shader scenes use `uPulse`, particle scenes swell their point
      size; the composite pass declares no beat pulse at all, so the slider
      is inert on MD/FL/CF/WA. Closing it means a new `uPostPulse` uniform
      (a beat-driven zoom/scale nudge inside `geo()`), the matching upload in
      `VisualizerRenderer` and `FxCompositor`, and a `CompositeGrade` mirror
      + headless test — the same shape as the v0.14 grading work. Pick the
      curve so it matches what shader scenes already do at the same value.
- [ ] **`audioDrive` / `beatResponse` have no reader on Fluid, Water (and
      `audioDrive` none on MilkDrop).** Those scenes consume `AudioFeatures`
      straight after `applyBandGains`, so the per-band faders work but the
      two master reactivity sliders do nothing. Curl Flow reads `audioDrive`
      (CurlFlowScene.kt:170) and still ignores `beatResponse`. Do NOT fix by
      folding `audioDrive` into `applyBandGains` — shader and particle scenes
      apply it themselves and would double-apply. The honest fix is to scale
      the emitter/choreography drive terms in FluidScene/WaterScene, which
      needs on-device tuning to avoid blowing the sim out at 2.5x.
- [ ] **Shader-only params are shown on every style.** `morph`,
      `palette2`, `paletteMix` and `duotone` are read only by `ShaderScene`
      (by design — they need the fragment palette/pattern machinery), but
      since the Customize panel moved into `VisualsHub` the Shape and Color
      tabs render unconditionally, so four live sliders do nothing on
      particle, MilkDrop and fluid styles. Same class of bug the Fluid tab
      gating fixed. Closing it: plumb `sceneId` into `ShapeTab`/`ColorTab`,
      add `isShaderSceneId`-style predicates next to the fluid ones in
      VisualsHub.kt:372-400, and pin them the way `FluidTabGatingTest` pins
      the fluid slices. Decide the policy first — hide, or show disabled
      with a "shader styles only" note (the `trails` slider takes the second
      route today, labelled "Trails (particle scenes)").
- [ ] `endlessZoom` is shown on the fluid styles, which have no respawn/
      outflow behaviour to drive. Same fix shape as the item above; low
      priority because the checkbox is cheap and harmless.
- [ ] `hueRange` is clamped to 0.1..1 on the fluid family (`FluidHue.span`)
      but multiplied raw on shader/particle scenes, so the slider's 1.0-1.5
      band is flat on three of six families. Intentional today (a 0 span
      kills the fluid look). If it is ever unified, do it in `FluidHue` so
      all three fluid styles move together.
- [ ] ADSR card labels "Attack"/"Decay" collide with the Behavior tab's
      reactivity envelope sliders of the same name. Harmless right now —
      neither is a `ParamRandomizer` key, so the shared lock chip only
      mirrors a highlight — but it becomes a real collision the moment
      either gets randomized. Rename to "Env attack"/"Env decay" if so.

## Always
- [ ] Update docs (NAVIGATION.md, WIREFRAME.md, PARAM_MATRIX.md) when
      behavior changes; keep README changelog per round.
