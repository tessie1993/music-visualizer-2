# Reference coverage ledger

**Generated — do not edit.** `ReferenceCoverageTest` rewrites this file from
`reference-coverage.json` whenever the two drift, and fails until the new version is
committed. Edit the JSON.

Every effect and concept named in [`MASTER_PLAN.md`](MASTER_PLAN.md) §8.1, with the
family that owns it and what is being done with it. §3.2 requires the enumeration to
be complete rather than limited to the first release: without it the same source gets
re-researched, a shader gets borrowed with no traceable origin, and the catalogue
fills with four near-duplicates of one idea found in four repositories.

A row is **complete** only once its implementation, rejection or merge is evidenced.
Being named here means nothing has been incorporated yet.

## Totals

| Disposition | Meaning | Rows |
|---|---|---:|
| **PORT** | Becomes its own V2 engine or reusable kernel. | 9 |
| **MERGE** | Folded into a family as a recipe, mode, field, boundary or post node (§8.2). | 123 |
| **EXCLUDE** | Licence, provenance, duplication or product fit blocks it. | 0 |
| **DEFER** | In the catalogue, not in this wave. The rationale says what would unblock it. | 30 |
| | **total** | **162** |

| Source | Rows | Licence tier |
|---|---:|---|
| `fosfora` | 55 | STUDY |
| `swissgl` | 9 | ADAPT |
| `velo-visualiser` | 44 | STUDY |
| `vgalizer` | 32 | REIMPLEMENT |
| `threelab` | 22 | REIMPLEMENT |

## Coverage by family

Columns are the §3.2 schema. `recipe`, `tests`, `captures` and `shipped` fill in as
the owning family's slices land.

### `sdf-volumetric` — SDF Dream Space and volumetrics (§7.8)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Aurora | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.8 lists Aurora as a recipe; the app already ships an `aurora` scene ID to migrate. | — | — | — |
| Beam | `fosfora` | `09132c0` | STUDY | — | **MERGE** | Existing `beam` scene ID; volumetric light shafts belong with the raymarch library. | — | — | — |
| Mandelbox | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Nebula | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| permitted 3D variants | `swissgl` | `489dfcf` | ADAPT | — | **DEFER** | Per-kernel decision; no 3D variant is scheduled before the raymarch library exists. | — | — | — |
| Spectral Canyon | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Starscape | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |

### `image-matter` — Image matter and media textures (§7.10)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Logo Particle | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Raster | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.10 requires mosaic, Voronoi, raster and point-cloud renderers. | — | — | — |

### `math-geometry` — Mathematical geometry and fields (§7.9)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| circle packing | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| cloth | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| electric field | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Electric Iris | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| fractal | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Helix | `fosfora` | `09132c0` | STUDY | — | **MERGE** | Parametric helical geometry is analytic-family work. | — | — | — |
| isoline field | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| L-systems | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Laser Array | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| laser burst | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| line moiré | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Mandelbrot zoom | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| morphing geometry | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| ring tunnel | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| space-filling curves | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| spirograph | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Tesla | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.9 covers electric/magnetic field visualisation. | — | — | — |
| Truchet | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| vector terrain | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Voronoi | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Voronoi pulse | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| voxel landscapes | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| wave interference | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| wire tunnel | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |

### `swarm-ecology` — Slime, swarm and ecology (§7.4)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Audio Web | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Firefly Sync | `swissgl` | `489dfcf` | ADAPT | — | **PORT** | §7.4 phase-coupled oscillators. | — | — | — |
| Murmur | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.4 lists Murmur as a recipe (murmuration/boids). | — | — | — |
| Mycelium | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.4 lists Mycelium as a recipe. | — | — | — |
| network graph | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Particle Life | `swissgl` | `489dfcf` | ADAPT | — | **PORT** | §7.4 species attraction matrix; its own kernel, field-approximated at low tiers. | — | — | — |
| Physarum | `swissgl` | `489dfcf` | ADAPT | — | **PORT** | §7.4 sensor/turn/deposit/decay; the SoA agent kernel. | — | — | — |
| Physarum | `threelab` | `9b37d76` | REIMPLEMENT | — | **PORT** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Polycephalum | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.4 lists Polycephalum; Physarum is its engine. | — | — | — |
| Symbiosis | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.4 lists Symbiosis. | — | — | — |

