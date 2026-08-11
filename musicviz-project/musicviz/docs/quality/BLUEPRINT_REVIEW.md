# Blueprint Review — 91-Feature Architecture Plan, Grounded

Review of the "91 Missing Features" architecture blueprint against the codebase
as it actually stands at `2b842bd`. Produced by four independent architect
passes (composite pipeline, scene layer, fluid sims, audio path), with every
load-bearing claim re-verified by hand before it was written down.

**Read this before implementing any blueprint feature.** Three of its ADRs
contain specific instructions that would fail — one of them silently, in a way
that ships a dead visualizer.

---

## 1. What the blueprint got right

Its current-state analysis is accurate on the details that matter, and the
central insight of ADR-2 (the composite pipeline is the constraint) is correct
even though its proposed fix is not. Verified accurate:

- `VisualizerRenderer.kt` is ~1679 lines, `SceneParams` has ~180 fields.
- The DSP insertion point exists exactly where ADR-1 says: `audio/TapRenderersFactory.kt:34`.
- gl-transitions is already vendored (`assets/gl_transitions.json`) — #86 needs
  no "move to assets" step, only a shader-splicing consumer.

Two of its stated numbers are wrong, and both change budget arguments:

- **"1.4× supersampled FBO"** — `supersampleFactor` (`render/VisualizerRenderer.kt:974-984`)
  returns **1.0× at ≥2200 px longest edge**. A 1080×2400 phone gets *no*
  supersampling; 1.4× only happens on ≤720p-class surfaces. Any memory or
  fill-rate estimate built on 1.4× overstates current cost roughly 2×.
