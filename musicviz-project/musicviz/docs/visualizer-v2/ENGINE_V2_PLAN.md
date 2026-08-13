# Engine V2 — a new audio and visual engine

Status: plan. No code written. Baseline is PR #95 (`05aca01`).

This supersedes the earlier "Audio Visualizer V2" draft where they disagree. The two
substantive changes are recorded in §1.

---

## 1. What changed from the earlier draft, and why

**The earlier plan kept everything inside `:app` as `dev.musicviz.engine.*` packages and
deferred module extraction.** That is wrong for this codebase, for a reason that is
measurable rather than stylistic:

- `settings.gradle.kts` contains exactly `include(":app")`. 51,153 LOC of main Kotlin in
  one module, **no dependency-injection framework anywhere** (zero Hilt/Koin/Dagger in
  the version catalog, zero `@Inject`/`@HiltViewModel`/Koin imports in main source), and
  **one ViewModel for the whole app** — `ui/PlayerViewModel.kt` at 2,518 LOC constructing
  its own collaborators inline.
- The project's own `docs/quality/QUALITY_BAR.md` demands UDF layering, one
  `StateFlow<UiState>` per screen, constructor injection everywhere, package-by-feature
  and files under ~700 lines. **Four of its nine engineering criteria fail today.**
- Packages cannot enforce any of this. A package convention did not stop `AnalysisCache.kt`
  and `OfflineAnalyzer.kt` from being the only two of 21 files in `analysis/` to import
  `android.*` — the drift a `java-library` module makes impossible to compile.

The source-text gate tests resolve paths under `app/src/main/java/dev/musicviz`
(`ParamSurface.moduleRoot` walks up until it finds `render/scene/SceneParams.kt`;
`repoFile` tries `""` and `"app/"`). **New sibling modules are invisible to all of them.**
Gate failures in this programme come from *deleting or moving legacy files*, never from
adding modules. That decouples the two risks and drives the ordering in §8.

**Corrected inventory** (an earlier draft of this document said "39 gates" and "three
copies of `repoFile`"; both were wrong, and the number is load-bearing because every one
of these needs a module-scoped twin before the code it guards is deleted):

- **57** test files read main source text, via `readText()` or `ParamSurface.*`.
- **18** of them define their own `private fun repoFile` — not three. Collapsing these to
  one shared helper is prerequisite work, not cleanup.
- **11** additionally read `src/main/assets` / `src/main/res` — the GLSL gates, which no
  earlier draft mentioned at all.

**And escaping a gate can silently disarm it rather than merely bypass it.**
`AudioChainContractTest` proves tap-first by string index *within one file*:
`tapAt = factory.indexOf("TeeAudioProcessor(")`, then for each of seven DSP stage names
asserts `at > tapAt` **only if `at >= 0`**. The moment tap construction and the DSP stages
live in different modules, the stage loop finds nothing, every guard is false, and the
test passes unconditionally forever. Nothing in Gradle stops `:app` inserting a processor
ahead of an adapter it consumes. **The commit that moves the tap must replace that text
gate with a runtime gate** that instantiates the real `RenderersFactory` and asserts the
tap's index in the constructed `AudioProcessor[]`. A module boundary does not strengthen
this invariant; it destroys the only thing currently checking it.

**The second change: the engine is not starting from zero on rhythm.** The earlier plan
proposed replacing `PulseTracker` with a new predictive `BeatClock`. `PulseTracker`
(553 LOC) already publishes seven separated quantities — `beat`, `strength`, `transient`,
`phase`, `confidence`, `energy`, `bpmEstimate` — and exposes a static
`decidePulse(flux, rms, hopRateHz, sigma, minIntervalMs, historySeconds)` that replays the
entire beat decision offline from stored curves. **That one function is what makes the
analysis cache re-thresholdable and exports deterministic.** Extend it; do not rewrite it.

---

## 2. The real justification: four silent divergences

The codebase claims live/export parity. It is close, and these four gaps are real,
undocumented, and untested:

| # | Divergence | Consequence |
|---|---|---|
| 1 | Live analysis is a **62.5 Hz wall-clock** loop taking `snapshotLatest` ("newest window"); offline is **60 Hz sample-locked** | Different hop *and* different alignment. Under load, live silently drops or repeats audio |
| 2 | `OfflineAnalyzer` downmixes to mono and never constructs `StereoField` | **`stereoWidth` is 0 in every exported video, always** |
| 3 | Offline never calls `updateChroma` (only whole-track key) | **Every harmony-driven visual is dead in exports today** |
| 4 | Live box-averages the waveform (deliberately, to stop hi-hats aliasing into shimmer); offline point-samples | Scope-family scenes differ between screen and file |

A genuinely sample-driven single analysis path collapses all four for free. **This is the
strongest single argument for the rebuild** — stronger than any visual argument.

