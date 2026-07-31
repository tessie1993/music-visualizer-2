# Adding Organic, Trippy, Music-Reactive Motion to *musicviz*: An Implementation Report

> **STATUS — this is a 2026-07 research report, not a plan. Read this first.**
>
> The port it recommends **has been built and shipped.** `render/fluid/` contains
> FluidSim, FluidBuffers, FluidLook, FluidParticles, FluidQuality, FluidChoreography,
> CurlFlowScene, WaterScene, RippleSim and more (18 files), driving 21 `fluid_*.glsl`
> shaders. Curl-noise flow fields, feedback trails, the fluid sim and the water/ripple
> scenes from the roadmap below are all live styles in the app today.
>
> Treat the sections below as **design rationale and GPU/GLES reference**, which is
> why four source files cite it by section number. Do NOT treat any roadmap item as
> outstanding work without checking `render/fluid/` first. Items 4-5 of the roadmap
> (Physarum, reaction-diffusion, raymarched fractals) are the only ones still unbuilt.
>
> Section numbering in A and D is frozen: `trail_warp_frag.glsl`, `curl_field_frag.glsl`,
> `FluidChoreography.kt` and `SceneParams.kt` cite it by ordinal. Annotate in place;
> never renumber.

## TL;DR
- **Build a GPU Navier-Stokes fluid sim as your flagship new scene.** Pavel Dobryakov's WebGL-Fluid-Simulation (MIT license, 16.4k+ stars, 1.9k forks, tagline "Play with fluids in your browser (works even on mobile)") is the gold-standard "liquid/gel/smoke" look; its architecture (ping-pong double FBOs, low-res sim grid + high-res dye, curl/vorticity, Jacobi pressure solve, Gaussian splats) maps almost 1:1 onto your existing FBO→composite pipeline and your FFT/beat infrastructure — drive splats from bass onsets, splat force from beat-phase, and dye color from an IQ cosine palette.
- **The "organic" quality is not one trick but a stack of properties:** divergence-free/curl-noise velocity fields (no clumping), motion continuity/inertia (never teleport), trails and feedback persistence, soft additive blending + bloom, limited HSV/cosine palettes with slow hue cycling, and beat-synced *impulse + slow decay* envelopes. You already own kaleidoscope/warp/bloom in the composite pass, so invest in the **motion/simulation layer**, not more post-FX.
- **Prioritized roadmap:** (1) curl-noise flow-field GPU particles (quick win, reuses your particle pipeline); (2) feedback-buffer trails (tiny change, huge payoff); (3) the fluid sim (flagship); (4) Physarum slime and reaction-diffusion as texture-based "living" scenes; (5) raymarched fractal scenes last (heaviest). Wire every one to spawn/respawn and pulse on music.

## Key Findings