- **"26-theme `AppTheme` enum"** — deleted. Themes are now authored 13-role
  `ThemePack`s (`ui/theme/ThemePack.kt`), and `colorScheme` carries a dim-aware
  readability pass. Anything premised on four-anchor derived palettes (#53, #61)
  must be re-grounded.

---

## 2. Phase 0 — four verified defects, ahead of any feature

Each of the four architect passes found a live bug in the area it was pointed
at. None of them is a missing feature; all four are already shipping. Fixing
them is cheaper than any blueprint item and removes failure modes that would
otherwise be blamed on the new subsystems.

### P0.1 — The export path never uploads `uLayerMix` / `uBlendMode`

`render/VisualizerRenderer.kt:1277-1278` uploads both. `export/FxCompositor.kt`
uploads **neither** — while a comment directly above its `uStyle` line asserts
"the two composite call sites stay uniform-for-uniform identical." That comment
is false today.

Harmless only because export pins `uStyle = 0`, making the blend path
unreachable. The first layered export reads GL's default `0` for both and
silently renders the bottom layer alone — precisely the failure ADR-2's own
feature would hit on day one.

**Fix:** upload neutral values in `FxCompositor`, then add the parity gate in
P0.5 so it cannot regress.

### P0.2 — Every 30 fps fluid export renders at minimum quality

- `render/fluid/PerformanceMonitor.kt` hardcodes `targetFps = 50f`, `sustainSeconds = 2.5f`.
- `render/fluid/FluidScene.kt:145` stores `lastDt = dt`; `:244` feeds it to
  `monitor.onFrame` whenever `fluidAutoQuality` is on — which **defaults to
  `true`** (`render/scene/SceneParams.kt:129`).
- `export/VideoExporter.kt` drives scenes with a *constant* `dt = 1f / fps`.

A 30 fps export therefore reports a steady 30 fps against a 50 fps target,
clears the 5–180 fps stall filter, accumulates deficit every frame, and at 2.5 s
computes severity `(50-30)/50 = 0.4 > 0.35` → severity **2** → drops two
quality tiers → `reset()` → repeats until it saturates at the minimum tier.

This is arithmetic, not thermals: it reproduces on every device, every time, and
it breaks the export/screen parity that the same file asserts elsewhere. 60 fps
exports escape; a 60 fps export that falls back to 30 on encoder failure does not.

**Fix:** a sensor boundary — the export path injects a fixed/no-op sensor, or
`VideoExporter` forces `fluidAutoQuality = false`. Either is a small change.

Two further defects in the same 60-line file, worth fixing while it is open:
its KDoc claims "Welford's online algorithm maintains the mean frame time"
(it is a plain ring-buffer sum; nothing computes variance), and its stall filter
is unreachable on the live path because the renderer clamps `dt` to 1–100 ms —
so a multi-second GC pause arrives as one 100 ms frame *counted as signal*.

### P0.3 — A broken shader is recorded as "the active shader" before it compiles

`render/VisualizerRenderer.kt:803-809` (`submitShader`) queues the source **and**
writes `activeCustomShaders[sceneId] = fragmentSrc` unconditionally, before the
GL thread attempts a compile. That map is re-pushed into every fresh context
after EGL loss (`:878-880`), baked into exports, and saved into presets.

Sequence: type a bad shader → last-good program still renders → background the
app → the last-good program dies with the context, the broken source is
restored → the style is **permanently black**, with the breakage persisted into
presets and shared preset links.

**Fix (~10 lines):** record `activeCustomShaders[id]` on the GL thread only
after a successful link; keep a `lastGoodSource` per scene. This is also the
foundation #62 needs.

### P0.4 — `PMBridge` error reporting is process-global and destructively read

`tools/pm_jni.c:17` declares `static char g_last_error[512]`.
`nativeGetLastError` (`:121-126`) takes **no handle** and **clears the buffer on
read**; `PMBridge.kt:51` mirrors that signature. `ProjectMScene` polls it up to
three times per frame.

This is not merely a #49-phase-2 blocker. A MilkDrop **export** already builds a
second `ProjectMScene` on its own EGL context and thread while the live view
keeps rendering, so two projectM instances share that destructive global *today*
— which means `pm_jni.c`'s own "no locking needed, everything runs on the GL
thread" justification is already false.

**Fix:** handle-scoped error retrieval. Note the cost honestly: any `pm_jni.c`
change means a full native rebuild through `.github/workflows/native-libs.yml`,
the 16 KB alignment gate, regenerated `SHA256SUMS` provenance, and MilkDrop
device re-checks. Schedule it; do not slip it into an unrelated commit.

### P0.5 — The gates that would have caught all of this

Three cheap source-text gates, in the style the repo already uses:

1. **Composite uniform parity** (~40 lines): extract `cLoc("…")` from
   `VisualizerRenderer.onDrawFrame` and `loc("…")` from `FxCompositor.composite`,
   assert equal sets with a declared exemption map. Fails immediately on P0.1 —
   that is the point. Highest value-per-line change available anywhere in this
   document.
2. **Export frame-order parity**: assert the export loop calls
   `GlUtil.resetFrameState()` once per frame (live does, export does not) and
   that the FlowField step precedes `scene.update` as it does live. The export
   path currently runs them in the opposite order while both comments claim they
   match, giving field-driven styles a one-frame phase difference between screen
   and file.
3. **Float-output prohibition**: fail if `TapRenderersFactory.kt` ever contains
   `setEnableFloatOutput(true)`, with the reason in the failure message (see §3).

Also decide, explicitly rather than by silence: export applies **no**
supersampling and **no** param fade. The second one means a recorded Take's
slider moves *snap* in the rendered file and glide on screen. Either fix them or
document them where the layer divergence is already documented.

---

## 3. ADR revisions

### ADR-1 (float DSP chain) — two instructions would fail, one silently

**Do not call `setEnableFloatOutput(true)`.** media3 1.10.0's own javadoc on
`DefaultAudioSink.Builder.setEnableFloatOutput` states: *"Audio processing (for
example, speed adjustment) will not be available when float output is in use."*
Float output does not give a float chain — it **deletes** the chain, including
the `TeeAudioProcessor` visualizer tap. No crash, no log: frozen visuals and a
dead EQ. The current code forwards a flag that nothing sets, so it is `false`
and harmless today. Gate it (P0.5.3).

**`setAudioProcessors` is the wrong hook.** It wraps the array in
`DefaultAudioProcessorChain`, which allocates `length + 2` and appends
`SilenceSkippingAudioProcessor` and `SonicAudioProcessor` *after* your stages
("applied before silence skipping and speed adjustment processors"). Both accept
`ENCODING_PCM_16BIT` only, so the first stage emitting float makes
`AudioProcessingPipeline.configure` throw and playback fails at track start.
Use `DefaultAudioSink.Builder.setAudioProcessorChain(...)` — confirmed present
in 1.10.0 — with a custom chain that delegates the playback-parameter methods.

**Do not claim a hi-res path.** Whatever is inserted lands *after* media3 has
already converted to 16-bit (which is why `AiffExtractor` can declare 24/32-bit
big-endian and rely on the converter). There is no public hook upstream of that.
A float chain buys inter-stage headroom and avoids double truncation — a real
win — but it is not bit-depth transparency, and #23 must not be sold as one.