### 2.1 Three ways to reopen the gap while claiming to have closed it

Closing these four is necessary and not sufficient. Each of the following silently
reintroduces divergence, and the obvious parity test passes anyway:

**A fifth divergence: adaptive normalization.** Rolling P5/P95 gated normalization is the
right default for making quiet and loud tracks both fill the visual range — and it makes
every published feature a function of *listening history*. The same track played live
after a loud track normalizes differently from an export started cold. A parity test that
runs both drivers from a cold analyser **cannot fail**. Fix: define an explicit
analysis-session reset point and a specified warm-up (fixed range for the first N seconds,
crossfaded into adaptive), and make the parity test **prime** the normalizers with one
fixture before running another and still assert equality.

**Determinism stops at the audio boundary unless the exchange is time-addressed.** A
triple-buffered latest-wins `AudioFrameExchange.acquireLatest()` is nondeterministic by
construction, and it takes no time argument — so discrete events (drained from a
timestamped ring by range) land correctly while every continuous feature runs ~150 ms
early. That is worse than either error alone: *the beat flash lands on time and the band
motion it was meant to accompany already happened.* Make the exchange a small ring of
timestamped frames with `acquireAt(sampleIndex)` and interpolation between the two
bracketing frames. Export then gets its lockstep pull for free — the frame runner requests
state at `round(frameIndex * 48000 / fps)` and analysis advances synchronously to it.
Determinism is a swapped **audio-delivery** binding, not only a swapped clock.

*Corroborating tell:* if a design defines an `InterpPolicy` on its feature spec and no
component anywhere consumes it, that is the shape of this gap.

**Latest-wins also drops peaks.** At 30 fps export against 93.75 Hz analysis the renderer
sees roughly one hop in three. Smoothed features survive; RAW peak features
(`transientRaw`, per-hop band maxima) are silently discarded. The exchange needs
max-hold/accumulate across skipped hops, and the ring and the exchange must be consumed as
one coherent snapshot.

**Parity must be asserted on modulated uniforms, not just features.** Live runs a variable
`dt`; export runs a fixed one. Every modulation binding carries attack/release in
*seconds*, converted to per-frame coefficients via `dt`. Identical features plus different
`dt` produce different modulated parameter values and therefore a visibly different video.
Sample-lock the smoothing — derive coefficients from the audio hop index rather than
render `dt` — and extend the golden-vector gate to the modulated uniform vector.

---

## 3. Module graph

Six engine modules. Each boundary is justified by an invariant the compiler can enforce
that a package convention cannot.

```
                        :app  (com.android.application)
                          |  implementation(projects.engine.runtime)   <- one line
                          v
                  :engine:runtime  (android-library)
      +--------------+--------+--------+------------------+
      | api          | api             | implementation   | implementation
      v              v                 v                  v
:engine:visual-core  :engine:audio-core  :engine:scenes   :engine:audio-android
  (java-library)      (java-library)    (android-library)   (android-library)
                                              |
                                              v
                                        :engine:gl
                                      (android-library)
```

| Module | Type | Holds | Boundary buys |
|---|---|---|---|
| `:engine:audio-core` | `java-library` | `AnalysisConfig`, multi-resolution FFT bank, feature schema, `BeatClock`, `AudioEventRing`, `AudioFrameExchange`, `AudioPresentationClock`, the `PcmSink` port | `import android.os.SystemClock` **will not compile**. JVM tests in milliseconds, no Robolectric, no emulator |
| `:engine:visual-core` | `java-library` | Typed `ParamSchema`, modulation matrix, LFO/ADSR sources, family recipes as data, quality policy, colour/tonemap math | "Does this binding behave at 120 BPM with confidence 0.4" becomes a 2 ms JVM test; today it needs a device |
| `:engine:gl` | `android-library` | GLES30 abstraction, capability probe, program cache, FBO/ping-pong pool, render-graph executor, `GpuAudioBridge` | GL confined; scenes cannot reach around it |
| `:engine:scenes` | `android-library` | The six families + their GLSL | A family needing a new FBO format must **add it to `gl`'s public API**, not reach into internals |
| `:engine:audio-android` | `android-library` | The `TeeAudioProcessor` -> `PcmSink` adapter, renderers factory, mic/playback-capture sources, offline decode | **The only module with media3 on its classpath.** A media3 stage added anywhere else fails to compile — the tap-first invariant gets a module-scoped home |
| `:engine:runtime` | `android-library` | `FrameRunner`, the three surface hosts, `EngineSession` lifecycle, preset adapters, Koin modules | `implementation` (not `api`) on gl/scenes/audio-android means **`:app` cannot call GLES30 engine internals even if a future author wants to** |

**One `:engine:scenes`, not one per family.** Per-family modules are the "many shallow
modules" anti-pattern and multiply configuration cost for a boundary that buys nothing.

