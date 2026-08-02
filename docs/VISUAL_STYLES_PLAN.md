# Hyperspace and Cymatics: the rebuild

## What this is

Twenty new styles: ten in the Hyperspace family, ten in the Cymatics family. Each one has to be
real 3D (a camera, depth, geometry or a marched volume - not a fullscreen quad pretending), each one
has to have an object that moves and changes shape rather than a pattern that scrolls, and each one
has to be coupled to a fluid or wave medium in both directions.

This document is the design. It commits to:

- One shared 3D foundation for both families, not two. The first draft of this plan had
  `GeoTarget` and `CymaticStage`, `HyperCamera` and `CymaticCamera`, `HyperMesh` and `HeightMesh`,
  `lib_hyper_film` and `lib_iridescence` - four pairs of the same object. They are merged here into
  `render/space/`, and that merge is the single largest saving in the plan.
- Exactly ten hyperspace styles and exactly ten cymatics styles, with every design that a reviewer
  killed either rebuilt on a different technique or replaced. Three concepts were cut outright
  (§ What we are not doing) and one was promoted in to keep the count.
- Performance budgets computed against the target this app actually runs at - 50 fps, at
  `supersampleFactor`-inflated resolution - not against 60 fps at 1080p.
- A licence position stated per source, with the rule written down and the unshippable sources named.

It does not commit to a schedule. The build order in § Build order is a dependency order with rough
sizes, and the phase boundaries are where a measurement should change the plan.

## What exists today

One hyperspace style and one cymatics style. Both are good and both are structurally incapable of
being the first of ten.

**`HyperspaceScene` (`render/scene/HyperspaceScene.kt`, `res/raw/hyperspace_frag.glsl`).** A single
fullscreen fragment pass that raymarches up to eight distance-estimated fractal bodies, each with its
own rotation, orbit, colour and life, through a five-act journey driven by loudness. It owns a
`MeltField` - a full velocity-and-dye `FluidSim` anchored to the world plane - and the coupling runs
both ways: bodies lay capsules of ink along their own paths through `queueBodySplat`, and the ink's
velocity field warps the march domain through `meltAt(p)`, with `uMeltRelax = 1/(1 + 1.6*melt)`
shortening the step because the warp breaks the 1-Lipschitz bound the marcher depends on. It is the
most complete thing in the codebase and the melt coupling is the model the rest of this plan follows.

What it does well: the body lifecycle (`BloomBank`, `HyperspaceMath.kt:695-847`) is genuinely good -
spawn gate, birth/grow/wither envelope, held wither on retire, compaction in `snapshot`. The bound-
sphere cull with `df = max(df, bound)` correctly clips three unbounded estimators. `MeltMath.reach`
inflates the bounding radii by exactly the displacement ceiling, and a test pins the identity.

**`CymaticsScene` (`render/scene/CymaticsScene.kt`, `res/raw/cymatics_field_frag.glsl`).** One
fullscreen pass over a scalar field summed from up to 8 modes of a 105-entry table, in two geometries
(square plate, circular dish). The resonator bank (`CymaticsPlate`) is the good part: band whitening
over a +/-4-band local mean, `max` rather than `sum` into each mode because several bands land on one
mode in the low octaves, persistent per-mode phase so a mode leaving and re-entering the top 8 does
not jump, and a sum normalisation that only ever scales down.

What it does well: the pitch law. `wavenumber = sqrt(hz / f0)` is the correct inverse of a stiff
plate's `f proportional to k^2`, which is why an octave up is only sqrt(2) finer and why a 40 Hz -
16 kHz spectrum fits inside `MAX_ORDER = 14`. The shading stack - filigree, halo, caustic,
iridescence, relief - is entirely reusable and is currently trapped inside one scene's `main()`.

### Why a family of ten cannot be presets of either

These are specific blockers, not a general complaint.

1. **Nothing in this app has ever rasterised depth-tested geometry.** Every FBO in the tree is
   colour-only: `FluidBuffers.Fbo.create` attaches `GL_COLOR_ATTACHMENT0` and nothing else, and
   `VisualizerRenderer.TargetFbo` likewise. The only four references to `GL_DEPTH_TEST` in the whole
   tree are `glDisable` calls, one of them inside `GlUtil.resetFrameState()`. Eleven of the twenty
   styles below need a depth buffer. That is a new class, not a parameter.
2. **There is no camera.** `HyperspaceCamera` produces a position and a `mat3` basis, which is all a
   raymarcher needs; the cymatics style's only spatial transform is a 2D rotate-and-scale. Nothing in
   the app has a projection matrix. Rasterised geometry and marched volumes have to agree on one.
3. **There is no way for a scene to render below `renderWidth`/`renderHeight`.** `onSurfaceChanged`
   hands every scene the supersampled size and the scene renders at it. Six of the twenty designs
   below are only affordable at 0.4x-0.7x internal scale. This was missing from the first draft of
   the foundation and every perf number in it was consequently a claim about a shader that could not
   be written.
4. **`HyperspaceScene.id` is a hardcoded `val`** (`:55`), not a constructor parameter, and the id
   appears in five places that must agree (`availableSceneIds`, `createScene`, the export factory,
   the `VisualsHub` tab, `isHyperspaceSceneId`). Only the first two are checked by a test.
5. **`MeltField` is fixed at `SIM_RES = 96`, `DYE_RES = 256`, 14 pressure iterations** and an emitter
   config in an `apply{}` block, and `MeltMath.DEFAULT_SCALE = 2.6f` is passed to six call sites with
   no per-style override. A second style with a different world scale has no way to say so.
6. **`HyperspaceMath.MAX_BLOOMS = 8`** sizes the bloom pool, four uniform arrays, `prevBodyXy`,
   `hasPrevBody`, `bodyTarget`'s clamp and a `#define MAX_BLOOMS 8` in the shader - two independent
   declarations with nothing enforcing agreement at build time.
7. **`CymaticsMath.Mode(n, m)` derives one ordering key, `wavenumber = sqrt(n*n + m*m)`.** That is
   the square-plate metric, and it is currently used to rank modes for the circular dish too, which
   is the wrong quantity - a membrane is ordered by the Bessel zero `j_{n,m}`, a shell by its own
   flexural law, a rectangular cavity by `sqrt(nx^2 + ny^2 + nz^2)`. The `vec4` mode packing also has
   no room for a third integer order, which one style below genuinely needs.
8. **There is no per-frame pitch content anywhere.** `KeyDetector` accumulates over a whole track and
   finishes once. Six of the cymatics designs need a chromagram every frame.
9. **The preview harness covers `HyperspaceScene` and the 22 `ShaderScene` styles only.** It is the
   only thing in the repo that can look at a frame before a device build, and its driver contract
   (`tools/shaderpreview/lib/scenes.mjs:18-296`) has to be implemented once per new style.

Two smaller items that are corrections rather than blockers: `CymaticsMath.kt:26` claims every
formula has a twin in `cymatics_plate_vert.glsl`, which does not exist (the twin is
`cymatics_field_frag.glsl`); and the same file's attribution to an unlicensed GitHub project should
cite the published mathematics instead (§ Licence position).

## The shared foundation

Nine pieces, one package for the parts both families need and one package each for the parts they do
not. The first draft had two independent foundations; a review comparing them found that
`GeoTarget`/`CymaticStage`, `HyperCamera`/`CymaticCamera`, `HyperMesh`/`HeightMesh`,
`lib_hyper_film`/`lib_iridescence` and `lib_volumetric`/the plume marcher were five duplicated
objects. They are single objects here.

### 1. `render/space/DepthStage.kt` - the depth problem

The hard blocker. Owns a `GL_DEPTH_COMPONENT24` renderbuffer and two modes:

- **Attached mode** (full internal resolution). `attach(callerFbo)` binds the depth renderbuffer to
  the FBO the renderer already gave the scene with `glFramebufferRenderbuffer`, enables depth test
  and depth write, and clears depth. `detach()` calls
  `glInvalidateFramebuffer(GL_FRAMEBUFFER, [GL_DEPTH_ATTACHMENT])` **before** unbinding, then detaches
  and `glDisable(GL_DEPTH_TEST)` so `GlUtil.resetFrameState()`'s post-condition still holds. The
  invalidate is not optional: a depth renderbuffer that is not invalidated is written out to main
  memory every frame, which at 1080x2400 is about 10 MB/frame - roughly 500 MB/s of pure waste on a
  part with about 25 GB/s of total bandwidth. This mode costs zero extra passes and zero extra
  allocations.
- **Owned mode** (reduced internal resolution). `ResTarget` (below) owns colour + depth together, and
  the resolve blit that scales the result back up is the pass you were already paying for.

Snapshots `GL_FRAMEBUFFER_BINDING` and `GL_VIEWPORT` into preallocated `IntArray` fields, per the
`HotPathReuseTest` convention (`FluidScene.kt:58-60`).

*What changed:* the first draft specified a separate full-resolution RGBA8 colour texture plus a
fullscreen blit for five hyperspace styles, and did not mention invalidation. Both were flagged. The
attached mode deletes a full-resolution pass and a full-resolution allocation from every style that
runs at native scale.

### 2. `render/space/ResTarget.kt` - internal resolution scaling

**This did not exist in the first draft and six styles' budgets depended on it.** There is no
mechanism today for a scene to render below `renderWidth`/`renderHeight`.

An RGBA16F colour texture plus (optionally) a depth24 renderbuffer at `round(scale * renderWidth) x
round(scale * renderHeight)`, with `scale` from the quality ladder. `begin()` snapshots and binds;
`resolve()` restores the caller's FBO and viewport and blits with a 5-tap Catmull-Rom upsample and a
mild sharpen. A second entry point, `resolveBilateral(depthTex, callerDepthTex)`, does the depth-aware
upsample the two volumetric styles need.

Derive the default from the supersample factor rather than hardcoding it:
`scale = baseScale / VisualizerRenderer.supersampleFactor()`. A fixed 0.7 lands at native on a
mid-range phone and at 0.7x of native on a flagship, which is backwards - the flagship has the
headroom.

### 3. `render/space/SpaceCamera.kt` - one camera

Perspective projection with a near/far pair that the marched and rasterised styles share, so both can
write the same depth buffer. Allocation-free: `view`, `proj`, `viewProj`, `invViewProj` as
`FloatArray(16)` fields, `basis` as `FloatArray(9)` for the ray generation the marchers need, plus a
per-frame sub-pixel jitter for dither. Orbit/elevation/dolly rig with a per-style `CameraConstraint`.
Clocks wrap on `VisualizerRenderer.TIME_WRAP_SEC = 7100f`, for the reason the renderer's do.

### 4. `render/space/SpaceMesh.kt` - static indexed geometry

Pure Kotlin producing `FloatArray`/`ShortArray` uploaded once in `init()`. The vertex buffer holds
only the parameter-space coordinate; all displacement happens in the vertex shader, so there is zero
per-frame geometry traffic anywhere in either family. Variants: Cartesian grid with an optional
`pow(v, 1.7)` row warp and degenerate-triangle row joins; polar ring/sector grid with a cap quad at
`r = 0` and an `r^0.5`-weighted radial distribution (a plain polar grid puts 256 slivers at the
origin); subdivided icosphere; ribbon/tube strip; axis-aligned quad set.

### 5. `render/space/VolumeAtlas.kt` - flat 3D without `sampler3D`

`n` slices of `s x s` packed into one 2D texture, because 3D texture formats carry a 2x sampling
penalty on Mali before mip filtering. Owns the slice-to-offset arithmetic, the neighbour offsets for a
3D Laplacian, a hand-rolled trilinear (two bilinear taps plus a z lerp), a ping-pong pair, and - this
is the one that bites - a **one-texel edge-replicated gutter per slice**, so bilinear filtering never
bleeds across a tile border and puts a bright seam every `s` texels through the whole volume. A test
samples either side of every slice boundary.

Used by `hyper_plume` (32 slices of 64x64 in 512x256 RG16F) and `hyper_vivarium` (48 slices of 48x48
in 384x288 RG16F).

### 6. `render/space/GpuGrains.kt` - MRT particle state

Modelled structurally on `FluidParticles` (`FluidBuffers.DoubleMrt`, attachment A = position,
attachment B = age/mass/seed, state format `formats.rgba32 ?: formats.rgba`) but with a pluggable
update program, because the three consumers need three different integrators: Euler-Maruyama with
multiplicative noise (`chladni_sand`, `kundt_tube`), and Newton surface projection
(`hyper_dustskin`). Never recreated on an auto-downgrade - `create()` reseeds every particle to a
random position, which reads as a full-screen flash exactly when the device is already struggling.
The draw count is clamped instead. That is the `FluidScene` precedent.

### 7. `render/space/QualityLadder.kt` - one tier ladder

Wired to the existing `fluidQuality` / `fluidAutoQuality` params and the existing
`PerformanceMonitor(targetFps = 50f, sustainSeconds = 2.5f)`. Supplies, per tier: mesh side, march
steps, march scale, grain count, atlas slices, `ResTarget` scale. Two properties of the existing
monitor that every budget below is written against:

- **The target is 50 fps, not 60.** Budgets computed against 60 are 20 percent optimistic before
  anything else.
- **`FluidQuality.effectiveIndex` only ever lowers**, and the auto latch never upgrades within a
  session. A style that needs its top tier to read correctly will spend most of a session looking
  wrong with no path back. Every style below states what its lowest tier actually looks like, and
  where that is a real loss it says so.

### 8. Family bases

**`render/hyper/HyperSceneBase.kt`** takes `id` as a constructor parameter (the hardcoded `val` at
`HyperspaceScene.kt:55` blocks a second style outright) and owns what `HyperspaceScene` currently owns
inline: the `HyperFluid`, the `SpaceCamera`, the `BodyBank`, the FBO/viewport snapshot fields, the
`programOk` guard that `SceneFailureTest` requires within the first 3 non-comment lines of `draw`, the
`onShaderError` wiring that the same test requires every `catch (e: GlUtil.ShaderCompileException)`
block to call, and a `stir()` template method - the one place a style couples bodies back into the
medium.

**`render/hyper/HyperFluid.kt` + `HyperFluidMath.kt`** generalise `MeltField`/`MeltMath`.
`HyperFluid(context, config: HyperFluidConfig)` takes the grid sizes, iteration count, emitter config
and world scale as data. It adds three things every consumer needs: `queueSourceSplat(x, y, dRdt)` (a
purely radial, divergent splat), `readbackGrid(): CpuGrid` (a 32x32 RGBA32F copy pass plus a 16 KB
`glReadPixels`, modelled on `FlowField.readback` at `FlowField.kt:293-316` including its
self-snapshot/restore, gated on `formats.rgba32 != null` and degrading to a zero grid), and
`setInjectionShaders(force, dye)` passed through to the `FluidSim` seam at `:324` that nothing
currently installs. `HyperFluidMath` is `MeltMath` with `scale` as an explicit parameter;
`MeltMath` delegates to it, so there is one implementation and `HyperspaceMeltTest`'s reach/inflation
identity keeps covering both.

**`render/hyper/BodyBank.kt`** is `BloomBank` with capacity as a constructor argument and the payload
width pluggable, keeping the whole proven lifecycle. **Uniform budget warning:** the existing pattern
is four `vec4` uniform arrays plus a `mat3` array. At capacity 8 that is 48 vec4-equivalents, already
about 21 percent of the ES 3.0 `GL_MAX_FRAGMENT_UNIFORM_VECTORS` floor of **224**. At capacity 64 it
is over the floor on its own. Any bank above about 12 bodies routes through instance attributes (for
rasterised styles) or a uniform block (ES 3.0 guarantees 12 fragment blocks of 16 KB). The preview
harness cannot see this - SwiftShader reports 4096 - so it is a rule, not something a test will catch.

**`render/hyper/HyperStyle.kt`** turns `HyperspaceLook` (`HyperspaceMath.kt:1078-1151`, already a pure
object of one-line functions) into an interface with `spread`, `bodySize`, `cameraDistance`,
`farPlane`, `marchBudget(detail)`, `fluidConfig`, `HIT_EPSILON` and `BOUND_MARGIN` per style.
`HyperspaceLook` becomes one implementation.

**`render/cymatic/ModalBank.kt`** replaces `CymaticsPlate`. Two changes. `Mode(a, b, c, key: Float)` -
three integer orders plus a **geometry-supplied ordering key**, because `sqrt(n^2+m^2)` is the square
plate's metric and is wrong for every other geometry here. And the ad-hoc attack/release envelope is
replaced by the forced-response weight from Tuan et al., `C_n proportional to Psi_n(x',y') /
((K_n^2 - k^2) - i*gamma)` - one complex divide per mode yielding amplitude **and** phase. The
whitening (`localMean` over +/-4 bands, `WHITEN_GAIN = 2.6`, `max` not `sum`) and the persistent
per-mode phase array are kept verbatim; they are correct and already pinned by tests. `snapshot()`
writes into a caller array of `stride` floats per mode rather than a hardcoded 4.

**`render/cymatic/CymaticChroma.kt`** folds `AudioFeatures.bands` through the shared `bandCenterHz`
mapping into a 12-bin chromagram with a one-pole per bin. Exposes `bins: FloatArray(12)`,
`dominantPitchHz`, `top(n)`, and `confidence` (peak-to-median, so a style can hold its last good
reading through a drum fill). Allocation-free, reduced once per frame.

**It does not expose `justDetune`, and no style below claims to measure tuning.** The just-versus-
tempered fifth is 1.96 cents and the major third is 13.7 cents; a 2048-point FFT at 48 kHz has a bin
width of 23.4 Hz, which at A2 is about 180 cents, and band energies are coarser still. Four designs in
the first draft built their second morph axis on that number. It is FFT bin quantisation noise
modulated by vibrato. Chroma is also octave-invariant, so the ratio between two bins is ambiguous
(C+G is 3/2 or 4/3 and you cannot tell) and an octave interval is unrepresentable. `JustIntonation.kt`
therefore ships the small-integer ratio table and a **three-way user toggle** - JUST / AS-MEASURED /
12-TET - with no claim that anything was measured from the track. See § Open questions for whether to
add a real pitch tracker later.

**`render/cymatic/PitchClock.kt`** - the audio-to-visual frequency rescale, which four designs
silently needed and only one declared. You cannot drive a 400 Hz mode with 60 impulses per second, and
you cannot show a 200 Hz surface inverting. The rule, applied by every cymatics style:
**spatial structure from the true pitch; temporal phase from a mapped clock in 0.5-3 Hz.** The 3 Hz
ceiling is a shared constant, `PitchClock.MAX_FLASH_HZ = 3f`, because three separate designs produce a
full-field luminance event - a subharmonic lattice inversion, a beat-locked all-mode phase collapse,
and a spike-field eruption - and `VisualSafety` is structurally blind to a geometric inversion. Styles
that produce one expose its rate and luminance swing so the global limiter can see them.

### 9. GLSL libraries, registered in `GlUtil.INCLUDES`

`GlUtil.kt:61` currently holds four entries and the cymatics style uses none of them. Eight more, all
leaf libraries, all in `app/src/main/res/raw/`:

| File | Contents | Consumers |
|---|---|---|
| `lib_sdf.glsl` | iq normalised smin family (quadratic/cubic/circular), union/subtraction/intersection, onion, four-tap tetrahedron normal, `sdSphere`/`sdBox`/`sdTorus`/`sdCapsule`/`sdSegment`, `hsv2rgb`, the IGN dither, the adaptive `eps = max(e0, alpha*t)` law | polytope, membrane, foam, dustskin, levitator, spikes |
| `lib_shade.glsl` | key/fill lights, fresnel, AO, haze, vignette, exposure - currently baked as bare literals at `hyperspace_frag.glsl:730-810` | all marched styles |
| `lib_volume.glsl` | Beer-Lambert, Henyey-Greenstein, front-to-back accumulation, `VolumeAtlas` trilinear fetch, transmittance early-out | plume, vivarium, chamber, caustic |
| `lib_film.glsl` | Khronos iridescence: `IorToFresnel0`, `Fresnel0ToIor`, `evalSensitivity`, the m=2 Airy sum | membrane, moire, faraday, spikes, drumhead |
| `lib_fluidsample.glsl` | `simUv`, `flowAt`, `dyeAt`, `pressureAt`, and the border `smoothstep(0.0, 0.02, min(edge))` that `hyperspace_frag.glsl:257-284` documents as the fix for a razor horizon from CLAMP_TO_EDGE extrapolation | every fluid-coupled style |
| `lib_tiling.glsl` | Poincare-disk fold, Mobius disk translation, recursive Truchet, fwidth AA helpers | moire, cortex |
| `lib_legendre.glsl` | Legendre recurrence, real-form `Y_lm`, theta/phi derivatives | shell |
| `lib_modes.glsl` | plate and membrane eigenfunctions, the Bessel approximation hoisted out of `cymatics_field_frag.glsl`, and the existing filigree/halo/caustic/iridescence/relief shading stack, which is reusable across any scalar field and is currently trapped in one `main()` | chladni, drumhead, chamber |

**Precision:** declare it per parameter in these libraries rather than letting it inherit. The preview
harness ignores precision qualifiers entirely, so `membrane`'s nanometre-scale optical path
difference, `chamber`'s ray parameter and the atlas coordinates all look fine there and break on Mali
only. `docs/DEVICE_CHECKS.md` remains the authority.

### Which medium each style uses

Not every style needs Navier-Stokes, and the first draft added one to two styles that did not - one of
them saying so in as many words ("for continuity with the family"). That leg is deleted.

| Medium | Hyperspace | Cymatics |
|---|---|---|
| `HyperFluid` (velocity + dye) | polytope, membrane, caduceus, reliquary, moire, foam, dustskin, plume, vivarium | - |
| `FluidSim` (scene-owned) | - | chladni, faraday, shell, chamber |
| `MeltField` | - | spikes |
| `RippleSim` (wave equation) | cortex | drumhead, caustic |
| `FlowField` + `FluidParticles` (shared service) | - | harmonograph, levitator, kundt, caustic |

### Files to create vs files to change

**Create** (shared foundation only; per-style files are listed with each style):

| Path | What |
|---|---|
| `app/src/main/java/dev/musicviz/render/space/DepthStage.kt` | depth attachment, both modes |
| `app/src/main/java/dev/musicviz/render/space/ResTarget.kt` | internal resolution scaling + upsample |
| `app/src/main/java/dev/musicviz/render/space/SpaceCamera.kt` | perspective camera, orbit rig, constraints |
| `app/src/main/java/dev/musicviz/render/space/SpaceMesh.kt` | grid / polar / icosphere / ribbon / quad-set builders |
| `app/src/main/java/dev/musicviz/render/space/VolumeAtlas.kt` | flat-3D atlas, gutters, trilinear, ping-pong |
| `app/src/main/java/dev/musicviz/render/space/GpuGrains.kt` | MRT particle state, pluggable integrator |
| `app/src/main/java/dev/musicviz/render/space/QualityLadder.kt` | one tier ladder for both families |
| `app/src/main/java/dev/musicviz/render/hyper/HyperSceneBase.kt` | family base, `id` as a parameter |
| `app/src/main/java/dev/musicviz/render/hyper/HyperFluid.kt` | generalised `MeltField` |
| `app/src/main/java/dev/musicviz/render/hyper/HyperFluidMath.kt` | `MeltMath` with `scale` as a parameter |
| `app/src/main/java/dev/musicviz/render/hyper/BodyBank.kt` | `BloomBank` with capacity as a parameter |
| `app/src/main/java/dev/musicviz/render/hyper/HyperStyle.kt` | the `Look` interface |
| `app/src/main/java/dev/musicviz/render/cymatic/CymaticSceneBase.kt` | family base |
| `app/src/main/java/dev/musicviz/render/cymatic/ModalBank.kt` | 3-order modes, geometry-supplied key, forced response |
| `app/src/main/java/dev/musicviz/render/cymatic/CymaticChroma.kt` | per-frame chromagram |
| `app/src/main/java/dev/musicviz/render/cymatic/PitchClock.kt` | the visual-frequency rescale and the 3 Hz ceiling |
| `app/src/main/java/dev/musicviz/render/cymatic/JustIntonation.kt` | ratio table, three-way toggle |
| `app/src/main/res/raw/lib_sdf.glsl` and seven more | see table above |
| `app/src/test/java/dev/musicviz/SpaceFoundationTest.kt` | depth invalidate contract, res scale derivation, atlas gutters, camera orthonormality |
| `tools/shaderpreview/lib/space-drivers.mjs` | the `{ id, supplies, step, jumpClock, meltConfig }` driver contract, once per style |

**Change:**