**The tap stays first, and the reason is stronger than the ADR states.** It is
not a taste call: `analysis/FeatureExtractor.reset` and `analysis/AnalysisEngine.reset`
both document in-source that live playback must reproduce what the cached and
exported render produce from the same file. The offline path decodes the file
with no EQ. A user-tunable chain upstream of the tap makes live visuals diverge
from every exported video, per user, per preset — an untestable bug class. The
loudness seek bar, drawn from the offline RMS curve, would disagree too. If an
output meter is wanted later, add a *second* tap post-DSP that feeds metering
only and never `AudioFeatures`.

**Revised first slice (zero user-visible change):** replace
`setAudioProcessors(arrayOf(TeeAudioProcessor(sink)))` at
`audio/TapRenderersFactory.kt:34` with a custom chain returning exactly
`[tap, silenceSkipping, sonic]` — byte-for-byte today's order. No DSP stage, no
float, no new dependency. It proves the interface compiles against 1.10.0, that
we control ordering, and that the tap still fires from position 0. Protect it
with an ordering test and a pipeline transparency test asserting byte-identical
output through a fully bypassed chain.

Then sequence: parametric EQ (16-bit in, float internal, 16-bit out) → ReplayGain
+ limiter as one gain slice → fixed-output-rate resample → convolution last,
because it is the only item that touches the native build, the licence gate and
process-kill risk at once. **Drop DVC (#28) from ADR-1's scope** — it needs
system-volume ownership and fights A2DP absolute volume; it is a separate
decision. **Move crossfade (#2) out of ADR-1 entirely** (see below).

### ADR-2 (LayerStack) — the two promises are mutually exclusive

The blueprint promises both "a 1-layer `LayerStack` is byte-identical to today"
(Invariant 4) and a correct generalized N-layer composite. In this shader they
cannot both hold.

`composite_frag.glsl` applies nine whole-frame effects — vignette, scanlines,
grain, strobe, chroma, fisheye, glitch, flow, ripple — **inside** the per-texture
`postFx` function, interleaved between gated per-layer effects
(`grade → scanline → grain → vignette → strobe → solarize → flash → invert`).
Multiplication does not commute with `solarize`, `invert` or `flash`. So:

- keep `postFx` monolithic → every whole-frame effect is applied N times; or
- split into per-layer and per-frame stages → N=1 is **not** byte-identical.

This is not hypothetical. **The existing 2-layer path already double-applies all
nine** — two decorrelated grain fields, vignette darkening each layer's edges
before a non-linear SCREEN/OVERLAY blend. That is a current quality bug, and N
layers makes it N-fold. The ADR must pick a side and say which.

Two further structural obstacles: `fboB` is double-booked between transitions
and layers (so a stack surviving a transition costs +1 full-res FBO *always*,
not only at N≥2), and `uStyle` is a single int already carrying three
vocabularies (0–4 transitions, 5 library, 6 layers) that cannot express a stack
— and 122 spliced transition variants recompile against any change to it.

**Revised sequence:** the real defect is that the composite pass is hand-written
twice with no test comparing them. So: P0.5.1 parity gate → fix divergences →
`RenderTarget` + `CompositePass` (one FBO wrapper, one composite, two consumers)
→ `LayerStack` **as a value type only, capped at 2**. The payoff of that last
step is not more layers; it is that the layer→uniform mapping becomes unit-testable
without a GL context for the first time, and that the wallpaper — which today
silently ignores layers entirely — can set the same value. N≥3 only behind a
product decision, implemented as a pre-composite accumulation pass (+1 FBO total,
N≤2 untouched), and only after evidence that any device runs *two* heavy layers
at 60 Hz.

### ADR-2 addendum (#60 feedback buffer) — the premise is wrong

The blueprint frames #60 as generalizing the sims' ping-pong. That code is
**already** generic and already extracted: `render/fluid/FluidBuffers.DoubleFbo`
is ~30 lines, reused by four independent owners, with an empirical
`R16F → RG16F → RGBA16F` renderability probe. "Generalize the sim ping-pong"
would be a rename.

Worse, the sims are the wrong pooling customers: velocity, dye, pressure, `grid`
and `ink` are persistent cross-frame state that can never be recycled while the
scene lives, so pooling them yields exactly zero at high migration and gate cost.
The real prior art for #60 is `res/raw/trail_warp_frag.glsl` — already documented
as the MilkDrop "warp shader has a memory" effect, already frame-rate normalized
via `CurlFlowMath.warpDecay`, already clamped below 1, already pinned by a
live/export dedup gate. #60 generalizes *that*, per-layer.

The pool's actual customers are transient: `divergence`, `curl`, the bloom mip
chain, and `trailTex` (which today stays permanently resident once touched).

**Two invariants belong in code, not slider ranges:**

- **8-bit multiplicative decay never reaches zero.** At `decay = 0.99`, a texel
  below ~50/255 changes by less than half an LSB and sticks forever. The existing
  fade path has this today — it is the "ghost trails never fully clear" bug
  waiting to be filed. Use a 16F target or a subtractive floor.
- **`VisualSafety` cannot see accumulated output.** It runs on *params*, before
  scenes draw. That is the same structural gap `VisualSafety.layerMix` was
  written to patch for Layers, and a feedback buffer with decay→1 plus additive
  content is exactly that class of full-frame luminance event. It needs its own
  `feedbackDecay` limiter beside `layerMix`, called from the shared
  `CompositePass` so all three consumers get it by construction.

**Budget reality:** bandwidth binds before VRAM. A full-res RGBA8 feedback pass
reads 10.1 MB and writes 10.1 MB ⇒ ~1.2 GB/s per layer at 60 fps, atop the
composite's ~1.7 GB/s and FLUID's 2–4 GB/s, against ~4–6 GB/s realistically
reachable on a mid-range phone. And these are tile-based GPUs: a feedback pass
samples a *displaced* texel, so it can never be tile-local — every feedback layer
is an unavoidable full load plus full store. Realistic cap: **N=2 at scale ≤0.7**,
or N=3 at 0.5.

### ADR-2 addendum (#47/#48 shader import) — one decision before any code

`compositeFamily` maps `is ShaderScene → SHADER`
(`render/VisualizerRenderer.kt:1513-1519`), and `gateFor(SHADER)`
(`render/CompositeGrade.kt:97-103`) returns `geo=false, mirrorInvert=false,
grade=false, pulse=false` — **all four off**, because built-in shaders apply
those themselves in their shared `view()`/`grade()` prelude.

An imported ISF or Shadertoy shader knows nothing about that prelude. If imports
are built as a `ShaderScene` subclass — the obvious choice — then Zoom, Rotation,
Shape, Colour, FX and Pulse all silently do nothing on every imported shader.
**Give imports their own `Scene` class** so the `else -> FLUID` branch hands them
the whole `uPost*` block. One line; the difference between "imports are a
first-class style" and "imports are an island where two-thirds of the panel is
dead."

**Dynamic uniforms: do not add `isfParam0..15` to `SceneParams`.** That would
cost 16 controls, 16 randomizer rolls, 16 preset keys, 16 readers and 16
meaningless `PARAM_MATRIX.md` rows, all dead on the other 38 styles. The repo
already solves this twice: `render/TransitionCatalog` keeps per-entry typed
params *out* of `SceneParams` and uploads them by name with `if (loc < 0) continue`,
and the preset envelope already carries looks that are not parameters
(`customShader`, `milkPreset`). Build an `ImportedShaderCatalog` on the
`TransitionCatalog` model, persist one envelope key, and put its UI in its own
file — **not** `ui/CustomizeTabs.kt`, which the `ParamSurface` gate slices by
function.

**#48's real trap is the audio texture.** Shadertoy's `iChannel0` row 0 is 512
*linear* FFT bins. This app has 64 log-spaced, dB-normalized, temporally
smoothed bands and a 128-entry box-averaged waveform. Imported shaders indexing
linear frequency will look **wrong rather than broken** — the worst failure mode,
because nothing errors. `analysis/FftProcessor` already computes 1024 linear
magnitudes internally and only exposes them to key/chroma detection, so the fix
is a new accessor, not new DSP. Budget it explicitly.

**Reuse the existing GLSL port.** `tools/vendor_gl_transitions.py` already does
`texture2D(` → `texture(` *and* hoists non-constant file-scope initialisers into
`#define`s — which ES 3.00 requires and which Shadertoy code violates constantly
(`float t = iTime*0.5;` at file scope). Do not write a third porter.

**Scope ISF honestly.** It is materially harder than "parse the leading JSON
comment": `PASSES` targets can be sized by *expressions* (`"$WIDTH/4"`), which
is an evaluator; `PERSISTENT`+`FLOAT` buffers need renderable half-float that
the fluid family degrades from gracefully but an ISF preset cannot;
`isf_FragNormCoord` must be defined against the *supersampled* target. Ship a
documented subset with an explicit `unsupported` marker per entry, the way the
transitions vendoring already handles what it cannot verify.

**Licensing, to settle now:** Shadertoy's default is CC-BY-NC-SA with ToS
restrictions on bulk redistribution. #48 must be **user-import only, never a
bundled browser.** Write it in the design doc before anyone builds the browser.

Conversely **#49 phase 1 is nearly free**: `nativeLoadPreset` already carries a
`smooth` flag, soft-cut duration is already configured natively, and
`ProjectMScene` hardcodes `smooth = false`. One boolean plus a toggle. The
*adjustable-duration* variant needs a new JNI export and therefore the full
native rebuild — do not bundle the two.

### ADR-2 addendum (#2 crossfade) — contradicts a tested invariant

`playback/PlaybackEngine` exists specifically to prevent a second `ExoPlayer`
("correct-looking transport controls over silence, two decoders fighting for the
audio device, and two writers into the one analysis ring buffer"), and
`PlaybackEngineTest` asserts the single-player identity. The dual-player proposal
is therefore a re-architecture, not a feature. Its hardest problem is not the
mixer maths — it is **position and timeline reporting through a facade `Player`
during the overlap**, because that one value simultaneously drives the
notification, lock screen, AVRCP, the seek bar and the loudness waveform, and
must never jump backwards at handover. Audio focus is the second problem: two
players both requesting `AUDIOFOCUS_GAIN` means one sees a loss and pauses itself.

Evaluate the **single-decoder** alternative first: one chain stage buffers the
outgoing track's final N seconds and mixes that fading tail over the incoming
head. No second player, no facade, no MediaSession change. It costs N seconds of
buffered PCM, must be disabled for gapless albums, and desyncs the tail from the
visuals — but it is a paragraph of evaluation against a month of two decks.

### ADR-6 (licence gates) — one addition

`sampler3D` and `sampler2DArray` are core ES 3.0 and therefore legal, but there
is **zero** in-repo precedent (every shader uses `sampler2D` only), zero device
coverage in `DEVICE_CHECKS.md`, and the `ShaderSamplerPrecisionTest` gate only
knows `sampler2D`. #83 (3D LUT import) is the first consumer — treat it as new
capability with a device-check row, not as a small addition.

---

## 4. Revised staging

**Phase 0 — repair and gate** (§2). Four verified defects plus three source
gates. No new subsystems. Everything below is cheaper and more attributable
afterwards.

**Phase 1 — foundations, corrected.**
1. Custom `AudioProcessorChain` reproducing today's order exactly (no DSP, no float).
2. `RenderTarget` + `CompositePass` — one FBO wrapper, one composite, two consumers.
3. `LayerStack` as a value type, capped at 2.
4. Shader-compile substrate: last-good *source* (not just last-good program),
   infoLog line normalization, `glGetActiveUniform` introspection.
5. `TextEngine` (bitmap→texture first; SDF only if measured need).
6. `KeyframeEngine` with the one export clock.

**Phase 2 — payoff, in dependency order.** #49 phase 1 (one boolean) → parametric
EQ → ReplayGain + limiter → #48 Shadertoy import (which forces the linear-FFT
accessor) → #47 ISF subset → library items (#4/#5/#6/#13/#19) → export items
(#70/#79/#76/#80).

**Phase 3 — heavy, platform-gated, or re-architecture.** #21/#22/#23 bit-perfect
family; #2 crossfade (after the single-decoder evaluation); #49 phase 2 (after
handle-scoped errors and a fan-out PCM provider); #39 ML stems; #85–#88 Studio
timeline; #75/#90/#91 robustness; #65 OSC flavor.

---

## 5. Invariants to add to `QUALITY_BAR.md`

1. The analysis tap is **first** in the audio chain. Live features must equal
   offline/cached/exported features for the same file; a downstream tap breaks
   that per user and no test can catch it.
2. Never enable media3 float **output** — it disables the processor pipeline
   including the visualizer tap.
3. The two composite call sites upload identical uniform sets, enforced by a gate.
4. Nothing on the export path reads wall-clock time, frame-rate, or adaptive
   quality state. One export clock; deterministic sensors only.
5. Every new full-frame luminance event (per-layer blend, feedback decay,
   performance FX) gets a `VisualSafety` entry point, because `apply()` only
   sees params and cannot observe accumulation.
6. Bit-perfect claims come from API confirmation, and bit-perfect implies
   DSP-bypass in code, not by UI convention.