**Inverted dependency for PCM.** `audio-core` is pure JVM and cannot see media3, so it
declares the port and `audio-android` implements it. media3's `ENCODING_PCM_16BIT` and
`ByteBuffer` are parsed once at the boundary — parse-don't-validate — exactly as
`PcmTapSink.handleBuffer` already does.

**GLSL moves to `:engine:scenes/src/main/assets/shaders/`, not `res/raw`.** `res/raw` is a
flat namespace merged across modules; a library `lib_palette.glsl` would collide with the
app's at merge time. Assets allow directories and avoid R-class coupling, and make the
loader a port (`interface ShaderSource { fun read(path: String): String }`) that fakes to
a `Map` in JVM tests. The cost — assets are not compile-checked — is paid with a JVM test
asserting every shader path in the family registry exists. **Legacy `res/raw` shaders do
not move.**

---

## 4. Dependency injection — Koin

**Not Hilt**, for three concrete reasons:

1. **Hilt cannot participate in a `java-library` module.** It needs the Android Gradle
   plugin and a component tree. `:engine:audio-core` and `:engine:visual-core` are the
   point of this design; Hilt forces either promoting them to `android-library` (killing
   the JVM-only test story and the compiler-enforced Android ban) or hand-writing
   `@Provides` in `:app` for types `:app` does not own.
2. **The engine's scopes are not Android lifecycles.** An `EngineSession` outlives every
   Activity — that is precisely why `AudioBus` exists as a global today. An export session
   attaches to no Android component. Koin's `scope<EngineSession> { }` models this
   directly; Hilt needs `@DefineComponent` + `@EntryPoint` ceremony.
3. **Build cost.** KSP across the whole graph, on a codebase already carrying Compose
   codegen and 27k LOC of Robolectric tests, to wire ~40 objects.

**The rule that makes this cheap to be wrong about: constructor injection everywhere;
Koin appears only at composition roots.** No `KoinComponent`, no `by inject()`, no `get()`
inside any engine class. Every engine class is constructible with a plain constructor call
in a test. Koin is a leaf, replaceable in an afternoon. Enforced by a `KoinConfinementTest`
source gate: no engine source outside `di/` may import `org.koin`.

**Start with manual composition. Adopt Koin only when it earns its place.** Adversarial
review made the case and it holds: the two concrete payoffs claimed for Koin — swapping
`FrameClock` for export, and `Scope.close()` releasing the GL pool deterministically — are
both obtainable from a plain factory:

```kotlin
class EngineSessionFactory(
    private val caps: GlCapabilities,
    private val shaders: ShaderSource,
) {
    fun create(mode: EngineMode): EngineSession   // exhaustive `when`, no `else`
}
// EngineSession is a data class with close()
```

Fully compile-checked, no reflection, no runtime resolution failure, works in pure JVM.
A container introduces a runtime-failure mode into a codebase whose stated rule is *make
invalid states unrepresentable*, and then needs two bespoke test mechanisms
(`checkModules()` per module, plus a Robolectric composition-root test) purely to
compensate for it. If the honest defence of Koin is "replacing it is an afternoon", then
do the afternoon and keep the compiler.

**Falsifier, recorded in the ADR:** adopt Koin when the engine passes ~40 constructed
objects *or* a third entry point needs a genuinely different subset at a different
lifetime. Until then the factory is smaller, safer and testable without a container.

**Do not replace one global with a larger one.** An `object EngineRuntime` with hold
counters that internally resolves from a container is the service-locator anti-pattern —
the thing DI exists to remove — and is strictly worse than the `AudioBus` global it
replaces, because it holds the entire engine session rather than a features snapshot.
The wallpaper and export scopes are real scopes; model them as such.

---

## 5. The audio engine

**Two boundaries, not one.** The engine claims both and nothing between them:

- **Input: `PcmRingBuffer`**, not `AudioFeatures`. Everything upstream is media3 plumbing
  the engine must not touch; everything downstream is replaceable. Three producers write
  one buffer — player tap (ExoPlayer playback thread), mic, playback capture (both via
  `AudioCapturePump`'s daemon threads). The pump's own header states the rule: *"a second
  producer for one buffer, not a second analysis path that would drift from the first."*
  `AudioSourceRouter` sits **at or above** the ring, never replacing its single-writer
  contract. `AudioCapturePump.runGeneration` is already the source-epoch mechanism —
  adopt it rather than inventing one.
- **Output: the `Scene` contract.** Six methods, all GL-thread, all resources rebuilt in
  `init()` because the EGL context is deliberately not preserved. Keep a shape close to
  it; add what it lacks — a failure return, a capability declaration, and a deterministic
  frame clock instead of wall-clock seconds.

**Analysis schedule.** Sample-driven, not wall-clock. 48 kHz canonical internal rate,
512-sample hop (93.75 fps). Multi-resolution FFT: 1024 every hop (transients), 4096 every
second hop (bands, descriptors), 8192 every fourth hop (bass, chroma, tuning). Hann
windows, precomputed filter banks, all state preallocated.

**The ring contract must be fixed before anything else is built.** An earlier draft called
`copyNewSince` "already a gap-free monotonic sample-driven reader". It is not.
`PcmRingBuffer.kt:103-127` **clamps a lagging reader to the write head** — so a consumer
that falls behind silently jumps forward, and `sampleIndex += HOP` drifts against real
audio under load. That is precisely the bug being diagnosed in `AnalysisEngine`, sitting
one layer lower.

Required before slice 1: `readFrom(sampleIndex, out, count): Ok | Gap(n)`, with `Gap`
wired to `Discontinuity.Underrun`. The presentation clock, event timestamps and
live/offline parity are all fiction until the sample index means what it claims. Slice 1's
exit criterion is therefore a **stalled-reader** test, not a synthetic click track.

The ring also supports **one** reader and today has two competing users (`AnalysisEngine`
via `snapshotLatest`, `PlayerViewModel.latestPcm()` via `copyNewSince`). Fan out from one
reader, or extend the ring with per-reader cursors.

**Multi-resolution windows must be centre-aligned, not just hop-aligned.** FFT-1024 spans
21 ms, FFT-4096 85 ms, FFT-8192 **171 ms**. Hop-aligned but not centre-aligned means
features in the same `AudioFrame` describe audio up to ~75 ms apart: onset (1024) fires
around seven hops before bass level (8192) responds to the same kick. No modulation tuning
fixes a fixed group-delay mismatch. Either align window centres and publish one pipeline
latency, or carry per-feature latency and delay-compensate the fast features.

**`AudioPresentationClock` cannot be a filtered scalar offset.** The tap is stage 1, ahead
of SilenceSkipping and Sonic. Sonic (speed/pitch) makes the sample→position map a *scale*,
so at 1.25× playback a low-passed offset diverges without bound; SilenceSkipping *deletes*
samples, making the map piecewise with discontinuous jumps. Model it as epochs and
segments driven by media3's own processor-reported output-frame counts. Note also that
`ExoPlayer.getCurrentPosition()` is main-thread-confined, so correlating from the analysis
thread costs a scheduling hop whose jitter is comparable to the ±20 ms target. And the
wallpaper needs this clock as much as the live surface does — it is visualizing audio
coming out of a speaker.

**Keep, do not rewrite:** `PulseTracker` (and `decidePulse` verbatim), `FeatureExtractor.BeatGate`
(the sigma-over-rolling-flux gate — `framesSinceBeat` is clamped at 1,000,000 because an
unbounded counter overflows on a long-running wallpaper and the gate then never fires
again), `DrumChannels` (rename honestly to `lowBandOnset`/`midBandOnset`/`highBandOnset`
with kick/snare/hat as aliases), `Chromagram`, `StereoField`, `FeatureTimeline`,
`FrameAccumulator`.

**Replace:** `AnalysisEngine` outright (the wall-clock loop). `FftProcessor`'s shape —
but note `bandEdges()` is inverted by `DrumChannels` to turn a frequency into a band
index, so a band-layout change and a `DrumChannels` change are **one commit**.
`AudioFeatures`' *transport* (a `data class` with three `FloatArray`s copied at 62.5 Hz
through a `MutableStateFlow`) — but keep its *semantics*, which are well designed:
`beatImpulse`'s legacy fold-back, `motionImpulse = max(beatImpulse, transient*0.5)`,
`EMPTY_CHROMA` (size 0) meaning "no pitch information" as distinct from silence, and
`stereoCorrelation` defaulting to 1f because that is what mono genuinely measures.

**Events.** `FeatureTimeline.featuresAt(timeMs, spanMs)` is the subtlest thing in the
export path: `beat` is exactly one timeline frame wide, so a 30 fps export sampling a
60 Hz timeline missed half the track's beats. The span ORs `beat` and peak-holds
`onset`/`flux`/`beatStrength`/`transient` while `bands`/`waveform`/`rms`/`bpm` stay
point-sampled (averaging a waveform cancels its phase). **A sample-timestamped
`AudioEventRing` is the correct generalisation of that hack — adopt it, and port its test
coverage across.**

---

## 6. The visual engine

- **GLES 3.0 is the baseline and it has no compute.** Particles live in ping-pong state
  textures updated by fragment shaders, read via vertex texture fetch, drawn as instanced
  billboards — the SwissGL pattern, whose WebGL2 shading language *is* GLES 3.0. A GLES 3.1
  SSBO/compute tier is opt-in behind a runtime capability probe, never assumed.