### `living-matter` — Living Matter (§7.3)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| 445 | `fosfora` | `09132c0` | STUDY | — | **MERGE** | A lattice CA rule; §8.2 makes rule names presets of one CA engine. | — | — | — |
| Brain | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 names Brain among the CA variants. | — | — | — |
| Builder | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 names Builder among the CA variants. | — | — | — |
| cellular automata | `swissgl` | `489dfcf` | ADAPT | — | **MERGE** | §8.2: CA rule names become presets of one lattice engine. | — | — | — |
| cellular automata | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Chunky | `fosfora` | `09132c0` | STUDY | — | **MERGE** | A lattice CA rule preset. | — | — | — |
| Clouds | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 names Clouds among the CA variants. | — | — | — |
| Frost | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 lists Frost as a recipe of the reaction-diffusion/CA engine. | — | — | — |
| Neural CA | `swissgl` | `489dfcf` | ADAPT | — | **DEFER** | §7.3 admits neural CA presets only after independent implementation and device proof. | — | — | — |
| Particle Lenia | `swissgl` | `489dfcf` | ADAPT | — | **PORT** | §7.3 Particle Lenia Garden; its own kernel. | — | — | — |
| Pulse (CA) | `fosfora` | `09132c0` | STUDY | — | **MERGE** | A lattice CA rule preset; distinct from the Fosfora shader of the same name. | — | — | — |
| Pyroclastic | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 lists Neural Pyroclastic, gated on independent neural-CA implementation. | — | — | — |
| Reaction Diffusion | `swissgl` | `489dfcf` | ADAPT | — | **PORT** | §7.3 Gray–Scott; the shared stencil/convolution engine. | — | — | — |
| Reaction Diffusion | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| reaction-diffusion | `threelab` | `9b37d76` | REIMPLEMENT | — | **PORT** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Shells | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 names Shell among the CA variants. | — | — | — |
| Turing | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.3 lists Turing Veil; Gray–Scott is its engine. | — | — | — |

### `overlays` — Overlay instruments (§7.11)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Astrolabe | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11 lists astrolabe. | — | — | — |
| Beat Pulse | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Bezel | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11 lists bezel/frame. | — | — | — |
| Fenestra | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11's window lattice; Fenestra and Tessera are one overlay with two parameter sets. | — | — | — |
| Intarsia | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11 lists intarsia/inlay. | — | — | — |
| Level Meter | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Limn | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11 lists limn/contour. | — | — | — |
| Mechanical Meter | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Reticle | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11 lists reticle. | — | — | — |
| Tessera | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.11 lists tessera/window lattice. | — | — | — |

### `post-nodes` — Common post-processing nodes (§6.6) — not a family

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Chromatic Dots | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| glitch post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| mirror post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Prism | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §6.6 already requires chromatic split, prism and lens dispersion as post nodes. | — | — | — |
| rotation post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| scanline post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| strobe-safe post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **PORT** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| trail post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| VGA post effect | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |

### `morphic-vector` — Morphic Vector Cathedral (§7.1)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| attractors | `threelab` | `9b37d76` | REIMPLEMENT | — | **PORT** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Aurora Drift | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Beat Fireworks | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Chaos | `fosfora` | `09132c0` | STUDY | — | **MERGE** | Chaotic attractors are the §7.1 field library's core. | — | — | — |
| Crystal Swarm | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| flow field | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| magnetic pendulum | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Morph | `fosfora` | `09132c0` | STUDY | — | **MERGE** | Field morphing without particle reset is the §7.1 thesis. | — | — | — |
| Phyllotaxis | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Strange Attractor | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |

### `fluid-flow` — Fluid and flow (§7.5)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Fluid | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Liquid Light | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Plasma Storm | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Splat | `fosfora` | `09132c0` | STUDY | — | **MERGE** | Splats are a §7.5 solver input, not a scene. | — | — | — |
| Storm | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.5 lists Storm/Plasma Storm; existing `storm` scene ID migrates here. | — | — | — |
| Tide | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.5 lists Chromatic Tide. | — | — | — |

### `recursive-temple` — Recursive Temple and optical feedback (§7.7)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| domain warping | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| hyperspace | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| kaleido warp | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| kaleidoscope | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Mandala Pulse | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Möbius grid | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Reliquary | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.7 lists Reliquary; an existing Hyperspace look carries the name. | — | — | — |
| Tunnel | `fosfora` | `09132c0` | STUDY | — | **MERGE** | §7.7 Living Tunnel; existing `tunnel` scene ID migrates here. | — | — | — |
| Tunnel | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| TV acid | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| warp grid | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |

### `cymatic-matter` — Cymatic and Acoustic Matter (§7.2)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Chladni Plate | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Cymatics | `fosfora` | `09132c0` | STUDY | — | **MERGE** | The whole of §7.2; the app already ships eleven Cymatics looks. | — | — | — |
| cymatics | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Wave2D | `swissgl` | `489dfcf` | ADAPT | — | **DEFER** | §7.2 marks FDTD Wave2D an Ultra experiment after grid scaling is proven. | — | — | — |

### `phase-scope` — Phase, scope and spectral landscape (§7.6)

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Bars | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Circular Spectrum | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| CRT Scope | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| harmonograph | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| LED Matrix | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| LED Matrix 3D | `velo-visualiser` | `bebf723` | STUDY | — | **DEFER** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Lissajous | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Lissajous | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Oscilloscope | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Phase Scope | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| radial EQ | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Raw Scope | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Spectral Bloom | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Spectrogram | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Spectrum Analyser | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| spectrum bars | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| spectrum orbit | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| spectrum terrain | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| spectrum wave | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| sphere spirals | `threelab` | `9b37d76` | REIMPLEMENT | — | **MERGE** | Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Topographic Ridge | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| wave dunes | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Waveform 3D | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Waveform Roll | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Waveform Waterfall | `velo-visualiser` | `bebf723` | STUDY | — | **MERGE** | Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| XY scope | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **MERGE** | vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |

### Unassigned

Named in §8.1, not yet attributable to a family from the plan alone. Each needs
the upstream look characterised before a family can own it — and until then it
is not evidence that the catalogue is short of anything.

| upstream name | source | commit | tier | recipe | disposition | rationale | tests | captures | shipped |
|---|---|---|---|---|---|---|---|---|---|
| Accretion | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; orbital accretion would sit in §7.1 if confirmed. | — | — | — |
| Array | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Ascend | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Cascade | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Chromatica | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename. Disposition needs the upstream look characterised first. | — | — | — |
| Cleave | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Drift | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; 'drift' names a motion, not a silhouette. | — | — | — |
| Etch | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| evolutionary parameter exploration | `threelab` | `9b37d76` | REIMPLEMENT | — | **DEFER** | Not characterised from the plan alone. Threelab is MIT but a web application; the mathematics is reimplemented inside the named family. | — | — | — |
| Flux | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; 'flux' is also an audio feature, so the name is not evidence. | — | — | — |
| Genesis | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Iris | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename. Velo's 'Electric Iris' may or may not be the same idea. | — | — | — |
| Lumen | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; likely a light/exposure treatment rather than a system. | — | — | — |
| Meridian | `velo-visualiser` | `bebf723` | STUDY | — | **DEFER** | Not characterised from the plan alone. Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Odyssey | `velo-visualiser` | `bebf723` | STUDY | — | **DEFER** | Not characterised from the plan alone. Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Panorama | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Pegboard | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; possibly a lattice instrument, which §7.11 would cover. | — | — | — |
| Protea | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
| Pulse | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename, and Fosfora reuses it for a CA rule — ambiguous until characterised. | — | — | — |
| Shards | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; crystalline billboards in §7.1 may already cover it. | — | — | — |
| Slipstream | `velo-visualiser` | `bebf723` | STUDY | — | **DEFER** | Not characterised from the plan alone. Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Strata | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; possibly layered terrain, which §7.6 would cover. | — | — | — |
| Sumi | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; ink-wash suggests §7.5 dye rendering, unconfirmed. | — | — | — |
| vector rabbit | `vgalizer` | `faa19ee` | REIMPLEMENT | — | **DEFER** | Not characterised from the plan alone. vgalizer is MIT but Rust/wgpu; the recipe is reimplemented inside the named family. | — | — | — |
| Veil | `velo-visualiser` | `bebf723` | STUDY | — | **DEFER** | Not characterised from the plan alone. Coverage question only. Velo is GPL-3.0, so the concept is implemented from published mathematics inside the named family and no Velo code or shader is read into it. | — | — | — |
| Vessel | `fosfora` | `09132c0` | STUDY | — | **DEFER** | Opaque codename; nothing in §8.1 or §7 describes the look, so naming a family would be invention. | — | — | — |