| Path | Change |
|---|---|
| `render/fluid/FluidSim.kt` | one line: `val pressureTex: Int get() = pressure?.read?.tex ?: 0`. The field is private at `:106`, is computed by 14 Jacobi iterations every frame and thrown away, and one style reads it. |
| `render/fluid/RippleSim.kt` + `res/raw/ripple_update_frag.glsl` | 9-point isotropic Laplacian `lap = (4(L+R+T+B) + (TL+TR+BL+BR) - 20C) / (6 dx^2)`, **gated behind a uniform** so `WaterScene`'s tuning does not shift silently. Two styles need it; in one of them the 5-point stencil's axis bias is amplified by a caustic and reads as a bug. Also a circular Dirichlet mask for the bounded vessels. |
| `render/fluid/MeltMath.kt` | delegate to `HyperFluidMath`; `scale` becomes a parameter |
| `render/VisualizerRenderer.kt` | a `SPACE_SCENES: Map<String, (Context) -> Scene>` so `availableSceneIds()` (`:685`), `createScene()` (`:733`) and `exportSceneFactory()` (`:1507`) are built from one list. `RendererWiringTest` fails the build if the first two disagree and **nothing checks the third**, where a miss silently falls through to `NebulaScene`. Also an explicit `is HyperSceneBase, is CymaticSceneBase -> SceneFamily.FLUID` branch in `compositeFamily()` (`:1352`) instead of reaching it by accident through `else`. |
| `render/scene/SceneIds.kt` | 20 new constants |
| `render/scene/SceneParams.kt` | new flat fields (no nested class - it would break `toJson`/`fromJson` and `PresetRoundtripTest`'s primary-constructor reflection) |
| `render/scene/CustomizeTab.kt` | tab entries; declaration order is on-screen order |
| `render/scene/ParamRandomizer.kt` | one `r("Exact Label")` per param; performance settings declared in `NEVER_ROLLED` with a reason, following the `hyperDetail` precedent at `:86` |
| `render/scene/GlUtil.kt` | eight `INCLUDES` entries |
| `render/scene/CymaticsMath.kt` | fix the stale `cymatics_plate_vert.glsl` comment at `:26`; reword the attribution to cite published maths |
| `ui/CustomizeTabs.kt`, `ui/VisualsHub.kt`, `ui/PresetStore.kt`, `ui/BuiltInPresets.kt` | the documented registration seams |
| `test/dev/musicviz/ParamSurface.kt` | new family entries (names must be distinct or the class `require`s at init) |
| `docs/PARAM_MATRIX.md` | regenerated - do not hand-edit; `CustomizeSurfaceTest` throws with the diff |

### Registration checklist per style

Fourteen sites. The tests that fail if you miss one are named because that is the design.

1. `res/raw/<name>_frag.glsl` (+ `_vert`) - `RecoveredShaderStylesTest` if it is a `ShaderScene`
2. `SceneIds.kt` constant - picked up by reflection in `ParticleGatingTest` and `RendererWiringTest`
3. the scene class - `SceneFailureTest` (four gates: a `catch` for every `buildProgram`, a
   `^if \(.+\) return$` guard within the first 3 non-comment lines of `draw`, every catch block calls
   `onShaderError`, and the scan still finds enough scenes)
4. `VisualizerRenderer.availableSceneIds()` - `RendererWiringTest`
5. `VisualizerRenderer.createScene()` - `RendererWiringTest` (set equality against 4)
6. `VisualizerRenderer.exportSceneFactory()` - **not checked by any test**; a miss falls through to `NebulaScene`
7. `SceneParams` fields with a `//` comment naming the domain - `CustomizeSurfaceTest`
8. `CustomizeTab` enum entry - `ParamSurface.tabBodies` errors at class init without a matching composable
9. `ui/CustomizeTabs.kt` composable named exactly `<Title>Tab`; every chip row preceded by
   `LockableChipLabel` (the label string **is** the randomizer lock key) - `ParamRandomizerFluidTest`
10. `ui/VisualsHub.kt`: tab title, `SceneList` branch, gating predicate, `CustomizePanel` filter and
    dispatch - `ParticleGatingTest`, `FluidTabGatingTest`, `ShaderLookGatingTest`
11. `ParamRandomizer.roll` section, or `NEVER_ROLLED` with a reason - `CustomizeSurfaceTest`,
    `ParamRandomizerTabScopeTest`
12. `PresetStore.toJson`/`fromJson`, defaults matching `SceneParams` literally - `PresetRoundtripTest`
13. `BuiltInPresets` variant block appended to `ALL` (the twelve generic `LOOKS` only auto-apply to
    particle and shader scenes)
14. `tools/shaderpreview/lib/space-drivers.mjs` driver. The harness performs a three-way uniform
    audit - shader-declared vs. Kotlin-uploaded vs. harness-supplied - and **refuses to render on any
    disagreement**. Any Kotlin maths the driver needs is ported into `lib/` carrying the comment that
    justifies each constant, so drift shows up in a diff.

### The budget arithmetic, done honestly

`supersampleFactor` (`VisualizerRenderer.kt:912`) returns 1.4x per axis below 1600 px longest side,
1.25x at 1600-2199, and 1.0x at 2200 and above. So:

| Device | Window | Factor | Render pixels |
|---|---|---|---|
| Flagship | 1080x2400 | 1.0x | 2.59 Mpx |
| Mid-range | 1080x1920 | 1.25x | 3.24 Mpx |
| Budget | 720x1520 | 1.4x | 2.14 Mpx |

The mid-range device - the one that throttles - gets the largest pixel count. At the
`PerformanceMonitor` target of **50 fps**, 3.24 Mpx is 162 Mpx/s, so one ALU op per pixel per frame
costs 162 MFLOP/s. Against 300-600 GFLOP/s sustained (peak times the 50-60 percent you actually get on
a texture-fetching marcher after throttle), the whole-frame budget is roughly
**1,000-2,200 ALU ops per pixel** at native scale. At `ResTarget` 0.6x that is 0.36x the pixels, so
2,800-6,000. Those are the numbers every budget below is written against.

Two corrections to how the first draft counted:

- **It counted ALU and ignored fetch and blend.** On Mali the texture unit returns one bilinear
  filtered texel per clock per quad, and a Mali-G57 MC2 tops out around 2 Gtex/s. Several designs
  were 15x under because they costed a distance evaluation as one FLOP. The rule for this family:
  **no style gets more than one dependent texture fetch per march step, and no style gets more than
  one large additive point cloud.** Additive `GL_POINTS` into an HDR target is blend serialisation,
  not ALU.
- **Warp coherence is worth real money.** Bifrost quad is 4 threads, Mali-G52/G76 is 8, Valhall is 16,
  and the whole warp runs to the maximum step count of its slowest lane. A style with a uniform
  early-out threshold and a low-frequency field costs much less than its worst-case count suggests; a
  style with a runtime branch inside an unrolled loop costs much more, and on Adreno can spill to
  scratch, which is a 5-10x cliff the harness cannot see.

## Licence position

**The rule this project follows.** Copyright covers expression - source code, prose, figures, preset
files - not mathematical methods, equations or numerical constants. So: implement from the equation,
never paste the listing, unless the licence is MIT, BSD, Apache-2.0 or CC0. Every third-party GLSL
file keeps its original header comment unmodified at the top of the file. `THIRD_PARTY_NOTICES` gains
one entry per source with URL, SPDX identifier, retrieval date, and the elected licence where a source
is dual-licensed. Where a licence could not be verified from the LICENSE file itself, the entry below
says **"unverified - do not take code"** and the technique is reimplemented from the published maths.

Two blanket prohibitions, because both come up constantly in this subject area:

- **Shadertoy's default is CC BY-NC-SA 3.0 Unported**, not "no licence stated". NonCommercial bars a
  paid or ad-supported app; ShareAlike would force us open. Every Apollonian, Kleinian, cloud and
  Chladni shader that comes up first in a search on these topics is under it. Nothing in this plan
  takes a line from Shadertoy. Individual shaders carrying an explicit MIT header in their source are
  fine, per shader, with the header screenshotted.
- **`iquilezles.org` article snippets are MIT** (the site states it), and that covers the smin family,
  the SDF primitives and operators, the onion, the tetrahedron normal and the domain-warp recipe. His
  *Shadertoy artworks* are separately protected. We take from the articles only.

| Source | URL | Licence | We take |
|---|---|---|---|
| iq, distance functions / smin / domain warp | https://iquilezles.org/articles/ | MIT (stated on site, snippets only) | **CODE**, notice reproduced in the shader header |
| iq, quaternion Julia (analytic Jacobian normal) | https://iquilezles.org/articles/juliasets3d/ | MIT | CODE (reference only; no style ships it) |
| Khronos `KHR_materials_iridescence` | https://registry.khronos.org | Khronos grants a royalty-free copyright licence to use and reproduce the unmodified specification for any purpose | **TECHNIQUE** - implementing the described algorithm is the intended use |
| KhronosGroup/glTF-Sample-Renderer | https://github.com/KhronosGroup/glTF-Sample-Renderer | Apache-2.0 | **CODE** for `lib_film.glsl`, with attribution and a NOTICE entry |
| Belcour & Barla, thin-film microfacet BRDF | https://belcour.github.io/ | ACM copyright; the code zip states no licence | **NOT SHIPPABLE as code.** We use the Khronos route instead. |
| ARM OpenGL ES SDK for Android | https://github.com/ARM-software/opengl-es-sdk-for-android | MIT (c) 2012-2017 ARM Limited | TECHNIQUE - the GPGPU-state-in-textures pattern on Mali; unmaintained |
| Arm Bifrost/Valhall shader-core notes | https://developer.arm.com/community/arm-community-blogs/ | vendor guidance, no code | TECHNIQUE - register pressure, warp width, texture cost multipliers |
| Samsung GameDev OpenGL guidance | https://developer.samsung.com/galaxy-gamedev/resources/articles/opengl.html | vendor guidance, no code | TECHNIQUE - avoid `discard`, cache program binaries |
| WebGL-Fluid-Simulation (Dobryakov) | https://github.com/PavelDoGreat/WebGL-Fluid-Simulation | MIT | Already in `THIRD_PARTY_NOTICES` and already the basis of `FluidSim`. **Note:** its README credits two GPL ancestors (`mharrys/fluids-2d` GPL-2.0, `haxiomic/GPU-Fluid-Experiments` GPL-3.0). Nothing new is taken from it; new solver work is derived from Harris/Stam. |
| Harris, *Fast Fluid Dynamics Simulation on the GPU*, GPU Gems ch. 38 | developer.nvidia.com | Addison-Wesley, all rights reserved on the book text | **TECHNIQUE ONLY** - the pass structure, Jacobi alpha/beta, boundaries, and the buoyancy term. No Cg listings copied. |
| Stam, *Stable Fluids* (1999) / *RT Fluid Dynamics for Games* (2003) | graphics.cs.cmu.edu | academic copyright | TECHNIQUE ONLY |
| Bridson, Hourihan & Nordenstam, *Curl-Noise* | https://cs.ubc.ca/~rbridson/docs/bridson-siggraph2007-curlnoise.pdf | ACM copyright, author-hosted | TECHNIQUE ONLY - and specifically the rule that you modulate the potential, not the velocity |
| Keinert et al., *Enhanced Sphere Tracing* | https://www.lgdv.tf.fau.de/publications/enhanced-sphere-tracing/ | Eurographics copyright | TECHNIQUE ONLY (no style ships it - see § What we are not doing) |
| Balint & Valasek, *Accelerating Sphere Tracing* | https://people.inf.elte.hu/csabix/publications/articles/eurographics-2018-shortpaper.pdf | authors/Eurographics; **their accompanying GLSL states no licence** | TECHNIQUE ONLY, and not used by any shipping style |
| Coulon, Matsumoto, Segerman & Trettel, *Ray-marching Thurston geometries* | https://arxiv.org/abs/2010.15801 | authors / Exp. Math. 31(4) | TECHNIQUE ONLY - the fold-to-fundamental-domain framework and the \|J\| = 1 argument |
| hypVR-Ray | https://github.com/mtwoodard/hypVR-Ray | **no licence file** = all rights reserved | **DO NOT TAKE CODE** |
| mwalczyk/hopf | https://github.com/mwalczyk/hopf | **unverified - do not take code** (LICENSE fetch 404'd) | nothing |
| Wikipedia: 24-cell, 600-cell, Apollonian gasket, Mandelbox, harmonograph, Truchet, spherical harmonics | en.wikipedia.org | CC BY-SA | **FACTS ONLY** - mirror normals, facet counts, Descartes theorem, the four-term damped form. No prose pasted (BY-SA would require share-alike). |
| Schoen (1970) gyroid, Schwarz P and D level sets | https://minimalsurfaces.blog/home/repository/triply-periodic/gyroid/ | published mathematics | FACTS, implemented clean-room |
| fractbox-engine | https://github.com/fractbox/fractbox-engine | MIT (c) 2026 Vladimir Weinstein | TECHNIQUE - the derivative-rule table used as a cross-check that isometric folds need no derivative bookkeeping |
| Fragmentarium / Mandelbulber2 / projectM / Hydra | github | GPL / GPL-3.0 / LGPL-2.1 / AGPL-3.0 | **NOT SHIPPABLE.** Fragmentarium's *blog articles* are the clean-room source for KIFS folds; the bundled `.frag` files are GPL. |
| Tuan, Lai, Wen, Huang & Chen, *Point-driven modern Chladni figures with symmetry breaking*, Sci Rep 8 (2018) | https://pmc.ncbi.nlm.nih.gov/articles/PMC6052176/ | **CC BY 4.0** | **CODE AND TEXT** reusable with attribution. The most directly usable source in the plan: the point-driven Green's function, the complex resonance denominator, the exciter numerator and the orthotropy split. |
| Zhou, Sariola, Latifi & Liimatainen, Nat Commun 7:12764 (2016) | https://pmc.ncbi.nlm.nih.gov/articles/PMC5023966/ | **CC BY 4.0** | TECHNIQUE with attribution: nodes are attractors of a chaotic system; the fine-particle inverse-Chladni regime |
| Gander & Kwok, *Chladni Figures and the Tacoma Bridge*, SIAM Review 54(3) | https://www.unige.ch/~gander/Preprints/ChladniTacoma.pdf | SIAM copyright, author preprint | **FACTS ONLY** - the biharmonic operator and free-edge Kirchhoff conditions, used to document in code why the cosine basis is an approximation |
| Bressloff, Cowan, Golubitsky, Thomas & Wiener, Phil. Trans. R. Soc. B 356 (2001) | https://pmc.ncbi.nlm.nih.gov/articles/PMC1088430/ | Royal Society copyright | TECHNIQUE ONLY - the retinocortical map, the fovea-corrected form, the planform classification |
| Tamekue, Prandi & Chitour | https://arxiv.org/html/2212.04827 | arXiv | FACTS - the exact `r e^{i theta} -> (log r, theta)` statement and the tunnel/funnel/spiral forms |
| Kluver, *Mescal* (1926/1928) | - | historical | FACTS - the four form constants |
| Pearson, *Complex Patterns in a Simple System*, Science 261:189 (1993) | science.org | AAAS copyright | **FACTS** - the Gray-Scott equations and the (f,k) atlas. Cited to Pearson, **not** to karlsims.com, which is explicitly all rights reserved. |
| karlsims.com/rd.html | https://www.karlsims.com/rd.html | "All rights reserved" | **DO NOT TAKE CODE OR IMAGES.** Parameter values are facts. |
| mrob.com xmorphia | https://mrob.com/pub/comp/xmorphia/ | site terms unverified | FACTS ONLY (the (f,k) parameter-space map) |
| jasonwebb/reaction-diffusion-playground | github | CC BY-NC-SA 4.0 | **NOT SHIPPABLE** |
| amandaghassaei/ReactionDiffusionShader | github | **no licence file** | **DO NOT TAKE CODE.** Superseded by gpu-io. |
| amandaghassaei/gpu-io | https://github.com/amandaghassaei/gpu-io | MIT (c) 2020 Amanda Ghassaei | CODE permitted; used only as a reference to check our RD against |
| Witkin & Kass, *Reaction-Diffusion Textures*, SIGGRAPH 1991 | https://cs.cmu.edu/~aw/pdf/texture.pdf | ACM copyright, author-hosted | TECHNIQUE ONLY - the anisotropic diffusion matrix `Tr(D^T Q^T H Q D)` |
| Lange, Richter & Tobiska, Rosensweig instability | https://wwwpub.zih.tu-dresden.de/~adlange/download/lin_nonlinRosen.pdf | Wiley copyright, author-hosted | **EQUATIONS ONLY** - energy functional, amplitude equation, `B_c`, `q_c`, `q_m` |
| Kumar & Tuckerman (JFM), Faraday parametric instability | journal | Cambridge copyright | **EQUATIONS ONLY** - the Mathieu form, resonance tongues at `omega/omega0 = 2/n`, threshold. *Replaces* the CC BY-NC-SA utoronto lab manual the first draft cited for the same equations. |
| Gor'kov (1962) acoustic radiation potential | - | classical acoustics | FACTS |
| Thomas, Int. J. Bifurcation Chaos 9(10) (1999) | - | published mathematics | FACTS - the ODE and the b ~ 0.208 bifurcation |
| Wang, Juttler, Zheng & Liu, *Computation of rotation minimizing frames*, ACM TOG 27(1) | - | ACM copyright | TECHNIQUE ONLY (referenced but not used - see caduceus) |
| Vogel (1979), golden-angle phyllotaxis | - | published mathematics | FACTS - 137.50776 degrees, `r = c sqrt(n)` |
| Yuksel & Keyser, height-field caustics | https://www.cemyuksel.com/research/heightfield_caustics/ | Springer, author preprint, no code offered | TECHNIQUE ONLY |
| Jorge Jimenez, Interleaved Gradient Noise | via https://blog.demofox.org/2020/05/10/ray-marching-fog-with-blue-noise/ | blog, no licence; shared by Jimenez on condition of credit | **ONE shared entry** in `THIRD_PARTY_NOTICES` crediting Jimenez, not three independent hand-waves. A one-line formula and a scroll constant. |
| Blue-noise textures (Peters, momentsingraphics.de) | https://momentsingraphics.de/BlueNoise.html | **unverified - do not take code** (widely reported CC0; not confirmed here) | nothing until verified; the repo already ships `blue_noise_64.bin` |
| maximeheckel.com volumetric raymarching | blog | **no licence stated**, and it credits Shadertoy authors | **DO NOT TAKE CODE.** Used only for measured step-count figures; the maths is textbook. |
| Dan Russell, circular membrane mode ratios | https://www.acs.psu.edu/drussell/demos/membranecircle/circle.html | site copyright | **NUMBERS ONLY** (independently recomputed here and matching). No images or animations. |
| euphonics.org 3.6.1 | https://euphonics.org/3-6-1-vibration-modes-of-a-circular-drum/ | not stated | FACTS - standard textbook separation of variables |
| phys.libretexts.org | - | CC BY-NC-SA 4.0 | **DROPPED.** The first draft cited it for the same separation of variables already covered by euphonics. There is no reason to carry an NC entry into the notices for equations sourced elsewhere. |
| physics.utoronto.ca Faraday lab manual | - | CC BY-NC-SA 3.0 | **DROPPED**, replaced by Kumar & Tuckerman, same reason. |
| hilbertcube/Chladni-Patterns-Generator | https://github.com/hilbertcube/Chladni-Patterns-Generator | MIT | reference only - read to sanity-check eigenvalue ordering against a real FEM solve. No code taken (Python). |
| codenlighten/3D-Cymatics | https://github.com/codenlighten/3D-Cymatics | **no licence** | **DO NOT TAKE CODE.** `CymaticsMath.kt`'s doc comment currently names this as "the reference project this style is modelled on". The formula it names is textbook and appears in Gander & Kwok and in LibreTexts, so there is no infringement - but the comment reads as derivation from an unlicensed work and is reworded to cite the published maths. |
| Beer-Lambert, Henyey-Greenstein (1941), Snell, Mathieu, Helmholtz, capillary-gravity dispersion, Descartes circle theorem, Blackburn pendulum, Rayleigh streaming | - | textbook physics | FACTS |

**Not shippable, and what we do instead:** Fragmentarium, Mandelbulber2, projectM, Hydra, hypVR-Ray,
karlsims.com, jasonwebb's RD playground, amandaghassaei/ReactionDiffusionShader, Belcour & Barla's code
zip, Balint & Valasek's GLSL, ttnghia's narrow-range-filter reference, and every unheadered Shadertoy
shader. In each case the mathematics is published and reimplemented from the equation, and where a
permissive equivalent exists (Khronos/Apache-2.0 for iridescence, gpu-io for reaction-diffusion,
Tuan et al. under CC BY 4.0 for the whole Chladni forced-response model) that is the one we use.

## The ten hyperspace styles

### Polytope - `hyper_polytope`

**Look.** Vaulted, mirror-bright architecture assembled from the 3-space slice of a 24-cell or
600-cell. Facets are flat and hard-edged with sharp dihedral seams, and nested shells recede into haze
so you always see three or four chambers deep. As the 4D rotation advances, walls slide through each
other: a column thins to nothing and vanishes, a new arch grows out of empty air where there was no
geometry at all, and the cell count changes without anything having moved in 3-space. The onion-shell
hollowing makes it read as leaded glass rather than poured concrete. Colour is per-chamber, keyed off
the Coxeter fold count, so neighbouring cells are related but never identical. Over a track the slice
offset drifts and the two rotation angles beat against each other at incommensurate rates, so the
floorplan at minute four shares nothing with minute one. On a hard downbeat the slice offset steps and
the room re-deals itself in one frame.

**Technique.** Exact 4D SDF restricted to a 3-plane: `map(p) = D4(R4 * vec4(p, w0))`. The load-bearing
theorem is two lines: the inclusion `R^3 -> R^4` is an isometry, so the restriction of a 1-Lipschitz
`D4` is 1-Lipschitz in `(x,y,z)`. Sphere tracing runs unmodified with no step multiplier. `D4` folds
to the Coxeter fundamental chamber of F4 (24-cell) or H4 (600-cell) with 6-15 conditional mirror
reflections `if (dot(q,m) < 0.0) q -= 2.0*dot(q,m)*m;` - each has `|J| = 1`, so no derivative
bookkeeping - then one half-space test `dot(q, n) - c`, hollowed with the onion `abs(d) - shell`.
Exact 4D primitives available for blending:

```
sd4Ball(q, r)          = length(q) - r
sd4Box(q, b)           = length(max(d, 0.0)) + min(max(d.x, max(d.y, max(d.z, d.w))), 0.0),  d = abs(q) - b
sd16Cell(q, s)         = (|q.x| + |q.y| + |q.z| + |q.w| - s) * 0.5        (the L1 ball, scaled by 1/sqrt(4))
sd4Duocyl(q, r1, r2)   = length(max(d, 0.0)) + min(max(d.x, d.y), 0.0),   d = vec2(length(q.xy) - r1, length(q.zw) - r2)
```

`R4` is built on the CPU each frame. Do **not** use 4D perspective projection: it is not an isometry
and destroys the Lipschitz property. Slice, never project.

**Morph.** Three axes, none of them a field lerp, so the gradient-collapse failure that kills naive SDF
morphing does not exist here. (a) The two SO(4) plane angles advance at incommensurate rates -
`alphaDot = 0.11 + 0.30*bass`, `betaDot = 0.071 + 0.19*mid` - so the slice sweeps continuously and
cells appear and disappear rather than growing and shrinking. This is a morph with no 3-space image:
geometry arrives from nowhere. (b) `w0`, the slice offset, walks a slow Lissajous over +/-0.85. Free -
it changes nothing about convergence - and it produces a completely different-looking morph from (a),
because it changes which cross-section you are in rather than how it is oriented. (c) The mirror count
ramps 6 -> 15 on energy, interpolating 24-cell ornament density into 600-cell density; each extra
mirror is an isometry, so the distance estimate is untouched.

**Fluid coupling.** `HyperFluid.readbackGrid()` is reduced on the CPU to a mean flow direction and
magnitude, and those two scalars become a **third and fourth bivector rotation composed into `R4`**:
`R4 = rot4(0,1,alpha) * rot4(2,3,beta) * rot4(2,3,v.x*twist) * rot4(0,3,v.y*twist)`. The fluid rotates
the fourth dimension, and because `R4` is constant over the frame it is an exact isometry, so the
Lipschitz bound survives exactly and the coupling costs nothing per sample. `dyeAt(p)` is read once per
march step and key-lit onto the facets so ink reads as wet stone. Backward: `PolytopeMath` folds 8 seed
points through the same mirror stack on the CPU (8 x 15 dot-and-compare, negligible) to get the
projected 3-space centroids of the largest chambers, and calls `queueBodySplat` along each centroid's
frame-to-frame path. The architecture's own sliding drags the medium, and because centroid speed is
proportional to `alphaDot`, a fast 4D turn stirs hard. `queueTouchStroke` stays wired: a finger pushes
the medium, which twists the fourth dimension, which moves the walls.

**Critic's objection and the fix.** Two reviewers, one root cause. The design claimed "because
rotations are isometries the Lipschitz bound survives exactly - this style pays literally nothing for
its coupling", while applying `q.zw = rot(v.x*uFlowTwist)*q.zw` **per march sample** with `v = flowAt(p)`
varying with `p`. That map has Jacobian `R(v)*di/dp + (dR/dv . grad v)*i(p)`; the second term is nonzero
wherever the flow has gradient and scales with `|p|`, so `Lip(T) = 1 + O(|p| |grad v| twist) > 1` and
the marcher oversteps and punches holes in the architecture at exactly the moments the fluid is most
active. Separately it was 4 sin/cos per DE evaluation - roughly 370 special-function ops per pixel at
88 steps plus 4 normal taps, on units that run at about quarter rate. **Fix: hoist the twist to the
CPU-built `R4`, once per frame.** A spatially constant rotation is a genuine isometry, so the
Lipschitz argument becomes true rather than asserted, and the per-sample cost really is 15
dot-and-mads with zero transcendentals. `PolytopeMathTest` asserts `R^T R = I` to 1e-5 at every angle
pair including the two flow angles. Second fix: the mirror normals move into a **uniform block** (ES
3.0 guarantees 12 fragment blocks of 16 KB) and the fold is written in the Arm-recommended
`for (i = 0; i < MAX_MIRRORS; i++) { if (i >= uMirrors) break; }` form with `MAX_MIRRORS` a `#define`,
because a dynamic-trip-count inner loop indexing a uniform array inside a 128-step outer loop is the
classic Adreno register-spill cliff. The 15-mirror mode is gated behind a measured on-device compile
and register report before it is offered at all.

**Audio.** bass -> `alphaDot` and `hxPolyShell` (walls fatten on the kick, which reads as the glass
thickening); mid -> `betaDot` and `HyperFluid.curlStrength`; treble -> facet specular exponent 40..160
and the per-fold hue jitter, so hats make the seams glitter; energy -> mirror count 6..15 and the flow
twist gain; beat -> a 120 ms eased step in `w0`.

**Params.** `hxPolyCells` 0..1 / 0.55 (24-cell to 600-cell mirror count); `hxPolySlice` 0..1 / 0.5
(`w0` drift amplitude); `hxPolyTurn` 0..3 / 1.0 (multiplier on both plane rates); `hxPolyShell`
0.005..0.12 / 0.035 (onion half-thickness, world units; 0 reads as solid stone); `hxPolyTwist` 0..2 /
0.7 (how hard the flow rotates the 4D slice); `hxPolyIso` 0..1 / 0.3 (how close alpha and beta are
locked, i.e. how isoclinic the double rotation is).

**Files.** `res/raw/hyper_polytope_frag.glsl`;
`render/hyper/PolytopeScene.kt`; `render/hyper/PolytopeMath.kt` (F4/H4 mirror normal tables, `rot4`
composition, chamber-centroid folding; pure, allocation-free);
`app/src/test/java/dev/musicviz/PolytopeMathTest.kt`.

**Budget.** One fullscreen pass, one draw call. The DE is up to 15 conditional reflections (one dot,
one compare, one mad each), one half-space test and the onion: about 70 ALU with **zero
transcendentals and zero texture fetches inside the fold** - cheaper than four of the five existing
Hyperspace species (BULB alone costs `acos + atan + 2 pow + sin + cos` per iteration). At 88 steps
that is roughly 6.2k ALU/pixel plus four normal evaluations and one dye fetch per step. Against the
1,000-2,200 ops/pixel native budget it ships at `ResTarget` 0.6x, where the budget is 2,800-6,000.
**Low:** 56 steps, 9 mirrors, shell forced up, `ResTarget` 0.45x. Still an unambiguous 24-cell, just a
coarser one.

**Risks.** At high mirror counts adjacent chambers subtend less than a pixel near the horizon and alias
into shimmer - fixed by the adaptive `eps = max(1.6e-4, 6.4e-4*t)` from `lib_sdf`, which is also the
documented cure for constant-epsilon horizon cost. The onion lets the camera end up inside a wall, and
marching from inside a `max()`-intersected region overestimates distance (unsafe); mitigated by keeping
the camera-outside invariant (`cameraDistance = max(actCamera, spread + maxBodyRadius + 0.9)`, pinned
today by `the_camera_never_ends_up_inside_a_body`) plus an analytic ray-sphere entry on the circumradius
that sets `t0` outside the shell. The 600-cell mode degenerates into grey noise if the shell is thin and
cells are sub-pixel; `shell = max(hxPolyShell, 0.012 + 0.004*mirrors)`.

**Distinct.** All five existing Hyperspace species are 3D escape-time or 3D IFS estimators warped by a
displacement that breaks their Lipschitz bound - which is why `uMeltRelax` exists. This is a 4D exact
SDF whose slice is provably 1-Lipschitz, so it is the only style in the family that pays no step
penalty for its coupling, and its morph produces geometry that appears from nothing. Not `hyper_membrane`
(unbounded periodic level set, saddles everywhere, no cells, no mirror group), not `hyper_foam` (sphere
inversion, curved shells, reads pressure not velocity), and nothing to do with `julia_frag` or
`mandel_frag`, which are 2D escape-time fields.

### Membrane - `hyper_membrane`

**Look.** A triply-periodic minimal surface fills the whole volume, a shell of soap-film thickness
threading space, so you are always inside it and looking through several layers at once. Every point
is a saddle - not one flat patch or convex bulge anywhere in frame, which is the negative-curvature
quality the phenomenology keeps reporting and which nothing else in this app produces. Colour is not
from a palette but from thin-film interference: shell thickness sets the hue, so the surface runs
magenta into teal into gold in slow bands that slide as the thickness field moves. Where dye is dense
the shell fattens and goes opaque; where it has drained the shell thins to nothing and tears open into
holes you can fly through, and you watch them open. Over a track the surface morphs between the
gyroid's two interpenetrating labyrinths, Schwarz P's cubic tubes and Schwarz D's diamond struts, and
the genus of a unit cell changes as it goes. The cell size breathes with the bass; on a hard transient
a hole punches outward from the camera.

**Technique.** Shelled triply-periodic minimal surfaces. On `q = p * uCell`:

```
Gyroid (Schoen 1970):  G = sin(q.x)cos(q.y) + sin(q.y)cos(q.z) + sin(q.z)cos(q.x)
Schwarz P (1865):      P = cos(q.x) + cos(q.y) + cos(q.z)
Schwarz D:             D = sin x sin y sin z + sin x cos y cos z + cos x sin y cos z + cos x cos y sin z
Blend:                 F = wG*G + wP*P + wD*D,   wG + wP + wD = 1
Distance estimate:     d = (abs(F) - thick) * uLip / uCell
```

`uLip` is the empirical directional bound, computed on the CPU per frame as
`0.5*wG + 0.36*wP + 0.33*wD` (0.5 is the standard gyroid figure; P and D have steeper gradients), with
a 0.6 step multiplier on top. `sin(q)`, `cos(q)`, `sin(q.yzx)` and `cos(q.yzx)` are computed once and
all three level functions read from them, so the three-way blend costs the same as one gyroid. Shading
is `lib_film.glsl`: `OPD = 2*etaFilm*thickness*cosTheta2`, Airy summation truncated at m = 2,
`evalSensitivity` as the Gaussian fit to the CIE colour-matching functions in Fourier space - the trick
that turns a spectral integral into about ten ALU ops.

**Morph.** Four axes, all exact. (a) The barycentric point `(wG, wP, wD)` walks a slow closed path on
the 2-simplex: a genuine topology morph, because the gyroid's two interpenetrating labyrinths merge into
P's single cubic network and reorganise into D's diamond, and the genus of a unit cell changes along the
way. (b) `thick` is a field, not a scalar:
`thick = uThick + uDyeThick*dye(p) + uNoiseThick*noise(p, t)`, so the shell fattens and thins spatially,
and that is what opens and closes holes - a real topology change driven by the fluid. (c) `uCell`
breathes on the bass. (d) A unit-determinant shear on `q` skews the cubic cell into a monoclinic one, so
the lattice leans without changing its volume.

**Fluid coupling.** The most structural of the ten, and it uses the seam nobody has:
`FluidSim.setInjectionShaders(forceSrc, dyeSrc)` at `FluidSim.kt:324`, routed through `runInjection`'s
extended uniform context at `:501-540` (which already supplies `uDt, uDx, uTime, uBass, uMid, uTreble,
uEnergy, uBeat`) and which `MeltField` never installs. Forward: `dyeAt(p)` sets shell thickness, and the
same value feeds the film thickness in the iridescence - so the ink colours the surface by making it
thicker, which is physically what makes a real soap film iridescent rather than a decorative
correlation. `flowAt(p)` shears the domain before the level function. Backward:
`hyper_membrane_force_frag.glsl` is a custom force-injection program that evaluates the **same** gyroid
analytically on the 96x96 sim grid and injects velocity along the surface tangent
`t = normalize(cross(gradF, up))`, scaled by `uEnergy`. The membrane's own surface flow drives the
fluid, on the GPU, at zero CPU cost, and because the injection program reads the same `uCell`, `uTime`
and `(wG,wP,wD)` uniforms, the drive cannot drift out of phase with the geometry. On top of that
`TpmsMath` evaluates `F` on a coarse CPU lattice (about 200 evaluations) to find the four to six largest
open holes and queues a body splat at each, so the tears act as drains.

**Critic's objection and the fix.** Flagged as the most expensive style in the set - not
`hyper_plume` - and undercounted by about 3x. 6 sin + 6 cos per DE evaluation is 12 special-function
ops; at 72 steps that is 864, but the design also promises four or five visible layers, and the ray does
not terminate at first hit, so each shell crossing costs a four-tap tetrahedron normal (20 more DE
evaluations = 240 more SFU) plus a full Airy evaluation per layer. About 1,150 SFU per pixel, and at
quarter rate that is roughly 4,600 ALU-equivalent cycles of trig alone. **Fixes, all three shipped:**
(1) the level functions are evaluated on `q = p*uCell`, which is periodic by construction, so `sin` and
`cos` are replaced by a wrapped degree-5 minimax polynomial - `x = fract(q/TAU)` then about 6 mads, no
SFU. That is roughly a 6x cut on the dominant cost and is exact enough for a level set whose Lipschitz
bound is already empirical. (2) Layer traversal is **hard-capped at 3 crossings**; the `T < 0.012`
early-out is a hope, not a cap. (3) Iridescence is evaluated on the front layer only and the rest are
tinted. Fourth: the design's own note that `uLip = 0.5` is invalid at the P and D corners means the
Lipschitz test must gate the **shipped** simplex path, not merely exist - `TpmsMathTest` asserts a
finite-difference gradient never exceeds the reported `uLip` over a randomised sweep of the whole
simplex, and that test is the only thing standing between this style and a shipped surface with holes
in it. It stays in the blocking set, not the slow suite.

**Audio.** bass -> `uCell` (0.8x..1.35x) and base shell thickness; mid -> the simplex walk rate and
`curlStrength`; treble -> film thickness modulation frequency (fast iridescent shimmer) plus specular;
energy -> `uDyeThick` (how much ink can tear the film) and tangential injection strength; beat -> a
radial `queueSourceSplat` at the camera position that punches a hole outward through the membrane.

**Params.** `hxMembraneFamily` 0..1 / 0.0 (0 holds the pure gyroid, 1 walks the full simplex);
`hxMembraneThick` 0.02..0.5 / 0.12; `hxMembraneCell` 0.5..4 / 1.6 (unit cells per world unit);
`hxMembraneFilm` 0..1 / 0.6 (0 falls back to flat palette shading); `hxMembraneTear` 0..1.5 / 0.7;
`hxMembraneDrive` 0..2 / 0.8 (surface-tangent force injected back into the solver).

**Files.** `res/raw/hyper_membrane_frag.glsl`; `res/raw/hyper_membrane_force_frag.glsl`;
`render/hyper/MembraneScene.kt`; `render/hyper/TpmsMath.kt` (CPU level functions, simplex walk, hole
finding, per-blend-point Lipschitz bound; pure); `app/src/test/java/dev/musicviz/TpmsMathTest.kt`
(CPU and GLSL agree at 2000 sample points, weights always sum to 1, the gradient bound holds across the
simplex).

**Budget.** After the polynomial-sine fix the DE is about 12 mads for the shared trig plus 20 ALU, and
transcendentals are gone from the inner loop. At 72 steps with three layer crossings and one dye fetch
per step that is roughly 2.9k ALU and 72 fetches per pixel. Three structural facts make it ship rather
than optimism: a TPMS has **no empty space at all**, so `uFar` can be 9 world units rather than 40 and
every ray terminates early; the transmittance early-out at `T < 0.012` kills the layered pass in dense
regions; and the three-way blend is free relative to one gyroid because the trig is shared. The
injection pass at 96x96 is a rounding error. `ResTarget` 0.6x. **Low:** the shell goes opaque (no
layering), 40 steps, `uFar` 5, and the blend locks to the pure-gyroid corner so the compiler dead-strips
P and D entirely - about 3x cheaper and still recognisably the same style.

**Risks.** Thin shells alias badly: below `hxMembraneThick = 0.05` the surface is sub-pixel at distance
and boils. Mitigated by fattening thickness with distance, `thick += 0.5*uHitEps*t`, which is the SDF
analogue of mipmapping and costs one mad. Iridescence bands visibly in mediump because OPD is
nanometre-scale against a metre-scale position; OPD stays highp and everything downstream is mediump,
and this specific bug is invisible in the preview harness and must be checked against
`docs/DEVICE_CHECKS.md`.

**Distinct.** The only style in the family with **no bodies at all** - an unbounded periodic field, so
the bounding-sphere culling, the bloom bank and the `localRadius` machinery every Hyperspace species
depends on simply do not apply, and `BodyBank` is unused. Its morph is a change in the genus of a unit
cell. Not `hyper_polytope` (finite chambers, a reflection group, flat facets, positive and zero
curvature) and not `hyper_foam` (finite convex bubbles). Nothing among the existing 37 uses a level-set
surface or thin-film shading; `metaballs_frag` is a flat 2D field with a palette.

### Caduceus - `hyper_caduceus`

**Look.** Long, wide **ribbons** - flat twisted bands, never tubes - sweep through a dark volume,
catching a key light on one face and going nearly black on the other, so the roll along their length is
legible as a roll rather than as a brightness wobble. Two or three share one trajectory family and
cross each other constantly, and because they are depth-tested geometry the crossings occlude
correctly. The ribbon is opaque at the head and additive at the tail, so you see the older writhe
through the newer and can read the history of the motion. As the attractor's control parameter drifts,
the trajectory reorganises: a single lobe splits into two, a tight coil unwinds into a slow drift.
Colour runs along arclength. Over a track the braid precesses, the ink it laid down builds into a
luminous fog, and it flies back through that fog and lights it from inside - and then **veers**, because
the ink it laid down thirty seconds ago is now bending the path it takes next.

**Technique.** Rasterised ribbon geometry with depth. The CPU integrates the Thomas cyclically-symmetric
attractor with RK4 at a fixed 240 Hz substep, keeping a ring buffer of 1024 states per ribbon:

```
xdot = sin(y) - b*x
ydot = sin(z) - b*y
zdot = sin(x) - b*z
```

The ribbon uses a **rotation-minimising (double-reflection) frame**, not a Frenet frame: Frenet's normal
flips through inflection points and the ribbon would snap 180 degrees mid-flight, which is the most
common way this technique looks broken. The reference vector is carried forward by two reflections per
segment - which is possible here, and only here, because the CPU integrates the path sequentially and
uploads vertices. Two vertices per sample, offset by
`+/- w * (cos(phi)*u + sin(phi)*cross(T, u))` with `phi` accumulating along arclength. Upload to a
dynamic VBO with `glBufferSubData` after orphaning with `glBufferData(..., null, GL_STREAM_DRAW)` to
dodge the implicit sync. Edges are feathered with `smoothstep(0.0, fwidth(v), edge)`; MSAA buys nothing
here.

**Morph.** (a) The parameter `b` morphs from 0.14 to 0.33 on a slow envelope. Below roughly 0.208 the
Thomas system is chaotic and space-filling; above it collapses to a limit cycle. So the topology of the
path changes - the braid unwinds into a clean loop and re-shreds into chaos - and that is a
**bifurcation**, not an animation. No other style here morphs by crossing one. (b) The twist rate
`dphi/ds` sweeps +/- 3 turns per unit arclength, so the ribbon rolls over along its own length
independently of where it is going. (c) Half-width `w(s)` is a function of arclength and local speed, so
the ribbon flares where the trajectory is slow and narrows to a filament where it is fast.

**Fluid coupling.** A genuine GPU -> CPU -> GPU loop, which nothing in this app currently does for a
geometry style, and **this is the style's identity**. Forward: the ODE right-hand side gains
`v += hxRibbonBend * HyperFluid.readbackGrid().sample(x, y)`. That 32x32 float grid comes from a 16 KB
`glReadPixels`, sampled bilinearly on the CPU. The fluid therefore bends the attractor's trajectory -
the shape of the object is downstream of the simulation, not merely deformed by it. Second forward path:
the fragment shader samples `dyeTex` at the fragment's world xy and adds it as emission. Backward: every
segment queues a body splat along its own path, laying a capsule of velocity and dye. Because that wake
feeds the readback, the ribbons repel and entrain each other purely through the medium - there is no
direct force between them, and the emergent behaviour is the point.

**Critic's objection and the fix.** Three, two of them real bugs. (1) **The look and the technique
contradicted each other.** "Translucent at the tail so you see the older writhe through the newer" plus
depth test on plus a single `GL_TRIANGLE_STRIP` is order-dependent alpha over unsorted self-intersecting
geometry - and a chaotic attractor self-intersects constantly, by definition. With depth write on the
translucent tail occludes everything behind it; with it off the head no longer occludes the tail and the
"real depth" claim dies. **Fix: two passes over the same VBO.** An opaque, depth-writing pass for the
head segment (age below a threshold), then a premultiplied-additive `GL_ONE, GL_ONE` pass with depth
test ON and depth write OFF for the tail. Additive is order-independent by construction - the same
argument `hyper_dustskin` relies on - it gives "see the older through the newer" honestly, and it needs
no sort. (2) **The `glReadPixels` reasoning was wrong.** Placing it at the top of `update` does not mean
"the stall overlaps nothing"; it stalls on the previous frame's in-flight work, which is exactly what is
pending at that point. The placement matches `FlowField.readback` and is fine; the comment is corrected
to say what it does - one frame of latency, accepted because these motion rates are low. (3) **It
collided with `harmonograph`** - both were a glowing extruded curve self-occluding in a dark volume with
a dust wake, and neither `distinctFrom` section mentioned the other because the two families were
designed separately. Fix: caduceus is held to a flat twisted **ribbon** (never a circular cross-section),
to **chaos and braiding** (two or three strands crossing constantly, versus harmonograph's single closed
rosette), and to the one thing harmonograph structurally cannot do - the fluid decides the trajectory.
That third point is promoted from a footnote to the headline of the look description.

**Audio.** bass -> integration rate and ribbon width; mid -> `b`, the bifurcation parameter, through a
deliberately slow envelope because the attractor's own relaxation time is seconds and fast modulation
averages out into mush; treble -> twist rate and rim intensity; energy -> `hxRibbonBend` and ribbon
count 1 -> 3; beat -> a `queueSourceSplat` burst at the ribbon head, which the readback feels a few
frames later as a shove that visibly kinks the trajectory.

**Params.** `hxRibbonCount` 1..3 / 2; `hxRibbonWidth` 0.01..0.2 / 0.055; `hxRibbonTwist` -4..4 / 1.4
(turns of roll per unit arclength); `hxRibbonLength` 128..2048 / 1024 (samples retained, i.e. visible
tail); `hxRibbonBend` 0..2 / 0.8 (how strongly the read-back flow bends the trajectory);
`hxRibbonSpread` 0.1..1 / 0.35.

**Files.** `res/raw/hyper_ribbon_vert.glsl`, `res/raw/hyper_ribbon_frag.glsl`;
`render/hyper/CaduceusScene.kt`; `render/hyper/AttractorPath.kt` (RK4 integrator, ring buffer,
double-reflection frame, ribbon vertex packing into a caller-supplied `FloatArray`; pure and
allocation-free); `app/src/test/java/dev/musicviz/AttractorPathTest.kt` (the frame stays orthonormal to
1e-4 over 10,000 steps; consecutive ribbon normals never differ by more than 90 degrees - the anti-snap
test; RK4 reproduces the known Thomas fixed points; the ring buffer allocates nothing after
construction).

**Budget.** The cheapest of the ten by a wide margin, deliberately - this is the style that stays at
50 fps when everything else has dropped a tier, which makes it the right one to leave at full internal
resolution. CPU: 3 ribbons x 240 RK4 steps/s x 3 sin is nothing; frame construction is about 30k flops.
GPU: 3 x 2048 vertices = 6k vertices, one `glBufferSubData` of about 200 KB, two draw calls, fill bounded
by the ribbons' small screen area with essentially no overdraw once depth is on. The one real cost is
the 16 KB `glReadPixels`, a pipeline stall rather than a bandwidth cost. **Low:** 1 ribbon, 512 samples,
readback disabled. Uses `DepthStage` in attached mode - no `ResTarget`, no blit.

**Risks.** On GPUs where `formats.rgba32 == null` the readback is unavailable, the bend term drops to
zero and the attractor runs unbent - the style degrades rather than failing, exactly as
`FlowField.canReadback = false` already does for CPU particle advection. Thin ribbons alias into crawling
stipple; the fwidth feather handles it. A chaotic attractor can wander out of frame; the integrator
recentres by subtracting a slowly-following centroid rather than clamping positions, so the shape is
never distorted by the framing.

**Distinct.** `AttractorScene.kt` already exists and is a 2D CPU point-sprite scene on
`ParticleSceneBase`: no depth, no geometry, no frame, no fluid, no bifurcation. This is depth-tested
triangle geometry with a rotation-minimising frame, and the only style in either family where the fluid
decides the object's trajectory rather than deforming a shape the CPU already chose - the coupling runs
through the ODE, not through the shader. Against `harmonograph`: ribbon not tube, chaotic braid not
closed rosette, fluid-in-the-ODE not fluid-in-the-wake.

### Cortex - `hyper_cortex`

**Look.** One enormous surface fills the frame, tilted away in honest perspective so the near edge is
almost within reach and the far edge dissolves into haze - the depth is real geometry, not a painted
tunnel. The surface carries a pattern that is not printed on it but **is** its height: concentric rings
marching outward, a radial fan of rays, a logarithmic spiral, or a honeycomb whose cells grow
exponentially toward the horizon. Sweeping one parameter walks continuously through all of them, so the
rings uncoil into a spiral and the spiral opens into a fan without a cut. Light rakes across at a
shallow angle so every ridge has a bright crest and a black trough. Struck by a beat, real circular
waves spread across the sheet and interfere with the standing pattern, and you watch the fringes travel.
Over a track the planform rotates and the wavelength halves and doubles, telescoping the rings.

**Technique.** A real tessellated height-field mesh: 192x192 vertices in an indexed strip with
degenerate-triangle row joins, one draw call, depth-tested through a perspective camera. Height is the
sum of two sources in the vertex shader.

(a) The Kluver planform in **cortical** coordinates. The retinocortical map is the complex logarithm,
`x = (log r, theta)`, with Bressloff's fovea-corrected form so there is no singularity at the centre:

```
x1 = (1/eps) * ln(1 + eps*r/w0)          x2 = theta
h  = sum_i cos( 2*PI*lambda * (x1*cos(phi_i) + x2*sin(phi_i)) )
```

One wave at `phi = 0` gives concentric rings (a tunnel); at `phi = PI/2` a radial fan (a funnel); three
waves at 60 degrees give the hexagonal planform. The inverse log-polar map is what makes cells grow
exponentially outward, and that radial size gradient is the entire hallucinatory quality - a plain hex
grid is wallpaper.

(b) `RippleSim.heightTex`, a real wave-equation solve, sampled at the vertex uv.

Normals are analytic for (a) - the derivative of a sum of cosines is a sum of sines, exact and free -
and a central difference of the height texture for (b). Shading is a shallow rake light plus a caustic
term from the surface's Gaussian curvature.

**Morph.** One parameter does nearly all of it. Because `x1 = log r`, a line at angle `phi` in cortical
space pulls back to `r = exp(-theta * tan(phi) + c)` - a logarithmic spiral whose pitch is set directly
by `phi`. So sweeping `phi` from 0 to `PI/2` walks tunnel -> spiral -> funnel continuously, with no
blending and no popping, and every intermediate state is itself a genuine Kluver form constant rather
than a halfway smear. Second axis: superposed wave count 1 -> 2 -> 3 morphs stripes into chequerboard
into honeycomb. Third: `lambda`, the cortical wavelength, halves and doubles on the bass - and because
of the log map the **radial** ring spacing changes non-uniformly, so the pattern telescopes rather than
simply scaling.

**Fluid coupling.** The only style in either family whose medium is a **wave equation** rather than
advection-diffusion, which makes its physics genuinely different from the other nineteen. It owns a
`RippleSim` with `inkEnabled = true`, a legitimate second consumer alongside `WaterScene`. Forward:
`heightTex` displaces the mesh directly, so those are real waves on the sheet, not a texture of waves;
and `inkTex` tints and wets it, with the ink's own gradient adding a second normal perturbation so the
ink reads as a film sitting on the relief rather than a decal painted into it. Backward, and this is the
two-way part: the planform's own crest lines emit waves. The crests of `cos(2*PI*lambda*(...))` are where
the argument is a multiple of `2*PI`, so they are closed-form and computable rather than searched;
`PlanformMath` walks a moving subset and calls `RippleSim.queueStroke` along each, so the standing
pattern continuously seeds travelling waves that interfere with it and change the surface that generated
them. Each beat is a `queueDrop` at the spectral-centroid position, and `drainTouchStrokes` is wired so
a finger drags real waves across the sheet.

**Critic's objection and the fix.** Three. (1) **It would not compile.** The stated aliasing mitigation
was "clamp the wave amplitude against screen-space vertex density estimated with `fwidth`" - but the
amplitude clamp has to be in the vertex shader, because that is where the height is computed, and GLSL
ES 3.00 makes `fwidth`/`dFdx`/`dFdy` fragment-stage only. The shader is rejected at compile, and the
design's own Nyquist argument said this clamp is what stands between the style and crawling moire that
looks like a bug, so there was no ship-without-it fallback. **Fix: compute the screen-space vertex
spacing analytically in the vertex shader from the camera.**
`d = length(uViewProj * (pWorld + rowStep) - clip) / clip.w` gives the projected spacing of the next grid
row in NDC, and the clamp is `min(uCortexRelief, k * d / uCortexLambda)`. One extra mat4 multiply per
vertex on a 36k-vertex mesh - free - and it is the correct quantity anyway, since `fwidth` in the
fragment stage would be measuring the interpolated height, not the sampling rate of the generator. (2)
**It ran two independent fluid solvers.** `RippleSim` is the medium and the coupling is genuine; then a
whole `HyperFluid` was added on top purely to warp the cortical coordinates, and the design said why in
as many words - "for continuity with the family". That is a second Navier-Stokes solve per frame to
satisfy a convention. **The `HyperFluid` leg is deleted.** `RippleSim` satisfies the fluid requirement
more convincingly than most of the set, and the wave equation being a genuinely different medium is one
of this style's strongest distinctness arguments - diluting it with a second solver weakened the claim as
well as the frame time. (3) **It collided with `chladni_sand`** - both a large flat plate in tilted
perspective as displaced geometry, rake-lit, carrying a modal pattern that reorganises with pitch. Fix:
cortex owns the log-polar map and must lean on it - exponentially growing cells toward the horizon, the
continuous tunnel-spiral-funnel sweep, no straight nodal lines anywhere - and is **forbidden a
square/Cartesian planform option**. Chladni owns matter and is forbidden any log-polar warp.

**Audio.** bass -> `lambda` and displacement amplitude; mid -> `phi`, through a slow envelope because
this is the identity of the frame and should not flicker; treble -> wave count 1 -> 3 and rake specular;
energy -> crest-line emission rate and `RippleSim.waveSpeed`; beat -> `queueDrop` at the centroid with
amplitude from the transient size.

**Params.** `hxCortexPhi` 0..1 / 0.35 (0 rings, 1 radial fan, between them logarithmic spirals);
`hxCortexWaves` 1..3 / 1; `hxCortexLambda` 0.5..8 / 2.4; `hxCortexRelief` 0..1 / 0.35;
`hxCortexSwell` 0..1.5 / 0.6 (how much `RippleSim` height adds on top); `hxCortexSeed` 0..1.5 / 0.5
(how strongly crests seed travelling waves back into `RippleSim`).

**Files.** `res/raw/hyper_cortex_vert.glsl`, `res/raw/hyper_cortex_frag.glsl`;
`render/hyper/CortexScene.kt`; `render/hyper/PlanformMath.kt` (fovea-corrected log-polar map, wave sum,
analytic derivatives, crest-line sampling; pure);
`app/src/test/java/dev/musicviz/PlanformMathTest.kt` (analytic normal matches a central difference;
`phi = 0` provably produces closed rings and `phi = PI/2` radial rays, asserted on the pullback geometry
so the mathematics is pinned rather than the code; crest samples always land inside the mesh).

**Budget.** 36,864 vertices and 73k triangles in one indexed draw. Per vertex: 3 waves x (one cos plus
one sin for the derivative), one log, one atan, one height fetch, one extra mat4 for the density clamp -
about 55 ALU, 2 SFU, 1 fetch. Fragment cost is one surface covering most of the frame with about five
fetches. `RippleSim` at the tiered 384 grid is `ceil(drops/8) x 2` splat passes plus at most 6 update
passes plus 1 ink advect - the `WaterScene` budget, already shipped and already measured on target
hardware. Second-cheapest style here after caduceus, and a good candidate for full internal resolution
with `DepthStage` in attached mode. **Low:** 96x96 mesh (a real and visible loss of ridge crispness at
the near edge), `RippleSim` at 192, wave count locked to 1.

**Risks.** The vertex-stage `heightTex` fetch is legal (ES 3.0 guarantees 16 vertex texture units) but on
Bifrost/Valhall IDVS a sampler in the position path de-optimises the split position/varying shading -
measure before assuming it is free. The near edge of a large tilted grid has enormous foreshortening and
shows its triangulation; mitigated by warping the grid rows with `pow(v, 1.7)`, a fixed cost with no LOD
system to maintain. The log-polar singularity sits in the middle of the sheet, the worst possible place;
the Bressloff correction removes it and is linear near the centre.

**Distinct.** `ripples_frag`, `waves_frag` and `WaterScene` are top-down 2D fields with no perspective,
no mesh and no camera. This is the only hyperspace style built on rasterised displaced geometry, the
only one whose medium is a hyperbolic wave equation, and the only one that renders the Kluver form
constants as **relief** rather than as a texture - which is what lets a rake light make them legible.
Not `hyper_moire` (flat parallax layers, no mesh, no waves), not `hyper_reliquary` (closed bodies rather
than one open sheet), and not `chladni_sand` (square, Cartesian, made of matter).

### Reliquary - `hyper_reliquary`

**Look.** Twenty to forty solid bodies hang in a dark volume at real depths - near ones large and
sharply lit, far ones small and hazed - and because they are opaque and depth-tested they occlude each
other properly, which reads immediately as a room rather than a collage. Each body is a closed, lobed,
radially symmetric shape with **hard quantised facets** and a bright specular hit riding every facet
edge, so they read as cut stones rather than blobs. They sit on a golden-angle Vogel packing, which is
why the arrangement looks designed instead of scattered, and the congregation turns slowly as one body
while each stone also turns on its own axis. When the music changes the stones visibly re-carve: a
four-lobed jewel grows to seven lobes, every facet re-cuts to match, smoothly rather than popping. Ink
drifts between them, and a stone passing through dense ink goes wet and saturated and drags a coloured
tail.

**Technique.** Real instanced indexed geometry. One shared icosphere at subdivision level 3 (642
vertices, 1280 triangles) in a static VBO plus an index buffer; per-instance attributes via
`glVertexAttribDivisor(location, 1)` - the pattern `ParticleSceneBase.kt:155` already uses - carrying
centre, scale, an orientation quaternion, hue, life envelope, **the sampled fluid velocity at the
instance centre**, and eight lobe coefficients. The vertex shader displaces each vertex radially by the
six-parameter lobe surface:

```
r(theta, phi) = sign(s1)*|s1|^n1 + sign(c2)*|c2|^n2 + sign(s3)*|s3|^n3 + sign(c4)*|c4|^n4
    where s1 = sin(m1*phi), c2 = cos(m2*phi), s3 = sin(m3*theta), c4 = cos(m4*theta)
```

642 evaluations per instance is nothing on a vertex unit that is otherwise completely idle in every
other style here, and it means the entire morph costs zero fragment work. Normals come from the
closed-form derivative rather than a geometry pass. Faceting is a fragment-stage normal quantisation
`n = normalize(round(n*k)/k)` with a per-facet hue offset hashed from the quantised normal, plus a
Blinn-Phong lobe at exponent 40..160. Depth test on, one `glDrawElementsInstanced`.

**Morph.** The four mode numbers `m1..m4` and four exponents `n1..n4` are per-instance and are
continuously interpolated toward audio-derived targets. Integer mode numbers give clean symmetry;
non-integer ones give a smooth crossfade between two symmetry orders. Sweeping `m3` from 4.0 to 7.0
makes a four-lobed stone grow three new lobes and re-cut every facet on the way - the object changes
its **symmetry order**, which is the "the symmetry keeps shifting" report from the phenomenology, and it
is a vertex-stage morph on real geometry rather than a field trick. Second axis: the facet level `k`
ramps 3 -> 12, so a stone dissolves from a crude gem into a smoother pearl. Third: each body's
birth/grow/wither drives scale, reusing the `lifeEnvelope` law at `HyperspaceMath.kt:413` through
`BodyBank`.

**Fluid coupling.** Forward, two paths at very different granularities. The **CPU** samples
`HyperFluid.readbackGrid()` once per instance and packs the result into the instance attribute stream,
so the congregation is advected bodily by the fluid at a cost of 40 bilinear CPU samples per frame. The
fragment shader samples `dyeTex` at the fragment's world xy and adds it as a wet, key-lit stain with a
saturation boost, so a stone passing through ink genuinely goes wet rather than getting a coloured
overlay. Backward: every live instance queues a body splat along its frame-to-frame path with radius set
to its current inflated bounding radius - the densest body-to-medium coupling of the ten, and the reason
this style's medium always looks churned rather than smooth. Separately, a stone's scale **change**
injects divergence through `queueSourceSplat`: a growing stone pushes fluid out of the way and a
dissolving one lets it rush back in, so birth and death are legible in the ink after the stone has gone.

**Critic's objection and the fix.** Four, one of which would have shipped as a crash. (1) **`pow()` on a
negative base.** The morph requires non-integer `n` ("non-integer mode numbers give a smooth crossfade"),
and `sin(m1*phi)` is negative over half its domain. `pow(x, y)` with `x < 0` is undefined in GLSL ES
3.00 and returns NaN on both Adreno and Mali. A NaN radius propagates through the analytic normal into
`gl_Position`, and a NaN vertex position does not produce a black stone - on some drivers it rasterises
an undefined-size triangle across the entire screen. The stated mitigation clamped the **radius**, which
is downstream of the NaN and clamps nothing. **Fix: the sign-preserving form shown above,** plus a test
that sweeps the whole offered `(m, n)` rectangle asserting `isNaN` is never true and `r > 0`, not a
radius-positivity check. (2) **"Samples `velocityTex` once per instance, 60 fetches rather than 38,000"
is not a thing ES 3.0 can do** - there is no per-instance stage, so it would have been 38,500 vertex
texture fetches. Harmless in cost, but it means the budget had not been reasoned about. Fix: the lookup
moves to the CPU via `readbackGrid()`, which this family already has, and rides the instance attribute
stream - which removes 38k vertex fetches for free. (3) **The per-facet hue hashed off a quantised
normal had no anti-aliasing story**; at 40 small stones it flickers per-pixel along every quantisation
boundary. Fix: `k` fades toward smooth with distance. (4) **It was not spherical harmonics.** The
six-parameter form above is Bourke's lobe surface, unrelated to `Y_lm`; calling the payload "eight
spherical-harmonic coefficients" and the file `HarmonicMath.kt` put it in direct nomenclature collision
with `harmonic_shell`, which uses genuine `Y_lm` with a Legendre recurrence. **The file is `LobeMath.kt`
and the term "spherical harmonic" does not appear in this design.** For the same reason the two styles
are separated by construction: reliquary is held to many small hard-faceted opaque stones with `k`
capped at 12 (a smooth reliquary stone is a small `harmonic_shell`), and shell is held to one large
smooth body with powder and a cast shadow.

**Audio.** bass -> `m3`, `m4` (lobe count along theta, the most visible axis) and instance scale; mid ->
`m1`, `m2` and the congregation's bulk rotation rate; treble -> facet level `k` and specular exponent;
energy -> live instance count 20 -> 40 through the spawn gate, plus advection strength; beat -> a kick on
`n1..n4` so every stone momentarily goes spiky and relaxes, which reads as a flinch running through the
congregation.

**Params.** `hxReliquaryCount` 8..40 / 24; `hxReliquaryLobes` 0..1 / 0.5 (where in the 2..9 mode-number
band the targets sit); `hxReliquaryFacet` 2..12 / 8; `hxReliquaryPack` 0..1 / 0.7 (golden-angle sphere
packing to free orbit); `hxReliquaryWet` 0..1.5 / 0.7; `hxReliquaryDrift` 0..2 / 0.8.

**Files.** `res/raw/hyper_jewel_vert.glsl`, `res/raw/hyper_jewel_frag.glsl`;
`render/hyper/ReliquaryScene.kt`; `render/hyper/LobeMath.kt` (lobe radius and analytic normal, mode-target
envelopes, Vogel phyllotaxis `theta_n = n * 137.50776 deg`, `r_n = c * sqrt(n)`; pure);
`app/src/test/java/dev/musicviz/LobeMathTest.kt` (NaN sweep over the whole offered band; analytic normal
matches a central difference to 1e-3; radius never `<= 0`; phyllotaxis placement is injective).

**Budget.** 40 instances x 642 vertices = 25.7k vertices with four sin/cos each - about 103k SFU ops, on
the vertex unit, which every other style leaves idle. 51k triangles, small on screen, no overdraw once
depth is on, one draw call. `BodyBank` at capacity 40 exceeds the fragment uniform floor if packed as
uniform arrays, so it is instance attributes - which is what the geometry wants anyway. Comfortably the
cheapest geometry style after caduceus; runs at full internal resolution with `DepthStage` attached.
**Low:** subdivision level 2 (320 triangles), 16 instances, `k` pinned at 6 - about 5x cheaper, and it
still reads correctly because this style's identity is the morph, not the polygon count.

**Risks.** Forty opaque bodies with no sort is fine, but the dye stain is additive and therefore
order-dependent if blended - so the stain stays inside the opaque depth-tested pass rather than being a
second blended pass, which keeps it deterministic. A vertex-stage morph is invisible when bodies are
small on screen, so the framing law biases toward 16 large stones at low energy and 40 small ones at
high energy rather than the reverse, and `cameraDistance` is tuned so a handful are always large in
frame.

**Distinct.** Nothing in this repo rasterises indexed 3D geometry at all - `ParticleSceneBase` instances
a flat billboard and `BeamScene` draws a 2D strip. This is the only style whose morph happens in the
vertex stage, so it costs nothing at any resolution, and the only one where the animated quantity is a
shape's symmetry **order**. Not `hyper_dustskin` (a point cloud has no surface, no facets and no
occlusion between bodies), not `harmonic_shell` (one large smooth body, real `Y_lm`, powder, a shadow),
and not the existing Hyperspace bloom bank, which is eight raymarched fractal bodies sharing one
fullscreen pass with no rasterisation and no per-body draw cost.