- **Encode particle state as `RGBA32UI` + `floatBitsToUint`, not float textures.**
  `EXT_color_buffer_float` is not core in ES 3.0; `RGBA32UI` is. This removes the
  extension from the baseline entirely and turns "GLES 3.0 with a fallback" from a hope
  into a core-spec guarantee. Consequences to state up front: `out uvec4`,
  `glClearBufferuiv` rather than `glClearColor`, no blending, no filtering, and
  `uintBitsToFloat` in every downstream pass. Header rule:
  `precision highp float; precision highp int;`.
- **Deposit fields accumulate LINEARLY. Log packing is for storage, not accumulation.**
  `log(a) + log(b) = log(a·b)`, not `log(a+b)` — additive blending into a log-packed
  target multiplies densities instead of summing them, and `filter:'linear'` then
  interpolates in log space too. SwissGL's Physarum gets away with it because a
  chemoattractant field only needs monotonicity; a field driving audio-reactive brightness
  does not. Use an R16F accumulator behind the format probe, or linear RGBA8 with an
  explicit pre-scale and an accepted ~2-decade range, and apply the log curve at *display*.
  **The whole Living Field family sits on this choice — settle it with a measurement, not
  an argument.**
- **The binding constraint for scattered geometry is the tiler, not the ROP.** 128k
  instanced deposit quads at 60 fps is 7.7 M primitives/s, each landing at an essentially
  random tile; Mali's polygon-list build and Adreno's visibility stream both write
  per-primitive-per-tile records to main memory, which is exactly the locality the tiler
  assumes. Real mobile agent simulations sit at 10k–50k, not the ~128k a fragment-count
  calculation suggests — and shrinking the quad from 4×4 to 2×2 cuts fragments while doing
  **nothing** to binning cost, so the obvious lever does not pull the actual constraint.
- **Budget bandwidth, not ALU.** Multi-pass feedback means repeated FBO binds, and every
  bind of a partially-written target costs a full tile unresolve and store. Use
  `glInvalidateFramebuffer` on transient attachments, avoid load ops, and enumerate the
  pass count per frame against a documented frame graph.
- **Quality profiles must name a *simulation* resolution multiplier separate from the
  display one.** SwissGL's Physarum field runs at `scale: 1/DPR` — one-third linear
  resolution on a DPR-3 phone. Its diffusion shader does nine fetches per pixel; at native
  1080×2400 × 3 passes × 60 fps that is texture-fetch-bound on a mid-tier Mali. A profile
  table without this number is not a profile table.
- **Thermal management needs an API-26 path.** `PowerManager.addThermalStatusListener` is
  API 29 and `minSdk` is 26 — precisely the low-end devices the Compatibility tier exists
  for have no thermal API at all. Fall back to a measured frame-time trend.
  (`AudioPlaybackCapture` is also API 29+.)
- **Every offscreen RGBA8 colour target goes through `RenderTarget`** or
  `RenderTargetOwnershipTest` fails — it walks the whole main tree for `glGenFramebuffers`
  against an allowlist. A new GPU engine allocating its own FBOs fails immediately unless
  it goes through `RenderTarget` or re-authors that gate for its module. `release()` (live
  context) and `forget()` (dead context) are not interchangeable.
- **The per-frame order is an inherited contract**, mirrored line-for-line in the exporter:
  `resetFrameState` -> param fade -> **ADSR tick before LFO tick** (envelopes drive LFO rate
  and depth) -> apply -> **`VisualSafety.apply` last, after every modulator** ->
  `CompositeGrade` integrations (rotation and colour-cycle are *speeds*) -> scene update and
  draw -> composite. Reproduce those steps in that order or export parity tests fail.
- **Colour and compositing happen exactly once.** Scenes emit linear premultiplied colour;
  grading, safety, tone-map and sRGB conversion are global stages.
- **Quality profiles** scale resolution, particle count, raymarch steps, pressure
  iterations and bloom resolution independently with hysteresis. **Adaptive quality is
  forced off for export** — a 30 fps render reads as a permanent deficit against a 50 fps
  target and drops two tiers every 2.5 s until it bottoms out (`ExportDeterministicQualityTest`).

**Six families as data-driven recipes, not one class per look:** Particle Field, Cymatic
Matter, Living Field, Recursive Feedback, Phase & Scope, Spatial & Fluid.

---

## 7. Presets and migration — five concrete problems

1. **The `SceneParams` -> typed-schema translation is 160 keys wide and pinned in four
   directions.** `ParamSurface.fields` regexes `^\s*val (\w+):` out of
   `data class SceneParams(`; `presetKeys` regexes `.put("(\w+)"` out of `PresetStore.kt`.
   The moment `SceneParams` stops being a flat data class, `ParamSurface`, `ParamMatrix`
   and `CustomizeSurfaceTest` break at once. **Plan the schema and the gate rewrite as one
   vertical slice.**
