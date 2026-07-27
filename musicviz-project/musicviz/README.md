## v0.11.1 - launch crash fixed, app-wide automated test pass
- FIXED the immediate crash on launch. Root cause: the ViewModel's main loop
  starts in an init block on Dispatchers.Main.immediate, which executes
  synchronously during construction until its first delay - and the v0.11
  preset-lock guard inside applyIntelligence() read a field declared ~400
  lines BELOW that init block, so on a real device it was still null at
  launch (NullPointerException). The construction-order-critical fields now
  live above the init block, with a comment explaining why they must stay
  there. (Robolectric's deferred looper hid this, which is why it only
  crashed on-device.)
- New on-JVM app test suite (Robolectric + Compose UI tests) that actually
  launches MainActivity and walks the app: every bottom-nav destination,
  every Visuals tab including MilkDrop, the Customize hub (sliders +
  Randomize), Library's permission screen, Settings. Plus behavior tests:
  randomizer respects locks, lock toggling, Auto tri-state wiring
  (off/random/intelligent), preset-lock blocking random switching, preset
  save/delete + folder placement + name sanitizing, play-history ordering.
  29 tests, all green.
- Crash capture: any future uncaught exception writes its full stack trace
  to a file; on next launch the app shows a "Previous crash captured"
  dialog with a Copy button so the exact trace can be reported.
- Restored the Now Playing gestures and collapse button (lost in a
  concurrent edit): swipe down = collapse, swipe up = queue, swipe L/R =
  prev/next preset, double-tap edges = seek, plus seekBy/quick-preset APIs.

## v0.11.0 - navigation v2: full app-shell restructure
The app now follows docs/NAVIGATION.md (v2) - two layers like a proper music
player:
- App shell: bottom nav (Home / Library / Visuals / Settings) with a
  persistent mini-player docked above it; tap to expand into the fullscreen
  visualizer (Now Playing), collapse arrow or swipe down to return.
- Home: resume card, Recently played / Most played rows (new play-history
  store), shuffle-all.
- Library: the whole device via MediaStore - Tracks, Albums, Artists,
  Playlists, and device Folders tabs, with runtime permission, drill-in
  play/shuffle, and per-track menu (play next / add to queue / add to list).
  Google Drive appears in Folders as coming-soon.
- Visuals hub: Presets (folder tree with add-folder, save-into-folder,
  remove), Styles (Particles / Shaders / MilkDrop - Load .milk and Textures
  live in the MilkDrop tab), Customize (same tabs as the sheet, with
  per-param locks and Randomize), Textures.
- Now Playing gestures: swipe down = collapse, swipe up = queue, swipe
  left/right = prev/next preset, double-tap screen edges = seek +/-10 s.
  New chips: Lock (keep current preset) and Auto (off / random /
  intelligent tri-state).
- Search overlay from Home/Library: tracks + visual presets.
- Settings destination: theme, bar opacity, player position, beat-threshold
  slider, export dialog.
Built on v0.10.0's flicker fix, MilkDrop tab groundwork, preset remove,
persisted param locks + randomizer, and beat-triggered ADSR envelopes
(live + export).

## v0.10.0 - feature round 1 from the to-do list
- Flicker fixed: beat detection now needs a stronger onset (2.5 sigma, longer
  refractory) and treble gets dedicated smoothing so hi-hats stop strobing the
  visuals; new Settings > Analysis "Beat threshold" slider to tune it.
- MilkDrop overhaul: Styles is now tabbed (Particles / Shaders / MilkDrop);
  Load .milk and Textures moved into the MilkDrop tab; the low-quality bundled
  .milk presets were removed (old copies are cleaned up on first run) - the
  tab lists your own imported/saved .milk files.
- Preset remove button for user presets in the browser.
- Randomizer: one tap randomizes every unlocked Customize parameter; each
  slider has a "lock" toggle to pin values you want kept.
- ADSR envelope: beat-triggered attack/decay/sustain/release assignable to any
  LFO target (Customize > FX > Envelope), persisted, and reproduced in exports.
- Planning docs added under docs/: NAVIGATION.md, WIREFRAME.md,
  FEATURES_TODO.md.

# MusicViz — Android Music Visualizer (MVP)

Native Android app: pick a local audio file, it plays with a real-time
GL ES 3.0 particle visualization driven by FFT data. See PLAN.md for the
full roadmap (analysis/intelligence, shader scenes, projectM, export).

## Build
- `./gradlew assembleDebug` — build APK
- `./gradlew testDebugUnitTest` — unit tests (FFT, smoothing, ring buffer)
- `./gradlew lint ktlintCheck` — lint
- `./gradlew installDebug` — install on a connected device

Requires JDK 17+, Android SDK 36. `local.properties` must point at your SDK.

## Architecture (one-way: ui -> audio/analysis/render)
- `audio/` — ExoPlayer wiring: `TapRenderersFactory` overrides `buildAudioSink`
  to insert a `TeeAudioProcessor`; `PcmTapSink` copies PCM (16-bit or float,
  any channel count) off the playback thread into `PcmRingBuffer` (lock-free,
  mono-downmixed). No RECORD_AUDIO permission, no `android.media.audiofx.Visualizer`.
- `analysis/` — `AnalysisEngine` worker on Dispatchers.Default: Hann window ->
  2048-pt real FFT (JTransforms) -> 64 log-spaced bands -> dB normalize ->
  asymmetric attack/decay smoothing -> `StateFlow<FloatArray>`.
- `render/` — `VisualizerRenderer` (GL ES 3.0): 2,500 point-sprite particles,
  one draw call, additive glow blend. All GL resources recreated in
  `onSurfaceCreated`; bands handed over via a volatile snapshot (no locks on
  the GL thread). Shaders live in `res/raw/*.glsl`.
- `ui/` — Compose: SAF picker (`ACTION_OPEN_DOCUMENT` + persisted URI
  permission), play/pause/seek overlay, `LifecycleResumeEffect` forwards
  onResume/onPause to the GLSurfaceView.

## Status
Phases MVP + A + B + C + D implemented and verified: build, 16/16 unit
tests, lint + ktlint all green.

- Phase A: OfflineAnalyzer (MediaExtractor/MediaCodec decode), FeatureExtractor
  (spectral flux onsets, beat pulses, autocorrelation BPM, centroid),
  FeatureTimeline with novelty-based section detection, SceneSuggester with
  three intelligence modes (manual / suggest / auto), reactivity sliders
  (attack/decay) in the UI.
- Phase B: Scene interface; NebulaScene + BurstScene (particles), Julia +
  Tunnel ShaderScenes (Shadertoy-style: uTime/uResolution/uBass/uMid/uTreble/
  uEnergy/uBeat + 64x2 uAudioTex bands/waveform texture); in-app GLSL editor
  with runtime compile, inline errors and last-working fallback; JSON presets.
- Phase C: deterministic offline export - scene re-rendered frame-exact at
  60 fps from the feature timeline into an H.264 encoder surface (EGL +
  eglPresentationTimeANDROID), original audio track muxed alongside, 1080p
  16:9 or 9:16, saved via MediaStore, cancellable with progress.
- Phase D: multi-file queue (OpenMultipleDocuments) with prev/next, audio
  focus handling, ambient mode (tap canvas to hide controls), keep-screen-on.

## MilkDrop / projectM backend
libprojectM 4 is integrated as a fifth scene ("milkdrop"), built for
arm64-v8a with the Android NDK (GLES3). The scene renders projectM's idle
preset by default; tap "Load .milk" to import any MilkDrop/BeatDrop preset
file via SAF. Live PCM is fed from the existing TeeAudioProcessor ring
buffer. libprojectM is LGPL-2.1 and dynamically linked (lib/arm64-v8a/
libprojectM-4.so); a thin JNI wrapper (libprojectmjni.so) exposes create/
resize/addPCM/render/loadPreset/beatSensitivity. Rebuild instructions: see
tools/build-projectm.md. Note: the APK ships arm64-v8a only.

## Scenes (v0.3)
18 scenes total. Particles: nebula, bursts, swarm (firefly attractor),
fountain. Fragment shaders (all share one prelude with the audio texture +
param uniforms): julia, tunnel, mandel (pairs with Endless zoom), kaleido,
plasma, bars, ring (circular spectrum), scope (oscilloscope), liss
(vectorscope), warp (starfield), grid (synthwave horizon), voronoi,
metaballs, ripples. Plus milkdrop (projectM, arm64).