### Moire - `hyper_moire`

**Look.** Four sheets of stained glass hang at real depths and you can count them - the parallax is
exact, so moving the camera slides them across each other precisely as real glass would. Each carries a
hyperbolic {7,3} tiling: heptagons pouring endlessly out of the centre at the exponential rate
hyperbolic geometry demands. Individually they are too dense to read as anything but texture. Together
they beat, and the beat pattern is enormous, slow and alive - great rolling bands of light that belong
to no single sheet. **The band spacing is the musical interval**: the beat wavenumber is the difference
of the two sheets' wavenumbers, so a wide interval gives tight fringes and a near-unison gives broad
slow bands sweeping the whole stack. Thin-film colour gives each sheet its own hue, so where three
overlap the colour arrives somewhere none of them is.

**Technique.** Screen-space with real parallax and real depth, and **not** a raymarch - there is no loop
over `t` anywhere in it. For each of up to four layers, one analytic ray-plane intersection gives an
exact hit point and an exact depth: four divisions total, no iteration, no variance. At each hit the
ornament is evaluated procedurally and composited front-to-back with
`col += T * layerCol; T *= (1.0 - layerAlpha)`.

The default ornament is hyperbolic {7,3} by iterated circle inversion in the Poincare disk: two straight
mirrors through the origin at `PI/p` and one inversion circle at distance `D` with `D*D = 1 + R*R`
enforcing orthogonality to the unit circle, looped 12 times, with the reflection count giving the cell
index and hence hue and ornament phase. A recursive Truchet ornament is available as a **separate
compiled program variant**, not a runtime branch. `{p,q}` is admissible iff `1/p + 1/q < 1/2`.

Analytic anti-aliasing with `fwidth` on every ornament SDF is mandatory, not a polish item - without it
the result is aliasing, and aliasing and moire look nothing alike.

**Morph.** Three axes, all about **relationships between layers** rather than any layer's own shape,
which is what makes the interference the subject. (a) The relative rotation `dtheta` between adjacent
layers walks 0.004 to 0.06 rad; beat wavelength goes as `1/dtheta`, so a tiny change produces a huge
reorganisation. (b) Each layer's tiling is translated by a Mobius map of the disk,
`z -> (z + a)/(1 + conj(a)*z)` - the correct hyperbolic isometry, so the tiling flows without distortion
and heptagons stream out of the centre exponentially. (c) The layer depths animate, so the sheets breathe
apart and together along z, changing the ratios of their parallax rates and therefore the beat
**geometry**, not merely its frequency.

**Critic's objection and the fix.** Four, and one of them is that the style barely justified itself. (1)
**It was the same style as a cymatics design, down to the name** - `mode_moire` was also sheets at real
depths whose interference envelope rather than any sheet is the subject, with true parallax and per-sheet
thin-film colour. Two entries called Moire in one release is the same product shipped twice.
`mode_moire` is cut (§ What we are not doing) and **this style absorbs its one genuinely good idea**: the
beat wavelength as a direct readout of a musical interval. That turns `hxMoireBeat` from an abstract
radian slider into something the music drives meaningfully, and it is what earns this style its place.
The one thing it does **not** absorb is `mode_moire`'s just-versus-tempered precession, which was
unmeasurable (§ The shared foundation, `CymaticChroma`). (2) **The design sold its worst property as its
best**: "a fixed cost with no variance, which makes it the most predictable style of the ten and the
safest to leave on when thermal headroom is tight." A shader with 100 percent frame coverage, no
early-out, no empty space and no variance is the **maximum sustained** load - precisely the profile that
thermally throttles a phone, because no frame anywhere in the track lets the SoC cool. Variance is what
buys thermal headroom. That sentence is deleted and the style is treated as a sustained-load style, with
a layer count low enough that the sustained load is affordable. (3) **The cost was undercounted**: 8
layers x 18 Poincare inversions is 144 reciprocals (quarter-rate on both vendors) plus the fold
arithmetic, plus a three-level hashing Truchet, plus 8 Airy evaluations - realistically about 3,200 ALU
and 150 SFU per pixel with zero coherence relief. **Fixes: cap layers at 4** (the design's own Low tier
said three is enough to beat), **cut the fold to 12 iterations** with the uniform-bounded break form, and
**evaluate thin film on the front two layers only**. (4) **`hxMoireOrnament = 2` ("mixed per layer") put a
runtime branch selecting between the hyperbolic fold and the Truchet inside a loop the compiler wants to
unroll** - both bodies x 8 in one program, the classic Adreno register-spill cliff. That mode is
**deleted**; the ornament is a compile-time program variant, using the pattern `FluidLook` already has
(eight display programs keyed by bitmask, `FluidLook.kt:75-77`). Fifth, smaller: the `readbackGrid()`
call is dropped - it bought a mean velocity used to set four scalar rotation rates, which is not worth a
pipeline stall; those come from audio on the CPU. Register allocation is measured on a real Adreno 6xx
before the 4-layer count is committed.

**Fluid coupling.** Forward: each layer samples `velocityTex` at its **own parallaxed hit point**, so
layer 1 and layer 4 read the field at different world positions for the same screen pixel, and the fluid
slides them against each other - which multiplies straight into the moire, because relative motion
between layers is exactly what the beat pattern is made of. The dye sets each layer's film thickness
(hence hue) and opacity. Backward: each layer is a stirrer. The CPU queues a ring of body splats at each
layer's own rotation rate and radius - four counter-rotating stirrers at four angular speeds, a shear
profile no `FluidEmitters` pattern (CENTER, RING, RANDOM, SPECTRUM_ARC) produces, and what gives this
style's medium its laminar-banded look rather than the churned look of reliquary. A finger through
`queueTouchStroke` visibly slows or speeds individual sheets.

**Audio.** bass -> layer-depth breathing and base ornament scale; **the interval between the two loudest
chroma bins -> `dtheta`**, so the band spacing is the interval; treble -> `{7,3}` translation rate;
energy -> active layer count 2 -> 4 and film thickness spread; beat -> a step in one layer's Mobius
translation, so a single sheet lurches and the beat pattern snaps to a new configuration.

**Params.** `hxMoireLayers` 2..4 / 3; `hxMoireBeat` 0.001..0.08 / 0.02 (relative rotation, radians -
audio-driven from the interval when a confident chroma reading exists, else free); `hxMoireDepth`
0.2..4 / 1.2; `hxMoireOrnament` 0..1 / 0 (0 hyperbolic {7,3}, 1 recursive Truchet - a program variant,
not a branch); `hxMoireFilm` 0..1 / 0.7; `hxMoireStir` 0..2 / 0.8.

**Files.** `res/raw/hyper_moire_frag.glsl` (compiled twice, once per ornament);
`render/hyper/MoireScene.kt`; `render/hyper/TilingMath.kt` (the `{p,q}` admissibility test, mirror circle
radius and centre from `(p,q)`, the Mobius path, the layer depth schedule, and the interval-to-`dtheta`
law; pure); `app/src/test/java/dev/musicviz/TilingMathTest.kt` (the inversion circle is orthogonal to the
unit circle, `D*D = 1 + R*R` to 1e-6, for every offered `(p,q)`; the Mobius map keeps `|z| < 1` for every
input in the disk, because a leak there puts the fold into a non-terminating state; `{6,3}` is correctly
rejected as Euclidean rather than silently rendering wallpaper).

**Budget.** 4 layers x (one ray-plane intersection + 12 inversion iterations + one ornament SDF + two
texture fetches) plus two film evaluations: roughly 700 ALU, 40 SFU and 8 fetches per pixel. No marching
and no loop with an unknown trip count, so the cost is coherent across a warp - which matters more on a
Mali warp of 16 than the raw instruction count does. At `ResTarget` 0.7x this fits, but it is a sustained
100-percent-coverage load and the quality ladder drops it first on thermal grounds rather than last.
**Low:** 2 layers, 8 iterations, film replaced by a palette lookup, `ResTarget` 0.5x - about 3x cheaper,
and the moire survives because two layers is enough to beat.

**Risks.** Aliasing is the entire risk and it is existential rather than cosmetic: without analytic
`fwidth` filtering on every ornament SDF the output is shimmer, not moire, and it is worse under the
composite's downfilter than at native resolution. The mitigation is structural - every ornament function
returns a filtered coverage and never a hard step - and the harness's `fracBlownOut` and `deltaMeanLuma`
measurements are the regression gate. The Poincare fold can fail to converge near the disk boundary; the
loop is uniform-bounded and unconverged points are drawn as boundary black rather than garbage. Four
alpha-composited high-frequency layers can wash out to flat grey; alternating layers get complementary
film thicknesses so they subtract rather than average.

**Distinct.** `kaleido_frag`, `hexgrid_frag`, `voronoi_frag` and `grid_frag` are single-layer flat 2D
fields with no depth and no parallax. This is the only style built on exact analytic layer intersections
with true per-layer parallax, the only one whose subject is the **interference between objects** rather
than the objects, and the only one using hyperbolic geometry. It contains no marching loop at all, which
makes it structurally unlike polytope, membrane and foam; and unlike cortex it has no mesh, no relief and
no wave equation.

### Foam - `hyper_foam` (rebuilt; the space-filling Apollonian version was cut)

**Look.** A bounded nest of luminous bubbles - a dozen large shells with smaller ones packed in the gaps
between them and one further generation inside those, so you see through six curved surfaces at once
into a jewelled chamber with a bright caustic knot wherever four spheres meet. It breathes visibly: a
region swells, crowds its neighbours, and they compress and go small and dense while the swollen ones go
large and thin. Crucially the compression **travels** - you watch a wave cross the nest from one side to
the other, which is a kind of motion nothing else in this app produces. Colour comes from nesting depth,
so the fine structure filling the gaps is a different hue from the large shells. Over a track the nest
rearranges: contacts break and re-form and the shells go from solid to hollow and back.

**Technique.** A **bounded** set of 12 explicit spheres, positions on a shell, blended with the compact
cubic smooth minimum, plus **one level of sphere inversion** applied to the whole cluster to give the
nesting:

