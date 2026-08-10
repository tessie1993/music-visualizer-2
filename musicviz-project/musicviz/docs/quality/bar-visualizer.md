# AAA Quality Bar — Real-Time Music Visualization (Android / Kotlin + GLSL)

Clean-room external research. Sources: MilkDrop preset authoring guide (Geiss), projectM docs/LWN,
MilkDrop3 repo, Inigo Quilez articles (palettes, SDF/raymarching), Shadertoy practice, librosa/madmom
docs (superflux), Wallpaper Engine designer docs, Android developer docs (Frame Pacing/Swappy,
frame-rate API, game loops, graphics architecture), ARM Mali / Qualcomm Adreno best-practice guides,
Vulkan tile-based-rendering guide, Neon (Jeff Minter, Xbox 360) history.

Format per area: **Standard** → **Why top-tier visualizers do it** → **Checkable criterion** (something a
reviewer can verify by reading Kotlin/GLSL or running a quick test).

---

## 1. Scene/Preset Architecture (lessons from MilkDrop / projectM)

MilkDrop is the 25-year benchmark because of its *architecture*, not any single effect. Every AAA
visualizer since (projectM, MilkDrop3, Neon, Wallpaper Engine) reuses these ideas.

### 1.1 Data-driven presets, engine/content separation
- **Standard:** Visual content lives in data (preset definitions: parameters, palettes, shader
  snippets), not hardcoded into the render loop. MilkDrop presets are `.milk` files with
  `per_frame_*` / `per_vertex_*` equations plus warp/comp shader code; the engine only evaluates them.
  MilkDrop3 extends this with double presets (`.milk2`) and preset mash-ups across 5 bins.
- **Why:** Decouples content iteration from engine risk; enables dozens/hundreds of scenes, random
  transitions, and user content without rebuilds. It is why MilkDrop has ~50k community presets.
- **Criterion:** Adding a new visual scene requires zero changes to the render-loop/EGL/audio code —
  only a new scene class/file or data entry conforming to a `Scene`-like interface (init /
  per-frame update(uniforms) / draw). Grep test: no `when (sceneId)` branches inside the renderer
  core; scenes are registered, not switch-cased.

### 1.2 Per-frame uniform contract (the "audio bus")
- **Standard:** The engine computes one canonical set of per-frame values and delivers them to every
  scene as uniforms. MilkDrop's contract: `time`, `frame`, `fps`, `bass/mid/treb` (instantaneous,
  normalized so 1.0 = average loudness, ~0.7 quiet, ~1.3+ loud) and `bass_att/mid_att/treb_att`
  (time-damped versions), plus `progress` through the preset. MilkDrop3 carries q1–q64 user variables
  between per-frame and per-vertex/shader stages.
- **Why:** Normalized, *relative* audio levels make every preset work with quiet classical and
  brick-walled EDM alike; the att/instant pair gives authors both "snappy" and "smooth" signals.
- **Criterion:** There is a single `AudioUniforms`-style struct (e.g. bass/mid/treb + smoothed
  variants + beat/onset + time), computed once per frame in one place, passed to all scenes. Levels
  are normalized against a running long-term average (auto-gain), not raw FFT magnitudes. Reviewer
  check: shader code never receives raw un-normalized FFT energy as its only signal.

### 1.3 Feedback/warp pipeline
- **Standard:** MilkDrop's signature look = previous frame is re-sampled through a warp mesh
  (per-vertex equations distort UVs on a coarse grid, e.g. 32×24–64×48), slightly decayed/color-shifted,
  then new waveform/shape geometry is composited on top, then a composite ("comp") shader does final
  grading. Motion-vector-guided feedback keeps trails coherent.
- **Why:** Feedback gives "infinite" visual complexity for two texture reads per pixel — the single
  cheapest source of AAA-looking motion, especially on mobile.
- **Criterion:** Engine supports ping-pong FBO feedback with (a) decay factor strictly < 1.0 or an
  explicit fade/dim pass (no unbounded accumulation → white/black saturation), (b) warp displacement
  computed in the *vertex* stage on a coarse grid (not per-pixel dependent math where avoidable), and
  (c) linear-filtered sampling of the previous frame. Verify FBOs are recreated on surface resize and
  cleared on scene switch (no stale-frame flash).

