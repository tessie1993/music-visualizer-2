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

## Bugfix log (v0.2)
- Controls no longer overlap system bars: enableEdgeToEdge + safeDrawing
  insets on the overlay; GL canvas stays truly fullscreen.
- Export fixed for MP3/OGG/FLAC sources: MP4 cannot carry those audio
  codecs, so audio is now transcoded to AAC-LC (192 kbps) before muxing,
  and both tracks are registered before MediaMuxer.start().
- Analyze crash fixed: offline analysis is now streaming (constant memory
  regardless of track length) and failures are caught as Throwable.