```
smin(a, b, k):   k *= 6.0;  h = max(k - abs(a-b), 0.0)/k;  return min(a,b) - h*h*h*k*(1.0/6.0)
cluster(p)   =   smin over i of (length(p - c_i) - R_i)
inverted(p)  =   q = c + Rinv*Rinv*(p - c)/dot(p - c, p - c);  s = Rinv*Rinv/dot(p - c, p - c)
                 return cluster(q) / s
map(p)       =   min( cluster(p), inverted(p) )        then onion:  abs(d) - shell
```

The cubic smin has **strictly compact support** - the blend is exactly zero beyond `k` - which is what
makes the per-sphere bounding test valid and lets distant spheres be skipped. Because the polynomial
smins are not associative, the evaluation order is fixed and a sphere leaves by having a large constant
added to its distance rather than by being removed from the loop; removing it pops. Plain sphere tracing
against an analytic bounding sphere, 48 steps, adaptive epsilon.

**Morph.** The 12 radii and the inversion radius are the morph parameters, and they are not uniform -
each sphere's radius is driven by a different scalar. Changing them changes which spheres are in contact,
and contact is combinatorial: a smin blend joins or separates as `|a - b|` crosses `k`, so gaps open and
whole clusters merge or split. That is a discrete topological reorganisation reached by a continuous
parameter. Second axis: the inversion radius `Rinv`, which slides the nested generation in and out.
Third: the onion thickness breathes, turning solid bubbles into hollow shells you can fly inside.

**Fluid coupling.** The only style anywhere in the app that reads the **pressure** field, which needs one
line in `FluidSim` (`val pressureTex: Int get() = pressure?.read?.tex ?: 0`; the field is private at
`:106`, computed by 14 Jacobi iterations every frame and thrown away). Forward: each sphere's radius is
modulated by the **mean-subtracted** pressure sampled at its projected position,
`R_i = R_base,i * (1 - uSquash * (p_i - pbar))`. Where the medium is compressed, bubbles are crushed;
where it is rarefied, they swell. Backward, closing a physical loop: a bubble whose radius is increasing
displaces fluid, so the CPU calls `queueSourceSplat` with a purely radial velocity of magnitude
proportional to `dR/dt` - a divergence source. Dye tints the shells and `flowAt` adds a light domain warp
for surface detail.

**Critic's objection and the fix.** This design was **cut** in review and is rebuilt on a different
technique. The original was an unbounded space-filling Apollonian packing accelerated by cone marching
and enhanced sphere tracing, and both accelerators were mismatched to its own field. Cone marching buys
the ability to skip empty space; the look description said the packing filled space "all the way down to
the pixel", so there was nothing to skip and the 1/8 pre-pass mostly returned `t` near zero. Enhanced
sphere tracing buys speed on convergent rays and costs a second DE evaluation plus correctness on thin
features; an Apollonian gasket is a dense set of near-tangencies where the DE collapses toward zero, so
the overlap fallback fires constantly, and the 0.03 shell was exactly the thin feature over-relaxation
punches holes through. The design admitted the fallback rate "is the one number this design cannot
predict from first principles" - which is an admission that the whole performance case was unevidenced.
Two further concrete defects: the cone pre-pass stored `t` in R16F, which at `t = 40` quantises to about
0.04 world units, **larger than the 0.03 shell it must not overshoot**; and a Neumann-BC Jacobi pressure
solve has an arbitrary free DC constant, so the radius law would drift the whole packing as the DC mode
wandered - the zero-net-volume test checked the source term, not the pressure offset.

**What was kept and what replaced it.** The pressure coupling is the genuinely novel idea and it is
cheap, so it stays; it is attached to a bounded finite bubble set instead of an unbounded packing, which
gives a predictable step count and a march that terminates. Both accelerators are gone, and with them the
R16F quantisation bug. The DC drift is fixed by subtracting the mean pressure read through
`readbackGrid()`, and `ApollonianMathTest` becomes `FoamMathTest` with the mean-subtraction pinned
alongside the zero-net-volume assertion.

A second reviewer found a different problem with the same coupling, and it applies to the rebuild too:
**the travelling compression wave rested on an accident of solver convergence.** Pressure in an
incompressible solver is elliptic and therefore instantaneous; the only reason a divergence source at one
bubble did not crush every other bubble in the same frame is that `FluidSim` runs a fixed 14 Jacobi
iterations and never converges. The wave speed was literally the Jacobi information-propagation rate, and
raising `pressureIterations` for any reason on any device tier would have deleted the style's signature
behaviour. The claim that the wave crosses the foam "over roughly the following second" was also wrong -
14 cells per frame crosses a 96-grid in about seven frames. **Fix: the wave is a first-class owned
quantity.** The bubble-to-bubble coupling runs as an explicit small relaxation on the CPU over the 12
radii, `dR_i/dt += cWave * sum_j w_ij (R_j - R_i)` with `w_ij` a neighbour kernel, where the propagation
speed is a named, tunable, testable parameter. The pressure solve stays as it is and is read, not relied
on for timing.

**Audio.** bass -> base radius of the largest spheres and `uSquash`; mid -> sphere centre drift, which is
what breaks and re-forms contacts; treble -> onion thickness and the specular/caustic gain; energy ->
inversion radius (how prominent the nested generation is) and `cWave`; beat -> a large positive
`queueSourceSplat` at the camera-facing bubble, which then propagates outward as a visible compression
wave over roughly the following second - and now actually does, at a rate you can set.

**Params.** `hxFoamCount` 6..12 / 10; `hxFoamBlend` 0.05..0.6 / 0.25 (smin `k`, the merge/split control);
`hxFoamNest` 0..1 / 0.6 (inversion radius, i.e. how deep the nested generation sits);
`hxFoamSquash` 0..1.5 / 0.8 (how hard pressure crushes the bubbles); `hxFoamShell` 0..0.15 / 0.03;
`hxFoamWave` 0..2 / 0.9 (propagation speed of the crush wave across the nest).

**Files.** `res/raw/hyper_foam_frag.glsl`; `render/hyper/FoamScene.kt`; `render/hyper/FoamMath.kt`
(sphere placement, the compact-smin bounding radii, the pressure-to-radius law with mean subtraction, the
`dR/dt` source strength, and the explicit relaxation; pure);
`app/src/test/java/dev/musicviz/FoamMathTest.kt` (source splats sum to zero net volume over a full
breathing cycle - the most important test here, because a DC divergence leak makes the pressure field
drift upward until every bubble is crushed flat and the style silently dies; the mean-subtracted pressure
read is invariant to a constant offset; the relaxation is stable at every offered `cWave`).

**Budget.** The DE is 12 sphere distances (one length, one subtract each) plus 11 cubic smins (about 6
ALU each) plus the inversion (one reciprocal, one dot, three mads) and the second cluster evaluation -
about 220 ALU per evaluation with no transcendentals, and the compact support means the realistic average
after bounding-sphere culling is closer to 6 active spheres, so about 130 ALU. At 48 steps plus 4 normal
taps that is roughly 6.8k ALU and 96 fetches (two per step for the pressure and dye) per pixel. At
`ResTarget` 0.6x that fits the 2,800-6,000 band at the top of it, which is why the count and the nesting
are both on the ladder. **Low:** 6 spheres, no inversion level (nesting off), 32 steps, shells forced
solid, `ResTarget` 0.45x. Losing the nesting is a real loss - it becomes a blobby cluster rather than a
foam - and it is the honest bottom of this style.

**Risks.** The pressure read is one dependent fetch per march step, which is at the family limit; it is
not also warped by velocity in the same step. Sub-pixel bubbles at high nesting alias into boiling noise;
the adaptive epsilon plus a nesting clamp tied to screen-space cell size fixes it, at the cost of fine
structure fading with distance rather than sparkling, which is the correct trade. Marching from inside a
`max()`-intersected region is unsafe, so the camera stays outside the bounding sphere.

**Distinct.** The existing Hyperspace GASKET species is a plane-trapped 3D inversion confined inside a
bounding sphere and evaluated as one of eight bodies sharing a single march. This is a purpose-built
bubble nest with twelve independently driven radii, and it is **the only coupling in the entire codebase
that reads the solver's pressure field** - which also makes its motion, a travelling compression wave,
unlike any other style's. Not `hyper_membrane` (periodic, saddles everywhere, no finite objects), not
`hyper_polytope` (flat facets, a reflection group, exact 4D, no inversion), not `hyper_reliquary`
(opaque faceted stones that occlude rather than transmit).

### Dustskin - `hyper_dustskin`

**Look.** At first only a haze of dust in a dark room. Then it condenses: every point finds a surface
and settles on it, and a shape you could not see is suddenly outlined in a quarter of a million glints -
dense and sharp where the surface curves tightly, sparse and dim where it is flat, so curvature is drawn
directly by density. The points are individually tiny and additive, so the surface reads as luminous mist
rather than a solid skin, and you see the far side straight through the near side - impossible depth done
honestly. When the hidden surface morphs the dust does not teleport: each point slides across the surface
to its new home over a few hundred milliseconds, so a sphere becoming a torus looks like sand pouring
around a hole that is opening. The fluid combs the dust into long streaks along the skin, and points torn
off by fast flow drift free as glitter and are recaptured a second later.

**Technique.** GPU particle state in MRT ping-pong through `GpuGrains`, with 3D positions and, critically,
a **surface-projection step**. Each frame the update pass does four things: integrate velocity with drag;
add the fluid's tangential drag; project onto the implicit surface with two Newton steps
`p -= f(p) * gradF / dot(gradF, gradF)` where `f` is a cheap analytic implicit (three to six
smin-blended primitives plus a low-octave noise skin) and `gradF` is a four-tap tetrahedron gradient; then
age, kill and respawn. The projection is what makes the cloud **be** the surface rather than approximate
it. Rendering is `GL_POINTS` with `gl_PointSize` from perspective distance, depth test ON and depth write
OFF, additive `GL_ONE, GL_ONE` - so points are correctly occluded by anything opaque but never occlude
each other, which is both the look and the reason the result is order-independent despite being blended.

**Morph.** The implicit `f` morphs and the dust follows, because the projection is re-run against the
current `f` every frame. The morph is the safe kind: never lerp two distant fields (that is where the
gradient collapses to zero at `t = 0.5` and the projection stalls), instead morph the parameters - the
primitives translate, change radius, and change their smin blend radius `k`. When `k` grows past the gap
between two spheres they merge into one connected component; when it shrinks they separate. That is a
genus change, and the dust visibly pours through it as it happens. Second axis: the noise skin's amplitude
and its fourth (time) coordinate. Because the dust is projected rather than advected, `f` only has to be a
smooth implicit, not a valid distance field - which removes the entire Lipschitz problem that constrains
every raymarched style here.

**Fluid coupling.** Forward, and it is a legible physical rule the viewer can watch: the update pass
samples `velocityTex` at the particle's xy and adds `v_fluid - dot(v_fluid, n) * n` - the **tangential
component only** - so the fluid slides dust across the skin without tearing it off, while the normal
component decides escape. When `abs(dot(v_fluid, n))` exceeds a threshold the point is released and
becomes free glitter until recaptured. `dyeTex` sets each point's colour and brightness. Backward: a
low-resolution (128x128) additive pass writes point counts into a scratch texture, installed as
`FluidSim`'s custom **dye** injection program through `setInjectionShaders`, so dust deposits ink where it
has piled up. The loop then closes: dust settles, ink accumulates, ink drives flow, flow combs the dust.
The skin's own primitives also queue body splats along their paths, so the invisible object stirs the
medium even where no dust has settled.

**Critic's objection and the fix.** One, and it was a broken path presented as a degraded one. When
`formats.rgba32 == null` the MRT state drops to RGBA16F; the Newton projection's own shipping gate is
`|f| < 1e-3 within three iterations`, and fp16 has about three significant decimal digits with ULP around
1e-3 near 1.0. **The convergence test cannot pass in the fallback format.** A non-converging Newton step
on a surface is not "a real quality loss" - it is dust that boils and creeps every frame, which is the one
artifact this style cannot survive, since the entire premise is that the points **are** the surface. The
stated mitigation ("shrink the world scale so positions stay in the well-resolved part of the fp16 range")
does not help: fp16 is floating point, so scaling moves the exponent and leaves the 10-bit mantissa exactly
where it was. **Fix: RGBA32F is a hard requirement.** Without it the style takes the
`FluidSim.available = false` path and reports "Dustskin needs full-float render targets on this GPU",
exactly as `FluidScene.init` already does at `:98`. A style that is 60 percent right is worse than a style
that is honestly off. Two consequences also applied: **the 1M tier is deleted and the ladder caps at
256k** (at 6 px points, 1M is 36M additive fragments per frame plus 64 MB of ping-pong state, about
2 GB/s of state traffic alone on a part with roughly 25 GB/s total); and on Adreno large `GL_POINTS` are
culled when the point **centre** leaves the viewport, so dust pops off along all four screen edges unless
the spawn volume is inset - it is inset, and the alternative (instanced quads) is noted as the fallback if
inset spawning is not enough. Third, smaller: this style is entirely non-opaque, so nothing in it can be
rejected by a depth test against itself; it uses `DepthStage` only to be occluded by other content, and
where it is the only content in frame it opts out of the depth renderbuffer entirely rather than
allocating one that is never read.

**Audio.** bass -> primitive radii and the smin blend `k`, which is the merge/split topology morph; mid ->
noise skin amplitude and `curlStrength`; treble -> point size and the escape threshold, so bright hats
spray dust off the surface in visible sheets; energy -> live point count through a ttl cull and the
tangential drag gain; beat -> a radial impulse at one primitive that blows a patch of dust clean off the
skin, which re-settles over about a second.

**Params.** `hxDustCount` 0..3 / 2 (quality index into 64k / 100k / 160k / 256k points - a performance
setting, `NEVER_ROLLED`); `hxDustGrip` 0..1 / 0.7 (how hard the projection holds points; 0 is a free
cloud); `hxDustBlend` 0.05..1.2 / 0.4 (smin blend radius, i.e. merge/split); `hxDustSkin` 0..1 / 0.45
(noise displacement amplitude); `hxDustComb` 0..2 / 1.0 (tangential drag); `hxDustDeposit` 0..1.5 / 0.6
(how much ink settled dust lays down).

**Files.** `res/raw/hyper_dust_update_frag.glsl` (MRT integrate plus Newton projection);
`res/raw/hyper_dust_seed_frag.glsl`, `hyper_dust_vert.glsl`, `hyper_dust_frag.glsl`;
`res/raw/hyper_dust_deposit_frag.glsl` (custom `FluidSim` dye-injection program driven by point density);
`render/hyper/DustskinScene.kt`; `render/hyper/SkinField.kt` (the CPU mirror of the implicit - primitive
list, smin, the same noise - used for the body splats and pinned against the GLSL);
`app/src/test/java/dev/musicviz/SkinFieldTest.kt` (a Newton step from any random start converges to
`|f| < 1e-3` within three iterations **for every reachable parameter set including the noise-skin
extremes**, not a sampled subset - this is the test that stops the dust exploding).

**Budget.** One MRT update pass at `side^2`, four field evaluations per Newton step and two steps = eight
field evaluations per particle per frame. A six-primitive smin field is about 90 ALU, so at 256k points
that is `8 x 90 x 256k` = 184 MFLOP per frame, about 9 GFLOP/s at 50 fps, which fits inside the FLUID
Medium envelope. The draw is 256k additive `GL_POINTS` with depth test, which is fill-bound at large point
sizes, so `gl_PointSize` is clamped to 6 px and falls off with distance - and this is the family's one
permitted large point cloud, so no other pass in this style adds a second. **Low:** 64k points, **one**
Newton step, three primitives, no noise skin.

**Risks.** Newton converges slowly where `|gradF|` is small and the noise skin can manufacture exactly such
spots; the step is normalised by `dot(gradF, gradF)` and additionally clamped to 0.15 world units, with
the convergence test as the gate. Additive `GL_POINTS` with depth test and no depth write has no draw
ordering guarantee, but with `GL_ONE, GL_ONE` the result is order-independent by construction - that
combination is chosen for that reason.

**Distinct.** `FluidParticles` is 2D, has no surface, no depth test and no projection step. `NebulaScene`,
`SwarmScene` and `FountainScene` are CPU particle scenes drawing 2D billboards. This is the only style
where particles are **constrained to** a moving implicit rather than advected freely, so it is the only
one where the morph of an invisible object is made visible by a quarter of a million witnesses - and the
only one where the fluid's normal component versus tangential component produces two visibly different
behaviours. Not `hyper_reliquary` (solid facets, occlusion, few bodies), not `hyper_vivarium` (a filled
volume with no surface at all).

### Plume - `hyper_plume` (rebuilt on a real volume)

**Look.** Nothing solid at all - the entire image is smoke. A dense, self-shadowing column climbs through
the frame, forward-scattering so the side facing the light glows hot and the far side falls into deep
saturated blue. The plume is not a cylinder: it rises off a knotted curve of vents, so it braids as it
climbs, and where two braids meet they fold over and roll into a mushroom cap. Fine wisps ride on the
large motion, curling and being eaten by the bigger folds - the detail that separates real fluid from an
airbrushed gradient. Colour is absorption-driven rather than palette-driven, so thin edges run warm and
thick cores go dark and saturated, with a hard bright rim where the light punches through. Over a track
the vent knot re-ties and the braid pattern reorganises. On a big transient a ring vortex punches up
through the column and everything above rolls outward. **You can orbit it.**

**Technique.** Participating-media raymarching over a real 3D density field held in a `VolumeAtlas`: 32
slices of 64x64 packed into a 512x256 RG16F ping-pong pair, R = density, G = temperature. Each frame the
atlas is semi-Lagrangian advected: in-plane by the 2D solver's `(vx, vy)`, vertically by a buoyancy term
derived from the atlas's own temperature, and in depth by a divergence-free curl-noise field whose scale
is tied to the solved in-plane speed. That last component is an **approximation and is stated as one**:
the solver has no z velocity, so depth transport is not solved, it is a plausible incompressible field.
Modulating the potential rather than the velocity is what keeps it divergence-free.

Marching is 26 fixed steps between analytic ray-box entry and exit with IGN temporal dither
`fract(52.9829189 * fract(0.06711056*x + 0.00583715*y))` scrolled 5.588238 px per frame (credit Jorge
Jimenez), Beer-Lambert transmittance `T *= exp(-sigma_t * dens * ds)`, and a two-lobe Henyey-Greenstein
phase `(1-g*g)/(4*PI*pow(1+g*g-2*g*mu, 1.5))` with `g = 0.55` forward and `-0.2` back. **Self-shadowing
is per-sample**: a 6-step light march against the same atlas, run at every 4th primary sample with the
result held between. Early-out at `T < 0.012`. One noise octave in the shader; the other two are folded
into a precomputed tiling 2D texture. Rendered at quarter resolution into `ResTarget` and
depth-aware bilateral upsampled.

**Morph.** The **emitter manifold** morphs, and now so does the volume it fills. Vents sit on a torus knot
`((R + r cos qt) cos pt, (R + r cos qt) sin pt, r sin qt)` and `(p, q)` steps between coprime pairs -
(2,3) -> (3,4) -> (3,5) -> (2,7) - on section boundaries. Between steps `R` and `r` interpolate
continuously so the knot inflates and deflates; at the step the knot's topology changes, and because a
knot's writhe determines how the rising columns braid around each other, the plume re-braids without
anything having been moved directly. Second axis: the buoyancy coefficient and vent temperature spread
animate, so the column shifts between a lazy wide mushroom-heavy plume and a narrow straight jet.

**Fluid coupling.** Total: the fluid is the object. Forward, the atlas is advected by the solver's
velocity field and its density is both extinction and emission. Backward, this style installs a custom
**force** injection program through `FluidSim.setInjectionShaders` implementing buoyancy from the dye
field itself, `F = (-kappa*d + sigma*(d - d_amb)) * up` - it reads the dye texture inside the force pass
and pushes it upward in proportion to its own density. That closes a physical loop entirely inside the
solver - dye rises, rising creates shear, shear rolls the dye into vortices, vortices spread it - which is
why this produces real mushroom caps instead of a smeared airbrush, and it is the only style here where
the solver runs a closed feedback loop rather than being stirred from outside. The CPU adds the vents as
`queueBodySplat` calls along the knot. Vorticity confinement (already pass 4 of `FluidSim`) is turned up
hard, `curlStrength` 34..46, because semi-Lagrangian advection eats precisely the small-scale swirl this
style is made of.

**Critic's objection and the fix.** Two, and the second one was the design's own confession. (1) **The
budget and the look described different shaders, by about 4x on the load-bearing feature.** "230 texture
fetches per covered pixel" decomposed as `44 x (2 dye + 3 noise) + 5 x 2` - i.e. the five light-march
steps happened **once per pixel**, not once per primary sample. One light march per pixel gives one shadow
value for the entire ray, which cannot produce "a dense, self-shadowing column, the side facing the light
glowing hot and the far side falling into deep blue"; self-shadowing is by definition a per-sample
quantity. Doing it correctly is `44 x 5 x 2 = 440` more fetches, taking the real figure to about 660 per
pixel - roughly twice the entire bilinear throughput of a mid-range part, with the solver's own 26+ passes
and the composite still to pay for. The advected noise octaves also needed a velocity fetch each, which
was not in the 230 either. **Fix: the shipping recipe is quarter resolution as the default (not the Low
tier), 26 primary steps, one shader noise octave with the other two precomputed into a tiling texture, and
a 6-step light march at every 4th primary sample with the result held between.** That is genuinely
per-sample self-shadowing at about 136 taps per pixel and it preserves the mushroom-cap read.

(2) **It only paid lip service to real 3D.** Density was `dyeXY(p.xy) * dyeZY(p.z, p.y)` - a product of
two projections of the same 2D texture, with no three-dimensional representation of the plume anywhere.
The tell was a per-style `CameraConstraint` pinning the orbit to +/-25 degrees around the plume axis,
which existed solely because the illusion breaks outside that cone. A style that cannot be looked at from
the side is not a 3D style; it is a 2D style with a parallax budget. **Fix: give it an actual third
dimension.** The `VolumeAtlas` pattern is cheap - one more small ping-pong pair - and it removes the
camera constraint, the ghost-density artefact and the "reads visibly flatter" Low-tier degradation all at
once. The camera constraint is deleted; you can orbit this style.

**Audio.** bass -> buoyancy sigma and vent injection rate; mid -> `curlStrength` and the noise advection
gain; treble -> fine-octave amplitude and the phase function `g`, since sharper forward scattering reads
as crisper wisps; energy -> the `(p,q)` advance rate and hero-light intensity; beat -> a ring-vortex pair
of `queueSourceSplat` calls, two counter-rotating splats one radius apart, which is literally how you seed
a vortex ring in a 2D solver.

**Params.** `hxPlumeDensity` 0..3 / 1.0 (extinction `sigma_t`); `hxPlumeBuoyancy` 0..2 / 0.9;
`hxPlumeKnot` 0..1 / 0.4 (how often the vent knot re-ties; 0 holds one knot); `hxPlumeScatter`
-0.6..0.9 / 0.55 (Henyey-Greenstein `g`); `hxPlumeSteps` 0.4..1.5 / 1.0 (march-step multiplier; a
performance setting, `NEVER_ROLLED`); `hxPlumeLight` 0..2 / 1.0.

**Files.** `res/raw/hyper_plume_march_frag.glsl`; `res/raw/hyper_plume_advect_frag.glsl` (atlas
semi-Lagrangian advection with buoyancy and curl-noise depth); `res/raw/hyper_plume_force_frag.glsl` (the
dye-driven buoyancy force-injection program); `res/raw/hyper_upsample_frag.glsl` (depth-aware bilateral
upsample, shared with vivarium); `render/hyper/PlumeScene.kt`; `render/hyper/KnotVents.kt` (torus-knot
vent placement, the coprime `(p,q)` schedule, ring-vortex splat pairs; pure);
`app/src/test/java/dev/musicviz/KnotVentsTest.kt` (`(p,q)` are always coprime - a non-coprime pair
degenerates to a circle and the plume goes boring; the vent path closes to 1e-4; the ring-vortex pair has
exactly zero net momentum).

**Budget.** Per covered pixel: 26 primary steps x 1 trilinear atlas fetch (2 bilinear taps) = 52 taps,
plus `26/4 = 7` light marches x 6 steps x 2 taps = 84 taps, plus one tiling-noise fetch per step. About
140 taps and 900 ALU per covered pixel. At quarter resolution on 3.24 Mpx that is 810k pixels; the plume
typically covers about 35 percent, so 284k pixels x 140 = **40M taps per frame, about 2.0 Gtex/s at
50 fps** - at the ceiling of a Mali-G57 MC2 and comfortable on anything above it, which is why the
quality ladder cuts the light-march stride first. The analytic ray-box bound means uncovered pixels march
zero steps, and the `T < 0.012` early-out kills the column's interior where most of the density is. Atlas
advection is 2 passes at 512x256 = 262k texels, a rounding error. **Low:** 16 primary steps, light march
every 8th sample with 4 light steps, 24 atlas slices, `ResTarget` 0.2x. It goes soft and loses the crisp
wisps; it does not lose the volume.

**Risks.** The buoyancy feedback loop can go unstable and blow out the dye field; it is bounded by
construction because `FluidSim.dyeCeiling` is enforced at injection through the headroom term in
`fluid_splat_frag.glsl` mode 1, and the force program clamps `F`. Half-resolution volumetrics ghost
against moving geometry, but there is no geometry here, so the bilateral upsample only has to respect the
plume's own soft depth. The depth-direction curl-noise transport is not solved physics and is capped so
it cannot dominate the in-plane advection, which is the part that is grounded.

**Distinct.** `InkflowScene` and `FluidScene` display the dye as a flat 2D image. This is the only style
with no surface, no bodies and no SDF anywhere in it: it renders the medium itself in 3D with real
per-sample self-shadowing, and it is the only one where the fluid solver runs a closed physical feedback
loop rather than being stirred from outside. Its morph is a change in the topology of the **source**.
Against `hyper_vivarium`, which now shares its marcher: plume's atlas is advected dye and temperature with
no state law of its own; vivarium's atlas is a chemical state with a growth law and a diffusion tensor.
Same renderer, different physics - and that is deliberate engineering rather than a coincidence.

### Vivarium - `hyper_vivarium` (rebuilt; the slice stack was cut)

**Look.** A translucent mass hangs in the middle of the frame and it is unmistakably alive - not animated
but growing. Fronts of colour propagate across it, split, collide and annihilate; spots divide by mitosis;
coral-like fingers extend and branch and fuse. You see straight into it, and the internal structure is
exactly as detailed as the surface - there is no skin anywhere, which is what makes it read as a specimen
rather than an object. Depth comes through density and a shift toward blue at the back. Where the
surrounding fluid moves fast the pattern smears into long stripes aligned with the flow; where it is still
it relaxes back into isotropic spots, and you watch the transition sweep across the mass as a gust
passes. Over a track the chemistry itself changes: the same organism goes from dividing cells to worm-like
solitons to a static coral and back.

**Technique.** 3D reaction-diffusion in a `VolumeAtlas` - 48 slices of 48x48 packed 8x6 into a 384x288
RG16F ping-pong pair, so it is a 2D texture and pays no `sampler3D` penalty on Mali - stepped with a
7-point 3D Laplacian assembled from three in-slice fetches plus two cross-slice fetches at the atlas
offsets. Gray-Scott:

```
dA/dt = D_A * lap(A) - A*B*B + f*(1 - A)
dB/dt = D_B * lap(B) + A*B*B - (k + f)*B          D_A = 1.0, D_B = 0.5
```

Four substeps per frame at a 30 Hz solver rate decoupled from the render rate. Rendering is the **same
front-to-back marcher as `hyper_plume`** from `lib_volume.glsl`: 24 fixed steps at half resolution,
Beer-Lambert transmittance, `T < 0.01` early-out, IGN dither, bilateral upsample, depth-tested against
`DepthStage` so the organism can be occluded. Internal lighting comes from a one-tap directional
difference `L = exp(-k*(B(p + l*h) - B(p)))` rather than a full 3D gradient - a deliberate trade, stated,
because a central-difference normal is six extra trilinear lookups per sample.

**Morph.** The chemistry morphs. The `(f, k)` point walks along the thin live arc of the Gray-Scott
parameter plane, parameterised by **one** scalar so `f` and `k` are never modulated independently - off
that arc the pattern dies to a uniform state and does not come back, which is the classic and
unrecoverable failure. Along the arc the pattern **class** changes: mitosis (`f ~ 0.0367, k ~ 0.0649`)
into coral (`f ~ 0.0545, k ~ 0.0620`) into solitons into worms. That is a morph of the object's growth
**law** rather than of its geometry, and it is qualitatively unlike every other morph in either family.
Second axis: `(f, k)` also vary spatially on a slow gradient, so different districts of the organism sit
in different regimes simultaneously and the boundary between them migrates.

**Fluid coupling.** The deepest of the twenty, and it comes from the published literature rather than
being invented. Forward, two mechanisms. (i) **Advection**: an extra pass semi-Lagrangian-advects the
`(A,B)` atlas by `velocityTex` before the reaction step, so the organism is carried and stretched by the
medium. The z component is synthesised from the flow's local divergence; that part is invented rather than
derived, so it is kept small and gated behind `hxVivariumAnise` so it cannot dominate the in-slice
advection, which is the part that is grounded. (ii) **Anisotropic diffusion oriented by the flow**, which
is Witkin & Kass's diffusion matrix: replace the trace of the Hessian with `Tr(D^T Q^T H Q D)`, where `Q`
is the rotation by `theta = atan2(v.y, v.x)` and the principal rates satisfy `a1/a2 = 1 + kappa*|v|`. That
makes the Turing stripes **align with the velocity field** and stretch where it is fast, so the pattern
becomes a legible visualisation of the flow while still being a genuine Turing pattern - not arrows, not
streamlines, chemistry. Backward: the B species is injected into the fluid as dye through a custom dye
program **and** as force through a custom force program with `F` proportional to `grad(B)`, so the
organism's own growth stirs the medium it is growing in. A fast-growing front generates its own current,
which then combs the front that made it.

**Critic's objection and the fix.** This design was **cut** in review for its renderer, and rebuilt with
the chemistry intact. View-aligned slice-stack volume rendering is the wrong technique for a tile-based
deferred GPU, and the design's own text contained the proof: back-to-front `SRC_ALPHA` blending "forbids a
per-pixel early-out" and the empty-texel path "writes background rather than calling discard", so **every
one of 48 quads ran its complete fragment shader over its complete area, always, worst case, every
frame** - up to 48x overdraw with no relief mechanism of any kind. The per-fragment cost was also
undercounted: not three fetches but two bilinear taps for the hand-rolled trilinear plus **six more
trilinear lookups** for the 3D central-difference normal, so 14 fetches per blended fragment. At half res
with 40 percent coverage and 48 slices that is about 174M fetches per frame, two to three times a flagship
mobile texture unit, with zero possible early-out. There was also an unaddressed correctness bug: bilinear
fetches into an 8x6 slice atlas bleed across tile borders, putting a bright seam every 48 texels through
the whole volume, and the stated test covered only the Laplacian's integer neighbour offsets, not
render-time filtering.

**Fix: the chemistry is the identity and it is genuinely cheap** - 8 passes at 110k texels, smaller than a
single 512x512 dye pass. Only the renderer was unshippable. Slice stacking is thrown away and the atlas is
marched front-to-back with Beer-Lambert and a `T < 0.01` early-out, reusing `hyper_plume`'s marcher: same
look, an early-out that kills the dense interior where most of the cost is, and no 48x overdraw floor. The
6-tap gradient normal is replaced by the one-tap directional difference above. The atlas bleed is fixed in
`VolumeAtlas` with a one-texel edge-replicated gutter per slice and a test that samples either side of
every slice boundary. The reviewer's caveat is accepted and stated: this makes vivarium the second
volumetric marcher rather than a structurally different renderer, and its distinctness now rests entirely
on the density field having chemical state and its own growth law. That is a good enough reason for one
style; it is the reason this one is built last.

**Audio.** bass -> position along the `(f,k)` arc, with a deliberately slow attack because RD's own
relaxation time is hundreds of steps and fast modulation averages out; mid -> `kappa`, the anisotropy
gain, plus `curlStrength`; treble -> the spatial `(f,k)` gradient steepness, i.e. more or fewer districts;
energy -> substep count 2 -> 6, so the organism lives faster, plus emission brightness; beat -> a `B = 1`
disc stamped at a position derived from the spectral centroid, so the drum seeds new growth and you watch
it take hold.

**Params.** `hxVivariumArc` 0..1 / 0.45 (position along the live `(f,k)` arc; 0 mitosis, 1 coral);
`hxVivariumRate` 1..8 / 4 (reaction substeps per frame); `hxVivariumAnise` 0..2 / 0.9 (the Witkin-Kass
anisotropy, and the gate on the synthesised z advection); `hxVivariumDistricts` 0..1 / 0.4;
`hxVivariumSteps` 0.4..1.5 / 1.0 (march-step multiplier; a performance setting, `NEVER_ROLLED`);
`hxVivariumFeed` 0..1.5 / 0.7 (how much the B species inks and forces the medium).

