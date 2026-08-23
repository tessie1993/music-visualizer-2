# Visual style backlog — researched candidates

Companion to `VISUAL_STYLE_RESEARCH.md`, which records what was *built*. This
records what could be built next, and why each one is worth a slot.

The bar for entry is that a candidate brings a **technique the engine does not
already have**. Geode already ships distance-estimated fractal flight
(Hyperspace), a Navier-Stokes solver (Fluid/Curl/Water), standing-wave physics
(Cymatics), four agent/field simulations (Silk/Life/Acid/Myco), an oscilloscope
beam, projectM, nine CPU particle scenes and 22 fragment styles. "Another
fractal" is not a candidate. Each entry below names what is genuinely new.

Every entry carries the same four columns of judgement, because those are the
things that decide whether a style ships:

- **Cost** — per-pixel or per-frame expense on a mid-tier Mali, relative to the
  existing families. The engine's constraint is bandwidth and the tiler, not ALU
  (see `docs/visualizer-v2/ENGINE_V2_PLAN.md` §6).
- **Touch** — what a finger means. Post-`TouchField`, every style is expected to
  answer this; a style with no good answer is a weaker candidate.
- **Audio** — what the music actually drives. A style whose audio mapping is
  "brightness goes up on the beat" is not finished being designed.
- **New** — the technique the engine gains.

---

## Tier 1 — strongest candidates

### 1. Strange attractor swarm

Hundreds of thousands of particles integrating a chaotic ODE — Lorenz, Thomas,
Aizawa, Clifford, de Jong — each frame stepping every particle by one
Runge-Kutta or Euler step and depositing additively. The attractor's *shape* is
emergent: nobody models the butterfly, it falls out of three lines of algebra.
Switching the coefficient set morphs one attractor continuously into another.

The engine already has the machinery: this is a ping-pong state texture updated
by a fragment shader and read back through vertex texture fetch as instanced
points — the exact SwissGL pattern `ENGINE_V2_PLAN.md` §6 specifies, and the
same shape as the existing Myco deposit pass.

- **Cost** Medium. Binding, not fragments, is the constraint — the plan's own
  note applies: real mobile agent sims sit at 10k–50k, not the 128k a
  fragment-count calculation suggests.
- **Touch** The finger is a gravitational attractor added to the vector field;
  particles bend toward it and the attractor visibly deforms. Multi-touch =
  several competing wells.
- **Audio** Bass scales the integration timestep (the attractor speeds up and
  the orbit widens), mids morph the coefficient set between named attractors,
  treble drives point brightness, beats snap to a new coefficient set.
- **New** Chaotic dynamical systems. Nothing in the app integrates an ODE.