### 1.4 Transitions and beat-driven scene changes
- **Standard:** Preset changes are (a) crossfaded/blended over ~1–4 s (projectM "smooth preset
  switching"), and (b) optionally triggered on detected beats/loud events (MilkDrop3 F8 mode:
  auto-change preset on beat). Hard cuts only as deliberate on-beat cuts.
- **Why:** Transitions are where cheap visualizers look cheap; blending two presets requires the
  architecture to run two scenes concurrently for the blend window.
- **Criterion:** A `TransitionManager` (or equivalent) exists; scene switch renders both scenes to
  offscreen targets (or blends uniforms) for a configurable duration; no visible one-frame glitch or
  uninitialized-buffer flash when switching. Beat-synced switch honors a minimum dwell time
  (e.g. ≥ 10 s) so scenes don't thrash.

---

## 2. Audio Analysis Quality (what makes visuals feel *musical*)

### 2.1 Capture path and latency
- **Standard:** End-to-end audio→photon latency under ~80–100 ms; analysis hop of 5.8–11.6 ms
  (256–512 samples @ 44.1 kHz). Android's built-in `Visualizer` API is a fallback, not the AAA path:
  it delivers 8-bit capture at limited rate/size and requires RECORD_AUDIO; top apps analyze the PCM
  they play (own decoder path) or use AudioRecord/AudioPlaybackCapture with a proper float pipeline.
- **Why:** Humans notice visual-beat misalignment above ~100 ms; 8-bit magnitudes quantize quiet
  passages into staircase jitter.
- **Criterion:** Audio thread produces analysis frames at ≥ 86 Hz (hop ≤ 512 @ 44.1 kHz) into a
  lock-free/atomic snapshot the GL thread reads (no shared mutable arrays without synchronization; no
  allocation per audio callback). If `Visualizer` API is used, code must compensate its latency and
  document the quality ceiling.

### 2.2 FFT discipline
- **Standard:** Windowed STFT: Hann (or Blackman-Harris) window applied before FFT — never a
  rectangular window; FFT size 1024–2048 @ 44.1 kHz with ≥ 50–75% overlap; magnitudes converted to a
  log/dB-like scale before display mapping.
- **Why:** Rectangular windows cause spectral leakage → bins flicker even on steady tones; overlap
  restores temporal resolution lost to window length; linear amplitude wastes 90% of visual dynamic
  range on the loudest sounds (perception is logarithmic).
- **Criterion:** Grep for the window function: a precomputed Hann table multiplied into the frame
  before FFT. Magnitude path includes `log`/dB (e.g. `20*log10(mag + eps)` or `log1p`) or an explicit
  power-law (`mag^0.3..0.5`) before it reaches shaders. FFT is done with a real-FFT (RFFT), not a
  naive DFT loop, and window+FFT buffers are preallocated.

### 2.3 Log/mel frequency mapping
- **Standard:** Display bands are spaced logarithmically or on a mel scale (Wallpaper Engine: 64
  bands per channel; typical AAA: 24–128 log-spaced bands 30 Hz–16 kHz), each band integrating
  multiple FFT bins with triangular or equal-log-width weighting; per-band normalization/tilt
  compensates the natural 1/f spectral slope (pink-noise equalization) so highs aren't permanently dead.
- **Why:** Linear bin mapping puts 90% of the musically interesting range (bass/mids) into the first
  few pixels; without spectral tilt correction, treble bars barely move on real music.
- **Criterion:** Band-edge computation uses `exp/log` or mel formula (`2595*log10(1+f/700)`), not
  `binIndex * k`. Low bands aggregate ≥ 1 bin, high bands aggregate many. There is an explicit
  weighting/equalization curve (comment or constant table). Unit test exists mapping known sine
  frequencies to expected bands.