**Files.** `res/raw/hyper_rd_step_frag.glsl` (reaction plus anisotropic diffusion on the atlas);
`res/raw/hyper_rd_advect_frag.glsl`, `res/raw/hyper_rd_seed_frag.glsl`;
`res/raw/hyper_rd_march_frag.glsl` (thin: sets up the density callback and calls `lib_volume`);
`res/raw/hyper_rd_ink_frag.glsl` (custom dye and force injection driven by B);
`render/hyper/VivariumScene.kt`; `render/hyper/GrayScottArc.kt` (the live `(f,k)` arc as a checked curve,
the atlas layout maths, the anisotropy matrix; pure);
`app/src/test/java/dev/musicviz/GrayScottArcTest.kt` (every point on the arc lies inside the published
live region; a CPU mirror of one cell's update matches the GLSL to 1e-4 over 200 steps; the atlas
neighbour offsets are correct at every slice boundary, which is the classic off-by-one that makes the
whole volume look striped).

**Budget.** Solver: 4 substeps x (one reaction pass plus one advect pass) at 384x288 = 110k texels, so 8
passes x 110k = 880k texel-ops per frame. Render: 24 steps x (1 trilinear = 2 taps + 1 directional tap) =
72 taps per covered pixel at half resolution. On 3.24 Mpx that is 810k pixels x 40 percent coverage =
324k x 72 = **23M taps per frame, about 1.2 Gtex/s** - comfortably under plume, which is why this one
runs at half rather than quarter resolution. **Low:** 16 steps, a 32-cubed atlas, 2 substeps, anisotropy
off, `ResTarget` 0.33x. The volume goes chunky and the coral fingers get blocky; the growth law still
reads.

**Risks.** Gray-Scott dies permanently if `(f,k)` leaves the live arc, and a dead organism that never
recovers is the worst possible failure for a visualiser; hard-clamped in `GrayScottArc` and pinned by a
test. RG16F quantises B, which lives in roughly `[0, 0.4]`, into visible banding; B is stored scaled by
2.5 so it occupies the full `[0,1]` range - a one-multiply fix. A 48-cubed atlas is only 110k cells, so
coral fingers will be chunky; the honest lever if it reads too coarse is more slices at lower render
quality, not a finer render of a coarse volume.

**Distinct.** No existing style simulates chemistry. Its morph is a change in a growth **law** rather than
in geometry, its medium coupling runs through diffusion anisotropy rather than through displacement or
lighting, and it is the only style in either family whose object cannot be described by any equation of
shape at all. Against `hyper_plume`: same marcher, different physics - plume's field is advected dye with
no state, vivarium's is a chemical state that grows. Against `hyper_dustskin`: a filled volume with no
surface, versus a surface made of points.

## The ten cymatics styles

Every one names the physical phenomenon it renders and states its equation. All of them consume
`PitchClock`: spatial structure from the true pitch, temporal phase from a clock mapped into 0.5-3 Hz.

### Chladni Sand - `chladni_sand`

**Phenomenon: point-driven Kirchhoff plate forced response, plus stochastic grain transport to the
nodal set.**

```
Plate field (mode sum):
   Psi(x,y;w) = sum_{n1,n2} C_{n1,n2} * cos(n1*pi*x/a) * cos(n2*pi*y/a)

Forced-response weight (Tuan et al. 2018, CC BY 4.0):
                      4Q (m_d/m_p) * cos(n1*pi*x'/a) cos(n2*pi*y'/a)
   C_{n1,n2}  =  ------------------------------------------------------------
                  [ (1-delta) n1^2 + (1+delta) n2^2 ]^2  -  [ (w - i*gamma)/w0 ]^2

   w0 = sqrt(D / (rho*h)) * (pi/a)^2          D = E h^3 / (12(1 - nu^2))

Anisotropic plate operator (the self-consistent form):
   [ (1-delta) d2/dx2 + (1+delta) d2/dy2 ]^2 Psi = K^4 Psi
   K^2_{n1,n2} = (pi/a)^2 [ (1-delta) n1^2 + (1+delta) n2^2 ]
```

Amplitude is `|C|` and phase is `arg(C)` - both fall out of one complex divide per mode, so the resonance
denominator replaces the current ad-hoc one-pole envelope entirely. `delta` is the orthotropy parameter
that splits the degenerate `(n1,n2)`/`(n2,n1)` pair.

Two corrections to the transcribed source are carried into the code as comments: the published PDE reads
`(1-delta)^2 Psi_xxxx + (1+delta)^2 Psi_yyyy - K^4 Psi = 0`, which drops the
`2(1-delta)(1+delta) Psi_xxyy` cross term and is not consistent with the stated `K^2`; the squared-operator
form above is. And the published `w0 = sqrt(B/rho h)(pi/a)` is dimensionally wrong; since
`w = sqrt(D/rho h) K^2` and `K^2` goes as `(pi/a)^2`, the scale is `(pi/a)^2`.

**Grain transport (Zhou et al. 2016, CC BY 4.0 - technique).** Nodes are attractors of a chaotic system.
Euler-Maruyama, with the gate applied to **both** terms:

```
active   = step( uThrow * |Psi(X_k)| - 1 )                    (non-dimensional; uThrow from rms)
X_{k+1}  = X_k  -  active * kappa * grad(|Psi|^2) * dt
                +  active * sqrt( 2*D0*|Psi(X_k)|^2 * dt ) * N(0, I)
```

The **multiplicative** noise is the one line that must not be simplified. Because `D` vanishes at the
nodes, a grain that reaches a node stops diffusing - that is the stochastic stability of the equilibrium.
A constant-`D` model provably will not hold grains on the nodes. A second, finer population gets `kappa`
negated plus an air-drag term, giving the inverse Chladni figure at the antinodes.

**Look.** A square steel plate fills most of the frame, seen from about 30 degrees above and slowly
orbiting, so you read it as a real object with a rim and a thickness rather than wallpaper. The surface is
genuinely displaced - crests lift toward you and troughs sink, catching a hard key light so the flexing
reads as metal. Hundreds of thousands of grains sit **on** the surface at their own height, so as the
plate flexes under them you see them bounce, skitter and slide. They do not fade in at the nodes: they
travel there over two or three seconds, leaving thinning bare patches at the antinodes and building ridges
with real width and a shadowed side. A second, much finer population in a contrasting tint does the
opposite. When the exciter point moves, modes with a node under the driver go dark and the whole figure
reorganises around the new driver rather than crossfading. On a loud transient the plate throws the bed
into the air and it rains back down.

**Morph.** Two axes, neither a shape lerp. (1) **The exciter point `(x',y')` walks across the plate**:
spectral centroid drives `x'`, chroma vector angle drives `y'`. Because the numerator factor
`cos(n1*pi*x'/a) cos(n2*pi*y'/a)` is exactly zero for any mode with a node at the driver, moving the
driver silences and revives whole mode families - the figure reorganises through a genuinely different
mode set rather than crossfading between two pictures. This is the best morph in either family and it is
free: the multiply is already in the weight. (2) `delta`, the symmetry-breaking scalar, from spectral
spread. At `delta = 0` the pair is degenerate and the figure is four-fold symmetric; as `delta` grows the
pair splits, the drive falls between the two split eigenvalues, and the antiphase superposition rotates
the whole nodal lattice and can turn the driving point itself into a node. Pitch sets which `(n1,n2)` the
denominator resonates with via `k = sqrt(hz/f0)`, so an octave up is `sqrt(2)` finer, not 2x finer.

**Fluid coupling.** `FlowField` (the shared renderer-owned velocity-only service) as the **acoustic
streaming layer** above the plate - the actual physical mechanism behind inverse Chladni patterns, not a
decoration. Forward: `requiresFlowField = true`, and each frame the scene injects the streaming velocity
with up to 24 `queueKick(clipX, clipY, velX, velY, radius)` calls laid on the plate, with velocity
proportional to `-grad(|Psi|^2)` from the same mode array the shader uses. The fine grains read that
field and add an air-drag term, so the fine population is dragged to the antinodes while the coarse
population's bounce-dominated drift takes it to the nodes. Return: the coarse grains' accumulated flux
along each ridge is reduced on the CPU into a second kick set pointing along the ridge, so the sand piles
push the air they sit in and the fine population's figure visibly bends around the coarse ridges. Where
`FlowField.canReadback` is false the CPU-side drag is skipped exactly as the existing particle scenes do;
the GPU-side sampling still works, so it degrades to one-way coupling rather than going dark.

**Critic's objection and the fix.** Four. (1) **The grain cost was 4-6x under.** The update pass and the
grain vertex shader each re-evaluated the 8-mode sum plus its gradient per grain - about 4 transcendentals
per mode per evaluation, so 262k grains x 8 modes x 4 trig x 2 passes is roughly 16M sin/cos per frame,
not the claimed "about 40 ops". **Fix: render `Psi` and `grad(Psi)` once per frame into one RGBA16F field
texture at 256^2** - one fullscreen pass, about 5 percent of the cost - and have both the grain update and
the grain draw sample it. That is also what makes CPU/GPU parity testable. (2) **The `active` gate was
applied only to the drift term**, so in the sub-threshold band around a node the drift was off and the
noise was not, and grains random-walked off the ridge with no restoring force. And
`active = step(w^2*|Psi| - g)` mixed a rad/s^2 quantity with a normalised `Psi` and a real `g`, making the
gate identically 0 or identically 1. **Fix: both terms gated (a grain the plate does not throw does not
move at all - static friction), and the gate non-dimensionalised as above,** pinned by a test. (3) **The
stationary density of that SDE is `rho ~ |Psi|^(-2(1 + kappa/D0))`, a non-integrable spike**, so ridge
width is set by `dt` and the gate, not by `cymGrainNoise` as the parameter doc claimed. `cymGrainNoise` is
re-documented as "jitter of the bed"; ridge width is the gate's dead band. (4) **The 1M tier is dropped
and the ladder caps at 262k**, and the "64x64 density prepass" - which silently added a third 262k-point
scatter - is deleted; additive weight is clamped with a fixed per-grain alpha and the composite's exposure
does the rest. This style therefore has exactly one large point cloud, per the family rule.

**Two-sheet mode (absorbing the cut `mode_moire`).** `cymPlateSheets: 1..2`. At 2, a second transparent
plate is instantiated at a world-space offset with a **disjoint excitation vector** and a mode-index
offset of `(n+1, m)`. The two nodal lattices beat through real parallax, and because the envelope
wavenumber of adjacent plate modes is exactly `pi/L` - one cycle across the whole plate - the beat is
legible and physically exact, rather than being driven from an unmeasurable interval ratio. One extra mesh
draw, zero new files, zero new scene seam, and the gap is clamped to `gap >= 2 * maxDisplacement` so the
back sheet can never penetrate the front one and invalidate the fixed draw order.

**Audio.** `AudioFeatures.bands` feed the whitened per-band excitation into `ModalBank` exactly as
`CymaticsPlate.excite` does today. centroid -> `x'`. `CymaticChroma` vector angle -> `y'`. spectral spread
-> `delta`. rms -> `Q` and `uThrow`, so quiet passages leave the sand parked. transient/beatImpulse -> a
one-frame vertical impulse added to every grain's velocity. onset -> a step in `gamma`, widening the
resonance so a transient briefly lights many modes and the figure blurs before resharpening. macroEnergy
-> camera orbit radius.

**Params.** `cymPlateDrive` 0..3 / 1.2 (`Q`); `cymPlateDamping` 0.02..1 / 0.18 (`gamma`, the resonance
peak width - low is sharp isolated figures, high is smooth blended superposition); `cymPlateBreak`
0..0.35 / 0.06 (`delta`); `cymDriverTravel` 0..1 / 0.6 (0 pins the exciter at the middle, like Chladni's
bolt); `cymGrainCount` 0..2 / 1 (16k / 65k / 262k - a performance setting, `NEVER_ROLLED`);
`cymGrainDrift` 0..2 / 1 (`kappa`); `cymGrainNoise` 0..2 / 0.8 (`D0` - jitter of the bed);
`cymInverseMix` 0..1 / 0.35 (fraction in the fine antinode-seeking population); `cymPlateTilt` 0..1 /
0.55 (camera elevation); `cymPlateSheets` 1..2 / 1.

**Files.** `render/cymatic/ChladniSandScene.kt`; `render/cymatic/PlateResponse.kt` (pure forced-response
solver: complex denominator, orthotropy split, exciter numerator, allocation-free into a caller
`FloatArray`); `res/raw/cym_plate_field_frag.glsl` (the once-per-frame `Psi` + `grad Psi` field texture);
`res/raw/cym_plate_mesh_vert.glsl`, `cym_plate_mesh_frag.glsl`; `res/raw/cym_grain_update_frag.glsl`;
`res/raw/cym_grain_vert.glsl`, `cym_grain_frag.glsl`;
`app/src/test/java/dev/musicviz/PlateResponseTest.kt` (pins the exciter-node zero, the `delta` split, the
1/e resonance width, the non-dimensional gate, CPU/GPU field parity, and **node occupancy after 600
steps**, which is what enforces the multiplicative noise).

**Budget.** Field pass: one fullscreen at 256^2 with 8 modes x ~12 ops = 786k ALU, negligible. Mesh
192x192 = 73,728 triangles, one draw, vertex cost is one field fetch plus lighting. Grain update: one MRT
pass at 512x512 (262k) x about 20 ops with two field fetches. Grain draw: 262k additive `GL_POINTS` at
2-4 px - roughly 1-4 Mpx of overdraw, comparable to `FluidParticles` at Medium, which already ships.
`FlowField` adds its usual 18+N passes on the 64 grid plus one 16 KB readback. Total about 22 offscreen
passes + 1 field pass + 1 mesh pass + 1 point cloud, well inside the FLUID Medium envelope of 45-77
passes. `ResTarget` scale derived from `1/supersampleFactor`. **Low:** 65k grains, 96x96 mesh, one sheet,
`FlowField` off. Grains are never recreated on an auto-downgrade - the draw count is clamped instead.

**Risks.** The mesh goes faceted where the mode order is high enough that a nodal line falls between
vertices; clamped with `n_max <= side/6` and by carrying the analytic gradient into the fragment stage so
the shading stays smooth even when the geometry cannot resolve the wave. Without RGBA32F the grain
positions in RGBA16F quantise into visible lattice clustering; fall back to 65k grains and a smaller
domain so the ULP is under a pixel, and report it once through `onShaderError` as a quality note, not a
failure. The exciter walk can make the figure look nervous; a 0.6 s one-pole on `(x',y')` and
`cymDriverTravel` defaulting to 0.6.

**Distinct.** Not the existing cymatics style: that is one fullscreen 2D pass with no camera, no mesh, no
depth, no particles and no fluid, whose "sand" is `exp(-h^2/w^2)` evaluated per pixel - it has no matter
and nothing migrates. Here the sand is 262k stateful particles that take seconds to travel, and the plate
is real geometry in perspective. Not any particle style: those advect along authored trajectories with no
field and no modal physics; here the drift is the gradient of a solved eigenfield and the pattern is an
emergent attractor. Against `hyper_cortex`: chladni stays square, Cartesian and made of matter, and never
acquires a log-polar or radial planform.

### Drumhead - `bessel_drum`

**Phenomenon: driven circular membrane with a fixed rim - Bessel eigenmodes on a clamped drumhead
carrying a thin liquid film.**

```
w(r, theta) = J_n(k r) * (A cos(n theta) + B sin(n theta))
Fixed rim:  J_n(k a) = 0     ->   k = j_{n,m}/a,   f_{n,m} = c * j_{n,m} / (2 pi a)
Free rim:   J_n'(k a) = 0    ->   k = j'_{n,m}/a
```

`n` nodal diameters, `m` nodal circles. Baked zero tables:

```
j_{0,m} : 2.40483  5.52008  8.65373  11.79153  14.93092
j_{1,m} : 3.83171  7.01559  10.17347 13.32369  16.47063
j_{2,m} : 5.13562  8.41724  11.61984 14.79595  17.95982
j_{3,m} : 6.38016  9.76102  13.01520 16.22347  19.40942
j_{4,m} : 7.58834  11.06471 14.37254 17.61597  20.82693
j'_{0,m}: 3.83171  7.01559  10.17347 13.32369          (= j_{1,m})
j'_{1,m}: 1.84118  5.33144  8.53632  11.70600
j'_{2,m}: 3.05424  6.70613  9.96947  13.17037
j'_{3,m}: 4.20119  8.01524  11.34592 14.58585
```

Mode selection is **not** nearest-zero snapping: every table entry gets a Lorentzian weight
`w_{nm} = gamma^2 / ((f - f_{nm})^2 + gamma^2)` against the detected partial, and all of them superpose.
That is what a real drumhead does and it is what produces mush on some notes and clean figures on others.
This style is the reason `ModalBank`'s ordering key must be geometry-supplied: a membrane is ordered by
`j_{n,m}`, not by the square plate's `sqrt(n^2+m^2)`.

**Look.** A round drumhead fills the lower two thirds of the frame, seen from about 25 degrees above,
stretched over a real hoop with a visible rim and shell. A thin film of dyed liquid sits on the skin.
Light rakes across at grazing incidence, so what you mostly see is not the height but the specular and
caustic response of the slope field: bright filaments where the surface is locally flat and tilted toward
the key light, dark where it is steep. That is how CymaScope-style photographs are actually made, and it
is the difference between looking like a render and looking like a photograph. Rings travel outward and
reflect off the rim; petals rotate around the centre at a rate set by their angular order. The film is
carried by the surface flow, so the mode's own transport is visible as coloured striations pooling in the
nodal circles. As the pitch climbs, ring count and petal count trade off non-monotonically - a semitone
can swap a 3-ring 8-petal figure for a 7-ring 2-petal one, and the figure reorganises rather than
refining.

**Morph.** Pitch selects `(n, m)` through the zero table, and the crucial property is that the table is
**not monotone in either index** - `j_{4,1} = 7.588` sits between `j_{1,2} = 7.016` and `j_{0,3} = 8.654`,
so a rising pitch walks a path through `(n,m)` space alternating between petal-dominant and ring-dominant
figures. The morph is a discrete reorganisation, not a refinement, driven purely by pitch. Two continuous
axes ride on top: (1) because the `cos(n*theta + phi)` term carries the phase, every mode with `n > 0`
**rotates** at a rate proportional to its own frequency, so a chord makes counter-rotating petal sets that
beat against each other, while `n = 0` modes degenerate to a pure pulse; (2) `gamma`, the Lorentzian width,
from the track's decay estimate - a dry mix gives sharp isolated figures, a wet one permanently blended
superpositions. A rim slider crossfades the drive from `j_{n,m}` to `j'_{n,m}`, physically the difference
between a clamped drumhead and a free membrane edge.

**Fluid coupling.** `RippleSim` **is** the drumhead - the modal bank is only the driver, not the renderer.
Forward: each frame the Lorentzian-weighted mode set is reduced to drive impulses and the scene calls
`queueDrop(x, y, radius, amplitude, r, g, b)` at the antinode positions of the top modes (radius from the
mode's local wavelength, amplitude signed by the mode's instantaneous phase), plus `queueStroke` along the
rotating petal crests so rotation is transported rather than teleported. The FDTD then does the
propagation, the rim reflection and the interference for real, which is why the figure has ringing and
travelling fronts an analytic sum cannot produce. `inkEnabled = true`, so the ink film is the dye that
photographs as the caustic. Reverse leg one: `heightTex` displaces the mesh **and** its gradient drives the
shading, so the sim owns the look. Reverse leg two: the surface slope is reduced on the CPU to 16 sample
points and pushed into `FlowField.queueKick`, so the air above the head is stirred by the skin. A circular
Dirichlet mask is written once into the grid so the rim is a real boundary rather than the square bathtub
`RippleSim` defaults to.

**Critic's objection and the fix.** Four. (1) **The object and the model disagreed.** It was described as
a shallow dish of water on a driver, but the model is an ideal fixed-rim **membrane**, `f = c j_{n,m}/(2 pi a)`,
i.e. non-dispersive `omega = c k`. A water surface obeys `omega^2 = [g k + (sigma/rho) k^3] tanh(k h)` -
and a dish of water on a driver does not show eigenmodes at all, it shows Faraday waves at `f/2`, which is
what the `faraday` style below is. It also contradicted itself, documenting a fill parameter as entering
"the dispersion through `tanh(k h)`" while the mode table used `omega = c k`, which has no `h` in it.
**Fix: the object is reframed as a clamped drumhead with a liquid film,** which makes the Bessel
eigenmodes correct and makes it genuinely distinct from `faraday` instead of being the wrong model of the
same object. The fill parameter is deleted and replaced by membrane tension. (2) **You cannot excite a
400 Hz mode with 60 impulses per second.** The design drove `RippleSim` with per-frame drops, so the
maximum achievable drive frequency was the frame rate, and the rotating-petal rate and the travelling-front
behaviour both depended on a time rescale that was nowhere declared. **Fix: `PitchClock`**, shared with the
whole family - spatial structure from the true pitch, temporal phase from a 0.5-3 Hz clock, declared. (3)
**Factual error: free-rim zeros are smaller than fixed-rim** (`j'_{1,1} = 1.841` vs `j_{1,1} = 3.832`), so
crossfading rim 0 -> 1 moves nodal circles **inward** and coarsens the figure, not "moves every ring
outward". Corrected in the copy and pinned in `BesselZerosTest`. (4) **The paraxial caustic is singular**;
it is clamped as `1/max(eps, 1 - K*lap h)`, or it produces NaN pixels the first time `lap(h)` crosses
`1/K`. Fifth, a geometry fix: a 128x256 polar mesh has 256 slivers meeting at `r = 0` and wildly
non-uniform vertex density; `SpaceMesh`'s polar variant uses a cap quad plus an `r^0.5`-weighted radial
distribution. Sixth, licence hygiene: the CC BY-NC-SA libretexts citation is dropped, since euphonics
covers the identical separation of variables (§ Licence position).

**Audio.** bands -> `ModalBank` excitation keyed on `j_{n,m}`. `CymaticChroma.dominantPitchHz` -> the
Lorentzian centre; its second and third partials -> two more centres, so a chord is a genuine three-way
superposition. rms -> drive amplitude. bass -> `RippleSim.waveSpeed` (a slacker head, slower waves, coarser
figure). Decay estimate -> `gamma`. treble -> ink flow. beatImpulse -> one large central drop, physically
honest as "strike the drum". progress -> a slow drift of membrane tension, changing the whole figure's
scale over a track.

**Params.** `cymDrumTension` 0.2..3 / 1 (membrane tension, hence wave speed and figure scale);
`cymDrumRim` 0..1 / 0.15 (crossfade from `j_{n,m}` to `j'_{n,m}`); `cymDrumGamma` 0.01..1 / 0.2
(Lorentzian half-width); `cymDrumInk` 0..1.5 / 0.7; `cymDrumInkFade` 0..2 / 0.35; `cymDrumGrazing`
0..1 / 0.8 (key-light elevation; 1 is full grazing incidence and a pure slope readout); `cymDrumCaustic`
0..2 / 1; `cymDrumShell` 0..1 / 0.6 (hoop and shell prominence).

**Files.** `render/cymatic/BesselDrumScene.kt`; `render/cymatic/BesselZeros.kt` (the baked `j_{n,m}` and
`j'_{n,m}` tables, Lorentzian selection, and a downward Miller-recurrence `J_n` for CPU parity - upward
recurrence is unstable, and the zeros are constants so nothing needs a library);
`res/raw/cym_drum_surface_vert.glsl`, `cym_drum_surface_frag.glsl`; `res/raw/cym_drum_shell_frag.glsl`;
`res/raw/cym_caustic_floor_frag.glsl` (`B = 1/max(eps, 1 - K*lap h)`);
`app/src/test/java/dev/musicviz/BesselZerosTest.kt` (pins the baked zeros against a series-computed `J_n`;
pins `f_{n,m}/f_{0,1}` against the published ratios 1.0000 / 1.5933 / 2.1355 / 2.2954 / 2.6531 / 2.9173 /
3.5001 / 3.5985; pins that the Lorentzian never collapses to a single mode; pins the rim-crossfade
direction).

**Budget.** `RippleSim` at the tiered 384 grid: `ceil(drops/8) x 2` splat passes (typically 2-4), at most
6 CFL substeps, 1 ink advect - about 10 passes, the `WaterScene` budget, already shipping. Polar mesh
128 x 256 = 65,536 triangles, one draw, one texture fetch plus a 4-tap gradient per vertex. Shell is a
second small draw. Caustic floor is one pass over the drumhead's screen footprint with a 5-tap Laplacian.
Total about 13 offscreen passes + 3 geometry draws - comfortably under half the FLUID Medium envelope,
deliberately, because this is the style that stays at 50 fps on a mid-range Adreno when the volumetric
ones tier down. **Low:** grid 192, mesh 64x128, caustic off.

**Risks.** The square-grid FDTD makes circular rings go polygonal at large radius - a known artefact of
the 5-point Laplacian, and the fix is the 9-point isotropic stencil, which **must** land in
`ripple_update_frag.glsl` or the drumhead will not read as circular. It is gated behind a uniform so
`WaterScene`'s tuning does not shift silently. DC drift: the Neumann 5-point Laplacian conserves `mean(h)`
exactly, so velocity damping alone never drains an accumulated offset - `uHeightDecay` handles it and must
not be set to 1. `RippleSim.clearInk()` must be called after allocation; `Fbo.create` clears to opaque
black, which reads as a black sheet over the head.

**Distinct.** Not the existing cymatics dish branch: that is an analytic Bessel sum in a fullscreen
fragment shader with a two-Hankel-term approximation, no propagation, no rim, no reflection, no vessel, no
camera and no ink - and it ranks dish modes by the **square-plate** metric `sqrt(n^2+m^2)`, which is the
wrong quantity for a membrane. **That branch is retired when this ships** (§ What we are not doing);
shipping both is the clearest duplicate in the plan and it would be self-inflicted. Not `WaterScene`: a
pool struck by a golden-angle drop schedule, viewed flat, no modal driver, no rim, no camera. Not
`faraday`: that is a parametric instability at half the drive frequency, which is a different phenomenon
in a different object. It is the only style where the fluid solver, rather than a closed form, produces
the figure - the mode bank is demoted to being the driver.

### Harmonograph - `harmonograph`

**Phenomenon: the damped multi-pendulum drawing machine, and the Blackburn pendulum that sets its
frequency ratio.**

```
x(t) = A1 sin(f1 t + p1) e^{-d1 t}  +  A2 sin(f2 t + p2) e^{-d2 t}
y(t) = A3 sin(f3 t + p3) e^{-d3 t}  +  A4 sin(f4 t + p4) e^{-d4 t}
z(t) = A5 sin(f5 t + p5) e^{-d5 t}  +  A6 sin(f6 t + p6) e^{-d6 t}

Blackburn pendulum (a bob on a V of strings, two effective lengths):
   omega_x / omega_y = sqrt( (l1 + l2) / l2 )
   so a 4:1 length ratio draws a 2:1 figure, and 9:4 draws 3:2
```

The rod lengths shown on screen are the real ones for the ratio being drawn, which is why the mechanism is
worth rendering. The small detune `f2 = f1 (1 + zeta)`, `zeta ~ 1e-3`, is what makes the rosette precess
and fill rather than shrinking to a static Lissajous.

**Look.** A dark volume with a slowly orbiting camera. Two pendulum rods hang at the top of the frame,
swinging in real time with the right periods, and a third moves the paper plane below - you can see the
mechanism that is drawing the figure, which makes the figure legible instead of magic. The pen traces a
continuous tube of light through space, several thousand segments long, extruded around the curve with a
real circular cross-section so it occludes itself where it passes behind an earlier pass. Brightness is
inversely proportional to the pen's speed, so the curve blazes at the cusps where the pen turns and dims
through the fast sweeps - which is how a real pen deposits ink, and the same relationship `BeamScene`
already uses for a CRT. The oldest segments fade and thin as the damping runs. Around it hangs luminous
dust that the pen's motion drags into a visible wake - and the wake pushes back: where a draught the pen
laid down earlier is still circulating, the rosette wobbles off true.

**Technique.** The curve is evaluated in the vertex shader from a small uniform block (24 floats:
amplitude, frequency, phase, damping per term) with no per-frame geometry upload. `gl_VertexID` decodes to
`(segment, ring vertex)`, the segment's `t` is derived from a per-frame head offset, and the frame is
built from the analytic first derivative. 2048 segments x 8 sides = 32,768 triangles from a static index
buffer. Brightness per segment is `1/|dP/dt|`, clamped to 8x the mean (the `BeamScene` precedent).

**Morph.** The frequency ratios are the music's interval ratios from `CymaticChroma`'s two loudest bins.
Everything else follows from harmonic theory: octave 2:1 draws a figure-eight, fifth 3:2 a three-lobe
rosette, fourth 4:3 four lobes, major third 5:4 a five-pointed star, minor third 6:5 six lobes, and a
tritone draws something that never resolves. Lower integers give a simpler curve, which is consonance made
visible and is true rather than decorative. In JUST mode the ratio snaps to the nearest small-integer
ratio, the curve closes after `t = 2*pi*q/b` and retraces exactly, and the figure is frozen; in 12-TET
mode the ratio is `2^(n/12)`, irrational for everything but the octave, and the curve never closes. Second
axis: the damping vector - a long reverb tail sets low damping and the figure fills the whole square before
it decays, a dry percussive track sets high damping and each phrase draws one quick spiral and dies. Third:
the z pair's amplitude, from spectral spread, which lifts the figure out of the plane into a true space
curve.

**Fluid coupling.** `FlowField` for the wake plus `FluidParticles` for the dust, deliberately the two
cheapest fluid classes in the app, because this is the style that must never be the one that drops frames.
Forward: the pen tip's velocity is pushed in every frame as `queueKick` at the head position and two
trailing positions, so the pen leaves a real divergence-free wake rather than a decal. Because the pen is
fast through the sweeps and slow at the cusps, the wake is strongest exactly where the trace is dimmest,
which reads as the pen pushing the air. Reverse leg one: the dust is `FluidParticles`, advected by
`flowField.velocityTex`, so the dust is a readout of the fluid and the fluid is a readout of the curve, and
`setChoreography` is handed catch points at the curve's cusps - computed as the extrema of the slower
pendulum per period - so dust is captured exactly where a real pen pools ink. **Reverse leg two, the one
that makes this a coupling rather than a decoration:** each pendulum bob samples `FlowField.cpuGrid` at its
projected position (three bilinear samples per frame - the class is designed for exactly this, and
`levitator` already does it for 32 droplets) and the local velocity perturbs that pendulum's phase and
amplitude. Physically that is air resistance on a real harmonograph; visually it means the rosette wobbles
off true where the wake it laid down earlier is still circulating. **The figure remembers its own
draughts.**

**Critic's objection and the fix.** Six. (1) **The tube frame would visibly break.** "The Frenet frame is
built from the analytic first and second derivatives" - a Frenet frame is undefined wherever curvature
vanishes and its normal flips sign through every inflection point, and a damped harmonograph curve is full
of near-inflections, so the tube would twist 180 degrees and pinch at each one. A rotation-minimising
frame is the correct answer but it is a sequential integration along the curve, which a stateless
`gl_VertexID` vertex shader cannot do - so it would also have broken the zero-geometry-traffic claim the
whole perf argument rests on. **Fix: a reference-up parallel frame.**
`bitangent = normalize(cross(T, worldUp))`, `normal = cross(bitangent, T)`, with a smooth fallback blend
when `|dot(T, worldUp)| > 0.95`. Stateless, per-vertex, no inflection flip, and for a mostly-planar
harmonograph the residual twist is invisible. (This is also why `hyper_caduceus` keeps the true
double-reflection frame: it integrates on the CPU and uploads vertices, so it can afford one, and it is
strongly non-planar, so it needs one.) (2) **`t` cannot be wrapped.** The design said the phase and the
envelope must wrap together, but `exp(-d t)` is not periodic, so no wrap period exists and the test that
"pins continuity across the wrap" could not pass. **Fix: a retrigger, not a wrap** - `t` is time since the
last `beatImpulse` re-energise, clamped at `t_max` where `exp(-d t) < 1e-3`, and held. (3) **Two
independent precession sources fought each other** - the intra-axis detune `zeta` and the tuning-mode
inter-axis precession. Only one can be the visible one. **Keep `zeta`; delete the tuning-mode precession.**
(4) **The interval ratio was octave-ambiguous and the just-versus-tempered detune was below the measurement
floor** (§ The shared foundation). `cymHarmRatio` becomes a pure user toggle - JUST / AS-MEASURED /
12-TET - with no claim that a detune was measured from the track. (5) **"The cheapest style by a wide
margin" while carrying `FluidParticles` at Medium** = 262k additive `GL_POINTS` at native resolution. That
point cloud was the entire cost. **Dust is halved to 65k** if this is to be the guaranteed-50 fallback. (6)
**Its fluid coupling was the clearest lip-service case in the twenty** - the design closed off the return
path structurally and said so with pride ("no CPU upload at all"), so nothing about the figure responded
to the fluid and the medium could have been deleted with no change to the subject. Reverse leg two above
is the fix; it costs three bilinear samples and one add, and the 24-float uniform block is kept - the CPU
just updates those 24 floats from the field.