## Customize panel (v0.3)
Tabbed: Motion (speed, zoom, rotation, endless zoom + dive speed),
Behavior (audio drive, beat response, turbulence, density, mirror,
trails + trail length), Color (5 palettes, hue shift/range, saturation,
brightness, intensity, color cycle + speed, invert), and GLSL (raw editor,
shader scenes only). All params apply to every scene type uniformly, are
saved in presets, and carry into exports. Trails are implemented as a
fade-quad instead of a clear (particle scenes).

## Customization (v0.2)
Every scene has a "Customize" panel with live sliders: Speed, Zoom, Color,
Intensity. They apply uniformly - particle motion/size/hue, shader uniforms
(uSpeed/uZoom/uColorShift/uIntensity), and projectM beat sensitivity - and
are saved in presets. Shader scenes additionally expose an "Advanced" tab
containing the raw GLSL editor (compile-on-apply, inline errors, safe
fallback), following the ISF pattern of sliders-first, code-optional.

## v0.3.4
- Freeze after loading presets, mitigations: removed
  preserveEGLContextOnPause (foreign edit; preserved-context resume after
  the file picker is a known device-specific GL hang source - our
  context-loss discipline with automatic preset restore covers resume);
  projectm_set_texture_search_paths now only fires when the preset's
  directory changes (each call purges projectM's texture cache and repeated
  purges caused stalls); full GL state restore after projectM renders.
- Customize panel restructured: Style is its own tab with a vertical
  scrolling scene list (suggested scene marked with a star); Behavior tab now
  contains Attack/Decay reactivity sliders and the manual/suggest/auto
  intelligence mode buttons; main overlay slimmed to a quick bar
  (Customize, milkdrop preset buttons, saved presets) + transport.

## v0.9.6 - P0 verification + P1 milkdrop tab & preset architecture
- P0: reflection roundtrip test guards all SceneParams fields through preset
  JSON forever (any dropped field fails CI); param x scene-family matrix
  documented (docs/PARAM_MATRIX.md) and its dead combos fixed - Pulse now
  works on particle scenes (beat size swell) and Endless Zoom works on all
  five particle scenes (Swarm/Fountain outward flow, Orbit radius drift).
  Flicker fix confirmed in: beat threshold 2.5 sigma (user slider in
  Settings > Analysis), longer refractory, extra treble smoothing.
  Built-in milkdrop presets removed; "Preset >" cycles your own .milk files.
  On-device checklist: docs/DEVICE_CHECKS.md.
- P1: Style browser has Particles | Shaders | MilkDrop tabs with Load .milk
  and Textures moved into the MilkDrop tab; user presets are deletable;
  preset folders (add/rename/move) form a browsable tree; NEW Settings >
  Paths lets you choose a preset folder - every save mirrors the JSON and
  paired .milk there so your own sorting shows in any file manager.

## v0.9.6 - P0 verification, P1 preset architecture, P2 modulation
Working through todo.md (P0-P2), merged with a parallel session's drops:

P0 Verify & stabilize:
- New reflection roundtrip test: every SceneParams field is perturbed,
  serialized, parsed, and compared - presets can never silently drop a
  parameter again (serializers moved to an internal companion for
  testability; 30 tests green).
- docs/PARAM_MATRIX.md: all 49 params traced across shader / particle /
  milkdrop with their mechanism; dead combos fixed - Pulse now works on
  particles (beat size swell) and Endless Zoom works on all five particle
  scenes (outward flow; Orbit drifts radii with respawn). Remaining
  exceptions documented with reasons. docs/DEVICE_CHECKS.md lists the
  on-device checks that can't be verified headless.
- Flicker fix confirmed in: beat threshold 2.5 sigma (user slider in
  Settings > Analysis), ~333 ms refractory, extra treble smoothing.
- Built-in milkdrop presets removed; "Preset >" cycles your own files.

P1 Milkdrop tab & preset architecture:
- Style sheet now tabs Particles | Shaders | MilkDrop, with Load .milk and
  Textures moved into the MilkDrop tab; delete button on user presets;
  preset folder tree (add / rename / move).
- NEW Settings > Paths: choose a preset folder (system picker) - every
  save mirrors the JSON and paired .milk there so your own sorting is
  visible in any file manager.

P2 Modulation:
- 2x ADSR envelopes exactly per spec: BEAT triggers attack (optional
  retrigger), band ENERGY holds sustain (with hysteresis; sustain can
  track energy), release when energy drops. Multi-target: params or LFO
  rate/depth. Runs in the live renderer AND exports. Editors in
  Customize > Mod; persisted.
- Per-parameter locks + "Randomize (skips locked)" in Customize.

## Fluid round 3 (code 12) — F3 GPU particle layer
- Up-to-65k (256^2 default) inertial particles ride the fluid: state lives
  in an RGBA16F ping-pong texture (xy pos in sim space, zw velocity), one
  fullscreen kernel advances every particle, and a static texel-coord VBO
  drives a GL_POINTS render whose vertex stage fetches state (no CPU per
  frame). Drag inertia v += (flow - v) * drag (0.5) turns tracer dots into
  streaming light trails; edge wrap keeps the field populated; GPU-hash
  seeding, reseeded on aspect change.
- Speed-colored soft sprites drawn additively over the dye; existing knobs
  drive them now: Particle size scales the points, Density their
  brightness, palette base + hue range their speed-gradient colors.
- Cross-stage precision discipline from the round-2 crash applied to the
  new render program (explicit highp varyings/uniforms).
- Tests: drag-inertia contract (geometric convergence; drag=1 is the pure
  tracer) alongside the existing fluid math suite. Also integrated a
  parallel session's GLSL-tab groundwork (uFlow-ready helper text).
- ON-DEVICE: trails streak along the flow (not dots); rotation reseeds
  cleanly; style-cycling still leaks nothing; fps holds at 128/512+65k.

## Fluid round 3 (code 12) — F3 GPU particle layer
- Inertial GPU particles riding the fluid: state lives in a ping-ponged
  texture (xy = position in sim space, zw = velocity), one fullscreen
  kernel advances every particle per frame (v += (flow - v) * drag,
  p += v * dt, wrap at the domain edge), and a static VBO of texel
  coordinates drives a GL_POINTS render whose vertex stage fetches state -
  no CPU involvement, no per-frame uploads. Drag inertia (default in the
  streaks-not-dots range) is what turns tracer dots into streaming light
  trails; particles draw additively over the dye.
- Correctness work this round: highp qualifiers on the state/velocity
  samplers (lowp default sampling can quantise positions on some GPUs -
  same driver-strictness family as the round-2 link crash); grid scale dx
  now derived from the allocated grid (2/gridHeight) so alpha = -dx^2 and
  all finite differences are correct at ANY sim resolution - the spec 6.4
  invariant that resolution changes quality, not look (the fixed 2/32
  from round 1 was a constant-factor error, absorbed visually then, wrong
  once resolution varies). RGBA32F probed for particle state with 16F
  fallback so positions don't visibly cluster.
- Tests: state-texture side math (smallest covering square).
- ON-DEVICE: particles visible as moving streaks over the ink; drag feel
  (streaks curve through vortices rather than sitting on them); no
  clustering/stepping in particle positions (if stepping appears the 32F
  probe result in the format log line is the first thing to check);
  style-cycling still leak-free with the extra state buffers.

## Fluid round 2 (code 11) — device crash fix + F2 audio emitters
- FIX (device crash report): GL link failure "uInvRes precision does not
  match" on strict drivers - the shared vertex shader declares uInvRes as
  highp while the divergence/pressure/gradient fragments redeclared it
  under mediump default precision. Cross-stage uniforms now use explicit
  highp; swept all fluid shaders - uInvRes was the only cross-stage
  declaration, so this was the whole crash class.
- F2 emitters: the four-emitter audio system per the v2 spec - BeatSplat
  (center / ring-vortex / random / spectrumArc patterns), BandStirrer x2
  orbiting on band energy, TrebleSparkle on treble transients, BassPump
  (gated, off by default) - all as capsule splats with velocity blending;
  self-contained beat/bass envelopes shaped like the app ADSR.
- Continuous modulation: mids raise curl, quiet passages fade the canvas
  (drops leave lasting ink), beat envelope pulses splat radius; chromatic
  aging spreads per-channel dye decay so fading ink drifts in hue.
  Existing Color customize controls (palette, hue range, cycle) drive the
  dye; Speed drives stirrers, Turbulence scales curl.
- Tests: FluidEmittersTest - beats produce exactly beatSplats requests,
  stirrer paths are frame-continuous, spectrumArc orders left->right by
  band, beat envelope attacks/releases.