2. **Deleted styles orphan live presets, not just code.** Presets on disk carry
   `sceneId` values for the 9 particle scenes and hyperspace, plus ~30 `hyper*` keys.
   **`SceneIds` constants for deleted styles must survive as deprecated aliases** or a
   remap table has nothing to key on. Needs a "this preset used a retired style" affordance.
3. **`live_state` is a preset and the wallpaper reads it cross-process.** `PlayerViewModel`
   writes it with `commit()`; `VisualizerWallpaperService.restoreLiveState` reads it via
   `PresetStore.fromJson`. A format change is a compatibility change between an updated app
   and a wallpaper engine still running from before. **Version the document.**
4. **MilkDrop presets carry `.milk` source inline and two eras coexist.** MilkDrop is out
   of scope for this engine, but the preset document is shared, so the new format must
   round-trip `milkPreset` and `customShader` untouched.
5. **`AnalysisCache` v2 needs a bump, and its versioning is destructive** (mismatch deletes
   the file — fine, re-analysis is cheap). Keep the current philosophy: **store raw curves
   and regenerate derived features**, which is what makes beats re-thresholdable. Bump to
   v3, store the multi-resolution raw curves, and add `sampleRateHz` to the header — today
   `withDrumChannels` guesses 48 kHz because the header does not carry it.

---

## 8. Slices

Ordering is driven by one fact: **gate failures come from moving or deleting legacy files,
never from adding modules.** So all module work lands first, and deletion last.

**Slice −1 — measure the GPU before designing for it.** A ~200-line throwaway GLES
harness: instanced scatter deposit at 8k / 32k / 128k agents, linear vs log-packed
accumulation, R16F vs RGBA8, on **one real Mali and one real Adreno**. No module exists
yet. This single measurement sets the deposit format, the particle budget, the simulation
resolution multiplier and whether `EXT_color_buffer_half_float` is a baseline requirement.
Every one of those is currently an extrapolation from a desktop browser, and every
downstream milestone is built on them. It is the cheapest de-risking available and
scheduling it late is how a plan discovers at week 14 that its headline family does not
run. *Exit: a numbers table committed to this document.*

**Slice 0 — CI and build conventions, zero production code.**
`.github/workflows/android.yml` and `ship-apk.yml` hard-code `:app:detekt`,
`:app:testDebugUnitTest`, `:app:lintDebug`. **A new module's tests would not run and
nobody would notice.** Worse, `checkThirdPartyNotices` is registered in
`app/build.gradle.kts:148`, wired into `:app:check`, and runs in both workflows — it is
`:app`-scoped, so **Apache-2.0 SwissGL adaptations landing in `:engine:gl` and
`:engine:scenes` would be invisible to the one gate that enforces attribution.** That is a
licence-compliance regression, not a test gap.

Fix all of it at once: make the CI line `./gradlew check` rather than a hand-enumerated
task list, and move ktlint, detekt, the 700-LOC discipline and `checkThirdPartyNotices`
into **convention plugins in `build-logic`** — before any engine module exists, not four
modules later. Modules created ahead of the convention plugins live for weeks with no
static analysis and no notices coverage, and a green CI blesses that state.
*Exit: a deliberately failing test in a scratch module turns CI red; a scratch module with
an unattributed Apache-2.0 header fails `check`.*

**Slice 1 — `:engine:audio-core` skeleton + `PcmSink` port.** JVM-only, WAV fixtures on the
classpath. *Exit: multi-resolution FFT bank matches the existing `FftProcessor` band output
on a fixture within tolerance, in a JUnit test that runs in milliseconds.*

**Slice 2 — sample-driven analysis and the single path.** `audio-android` adapter; live and
offline both call it. *Exit: divergences 1-4 in §2 are closed and pinned by a test that
feeds identical PCM to both paths and asserts frame-for-frame equality.*

**Slice 3 — `AudioFrameExchange` + `GpuAudioBridge`.** Legacy `AudioFeaturesAdapter` so
existing scenes run unchanged from V2 features. *Exit: no steady-state allocation on the
audio path; existing visuals unchanged on screen.*

**Slice 4 — `:engine:gl` + `:engine:runtime` + one family (Particle Field).** The tracer
bullet: PCM in, one scene on screen, exported to video. *Exit: no synchronous readback,
deterministic seeded output, context-loss recovery, live/export parity.*

**Slice 5 — typed `ParamSchema` + the gate rewrite, together.** *Exit: `PARAM_MATRIX.md`
regenerates; round-trip tests pass against the new schema.*

**Slice 6 — remaining families.**

**Slice 6.5 — carve the visualizer surface out of `PlayerViewModel`.** Non-optional, and
the reason is the plan's own premise. The case for building fresh was that *nothing
structurally prevents the new engine from acquiring the old engine's coupling*. Six
modules underneath an unchanged 2,518-LOC `AndroidViewModel` that constructs its own
collaborators is exactly where that coupling re-forms — and after every other slice ships,
all four failing `QUALITY_BAR` engineering criteria would still be failing. Extract the
visualizer surface into its own `StateFlow<UiState>` with constructor-injected
collaborators. *Exit: `PlayerViewModel` no longer references the engine; one screen has a
sealed `UiState` and a Turbine test.*