**Audio.** `CymaticChroma`'s two loudest bins -> `f1` and `f3`. Band envelope release times -> the damping
vector. rms -> amplitudes. Spectral spread -> the z-pair amplitude. beatImpulse -> a fresh push on the
pendulums: amplitudes jump and the damping restarts, so each beat redraws. bpm -> `f1` scaled so one figure
completes in a musically sensible number of bars. transient -> a brief brightening of the tube head.
sectionIndex -> a re-seed of the phases, so a new section draws a visibly different rosette from the same
interval.

**Params.** `cymHarmRatio` 0..2 / 0 (0 as measured, 1 snap to just, 2 force 12-TET - a user toggle);
`cymHarmDamping` 0..1 / 0.3; `cymHarmDetune` 0..0.01 / 0.0015 (`zeta`, the only precession source);
`cymHarmDepth` 0..1 / 0.4 (z-pair amplitude; 0 is a flat plane figure); `cymHarmTrail` 256..4096 / 2048;
`cymHarmTube` 0..1 / 0.35; `cymHarmMechanism` 0..1 / 0.6 (visibility of the rods and pivots);
`cymHarmDust` 0..1 / 0.4; `cymHarmDraught` 0..1 / 0.5 (how hard the wake perturbs the pendulums).

**Files.** `render/cymatic/HarmonographScene.kt`; `render/cymatic/HarmonographMath.kt` (pure: the
closed form and its first derivative, the Blackburn length ratio, cusp detection, the parallel-frame
fallback blend); `res/raw/cym_tube_vert.glsl`, `cym_tube_frag.glsl`; `res/raw/cym_mechanism_frag.glsl`;
`app/src/test/java/dev/musicviz/HarmonographMathTest.kt` (closure for rational ratios - the curve returns
to its start within 1e-4 after `t = 2*pi*q/b`; non-closure for `2^(7/12)`; the Blackburn 4:1 -> 2:1
identity; the parallel frame stays orthonormal and never flips, including through the `worldUp` fallback
blend; the retrigger clamp holds rather than wrapping).

**Budget.** The cheapest style in either family. 2048 x 8 = 16,384 vertices, 32,768 triangles, one draw
call, static index buffer, **zero per-frame vertex upload**. About 24 sin/cos plus one derivative per
vertex, roughly 120 ALU on 16k vertices = 2 MFLOP. Fragment cost is a thin emissive tube covering maybe 3
percent of the frame. Mechanism is another 2k triangles. `FlowField` is 18+N passes on the 64 grid.
`FluidParticles` at 65k is 1 pass at 256x256 plus 65k additive points. Total about 20 offscreen passes + 2
tiny geometry draws + 1 particle pass + 1 point cloud, at native resolution with `DepthStage` attached.
This is the family's guaranteed-50 fallback and it is built first for that reason. **Low:** 512 segments,
no dust, mechanism hidden.

**Risks.** The figure is illegible when the track has no clear two-pitch content - a percussion-only
passage gives a meaningless ratio and the curve becomes noise. Mitigated by holding the last confident
ratio (gated on `CymaticChroma.confidence`) and letting the damping run so the figure gracefully spirals to
a point rather than thrashing. Self-intersection z-fighting where the tube crosses itself at a shallow
angle needs a small depth bias, and the tube radius must not exceed the minimum inter-pass distance
(clamped from the analytic curvature).

**Distinct.** Not `liss_frag`: that plots one waveform against a phase-shifted copy with a 64-tap
per-pixel search in a fullscreen 2D shader - no pendulums, no damping, no interval ratios, no geometry, no
depth, and it is O(pixels x 64) where this is O(16k vertices). Not `beam_frag`: a woscope trace of the raw
waveform as 2D segment quads. Not `orbits`/`attractor`: particle systems with no harmonic ratio semantics.
It is the only style in either family with **no field at all** - no eigenfunction, no grid, no surface -
the only one whose subject is tuning systems, and the only one that renders the apparatus alongside the
phenomenon. Against `hyper_caduceus`: a single closed rosette drawn by a visible machine, versus a chaotic
braid of flat ribbons whose trajectory the fluid decides.

### Faraday - `faraday`

**Phenomenon: parametric (Faraday) instability - a shaken fluid layer responding at half the drive
frequency, with the pattern's symmetry class selected by a nonlinear amplitude competition.**

```
Effective gravity:   g(t) = g [ 1 + Gamma cos(omega t) ],   Gamma = A omega^2 / g
Per-mode Mathieu:    z'' + 2 mu z' + omega0^2 [ 1 + Gamma cos(omega t) ] z = 0
Dispersion:          omega0^2(k) = [ g k + (sigma/rho) k^3 ] * tanh(k h)
Viscous damping:     mu = 2 nu k^2
Threshold:           Gamma_c = 4 mu / omega0 = 8 nu k^2 / omega0
Resonance tongues:   omega / omega0 = 2/n ;  dissipation kills n > 2, so the n = 1
                     subharmonic (omega0 = omega/2) is what you see
```

`k_c` solves `omega0(k_c) = omega/2` - by a **5-iteration Newton solve on the full dispersion above**, not
by the deep-water capillary closed form. Pattern selection is a Landau amplitude system over wavevectors
all of magnitude `k_c` at different orientations:

```
A_j' = sigma_j (Gamma/Gamma_c - 1) A_j  +  q_j sum_{(l,m): k_l + k_m = k_j} A_l A_m
       -  beta A_j^3  -  sum_{i != j} g(theta_i - theta_j) A_i^2 A_j

h(x,t) = sum_j A_j cos(k_j . x + phi_j) * cos(omega t / 2 + psi)
```

One wavevector is stripes, two at 90 degrees is squares, three at 120 degrees with `sum k_j = 0` is
hexagons. Integrated semi-implicitly, `A_new = A + dt*linear / (1 + dt*beta*A^2)`, so the cubic term cannot
overshoot into oscillation.

**Look.** A wide shallow tray, seen from a low angle so you read the depth of the fluid and the meniscus at
the walls. The surface is not rippling - it is standing in a lattice with a nameable geometry: stripes when
the drive is weak, a square grid as it rises, hexagons higher still. The signature behaviour is the
inversion: every cell turns inside out once per drive period, peak becoming pit, because the response is at
half the drive frequency. Cell size shrinks as the pitch climbs, following a two-thirds power law, so a
rising line packs the tray with progressively finer cells. Defects are visible and mobile: dislocations
wander, grain boundaries drift and annihilate. Dye is dragged along the surface flow and pools in the cell
troughs, and the film thickness variation gives an oil-slick iridescence that shifts as the cells invert.

**Morph.** Pattern selection through a bifurcation, not shape interpolation. (1) Pitch sets `k_c`, so cell
size scales as roughly `f^(-2/3)` in the capillary regime: an octave up makes cells about 0.63 times as
wide. Continuous, legible, purely pitch-driven. (2) The two drive components set `Gamma1` and `Gamma2`, and
the Landau competition decides which of the one-, two- and three-wavevector solutions is stable - so as the
music changes the tray goes stripes to squares to hexagons to quasipattern through genuine
symmetry-breaking transitions, where one `A_j` collapses and another grows over a few hundred milliseconds.
You see the old lattice dissolve and the new one nucleate from defects, which is exactly what a real tray
does and completely unlike a crossfade. Hysteresis in the selection (a new symmetry must win by 20 percent
for 0.4 s before the old one is released) is both a nervousness fix and physically right, since these
bifurcations are hysteretic.

**Fluid coupling.** `FluidSim` (full) as the lateral surface transport, coupled both ways to the amplitude
system. Forward: the surface's Stokes drift, proportional to `grad(A_j^2)`, is injected as up to 12
`queueSplat` entries with dye colour from `FluidHue.base(paletteBase)`/`FluidHue.span`, so dye collects in
the troughs and is scoured from the crests without anyone drawing it there. Reverse leg one, the important
one: `velocityTex` is sampled per-vertex and used to advect each wavevector's **phase**,
`phi_j += (k_j . u) * dt`. Because the phase is advected non-uniformly across the tray, the lattice
develops real dislocations and grain boundaries that then drift - a rigid analytic cosine sum cannot
produce a defect, and defects are most of what makes a Faraday photograph look alive. Reverse leg two:
`dyeTex` modulates the thin-film thickness `d` in the iridescence (`OPD = 2 n_film d cos theta2`), so where
dye has pooled the film is thicker and the colour shifts - physically correct, since thickness variation is
precisely why a real film is iridescent.

**Critic's objection and the fix.** Three, and the first is a physics error that would have failed its own
test. (1) **Hexagons were not available from the stated equations.** Pure subharmonic response has the
symmetry `A -> -A` (a half-drive-period time shift), which **forbids quadratic terms** in the amplitude
equation. The design's Landau system was cubic-only, consistent with that symmetry - and a cubic-only
system with equal-magnitude wavevectors selects stripes or squares, never hexagons, because hexagon
selection is exactly the resonant-triad quadratic term the symmetry kills. Single-frequency Faraday gives
stripes and squares; hexagons and quasipatterns require **two-frequency forcing**. So "stripes to squares
to hexagons to quasipattern as rms rises" was not what the equations produced, and the test that "pins
that the Landau system selects 1/2/3 wavevectors at rising supercriticality" would have failed against the
physics. **Fix: a second drive component**, `Gamma1 cos(omega t) + Gamma2 cos(2 omega t + phi)`, driven
from the two loudest partials. That is both how real experiments get hexagons and quasipatterns **and** a
better music mapping than rms alone; it legitimately reintroduces the quadratic term (written into the
amplitude equation above) and makes the "symmetry class is what the music changes" thesis true rather than
asserted. (2) **Safety.** An 8 Hz full-field peak-to-pit inversion of a high-contrast iridescent lattice
under grazing light is a large luminance swing above the 3 flashes/second general-flash threshold, and the
design correctly noted that `VisualSafety` cannot see a geometric inversion - which meant the scene-local
clamp was the only protection and it was set too high. **Fix: the visual inversion rate is clamped to 3 Hz
via `PitchClock.MAX_FLASH_HZ`, the clamp lives in `FaradayMath` so a test pins it rather than a shader, the
shading preserves mean luminance across the inversion (the two signs are tinted, bright and dark are not
swapped), and the scene exposes the inversion rate and swing so the global limiter can see this style** -
because the next design with a geometric flip will hit the same blind spot. (3) **`k_c` and depth were
inconsistent**: the closed form `k_c = [rho omega^2/(4 sigma)]^(1/3)` is the deep-water capillary limit and
contains no `h`, yet the depth parameter was documented as entering through `tanh(k h)`. Fixed with the
Newton solve above - about 20 CPU flops per frame, and it makes the depth parameter mean something.
Fourth, licence: the CC BY-NC-SA utoronto lab manual is replaced by Kumar & Tuckerman for the same
equations. Fifth: the three-wavevector Landau integrator is **factored into one shared module** used by
both this style and `rosensweig`, rather than written twice.

**Audio.** `dominantPitchHz` -> `omega`, hence `k_c` and the cell size. The two loudest partials -> `Gamma1`
and `Gamma2`, hence which lattice symmetry is stable. bass -> `h`, the fill depth, which enters through
`tanh(k h)`. treble -> `nu`, hence `Gamma_c` and how sharp the threshold is - a bright mix makes the tray
twitchy and quick to switch lattices. transient/onset -> a `Gamma` spike above `Gamma_c`, which nucleates a
pattern switch - the physically honest way for a drum hit to change the picture. beatPhase -> `psi`, locking
the inversion to the beat when `pulseConfidence` is high. flux -> the initial orientation spread, so a busy
passage seeds more competing orientations and more grain boundaries.

**Params.** `cymFaradayDrive` 0..3 / 1.2 (`Gamma/Gamma_c` at unit rms); `cymFaradayTwoTone` 0..1 / 0.5
(how much of the drive is the second harmonic component - the hexagon/quasipattern control);
`cymFaradayDepth` 0.05..1 / 0.4; `cymFaradayTension` 0.2..3 / 1 (`sigma/rho`); `cymFaradayViscosity`
0.05..2 / 0.4 (`nu`); `cymFaradayModes` 1..6 / 3; `cymFaradayFlicker` 0..1 / 0.8 (strength of the
subharmonic inversion; **rate** is clamped independently and is not a user control);
`cymFaradayIridescence` 0..1 / 0.6; `cymFaradayDefects` 0..1.5 / 0.7.

**Files.** `render/cymatic/FaradayScene.kt`; `render/cymatic/FaradayMath.kt` (pure and allocation-free:
capillary-gravity dispersion, the Newton `k_c` solve, `Gamma_c`, the two-frequency drive, and the shared
Landau amplitude integrator); `render/cymatic/LandauAmplitudes.kt` (the six-ODE three-wavevector system,
shared with `rosensweig`); `res/raw/cym_faraday_surface_vert.glsl`, `cym_faraday_surface_frag.glsl`;
`app/src/test/java/dev/musicviz/FaradayMathTest.kt` (the response period is twice the drive period; `k_c`
matches the closed form in the capillary limit and moves correctly with `h`; `Gamma_c`; **the system selects
1, 2 and 3 wavevectors at rising supercriticality only when the second drive component is present, and
selects only 1 or 2 when it is absent** - the test now agrees with the physics; the visual inversion rate
never exceeds 3 Hz across the whole parameter range; mean luminance is preserved across an inversion).

**Budget.** The physics is nearly free: six coupled ODEs on the CPU per frame, six cosines per vertex. Mesh
192x192 = 73,728 triangles, one draw, about 90 ALU per vertex. Fragment cost is the real one: Fresnel plus
a refraction fetch plus the Airy summation, about 60 ALU per pixel over roughly 70 percent of the frame. At
`ResTarget` 0.7x on 3.24 Mpx that is 1.6 Mpx x 60 = 95 MFLOP/frame, under 5 GFLOP/s at 50 fps. `FluidSim`
at Medium is 26 + 2N passes on 128/512. Total about 30-58 offscreen passes + 1 mesh draw + 1 floor draw -
FLUID Medium. **Low:** mesh 96x96, sim 96/384, iridescence replaced by a palette lookup.

**Risks.** The Landau system going stiff when `Gamma/Gamma_c` is pushed hard - amplitudes are clamped and
the cubic term is integrated semi-implicitly. The tray reads as a flat texture if the camera is too high;
default elevation is low and the meniscus at the walls is drawn to sell the depth. Iridescence banding in
mediump: OPD is nanometre-scale times thickness and must be highp.

**Distinct.** Not the existing cymatics style and not `bessel_drum`: both render **eigenmodes** of a linear
system, which is the thing a real cymatics dish is famously not doing. Faraday is a parametric instability -
the response is at half the drive frequency, the pattern is selected by a nonlinear amplitude competition,
and the lattice inverts every drive period. That inversion is the single behaviour that separates a real
driven fluid from every mode-sum picture, and no other style in the app has it. Not `water`/`ripples`/
`waves`: linear wave propagation with no threshold, no pattern selection and no subharmonic. Within this
family it is the only nonlinear one - every other style superposes linearly - and the only one where the
pattern's **symmetry class**, not just its scale, is what the music changes. Against `rosensweig`, with
which it shares the Landau integrator: a flat wide tray with a shallow lattice, versus a mirror-black spike
forest with hysteresis.

### Shell - `harmonic_shell`

**Phenomenon: inextensional flexural (spheroidal bending) vibration of a thin spherical shell, with powder
transported from antinodes to nodal bands across its curved surface.**

```
Real-form spherical harmonics:
   m > 0 :  Y = sqrt(2) * N_{l|m|} * P_l^{|m|}(cos theta) * sin(|m| phi)
   m = 0 :  Y = N_{l0} * P_l(cos theta)
   m < 0 :  Y = sqrt(2) * N_{l|m|} * P_l^{|m|}(cos theta) * cos(|m| phi)
   N_lm = sqrt( (2l+1)/(4 pi) * (l-m)! / (l+m)! )

Stable Legendre recurrence:
   P_m^m       = (-1)^m (2m-1)!! (1 - x^2)^{m/2}
   P_{m+1}^m   = x (2m+1) P_m^m
   (l-m) P_l^m = x (2l-1) P_{l-1}^m - (l+m-1) P_{l-2}^m

Surface:   r(theta, phi, t) = R [ 1 + eps * Y_lm(theta, phi) * cos(omega_l t) ]

Flexural (radially moving) spectrum, thin shell:
   omega_l  proportional to  (h/R) * l(l+1) * sqrt( (l-1)(l+2) / (l^2 + l + 1 + nu) )
```

Nodal structure is exactly `|m|` meridians and `l - |m|` latitude circles, so the mode is countable on
screen.

**Look.** A single sphere, lit by one hard key and a cool rim, orbiting slowly. Its surface is genuinely
deformed - lobes push outward and pull inward in a pattern with a countable structure: you can see the
meridians and the latitude circles and count them. Nodal lines are dark bands where the surface is
momentarily still, and the two signs of the displacement are tinted differently, warm outward and cool
inward, so the parity is readable at a glance. The shell **self-shadows**: a lobe pushing toward the light
throws a real shadow into the trough beside it. Fine powder clings to the surface and moves - thrown off
the antinodes as they accelerate, sliding along the surface, packing into the nodal bands until they are
visible ridges. As the pitch climbs the shell subdivides: a smooth two-lobed rugby ball becomes a
four-lobed clover becomes a knobbly star. As the pitch class walks within the octave the character changes
from a stack of latitude rings, through a beach-ball segmentation, to pure meridian stripes, without the
total order changing.

**Morph.** A two-integer morph driven by two independent aspects of pitch - the cleanest pitch-to-shape
mapping in either family. The total order `l` comes from absolute pitch height through the wavenumber law,
so low notes give `l = 2` or 3 and high notes `l = 10` or more. Independently, `m` comes from the pitch
**class**: the position of the dominant chroma bin within the octave maps to `m/l` in `[0,1]`, so a
chromatic run at constant octave walks `m` from 0 to `l` while `l` stays put, and the shell morphs from
zonal (pure latitude rings) through tesseral (a checkerboard) to sectoral (pure meridian orange segments) -
a continuous metamorphosis at fixed subdivision count. Because the axes are orthogonal, a rising chromatic
scale traces a diagonal through `(l, m)` space and never repeats. Third axis: `eps`, from rms - at high
drive the lobes are deep enough to nearly pinch off.

**Fluid coupling.** `FluidSim` with its 2D domain mapped to the shell's `(theta, phi)` parameter square -
the powder on the shell is a dye field living in the surface's own coordinates, which is the cheap way to
put a fluid on a curved surface without a 3D solver. Forward: the radial surface velocity is evaluated at
up to 12 antinodes, converted to `(u, v)`, and pushed in as `queueSplat` with velocity directed down the
local `|Y|` gradient - so powder is thrown off the accelerating antinodes and driven toward the nodal
bands, which is the actual mechanism (a Chladni plate wrapped onto a sphere). Reverse leg one: `dyeTex` is
sampled per-fragment as the powder layer, so the ridges are accumulated material with thickness-dependent
shading, not a painted band. Reverse leg two, closing the loop: the dye density is read back coarsely (a
32x32 reduction reusing the `FlowField.readback` pattern, at 20 Hz not 60, decoupled by a frame counter)
and used to raise the local damping `gamma` in `ModalBank` - a loaded region of the shell rings less, so
the powder that has collected **detunes the shell** and the mode set shifts as material accumulates. That
is mass loading, it is real, and it means the figure never sits perfectly still.

**Critic's objection and the fix.** Four. (1) **Wrong mode family for the motion being rendered.**
`Omega_l = sqrt((l-1)(l+2))` is the **torsional** spectrum of a thin spherical shell - purely tangential
displacement, zero radial component - while the render was a purely **radial** displacement. The frequency
law and the deformation belonged to different mode families: the modes being drawn were invisible in the
spectrum being used, and vice versa. The sanity check that confirms it: that formula gives `Omega_1 = 0`,
which is the rigid rotation of the torsional family, not a breathing mode. **Fix: the inextensional
flexural (spheroidal bending) spectrum above, which is the family that actually moves radially**, and the
torsional result is no longer cited. (2) **The powder solver's domain was geometrically wrong.** A
Cartesian Navier-Stokes solve on the `(theta, phi)` square is not a fluid on a sphere: it uses the wrong
metric and the wrong area element, so cells near `theta = 0` and `theta = pi` represent vanishingly small
physical area while occupying full grid cells. Powder would pile at both poles for parametrisation reasons
and that pile would look exactly like the antinode-to-node transport the style is selling - a coordinate
artefact masquerading as the phenomenon. The design handled the `phi = 0` seam carefully and did not
mention the poles at all. **Fix: weight the injection and the dye read by the metric** - scale
`queueSplat` strength and the sampled density by `sin(theta)`, the Jacobian of the parametrisation, one
multiply in two places - **plus a test that a uniform physical injection over the sphere produces uniform
dye density in physical area, not in parameter area.** Without that test the pole artefact ships and gets
mistaken for a feature. The design also states plainly that this is a fluid on a parameter square
approximating a fluid on a sphere, good in the mid-latitudes and poor near the poles. (3) **The mesh could
not resolve the promised orders.** `cymShellOrder` reached 16, about 32 lobes around the equator; an
icosphere at subdivision 6 has about 320 vertices around a great circle - 10 per lobe - and the tier ladder
dropped to subdivision 5, giving 5 per lobe, at which point "you can count the meridians" is false and the
shell is faceted. The `n_max <= side/6` clamp had been applied to the plate and not here. **Fix: `l_max <=
verticesPerGreatCircle / 8`, and the mode's contribution fades rather than popping.** The offered ceiling
drops from 16 to 12. (4) The Legendre recurrence's `(1 - x^2)^{m/2}` term underflows to zero at high `m`
well before the poles, silently killing sectoral modes; the recurrence is carried in **highp** with
`x = cos(theta)` clamped to `[-1 + 1e-6, 1 - 1e-6]`. Fifth, a nomenclature fix that belongs here: this is
the only style in either family that uses genuine `Y_lm`, and `hyper_reliquary`'s Bourke lobe surface is
renamed accordingly so the two do not collide.

**Audio.** bands -> `ModalBank` excitation keyed on the flexural `omega_l` - the second geometry that
proves the ordering key must be geometry-supplied. Pitch height -> `l`. Chroma bin position within the
octave -> `m/l`. rms -> `eps`. treble -> powder injection rate. bass -> shell radius. beatImpulse -> a
radial impulse exciting all active modes in phase, so the shell pings and the powder jumps. onset -> a
widened Lorentzian, briefly recruiting neighbouring `(l, m)`. progress -> the key light's slow azimuthal
drift, so the self-shadowing reveals different faces over a track.

**Params.** `cymShellOrder` 2..12 / 8 (the fineness ceiling, clamped further by mesh density);
`cymShellDepth` 0..1 / 0.45 (`eps`); `cymShellSectoral` 0..1 / 0.5 (how strongly pitch class pushes `m`
toward `l`; 0 pins `m = 0`); `cymShellPowder` 0..1.5 / 0.7; `cymShellLoading` 0..1 / 0.4 (mass-loading
feedback strength); `cymShellShadow` 0..1 / 0.7; `cymShellParity` 0..1 / 0.6 (how differently the two
displacement signs are tinted); `cymShellRing` 0..1 / 0.35.

**Files.** `render/cymatic/ShellScene.kt`; `render/cymatic/SphericalHarmonics.kt` (pure: the Legendre
recurrence and its derivative, baked normalisers to `l = 12`, the flexural `omega_l`, the `(l, m)`
selection from pitch height and pitch class, and the `sin(theta)` metric weights);
`res/raw/lib_legendre.glsl`; `res/raw/cym_shell_vert.glsl`, `cym_shell_frag.glsl`;
`res/raw/cym_shell_depth_vert.glsl` (the same displacement, depth-only, for the shadow pass);
`app/src/test/java/dev/musicviz/SphericalHarmonicsTest.kt` (orthonormality over a Lebedev-style sample set;
the nodal counts - `|m|` meridians, `l - |m|` latitudes; the recurrence against a series expansion at high
`m` near the poles, where it must not underflow; **uniform physical injection gives uniform physical
density**; the seam at `phi = 0` samples equal on both sides).

**Budget.** Icosphere at subdivision 6 is 40,962 vertices / 81,920 triangles, one draw. Vertex cost is the
recurrence to `l = 12` for up to 8 modes: `8 x 12 x ~8` = about 770 ALU per vertex, roughly 32 MFLOP per
frame - the heaviest vertex shader in either family, and the reason the mode count is capped at 8 and the
order at 12 rather than being open-ended. The shadow pass runs the same displacement at 1024x1024
depth-only, about half the vertex cost again with no fragment work. This is the first thing in the app that
casts a shadow at all. Fragment cost is a lit surface with a 4-tap PCF plus one dye fetch over about 40
percent of the frame. `FluidSim` at Medium plus one 4 KB readback at 20 Hz. Total about 30-58 offscreen
passes + 2 geometry draws - FLUID Medium, weighted toward **vertex** rather than fragment, which is unusual
for this app and is useful because the fragment-heavy styles tier down at different points. **Low:**
subdivision 5 with `l_max` clamped to 6 accordingly, shadow map 512.

**Risks.** The mass-loading feedback can oscillate - powder damps the mode, the mode stops throwing powder,
powder disperses, the mode restarts. Mitigated by a 1.5 s one-pole on the readback and by capping the
loading contribution at 40 percent of base `gamma`; `cymShellLoading` defaults to 0.4 for the same reason.
Without RGBA32F the readback is unavailable exactly as `FlowField` documents; the mass-loading leg is
skipped and the style runs one-way, which is a look change, not a failure.

**Distinct.** Not the existing cymatics style: two geometries, both planar, both fullscreen 2D, with a
`Mode` class carrying two integer orders on the square-plate metric - a spherical harmonic needs a
different key and a sign branch as a third piece of state, which the current `vec4` packing cannot hold.
Not `metaballs`/`julia`/`mandel`: raymarched implicits with no modal physics, no material on the surface
and no shadows. Not any particle style: the powder is a dye field in surface parameter space, advected by a
real solver. It is the only closed 3D body, the only style that casts a shadow, the only one with a
two-integer pitch mapping from two independent musical quantities, and the only one where accumulated
material feeds back into the physics that put it there. Against `hyper_reliquary`: one large smooth body
with powder and a shadow, versus many small hard-faceted opaque stones.

### Caustic - `caustic_sheet`

**Phenomenon: Huygens interference on a bounded liquid sheet, and the refractive caustic it throws onto a
receiver plane below.**

```
Source field (2D cylindrical spreading):
   psi(x, t) = sum_j ( A_j / sqrt(|x - x_j| + eps) ) * cos( k_j |x - x_j| - omega_j t + phi_j )

Two-wave identity (the visible large-scale structure):
   cos(k1.x - wt) + cos(k2.x - wt) = 2 cos((k1-k2).x/2) * cos((k1+k2).x/2 - wt)
   envelope spatial period = 4 pi / |k1 - k2|

Paraxial caustic (always on):
   B = 1 / max(eps, 1 - K * lap(h)),      K = D (eta - 1) / eta

Photon splat (above the lowest tier):
   n = normalize(-h_x, -h_y, 1);   c = -dot(n, i)
   t = eta*i + ( eta*c - sqrt(1 - eta^2 (1 - c^2)) ) * n      (Snell, vector form)
   p = x + t * (D / |t_z|);       intensity  proportional to  1/|det dp/dx|
```

**Look.** A dark room. A thin horizontal sheet of liquid hangs in mid-air about two thirds of the way up
the frame, held in a visible bounding rim, lit from above by one hard source, with the floor a long way
below. The sheet is barely visible - a faint refracting membrane you catch at grazing angles - but what it
does to the light is not: the floor carries a web of bright filaments that braid, split and collapse into
brilliant points, blazing exactly over the sheet's troughs and dark over the flat parts. Faint dust in the
volume makes the light shafts visible. Twelve source points sit on a ring, one per pitch class, each
pulsing at its own amplitude; where two meet, the interference makes a broad slow moire envelope that
sweeps across the sheet and drags the caustic web with it. Wavefronts reflect off the rim and re-interfere,
which is a large part of the web's complexity.

**Morph.** The twelve sources **are** the chromagram: source `j` sits at angle `2 pi j / 12`, its amplitude
is chroma bin `j`, and its wavenumber comes from that pitch class through the dispersion. One note lights
one source and the sheet carries clean concentric rings that tighten as the note rises. Two notes create
the difference wavevector and the slow envelope. **Tight fringes mean a consonant, widely-spaced interval;
broad slow bands mean a near-unison.** Three or more and the envelopes multiply into a quasi-periodic web.
Second axis: the sheet-to-floor distance `D`, which appears linearly in `K` and in the photon projection,
so a rising macro-energy raises the sheet and blows the web up across the floor. Third: `eta`, from
spectral centroid - a bright mix bends light harder and the filaments get thinner.

**Fluid coupling.** `RippleSim` is the sheet; the Huygens sources are drive, not render. Forward: each
pitch class is realised as repeated `queueDrop` at its ring position, fired at a rate proportional to its
own (rescaled) frequency and with amplitude from its chroma bin, plus `queueStroke` for sustained tones.
The FDTD produces the propagation, the spreading, the interference and the rim reflections for real -
analytic Huygens summation cannot give you wavefronts reflecting off the boundary and re-interfering.
`inkEnabled = false`: this style needs height only, and turning ink off saves two passes. Reverse leg one:
`heightTex` drives both the sheet's refraction and the caustic map, so the sim owns the entire look.
Reverse leg two: the caustic map is reduced on the CPU to its 16 brightest cells, pushed into
`FlowField.queueKick` on the floor plane, so the dust in the shafts is stirred hardest exactly where the
light is concentrated - a convection cell under each focus, which is physically what a bright caustic does
to air. `FluidParticles` on `flowField.velocityTex` is the dust.

**Critic's objection and the fix.** Five. (1) **The test oracle and the whole consonance reading were
backwards.** The design asserted "a consonant interval has a small `|k1 - k2|`" and pinned "a just interval
gives a coarser envelope than a tempered semitone". Envelope period is `4 pi / |k1 - k2|`: a just fifth
gives `|dk| = 0.5 k1` and period `8 pi / k1`, while a tempered semitone gives `|dk| = 0.0595 k1` and period
`67 pi / k1` - **the semitone is about 8x coarser.** Every sentence following from that is inverted.
**Fix: tight fringes = consonant, broad slow envelope = near-unison. Still legible and teachable, just the
opposite of what was written**, and the test oracle is flipped. (2) **Twelve sources at twelve audio
frequencies in one FDTD is not realisable.** `RippleSim` has a single wave speed, so `k_j = omega_j / c`,
and `omega_j` is bounded above by the rate at which impulses can be queued and further by the
32-drops-per-frame budget; the realisable spread of `k_j` is maybe one octave, not a 12-semitone chromatic
span. **Fix: `PitchClock`, and the design states honestly that the source wavenumbers are proportional to
pitch on a compressed scale rather than equal to it.** (3) **The sheet was described as unbounded while
relying on rim reflections as a headline feature.** Fix: **the sheet is bounded and the rim is a deliberate
part of the picture**, drawn as a visible ring. (4) **Photon density.** One point per height texel is
147,456 points into a 262k-texel map - undersampled by about 2x before any focusing occurs, so a calm sheet
produces a stippled map rather than a smooth one, since intensity-from-splat-density only works when
several photons land per texel. **Fix: the caustic map drops to 256^2 so the flat-sheet case starts
oversampled, and each photon gets a soft 2-3 px kernel with a matching normalisation.** The half-texel IGN
jitter addresses aliasing but not the density deficit. (5) **Budget against ROP, not ALU.** 147k additive
points concentrated at folds into an RGBA16F target is blend serialisation. **The photon pass runs every
other frame at all tiers, not only below the top**, and `cymCausticPhotons` is the continuous blend it was
described as. A sixth, smaller item: the shaft march samples a 2D floor caustic map as volumetric emissive
along the view ray, which is only correct for a strictly vertical key light - **that constraint is stated,
and the key light is constrained to within 15 degrees of vertical** rather than letting the shafts point
the wrong way.

**Audio.** `CymaticChroma`'s twelve bins -> the twelve source amplitudes directly; this is the only style
that uses the full chroma vector rather than its top one or two entries, and it is the reason
`CymaticChroma` is worth building. Each bin's centre frequency -> that source's `k_j` and drop rate
(through `PitchClock`). rms -> global drive. macroEnergy -> `D`. centroid -> `eta`. beatImpulse -> one
large drop at the ring centre, sending one clean front through all twelve patterns at once, which
momentarily unifies the web. transient -> a brief spike in photon exposure. beatPhase -> the ring's slow
rotation. pulseConfidence -> how tightly drop firing is quantised to the beat.

**Params.** `cymCausticHeight` 0.2..3 / 1.2 (`D`); `cymCausticIor` 1.05..1.8 / 1.33 (`eta`);
`cymCausticSources` 1..12 / 12; `cymCausticPhotons` 0..1 / 0.7 (blend from the paraxial term to the photon
splat); `cymCausticShafts` 0..1.5 / 0.6; `cymCausticSheet` 0..1 / 0.25 (visibility of the membrane itself);
`cymCausticRing` 0.2..1.5 / 0.8 (source ring radius); `cymCausticExposure` 0.2..3 / 1.