Refs: [Draves-style GPU attractor systems](https://github.com/tmhglnd/strange-attractors),
[fusefactory GPU attractors](https://fusefactory.github.io/openfuse/strange%20attractors/particle%20system/Strange-Attractors-GPU/).

### 2. Gravitational lensing / black hole

Bend the ray itself. Instead of marching a straight line, integrate the photon
path under a Schwarzschild-like deflection each step, so the background starfield
smears into an Einstein ring and the accretion disk appears both in front of and
*above* the hole at once. Doppler beaming brightens the approaching limb;
gravitational redshift reddens the inner edge.

This is the single most recognisable image in modern physics and no music
visualizer ships it well.

- **Cost** Medium-high; it is a raymarch with a curved ray, so the step count is
  the whole budget. Falls out naturally under the `uSteps` budget uniform.
- **Touch** The finger *is* the singularity. Drag it and the entire starfield
  distorts around your fingertip. This may be the best touch mapping available
  in the whole backlog.
- **Audio** Bass sets the mass (and so the ring radius), mids the disk's orbital
  speed, treble the disk's turbulent structure, beats fire infalling matter.
- **New** Non-straight ray propagation. Every existing raymarcher walks lines.

Refs: [Bruneton's real-time black hole shader](https://ebruneton.github.io/black_hole_shader/),
[Kerr ray-tracer with frame dragging](https://github.com/SushantGagneja/Black-Hole-simulation).

### 3. Fractal flame

Scott Draves' algorithm: the chaos game over a set of non-linear variation
functions, accumulated into a histogram, then **log-density tone mapped**. The
log-density step is the whole trick — it is what turns a sparse point cloud into
the luminous, smoke-like structures Electric Sheep is famous for, and it is
exactly the "apply the log curve at display, not in the accumulator" rule the
engine plan already states for deposit fields.

- **Cost** Medium. It is an accumulation buffer plus a cheap per-sample
  iteration; the cost is samples per frame, which is tunable.
- **Touch** A finger adds a transient variation function centred on it, so the
  flame grows a new lobe toward the touch.
- **Audio** Variation weights interpolate on mids, the affine coefficients
  breathe on bass, and the tone-map gamma opens on energy. Beats cross-fade to a
  new variation set.
- **New** Non-linear IFS with histogram accumulation. Genuinely different from
  distance-estimated fractals — this is a *measure*, not a surface.

Refs: [Draves & Reckase, *The Fractal Flame Algorithm*](https://flam3.com/flame_draves.pdf),
[Cuburn GPU renderer](https://www.ece.ucf.edu/seniordesign/su2011fa2011/g12/Cuburn.pdf).

### 4. Hyperbolic tiling (Poincaré disk)

A {p,q} tiling of the hyperbolic plane where `(p-2)(q-2) > 4`, rendered in the
conformal Poincaré disk — infinitely many identical cells crowding toward a
boundary circle they never reach. Escher's *Circle Limit*. Translating in
hyperbolic space slides the whole tiling through itself in a way that has no
Euclidean equivalent, which is the trippy part: the cells change size as they
move but never change shape.

- **Cost** **Low.** This is a 2D conformal map plus a fold — one of the cheapest
  entries here, which makes it valuable as the low-end-device showpiece.
- **Touch** The finger is the point the tiling is centred on; dragging performs
  a hyperbolic translation, so the entire infinite pattern flows around it.
- **Audio** Bass drives the hyperbolic translation speed, mids rotate, treble
  lights cell edges, and beats step the Schläfli symbol {p,q} to retile.
- **New** Non-Euclidean 2D geometry with a conformal metric.

Refs: [Hyperbolic Poincaré tiling](https://www.shadertoy.com/view/WlBczG),
[hyperbolic Truchet tiles](https://www.shadertoy.com/view/3llXR4),
Dunham & Lindgreen, *Creating Repeating Hyperbolic Patterns* (1981).

### 5. Thin-film interference / iridescence

Physically-grounded colour: light reflecting off the top and bottom of a film
whose thickness is on the order of a wavelength interferes, reinforcing some
wavelengths and cancelling others. Vary the thickness across a surface and you
get the oil-slick and soap-bubble palette — colours no cosine palette produces,
because they come from physics rather than from a ramp.

Best applied to a **bubble/foam** surface: raymarched spheres fused with `smin`,
or a Worley-cell foam, with film thickness driven by curvature and flow.

- **Cost** Low-medium. The interference term is a few trig evaluations per
  pixel; the cost is whatever surface carries it.
- **Touch** The finger thins the film where it presses, shifting the colour band
  — like actually touching a bubble. Released fingers leave a fading thin spot.
- **Audio** Bass changes bulk film thickness (the whole image sweeps through the
  spectrum), treble adds fine thickness noise, beats pop a cell.
- **New** Spectral/wave-optics colour. Every existing style colours from a
  palette LUT or a cosine ramp.

Refs: [Thin-film interference](https://en.wikipedia.org/wiki/Thin-film_interference).
Note the real caveat: R/G/B alone is not enough — the interference has to be
evaluated at several wavelengths and then integrated to RGB, or the hues come
out wrong.

---

## Tier 2 — strong, with a caveat

### 6. 4D polytope / Hopf fibration

Rotate a tesseract or 24-cell through the six fundamental 4D planes (XY, XZ, XW,
YZ, YW, ZW) and project **stereographically** rather than perspectively —
stereographic projection preserves angles, so edges become arcs and you can see
the internal structure instead of a tangle of lines. The Hopf fibration
(interlocking great circles on a 3-sphere, every pair linked) is the same
machinery and is arguably the more beautiful image.

- **Caveat** Reads as "mathematical demo" unless art-directed hard. Needs glow,
  depth cueing by the 4th coordinate, and restraint.
- **Cost** Low (it is line/tube geometry, not a march).
- **Touch** The finger drives rotation in the *W* planes — the ones with no 3D
  analogue — so touching literally turns the object through the fourth dimension.
- **Audio** Bass drives W-plane rotation rate, treble edge glow, beats swap
  polytope.
- **New** 4D geometry and stereographic projection.

Refs: [Clifford torus rotation](https://www.shadertoy.com/view/wsfGDS),
[Hopf fibration](https://polytope.miraheze.org/wiki/Hopf_fibration).

### 7. Dielectric breakdown / Lichtenberg lightning

Grow a branching discharge by the Niemeyer–Pietronero–Weismann model: solve a
Laplace field, and grow the tree preferentially where the field gradient is
strongest. This is DLA with an electric bias, and it produces real lightning
morphology rather than the hand-drawn zigzags most "lightning" shaders use.

- **Caveat** Genuinely a simulation with state — it needs the same ping-pong
  field machinery the Life/Acid families use, and a Laplace solve is a Jacobi
  iteration, which the fluid solver already has. Reuse, do not rebuild.
- **Cost** Medium (Jacobi iterations dominate).
- **Touch** The finger is an electrode. Discharge grows toward it; two fingers
  arc between each other.
- **Audio** Transients trigger strikes (this is the most natural
  onset-to-visual mapping in the whole backlog), bass sets branch thickness,
  treble the fine filaments.
- **New** Laplace-field-driven growth; a discrete branching structure.

Refs: [Lichtenberg figures / DBM](https://handwiki.org/wiki/Lichtenberg_figure),
[dielectric breakdown implementations](https://github.com/chromia/lichtenberg).

### 8. Suminagashi / mathematical marbling

The Japanese floating-ink art, done properly: the literature gives **closed-form
transforms** (drop, tine, wavy, vortex) that are exactly area-preserving, so ink
never diffuses and the pattern stays razor-sharp no matter how many operations
are applied. Combine those with the existing fluid solver for a look neither
gives alone.

- **Caveat** Overlaps the existing Fluid/Water family; needs to be visually
  distinct enough to justify a slot. The distinctness comes from the sharp,
  non-diffusing ink boundaries, which the Navier-Stokes path cannot produce.
- **Cost** Low. The transforms are closed-form and cheap.
- **Touch** The finger *is* the tine — this is the real gesture of the art form,
  and it maps to touch better than almost anything else here.
- **Audio** Beats drop new ink, bass drives tine strength, mids the vortex.
- **New** Area-preserving closed-form deformation; sharp-boundary ink.

Refs: [Amanda Ghassaei, Digital Marbling](https://blog.amandaghassaei.com/2022/10/25/digital-marbling/),
[Computer-Generated Marbling Textures: A GPU-Based Design System](https://www.researchgate.net/publication/3210505_Computer-Generated_Marbling_Textures_A_GPU-Based_Design_System).

### 9. Moiré / Op-art

Two near-identical repeating patterns at slightly different frequency or angle
produce a third, much larger pattern that exists in neither. Tiny changes in the
inputs produce enormous changes in the output, which makes it exquisitely
audio-sensitive — a 0.5% frequency shift on the bass moves the whole interference
field across the screen.

- **Caveat** This is the highest photosensitivity risk in the backlog. The app
  has a WCAG-grounded safety system and a flash budget; this style must be built
  against it from the first line, not retrofitted.
- **Cost** **Very low.** Two pattern evaluations.
- **Touch** The finger sets the centre of the second pattern's rotation, so
  dragging sweeps the interference bands.
- **Audio** Frequency ratio on bass, relative angle on mids, pattern phase on
  treble.
- **New** Interference between sampling lattices; Op-art lineage.

Refs: [graphene moiré fragment shader](https://observablehq.com/@pamacha/graphene-moire-fragment-shader).

### 10. Slit-scan / time displacement

Swap a spatial axis with the time axis: each column of the image comes from a
different frame of history. The *2001* Stargate sequence and the Doctor Who
title tunnel. Applied to a live visualizer it turns any other style into a
smeared record of its own past.

- **Caveat** Costs **memory**, not ALU — a frame history at 1080p is hundreds of
  MB in the naive form. Only viable at reduced internal resolution with a
  bounded ring, which the engine's render-scale machinery already supports.
- **Cost** Low ALU, high bandwidth/memory. Needs care on a phone.
- **Touch** The finger paints the delay map — where you touch, time runs behind.
- **Audio** Delay depth on bass, the displacement map's structure on mids.
- **New** Temporal rather than spatial transformation. Nothing else in the app
  treats time as an image axis.

Refs: [Recreating the Doctor Who time tunnel in GLSL](http://roy.red/posts/slitscan/),
[KinoSlitscan](https://github.com/keijiro/KinoSlitscan) (note its measured
~300 MiB at 1080p — the reason for the caveat).

---

## Tier 3 — worth having, lower distinctiveness

| Candidate | Technique | Cost | Touch | New |
| --- | --- | --- | --- | --- |
| **Caustics** | Refract a light front through an animated surface; use screen-space derivatives to measure area compression rather than sampling — the standard cheap trick | Low | Finger disturbs the surface, caustics reorganize | Wave-optics light concentration |
| **GPU boids** | Separation/alignment/cohesion over a state texture; the natural first consumer of the GLES 3.1 compute tier | Medium | Finger is a predator (flee) or food (flock to) | Emergent multi-agent behaviour |
| **3D Voronoi foam** | Worley F1/F2 in 3D, raymarched as cell walls; F2−F1 gives the membrane | Medium | Finger pops cells / nucleates new ones | Cellular structure in 3D |
| **L-system growth** | Grammar-driven branching, grown over time and audio | Medium | Finger seeds a new plant | Rule-based procedural growth |
| **Terrain flythrough** | Raymarched heightfield with fog and cloud layer; iq's terrain-marching approach | Medium-high | Finger raises a mountain | Landscape/horizon composition |
| **Quaternion Julia** | 4D Julia set sliced to 3D, distance-estimated | Medium | Finger moves the Julia constant `c` — visibly reshapes it | 4D escape-time fractals |
| **Spectrogram terrain** | The FFT history as a scrolling 3D heightfield | Low | Finger scrubs/freezes the history | Direct data-as-landscape |

---

## Deliberately excluded, and why

- **More distance-estimated fractal flythroughs** — Hyperspace already carries
  six DE species with per-substyle Lipschitz correction and bounding-sphere
  culling. A seventh reads as a variation.
- **Reaction-diffusion (Gray-Scott)** — the Acid family already occupies this
  space.
- **Physarum / slime mould** — that is Myco.
- **Ferrofluid spikes** — already a Cymatics substyle (Rosensweig).
- **Generic "bars/waveform/spectrum"** — the app has these and they are table
  stakes, not a style.
- **Anything requiring a photo, video or ML model as input** — the app ships no
  image assets by design (`VISUAL_STYLE_RESEARCH.md`: procedural marks keep the
  UI sharp at every resolution), and a model would dwarf the APK.

---

## Cross-cutting notes

**Photosensitivity.** Moiré, lightning and anything with a strobe-like beat
response must be authored against `VisualSafety` and the flash budget from the
start. The product review already flags that the safety system ships off by
default; adding high-contrast flicker styles raises the stakes on that decision.

**Palette discipline.** Everything except thin-film interference should colour
through `pal()` / the Crameri LUTs. Thin-film is the deliberate exception
because its colours are the physics.

**The compute tier changes the ranking.** Boids, attractor swarms and the
Laplace solve for lightning all become substantially cheaper once the GLES 3.1
compute path exists. Until then, prefer the fragment/ping-pong formulations,
which is what `ENGINE_V2_PLAN.md` §6 already mandates as the baseline.