**Slice 7 — collapse the gate helpers and inventory the twins.** Before any deletion:
collapse 18 hand-copied `repoFile` helpers to one, inventory every gate that needs a
module-scoped twin, author the twins, and state `SceneParams`' end-of-life including how
recipe params reach the Compose controls, the randomizer and the lock chips. Without that
last part the flat param model and the typed schema ship side by side forever.

**Slice 8 — delete the particle and hyperspace style classes.** Only now, with recipes in
place, twins authored and `sceneId` remapping tested. Update `ParamSurface.FAMILIES` in
the same commit.

**Files that must survive at their exact paths for the whole migration** — an earlier
draft named only the first, which is why its "gate risk is concentrated in the deletions"
claim did not hold:

| Path | Anchors |
|---|---|
| `render/scene/SceneParams.kt` | `ParamSurface.moduleRoot` — if it moves, every `ParamSurface` test dies with `"musicviz project root not found"` |
| `render/scene/CymaticsScene.kt` | `ParamSurface.FAMILIES`, `CymaticsClockSafetyTest`, `CymaticsStyleIdentityTest` |
| `render/VisualizerRenderer.kt` | `FAMILIES` "Composite", `RendererWiringTest`, `CompositeUniformParityTest` |
| `render/CompositeGrade.kt` | `FAMILIES` "Composite" |
| `export/FxCompositor.kt`, `export/VideoExporter.kt` | `FAMILIES` "Export", `CompositeUniformParityTest`, `ExportDeterministicQualityTest` |

Note also that registering a new family in `VisualStyleCatalog` **does** edit legacy code:
`RendererWiringTest` compares the offered-style id set against what `buildScene` and
`onSurfaceCreated` construct, so a visible `EngineFamilyScene` requires touching
`VisualizerRenderer.buildScene` and `SceneIds`. "Zero legacy files modified before the
deletion slice" is not achievable; plan for it rather than claiming it.

---

## 9. The trap to design against

The 39 gates encode roughly two years of hard-won device-specific knowledge — FBO
completeness, context-loss release-vs-forget, composite uniform parity, tap ordering,
backup quota, hot-path buffer reuse. New modules are invisible to all of them, which means
**moving out of their reach silently discards that knowledge.**

**Every gate the new modules escape must be re-authored against the new module before the
corresponding old code is deleted.** The module-independent invariants — RenderTarget
ownership, scene failure isolation, backup coverage, persistable-URI guarding, hot-path
reuse, and the currently-unguarded GLES 3.0 ceiling — should be re-expressed as per-module
source gates or Detekt/Konsist rules, not dropped.

Add the missing gate while you are there: **nothing currently enforces GLES 3.0.** The
manifest declares `0x00030000` and `GLES30` is the only import in the tree, but no test
says so.

---

## 10. The hard-delete list, reconciled

A separate draft answered "what would you fully delete and redo?" Most of it is right and
this plan adopts it. Seven entries conflict with something verified in the tree, and each
would cost a property the app currently has.