**Files.** `render/cymatic/CausticScene.kt`; `render/cymatic/HuygensSources.kt` (pure: chroma bin to
position/wavenumber/drop-rate through `PitchClock`, the difference-wavevector envelope period, the
beat-quantised firing schedule budgeted so the total never exceeds 32 drops per frame against
`RippleSim.MAX_PENDING = 64`); `res/raw/cym_caustic_photon_vert.glsl`, `cym_caustic_photon_frag.glsl`;
`res/raw/cym_caustic_floor_frag.glsl`; `res/raw/cym_caustic_sheet_frag.glsl`;
`res/raw/cym_shaft_march_frag.glsl`; `app/src/test/java/dev/musicviz/HuygensSourcesTest.kt` (the two-source
envelope period matches `4 pi / |k1 - k2|`; **a wide interval gives a finer envelope than a near-unison** -
the corrected oracle; the photon projection matches the paraxial term in the small-curvature limit; the
firing schedule never exceeds the drop budget).

**Budget.** `RippleSim` at 384 with 12-24 drops per frame: 3 splat passes (height only, ink off, so one
pass each not two), at most 6 substeps, no ink advect - about 9 passes. Photon splat: one `GL_POINTS` draw
of 147k points into a 256^2 RGBA16F caustic map, **every other frame**. Floor and sheet are two small
geometry draws. Shaft march is 16 steps at `ResTarget` 0.5x bounded between two known depths, blue-noise
dithered - about 0.5 Mpx x 16 x 12 ops = 100 MFLOP/frame. `FlowField` 18+N passes; `FluidParticles` 65k
points. Total about 30 offscreen passes + 1 photon cloud (half rate) + 1 particle cloud + 2 geometry draws
+ 1 half-res march. Upper-middle of FLUID Medium. The honest hazard is two point clouds on a
bandwidth-limited Mali, which is why the photon pass is half-rate and the dust is 65k rather than 262k.
**Low:** photons off (paraxial only - a single Laplacian tap, costing nothing), shafts off, dust off.

**Risks.** Caustic dynamic range: `1/|det dp/dx|` genuinely goes to infinity at a fold, so the map is
RGBA16F with a hard ceiling at injection (the `dyeCeiling` precedent) and a tone map on read, or the floor
blows to pure white on every focus. Square wavefronts from the 5-point Laplacian are **fatal rather than
cosmetic** here, because the caustic amplifies the anisotropy - the 9-point stencil is a hard requirement,
shared with `bessel_drum`. Photon splat aliasing when the sheet is calm is handled by the half-texel IGN
jitter plus the soft kernel.

**Distinct.** Not `ripples` or `waves`: fullscreen 2D styles that draw the height field itself; here the
height field is nearly invisible and the subject is what it does to light on a surface a metre below,
which requires a camera, two planes at different depths and a real projection. Not `water`: a pool from
above with an ink film, no receiver plane, no refraction projection, no caustic map. Not `bessel_drum`,
which also uses `RippleSim`: that is a bounded circular head driven by a modal bank and rendered as its
own surface with a floor caustic as a secondary flourish; this is a sheet driven by twelve independent
point sources where the caustic **is** the image, with a full photon splat rather than the paraxial tap.
It is the only style that consumes the entire twelve-bin chromagram, and the only one whose visible subject
is a projection of the field rather than the field.

### Levitator - `levitator`

**Phenomenon: acoustic levitation - the Gor'kov radiation potential trapping droplets at the pressure
nodes of a standing wave, with Rayleigh streaming circulating the air around them.**

```
Field, with the transducer aperture envelope:
   p(r, z, t) = P * exp(-r^2 / w^2) * cos(k z) * sin(omega t)
   nodes at z = (n + 1/2) * lambda/2,   plane spacing = lambda/2 = c/(2f)

Gor'kov potential for a small sphere (R << lambda):
   U = 2 pi R^3 [ f1 * <p^2> / (3 rho0 c0^2)  -  f2 * rho0 <v^2> / 2 ]
   f1 = 1 - (rho0 c0^2)/(rho_p c_p^2)
   f2 = 2 (rho_p - rho0) / (2 rho_p + rho0)
   F = -grad U

Trap dynamics:  z'' = -(k_trap/m)(z - z_node) - 2 zeta omega_trap z' + drag
   k_trap = d2U/dz2 at the node, proportional to P^2 k^2
```

For a dense droplet `f2 > 0` and `f1` is close to 1, so it traps at pressure **nodes**; the axial force is
far larger than the radial, which is why real levitators hold beads in planes rather than points. The
`exp(-r^2/w^2)` aperture envelope is what makes the radial force nonzero at all.

**Look.** Two circular transducer arrays face each other across a gap, their emitter faces catching a cold
specular. Between them float twenty or thirty droplets, held in horizontal planes, evenly spaced, each
flattened slightly into an oblate shape by the radiation pressure squeezing it from above and below. They
are not static: each sits in a parabolic well and rings about it, and when two drift close they merge with
a visible coalescence, then later split. The pressure field is faintly visible as translucent shells, and a
fine mist fills the gap, spiralling into the node planes and revealing the streaming flow. The moment that
sells it: when the pitch rises the node spacing shrinks, the planes slide toward the centre, and the
droplets cannot follow continuously - they hang, then **jump**, snapping to the nearest surviving trap with
an overshoot and a ring. On a chord the traps are the minima of a superposed field and stop being evenly
spaced, so the droplets settle into an irregular stack that shifts as the harmony moves.

**Morph.** Node spacing is `lambda/2 = c/(2f)`, so the dominant pitch literally sets how many trap planes
exist between the arrays - and because the count must be an integer, a continuous pitch glide produces
**discrete reorganisations**. As `f` rises a new plane is born at the centre and every droplet
redistributes; as `f` falls a plane is annihilated and the droplets it held must jump. The jump is the
morph: a physical discontinuity complete with overshoot and ring-down whose rate is set by `d2U/dz2`
(proportional to `P^2 k^2`, so louder means stiffer means faster ringing). Second axis: polyphony. With two
or three partials the total potential is a sum of standing waves at different `k`, and its minima are
neither evenly spaced nor equal in depth - shallow traps let their droplets wander and eventually lose them
to a deeper neighbour, so a chord produces a visibly unequal, slowly reorganising stack. Third axis: `f2`,
from spectral centroid. **When `f2` passes through zero the droplet becomes less dense than the medium (a
bubble) and traps at pressure ANTINODES - the whole stack jumps by `lambda/4` at once.**

**Fluid coupling.** `FlowField` as the acoustic streaming field plus `FluidParticles` as the mist - the
second-order steady flow a standing wave drives, which is real and is why a levitator's chamber is always
visibly circulating. Forward: the streaming velocity, proportional to `-grad(|p|^2)` with a Rayleigh
convention sending flow along the axis toward the nodes and back out radially, is evaluated on a 6x6 grid
and pushed in as 36 `queueKick` calls per frame - well inside budget. Reverse leg one: `FluidParticles`
advects the mist so the toroidal streaming cells become visible as spiralling dust converging on each node
plane, and `setChoreography` is handed catch points at the node planes so mist accumulates there rather
than passing through. Reverse leg two, the CPU leg: each of the at most 32 droplets samples
`FlowField.cpuGrid.sample(u, v, out)` - the documented bilinear readout - and takes a Stokes drag force
from the local streaming velocity, so a droplet in a fast streaming cell is pushed off-axis and has to
fight its way back to the trap. Genuine two-way coupling at 32 bilinear samples per frame, using the class
exactly as designed. When `canReadback` is false the drag leg is skipped, per the documented degradation.

**Critic's objection and the fix.** Three. (1) **The field had no radial confinement, so the picture could
not form.** `p(z,t) = P cos(kz) sin(omega t)` depends only on `z`, therefore `U` depends only on `z`,
therefore **the radial force is identically zero** - and twenty-four droplets "held in horizontal planes"
would drift laterally out of the chamber under the very streaming drag the design was deliberately applying
through `cpuGrid`, with nothing to bring them back. Real levitators confine radially through the finite
transducer aperture, which was the omitted term. **Fix: the Gaussian aperture envelope above.** Two extra
ops in `GorkovMath`; it makes `F_r` nonzero and axis-seeking, makes the "laterally loose but held" look
true, makes the drag leg a genuine competition rather than an exit ramp, and gives the contrast parameter
something real to flip. (2) **`cymLevContrast` was documented wrongly.** `f2 < 0` means the particle is
less dense than the medium - a bubble - and the consequence is that it traps at pressure **antinodes**, a
`lambda/4` shift of the entire stack, not the "axis-seeking versus ring-seeking radial flip" the parameter
claimed. **That is a better morph than the one that was written, so it is kept and described correctly.**
(3) **The raymarch budget was 15x under**: "32 steps x 6 droplets = 192 distance evaluations per pixel over
0.2 of a 2 Mpx stage = 77 MFLOP/frame" counted one FLOP per distance evaluation, where a cubic-smin sphere
distance is about 15 ALU. The real figure is about 1.2 GFLOP/frame, roughly 60 GFLOP/s at 50 fps - still
affordable, but the style is not "genuinely cheap" by the margin claimed, especially with `gl_FragDepth`
killing early-Z for the whole draw. The budget below is restated in ALU with the early-Z loss called out.
A fourth item is a correctness note rather than a fix: summing `U` over partials is only valid **because
they are at different frequencies** (cross terms time-average to zero), and `GorkovMath` asserts on this so
nobody later sums two same-frequency fields' potentials.

**Technique note.** Droplets are raymarched rather than instanced because coalescence is the whole point
and it is free in an implicit formulation: iq's compact cubic smin (`k *= 6.0; h = max(k - |a-b|, 0)/k;
return min(a,b) - h*h*h*k/6`) makes the merge C2 with strictly compact support, so distant droplets are
culled by a bounding test. Fewer than about 32 droplets means the blend loop is unrolled over a uniform
array with no acceleration structure. The polynomial smins are **not associative**, so the evaluation order
is fixed and droplets fade out by adding a large constant to their distance rather than being removed from
the loop - which is the documented way to avoid popping. The raymarch writes `gl_FragDepth` from its hit
`t` so it interleaves correctly with the rasterised transducers; that costs early-Z on Mali and is a
deliberate trade, to be measured on device before the droplet cap is raised past 32.

**Audio.** `dominantPitchHz` -> `k`, hence `lambda/2`, hence the plane count and every jump. The two
next-loudest partials -> additional standing waves summed into the potential, producing unequal trap
depths. rms -> `P`, hence stiffness and ring-down rate, and hence how tightly droplets are held (quiet
passages let them sag). centroid -> `f2`, flipping node to antinode. beatImpulse -> a synchronous kick to
every droplet, so the stack rings in unison. onset -> a droplet split, which is what a real overdriven trap
does. bass -> array separation. treble -> mist injection rate. bpm -> slow rotation of the apparatus.

**Params.** `cymLevGap` 0.3..2 / 1; `cymLevPower` 0..2 / 1 (`P`); `cymLevAperture` 0.2..2 / 0.8 (`w`, the
transducer aperture - how strongly droplets are held on axis); `cymLevDroplets` 4..32 / 20;
`cymLevContrast` -1..1 / 0.6 (`f2`; negative traps at antinodes, jumping the whole stack by `lambda/4`);
`cymLevMerge` 0..1 / 0.5 (smin `k`); `cymLevRing` 0..1 / 0.4 (damping ratio `zeta`); `cymLevMist`
0..1.5 / 0.7; `cymLevField` 0..1 / 0.3 (visibility of the pressure shells).

**Files.** `render/cymatic/LevitatorScene.kt`; `render/cymatic/GorkovMath.kt` (pure and allocation-free:
`U` with the aperture envelope, its gradient and second derivative, `f1`/`f2` from density and sound-speed
ratios, multi-partial superposition with the different-frequency assertion, and the 64-sample trap-minimum
scan); `res/raw/cym_droplets_frag.glsl`; `res/raw/cym_transducer_frag.glsl`;
`res/raw/cym_pressure_shell_frag.glsl`; `app/src/test/java/dev/musicviz/GorkovMathTest.kt` (node spacing is
`lambda/2`; **the radial force is nonzero and axis-seeking with the aperture envelope, and identically zero
without it** - the regression that would otherwise reintroduce the bug; `f2 > 0` traps at nodes and
`f2 < 0` at antinodes with a `lambda/4` offset; stiffness scales as `P^2 k^2`; a two-partial superposition
produces unequally spaced minima; superposing two same-frequency fields asserts).

**Budget.** At most 32 spheres in an unrolled loop over a uniform `vec4` array (512 bytes, so it comes from
memory not registers - the documented Mali threshold, fine at one fetch per step). The march is bounded by
an analytic cylinder between the arrays, roughly 20 percent of the frame, 32 steps; with compact-support
culling the realistic average is about 6 active droplets per evaluation at about 15 ALU each, so
`32 x 6 x 15 = 2,880` ALU per pixel over 0.2 of a 2.3 Mpx `ResTarget` = 460k pixels = **1.3 GFLOP/frame,
about 66 GFLOP/s at 50 fps**. Transducers are two small geometry draws; pressure shells one additive
quarter-res pass. `FlowField` 18+N passes with 36 kicks plus the readback; `FluidParticles` 65k points.
CPU: 32 bilinear samples, a 64-sample trap scan, 32 oscillator integrations - microseconds. Total about 20
offscreen passes + 1 bounded raymarch + 1 particle cloud + 3 small draws. Affordable, but the `gl_FragDepth`
write disables early-Z for that draw, so the 20-percent coverage figure is the whole cost with no rejection.
**Low:** 12 droplets, 20 steps, shells off, mist off.

**Risks.** The smin blend merging all droplets into a single sausage when they line up in a plane - compact
support is the defence and `cymLevMerge` is capped against the minimum inter-droplet spacing. Jump chatter
if the pitch sits exactly on a plane-count boundary; mitigated by hysteresis - a new trap set must be
favoured for 0.25 s before a droplet commits - which is also what a real droplet's inertia does. Spurious
minima in a noisy superposed potential; the scan takes only minima deeper than a threshold fraction of the
global minimum and caps at 12 traps. Marching from inside a droplet is an unsafe bound, so the camera is
constrained outside the bounding cylinder.

**Distinct.** Not `metaballs`: a fullscreen 2D style with no camera and no physics driving the ball
positions. Not `orbits` or `galaxy`: particle systems on authored trajectories with no potential and no
traps. Not `hyperspace`: no fractals, no journey, no `MeltField`, and the bodies are held in place by a
solved potential rather than flying a path. It is the only style in either family built on **rigid bodies
in a field** rather than a continuous surface, a mesh or a particle cloud, the only one whose morph is a
discrete jump forced by an integer constraint (how many half-wavelengths fit the gap), and the only one
where the visible apparatus is part of the subject.

### Chamber - `standing_chamber` (rebuilt; the volumetric march was cut)

**Phenomenon: Helmholtz standing waves in a rigid rectangular cavity - a single coherent mode whose nodal
set is an exact union of planes.**

```
psi(x,y,z) = cos(nx pi x / Lx) * cos(ny pi y / Ly) * cos(nz pi z / Lz)
f          = (c/2) * sqrt( (nx/Lx)^2 + (ny/Ly)^2 + (nz/Lz)^2 )

Nodal set of this mode = a union of planes:
   nx planes at x = (2i+1) Lx / (2 nx),  i = 0 .. nx-1     and likewise in y and z
   total nx + ny + nz planes, dividing the room into (nx+1)(ny+1)(nz+1) cells