- ON-DEVICE: re-run the round-1 checklist on this build (the code-10 APK
  crashed at init on strict drivers before the probe line could help),
  then: beat splats land on beats; quiet/loud fade behavior audible in
  the ink; ring pattern spins up a visible vortex.

## Fluid round 1 (code 10) — FLUID scene: core Stam sim (F0+F1 of FLUID_SIM_2.md)
- New visual style FLUID: GPU stable-fluids sim (GLES3 port per the v2
  spec): half-float renderability probe with fallback cascade, sim-space
  coordinates (aspect handled once), boundary sampling in every pass
  (Neumann pressure / free-slip velocity), grid-scale-correct Jacobi
  (alpha = -dx^2) so resolution changes quality not look, manual-bilerp
  advection, vorticity confinement, capsule (swept-segment) splats with
  velocity blending instead of impulses.
- Registered in Styles under a new Fluid tab; P1 debug emitters = two
  band-driven orbiting stirrers; display = dye with soft HDR rolloff
  (full bloom/sunrays/shading chain comes in the Look phase).
- Licensing per spec: MIT headers on ported shaders + THIRD_PARTY_NOTICES;
  GPL reference used as clean-room principles only.
- Tests: FluidMathTest (aspect resolution mapping, CPU Jacobi projection
  reduces divergence >90%, capsule segDist degenerate guard). todo.md now
  carries the remaining fluid phases F2-F7.
- ON-DEVICE checklist for this round: format-probe logcat line; 5-min soak;
  rotation keeps ink; pause freezes sim; style-cycling leaks nothing.

## v0.9.9 (code 9) - P3/P4 round: AIFF everywhere, key badges, playlists, morphing
- AIFF support completed across ALL decode paths: playback already used the
  Media3 AiffExtractor (parallel work, audited); analysis and export now
  read AIFF too via a new streaming PCM reader (COMM/SSND, 80-bit extended
  sample rate, 8/16/24/32-bit, >2ch folds to stereo) since the platform
  extractor can't - so AIFF files analyze, visualize, and export.
- Musical key detection (chromagram + Krumhansl-Schmuckler, parallel work
  audited): key + BPM show as "artist · Cm · 124 BPM" badges on analyzed
  library rows.
- Playlists: rename (dialog) and track reorder (expand a playlist, move
  rows up/down).
- Preset morphing: applying a preset now interpolates parameters over N
  beats of the detected BPM (Settings slider, 0-16 beats, default 4;
  0 snaps like before).

## v0.9.8 - real track titles/artists + bottom inset fix
- Track rows and the player showed bare document numbers for many files.
  Titles now resolve like a real media player: embedded tags first
  (MediaMetadataRetriever TITLE/ARTIST), then the provider display name,
  then the path - and every queue item is built with MediaMetadata so the
  playing title/artist is correct everywhere (Now Playing, mini-player,
  library rows, search). Artist shows as a secondary line; existing
  library entries with number-titles are repaired once on startup.
- The Now Playing control card no longer sinks under the system
  navigation bar (navigationBarsPadding + lifted bottom margin); the top
  row respects the status bar.
- Removed the orphaned MusicLibraryDialog left from the old navigation.

## v0.9.7 - one navigation everywhere: new shell is the only UI
- The new navigation (Home / Library / Visuals / Settings shell with the
  mini-player) is now the ONLY navigation. Previously it showed on fresh
  boot but expanding the player brought back the old TopPlayBar + QuickBar
  chrome - that entire legacy layer is deleted (old VisualizerScreen
  chrome, QuickBar, TopPlayBar, TransportRow, Queue/Texture/PresetSave
  dialogs, BrowserDialog; 692 -> 203 lines).
- Now Playing is a clean fullscreen canvas in the shell's design language:
  collapse chip + title on top, one Material3 card with seek, transport
  (shuffle/prev/play/next/repeat), a Visuals shortcut that jumps to the
  hub, and the Auto mode toggle (off/random/smart). Tap the canvas to
  hide/show controls; Back collapses.
- Root-cause fix for "reverts to old behavior": all renderer bindings
  (scene, params, LFO/ADSR configs, transitions, preset/shader apply,
  features/PCM) moved from the expanded screen to the app shell
  (VisualizerEngineBindings), so visual changes made anywhere apply
  regardless of which screen is open.

## v0.9.6 - todo.md P0-P2: verified pipeline, preset folder, ADSR + randomizer
- P0 verify: new reflection roundtrip test guards every SceneParams field
  through preset JSON (fails loudly if a field is ever dropped again);
  docs/PARAM_MATRIX.md traces all params x scene families; fixes from that
  audit: Pulse now works on particle scenes (beat size swell) and Endless
  zoom works on all five particle scenes; docs/DEVICE_CHECKS.md lists the
  on-device GL checks. Beat threshold slider + treble smoothing confirmed
  in (flicker fix), built-in milkdrop presets fully removed.
- P1: Style sheet has Particles | Shaders | MilkDrop tabs with Load .milk
  and Textures moved into the MilkDrop tab; preset delete button; preset
  folder tree (add/rename/move); NEW: Settings > Paths > "Choose preset
  folder" - saves mirror the preset JSON (and .milk) into your chosen
  folder so your own sorting shows in any file manager.
- P2: 2x ADSR envelopes - beat triggers attack, band energy holds sustain
  (hysteresis + optional energy-tracking sustain level), release when the
  energy drops; every time/band/threshold adjustable; multiple targets per
  envelope (params or LFO rate/depth); runs live AND in exports.
  Per-parameter locks + "Randomize (skips locked)" in Customize.

## v0.9.5 - full debug-audit fix round (16 items)
Applied the complete 2026-07-23 debug audit, every P0/P1/P2 item:

Rendering (P0):
- Supersampled FBOs were destroyed on the first frame (onDrawFrame re-ensured
  them at screen size), defeating the zoom AA fix and cropping/off-centering
  visuals on devices where the supersample factor > 1. All ensure/viewport
  sites now use the render resolution consistently.
- Geometry/color FX (warp, ripple, kaleido, tile, twist, pixelate, bloom,
  posterize) were applied TWICE on all 20 shader scenes - in-shader and again
  in the composite pass (Tile 3 produced a 9x9 grid, kaleido folded twice).
  The composite now passes neutral values for shader scenes and real values
  for particle + milkdrop scenes, matching the mirror/invert guard pattern.
- Invert was double-applied on particle scenes (per-particle AND composite),
  cancelling itself out. The composite pass is now the single owner.
- Band gains were squared on shader scenes (applied in gainAdjusted() and
  again inside ShaderScene.update). One owner now: gainAdjusted().

Export parity (P1) - renders now match the live view:
- Milkdrop exports load the active .milk preset instead of projectM's idle
  default, and stay audio-reactive via the timeline waveform.
- Custom GLSL edits are used in exports (previously silently reverted).
- LFO automations run during export: a dedicated LfoEngine ticks per frame
  and the compositor takes per-frame params instead of a construction-time
  snapshot.
- Band gains apply in exports for all scene types.
- Particle trails render in exports: the export FBO fades (like live FBO A)
  instead of clearing when trails are on.

Smaller fixes (P2):
- GLSL editor reopens with your edited source, not the stock shader.
- Morph-adjacent params that were dead on particle/milkdrop scenes now work
  via the composite pass: drift X/Y (wrapping scroll), sway, shake, flash,
  temperature, solarize.
- Custom-shader apply no longer races scene switches (VizApply carries its
  sceneId; the pending-shader slot is now a queue so rapid edits all land).
- Multichannel (5.1/7.1) audio is downmixed to the AAC encoder layout, fixing
  garbled/wrong-speed export audio; the EOS flush no longer stalls 10 ms per
  iteration at the export tail.
- Endless-zoom phase wrap no longer pops (triangle-wave exponent in the
  milkdrop post shader); LFO random (S&H) samples once per cycle as labeled.
- Uniform locations are cached per program link (composite ~30, shader ~45,
  particle ~9 lookups/frame eliminated).
- Transcode phase honors Cancel; cancel no longer reports as an error.
- Export "succeeds with empty file" guarded (muxer must have started).
- 4K x ultrawide exports clamp to the 4096 px AVC hardware limit, scaled to
  preserve aspect.
- Random mode can't pick MilkDrop when the native library is unavailable.
- Track import metadata queries moved off the main thread.
- Preset names containing the built-in separator are sanitized so user
  presets stay deletable; export success text distinguishes chosen-folder
  destinations; launcher now has a proper adaptive icon; versionCode/Name
  track the internal version (0.9.5 / 5).

