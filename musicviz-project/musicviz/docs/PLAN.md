# MusicViz — Implementation Plan for Claude Cowork

Read this, then work top-to-bottom through /todo.md (repo root). The brief's
rule is FIRST CHECK, THEN DO: phase 0 verification gates everything.

## Ground rules
- Verify gate after every change set, split to avoid timeouts:
  1) `./gradlew ktlintFormat`  2) `ktlintCheck testDebugUnitTest`
  3) `assembleDebug`  4) `lint`   (ANDROID_HOME=/home/claude/android-sdk)
- Deliverables per round: /mnt/user-data/outputs/musicviz-project.zip and
  musicviz-debug.apk. Bump versionCode/Name each round.
- Parallel Claude sessions may edit this tree. Before committing, `git diff`
  uncommitted work you didn't write; audit + integrate, don't discard.
- GL/visual changes can't be truly verified headless: state what needs
  on-device confirmation in the round summary.

## Architecture map (where things live)
- Audio/analysis: audio/ (PcmRingBuffer, PcmTapSink), analysis/
  (FftProcessor, FeatureExtractor: beats mean+2.0σ flux + ~250ms refractory
  — the flicker fix lives here; OfflineAnalyzer, FeatureTimeline).
- Rendering: render/VisualizerRenderer (composite FX chain, uPost*
  uniforms, supersampled FBOs, transitions), render/scene/* (ShaderScene,
  ParticleSceneBase, ProjectMScene + PMBridge JNI, SceneParams: 57 fields),
  render/Lfo.kt (LfoEngine — the ADSR system should mirror its shape:
  configs list, tick(), companion apply()).
- Export: export/ (VideoExporter, FxCompositor, AudioTranscoder).
- UI: ui/ (PlayerViewModel ~1000 lines — split as it grows; VisualizerScreen,
  CustomizeDialog, SettingsDialog, BrowserDialog, MusicLibraryDialog,
  PresetStore JSON, BuiltInPresets, TextureStore, TrackLibrary).
- Shaders: res/raw/ — shared prelude (view()/pal()/grade()) in every
  *_frag.glsl; composite_frag.glsl owns screen-space + particle/milkdrop FX.

## Phase notes & risks
- P0 param matrix: write a debug screen or instrumented test that steps
  every SceneParams field per scene family and asserts a framebuffer diff;
  cheaper: manual matrix doc + targeted fixes. Milkdrop coverage means the
  composite path (applyGeo=true) — verify uPost* actually alter .milk output.
- Flicker: FeatureExtractor threshold 2.0σ→~2.5σ, refractory ~350ms, add
  smoothing pole on treble/high-mid bands; Settings→Analysis sliders wire
  through AnalysisEngine. Keep beat-phase BPM clock stable.
- MilkDrop tab: Style sheet gains a pager; move Load .milk + Textures
  buttons from their current homes; delete assets/presets/*.milk and
  BuiltInPresets milkdrop rows.
- Preset tree: PresetStore grows folder = subdirectory; JSON files move
  with the preset; migration: existing flat files → "Unsorted/".
- ADSR: new render/Envelope.kt (AdsrConfig ×2: a/d/s/r seconds, trigger
  {BEAT, GATE(band, threshold)}, targets: List<{param|lfoIndex, amount}>);
  tick in renderer next to lfoEngine; include in export loop like LFO.
- AIFF: Media3 has no AIFF extractor — write one (FORM/COMM/SSND chunks,
  PCM big-endian → 16-bit LE); register via DefaultExtractorsFactory
  extension. Test file in androidTest assets.
- Analysis DB: Room; key = content URI + size + mtime hash. Entities:
  TrackAnalysis(bpm, key, energy curve blob, sections). Key detection:
  chromagram (12-bin fold of FFT) + Krumhansl profiles.
- Intelligent switch: detectSections + energy → preset pick from same-energy
  pool; morphing = lerp SceneParams over N beats (paramFade infra exists).
- Drive: Google Sign-In + Drive REST (files.list on picked folder,
  files.get media for download; stream via ExoPlayer data source with
  auth header). Heaviest item; keep last; needs OAuth client setup.
- Style dedupe: needs user approval of the removal list first.

## MilkDrop-inspired scenes (research, 2026-07-23)
MilkDrop's core = feedback buffer + decay + parametric warp + waveform
overlays; warp-shader output persists in the feedback texture frame to
frame (MilkDrop 2 added per-pixel shaders on top of the warp mesh).
Signature families: Geiss motion-blur/plasma feedback, Flexi fluid/
kaleidoscope (gradient-driven displacement, "fluid simulation" feel),
Rovastar tunnels, martin kaleido-fractals. Our enabler: give ShaderScene a
previous-frame sampler (uPrevFrame via FBO ping-pong) — then "flow"
(Flexi-ish fluid ink), "echotunnel" (zoom/rot video-echo + waveform ring),
"smear" (Geiss-ish parametric warp over decaying trails). Dedupe: grid,
ripples, scope (covered by hexgrid, waves, ring/liss).

## Decisions log (final, from user)
- UI OVERHAUL DEFERRED until architecture done (P6 in todo.md); GUI
  design is a later pass. Crystal themes/overlays stay deferred.
- Canvas bottom bar REMOVED; icons → player panel row 4 (P6 structural).
- ADSR (2×, multi-target params-or-LFOs): attack triggered by BEAT,
  sustain driven by band ENERGY (hold while above threshold, release on
  drop) — all times/band/threshold/amounts ADJUSTABLE. LFO-target
  property: depth default, rate selectable.
- Media browser mirrors VLC: full file browser; "add folder" points at a
  real folder path; files in it are read AND analyzed; imports dedupe.
- Google Drive: ONE user-picked folder; download-or-stream; last.
- New styles: be creative, grounded in milkdrop research (above).