```

A cylindrical variant `psi = J_n(j'_{n,m} r/a) cos(n theta) cos(p pi z / L)` with
`f = (c/2pi) sqrt((j'_{n,m}/a)^2 + (p pi/L)^2)` reuses `BesselZeros`' free-rim table.

**Look.** You are inside a large dark rectangular chamber, drifting slowly forward. The air is divided by
faint luminous membranes into a lattice of cells, and the membranes are made of dust collected on them.
They are surfaces, not lines - bright filaments seen edge-on, broad translucent sheets seen face-on - and
as the camera moves the parallax makes the three-dimensionality unmistakable. The lattice is not cubic
unless the music says so: on a single note it is close to isotropic; on a chord the three axes subdivide
independently and you get long flat cells or tall narrow ones. When the harmony changes membranes do not
slide - a new one **nucleates** in the middle of a cell and splits it in two, or two merge and vanish, and
the dust redistributes over a second. Light scatters through the volume with a forward bias, so a membrane
between you and a bright region glows from behind. Dust piled thick makes a membrane opaque enough to
occlude what is behind it; thin regions are barely there.

**Technique.** The nodal planes are drawn as **explicit axis-aligned translucent quads** - `nx + ny + nz`
of them, at most about 42 - alpha-blended back-to-front with a Henyey-Greenstein term
`p(g, mu) = (1/4pi)(1 - g^2)/(1 + g^2 - 2 g mu)^{3/2}`, `g` about 0.4, in the fragment shader. Each family
of planes is parallel, so a per-family sort by view depth plus an interleave of the three families is an
exact back-to-front order from a trivial CPU sort each frame. Opacity comes from `FluidSim.dyeTex` sampled
**in the plane's own 2D coordinates** - which is exact, because a plane genuinely is 2D, so the solver's 2D
dye maps onto it with no projection trick at all. An integer order change cross-fades the new plane's alpha
over about 0.4 s so it grows rather than pops, while the count itself stays discrete. Depth-tested against
`DepthStage`, depth write off.

**Morph.** A three-integer morph, and this style is the reason `ModalBank` carries three orders. The total
wavenumber `N = sqrt(nx^2 + ny^2 + nz^2)` comes from pitch height, so a rising line subdivides the room more
finely. The **distribution** of `N` across the three axes comes from the chord: the three loudest chroma
bins are ranked and their relative strengths partition `N` into `(nx, ny, nz)`, normalised to preserve the
total. A single note gives a near-isotropic lattice; a two-note interval flattens one axis into slabs; a
three-note chord gives a fully anisotropic lattice whose aspect ratio is a direct readout of the voicing.
Because the orders are integers, a chord change does not lerp - a membrane count changes by one on some
axis and the room re-partitions with a nucleation or an annihilation. Second axis: the cavity's own
dimensions drift slowly with track progress, so the same chord voices differently at the start and end of a
song. Third: sheet thickness, from the resonance damping.

**Fluid coupling.** `FluidSim` (full). Forward: the acoustic streaming velocity, proportional to
`-grad(|psi|^2)`, is evaluated at 12 points and injected as `queueSplat`, so dye is genuinely driven toward
the nodal surfaces rather than placed there. Reverse leg one: `dyeTex` sampled in each plane's own 2D
coordinates **is** the membrane's opacity and colour, so the dust on a sheet is transported material and the
sheets differ from each other. Reverse leg two: `velocityTex` warps the in-plane sample coordinate, so the
dust on a membrane drifts and wrinkles rather than sitting still. Reverse leg three: accumulated dye density
damps `psi` locally, so a loaded cell goes dark and the room does not light up uniformly.

**Critic's objection and the fix.** This design was **cut** in review and is rebuilt on a different
renderer, which also fixes the physics error. (1) **Superposition destroys the nodal surfaces.** The nodal
set of a **single** cavity mode is a union of planes - true. But the design superposed up to 6 modes at
different frequencies, and the time-averaged radiation potential of an incoherent superposition is
`<p^2> = sum |psi_i|^2`, which is strictly positive almost everywhere: the codimension-1 nodal **surfaces do
not survive superposition**, they collapse to isolated points and curves. "A superposition curves them into
genuine surfaces" is backwards. Meanwhile the render used `exp(-|psi|/bandWidth)` on a **coherent** sum
whose zero set sweeps through the room as the relative phases advance, so the membranes would have swum
rather than nucleated and split. **Fix: one coherent mode.** The three-integer chord partition survives
intact - the chord sets `(nx, ny, nz)` of a single mode - so the good morph is kept and the physics is
exact. (2) **Texture rate, not ALU, was the wall, and only ALU was budgeted:** `0.5 Mpx x 40 steps x 2 dye
fetches` = 40M dependent fetches per frame = 2.4 Gtex/s, above a Mali-G57 MC2's peak, against an
optimistic "300 GFLOP/s sustained". (3) **Internal contradiction**: "precomputed per-axis cosine phases
carried incrementally along the ray" requires a uniform step in a straight line, which the `velocityTex`
domain warp destroys. You cannot have both. (4) At `bandWidth = 0.12` in a room of extent about 2 with 40
steps, the step is 0.05 - **two samples per membrane**, which aliases, and the early-out does not help.
(5) "Blue-noise temporal dither lets 40 steps look like 150" is not true with a drifting camera and a
moving field; you get ghosting. (6) The medium coupling was the thinnest of the twenty: a 2D dye sampled
through two orthogonal projections multiplied together, with the design itself conceding it "produces ghost
density where the two projections happen to agree at a point that has no dye in 3D", plus a velocity leg
that was a domain warp on a sample coordinate - it wrinkled a field, it did not transport anything.

**Fix: do not ship a participating-medium march inside the room.** Draw the nodal set as what it actually
is. About 30-42 quads, correct physics, the parallax kept, the nucleate/annihilate morph kept, one geometry
draw instead of a 40-step volumetric integral - and the pseudo-3D projection trick and its ghost density
disappear entirely, because a plane is 2D and the solver's 2D dye maps onto it exactly. If the fuzzy-medium
look is wanted later it can be measured against a shipping baseline.

**Audio.** bands -> `ModalBank` excitation on a three-order table keyed by `sqrt(nx^2+ny^2+nz^2)`. Pitch
height -> `N`. `CymaticChroma`'s three loudest bins and their relative strengths -> the partition. rms ->
emission and sheet opacity. bass -> chamber dimensions, so low end makes the room bigger and the cells
coarser. treble -> `g`, so bright material scatters more forward and the sheets glow harder from behind.
beatImpulse -> a pressure pulse that momentarily brings the mode into phase, collapsing dust onto the nodes
and letting it relax - **rate-limited through `PitchClock.MAX_FLASH_HZ`, because this is a full-field
luminance event.** onset -> a splat burst. progress -> the camera's forward drift. flux -> sheet thickness.

**Params.** `cymChamberOrder` 2..14 / 8 (maximum per-axis order); `cymChamberAspect` 0..1 / 0.7 (how
strongly the chord makes the lattice anisotropic; 0 forces `nx = ny = nz`); `cymChamberGeometry` 0..1 / 0
(0 rectangular, 1 cylindrical using the `j'_{n,m}` table); `cymChamberBand` 0.02..0.5 / 0.12 (sheet
thickness); `cymChamberDensity` 0..2 / 0.8 (sheet opacity); `cymChamberScatter` -0.5..0.9 / 0.4 (`g`);
`cymChamberDrift` 0..1 / 0.3 (camera forward drift); `cymChamberWarp` 0..1.5 / 0.5 (how hard `velocityTex`
wrinkles the in-plane dust).

**Files.** `render/cymatic/ChamberScene.kt`; `render/cymatic/CavityModes.kt` (pure: the three-order table
and its ordering key, the chord-to-axis partition, the plane positions per axis, the per-family depth sort,
and the cylindrical variant reusing `BesselZeros`); `res/raw/cym_chamber_plane_vert.glsl`,
`cym_chamber_plane_frag.glsl`; `res/raw/cym_chamber_walls_frag.glsl` (the cavity's own walls, faintly lit,
giving a scale reference); `res/raw/lib_volumetric.glsl` is **not** needed by this style any more - the HG
term lives in `lib_shade.glsl`; `app/src/test/java/dev/musicviz/CavityModesTest.kt` (nodal plane counts and
positions per axis; `f` against the closed form; the chord partition preserves `N`; the cylindrical variant
against the `j'_{n,m}` table; the back-to-front order is correct for every camera orientation, which is the
one thing that would silently look wrong).

**Budget.** About 42 quads, each covering a large screen area, alpha-blended - so the cost is overdraw, but
**42 quads of overdraw, not 48 slices with a 14-fetch fragment shader.** Per fragment: one dye fetch, one
velocity fetch for the warp, one HG evaluation of about 12 ALU, and the accumulation - roughly 30 ALU and 2
fetches. At `ResTarget` 0.6x on 3.24 Mpx that is 1.17 Mpx; with an average of maybe 8 overlapping planes per
pixel that is 9.4M fragments x 2 fetches = **19M fetches per frame, about 0.9 Gtex/s at 50 fps**. One
geometry draw for the planes, one for the walls, one trivial CPU sort. Comfortably FLUID Medium, and about
an order of magnitude below what the marched version would have cost. **Low:** `cymChamberOrder` capped at
5 (so at most 15 planes), no velocity warp, `ResTarget` 0.45x.

**Risks.** Alpha-blended coplanar quads seen edge-on go to zero coverage and flicker; each plane is given a
small thickness in the fragment shader via a view-angle term so an edge-on sheet reads as a bright filament
rather than vanishing. The back-to-front order is exact only because each family is parallel; if a future
variant tilts a family this breaks, and the code says so. `FluidSim.dyeCeiling` stays at its default here
because the sheets tone-map afterwards.

**Distinct.** Not `tunnel`, `warp` or `starfield`: 2D fullscreen illusions of depth with no volume, no
scattering and no field. Not `hyperspace`: that raymarches SDF bodies with a journey structure and
`MeltField`; this draws the exact nodal set of a solved standing wave. Not `nebula` or `storm`: particle
systems. It is the only style where the standing wave is **three-dimensional** - every other member has a
2D field, a surface or a curve - the only one whose mode index is a triple, which is the concrete reason
the family needs `ModalBank` rather than the existing two-integer `Mode`, and the only one where the viewer
is inside the vibrating object.

### Spikes - `rosensweig`

**Phenomenon: the Rosensweig normal-field instability - a ferrofluid surface undergoing a subcritical
bifurcation to hexagons, which is where the hysteresis comes from.**

```
Energy functional:
   F[h] = (rho g / 2) h^2  -  integral_0^h B (mu_r - 1)/2 * H_MF dz  +  sigma sqrt(1 + |grad h|^2)

Amplitude equation (subcritical - the quadratic term is the whole point):
   eps A + gamma (1 + eps) A^2  -  g A^3 = 0,      eps = (B^2 - B_c^2) / B_c^2

Critical values:
   B_c,inf^2 = 2 mu0 mu_r (mu_r + 1) sqrt(rho sigma g) / (mu_r - 1)^2
   q_c       = sqrt(rho g / sigma),     lambda_c = 2 pi sqrt(sigma / (rho g))

Most-unstable wavenumber (inviscid), with Bbar = B / B_c,inf:
   q_m / q_c = (1/3) ( 2 Bbar^2 + sqrt(4 Bbar^4 - 3) )      requires Bbar >= (3/4)^(1/4) = 0.9306

Surface:
   h(x) = A * sum_{j=1..3} cos( q_m * khat_j . x + phi_j ),   khat_j at 0, 120, 240 degrees
   then sharpened toward cusps by a profile warp
```

Subcritical means the stable branch is hexagons, ridges and squares are unstable branches, and there are
two stable solutions over a range of `eps` - so which one you are on depends on where you came from.

**Look.** A shallow pool of something like liquid obsidian, seen from a low angle so the spikes stand
against a dark horizon. At rest it is a mirror. As the field rises, the edge deforms first, circular ripples
appear around the rim, and then peaks nucleate on those crests and a hexagonal lattice snaps into existence
across the pool in a fraction of a second. The spikes are cusps - tall and narrow with concave flanks -
reflecting the environment in long vertical streaks. Between them the surface is a saddle/valley network,
not a plane, and you can see the spikes reflected in it. A thin iridescent film rides the surface. The
crucial behaviour is the **hysteresis**: the spikes appear with a jump when the drive crosses threshold,
and when the drive falls back they do not retract at the same point - they persist well below it and then
collapse suddenly, all at once. Spike spacing tightens as the pitch rises.

**Technique.** A height-field raymarch, not a general SDF: march along the ray testing `p.z < h(p.xy)` -
three cosines per step - then binary-refine the crossing over 6 iterations, then take an analytic normal
from the gradient of the same three-cosine sum. Far cheaper and far more robust than an SDF of a spiky
surface, whose Lipschitz constant would be terrible. Step length is scaled by
`1/(1 + cuspSharpness * q_m * A)`, the local Lipschitz bound of the height field - without this a
near-vertical flank changes faster than the step and a ray tunnels through a spike, putting holes in the
forest. Shading is a near-perfect mirror BRDF plus `lib_film.glsl`.

**Morph.** (1) `Bbar`, from bass and rms, drives `eps` through the threshold. Because the bifurcation is
subcritical, crossing upward produces a **jump** - `A` leaps from zero to the finite upper root in a few
frames - and crossing back down does not undo it at the same point: the upper root remains stable below
`B_c`, so the forest persists through quiet passages and then collapses all at once when the drive falls
far enough. A beat can nucleate a spike field that outlives the beat by seconds, which is **the only memory
effect in either family**. (2) Pitch sets the effective surface tension, hence `q_c` and hence the lattice
constant, so higher pitch means a denser forest, continuously and legibly. `Bbar` also feeds `q_m`, so
loudness tightens the lattice on top of pitch. (3) The three phases `phi_j` drift at a slow authored rate,
rotating the crystal.

**Fluid coupling.** `MeltField`, which is exactly what `MeltField` exists for and which this is the only
style in either family to use. Forward: each spike tip is a body - up to 16 `queueBodySplat` calls per
frame with strength proportional to the tip's growth rate `dA/dt`, so an erupting spike stirs the field
around it and a collapsing one sucks it back. `MeltMath.reach(melt, scale)` inflates the bounding radius so
a melting tip does not cut its own edge off - the documented non-optional detail. `queueTouchStroke` is
wired so a finger stirs the pool. Reverse leg one: the raymarch samples `melt.velocityTex` to warp the
`(x, y)` at which `h` is evaluated, so the hexagonal lattice is dragged and sheared and develops grain
boundaries and dislocations rather than being a rigid crystal. Reverse leg two: `melt.dyeTex` is the
ferrofluid's own surface stain, modulating both film thickness and a dark absorptive tint, so the pool has
history. Reverse leg three: `MeltMath.stepRelaxation(melt) = 1/(1 + 1.6*melt)` shortens the march step
wherever the warp is strong - the correct and already-implemented answer to a warped field no longer being
safe to step through at full length, and the discipline `hyper_polytope` originally omitted.

**Critic's objection and the fix.** Four. (1) **The default value produces NaN on first launch.**
`q_m/q_c = (1/3)(2 Bbar^2 + sqrt(4 Bbar^4 - 3))` requires `Bbar >= (3/4)^(1/4) = 0.9306`, and
`cymSpikeField` defaulted to **0.9**. `sqrt(-0.376)` is NaN, the lattice constant is NaN, and the screen is
black or garbage. **Fix: `Bbar` is clamped to `max(Bbar, 0.9306)` inside `RosensweigMath.qm`, the default
moves to 1.0, and a test sweeps `cymSpikeField` across its whole 0..2 range asserting `isFinite`** - this
class of default-value NaN is exactly what a range test catches. (2) **The budget omitted its own largest
term.** It counted 48 steps x (30 ALU + 2 fetches) = 96 fetches per pixel, correctly identified fetch rate
as binding, halved it with an RG pack - and then added "the reflected spike forest via a second short
march" and declared it "folded into the step budget above". It is not: a mirror surface's dominant content
**is** the reflection of the other spikes, so that is a comparable-cost second march, and a half-res
screen-space version of it on a spiky surface is a mess of holes. **Fix: a single low-resolution latlong
environment probe captured every 4th frame plus a cheap analytic horizon term.** The spike-on-spike
reflection is a look you approximate, not a budget you absorb. (3) **A sum of three cosines does not give
"mirror-flat between the spikes"** - a hexagonal cosine lattice has a saddle/valley network between peaks,
not a plane, and the described look is achievable only with an aggressively nonlinear profile map, at which
point the physics lives in the warp and the amplitude equation is decoration. **Fix: the look description
above says saddle/valley network, and the code says plainly that the cusp profile is a phenomenological
warp fitted to photographs while the amplitude equation supplies `A` and `q_m` only.** (4) The `phi_j`
precession was driven by the unmeasurable just-versus-tempered detune, and unlike the harmonograph case
there is no mechanism connecting a frequency detune to a crystal orientation at all. **Dropped**; `phi_j`
drift is a slow authored constant. Fifth: the Landau three-wavevector integrator is shared with `faraday`
rather than written twice.

**Audio.** bass and rms -> `Bbar`, hence `eps` and the subcritical jump. `dominantPitchHz` -> effective
`sigma`, hence `q_c` and the lattice constant. beatImpulse and transient -> a `Bbar` step large enough to
cross threshold, which is how a kick erupts the forest. treble -> the cusp sharpening exponent. mid ->
`MeltField` curl strength, shearing the lattice. macroEnergy -> pool extent and camera elevation. onset ->
a dye injection burst. The release side of the band envelopes -> how fast `Bbar` decays, and therefore how
long the hysteresis holds the forest up after a phrase ends.

**Params.** `cymSpikeField` 0..2 / 1.0 (`Bbar` at unit drive; clamped to at least 0.9306 internally);
`cymSpikeTension` 0.2..3 / 1 (`sigma/rho`, setting the base lattice constant); `cymSpikeHysteresis`
0..1 / 0.6 (width of the bistable region - how far below threshold the forest survives); `cymSpikeCusp`
0..1 / 0.55 (profile warp from cosine to cusp); `cymSpikeMirror` 0..1 / 0.8; `cymSpikeFilm` 0..1 / 0.5;
`cymSpikeMelt` 0..1 / 0.45 (how hard `velocityTex` warps the lattice, and hence how far the step is
relaxed); `cymSpikeDetail` 0.25..1.5 / 0.8 (march step budget - a performance setting, `NEVER_ROLLED`,
following the `hyperDetail` precedent at `ParamRandomizer.kt:86`).

**Files.** `render/cymatic/RosensweigScene.kt`; `render/cymatic/RosensweigMath.kt` (pure and
allocation-free: `B_c`, `q_c`, the clamped `q_m`, the cubic amplitude equation with branch tracking for the
subcritical bistability, the cusp profile warp, and tip extraction for the body splats);
`res/raw/cym_spikes_march_frag.glsl`; `res/raw/cym_spikes_shade_frag.glsl`;
`res/raw/cym_env_probe_frag.glsl` (the quarter-rate latlong probe);
`app/src/test/java/dev/musicviz/RosensweigMathTest.kt` (`q_c` against `sqrt(rho g / sigma)`; `q_m` against
the closed form; **`isFinite` across the entire `cymSpikeField` range** - the default-NaN regression; and
critically the hysteresis loop: sweeping `Bbar` up then down must return two different `A` values over the
bistable range).

**Budget.** The most expensive style in the cymatics family, stated plainly. Height-field march at
`ResTarget` 0.5x: 48 steps plus 6 binary refinement iterations plus 4 normal evaluations, each about 30
ALU, plus **one** packed RG velocity fetch per step (the two separate fetches are collapsed into one;
`MeltField`'s velocity grid is already RG-capable through `FluidBuffers.Formats.rg`). That is about 1,700
ALU and 48 fetches per pixel. Three things pull the step count down: `stepRelaxation` shortens the step only
where melt is high, so the average is well under 48 when the warp is off; the ray is bounded by an analytic
slab between the pool floor and `max h = 3A`, which is known analytically, so a ray that misses the forest
exits in a handful of steps; and the empty space above the known maximum height is skipped in one jump.
Realistic average 20-25 steps. At 0.5x on 3.24 Mpx that is 810k pixels x 25 x 48 = 970M ALU and 20M fetches
per frame, about 1.0 Gtex/s at 50 fps. `MeltField` itself is 20+N passes on the 96 grid plus N+1 on 256,
negligible by comparison. Total about 25 offscreen passes + 1 half-res heavy march + 1 quarter-rate env
probe + 1 bilateral upsample. **Low:** 24 steps, `ResTarget` 0.3x, no melt warp, film off, probe captured
every 8th frame. `cymSpikeDetail` is the explicit escape hatch.

**Risks.** Tuning the hysteresis into something that reads as intentional rather than as a bug: too narrow
and it looks like a normal threshold, too wide and the forest never comes down. The test pins the loop
shape but **not the musicality**, and the failure mode - a forest that stays up through a whole quiet
section, or never erupts - is a visualiser that has stopped answering the music, which is the worst thing
this app can do. So there is a **soak test over a few real tracks asserting the forest crosses both branches
some minimum number of times per minute**, and `cymSpikeHysteresis`'s default is treated as a tuning result
rather than a chosen number. A mirror BRDF reflecting nothing is black, which is why the environment probe
is required rather than optional. `MeltField.dyeCeiling` is 1.0 because `HyperspaceScene` reads dye as
radiance with no grading after; here the dye is a stain multiplier, so the ceiling is fine as-is and must
not be raised.

**Distinct.** Not `hyperspace`, the nearest neighbour and the other `MeltField` consumer: that marches a
room of discrete SDF fractal bodies through a five-act journey, each on its own lifecycle clock, with
`MarchBudget.forDetail` driving iteration counts on Mandelbulb and Mandelbox estimators. This marches a
single continuous height field of three cosines - no bodies, no journey, no acts, no fractals, no SDF, no
iteration count. Not `lava` or `metaballs`: fullscreen 2D. It is the only style in either family with a
**bistable** state, the only one where the picture depends on the history of the drive rather than its
instantaneous value, the only one rendered as a mirror, and the only one whose lattice constant comes from a
capillary balance `sqrt(sigma/(rho g))` rather than from a modal eigenvalue. Against `faraday`, with which
it shares the Landau integrator: a mirror-black spike forest with memory, versus a flat wide tray with a
shallow lattice and no memory.

### Tube - `kundt_tube` (promoted in place of the cut `mode_moire`)

**Phenomenon: Kundt's tube - a 1D standing pressure wave in a closed cylinder, with powder piling at the
displacement nodes and Rayleigh streaming vortices circulating between the piles.**

```
Closed-closed tube of length L:
   u(x, t) = U sin(k x) sin(omega t),     k = n pi / L,     f_n = n c / (2 L)
   displacement nodes (where powder piles) at x = m L / n,   spacing d = lambda/2
   so  c = 2 f d   - the classroom measurement, run backwards to place the piles

Rayleigh acoustic streaming, slip velocity at the edge of the Stokes layer:
   u_L(x) = -(3 / (4 omega)) * U(x) * dU/dx  =  -(3 U0^2 / (8 c)) * sin(2 k x)
```

The `sin(2kx)` gives **four vortex cells per wavelength**, i.e. cells of length `lambda/4`, so between every
pair of piles there is a counter-rotating pair - which is what makes the mist between the piles read as
structure rather than haze.

**Look.** A long horizontal glass tube runs across the frame in perspective, a driver membrane at the near
end and a movable piston at the far end, both visible. Inside, a bed of fine powder lies along the bottom.
When the tube is on resonance the bed reorganises: powder is swept out of the antinode regions and heaps
into evenly spaced transverse ridges at the displacement nodes, with the classic fine striations combed
across each ridge by the streaming cells. Between the ridges a faint mist rolls in toroidal cells you can
follow. Light rakes in from one side so the ridges cast shadows down the tube and you can count them at a
glance. The moment that sells it: **the piston moves.** As the tube lengthens or the pitch climbs, the mode
number steps by one, and the whole bed has to reorganise - piles migrate, two merge, a new one nucleates -
over about a second, with the powder visibly travelling rather than fading.

**Morph.** The mode number `n` is an integer and the pile count is `n`, so the morph is a discrete
reorganisation forced by an integer constraint. Two independent drivers make it continuous to look at:
pitch sets which `n` is resonant at the current `L`, and `L` itself (the piston, from macro energy) slides
continuously, so the resonance condition `f_n = n c / (2L)` is crossed by moving either quantity. Between
steps the piles do not sit still - the resonance is imperfect off-peak, the standing wave has a travelling
component, and the ridges drift toward their new positions. Second axis: drive amplitude, which sets how
sharp the ridges are (below a threshold the powder does not move at all - the same `active` gate as
`chladni_sand`). Third: the streaming strength, which combs the striations across each ridge.

**Fluid coupling.** `FlowField` for the streaming plus `FluidParticles` for the mist, and `GpuGrains` for
the powder bed. Forward: `u_L(x)` above is evaluated at 16 points along the axis and injected as
`queueKick`, with alternating sign per `lambda/4` cell, so the toroidal circulation is a real
divergence-free field rather than a drawn spiral. Reverse leg one: `FluidParticles` advects the mist on
that field, and `setChoreography` catch points sit at the node planes. Reverse leg two: the powder grains
read the field and take a drag term, so the streaming combs the striations - the fine transverse structure
in a real Kundt's tube. Reverse leg three, the CPU leg: the accumulated pile height is reduced to 8 samples
and fed back as a local damping term on the driver, so a heavily loaded tube rings less and the resonance
detunes slightly as the powder collects - the same mass-loading idea as `harmonic_shell`, at a fraction of
the cost.

**Grain transport.** The same Euler-Maruyama integrator as `chladni_sand`, restricted to the axis, with the
gate on both terms:

```
active   = step( uThrow * |u(x)| - 1 )
X_{k+1}  = X_k  -  active * kappa * d(|u|^2)/dx * dt
                +  active * sqrt( 2 D0 |u(X_k)|^2 dt ) * N(0,1)
                +  streamingDrag(X_k)
```

Multiplicative noise again, for the same reason: it vanishes at the nodes, which is what makes a node a
stochastically stable resting place rather than somewhere a grain diffuses away from.

**Why this style and not the one it replaces.** `mode_moire` was cut for being the same style as
`hyper_moire`, down to the name (§ What we are not doing). Its replacement had to be cheap, distinct, real
3D, and physics the app does not have. Kundt is all four and it costs almost nothing new: the grain
integrator, the field-texture trick and the point-cloud budget all come from `chladni_sand`, the streaming
and mist come from `harmonograph`, and the only genuinely new code is the 1D field, the piston geometry and
the tube.

**Audio.** `dominantPitchHz` -> the drive frequency, hence which `n` is resonant. macroEnergy -> `L`, the
piston position. rms -> drive amplitude `U0`, hence `uThrow` and ridge sharpness. treble -> streaming
strength, hence how hard the striations are combed. bass -> tube diameter, changing the apparent scale.
beatImpulse -> a drive spike that throws the whole bed into the air briefly. onset -> a small piston step.
progress -> a slow camera dolly along the tube.

**Params.** `cymTubeLength` 0.3..3 / 1 (piston position `L`); `cymTubeDrive` 0..3 / 1.2 (`U0`);
`cymTubeGrains` 0..2 / 1 (16k / 65k / 160k - a performance setting, `NEVER_ROLLED`); `cymTubeDrift`
0..2 / 1 (`kappa`); `cymTubeStreaming` 0..2 / 0.9 (Rayleigh streaming strength);
`cymTubeMist` 0..1.5 / 0.6; `cymTubeLoading` 0..1 / 0.3 (how much accumulated powder detunes the driver);
`cymTubeGlass` 0..1 / 0.6 (wall refraction and rim prominence).

**Files.** `render/cymatic/KundtTubeScene.kt`; `render/cymatic/KundtMath.kt` (pure: `f_n`, node positions,
the `sin(2kx)` streaming profile, the mode-selection hysteresis, and the mass-loading reduction);
`res/raw/cym_tube_field_frag.glsl` (the 1D `|u|` and `d|u|^2/dx` field, a 256x1 texture);
`res/raw/cym_tube_grain_update_frag.glsl`; `res/raw/cym_tube_grain_vert.glsl`, `cym_tube_grain_frag.glsl`;
`res/raw/cym_tube_glass_frag.glsl` (the cylinder wall, refracting the far side of the bed);
`app/src/test/java/dev/musicviz/KundtMathTest.kt` (pile spacing is exactly `lambda/2` and `c = 2 f d`
recovers the wave speed; the streaming profile has exactly four sign changes per wavelength and zero net
axial momentum; the gate is non-dimensional and gates both terms; node occupancy after 600 steps, the same
multiplicative-noise regression as `chladni_sand`; the mode-selection hysteresis prevents chatter at an
`n`-boundary).

**Budget.** The second-cheapest style in the family after `harmonograph`, deliberately. The field is a
256x1 texture - one tiny pass. Grain update: one MRT pass at 256x256 (65k) with two field fetches. Grain
draw: 65k additive `GL_POINTS` at 2-3 px, confined to a narrow band of the frame, so the overdraw is a
fraction of `chladni_sand`'s. Tube and piston are two small geometry draws with one refraction fetch.
`FlowField` 18+N passes on the 64 grid with 16 kicks; `FluidParticles` 65k for the mist - and that is the
**second** point cloud in this style, which is at the family limit, so both are small and neither is
fullscreen. Total about 21 offscreen passes + 3 geometry draws + 2 small point clouds, at native resolution
with `DepthStage` attached. **Low:** 16k grains, mist off, glass refraction off.

**Risks.** Two point clouds in one style is at the stated limit; if it measures badly on a bandwidth-limited
Mali the mist goes first, because the powder is the subject. A long thin object in perspective wastes most
of the frame; the camera framing is close and low so the tube fills it, and the walls are drawn so the empty
space reads as glass rather than background. Mode chatter at an `n`-boundary is handled by the same 0.25 s
hysteresis as `levitator`.

**Distinct.** It is the only 1D standing wave in either family, the only sealed vessel, and the only style
where the geometry's own **length** is a played parameter. Against `levitator`, which shares the linear
`lambda/2 = c/(2f)` law: levitator holds discrete rigid bodies in a 3D potential across a variable gap and
its jumps are droplets snapping between traps; Tube transports a continuous powder bed along a fixed axis
and its jumps are ridges merging and nucleating, with visible streaming vortices between them. Against
`chladni_sand`, which shares the grain integrator: a square plate with a 2D Cartesian nodal lattice and a
262k bed, versus a cylinder with `n` transverse ridges and a 65k bed inside glass. Nothing in the app has a
sealed transparent vessel with matter in it.

## Build order

Sizes are rough and relative: S is a few days, M is a week or two, L is longer. "Done" names the tests and
the preview-harness assertions that have to pass, because in this repo those are the definition.

### Phase 0 - the foundation (L)

Nothing else can start. Land in this order, because each is a dependency of the next:

1. `ResTarget` + `DepthStage`. **Both, before any style**, because six styles' budgets depend on the first
   and eleven on the second, and the first draft of this plan omitted `ResTarget` entirely.
2. `SpaceCamera`, `SpaceMesh`, `QualityLadder`.
3. `HyperSceneBase` + `HyperFluid` + `HyperFluidMath` (with `MeltMath` delegating), `BodyBank`,
   `HyperStyle`.
4. `CymaticSceneBase` + `ModalBank` + `CymaticChroma` + `PitchClock` + `JustIntonation`.
5. The eight GLSL libraries, registered in `GlUtil.INCLUDES`.
6. `VolumeAtlas` and `GpuGrains` - deferred until their first consumers, but designed now.
7. The two shipping-code changes with two consumers each: `FluidSim.pressureTex` (one line), and the
   9-point isotropic Laplacian + circular mask in `ripple_update_frag.glsl`, gated behind a uniform.
8. `tools/shaderpreview/lib/space-drivers.mjs` scaffolding and the driver contract.

**Done:** `SpaceFoundationTest` passes - `DepthStage` invalidates before unbind and restores blend/depth
state exactly as found; `ResTarget` scale derives from `supersampleFactor`; `VolumeAtlas` gutters sample
equal on both sides of every slice boundary; `SpaceCamera`'s basis is orthonormal to 1e-6 and its clocks
wrap continuously at `TIME_WRAP_SEC`. `HyperspaceMeltTest` and `HyperspaceMathTest` still pass unchanged
after the `MeltMath` delegation - that is the regression that proves the generalisation did not move a
number. `MeltField` still renders identically: the harness's `--no-melt` and baseline frames for the
existing hyperspace style are byte-comparable within the documented luma tolerance.

### Phase 1 - the two cheap proofs (S each)

**`harmonograph`** first. It needs the least foundation of anything - camera and stage only, no modal bank,
no fluid grid - so it proves the depth contract cheaply, and it is the guaranteed-50 fallback the family
needs in place before anything expensive lands. **Done:** `HarmonographMathTest`; the harness renders it at
three clock offsets with `deltaMeanLuma` bounded (no wrap discontinuity, because there is no wrap); a
device run holds 50 fps at native resolution with dust on.

**`hyper_caduceus`** second. First `DepthStage` user in the hyperspace family, first `readbackGrid` user,
and by far the cheapest geometry style, so the depth-attachment path, the state restore and the
`glReadPixels` stall are all debugged on a scene whose frame time is 2 ms. **Done:** `AttractorPathTest`,
including the anti-snap assertion; the harness confirms the two-pass opaque/additive split produces no
order-dependent flicker across 200 frames of self-intersecting geometry; `formats.rgba32 == null` degrades
to an unbent attractor rather than failing.

### Phase 2 - the two theses (M each)

**`chladni_sand`.** The cymatics family's thesis, and building it forces `ModalBank`, `CymaticChroma`,
`GpuGrains` and the field-texture pattern into existence; everything after it is reuse. **Done:**
`PlateResponseTest` including the 600-step node-occupancy assertion (the multiplicative-noise regression),
the exciter-node zero, and CPU/GPU field parity; the harness's three-way uniform audit passes; grain count
clamps rather than reseeding on a simulated auto-downgrade.

**`hyper_polytope`.** Shaped like the raymarcher that already works, so it proves `HyperSceneBase`,
`HyperFluid` and the three shared GLSL libraries against a known-good pattern. **Done:**
`PolytopeMathTest` - mirror normals unit to 1e-6, `R^T R = I` to 1e-5 at every angle pair **including the
two flow-derived angles**, chamber-centroid fold idempotent inside the chamber; and, before the 15-mirror
mode is offered at all, a **measured on-device compile time and register-allocation report** on an Adreno
6xx and a Mali-G7x. The harness cannot see this and must not be trusted for it.

### Phase 3 - the mesh and instancing styles (M each)

`hyper_cortex` (second `RippleSim` consumer; the analytic vertex-density clamp is the thing to verify
compiles and works), `hyper_reliquary` (first instanced geometry; `LobeMathTest`'s NaN sweep is the gate),
`bessel_drum` (polar mesh, baked Bessel tables, and the 9-point Laplacian's first real consumer),
`harmonic_shell` (first non-planar mesh, first shadow pass, and the metric-weighting test).

**Done, all four:** their math tests; the harness renders each at three camera orientations with no
`glError` and no skipped uniforms; `PARAM_MATRIX.md` regenerates clean; `CustomizeSurfaceTest`,
`PresetRoundtripTest` and `ParamRandomizerTabScopeTest` pass. `bessel_drum` additionally: `WaterScene`'s
own tests still pass with the Laplacian uniform defaulted off, which is what proves the gate works.

### Phase 4 - the cheap remainder and the second solvers (S-M each)

`kundt_tube` (reuses phase 2's grains and phase 1's streaming; almost no new engine),
`faraday` (the Landau integrator, small; lands `lib_film.glsl` and `LandauAmplitudes.kt`),
`hyper_moire` (one fragment shader, two compiled variants; pins the `fwidth` anti-aliasing discipline the
harness polices from here on), `standing_chamber` (quads and a sort, not a march - the cheapest of the
"3D volume" styles because it is not one), `levitator` (first `gl_FragDepth` writer; measure the early-Z
loss before raising the droplet cap).

**Done:** as phase 3, plus - for `faraday` - the 3 Hz inversion clamp and the mean-luminance assertion pass
across the whole parameter range, and the scene's exposed inversion rate is visible to `VisualSafety`.

### Phase 5 - the expensive ones, against measured budgets (L each)

By now the quality ladder has been tuned against real device numbers rather than arithmetic, which is the
point of building these last.

`hyper_foam` (the pressure getter's only consumer; the zero-net-volume and mean-subtraction tests are the
gate), `hyper_dustskin` (largest GPGPU piece; RGBA32F is a hard requirement and the honest-unavailable path
must be exercised on a device that lacks it), `caustic_sheet` (photon splat; the flipped consonance oracle
is the correctness gate), `hyper_plume` (first `VolumeAtlas` consumer; per-sample self-shadowing measured in
fetches on device, not derived), `hyper_vivarium` (second atlas consumer, reusing plume's marcher; the
gutter test and the `(f,k)` arc clamp are the gates), `rosensweig` (most expensive, only `MeltField`
consumer, and the hysteresis is the hardest thing in either family to tune into something that reads as
intentional).

**Done:** all of the above, plus a **soak test over a set of real tracks** for `rosensweig` asserting the
spike forest crosses both branches of the hysteresis loop a minimum number of times per minute - because the
loop-shape test pins the maths and not the musicality, and a forest that stays up through a whole quiet
section is a visualiser that has stopped answering the music.

### Cross-cutting, every phase

`RendererWiringTest` gates two of the three registration sites; the export factory is checked by hand
against the list, every time, because nothing checks it and a miss falls through to `NebulaScene`. Every
style gets a preview-harness driver before it gets a device build, and the harness's refusal to render on a
uniform-audit disagreement is treated as the feature it is.

## What we are not doing

Three concepts were cut outright, two were rebuilt on different techniques, and several individual
mechanisms were deleted. This section is how the rest of the document earns trust.

**`mode_moire` - cut, and its ideas redistributed.** Two transparent Chladni sheets in a smoke slab, whose
interference envelope was the subject. It was the same style as `hyper_moire`, down to the name: sheets at
real world-space depths, the interference rather than any sheet as the subject, true parallax, per-sheet
thin-film colour, fluid between them. Two entries called Moire in one release is not a near-miss, it is the
same product shipped twice - and it existed because the two families were designed without reading each
other. Beyond the duplication it was also the weakest member of its own family: its teaching point (just
versus tempered, figure locks or precesses) was `harmonograph`'s thesis and `harmonograph` carries it
better, a closed rosette freezing dead being far more legible than a fringe pattern rotating slowly; its
own build order described it as "two instances of the plate in one scene, so almost no new maths"; the
octave-invariance of chroma made its worked example (an octave giving a `sqrt(2)` wavenumber ratio) literally
unreachable, since an octave is the same chroma bin; and its cost was a full FLUID Medium budget - `FluidSim`
plus a slab march plus two 51k-triangle meshes - for a payoff of "two gratings beat". Redistributed: the
two-sheet parallax moire becomes `cymPlateSheets: 1..2` on `chladni_sand`, driven from a mode-index offset
whose envelope wavenumber `pi/L` is legible and exact, at the cost of one extra mesh draw and no new files;
and the interval-to-beat-wavelength idea moves to `hyper_moire`, which needed exactly that to justify
itself. Its depth-ordered transparency work was going to validate the depth target, and
`harmonic_shell`'s shadow pass and `levitator`'s `gl_FragDepth` cover the same ground.

**The Apollonian space-filling packing in `hyper_foam` - cut, concept replaced.** Both of its acceleration
structures were mismatched to its own field, and the design said so without noticing. Cone marching skips
empty space, and the look description said the packing filled space "all the way down to the pixel". Enhanced
sphere tracing costs a second DE evaluation and correctness on thin features, and an Apollonian gasket is a
dense set of near-tangencies where the fallback fires constantly and the 0.03 shell was exactly what
over-relaxation punches through. Its own text conceded the fallback rate "is the one number this design
cannot predict from first principles", which is an admission that the whole performance case was
unevidenced. Two further defects sealed it: the R16F cone-depth buffer quantised to about 0.04 world units
at `t = 40`, larger than the shell it must not overshoot; and reading a Neumann-BC Jacobi pressure field
with a free DC constant would have drifted every radius as the DC mode wandered. **What ships instead** is a
bounded 12-sphere nest with one inversion level, which keeps the pressure coupling (the genuinely novel
idea), keeps the nesting and the iridescent shells, and has a predictable step count and a march that
terminates. Cone marching and enhanced sphere tracing appear nowhere in this plan.

**Slice-stack volume rendering in `hyper_vivarium` - cut, renderer replaced.** Back-to-front `SRC_ALPHA`
blending forbids a per-pixel early-out and the design's own empty-texel path wrote background rather than
calling `discard`, so all 48 quads ran their complete fragment shader over their complete area, always,
worst case, every frame - up to 48x overdraw with no relief mechanism of any kind. The per-fragment cost was
also 14 fetches rather than the claimed three, once the hand-rolled trilinear and the 3D central-difference
normal were counted, which at half resolution is about 174M fetches per frame with zero possible early-out.
There was an unaddressed correctness bug as well: bilinear fetches into a slice atlas bleed across tile
borders. **The chemistry survives intact** - it is genuinely cheap and it is the style's identity - and the
renderer becomes a front-to-back Beer-Lambert march sharing `hyper_plume`'s. The honest consequence is
stated in that style's entry: vivarium is now the second volumetric marcher and its distinctness rests
entirely on the density field having chemical state and its own growth law.

**The volumetric march in `standing_chamber` - cut, and a physics error with it.** The design superposed up
to 6 cavity modes at different frequencies, but the time-averaged radiation potential of an incoherent
superposition is `sum |psi_i|^2`, strictly positive almost everywhere: **superposition destroys the nodal
surfaces** rather than curving them into interesting ones. Separately it budgeted 96 GFLOP/s of ALU and
never mentioned its 40M dependent texture fetches per frame, about 2.4 Gtex/s, above a Mali-G57 MC2's peak;
it required both an incrementally-carried cosine phase along the ray and a velocity domain warp, which are
mutually exclusive; at its default band width it took two samples per membrane, which aliases; and its
medium coupling was the thinnest of the twenty - a 2D dye read through two orthogonal projections
multiplied together, with the design conceding the resulting ghost density. What ships is one coherent mode
whose nodal set is drawn as the union of planes it actually is, about an order of magnitude cheaper, with
the three-integer chord morph intact and the projection trick gone entirely.

**Hexagons from single-frequency Faraday forcing - deleted as physics.** Pure subharmonic response has the
symmetry `A -> -A`, which forbids the quadratic term, and a cubic-only amplitude system with
equal-magnitude wavevectors selects stripes or squares and never hexagons. The design's own proposed test
would have failed against the physics. Two-frequency forcing is added, which is how real experiments get
hexagons and quasipatterns and is a better music mapping anyway.

**Torsional shell frequencies for a radially-displaced shell - deleted.** `Omega_l = sqrt((l-1)(l+2))` is
the tangential-displacement spectrum; the render was a radial displacement. The modes being drawn were
invisible in the spectrum being used. The giveaway is `Omega_1 = 0`, which is rigid rotation, not breathing.

**Just-versus-tempered detune as a measured quantity - deleted from five designs.** The just fifth differs
from the tempered fifth by 1.96 cents and the major third by 13.7 cents; a 2048-point FFT at 48 kHz has a
bin width of about 180 cents at A2, and band energies are coarser still. `CymaticChroma.justDetune` would
have reported bin quantisation noise modulated by vibrato, and four styles built their second morph axis on
it. Chroma is also octave-invariant, so a two-bin interval is ambiguous and an octave is unrepresentable.
The motif survives as a three-way user toggle with no claim of measurement. See § Open questions for
whether to earn it back with a real pitch tracker.

**A second fluid solver in `hyper_cortex` - deleted.** `RippleSim` was the medium and the coupling was
genuine; a full `HyperFluid` was added on top solely to warp the cortical coordinates, justified in the
design as "for continuity with the family". That is a Navier-Stokes solve per frame to satisfy a
convention, and it diluted the style's strongest distinctness claim as well as its frame time.

**`hxMoireOrnament = 2` ("mixed per layer") - deleted.** A runtime branch selecting between two ornament
bodies inside a loop the compiler wants to unroll is the classic Adreno register-spill cliff. The ornament
is a compile-time program variant, using the pattern `FluidLook` already has.

**The 1M-particle tiers in `hyper_dustskin` and `chladni_sand` - deleted.** At 6 px points, 1M additive
points is 36M fragments per frame plus 64 MB of ping-pong state, roughly 2 GB/s of state traffic alone on a
part with about 25 GB/s total. Both ladders cap at 256k / 262k, and the family rule is one large point
cloud per style.

**`hyper_plume`'s dual-projection pseudo-3D density - deleted.** `dyeXY(p.xy) * dyeZY(p.z, p.y)` is a
product of two views of the same 2D texture; the tell that it was not 3D was a camera constraint pinning the
orbit to +/-25 degrees, which existed only because the illusion breaks outside that cone. A style that
cannot be looked at from the side is a 2D style with a parallax budget.

**The existing cymatics dish branch - retired when `bessel_drum` ships.** It ranks dish modes by the
square-plate metric `sqrt(n^2+m^2)`, which is the wrong quantity for a membrane, and shipping it alongside
`bessel_drum` would be the clearest duplicate in the release and entirely self-inflicted. The plate branch
is retired when `chladni_sand` ships, on the same grounds. Whether `SceneIds.CYMATICS` is removed or
collapsed is an open question below.

**Also considered and not built:** Kleinian limit sets and the Maskit slice (a third
inversion-based marcher after polytope and foam, and the limit-set topology change is a morph polytope
already has); marching cubes or dual contouring on the GPU (an existence
proof exists in ARM's MIT SDK at 32-cubed, but meshing buys nothing here - there is no physics, no collision
and no CPU-side consumer, and the shape is the opposite of static); SPH or position-based fluids (neighbour
search plus a GLES 3.1 compute gate plus bandwidth, for a look a non-physical particle system advected by
the existing Eulerian field reproduces at about 1 percent of the cost); screen-space fluid rendering with
the narrow-range filter (only worth it if you have already committed to particles, and the best reference
implementation has no licence file); the Rubens tube (the direction of the flame-height response is disputed
in the sources, `sqrt` being concave, so a physically honest version contradicts most demonstration videos);
and a full anisotropic Kuwahara post-pass (42 ms at 512x512 on a GTX 580 - not a mobile real-time effect,
though its structure tensor would be a nice free flow field if something else were already computing one).

**And a scoping disagreement worth recording.** One reviewer's recommendation was four cymatics styles, not
ten, holding the rest until the first four had measured per-device budgets; another's was that with foam and
vivarium cut the hyperspace set should be eight. Both are defensible and neither is what this document
delivers, because the brief asked for ten and ten. The build order is arranged so that stopping after phase
3 leaves a coherent, shippable subset - eight styles across both families, all cheap or mid-priced, with the
whole foundation paid for - and phase 5 is where the plan should be re-argued against measurements rather
than against arithmetic.

## Open questions for the user

1. **Ten and ten, or fewer and better?** Twenty styles on top of thirty-seven is a 54 percent catalogue
   increase, and the marginal value of style 57 is low. The ones that unambiguously earn their place are
   those with a coupling or a behaviour no other style can have: foam (pressure), vivarium (chemistry),
   rosensweig (hysteresis), chladni_sand (matter that migrates), levitator (rigid bodies in a solved
   potential), harmonic_shell (two orthogonal pitch axes), membrane (topology), dustskin (constrained
   particles), caduceus (the fluid decides the trajectory), caustic (the full chromagram). That is ten.
   The other ten are good but they are the middle of the distribution. Do you want twenty, or do you want
   the ten above plus more polish per style?

2. **Do we earn the tuning motif back?** Five designs originally hung a morph axis on just-versus-tempered
   detune, which band-energy chroma cannot measure. A YIN or autocorrelation pitch tracker over the existing
   time-domain ring buffer (the one `BeamScene` already reads) gives sub-cent precision on sustained
   monophonic tones and would make "play this chord in JI and in 12-TET, watch one hold still and the other
   spin" a real, teachable feature rather than a user toggle. It is a meaningful piece of DSP work and it
   only works on monophonic passages. Worth it, or is the toggle enough?

3. **What happens to `SceneIds.CYMATICS`?** `chladni_sand` supersedes its plate branch and `bessel_drum`
   supersedes its dish branch, both on correctness grounds. Retiring the id breaks any saved preset that
   names it; keeping it ships two styles that look like the new ones but are ranked by the wrong metric.
   Options: retire it, collapse it to plate-only as a "classic" entry, or keep it and accept the duplicate.

4. **Is the 3 Hz flash ceiling right, and should `VisualSafety` own it?** Three styles produce a full-field
   luminance event that the existing safety layer is structurally blind to, because it is a geometric
   inversion rather than a brightness ramp. The plan clamps at 3 Hz in `PitchClock` and has the scenes
   report their inversion rate upward. That is more conservative than the current global limiter. Should
   `VisualSafety` grow a "geometric inversion" input, and should the ceiling be a user setting with a floor?

5. **Which two devices are the budget?** Every number in this document is arithmetic against a 300-600
   GFLOP/s sustained part with 1-2 Gtex/s of bilinear throughput, at the `PerformanceMonitor`'s 50 fps
   target and the `supersampleFactor`-inflated resolution. Three specific things cannot be measured any
   other way and are named as device gates in the build order: on-device shader compile time and register
   allocation for `hyper_polytope`'s 15-mirror mode and `hyper_moire`'s 4-layer fold; the early-Z loss from
   `levitator`'s `gl_FragDepth` write; and the real fetch cost of `hyper_plume`'s per-sample light march.
   Naming the two target devices now decides several defaults.

6. **`hyper_dustskin` without RGBA32F: off, or degraded?** The plan says off, with an honest message,
   because the Newton convergence gate cannot be met in fp16 and non-converging dust boils - which destroys
   the one thing the style is. A style that is 60 percent right is worse than one that is honestly absent.
   If you would rather it degraded, the fallback is a free (unprojected) cloud, which is a different style
   wearing this one's name.

7. **How aggressively should the quality ladder be allowed to drop?** `FluidQuality.effectiveIndex` only
   ever lowers and never recovers within a session, so a style that needs its top tier to read correctly
   will spend most of a session looking wrong. Two of these styles have a lowest tier that is a real loss
   rather than a soft one (`hyper_foam` loses its nesting, `harmonic_shell` loses countable meridians).
   Should the auto-downgrade be allowed to recover after a sustained period above target, or should those
   styles refuse their bottom tier and drop frames instead?
