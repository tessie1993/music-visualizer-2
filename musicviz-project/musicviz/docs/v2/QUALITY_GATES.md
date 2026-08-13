# MusicViz 2.0 — extracted quality gates

Provenance record for `MASTER_PLAN.md` Appendix E.2.

The repository previously carried a seven-file quality corpus under `docs/quality/`.
Those files mixed three different things: externally-researched measurable
criteria, one-off review verdicts, and an execution queue. Only the first
category survives. This file holds it, with the **external** source cited —
never the repository's own review prose.

What was deliberately dropped, and why:

- **The "unanimously wowed critic panel" exit condition** (`QUALITY_BAR.md`).
  Not a measurable gate. `MASTER_PLAN.md` §5, Phase 11 and the Definition of
  Done supersede it.
- **`PRODUCT_REVIEW.md` / `BLUEPRINT_REVIEW.md` / `FEATURE_TRIAGE.md` verdicts
  and counts** ("7 implemented, 24 partial, 60 absent"). These are dated
  snapshots against a blueprint that 2.0 replaces. Their file:line evidence was
  accurate for its SHA and is preserved in Git history; re-deriving current
  state is Phase 0.1's inventory job, not a document to trust.
- **`GAUNTLET_STATE.md` / `GAUNTLET_BACKLOG.md`.** Obsolete queues; deleted
  outright per E.1.

Criteria already stated in `MASTER_PLAN.md` are **not** duplicated here. This
file only carries criteria the master plan does not already own.

---

## V-1 Audio analysis quality

Source: MilkDrop preset authoring guide (Geiss); librosa/madmom SuperFlux docs;
Wallpaper Engine audio docs.

| ID | Criterion | How it is checked |
|---|---|---|
| V-1.1 | Hann (or Blackman-Harris) window applied before every FFT; never rectangular. Precomputed table, not per-hop construction. | Read the window table construction; assert it is allocated at config time. |
| V-1.2 | Magnitudes reach scenes through a log/dB or power-law mapping (`20*log10(mag+eps)`, `log1p`, or `mag^0.3..0.5`), never linear amplitude. | Unit test on known input. |
| V-1.3 | Band edges computed by `exp`/`log` or mel formula (`2595*log10(1+f/700)`), not `binIndex * k`. Low bands aggregate ≥1 bin, high bands aggregate many. | Unit test mapping known sine frequencies to expected bands. |
| V-1.4 | An explicit spectral-tilt/pink-noise equalization curve exists so treble is not permanently dead. | Named constant table or documented curve. |
| V-1.5 | Onset detection uses rectified spectral flux (`max(0, cur - prev)` summed over bins) against an **adaptive** statistic (running mean + k·σ, or moving median), with a refractory period ≥ ~100 ms. Hard-coded absolute thresholds (`if (bass > 0.8)`) fail. | Fixture tests in Phase 3.1. |
| V-1.6 | Every visual-driving signal passes an asymmetric attack/release envelope with coefficients from `exp(-dt/tau)` — frame-rate independent. `env = env*0.9 + x*0.1` with no `dt` fails. | Test identical behavior at 60/90/120 Hz. |
| V-1.7 | Levels are normalized against a running long-term average (auto-gain). A quiet acoustic track and a loud EDM track must both span the full visual dynamic range. | AGC fixture pair. |

**Bonus, not required:** SuperFlux frequency max-filter before differencing.

## V-2 Shader and GL discipline

Source: Inigo Quilez articles; Vulkan tile-based-rendering best practices; ARM
Mali and Qualcomm Adreno best-practice guides; Android graphics docs.

| ID | Criterion | How it is checked |
|---|---|---|
| V-2.1 | Every fragment shader declares default precision explicitly. `mediump` is the default working precision; `highp` reserved for large-texture UVs, time accumulators, and raymarch positions — each justified. | Source scan plus on-device artifact check (desktop GLES emulators run mediump as FP32, so FP16 breakage is device-only). |
| V-2.2 | No `if (uMode == n)` ladders in fragment shaders. Scene variants are separate programs or `#ifdef` specializations. | Source scan. |
| V-2.3 | Every march/blur loop has a literal-constant iteration cap and an epsilon/far break. No `while(true)`. | Source scan. |
| V-2.4 | No chained dependent texture reads (sampling A to compute coordinates for B). Each instance needs a justification comment. | Source scan. |
| V-2.5 | Fullscreen-pass UVs come from the vertex stage as varyings. Every generated texture sets min/mag filter and wrap explicitly — no reliance on defaults. | Source scan. |
| V-2.6 | Every FBO bind is followed by a clear or a documented full-screen overwrite; `glInvalidateFramebuffer` used on transient attachments. Pass count per frame is enumerable and matches a documented frame graph. | Frame-graph doc plus source scan. |
| V-2.7 | One definition each of `hash`/`noise`/`palette`/`tonemap`, shared through a common include. | Source scan; the repo already has a `SharedShaderPreludeTest`. |
| V-2.8 | `fract(sin(dot(...)))`-style hashes are absent, or forced `highp` with an on-device screenshot test — they break on FP16. | Source scan plus device check. |
| V-2.9 | Shader compile/link results are checked and errors logged with the info log. Silent compile failure is a blocker. | Already satisfied by `GlUtil.ShaderCompileException`; keep the gate. |
| V-2.10 | Dither/grain (~1/255 amplitude) added before any 8-bit write, to prevent OLED banding in dark gradients. | Source scan. |
| V-2.11 | Bloom is downsampled separable passes, never an NxN single-pass kernel at full resolution. | Source scan. |
| V-2.12 | Blending explicitly disabled for fullscreen opaque passes. | Source scan. |

