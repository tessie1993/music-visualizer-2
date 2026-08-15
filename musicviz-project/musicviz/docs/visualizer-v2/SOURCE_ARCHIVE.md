# Source archive and adoption policy

Required by the V2 overhaul plan §3. Every external source that informs the V2 engine is
recorded here with its repository URL, pinned commit, licence, role, the files imported
from it, the modifications made, and its attribution requirements.

`provenance.json` in this directory is the machine-readable form of the same data. It is
the intended input for a `checkEngineProvenance` Gradle task; until that task exists, this
document is the contract.

**Working clones** live outside the repository at
`C:/Users/tessi/Claude/repos/_visualizer-research/`. They are reference material, not a
submodule and not a dependency. Restricted-licence clones are quarantined in
`_REFERENCE_ONLY_DO_NOT_COPY/` so the boundary is a filesystem fact rather than a note.

All licences below were read from the LICENSE file in the pinned clone, not from a
repository badge or a search result. Two claims from earlier research were wrong and are
corrected here.

This file is distinct from `THIRD_PARTY_NOTICES` and
`app/src/main/assets/third_party_notices.txt`, which are the shipped legal notices for
code already in the app. This one covers sources under evaluation and records the tier,
the pinned commit and the obligation each would create.

---

## 1. Adoption tiers

| Tier | Meaning |
|---|---|
| **ADAPT** | Source may be adapted into V2 with attribution, after a per-file licence check. Every adapted file carries an SPDX line and an origin comment. |
| **ALGORITHM** | Algorithms and parameter values may be reimplemented from the source. No code, shader text or constant table is copied verbatim. |
| **ORACLE** | Used only to generate test fixtures and expected values. Never shipped, never linked. |
| **STUDY** | Read for architecture and behaviour. Nothing derived from it enters the tree. |
| **EXCLUDED** | Licence forbids reuse, or there is no licence. Mood-board and mathematics research only. |

---

## 2. Direct adaptation