| Proposed deletion | Verdict | Why |
|---|---|---|
| `VisualizerRenderer` (1,651 LOC) | **Delete — agreed, strongest candidate** | Scene construction, frame timing, transitions, modulation, flow/ripple services, FBOs, compositing, safety, export setup and per-scene branching in one file. But it is pinned at its exact path by `FAMILIES` "Composite", `RendererWiringTest` and `CompositeUniformParityTest` — the file survives until the twins are authored |
| `ParticleSceneBase` + 9 CPU subclasses | **Delete — agreed** | Reads flow back from the GPU, integrates on the CPU, rebuilds and re-uploads buffers every frame. Reusing it preserves the performance ceiling |
| `AudioBus`, `BandSmoother`, `AnalysisEngine` | **Delete — agreed** | `AnalysisEngine` is the wall-clock loop. `AudioBus` is a DI-hostile global — but do not replace it with a *larger* global (see §4) |
| `SceneParams` flat object | **Delete — agreed, last** | Anchors `ParamSurface.moduleRoot`; survives as a shim to the end |
| **`PulseTracker` → "A/B baseline only"** | **Reject — keep and extend** | It already publishes `beat`, `strength`, `transient`, `phase`, `confidence`, `energy`, `bpmEstimate` — the seven quantities the "new predictive BeatClock" was specified to provide. Its static `decidePulse(...)` replays the whole beat decision offline from stored curves, which is what makes the cache re-thresholdable and exports deterministic. Rewriting it *loses* a property |
| **`FeatureTimeline`** | **Reject as written** | `featuresAt(timeMs, spanMs)` ORs `beat` and peak-holds `onset`/`flux`/`beatStrength`/`transient` across the span while `bands`/`waveform`/`rms`/`bpm` stay point-sampled. Without it a 30 fps export missed roughly **half the track's beats**. The event ring may replace it only by reproducing that property, with its tests ported |
| **`AnalysisCache`** | **Reject the philosophy change** | Its design — store raw curves, regenerate derived features — is exactly what allows `withBeatSensitivity` and `withDrumChannels` to re-derive without a format bump. Bump v2→v3 and add `sampleRateHz` to the header; do not switch to storing derived features |
| **The `Scene` interface** | **Reject wholesale replacement** | Six methods, all GL-thread, all resources rebuilt in `init()`. It is a good interface. Add what it lacks — a failure return, a capability declaration, a deterministic frame clock — rather than starting over |
| **`FxCompositor` + the visual half of `VideoExporter`** | **Agreed, but not deletable when proposed** | Both are pinned at exact paths by `ParamSurface.FAMILIES` "Export", `CompositeUniformParityTest` and `ExportDeterministicQualityTest`. Deleting the duplicate pipeline requires authoring those twins first |
| **`BeamScene`, `ShaderScene`** | **Agreed, with a gate cost** | `SceneUniformParityTest` pins `BeamScene.kt` together with `beam_vert.glsl`/`beam_frag.glsl`; `ShaderScene.kt` is `FAMILIES` "Shader" and `CustomShaderLastGoodSourceTest` |
| **`VisualizerView`** | **Agreed, carry the comment forward** | It deliberately does *not* call `setPreserveEGLContextOnPause(true)` — preserved-context resume is a known source of device-specific GL hangs. Losing that decision silently reintroduces the bug |
| `PerformanceTake` / `TakeController` replay | **Agreed — new scope** | Not previously in this plan's delete list; versioned media-time take timelines are the right replacement. Note `ExportTakeSceneTest` and `ExportHostSaveableTest` |

**The pattern in the rejections:** every one is a case where the old code encodes a
*property* rather than an implementation — deterministic replay, span-correct sampling,
re-thresholdable caching, context-loss safety. Those properties must be re-expressed as
tests against the new code **before** the old code is deleted, or the rebuild trades a
coupling problem for a correctness regression.

The draft's own "would not fully delete" list is adopted unchanged: the PCM tap and
processor chain, capture services, `PcmRingBuffer`'s lock-free design (extended per §5),
the fluid/cymatics/chromagram/stereo-field/drum math, projectM's JNI boundary, GL
compilation and context-loss utilities, encoder/muxer infrastructure, and all stable
scene IDs and user data. Its judgement on `PlayerViewModel` — extract responsibilities
rather than rewrite — is what §8's slice 6.5 implements.

---

## 11. Risks

1. **Gate-knowledge loss.** 57 test files read main source; escaping them discards two
   years of device-specific knowledge, and in the tap-first case escaping *disarms* the
   check rather than merely bypassing it. Mitigation: slice 7 authors the twins before
   slice 8 deletes anything; the tap gate becomes a runtime assertion in the same commit
   that moves the tap.
2. **A green gate that has become vacuous.** Collapsing the new parameters behind a single
   opaque `engineV2` preset key keeps `CustomizeSurfaceTest` passing — because it only
   asserts `ParamSurface.fields ⊆ presetKeys`, not the converse — while covering **zero**
   of them. A green vacuous gate is worse than a red one. Mitigation: pair the migration
   with a *replacement* JVM gate in `:engine:visual-core` asserting every `ParamSchema`
   entry round-trips through the serializer, is reachable from at least one control
   descriptor, and carries either a randomization policy or a declared reason not to —
   the same four-way check, done against typed data where it is cheap.
3. **Preset migration breaking users' saved work**, especially cross-process against a
   wallpaper engine still running the old format. Mitigation: version the document,
   deprecated `sceneId` aliases, round-trip tests on real preset fixtures before deletion.
4. **The GPU budget being wrong by an order of magnitude.** Particle counts, deposit
   format and simulation resolution are currently extrapolated from a desktop browser, and
   the true constraint is tile binning rather than fill rate. Mitigation: slice −1 measures
   on real Mali and Adreno hardware before any module exists.
5. **A big-bang cutover wearing vertical-slice clothing.** If the new audio path runs only
   under `BuildConfig.DEBUG` until one late commit that simultaneously flips the default,
   deletes `AnalysisEngine` and `AudioBus`, and re-points the chain contract test, then it
   has never run under R8, on real devices, on real battery. Mitigation: ship it behind a
   *release* flag with the old path authoritative, run both, then flip — deleting nothing
   until after the flip has held.
6. **Module split costing more than it buys** if the engine stays small. Mitigation: the
   ADR in §4 states the falsifier. Six modules is the ceiling, not a floor, and manual
   composition is the starting point.