## v0.9.4 - exports now include the FX/geometry chain
- Fixed a significant export bug: the exporter drew each scene straight to the
  encoder surface and skipped the composite pass entirely, so exported videos
  were missing the whole screen-space FX and geometry chain (chromatic
  aberration, vignette, scanlines, grain, glitch, fisheye, strobe, bloom,
  posterize, warp, ripple, kaleidoscope, tile, twist, mirror, invert). Worst
  hit were particle scenes, whose shape/color customizations are composite-only
  (see v0.9.3). Exports now render each scene into an offscreen FBO and
  composite it through the same FX shader the live view uses, so what you
  export matches what you see.

## v0.9.3 - universal composite FX so particle scenes honor all params
- Empirical per-parameter audit found 21 Customize parameters had no effect on
  particle scenes (their vertex pipeline only forwards 9 uniforms). Geometric
  (warp, ripple, kaleidoscope, symmetry, pixelate, tile, twist, mirror) and
  color (bloom, posterize, invert) effects are now applied universally in the
  composite shader, so every scene type honors them. Shader scenes keep doing
  mirror/invert in-shader (guarded against double-application).
- GUI: the full transport row (shuffle / previous / play-pause / next / repeat)
  is grouped together in the top bar with elapsed/total time; the bottom row is
  secondary actions only.

## v0.9.2 - preset rework, milk save, export destination, zoom AA, UI border
- Reworked built-in presets: 12 strongly-differentiated looks (Chill, Punchy,
  Hypno, Vivid, Retro, Glitch, Dream, Warp, Prism, Noir, Strobe, Deep) x every
  scene, each exercising a distinct mix of motion / shape / color / screen-FX
  params so presets are visibly different (the old four were too alike). All
  57 params round-trip through save/load (v0.9.1 loader fix).
- Milkdrop presets now save as real .milk files: saving a preset while on the
  milkdrop scene copies the active .milk into your preset folder (tracked at
  every load site) alongside the customization bundle.
- Export destination: new "Render to chosen folder..." button opens the system
  file picker (SAF) so you choose exactly where the .mp4 is written, instead of
  it always going to Movies/MusicViz.
- Zoom pixelation fixed: scenes now render into 1.4x supersampled framebuffers
  (eased down on large screens) and downsample at composite time, so the Zoom
  customization has real detail to magnify instead of blowing up screen texels.
- Milkdrop "Use texture" preset reworked to a minimal, transpiler-safe comp
  shader (single sampler, no per-pixel branching) since projectM's HLSL->GLSL
  translation is fragile with complex comp shaders.
- UI: a themed outer border now frames the whole app, tinted from each theme's
  accent color so it fits all themes.

## v0.9.1 - deep audit: every param wired, presets load everything
- Full wiring audit of every Customize parameter through UI -> ViewModel ->
  renderer -> shader. Three real defects found and fixed:
  - Morph did nothing on 17 of 20 shader scenes (uniform uploaded but never
    used). It now blends the coordinate plane toward a polar remap in the
    shared prelude - a smooth geometric metamorphosis on every scene.
  - Bass/Mid/Treble gain sliders did nothing: the gains are now applied to
    the band levels in the shader pipeline.
  - Loading a preset silently dropped every parameter added since v0.6
    (warp, kaleidoscope, dual palette, bloom, all FX, drift, tile, twist,
    band gains, and more): the loader only reconstructed the original v0.6
    field list. It now covers all 57 parameters, generated from the actual
    SceneParams defaults so it cannot drift again.
- Audited and integrated the parallel FX/LFO drop: screen-space FX chain
  (chromatic aberration, vignette, scanlines, grain, glitch, fisheye,
  strobe) applied at composite time so it covers shader, particle AND
  milkdrop scenes; 3 chainable LFOs modulating any parameter; parameter
  fade; player position (top/bottom) setting; export fps choice. Verified
  each layer end-to-end; all transitions (cut/fade/melt/slide/zoom) intact.
- GUI audit: every dialog state has exactly one render site; top bar and
  overlay respect system insets; all tabs reachable.

## v0.9 - new scenes, built-in presets, texture "Use", true export length, beat sync
- 7 new visual styles: starfield (warp-speed stars), waves (layered
  oscilloscope ribbons), hexgrid (pulsing hex cells), spiral (log-spiral
  tunnel), aurora (flowing curtains), solar (band-driven flares) shader
  scenes plus orbits (band-driven orbital rings) particle scene - 25 scenes
  + milkdrop total. All support the full Customize panel, transitions,
  presets and export.
- Built-in presets for every visual style: four curated looks (Chill /
  Punchy / Hypno / Vivid) x every particle and shader scene (~100 presets),
  listed beside your saved presets in the Styles browser and usable in the
  visual playlist and Random mode. Built-ins can't be deleted.
- Random mode: switch styles/presets on an interval or on strong musical
  moments, with source filters and per-switch color shuffle.
- Milkdrop textures are now actually usable: each imported texture has a
  "Use" button that generates an audio-reactive display preset referencing
  it (MilkDrop presets only show textures they name - previously imports
  loaded but nothing referenced them). Imported names are made shader-safe,
  and .gif (never scanned by projectM) is no longer accepted.
- Musically intelligent visuals: a BPM-locked beat-phase clock drives the
  Beat pulse so it lands on the actual beat instead of free-running;
  detected-beat resync keeps it locked through tempo drift.
- Exports are now exactly as long as the music: video frame count derives
  from the measured duration of the actual transcoded audio (not the
  analysis timeline), and the analyzer's timing drift was fixed (~4%
  shortfall previously cut exports short).
- Bug-fix batch: preset saves persist edited custom shaders; AUTO mode
  crash fixed (ExoPlayer touched off-main); long-export OOM fixed (AAC
  streams to a temp file); cancelled exports no longer publish truncated
  files; BPM detection de-biased from doubled tempos; projectM PCM cursor
  no longer skips samples; scene switches clear stale trails; float-PCM
  decoder output handled; playlist analysis progresses past failed tracks;
  persistable-permission crash guarded.

## v0.8 - fixes: textures, background reset, title, lighter icons
- Top bar: play/prev/next icons are now white (lighter) for contrast over the
  visuals, and the title field shows the current song, falling back to the
  file name when the track has no embedded title metadata.