## V-3 Frame pacing and thermal

Source: Android Frame Pacing (Swappy) docs; Android frame-rate API; Android
vitals "slow sessions" guidance.

| ID | Criterion | How it is checked |
|---|---|---|
| V-3.1 | The tick source is `Choreographer` (or Swappy), not free-running against `eglSwapBuffers` back-pressure. | Source read. |
| V-3.2 | `dt` is measured from `frameTimeNanos` deltas and **clamped** (e.g. to [1 ms, 100 ms]) so a hitch cannot explode the simulation. | Unit test on the clamp. |
| V-3.3 | There is an explicit high-refresh policy: either a `Surface.setFrameRate()` hint or documented native-rate support. | Source read. |
| V-3.4 | The quality scaler has at least two levers (render scale, fps cap, effect tier) driven by thermal status or a frame-time trend. | Phase 11.1 soak. |
| V-3.5 | Wallpaper rendering stops the Choreographer callback — not merely skips drawing — within one frame of `onVisibilityChanged(false)`. No wakelocks. | Lifecycle test. |
| V-3.6 | Time uniforms are wrapped to avoid fp32 degradation over a multi-day wallpaper session. `sin(uTime*k)` with unbounded `uTime` fails. | Source scan; the repo already has a `TIME_WRAP` convention. |
| V-3.7 | A defined no-audio idle behavior exists. A dead-black idle screen reads as broken. | Visual check per scene. |

## V-4 Photosensitivity

Source: WCAG 2.3.1 / ISO flash guidance, as applied by commercial console and TV
products.

| ID | Criterion |
|---|---|
| V-4.1 | Avoid more than 3 flashes per second of large-area, high-contrast content. Beat-flash implementations clamp area **or** luminance delta, or the reduced-flash toggle is active. |

`MASTER_PLAN.md` Phase 0.3 and 4.3 own the implementation and the offline
validator; V-4.1 is the numeric threshold they validate against. The product
must not claim formal medical safety.

## V-5 Engineering criteria adopted for 2.0

Source: google/nowinandroid; official Android architecture and coroutines
guidance; OxygenCobalt/Auxio.

Adopted only where they do not conflict with the master plan's real-time
exceptions.

| ID | Criterion |
|---|---|
| V-5.1 | Zero `GlobalScope`; no ad-hoc `CoroutineScope(Dispatchers.X)` inside classes that already have a lifecycle scope; no `runBlocking` in production. |
| V-5.2 | `CancellationException` is never swallowed — any bare `catch (e: Exception)` in a coroutine rethrows it. |
| V-5.3 | Dispatchers are injected, not hardcoded at call sites. CPU-heavy analysis runs on an injected `Default`; file/DB work on an injected `IO`. |
| V-5.4 | ViewModels expose immutable `StateFlow` only; no public `MutableStateFlow`, no event-channel-to-UI. |
| V-5.5 | Per-frame values (audio amplitudes, playback position) are read inside `Canvas`/`drawBehind`/`graphicsLayer` lambdas — never passed as composable parameters that recompose the tree every frame. |
| V-5.6 | Every lazy-list `items` call has a stable unique `key`. |
| V-5.7 | Fakes over mocks at repository/data-source seams. |
| V-5.8 | Tests use `runTest` and scheduler-controlled virtual time — no `Thread.sleep`, no real delays. |

### Explicitly NOT adopted

- **"Files < ~700/800 lines" as a hard limit.** Useful as a smell, not a gate.
  The master plan targets specific God-classes (`VisualizerRenderer`,
  `PlayerViewModel`) by responsibility, which is the sharper instrument.
- **Hilt/Koin.** `MASTER_PLAN.md` §1.1 explicitly forbids adding a DI framework
  during the migration; the hand-written `MusicVizGraph` is the decision.
- **Detekt as a required gate.** The master plan's §5 loop is
  `testDebugUnitTest` / `ktlintCheck` / `lintDebug` / `assembleDebug`.
- **"Behavior over source-text assertions" as an absolute.** This repository
  deliberately uses source-text gates where no behavioral expression exists;
  §5 governs when they may be replaced.

---

## Sources

- MilkDrop preset authoring guide — geisswerks.com/milkdrop/milkdrop_preset_authoring.html
- projectM overview — lwn.net/Articles/750152/
- Inigo Quilez, palettes and SDF/raymarching — iquilezles.org/articles/palettes/
- librosa SuperFlux — librosa.org/doc/main/auto_examples/plot_superflux.html
- Wallpaper Engine audio — docs.wallpaperengine.io/en/web/audio/visualizer.html
- Android Frame Pacing — developer.android.com/games/sdk/frame-pacing
- Android frame-rate API — developer.android.com/media/optimize/performance/frame-rate
- Android slow sessions — developer.android.com/games/optimize/vitals/slow-session
- Vulkan tile-based rendering — docs.vulkan.org/guide/latest/tile_based_rendering_best_practices.html
- google/nowinandroid — github.com/android/nowinandroid
- Android architecture recommendations — developer.android.com/topic/architecture/recommendations
- Android coroutines best practices — developer.android.com/kotlin/coroutines/coroutines-best-practices
- OxygenCobalt/Auxio — github.com/OxygenCobalt/Auxio

These URLs are carried from the deleted corpus as **claimed** provenance. They
were not re-fetched during extraction; Phase 3.1 and Phase 11.3 re-verify any
source before it justifies a shipped algorithm or a licence decision.