### 2.4 Onset/beat detection
- **Standard:** Onset strength via spectral flux — half-wave-rectified positive difference of
  successive (log-)magnitude spectra — at minimum; state of the art is **SuperFlux** (Böck/Widmer:
  max-filter across frequency before differencing, suppressing vibrato/tremolo false positives; the
  reference implementations are librosa `onset_strength(..., max_size>1)` and madmom). Peak-picking
  uses an *adaptive* threshold (moving mean/median of the onset envelope + delta) with a refractory
  period (~100 ms minimum inter-onset gap). Separate low-band flux for "kick" detection; MilkDrop-style
  per-band running average + variance for "significant beat" classification. Optional tempo tracking
  (autocorrelation/comb-filter over the onset envelope) for phase-locked pulses.
- **Why:** Raw energy thresholds fire on sustained loudness and miss soft transients; flux measures
  *change*, which is what eyes read as "the beat"; fixed thresholds break across genres/volumes.
- **Criterion:** Beat code computes rectified spectral difference (look for `max(0, cur - prev)`
  summed over bins), compares against an adaptive statistic (running mean + k·stddev or moving
  median), and enforces a minimum re-trigger interval. Hard-coded absolute magnitude thresholds
  (`if (bass > 0.8)`) without normalization = review failure. Bonus bar: frequency max-filter
  (SuperFlux) or multi-band flux.