- Removed the duplicate play/pause/prev/next from the bottom control row - the
  primary transport now lives only in the top bar. The bottom row keeps
  shuffle and repeat (which aren't in the top bar) plus the secondary actions.
- Bug fix - imported milkdrop textures now actually load: adding textures
  re-parses the current preset (bypassing the load debounce) so projectM
  re-binds the texture search path. The texture picker also accepts .tga/.dds
  (classic Milkdrop formats), not just standard images.
- Bug fix - visuals no longer reset when the app is backgrounded: when Android
  destroys the EGL context, the renderer now restores the current scene
  params, any edited custom shader, and the last milkdrop preset on surface
  recreation instead of falling back to defaults.

## v0.7 - milkdrop textures, bigger customize panel, single seek bar
- Milkdrop "Add texture": a Textures button (in the milkdrop control row)
  opens a manager to import image files into the shared texture folder that
  projectM already searches. Many MilkDrop presets reference external image
  textures by filename (the classic Milkdrop texture pack, plus per-preset
  images); importing them here lets those presets render correctly instead of
  falling back to noise/black. The current preset reloads automatically after
  import so textures apply immediately.
- Customize panel greatly expanded, now four tabs (Motion / Shape / Behavior
  / Color) plus GLSL:
  - New Shape tab: domain warp, ripple, morph, kaleidoscope with selectable
    fold count (2-12), pixelate, posterize, and particle-scene shape
    (Dot / Ring / Star / Square / Spark) + particle size.
  - Motion gained sway and beat-pulse.
  - Color gained a second palette with a blend slider, five extra palettes
    (Candy, Forest, Aurora, Sunset, Ice - ten total), bloom, and duotone.
  All shader-driven params flow through the shared prelude, so every shader
  scene inherits them; particle shapes/size drive the particle scenes.
- Removed the duplicate seek bar: the progress slider now lives only in the
  top play bar (it had been shown twice).

## v0.6 - top play bar, settings menu, themes
- Top play bar: a translucent card pinned to the top of the screen with the
  track title, standard transport icons (previous / play-pause / next) and a
  seek slider - always available while controls are visible, like a normal
  music player. The gear button on it opens Settings.
- Settings menu (gear) now leads with a Theme picker and keeps the full
  export section (quality, aspect ratio, share/Drive) below it.
- Six selectable app themes (Midnight, Neon, Sunset, Forest, Mono, Light),
  persisted across launches; the choice recolors all control surfaces,
  dialogs, sliders and accents via the Material 3 color scheme.

## v0.5 - music library, playlists, settings & export upgrades
- Music library browser (Library button): import individual audio files or a
  whole folder (SAF tree), with persisted read permission. Imported tracks
  list their cached BPM + duration once analyzed and persist across launches
  (library.json), so you can play them again later.
- Playlist maker: create named playlists, add tracks from the library,
  reorder with up/down controls, remove, delete, and play from any position.
  "Analyze" batch-analyzes every track in a playlist in the background and
  caches the results into the library.
- Transport is now icon-based (shuffle / previous / play-pause / next /
  repeat) like a standard player, with active-state tinting.
- Settings menu (gear icon) now hosts Export. Export gained a quality tier
  (720p / 1080p / 4K, bitrate scales per tier, 4K auto-falls-back if the
  device encoder refuses it) and six aspect ratios (16:9, 9:16, 1:1, 4:5,
  4:3, 21:9) computed to even encoder dimensions. Finished exports offer
  "Upload to Drive / Share" via the system share sheet (Drive appears when
  installed).

## v0.4 - customization for milkdrop, playlists, transitions
- FIX freeze after loading .milk presets: preset import and built-in asset
  copying now run on a background thread with one-time caching and tap
  debouncing (the old code re-copied every asset on the main thread per tap).
- FIX customizations now affect milkdrop: projectM renders into an
  offscreen FBO texture (projectm_opengl_render_frame_fbo) and a post-process
  pass applies zoom, rotation, endless zoom, mirror, hue/saturation/
  brightness/contrast/gamma/invert/intensity; Beat response maps to projectM
  beat sensitivity. (Speed cannot retime projectM's internal clock.)
- Color tab gains Contrast and Gamma faders across all scene types.
- Attack/Decay reactivity sliders and the Manual/Suggest/Auto intelligence
  chips moved into Customize > Behavior.
- Music player: shuffle, repeat (off/all/one), previous/next transport, and
  a tap-to-jump Playback queue dialog.
- Style/preset browser: pick a style first, then its presets - user-saved
  presets are grouped under their style; milkdrop lists built-in and
  imported .milk files. Anything can be added to the visual playlist.
- Visual preset playlist with auto-advance (fixed interval or Intelligent -
  switches on strong musical moments with a minimum dwell and a 2x-interval
  fallback) and transition styles between scenes: cut, fade, melt
  (dual-FBO compositor; melt drips the outgoing frame away by luminance).
  Same-scene preset changes currently hard-cut.

## Bugfix log (v0.3.3)
- Milkdrop lifecycle aligned with working reference integrations (official
  projectM examples-android and a ProjectM-4 Android TV app): the engine is
  now created lazily on the GL thread with the REAL surface size (never at
  1x1 followed by set_window_size), glViewport is forced to the surface size
  both before and after every projectm_opengl_render_frame (projectM depends
  on and mutates it), the last loaded preset is re-applied automatically when
  the engine is recreated after GL context loss, and an engine-init failure
  now shows an on-screen error instead of a silent black screen.
- Workspace placed under git (baseline commit) after repeated foreign file
  modifications were found; use git status to detect tampering.

## Bugfix log (v0.3.2)
- Milkdrop preset loading root cause found and fixed: opening the system
  file picker paused the GLSurfaceView, destroying the GL context; on
  return every scene was rebuilt and the queued .milk path landed on a
  discarded ProjectMScene instance - the preset deterministically never
  loaded, and any loaded preset (and custom GLSL edits) silently reverted
  on every app switch. Fixes: preserveEGLContextOnPause=true, and durable
  renderer-level state (milk preset path + custom shader sources) re-applied
  after genuine context loss. Engine verified working end-to-end via a
  headless Mesa/GLES smoke test (tools/smoke.c) that loads real BeatDrop
  presets and checks rendered pixels. Errors now display in the main
  overlay, including preset import failures.

## v0.3.4
- Freeze after loading presets, mitigations: removed
  preserveEGLContextOnPause (foreign edit; preserved-context resume after
  the file picker is a known device-specific GL hang source - our
  context-loss discipline with automatic preset restore covers resume);
  projectm_set_texture_search_paths now only fires when the preset's
  directory changes (each call purges projectM's texture cache and repeated
  purges caused stalls); full GL state restore after projectM renders.
- Customize panel restructured: Style is its own tab with a vertical
  scrolling scene list (suggested scene marked with a star); Behavior tab now
  contains Attack/Decay reactivity sliders and the manual/suggest/auto
  intelligence mode buttons; main overlay slimmed to a quick bar
  (Customize, milkdrop preset buttons, saved presets) + transport.

## v0.9.6 - P0 verification + P1 milkdrop tab & preset architecture
- P0: reflection roundtrip test guards all SceneParams fields through preset
  JSON forever (any dropped field fails CI); param x scene-family matrix
  documented (docs/PARAM_MATRIX.md) and its dead combos fixed - Pulse now
  works on particle scenes (beat size swell) and Endless Zoom works on all
  five particle scenes (Swarm/Fountain outward flow, Orbit radius drift).
  Flicker fix confirmed in: beat threshold 2.5 sigma (user slider in
  Settings > Analysis), longer refractory, extra treble smoothing.
  Built-in milkdrop presets removed; "Preset >" cycles your own .milk files.
  On-device checklist: docs/DEVICE_CHECKS.md.
- P1: Style browser has Particles | Shaders | MilkDrop tabs with Load .milk
  and Textures moved into the MilkDrop tab; user presets are deletable;
  preset folders (add/rename/move) form a browsable tree; NEW Settings >
  Paths lets you choose a preset folder - every save mirrors the JSON and
  paired .milk there so your own sorting shows in any file manager.

## v0.9.6 - P0 verification, P1 preset architecture, P2 modulation
Working through todo.md (P0-P2), merged with a parallel session's drops:

P0 Verify & stabilize:
- New reflection roundtrip test: every SceneParams field is perturbed,
  serialized, parsed, and compared - presets can never silently drop a
  parameter again (serializers moved to an internal companion for
  testability; 30 tests green).
- docs/PARAM_MATRIX.md: all 49 params traced across shader / particle /
  milkdrop with their mechanism; dead combos fixed - Pulse now works on
  particles (beat size swell) and Endless Zoom works on all five particle
  scenes (outward flow; Orbit drifts radii with respawn). Remaining
  exceptions documented with reasons. docs/DEVICE_CHECKS.md lists the
  on-device checks that can't be verified headless.
- Flicker fix confirmed in: beat threshold 2.5 sigma (user slider in
  Settings > Analysis), ~333 ms refractory, extra treble smoothing.
- Built-in milkdrop presets removed; "Preset >" cycles your own files.

P1 Milkdrop tab & preset architecture:
- Style sheet now tabs Particles | Shaders | MilkDrop, with Load .milk and
  Textures moved into the MilkDrop tab; delete button on user presets;
  preset folder tree (add / rename / move).
- NEW Settings > Paths: choose a preset folder (system picker) - every
  save mirrors the JSON and paired .milk there so your own sorting is
  visible in any file manager.

P2 Modulation:
- 2x ADSR envelopes exactly per spec: BEAT triggers attack (optional
  retrigger), band ENERGY holds sustain (with hysteresis; sustain can
  track energy), release when energy drops. Multi-target: params or LFO
  rate/depth. Runs in the live renderer AND exports. Editors in
  Customize > Mod; persisted.
- Per-parameter locks + "Randomize (skips locked)" in Customize.

## Fluid round 3 (code 12) — F3 GPU particle layer
- Up-to-65k (256^2 default) inertial particles ride the fluid: state lives
  in an RGBA16F ping-pong texture (xy pos in sim space, zw velocity), one
  fullscreen kernel advances every particle, and a static texel-coord VBO
  drives a GL_POINTS render whose vertex stage fetches state (no CPU per
  frame). Drag inertia v += (flow - v) * drag (0.5) turns tracer dots into
  streaming light trails; edge wrap keeps the field populated; GPU-hash
  seeding, reseeded on aspect change.
- Speed-colored soft sprites drawn additively over the dye; existing knobs
  drive them now: Particle size scales the points, Density their
  brightness, palette base + hue range their speed-gradient colors.
- Cross-stage precision discipline from the round-2 crash applied to the
  new render program (explicit highp varyings/uniforms).
- Tests: drag-inertia contract (geometric convergence; drag=1 is the pure
  tracer) alongside the existing fluid math suite. Also integrated a
  parallel session's GLSL-tab groundwork (uFlow-ready helper text).
- ON-DEVICE: trails streak along the flow (not dots); rotation reseeds
  cleanly; style-cycling still leaks nothing; fps holds at 128/512+65k.

## Fluid round 3 (code 12) — F3 GPU particle layer
- Inertial GPU particles riding the fluid: state lives in a ping-ponged
  texture (xy = position in sim space, zw = velocity), one fullscreen
  kernel advances every particle per frame (v += (flow - v) * drag,
  p += v * dt, wrap at the domain edge), and a static VBO of texel
  coordinates drives a GL_POINTS render whose vertex stage fetches state -
  no CPU involvement, no per-frame uploads. Drag inertia (default in the
  streaks-not-dots range) is what turns tracer dots into streaming light
  trails; particles draw additively over the dye.
- Correctness work this round: highp qualifiers on the state/velocity
  samplers (lowp default sampling can quantise positions on some GPUs -
  same driver-strictness family as the round-2 link crash); grid scale dx
  now derived from the allocated grid (2/gridHeight) so alpha = -dx^2 and
  all finite differences are correct at ANY sim resolution - the spec 6.4
  invariant that resolution changes quality, not look (the fixed 2/32
  from round 1 was a constant-factor error, absorbed visually then, wrong
  once resolution varies). RGBA32F probed for particle state with 16F
  fallback so positions don't visibly cluster.
- Tests: state-texture side math (smallest covering square).
- ON-DEVICE: particles visible as moving streaks over the ink; drag feel
  (streaks curve through vortices rather than sitting on them); no
  clustering/stepping in particle positions (if stepping appears the 32F
  probe result in the format log line is the first thing to check);
  style-cycling still leak-free with the extra state buffers.

## Fluid round 2 (code 11) — device crash fix + F2 audio emitters
- FIX (device crash report): GL link failure "uInvRes precision does not
  match" on strict drivers - the shared vertex shader declares uInvRes as
  highp while the divergence/pressure/gradient fragments redeclared it
  under mediump default precision. Cross-stage uniforms now use explicit
  highp; swept all fluid shaders - uInvRes was the only cross-stage
  declaration, so this was the whole crash class.
- F2 emitters: the four-emitter audio system per the v2 spec - BeatSplat
  (center / ring-vortex / random / spectrumArc patterns), BandStirrer x2
  orbiting on band energy, TrebleSparkle on treble transients, BassPump
  (gated, off by default) - all as capsule splats with velocity blending;
  self-contained beat/bass envelopes shaped like the app ADSR.
- Continuous modulation: mids raise curl, quiet passages fade the canvas
  (drops leave lasting ink), beat envelope pulses splat radius; chromatic
  aging spreads per-channel dye decay so fading ink drifts in hue.
  Existing Color customize controls (palette, hue range, cycle) drive the
  dye; Speed drives stirrers, Turbulence scales curl.
- Tests: FluidEmittersTest - beats produce exactly beatSplats requests,
  stirrer paths are frame-continuous, spectrumArc orders left->right by
  band, beat envelope attacks/releases.
- ON-DEVICE: re-run the round-1 checklist on this build (the code-10 APK
  crashed at init on strict drivers before the probe line could help),
  then: beat splats land on beats; quiet/loud fade behavior audible in
  the ink; ring pattern spins up a visible vortex.

## Fluid round 1 (code 10) — FLUID scene: core Stam sim (F0+F1 of FLUID_SIM_2.md)
- New visual style FLUID: GPU stable-fluids sim (GLES3 port per the v2
  spec): half-float renderability probe with fallback cascade, sim-space
  coordinates (aspect handled once), boundary sampling in every pass
  (Neumann pressure / free-slip velocity), grid-scale-correct Jacobi
  (alpha = -dx^2) so resolution changes quality not look, manual-bilerp
  advection, vorticity confinement, capsule (swept-segment) splats with
  velocity blending instead of impulses.
- Registered in Styles under a new Fluid tab; P1 debug emitters = two
  band-driven orbiting stirrers; display = dye with soft HDR rolloff
  (full bloom/sunrays/shading chain comes in the Look phase).
- Licensing per spec: MIT headers on ported shaders + THIRD_PARTY_NOTICES;
  GPL reference used as clean-room principles only.
- Tests: FluidMathTest (aspect resolution mapping, CPU Jacobi projection
  reduces divergence >90%, capsule segDist degenerate guard). todo.md now
  carries the remaining fluid phases F2-F7.
- ON-DEVICE checklist for this round: format-probe logcat line; 5-min soak;
  rotation keeps ink; pause freezes sim; style-cycling leaks nothing.

## v0.9.9 (code 9) - P3/P4 round: AIFF everywhere, key badges, playlists, morphing
- AIFF support completed across ALL decode paths: playback already used the
  Media3 AiffExtractor (parallel work, audited); analysis and export now
  read AIFF too via a new streaming PCM reader (COMM/SSND, 80-bit extended
  sample rate, 8/16/24/32-bit, >2ch folds to stereo) since the platform
  extractor can't - so AIFF files analyze, visualize, and export.
- Musical key detection (chromagram + Krumhansl-Schmuckler, parallel work
  audited): key + BPM show as "artist · Cm · 124 BPM" badges on analyzed
  library rows.
- Playlists: rename (dialog) and track reorder (expand a playlist, move
  rows up/down).
- Preset morphing: applying a preset now interpolates parameters over N
  beats of the detected BPM (Settings slider, 0-16 beats, default 4;
  0 snaps like before).

## v0.9.8 - real track titles/artists + bottom inset fix
- Track rows and the player showed bare document numbers for many files.
  Titles now resolve like a real media player: embedded tags first
  (MediaMetadataRetriever TITLE/ARTIST), then the provider display name,
  then the path - and every queue item is built with MediaMetadata so the
  playing title/artist is correct everywhere (Now Playing, mini-player,
  library rows, search). Artist shows as a secondary line; existing
  library entries with number-titles are repaired once on startup.
- The Now Playing control card no longer sinks under the system
  navigation bar (navigationBarsPadding + lifted bottom margin); the top
  row respects the status bar.
- Removed the orphaned MusicLibraryDialog left from the old navigation.

## v0.9.7 - one navigation everywhere: new shell is the only UI
- The new navigation (Home / Library / Visuals / Settings shell with the
  mini-player) is now the ONLY navigation. Previously it showed on fresh
  boot but expanding the player brought back the old TopPlayBar + QuickBar
  chrome - that entire legacy layer is deleted (old VisualizerScreen
  chrome, QuickBar, TopPlayBar, TransportRow, Queue/Texture/PresetSave
  dialogs, BrowserDialog; 692 -> 203 lines).
- Now Playing is a clean fullscreen canvas in the shell's design language:
  collapse chip + title on top, one Material3 card with seek, transport
  (shuffle/prev/play/next/repeat), a Visuals shortcut that jumps to the
  hub, and the Auto mode toggle (off/random/smart). Tap the canvas to
  hide/show controls; Back collapses.
- Root-cause fix for "reverts to old behavior": all renderer bindings
  (scene, params, LFO/ADSR configs, transitions, preset/shader apply,
  features/PCM) moved from the expanded screen to the app shell
  (VisualizerEngineBindings), so visual changes made anywhere apply
  regardless of which screen is open.

## v0.9.6 - todo.md P0-P2: verified pipeline, preset folder, ADSR + randomizer
- P0 verify: new reflection roundtrip test guards every SceneParams field
  through preset JSON (fails loudly if a field is ever dropped again);
  docs/PARAM_MATRIX.md traces all params x scene families; fixes from that
  audit: Pulse now works on particle scenes (beat size swell) and Endless
  zoom works on all five particle scenes; docs/DEVICE_CHECKS.md lists the
  on-device GL checks. Beat threshold slider + treble smoothing confirmed
  in (flicker fix), built-in milkdrop presets fully removed.
- P1: Style sheet has Particles | Shaders | MilkDrop tabs with Load .milk
  and Textures moved into the MilkDrop tab; preset delete button; preset
  folder tree (add/rename/move); NEW: Settings > Paths > "Choose preset
  folder" - saves mirror the preset JSON (and .milk) into your chosen
  folder so your own sorting shows in any file manager.
- P2: 2x ADSR envelopes - beat triggers attack, band energy holds sustain
  (hysteresis + optional energy-tracking sustain level), release when the
  energy drops; every time/band/threshold adjustable; multiple targets per
  envelope (params or LFO rate/depth); runs live AND in exports.
  Per-parameter locks + "Randomize (skips locked)" in Customize.

## v0.9.5 - full debug-audit fix round (16 items)
Applied the complete 2026-07-23 debug audit, every P0/P1/P2 item:

Rendering (P0):
- Supersampled FBOs were destroyed on the first frame (onDrawFrame re-ensured
  them at screen size), defeating the zoom AA fix and cropping/off-centering
  visuals on devices where the supersample factor > 1. All ensure/viewport
  sites now use the render resolution consistently.
- Geometry/color FX (warp, ripple, kaleido, tile, twist, pixelate, bloom,
  posterize) were applied TWICE on all 20 shader scenes - in-shader and again
  in the composite pass (Tile 3 produced a 9x9 grid, kaleido folded twice).
  The composite now passes neutral values for shader scenes and real values
  for particle + milkdrop scenes, matching the mirror/invert guard pattern.
- Invert was double-applied on particle scenes (per-particle AND composite),
  cancelling itself out. The composite pass is now the single owner.
- Band gains were squared on shader scenes (applied in gainAdjusted() and
  again inside ShaderScene.update). One owner now: gainAdjusted().

Export parity (P1) - renders now match the live view:
- Milkdrop exports load the active .milk preset instead of projectM's idle
  default, and stay audio-reactive via the timeline waveform.
- Custom GLSL edits are used in exports (previously silently reverted).
- LFO automations run during export: a dedicated LfoEngine ticks per frame
  and the compositor takes per-frame params instead of a construction-time
  snapshot.
- Band gains apply in exports for all scene types.
- Particle trails render in exports: the export FBO fades (like live FBO A)
  instead of clearing when trails are on.

Smaller fixes (P2):
- GLSL editor reopens with your edited source, not the stock shader.
- Morph-adjacent params that were dead on particle/milkdrop scenes now work
  via the composite pass: drift X/Y (wrapping scroll), sway, shake, flash,
  temperature, solarize.
- Custom-shader apply no longer races scene switches (VizApply carries its
  sceneId; the pending-shader slot is now a queue so rapid edits all land).
- Multichannel (5.1/7.1) audio is downmixed to the AAC encoder layout, fixing
  garbled/wrong-speed export audio; the EOS flush no longer stalls 10 ms per
  iteration at the export tail.
- Endless-zoom phase wrap no longer pops (triangle-wave exponent in the
  milkdrop post shader); LFO random (S&H) samples once per cycle as labeled.
- Uniform locations are cached per program link (composite ~30, shader ~45,
  particle ~9 lookups/frame eliminated).
- Transcode phase honors Cancel; cancel no longer reports as an error.
- Export "succeeds with empty file" guarded (muxer must have started).
- 4K x ultrawide exports clamp to the 4096 px AVC hardware limit, scaled to
  preserve aspect.
- Random mode can't pick MilkDrop when the native library is unavailable.
- Track import metadata queries moved off the main thread.
- Preset names containing the built-in separator are sanitized so user
  presets stay deletable; export success text distinguishes chosen-folder
  destinations; launcher now has a proper adaptive icon; versionCode/Name
  track the internal version (0.9.5 / 5).

## v0.9.4 - exports now include the FX/geometry chain
- Fixed a significant export bug: the exporter drew each scene straight to the
  encoder surface and skipped the composite pass entirely, so exported videos
  were missing the whole screen-space FX and geometry chain (chromatic
  aberration, vignette, scanlines, grain, glitch, fisheye, strobe, bloom,
  posterize, warp, ripple, kaleidoscope, tile, twist, mirror, invert). Worst
  hit were particle scenes, whose shape/color customizations are composite-only
  (see v0.9.3). Exports now render each scene into an offscreen FBO and
  composite it through the same FX shader the live view uses, so what you
  export matches what you see.

## v0.9.3 - universal composite FX so particle scenes honor all params
- Empirical per-parameter audit found 21 Customize parameters had no effect on
  particle scenes (their vertex pipeline only forwards 9 uniforms). Geometric
  (warp, ripple, kaleidoscope, symmetry, pixelate, tile, twist, mirror) and
  color (bloom, posterize, invert) effects are now applied universally in the
  composite shader, so every scene type honors them. Shader scenes keep doing
  mirror/invert in-shader (guarded against double-application).
- GUI: the full transport row (shuffle / previous / play-pause / next / repeat)
  is grouped together in the top bar with elapsed/total time; the bottom row is
  secondary actions only.

## v0.9.2 - preset rework, milk save, export destination, zoom AA, UI border
- Reworked built-in presets: 12 strongly-differentiated looks (Chill, Punchy,
  Hypno, Vivid, Retro, Glitch, Dream, Warp, Prism, Noir, Strobe, Deep) x every
  scene, each exercising a distinct mix of motion / shape / color / screen-FX
  params so presets are visibly different (the old four were too alike). All
  57 params round-trip through save/load (v0.9.1 loader fix).
- Milkdrop presets now save as real .milk files: saving a preset while on the
  milkdrop scene copies the active .milk into your preset folder (tracked at
  every load site) alongside the customization bundle.
- Export destination: new "Render to chosen folder..." button opens the system
  file picker (SAF) so you choose exactly where the .mp4 is written, instead of
  it always going to Movies/MusicViz.
- Zoom pixelation fixed: scenes now render into 1.4x supersampled framebuffers
  (eased down on large screens) and downsample at composite time, so the Zoom
  customization has real detail to magnify instead of blowing up screen texels.
- Milkdrop "Use texture" preset reworked to a minimal, transpiler-safe comp
  shader (single sampler, no per-pixel branching) since projectM's HLSL->GLSL
  translation is fragile with complex comp shaders.
- UI: a themed outer border now frames the whole app, tinted from each theme's
  accent color so it fits all themes.

## v0.9.1 - deep audit: every param wired, presets load everything
- Full wiring audit of every Customize parameter through UI -> ViewModel ->
  renderer -> shader. Three real defects found and fixed:
  - Morph did nothing on 17 of 20 shader scenes (uniform uploaded but never
    used). It now blends the coordinate plane toward a polar remap in the
    shared prelude - a smooth geometric metamorphosis on every scene.
  - Bass/Mid/Treble gain sliders did nothing: the gains are now applied to
    the band levels in the shader pipeline.
  - Loading a preset silently dropped every parameter added since v0.6
    (warp, kaleidoscope, dual palette, bloom, all FX, drift, tile, twist,
    band gains, and more): the loader only reconstructed the original v0.6
    field list. It now covers all 57 parameters, generated from the actual
    SceneParams defaults so it cannot drift again.
- Audited and integrated the parallel FX/LFO drop: screen-space FX chain
  (chromatic aberration, vignette, scanlines, grain, glitch, fisheye,
  strobe) applied at composite time so it covers shader, particle AND
  milkdrop scenes; 3 chainable LFOs modulating any parameter; parameter
  fade; player position (top/bottom) setting; export fps choice. Verified
  each layer end-to-end; all transitions (cut/fade/melt/slide/zoom) intact.
- GUI audit: every dialog state has exactly one render site; top bar and
  overlay respect system insets; all tabs reachable.

## v0.9 - new scenes, built-in presets, texture "Use", true export length, beat sync
- 7 new visual styles: starfield (warp-speed stars), waves (layered
  oscilloscope ribbons), hexgrid (pulsing hex cells), spiral (log-spiral
  tunnel), aurora (flowing curtains), solar (band-driven flares) shader
  scenes plus orbits (band-driven orbital rings) particle scene - 25 scenes
  + milkdrop total. All support the full Customize panel, transitions,
  presets and export.
- Built-in presets for every visual style: four curated looks (Chill /
  Punchy / Hypno / Vivid) x every particle and shader scene (~100 presets),
  listed beside your saved presets in the Styles browser and usable in the
  visual playlist and Random mode. Built-ins can't be deleted.
- Random mode: switch styles/presets on an interval or on strong musical
  moments, with source filters and per-switch color shuffle.
- Milkdrop textures are now actually usable: each imported texture has a
  "Use" button that generates an audio-reactive display preset referencing
  it (MilkDrop presets only show textures they name - previously imports
  loaded but nothing referenced them). Imported names are made shader-safe,
  and .gif (never scanned by projectM) is no longer accepted.
- Musically intelligent visuals: a BPM-locked beat-phase clock drives the
  Beat pulse so it lands on the actual beat instead of free-running;
  detected-beat resync keeps it locked through tempo drift.
- Exports are now exactly as long as the music: video frame count derives
  from the measured duration of the actual transcoded audio (not the
  analysis timeline), and the analyzer's timing drift was fixed (~4%
  shortfall previously cut exports short).
- Bug-fix batch: preset saves persist edited custom shaders; AUTO mode
  crash fixed (ExoPlayer touched off-main); long-export OOM fixed (AAC
  streams to a temp file); cancelled exports no longer publish truncated
  files; BPM detection de-biased from doubled tempos; projectM PCM cursor
  no longer skips samples; scene switches clear stale trails; float-PCM
  decoder output handled; playlist analysis progresses past failed tracks;
  persistable-permission crash guarded.

## v0.8 - fixes: textures, background reset, title, lighter icons
- Top bar: play/prev/next icons are now white (lighter) for contrast over the
  visuals, and the title field shows the current song, falling back to the
  file name when the track has no embedded title metadata.
- Removed the duplicate play/pause/prev/next from the bottom control row - the
  primary transport now lives only in the top bar. The bottom row keeps
  shuffle and repeat (which aren't in the top bar) plus the secondary actions.
- Bug fix - imported milkdrop textures now actually load: adding textures
  re-parses the current preset (bypassing the load debounce) so projectM
  re-binds the texture search path. The texture picker also accepts .tga/.dds
  (classic Milkdrop formats), not just standard images.
- Bug fix - visuals no longer reset when the app is backgrounded: when Android
  destroys the EGL context, the renderer now restores the current scene
  params, any edited custom shader, and the last milkdrop preset on surface
  recreation instead of falling back to defaults.

## v0.7 - milkdrop textures, bigger customize panel, single seek bar
- Milkdrop "Add texture": a Textures button (in the milkdrop control row)
  opens a manager to import image files into the shared texture folder that
  projectM already searches. Many MilkDrop presets reference external image
  textures by filename (the classic Milkdrop texture pack, plus per-preset
  images); importing them here lets those presets render correctly instead of
  falling back to noise/black. The current preset reloads automatically after
  import so textures apply immediately.
- Customize panel greatly expanded, now four tabs (Motion / Shape / Behavior
  / Color) plus GLSL:
  - New Shape tab: domain warp, ripple, morph, kaleidoscope with selectable
    fold count (2-12), pixelate, posterize, and particle-scene shape
    (Dot / Ring / Star / Square / Spark) + particle size.
  - Motion gained sway and beat-pulse.
  - Color gained a second palette with a blend slider, five extra palettes
    (Candy, Forest, Aurora, Sunset, Ice - ten total), bloom, and duotone.
  All shader-driven params flow through the shared prelude, so every shader
  scene inherits them; particle shapes/size drive the particle scenes.
- Removed the duplicate seek bar: the progress slider now lives only in the
  top play bar (it had been shown twice).

## v0.6 - top play bar, settings menu, themes
- Top play bar: a translucent card pinned to the top of the screen with the
  track title, standard transport icons (previous / play-pause / next) and a
  seek slider - always available while controls are visible, like a normal
  music player. The gear button on it opens Settings.
- Settings menu (gear) now leads with a Theme picker and keeps the full
  export section (quality, aspect ratio, share/Drive) below it.
- Six selectable app themes (Midnight, Neon, Sunset, Forest, Mono, Light),
  persisted across launches; the choice recolors all control surfaces,
  dialogs, sliders and accents via the Material 3 color scheme.

## v0.5 - music library, playlists, settings & export upgrades
- Music library browser (Library button): import individual audio files or a
  whole folder (SAF tree), with persisted read permission. Imported tracks
  list their cached BPM + duration once analyzed and persist across launches
  (library.json), so you can play them again later.
- Playlist maker: create named playlists, add tracks from the library,
  reorder with up/down controls, remove, delete, and play from any position.
  "Analyze" batch-analyzes every track in a playlist in the background and
  caches the results into the library.
- Transport is now icon-based (shuffle / previous / play-pause / next /
  repeat) like a standard player, with active-state tinting.
- Settings menu (gear icon) now hosts Export. Export gained a quality tier
  (720p / 1080p / 4K, bitrate scales per tier, 4K auto-falls-back if the
  device encoder refuses it) and six aspect ratios (16:9, 9:16, 1:1, 4:5,
  4:3, 21:9) computed to even encoder dimensions. Finished exports offer
  "Upload to Drive / Share" via the system share sheet (Drive appears when
  installed).

## v0.4 - customization for milkdrop, playlists, transitions
- FIX freeze after loading .milk presets: preset import and built-in asset
  copying now run on a background thread with one-time caching and tap
  debouncing (the old code re-copied every asset on the main thread per tap).
- FIX customizations now affect milkdrop: projectM renders into an
  offscreen FBO texture (projectm_opengl_render_frame_fbo) and a post-process
  pass applies zoom, rotation, endless zoom, mirror, hue/saturation/
  brightness/contrast/gamma/invert/intensity; Beat response maps to projectM
  beat sensitivity. (Speed cannot retime projectM's internal clock.)
- Color tab gains Contrast and Gamma faders across all scene types.
- Attack/Decay reactivity sliders and the Manual/Suggest/Auto intelligence
  chips moved into Customize > Behavior.
- Music player: shuffle, repeat (off/all/one), previous/next transport, and
  a tap-to-jump Playback queue dialog.
- Style/preset browser: pick a style first, then its presets - user-saved
  presets are grouped under their style; milkdrop lists built-in and
  imported .milk files. Anything can be added to the visual playlist.
- Visual preset playlist with auto-advance (fixed interval or Intelligent -
  switches on strong musical moments with a minimum dwell and a 2x-interval
  fallback) and transition styles between scenes: cut, fade, melt
  (dual-FBO compositor; melt drips the outgoing frame away by luminance).
  Same-scene preset changes currently hard-cut.

## Bugfix log (v0.3.3)
- Milkdrop lifecycle aligned with working reference integrations (official
  projectM examples-android and a ProjectM-4 Android TV app): the engine is
  now created lazily on the GL thread with the REAL surface size (never at
  1x1 followed by set_window_size), glViewport is forced to the surface size
  both before and after every projectm_opengl_render_frame (projectM depends
  on and mutates it), the last loaded preset is re-applied automatically when
  the engine is recreated after GL context loss, and an engine-init failure
  now shows an on-screen error instead of a silent black screen.
- Workspace placed under git (baseline commit) after repeated foreign file
  modifications were found; use git status to detect tampering.

## Bugfix log (v0.3.2)
- Milkdrop root cause found: libprojectM had been built from the *master*
  branch, which carries an experimental GL bootstrap (GLResolver/GladLoader
  "strict context gate") not present in any release - the resulting .so had
  no GLES linkage at all and projectm_create could fail on-device, leaving
  the scene black and preset loads with no engine to load into. Rebuilt from
  the stable v4.1.7 release tag: OpenGL ES: ON, direct libGLESv3.so linkage
  (verified with readelf), JNI relinked against release headers.
- Bundled three self-authored starter presets (assets/presets/*.milk,
  MilkDrop-1 syntax, no licensing constraints) and a "Preset >" button that
  cycles them - instant on-device verification without hunting for files.
  Pin the projectM checkout to a release tag in any future rebuild
  (tools/build-projectm.md updated).

## Bugfix log (v0.3.1)
- Export: after end-of-stream the encoder drain no longer aborts on the
  first TRY_AGAIN (was truncating the video); cleanup in finally is
  exception-safe so real errors are reported instead of "Failed to stop
  the muxer"; encoder falls back to 30 fps / 8 Mbps if the device rejects
  1080p60; MediaStore writes use IS_PENDING + Movies/MusicViz on Q+;
  export errors now include the exception class.
- Milkdrop: presets load with a hard cut (smooth blending is fragile on
  GLES), the preset is locked so projectM can't auto-switch away, texture
  search paths are set to the preset's folder + ./textures, load/render
  errors surface in the UI and logcat (tag projectM-jni), and PCM is fed
  as fresh samples via a ring cursor instead of overlapping windows.

## Bugfix log (v0.2)
- Controls no longer overlap system bars: enableEdgeToEdge + safeDrawing
  insets on the overlay; GL canvas stays truly fullscreen.
- Export fixed for MP3/OGG/FLAC sources: MP4 cannot carry those audio
  codecs, so audio is now transcoded to AAC-LC (192 kbps) before muxing,
  and both tracks are registered before MediaMuxer.start().
- Analyze crash fixed: offline analysis is now streaming (constant memory
  regardless of track length) and failures are caught as Throwable.