**1. The fluid sim is portable and is the single highest-value addition.** It is MIT-licensed and was explicitly designed to run on mobile GPUs (the repo tagline itself is "works even on mobile," and Pavel ships native iOS and Android apps of the sim). Its pipeline is a textbook Jos Stam "Stable Fluids" GPU solver: per frame it runs curl → vorticity confinement → divergence → Jacobi pressure iterations → gradient subtraction → semi-Lagrangian advection of velocity then dye. [deepwiki](https://deepwiki.com/PavelDoGreat/WebGL-Fluid-Simulation) All state lives in double-buffered FBOs (velocity RG, dye RGBA, pressure R, plus single-buffer curl/divergence). The simulation grid runs at low resolution (default 128) while the dye/color buffer runs high (default 1024) — this split is exactly why it looks crisp but runs cheap.

**2. GLES 3.0 float-target caveat is the main porting gotcha.** RG16F/RGBA16F are texture-filterable but **not color-renderable** in core GLES 3.0 — you must query `EXT_color_buffer_float` (or fall back to `EXT_color_buffer_half_float`). Crucially, **linear filtering of half-float textures is not core** either; it needs `OES_texture_half_float_linear`. Pavel's code already solves this: it probes support and, when linear filtering is unavailable, falls back to `GL_NEAREST` and swaps in a manual-bilinear advection shader. Your port must replicate that probe-and-fallback (create a 4×4 test texture, attach to FBO, check `FRAMEBUFFER_COMPLETE`).

**3. "Organic/trippy" has concrete, sourced properties** (see distilled list below). The recurring themes across IQ's writing, The Book of Shaders, MilkDrop's design, and creative-coding communities are: continuous noise-driven velocity (not linear paths), divergence-free flow so particles neither clump nor scatter, feedback/persistence trails, additive blending with glow/bloom, cosine/HSV palettes, and audio envelopes with fast attack + slow decay.

**4. Your app already has the hard audio parts.** FFT bands, BPM, beat-phase, and attack/decay envelopes are exactly the signals these techniques want. The work is *mapping*, not new DSP.

## Details

### A. Properties of good trippy / organic / liquid / gel / smoke visualizers (distilled, sourced)

These are the levers that make motion "read" as organic. Backed by Inigo Quilez's articles, The Book of Shaders, MilkDrop's authoring guide, and creative-coding/VJ community writeups:

1. **Divergence-free (curl) velocity fields.** Taking the curl of a noise potential yields a field with zero divergence, so particles "flow in swirling, incompressible streams" and never converge to a point [Kerkstra](https://www.kerkstra.dev/lab/flow-field) or clump — the defining property of fluid-like particle motion. The canonical source is Robert Bridson, Jim Houriham, and Marcus Nordenstam, "Curl-Noise for Procedural Fluid Flow," *ACM Transactions on Graphics* (SIGGRAPH 2007) 26(3):46:1–46:3, whose abstract describes "an extremely simple approach to efficiently generating turbulent velocity fields based on Perlin noise, with a formula that is exactly incompressible."
2. **Motion continuity and inertia.** Nothing teleports. Positions and velocities evolve smoothly frame-to-frame; use springs/easing rather than snapping. Easing gives "natural, organic movement" because constant linear speed "doesn't occur in nature." [adobe](https://www.adobe.com/uk/creativecloud/animation/discover/easing.html)
3. **Noise-driven, not parametric, paths.** Evaluate 3D Perlin/simplex noise at (x, y, time) so the field itself morphs slowly; frequency, octave count, and time-scale decide whether it looks like "slow smoke, river currents, or violent turbulence." [Kerkstra](https://www.kerkstra.dev/lab/flow-field)
4. **Domain warping.** Feed noise through itself — `f(p + f(p + f(p)))` — to get the swirly, marbled look. Per Inigo Quilez's "domain warping" article, computing `f(p + h(p))` "will produce some abstract but beautiful images with a pretty organic quality to them." This is the core of the liquid-marble look.
5. **Feedback / persistence trails.** Re-sample the previous frame slightly warped/zoomed/faded — the classic MilkDrop "warp shader has a memory" trick that "creates a decay or smear effect." [Winamp & Shoutcast Forums](https://forums.winamp.com/forum/visualizations/milkdrop/309946-milkdrop2-beat-detection-auto-adjusting) Produces the endless-tunnel/liquid-echo feel.
6. **Soft additive blending + bloom/glow.** Additive blending makes overlapping particles "emit light"; [Learn OpenGL ES](https://www.learnopengles.com/tag/additive-blending/) glow via `intensity / (distance + epsilon)` and bloom on bright pass. [Lumitree](https://lumitree.art/blog/shader-art) This is what makes gel/smoke feel luminous rather than flat.
7. **Limited palettes with slow hue cycling.** Use IQ's cosine palette. Verbatim from his "Simple procedural color palette" article: `color(t) = a + b · cos[2π(c·t+d)]`, GLSL `return a + b*cos( 6.283185*(c*t+d) );` — "as t runs from 0 to 1 … the cosine oscilates c times with a phase of d." Staggering the per-channel phase `d` ≈ 120° apart (e.g. `d = vec3(0.0, 0.33, 0.67)`) yields rainbows; restrained palettes read as designed, not noisy.
8. **Symmetry / kaleidoscope / feedback zoom.** Mirroring and radial symmetry plus slow feedback zoom is the signature psychedelic amplifier (you already have this in the composite pass).
9. **Beat-synced impulse + slow decay.** Fast attack on onset, slow exponential decay — MilkDrop's whole aesthetic is "beat detection to trigger… effects" [Things and Stuff Wiki](https://wiki.thingsandstuff.org/Visuals) that then ease back. Raw energy alone looks jittery; envelopes make it breathe.
10. **Vorticity/turbulence detail preservation.** Real fluids keep small swirls; numerically these get damped, so vorticity confinement is deliberately added back to keep the curls alive. [deepwiki](https://deepwiki.com/PavelDoGreat/WebGL-Fluid-Simulation/3.1-fluid-dynamics-theory)

### B. Technique-by-technique breakdown (repos, references, licenses)

**B1. GPU fluid simulation (Navier-Stokes / Stable Fluids).**
- Reference: **PavelDoGreat/WebGL-Fluid-Simulation — MIT** (16.4k+ stars, 1.9k forks). Based on GPU Gems Ch. 38, mharrys/fluids-2d, haxiomic/GPU-Fluid-Experiments. [GitHub](https://github.com/PavelDoGreat/WebGL-Fluid-Simulation/blob/master/README.md)
- Pipeline: advection (semi-Lagrangian back-trace) → divergence → curl → vorticity confinement → Jacobi pressure solve [Webgpu](https://www.webgpu.com/showcase/webgl-fluid-simulation-by-pavel-dobryakov/) (default 20–25 iterations) → gradient subtraction. [deepwiki](https://deepwiki.com/PavelDoGreat/WebGL-Fluid-Simulation/3.1-fluid-dynamics-theory) Splats inject a Gaussian velocity impulse + dye: `splat = exp(-dot(p,p)/radius) * color`. [deepwiki](https://deepwiki.com/PavelDoGreat/WebGL-Fluid-Simulation/3.1-fluid-dynamics-theory)
- Key params: SIM_RESOLUTION 128, DYE_RESOLUTION 1024, DENSITY_DISSIPATION ~1, VELOCITY_DISSIPATION 0.2, PRESSURE 0.8, CURL 30, SPLAT_RADIUS 0.25, SPLAT_FORCE 6000. [deepwiki](https://deepwiki.com/PavelDoGreat/WebGL-Fluid-Simulation) [Joshua Lown](https://joshualown.org/2025/08/13/fluid-simulation/)
- Music-reactive precedent exists (forks/experiments: CM-Tech/musical-ink; [GitHub](https://github.com/CM-Tech/musical-ink) type76/fluid audio demo; [GitHub](https://github.com/PavelDoGreat/WebGL-Fluid-Simulation/issues/10) rocksdanister lively-wallpaper audio fork) [GitHub](https://github.com/rocksdanister/WebGL-Fluid-Simulation) but at the time of writing **no faithful native-Android GLES/Kotlin port existed** — so this app built one. See `render/fluid/FluidSim.kt` and `fluid_*.glsl`; the port is done. Enhanced JS reference with palette/config options: michaelbrusegard/WebGL-Fluid-Enhanced. [GitHub](https://github.com/michaelbrusegard/WebGL-Fluid-Enhanced)
- Native reference solvers worth reading: mishurov/fluid (OpenGL fragment-shader Navier-Stokes that packs floats into unsigned-byte textures [GitHub](https://github.com/mishurov/fluid) to dodge float-texture support gaps on Android GPUs); ARM's official compute_particles GLES 3.1 sample.

**B2. Curl-noise / simplex flow-field GPU particles.**
- References: al-ro/particles (3D curl-noise FBM, instanced camera-facing particles); CaffeineViking/cnpf; kbladin/Curl_Noise; atyuwen bitangent-noise (single-shader divergence-free noise, GLSL+HLSL provided).
- Particles advect through curl(noise); divergence-free guarantees no clumping. Store positions/velocities in ping-pong float textures or transform-feedback buffers.

**B3. GPU particle lifecycle (spawn/respawn) via ping-pong FBO or transform feedback.**
- References: ARM OpenGL ES SDK "Particle Flow Simulation with Compute Shaders" (pack lifetime in w-component of vec4 position; respawn at emitter when life runs out); [ARM Software](https://arm-software.github.io/opengl-es-sdk-for-android/compute_particles.html) ARM "Boids" GLES 3.0 transform-feedback sample; [ARM Software](https://arm-software.github.io/opengl-es-sdk-for-android/boids.html) Chris Wellons' nullprogram "A GPU Approach to Particle Physics," which reports it "can simulate and draw over 4 million particles at 60 frames per second" using paired position/velocity textures (x, y, dx, dy packed into color channels). Both patterns are GLES-3.0-compatible (transform feedback is core in 3.0; ping-pong textures work everywhere).

**B4. Reaction-diffusion (Gray-Scott).**
- References: jasonwebb/reaction-diffusion-playground; amandaghassaei/ReactionDiffusionShader; Pierre Couy's <100-line Shadertoy Gray-Scott. [Pierre-couy](https://pierre-couy.dev/simulations/2024/09/gray-scott-shader.html) Ping-pong two chemical concentrations (R,G channels), run several iterations per frame, map to color. [GitHub](https://github.com/jasonwebb/reaction-diffusion-playground) Produces coral/mitosis/labyrinth living textures.

**B5. Physarum / slime-mold (multi-agent).**
- References: Sage Jenson's writeup (canonical); Jeff Jones' paper; erlingpaulsen/godot-physarum, nicoptere/physarum (JS+WebGL), ollien/slime-mold (WebGL, 622k particles). Agents sense a trail map with three forward sensors, turn toward highest concentration, deposit, then the trail map is blurred + decayed. [GitHub](https://github.com/erlingpaulsen/godot-physarum) Emergent vein/tentacle networks — extremely organic.

**B6. Metaballs / marching-squares gel.**
- References: ARM OpenGL ES SDK "Metaballs" (GLES 3.0 transform-feedback + marching cubes, runs on Android 4.3+); [ARM Software](https://arm-software.github.io/opengl-es-sdk-for-android/metaballs.html) Codrops droplet metaballs (raymarched smoothMin); [Codrops](https://tympanus.net/codrops/2025/06/09/how-to-create-interactive-droplet-like-metaballs-with-three-js-and-glsl/) jamie-wong metaballs+WebGL; Godot metaballs shader. `smoothMin` (soft union) of SDF spheres gives the merging-blob gel look. [Godot Shaders](https://godotshaders.com/shader/metaballs/)

**B7. Raymarched fractals (Mandelbulb, KIFS, Menger, Mandelbox).**
- References: IQ Mandelbulb article (realtime at 720p on modern GPUs with distance-estimation raymarching); [Inigo Quilez](https://iquilezles.org/www/articles/mandelbulb/mandelbulb.htm) Kosalos/OSX_BareBonesRayMarching (50 fractal DEs); [GitHub](https://github.com/Kosalos/OSX_BareBonesRayMarching) Hvidtfeldt's distance-estimated fractal series; countless Shadertoy KIFS. **Heaviest on mobile** — expect to run at reduced resolution.

**B8. Domain-warped FBM & feedback trails.**
- References: IQ "warp" article (his Shadertoy code is CC-BY-NC-SA, but the technique is free); The Book of Shaders Ch. 13; MilkDrop warp-shader feedback. Cheap and high-impact.

### C. Implementation recommendations for *musicviz* (GLES 3.0 specifics)

**Float FBOs.** At init, query `GL_EXT_color_buffer_float`; if absent, use `GL_EXT_color_buffer_half_float`. Use sized internal formats `GL_RG16F` (velocity), `GL_RGBA16F` (dye), `GL_R16F` (pressure/curl/divergence) with type `GL_HALF_FLOAT`. Probe each format with a 4×4 test FBO and `glCheckFramebufferStatus`. If `OES_texture_half_float_linear` is absent, set `GL_NEAREST` on all sim textures and use a manual-bilinear advection shader (Pavel already ships both variants). RGB16F is never color-renderable — always use RGBA16F.

**Ping-pong vs transform feedback.** For fluid and reaction-diffusion, use ping-pong FBOs (fragment-shader GPGPU) — simplest and universally supported. For flow-field particles you can use either transform feedback (core in GLES 3.0) or ping-pong position/velocity textures rendered as points; the texture approach avoids CPU readback and integrates cleanly with your existing particle pipeline. Use instanced rendering for sprite particles.

**Resolution strategy.** Fluid: sim grid quarter-res (e.g. 128–192 on the short axis) with the app's 1.4× supersampled FBO used for the dye/display buffer. Reaction-diffusion and Physarum trail maps: half-res is plenty. Raymarched fractals: render at 0.5–0.75× into an FBO then upscale in the composite pass. Cap pressure iterations at ~20 on mobile; expose as a quality slider.

**Plugging into your architecture.** Each technique becomes a **new scene that renders into the existing scene→FBO stage**, then flows through your FxCompositor unchanged — meaning kaleidoscope, warp, mirror, bloom, duotone, dual-palette, LFOs and param-fades all instantly apply to fluid/particles/RD/Physarum for free. Do **not** re-implement post-FX inside the new scenes.

**Audio→visual mapping (using your existing FFT bands + BPM/beat-phase + attack/decay).** Recommended conventions, corroborated by MilkDrop, MuseGen's "deep dive," and audio-reactive-particle guides:
- **Bass band / kick onset → splat injection & burst spawn.** Trigger a fluid splat (or particle burst) on a band-limited bass onset, not full-spectrum loudness. [MuseGen](https://www.musegen.ai/blog/music-visualiser-deep-dive-how-it-works-and-why-it-pops) Position splats at attractors or randomized points; scale **splat force by beat-phase pulse** and **splat radius by bass energy**.
- **Mids → global motion speed / flow-field time-scale / advection strength.** Mids drive how fast the field morphs.
- **Highs → sparkle/detail:** high-frequency energy spawns small bright short-lived particles, raises curl/vorticity, or adds fine noise octaves. [Audioreactivevisuals](https://audioreactivevisuals.com/particle-systems.html)
- **Beat / BPM → spawn bursts and feedback-zoom impulses.** Use beat-phase to schedule bursts; the app's BPM clock can drive steady pulses for quantized/AI music where onset detection is weak. [GitHub](https://github.com/dmeldrum6/Web-Audio-Visualizer/)
- **Attack/decay envelopes → particle lifetime and impulse decay.** Fast attack + slow decay on every impulse; tie particle lifetime to the decay envelope so emission bursts fade rather than pop.
- **Best practices to avoid jitter/popping:** pick 3–5 features max, smooth everything with attack/release, use band-limited triggers (kick ≠ hi-hat), [MuseGen](https://www.musegen.ai/blog/music-visualiser-deep-dive-how-it-works-and-why-it-pops) and fade particle alpha in/out over lifetime. Raw features are noisy; insufficient smoothing or mapping to the wrong band is the usual failure. [MuseGen](https://www.musegen.ai/blog/music-visualiser-deep-dive-how-it-works-and-why-it-pops)

Expose the salient parameters (curl, dissipation, splat radius/force, palette a/b/c/d, flow frequency/time-scale, decay times) as Customize sliders and LFO targets, matching how your existing 9 particle uniforms and composite FX are surfaced.

### D. Rough implementation order (quick wins first)

1. **Feedback-buffer trails** (days). One extra FBO + a warp/zoom/fade sample of last frame. Immediately makes *every* existing scene more liquid. Lowest risk.
2. **Curl-noise flow-field particles** (1–2 weeks). Reuses your particle pipeline; add a curl-noise velocity update (ping-pong textures or transform feedback) and music-driven emission. Biggest organic payoff per effort.
3. **GPU fluid sim scene** (3–5 weeks, flagship). Port Pavel's shaders to GLES 3.0 with the float/filtering fallback; wire splats to bass onsets and dye to the cosine palette.
4. **Reaction-diffusion & Physarum scenes** (1–2 weeks each). Texture-based "living" backdrops; modulate feed/kill (RD) or sensor/deposit (Physarum) params and inject on beats.
5. **Metaball gel scene** (1 week) and **raymarched fractal scenes** (2–3 weeks, heaviest, do last). Fractals need aggressive resolution scaling on mobile.

**Benchmarks that change the plan:** if the fluid sim can't hold 60 fps at 128 sim-res on your target device, drop to 96 and halve the dye buffer before cutting pressure iterations below ~15 (fewer iterations makes it look gaseous/leaky). If float FBOs aren't renderable at all on a target device, fall back to the mishurov-style unsigned-byte packing rather than dropping the scene.

## Recommendations

1. **Start this week with feedback trails + one curl-noise particle scene.** These are low-risk, reuse existing infrastructure, and validate your music-mapping conventions before you invest in the fluid port.
2. **Commit to the fluid sim as the headline feature** for the next release. Vendor Pavel's MIT shaders (keep the license notice), replicate the support-probe/NEAREST fallback, and render dye into your supersampled FBO so the composite FX apply.
3. **Standardize an audio-mapping layer** (bass→splat/spawn, mids→speed, highs→detail, beat→burst, attack/decay→lifetime) as shared code so every new scene reacts consistently. Smooth all signals; use band-limited onset triggers.
4. **Add Physarum and reaction-diffusion as "ambient" scenes** for slower passages — they shine when lightly perturbed rather than hammered on every beat.
5. **Defer raymarched fractals** until you've profiled headroom; gate them behind a "high performance" quality tier.
6. **Expose new sim parameters as sliders + LFO targets** so users (and your existing LFO/param-fade system) can animate them — the LFOs on curl/dissipation/palette-phase will multiply the organic feel for free.

## Caveats
- **No existing native Android/Kotlin GLES port of Pavel's sim was found at research time**, so this app wrote its own — `render/fluid/FluidSim.kt` + `FluidBuffers.kt`. This caveat is historical; do not read it as work outstanding.
- **Half-float precision and linear-filtering support vary by mobile GPU;** even where FP16 linear filtering is nominally supported, arithmetic precision differs across chips. Test on a range of devices and keep the NEAREST/manual-bilinear path working.
- **Compute shaders are GLES 3.1+**, above your min-SDK-26 / GLES-3.0 floor — so favor fragment-shader GPGPU (ping-pong) and transform feedback over compute-based Physarum/particle designs, or gate compute paths behind a capability check.
- IQ's Shadertoy code is often **CC-BY-NC-SA** (non-commercial); the *techniques* are free to use, but don't copy his shader source verbatim into a commercial app without checking each snippet's license. Pavel's sim is MIT and safe to vendor with attribution.
- Performance figures here are design targets, not measured on your hardware — treat the resolution/iteration numbers as starting points to profile against.