### 2.5 Smoothing: attack/release envelopes (the #1 "feel" differentiator)
- **Standard:** Every visual-driving signal passes through an asymmetric envelope follower: fast
  attack (0–50 ms; often instantaneous for beat flashes), slow release (150–500 ms exponential
  decay). Coefficients derived from *time constants converted per-frame/per-hop*
  (`k = exp(-dt/tau)`), so behavior is identical at 60/90/120 Hz. Long-term AGC (multi-second
  running average, as in MilkDrop's normalization to 1.0) sits underneath. Spatial smoothing across
  adjacent bars (small kernel) removes comb jitter in bar displays; Wallpaper Engine explicitly
  recommends interpolating between audio callbacks.
- **Why:** Direct FFT→pixel mapping looks like noise (jitter at hop rate); symmetric smoothing looks
  laggy and "drunk". Fast-attack/slow-release preserves transient snap while decays read as musical
  sustain — this is the difference users describe as "it dances" vs "it flickers".
- **Criterion:** Grep for the two-coefficient pattern:
  `env = if (x > env) mix(x, env, attackK) else mix(x, env, releaseK)` with attackK/releaseK computed
  from `exp(-dt/tau)` (or documented equivalents), NOT frame-rate-dependent constants like
  `env = env*0.9 + x*0.1` with no dt. Beat pulses use a decaying envelope (`pulse *= exp(-dt/tau)`),
  not a boolean toggled for N frames. Signals are also interpolated between audio hops on the render
  thread (no staircase when render Hz > hop Hz... and no aliasing when lower).

---

## 3. Visual Design Standards (Shadertoy / demoscene / commercial references)

### 3.1 Palette discipline
- **Standard:** Colors come from designed palettes, not per-channel `sin(time)` chaos. The demoscene
  standard is Inigo Quilez's cosine palette `col(t) = a + b*cos(2π(c*t + d))` with curated a/b/c/d
  sets; alternatives: small ramp textures / hardcoded 3–5 stop gradients. Top Shadertoy work grades
  in roughly-linear space then applies a tone curve; hue count per scene is limited (2–3 hue
  families + accent). Beat/energy modulates brightness/saturation *within* the palette rather than
  rotating hue randomly. (On GPU, evaluating `cos` is faster than any LUT approximation — IQ.)
- **Why:** Coherent palettes are the most visible single difference between amateur RGB soup and
  Apple-Music/Neon-grade output; Neon and Apple's visualizers are recognizable precisely because of
  restrained, art-directed color.
- **Criterion:** A palette function/uniform set exists and is shared across scenes; scene colors are
  `palette(t)` calls or gradient lookups, not independent `vec3(sin(t), sin(t*1.3), ...)`. Palettes
  are defined in one place (data), and at least dark-background contrast is considered (visualizers
  live on black; verify no full-white flashes > small area without intent — photosensitivity).

### 3.2 Raymarching / procedural discipline
- **Standard (from top-rated Shadertoy practice):** Bounded march loops (fixed max steps, typically
  48–100 on mobile, with early-exit distance and far-plane break); SDFs kept Lipschitz-correct
  (no distance over-estimation → surface holes); normals via tetrahedron 4-tap trick; domain
  repetition/symmetry instead of object counts; `smoothmin` for organic blends; cheap fake AO/glow
  from step count or distance; resolution-independent coordinates
  (`uv = (2*fragCoord - res)/min(res.x,res.y)`).
- **Why:** Raymarching is per-pixel-expensive; discipline is what makes it feasible at all on a
  phone. Step-count glow and distance fog are free-ish and read as "production value".
- **Criterion:** Any march loop has a compile-time constant iteration cap and an epsilon/far
  break; no `while(true)`. Full-screen raymarched scenes render at reduced internal resolution
  (see 4.6) with an upscale. Aspect ratio handled in one shared helper (no stretched circles in any
  orientation — test portrait and landscape).

### 3.3 Post-processing stack
- **Standard:** AAA look = base scene + feedback trails + bloom/glow (separable blur on a
  downsampled bright-pass, 1/4 res), subtle chromatic offset / vignette / film grain or dither, and a
  final tone-map/gamma step. Apple Music's visualizer aesthetic is essentially soft-focus bloom +
  slow camera drift + palette grading; Neon layers analog-style feedback with symmetric geometry.
  Dithering (e.g. blue-noise or hash) before writing to 8-bit prevents banding in dark gradients —
  critical on OLED phones.
- **Why:** Glow sells "light" (visualizers are light-synthesizers — Minter's term); banding on dark
  gradients is the most common mobile artifact reviewers see.
- **Criterion:** Bloom implemented as downsampled separable passes (never a NxN single-pass kernel at
  full res); a dither/grain term (~1/255 amplitude hash) added before final write; gamma handled
  explicitly (document whether the pipeline is linear or gamma-space; no double-gamma). Vignette and
  grain amplitudes are constants, not magic inline numbers.

### 3.4 Motion quality
- **Standard:** All animation is time-based (`uTime`, dt), never frame-count-based; idle state (no
  audio) still shows slow ambient motion (Neon and Wallpaper Engine both idle gracefully); camera/
  parameter drift uses smooth noise or LFOs with distinct primes so loops aren't obvious; beat
  impulses drive spring/decay systems (position kicks, zoom pulses) rather than instant teleports.
- **Why:** Frame-count animation changes speed with fps/refresh rate (60 vs 120 Hz phones);
  dead-black idle screens read as broken.
- **Criterion:** No `frameCount * speed` animation without dt; time uniforms are `float` seconds
  derived from a monotonic clock, wrapped/offset to avoid fp32 precision degradation after hours
  (e.g. `time = (now % 3600s)` with continuity handling, or double-precision accumulation on CPU;
  `sin(uTime*k)` with uTime unbounded for days = review flag). Verify a defined no-audio behavior.

---

## 4. Android Rendering Architecture

### 4.1 Surface choice
- **Standard:** `SurfaceView` (or `GLSurfaceView`, or SurfaceView + custom EGL thread) for the main
  visualizer: it composes as a separate layer via SurfaceFlinger, avoiding an extra GPU copy.
  `TextureView` only when View-hierarchy transforms/animation of the visualizer itself are required —
  it costs an extra composition copy, more memory, and worse frame timing. For live wallpapers, the
  `WallpaperService.Engine` surface with a dedicated EGL thread. On modern minSdk,
  `SurfaceControl`/`ASurfaceControl` + `setFrameRate` for refresh-rate hints.
- **Why:** TextureView's extra copy costs bandwidth (the scarcest mobile resource) and adds latency;
  SurfaceView content bypasses the view hierarchy.
- **Criterion:** Main render target is SurfaceView/wallpaper surface unless a comment justifies
  TextureView. If `GLSurfaceView` is used: `setRenderMode(RENDERMODE_CONTINUOUSLY)` only while
  visible and audio playing; `onPause()/onResume()` forwarded from lifecycle. Custom EGL: verify the
  render thread owns the context exclusively.

### 4.2 EGL context management
- **Standard:** Robust handling of the full surface lifecycle: `surfaceCreated/Changed/Destroyed`,
  context loss (`EGL_CONTEXT_LOST`), and preserve-on-pause semantics
  (`setPreserveEGLContextOnPause(true)` where GLSurfaceView is used — but all GL resources must
  still be recreatable from scratch). Config chosen explicitly (RGBA8888, ES 3.0+ with ES2 fallback
  or documented minimum); `eglSwapInterval`/Swappy governs pacing. No GL calls off the GL thread.
- **Why:** Context loss on Android is routine (backgrounding, display off, wallpaper preview vs
  home-screen instances); AAA apps never show black/garbage after resume.
- **Criterion:** All GL resource creation lives in an `onSurfaceCreated`-path idempotent
  `createResources()`; textures/FBOs/programs are recreated on context recreation (test: rotate,
  background/foreground, toggle screen off/on). Shader compile/link results are checked with
  `glGetShaderiv/glGetProgramiv` and errors logged with the info log + shader source line context —
  silent compile failure is a review blocker. FBO completeness checked
  (`glCheckFramebufferStatus`). No `glGetError` polling per draw call in release hot loops (it
  stalls some drivers) — use it in debug builds via a flag.

### 4.3 Frame pacing
- **Standard:** Frame loop driven by `Choreographer` vsync callbacks (or the Android Frame Pacing
  library, Swappy, which wraps Choreographer + presentation timestamps + swap-interval auto-mode);
  simulation advances by *measured* dt clamped to sane bounds, not assumed 16.6 ms. Never render
  free-running against `eglSwapBuffers` back-pressure alone; on 90/120 Hz devices either pace to a
  chosen rate via `Surface.setFrameRate()` (API 30+) / Swappy swap interval, or run native rate with
  dt-correct animation. Target: 99th-percentile frame time within budget — Android vitals flow
  ("slow sessions") judges apps on frame-time distribution, not average fps.
- **Why:** Uneven frame *pacing* is more visible than lower-but-steady fps; SurfaceFlinger buffer
  queues stall unpredictably without vsync alignment, and visualizers (continuous animation) expose
  every hiccup.
- **Criterion:** `Choreographer.postFrameCallback` (or Swappy) is the tick source; dt =
  `frameTimeNanos` deltas, clamped (e.g. to [1 ms, 100 ms]) to survive hitches without physics
  explosions. Frame-time stats (avg + p95/p99) are recorded in debug HUD or logs. There is an
  explicit frame-rate policy for high-refresh displays (either setFrameRate hint or documented
  native-rate support). Live wallpaper: rendering pauses within one frame of
  `onVisibilityChanged(false)`.

### 4.4 Thermal + battery discipline
- **Standard:** Sustained-performance mindset: the app monitors `PowerManager.getThermalHeadroom()` /
  `addThermalStatusListener` (or ADPF hints) and sheds load *before* the OS throttles — in order:
  lower internal render scale, reduce post-processing passes, cap fps 60→30. Live wallpapers idle at
  0 fps when not visible and use reduced rate (24–30 fps) as default; battery-saver mode respected.
  A visualizer that pins the GPU at 100% for 20 minutes on a phone is a defective product regardless
  of visuals.
- **Why:** Thermal throttling turns a beautiful first 5 minutes into a 23 fps slideshow at minute 15;
  reviewers/users measure by the 30-minute mark, and sustained heat measurably degrades batteries.
- **Criterion:** Code contains a quality-scaler with at least 2 levers (resolution scale, fps cap,
  effect tier) reacting to thermal status or measured frame-time trend; wallpaper engine stops the
  Choreographer callback (not just skips drawing) when invisible; no wakelocks. Testable: 20-minute
  soak run keeps p95 frame time in budget without device exceeding THERMAL_STATUS_SEVERE.

### 4.5 Live-wallpaper-specific constraints
- **Standard:** Multiple simultaneous `Engine` instances (preview + home) supported without shared
  mutable GL state; visibility-driven start/stop; scroll offsets (`onOffsetsChanged`) optionally
  parallax the scene; ambient/AOD never rendered to; audio permission fallback (wallpaper without
  RECORD_AUDIO shows ambient non-reactive mode rather than dying); config changes (density, screen
  size, foldables) handled by full resize path.
- **Criterion:** Engine instances own their EGL context/thread each; a kill test (revoke mic
  permission while running) degrades gracefully; preview and set-wallpaper flows both render
  correctly on first frame.

### 4.6 Resolution and bandwidth strategy
- **Standard:** Render internal scenes at a scaled resolution (commonly 0.5–0.75× on phones, and 1/4
  res for blur/bloom chains) and upscale with linear filtering — indistinguishable for glow-heavy
  content, halves-to-quarters bandwidth/ALU. Full-res only for final composite + any crisp UI/text.
- **Criterion:** FBO sizes are `surface * renderScale` with renderScale a tunable; bloom chain
  explicitly downsamples. No full-resolution intermediate that is only ever blurred.

---

## 5. Shader Code Quality Standards (GLSL ES, mobile)

### 5.1 Precision qualifiers
- **Standard:** Every fragment shader declares default precision explicitly; `mediump` (FP16) is the
  default working precision on mobile — it doubles ALU throughput and halves register pressure on
  Mali/Adreno; `highp` reserved for: texture coordinates fed into large-texture lookups, time
  accumulators, positions in raymarching where FP16 range (±65504, ~3 decimal digits) breaks.
  Samplers get precision too (`mediump sampler2D`; highp samplers can be half speed on Mali).
  Treat mediump as a hint, not a guarantee — desktop GLES emulators run it as FP32, so FP16
  artifacts must be tested on-device.
- **Criterion:** Each .frag has `precision mediump float;` (or documented highp default with
  justification); grep for un-annotated `highp` scattered ad hoc. Time-dependent math documents its
  precision plan (wrapped time uniform, see 3.4). Any UV math for feedback sampling on large
  textures is highp (FP16 UV on a 2000px texture = 0.5-texel error → visible feedback drift/smear).

### 5.2 Branching discipline
- **Standard:** Uniform-based branches are fine (all invocations take same path; compilers often
  specialize); avoid *divergent* per-pixel branches in hot loops; prefer `mix/step/smoothstep`/
  `clamp` arithmetic selects for small either/or computation; but do NOT compute-both-and-mix when
  one side is expensive (e.g. texture fetch or march) — a coherent branch is then correct. Loop
  bounds constant or uniform-bounded with early break; no unbounded loops. Scene variants selected
  by *separate compiled programs* (or #ifdef specialization at build time), not a mega-shader with
  `if (uSceneMode == 7)` chains.
- **Why:** Mobile GPUs execute warps/quads in lockstep; divergence serializes both paths; mega-shader
  uber-branching also inflates register usage, cutting occupancy even on the cheap path.
- **Criterion:** No `if (uMode == n)` ladders in fragment shaders (use preprocessor variants or
  separate programs); marching/blur loops have literal-constant max counts; small selects use
  `mix`/`step`. Reviewer may allow measured exceptions with a comment citing a profile.

### 5.3 Texture sampling hygiene
- **Standard:** UVs computed in the vertex shader (varyings) whenever they don't depend on fragment
  data — enables the hardware prefetch/tiler path; dependent (computed-in-fragment) texture reads are
  minimized and never chained (read A to compute coords for B is the classic mobile stall);
  sampler count small; mipmaps ON for any texture that's minified (no mips = cache-thrashing and
  shimmer); repeated samples grouped/batched; feedback texture sampled with linear filter, clamped
  edges (or explicit border handling) to avoid edge streaks; no sRGB confusion (declare and document).
- **Criterion:** Grep fragment shaders for `texture(...)` whose coords are arithmetic on another
  `texture(...)` result — each instance needs a justification comment. Fullscreen passes get UVs as
  varyings from the vertex stage. Every generated texture sets min/mag filters and wrap modes
  explicitly (no reliance on defaults). Bloom/blur taps use precomputed offsets (uniform array or
  vertex-stage), not per-fragment trig to build offsets.

### 5.4 Tile-based renderer (TBR/TBDR) pitfalls
- **Standard:** Mali/Adreno/PowerVR render in tiles; the expensive resource is memory bandwidth
  between GPU and DRAM. Rules: begin each render pass with a full `glClear` (or
  `glInvalidateFramebuffer`/`glDiscardFramebufferEXT` semantics) so the driver can skip loading the
  old contents into tile memory; invalidate depth/stencil after the pass if not needed; never
  interleave render targets mid-frame ping-ponging more than necessary (each FBO switch = tile
  flush); no mid-frame `glReadPixels`/`glCopyTexImage`/occlusion feedback on the current target
  (full pipeline flush); avoid `glFlush/glFinish` in the loop.
- **Why:** A logically-cheap pass that loads/stores full-res attachments needlessly can double
  bandwidth cost; on a visualizer running all day this is watts and heat.
- **Criterion:** Every FBO bind in the frame is followed by clear or documented full-screen
  overwrite; `glInvalidateFramebuffer` used on transient attachments (ES3); pass count per frame is
  enumerable from code and matches a documented frame graph (e.g. scene → bloom down ×N → bloom up
  ×N → composite ≤ ~6–8 passes); no readbacks in the render loop.

### 5.5 Overdraw and blending
- **Standard:** Alpha blending disables early-Z/hidden-surface-removal benefits; layered additive
  effects (particles, glows) are the main overdraw source. Budget overdraw (e.g. ≤ 3–4× average for
  particle scenes); render opaque backgrounds without blending; keep particle quads tight to the
  sprite (or use additive point sprites), sort/merge passes; on Mali, split large translucent
  layers so opaque parts stay opaque.
- **Criterion:** Blending explicitly disabled (`glDisable(GL_BLEND)`) for fullscreen opaque passes;
  particle counts are constants with documented budgets; debug build offers an overdraw/complexity
  visualization or at least particle-count HUD.

### 5.6 General GLSL hygiene
- **Standard:** No `discard` in hot fullscreen passes (kills early-Z on many mobile GPUs); constants
  named (`const float BLOOM_THRESHOLD = ...`) not magic; helper functions shared via an included
  common block (single source for palette/noise/tonemap — no 5 divergent copies of `hash()`);
  uniforms set via cached locations (or UBOs in ES3), never `glGetUniformLocation` per frame;
  vector ops written vectorized (`dot`, `mix`) rather than scalar-by-scalar; noise functions chosen
  deliberately (hash-based value/simplex; no `fract(sin(dot(...)))` in precision-critical paths on
  mediump — it breaks on FP16, a classic mobile artifact).
- **Criterion:** A shared `common.glsl` (or Kotlin-side include/concat system) exists; grep shows one
  definition of hash/noise/palette/tonemap each; uniform locations cached in Kotlin at link time;
  `fract(sin(...))`-style hashes either absent or forced highp with an on-device screenshot test.
  Shaders are versioned (`#version 300 es`) and asserts/tests cover compile success of every
  shipped shader on CI (even via a desktop GLES harness) plus at least one on-device smoke render.

---

## 6. Commercial-Reference Bar (what "AAA" looks like to a user)

- **Neon (Jeff Minter, Xbox 360 default visualizer; lineage: VLM on Atari Jaguar):** art-directed
  light-synthesis: feedback, symmetry, restrained palettes, and *interactivity* (players could steer
  it). Bar: the visualizer is beautiful with zero audio and rewarding to watch for minutes, not
  seconds; supports at least parameter-level user steering (touch interaction / theme choice).
- **Apple Music / iTunes visualizer lineage:** soft bloom, slow drift, palette-graded; never
  strobing chaos; instantly legible album/track context. Bar: default preset must be tasteful at
  first launch — the out-of-box scene is the product.
- **MilkDrop/projectM:** breadth via presets + normalized audio contract (works on any music at any
  volume). Bar: play a quiet acoustic track and a loud EDM track — both must drive the full visual
  dynamic range (AGC proof).
- **Wallpaper Engine:** 64-band spectra with user-visible smoothing controls and fps limits;
  graceful on battery. Bar: expose reactivity/intensity settings; respect system power state.
- **Photosensitivity:** commercial console/TV products constrain full-field flash rates (avoid > 3
  flashes/s of large-area high-contrast strobing, per WCAG 2.3.1/ISO guidance). Bar: beat flash
  implementations clamp area/luminance delta or provide a reduced-flash accessibility toggle.

---

## 7. Condensed Reviewer Checklist (apply to Kotlin/GLSL codebase)

| # | Check | Pass condition |
|---|-------|----------------|
| 1 | Scene abstraction | New scene = new file/data only; no renderer-core edits |
| 2 | Audio uniform contract | Single normalized AudioUniforms struct; AGC vs long-term average |
| 3 | Window + log | Hann window pre-FFT; log/dB or power-law magnitude mapping |
| 4 | Band mapping | Log/mel band edges; spectral tilt equalization; unit-tested |
| 5 | Onset detection | Rectified spectral flux (+ max-filter bonus); adaptive threshold; refractory period |
| 6 | Envelopes | Asymmetric attack/release from `exp(-dt/tau)`; frame-rate independent |
| 7 | Palettes | Central palette system (IQ-cosine or gradient data); no per-channel sin RGB |
| 8 | Feedback | Ping-pong FBOs, decay < 1, resize-safe, no stale-frame flash |
| 9 | Post stack | Downsampled bloom; dither before 8-bit write; explicit gamma story |
| 10 | Loop caps | All shader loops literal-bounded; no divergent mode ladders |
| 11 | Precision | `precision mediump float` default; highp only where justified (UVs on big textures, time) |
| 12 | Sampling | No chained dependent reads; UVs from vertex stage in fullscreen passes; filters/wraps explicit |
| 13 | TBR hygiene | Clear/invalidate per pass; no mid-frame readbacks; bounded pass count |
| 14 | Surface/EGL | SurfaceView-class surface; context-loss recovery tested; compile/link checked+logged |
| 15 | Pacing | Choreographer/Swappy tick; measured clamped dt; p95/p99 frame-time visibility; setFrameRate policy |
| 16 | Thermal | Quality scaler (res/fps/effects) wired to thermal headroom or frame-time trend |
| 17 | Wallpaper lifecycle | Renders stop on invisibility; multi-engine safe; permission-loss fallback |
| 18 | Time hygiene | dt-based animation; wrapped time uniform; no fp32 blowup after hours |
| 19 | Idle behavior | Defined ambient mode with no audio; both quiet and loud tracks span full visual range |
| 20 | Safety | Flash-rate/area limits or reduced-motion/flash toggle |

---

### Key sources
- MilkDrop preset authoring guide (Geiss): geisswerks.com/milkdrop/milkdrop_preset_authoring.html
- projectM overview: lwn.net/Articles/750152/
- MilkDrop3: github.com/milkdrop2077/MilkDrop3
- IQ palettes: iquilezles.org/articles/palettes/ (and SDF/raymarching article series)
- librosa SuperFlux example: librosa.org/doc/main/auto_examples/plot_superflux.html ; madmom onsets docs
- Wallpaper Engine audio docs: docs.wallpaperengine.io/en/web/audio/visualizer.html
- Android Frame Pacing (Swappy): developer.android.com/games/sdk/frame-pacing ; game loops: developer.android.com/games/develop/gameloops
- Android frame-rate API: developer.android.com/media/optimize/performance/frame-rate ; high-refresh blog: android-developers.googleblog.com/2020/04/high-refresh-rate-rendering-on-android.html
- Android slow sessions / vitals: developer.android.com/games/optimize/vitals/slow-session
- Vulkan tile-based rendering best practices: docs.vulkan.org/guide/latest/tile_based_rendering_best_practices.html
- Adreno best practices: docs.qualcomm.com (Snapdragon Game Toolkit) ; Mali guide: github.com/azhirnov/cpu-gpu-arch (ARM-Mali_Guide.md), ARM community blogs
- Neon (music visualization): en.wikipedia.org/wiki/Neon_(music_visualization) ; Virtual Light Machine: en.wikipedia.org/wiki/Virtual_Light_Machine