| Source | Commit | Licence | Role |
|---|---|---|---|
| [google/swissgl](https://github.com/google/swissgl) | `489dfcf` (2026-03-09) | Apache-2.0 | **ADAPT** — primary GLES 3.0 simulation reference. Port the texture-backed state histories, fragment-shader GPGPU, deterministic seeds and instanced particle rendering. Not the JavaScript wrapper. |
| [markusfisch/ShaderEditor](https://github.com/markusfisch/ShaderEditor) | `513e79f` (2026-08-11) | MIT | **ADAPT** — Android shader lifecycle, backbuffers, wallpaper behaviour, resolution choices, low-battery throttling. |
| [PavelDoGreat/WebGL-Fluid-Simulation](https://github.com/PavelDoGreat/WebGL-Fluid-Simulation) | `a2d2929` (2024-11-12) | MIT | **ADAPT** — already partially ported and attributed in `THIRD_PARTY_NOTICES`. Consolidate shared advection, pressure, splat, bloom and format-fallback infrastructure. |
| [QC20/Colourful-Attraction](https://github.com/QC20/Colourful-Attraction) | `6e502d3` (2026-04-14) | MIT | **ADAPT the GLSL only** — basis for the first signature scene: particles morphing between velocity fields without resetting state. See the caveat below. |
| [kevinraymond/fosfora](https://github.com/kevinraymond/fosfora) | `09132c0` (2026-08-10) | Apache-2.0 **and** MIT (dual) | **STUDY** in practice — the plan lists it for adaptation, but its runtime is Rust/wgpu and nothing is portable. Its value is the architecture: lock-free audio delivery, multi-resolution analysis, HDR layering, data-driven effects, modulation and audio textures. Algorithms must be independently validated. |
| projectM | in-tree `v4.1.7` + local patch | LGPL-2.1 | **RETAIN as-is.** Dynamically linked, notices shipped, patch and build recipe in-tree. Not the architecture for new visuals. See `tools/build-projectm.md`. |

## 3. Algorithm references — reimplement, do not copy

| Source | Commit | Licence | Role |
|---|---|---|---|
| [fogleman/physarum](https://github.com/fogleman/physarum) | `704dda7` (2020-12-04) | MIT | **ALGORITHM** — agent sensor/rotation/deposit/decay rules and their working parameter ranges. |
| [Chakazul/Lenia](https://github.com/Chakazul/Lenia) | `adfc542` (2022-03-16) | MIT | **ALGORITHM** — continuous CA kernel and growth function. |
| [keijiro/RDSystem](https://github.com/keijiro/RDSystem) | `7899466` (2025-08-11) | Unlicense | **ALGORITHM** — minimal GPU reaction-diffusion kernel. Public domain, but still recorded. |
| [hunar4321/particle-life](https://github.com/hunar4321/particle-life) | `2562787` (2024-08-29) | MIT | **ALGORITHM** — interaction-matrix ecology. |
| [jonradoff/threelab](https://github.com/jonradoff/threelab) | `9b37d76` (2026-07-06) | MIT | **ALGORITHM** — Lorenz/Rössler/Halvorsen/Thomas/Aizawa/Dadras formulations, curl noise, 4-pass GPU Physarum, wave interference, electric fields. |
| [niko-dellic/wavefield](https://github.com/niko-dellic/wavefield) | `3858a65` (2026-06-09) | MIT | **ALGORITHM** — spectral-peak → Chladni eigenmode mapping with attack/release/phase persistence. |
| [BrutPitt/glChAoS.P](https://github.com/BrutPitt/glChAoS.P) | `f3b604a` (2025-02-08) | BSD-2-Clause | **ALGORITHM** — attractor parameter ranges, point-sprite glow, tone mapping. |
| [meyda/meyda](https://github.com/meyda/meyda) | `ecf2566` (2024-04-21) | MIT | **ALGORITHM** — spectral feature formulas (centroid, rolloff, flatness, spread, ZCR, flux, MFCC, chroma). |
| [wizgrav/clubber](https://github.com/wizgrav/clubber) | `a8dad7c` (2018-06-27) | MIT | **ALGORITHM** — musical log/MIDI band mapping and feature→visual mapping. |
| [xsoophx/Kymatik](https://github.com/xsoophx/Kymatik) | `bbeca37` (2025-03-30) | MIT | **ALGORITHM** — Kotlin FFT/BPM pipeline structure on the JVM. |
| [sandner-art/Audio-Shader-Studio](https://github.com/sandner-art/Audio-Shader-Studio) | `3d5a6f1` (2025-09-14) | MIT | **ALGORITHM** — shader-side audio uniform/texture contract. |
| [s-macke/WebGPU-Lab](https://github.com/s-macke/WebGPU-Lab) | `ea1a8c0` (2026-04-07) | MIT | **ALGORITHM** — fluid and SDF techniques. Per-shader provenance review required. |
| [Hornfisk/vgalizer](https://github.com/Hornfisk/vgalizer) | `faa19ee` (2026-04-13) | MIT | **ALGORITHM** — effect recipes; Rust/wgpu so nothing is portable. |
| [gijzelaerr/spectrageist](https://github.com/gijzelaerr/spectrageist) | `0a177a4` (2025-12-21) | MIT | **ALGORITHM** — real-time feature extraction. |
| [karmatripping/KarmaViz](https://github.com/karmatripping/KarmaViz) | `67e8a15` (2025-07-15) | MIT | **ALGORITHM**. Earlier research flagged "conflicting licence, avoid" — **that was wrong**; `LICENSE.md` is plain MIT. |
| [muhamedamin308/wavora](https://github.com/muhamedamin308/wavora) | `cd49ad3` (2026-06-21) | MIT | **STUDY** — Android Media3 + clean-architecture example. Small project; treat as illustration, not authority. |

## 4. Test oracles

| Source | Licence | Role |
|---|---|---|
| [michaelkrzyzaniak/Beat-and-Tempo-Tracking](https://github.com/michaelkrzyzaniak/Beat-and-Tempo-Tracking) | MIT — pinned `c039090` (2023-12-17) | **ORACLE + ALGORITHM.** Benchmark its causal onset, tempo and predicted-beat behaviour against the Kotlin tracker. Port methods only after corpus evaluation. |
| librosa | ISC | **ORACLE.** Formula reference and fixture generator for RMS, descriptors, onset strength, HPSS, chroma, YIN, tempo. **Never embedded in the app.** Not cloned — used via Python at fixture-generation time. |
| libebur128 | MIT | **ORACLE.** BS.1770 / EBU R128 reference values. Runtime meter implemented allocation-free in Kotlin; vendor the C only if benchmarks justify it. Not cloned. |
| PFFFT | BSD-like | **BENCHMARK.** Compared against JTransforms on arm64. Adopt only for a material latency or battery win, target ≥2× FFT speedup. Not cloned. |

## 5. Study only — nothing derived enters the tree

| Source | Commit | Licence | Why study-only |
|---|---|---|---|
| [jberg/butterchurn](https://github.com/jberg/butterchurn) | `fbac2f6` (2026-04-19) | MIT | Permissive, but a second MilkDrop runtime duplicates projectM. **And the product ships no web technology** — no WebView, no JS, no WebGL runtime, by explicit decision. Study its feedback and preset-transition behaviour only. |
| [sqrew/rdpe](https://github.com/sqrew/rdpe) | `28db17f` (2025-12-29) | MIT *declared*, **LICENSE file absent** | Long-term reference for typed simulation rules, structure-of-arrays state, GPU-only updates and spatial hashing. `Cargo.toml` declares `license = "MIT"` and the README badge links to a `LICENSE` that **does not exist in the repository**. Treat as study-only until the text is present. Do not make its wgpu rule compiler a dependency. |

## 6. Excluded

| Source | Licence | Status |
|---|---|---|
| [rreusser/sketches](https://github.com/rreusser/sketches) | **NONE** — pinned `82eb3ce` (2026-03-11) | **EXCLUDED.** Earlier research recommended this as "MIT". It has no LICENSE file, no `package.json` licence field and no README licence section — therefore all rights reserved. Published mathematics may be learned from; **no code or shader text may be copied.** |
| `BaryonOfficial/Baryon` | PolyForm Strict 1.0.0 | **EXCLUDED** from reuse. Commercial licence required. Concepts only. |
| `adityarajashekaran/orphic`, `elder-plinius/ENTHEA` | AGPL-3.0 | **EXCLUDED.** Scene-design documents only. |
| `mardous/BoomingMusic`, `OxygenCobalt/Auxio` | GPL-3.0 | **EXCLUDED.** Feature reference only. |
| Shadertoy, LYGIA, and ambiguously licensed shader collections | Various / non-commercial / unstated | **EXCLUDED.** Shadertoy's default is all-rights-reserved unless the author states otherwise; per-shader provenance review would be required and is not worth it. |

### A caveat on Colourful-Attraction

It is genuinely MIT and its GLSL is the closest published match to the morphing-vector-field
idea. It also has **zero stars**, and its GitHub description terminates mid-sentence in what
appears to be a leaked authoring prompt (`…ribes the content specifications for how I want
you to make the readme`). Read and verify its shader maths; do not treat it as
battle-tested.

---

## 7. Obligations this creates

- **Apache-2.0 (SwissGL, fosfora)** requires the licence text, the copyright notice, a
  NOTICE file if one is present upstream, and a statement of changes in modified files.
  Every adapted file gets `// SPDX-License-Identifier: Apache-2.0` plus an origin and
  modification comment.
- **MIT / BSD-2 / Unlicense** require the copyright line and licence text to travel with
  any copied portion.
- **LGPL-2.1 (projectM)** requires dynamic linking (satisfied), the licence notice
  (satisfied), and the corresponding source of the *modified* library. The FBO backport
  patch and the full build recipe are in `tools/`; the CI workflow that produces the
  binaries is `.github/workflows/native-libs.yml`.

**Known gap:** `checkThirdPartyNotices` is registered in `app/build.gradle.kts` and wired
into `:app:check`. It is `:app`-scoped. Anything adapted into a new engine package is
covered only while the engine lives inside `:app`. [MASTER_PLAN.md](MASTER_PLAN.md) §4.1
extracts six engine modules at V2-1-02, so this gap stops being hypothetical then: **the
notice task must move into the convention plugin in that same commit, or Apache-2.0
attribution silently stops being enforced.** §3.3 says the same about
`checkEngineProvenance` — it scans every module, not only `:app`.
