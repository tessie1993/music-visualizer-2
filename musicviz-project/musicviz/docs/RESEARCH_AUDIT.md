# Audio & visualizer overhaul — source audit

Status: **verification pass complete; synthesis not started.**

Three earlier research rounds proposed an overhaul of the audio and visual
architecture and named roughly sixty external projects as references. None of
that output was ever committed, so this document exists to do two things and
only two things:

1. Record which of those references **actually exist**, under which licence.
2. Record the state of the in-tree MilkDrop/libprojectM path, which the
   earlier rounds proposed deleting.

Nothing here is an architecture decision. Where an earlier round made a
recommendation that rested on a source that failed verification, the
recommendation is marked withdrawn rather than quietly restated.

## Why this pass happened

The earlier rounds were conducted without fetching the repositories they
named. A verification pass was queued and never ran. This document is that
pass, run by fetching each project directly.

**Result: 7 of the named references could not be found at all, including the
single most load-bearing one.** Details below. Treat any unverified claim
from the earlier rounds as unreliable until it appears in the confirmed table.

## Confirmed, permissively licensed

These were fetched and their licence read. All are usable under the
MIT/Apache-2.0-only policy.

| Project | Repo | Licence | Why it matters here |
|---|---|---|---|
| SwissGL | `paradigms-of-intelligence/swissgl` | Apache-2.0 | <1k-line WebGL2 wrapper; ping-pong texture simulation kernels. The closest thing to a model for GLES 3.0 simulation passes without compute shaders. |
| ShaderEditor | `markusfisch/ShaderEditor` | MIT | Android GLES plumbing and live-wallpaper lifecycle. **No audio input** — see correction below. |
| Meyda | `meyda/meyda` | MIT | Audio feature extraction reference (spectral centroid, zero-crossing, MFCC). JS/Web Audio only — reference, not a dependency. |
| Clubber | `wizgrav/clubber` | MIT | Band→shader-uniform modulator mapping. |
| audioFlux | `libAudioFlux/audioFlux` | MIT | C library, **explicitly supports Android**. The only verified feature-extraction library that could actually ship in this app. |
| Kymatik | `xsoophx/Kymatik` | MIT | **Kotlin/JVM** FFT + comb-filter BPM detection. Directly readable from this codebase's language. Android use is plausible but undocumented upstream. |
| CAVA | `karlstav/cava` | MIT | FFTW band mapping, smoothing and sensitivity behaviour for bar-type visuals. |
| Audio Shader Studio | `sandner-art/Audio-Shader-Studio` | MIT | Audio-feature → fragment-shader uniform wiring. |
| Wavefield | `niko-dellic/wavefield` | MIT | Chladni-style modal patterns via frequency analysis + modal interpolation. Real, and the closest verified match to the proposed cymatics work. |
| WebGL-Fluid-Simulation | `PavelDoGreat/WebGL-Fluid-Simulation` | MIT | Navier-Stokes GPU fluid, per GPU Gems. Already conceptually mirrored by the in-tree fluid scenes. |
| Lenia | `Chakazul/Lenia` | MIT | Continuous cellular automata (Chan, 2018). |
| Physarum | `fogleman/physarum` | MIT | Go implementation of Jones (2010) transport networks; the basis of the Sage Jenson "36 Points" work. Algorithm is simple enough to port. |
| Threelab | `jonradoff/threelab` | MIT | Pattern/parameter-schema architecture. React + Three.js + Go — architectural reference only, nothing portable. |
| projectM | `projectM-visualizer/projectm` | LGPL-2.1 | Already vendored in-tree. See the MilkDrop section. |

## Confirmed, but licence-blocked

These exist and are genuinely relevant, but **cannot be used** — not as
vendored code, and not as copied shader source.

| Project | Repo | Licence | Consequence |
|---|---|---|---|
| Baryon | `BaryonOfficial/Baryon` | PolyForm Strict 1.0.0 + separate commercial licence | Non-commercial only. Cannot ship. WebGPU volumetric raymarch cymatics — relevant, unusable. |
| ENTHEA | `elder-plinius/ENTHEA` | AGPL-3.0 | Copyleft; incompatible with shipping this app closed. WebGL2, 29 GLSL modes, predictive whole-waveform analysis. |
| BoomingMusic | `mardous/BoomingMusic` | GPL-3.0 | Feature reference only. Do not copy code. |

Reading these for ideas is fine. Copying from them is not, and the earlier
rounds listed Baryon and ENTHEA without flagging either licence.

## Not found — treat as fabricated

Each of these was searched by name and by description. None resolved to a
real project.

| Named reference | Claimed role in earlier rounds |
|---|---|
| **Fosfora** | Round 2's **top reference**: "49 effects, 139 WGSL shaders, audio intelligence", and the stated basis for the normalized `AudioFrame` contract. |
| **Velo Visualiser** | Round 1's top reference. |
| **Colourful Attraction** | Round 1 top-tier particle reference. |
| **RDPE** | Round 2's proposed "rule system" model. |
| **ORPHIC** | Listed as an audio-reactive reference. |
| **Musicya** | Listed as a player reference. |
| **Kiln** | Listed as a "clean-room" player reference. |

The Fosfora entry matters most. The earlier implementation-stack
recommendation — "Media3 + Oboe, JTransforms FFT, custom AudioProcessor,
**Fosfora-style normalized audio frame**" — cited it as the model for the
audio frame contract. That model does not exist, so the audio-frame spec has
no external basis and must be designed from the codebase's own
`AudioFeatures` plus the verified references above.

## Corrections to earlier rounds

- **SwissGL is not a Google repository.** It is `paradigms-of-intelligence/swissgl`.
- **ShaderEditor has no audio input.** Its documented inputs are camera,
  accelerometer, gyroscope, magnetic field, light, pressure, proximity and
  battery. It is a GLES/wallpaper-lifecycle reference only.
- **Wavefield and Threelab are real** but were described imprecisely; both are
  web stacks, so neither offers portable code for an Android GLES target.

## MilkDrop / libprojectM: audit

The earlier rounds scoped this as "fully delete and deep-audit before
rebuild". **That recommendation is not supported by the code.** The
integration is careful, documented, and works. The real problems are
narrower.

What is in-tree:

- `app/src/main/jniLibs/arm64-v8a/libprojectM-4.so` (12.4 MB) and
  `libprojectmjni.so` (12 KB), with a `SHA256SUMS` provenance file.
  **Both checksums verify against the committed blobs.**
- `tools/pm_jni.c` — the JNI bridge source.
- `tools/projectm-v417-render-fbo-backport.patch` — projectM 4.1.7 renders to
  the default framebuffer; this backports `projectm_opengl_render_frame_fbo`
  so the engine can render into a scene-owned texture.
- `render/scene/ProjectMScene.kt` (346 lines) and `PMBridge.kt` (62 lines).
- LGPL-2.1 compliance is handled: dynamic linking, plus a
  `syncThirdPartyNotices` Gradle task and a `checkThirdPartyNotices` CI check
  that fails the build if the in-app notice drifts from `THIRD_PARTY_NOTICES`.

Quality of the Kotlin side is high. Shader-compile failure degrades the style
to "unavailable" with a user-visible reason rather than crashing the GL thread
or rendering silent black; GL state is explicitly reset after the native pass
because preset pipelines leave scissor/mask/blend-equation dirty; uniform
locations are cached per link; preset loads are debounced on the GL thread with
file I/O kept off it. The two most surprising design choices — why "Audio
drive" is deliberately unwired, and why the palette can only tint a `.milk`
preset — both carry long comments explaining the reasoning.

### Findings

1. **The shipped binary does not correspond to current JNI source.**
   `SHA256SUMS` states plainly: *"Build run: unknown (these blobs predate
   provenance tracking; `tools/pm_jni.c` has been hardened since — rebuild via
   `native-libs.yml` at the next release)."* The checksums prove the committed
   blobs are unmodified; they do **not** prove the blobs were built from the
   current source. The hardening in `pm_jni.c` is not in the shipped `.so`.
   This is the one finding that should block a release. Fix: dispatch
   `native-libs.yml` and commit the rebuilt pair with its provenance header.

2. **The whole app is arm64-only.** `abiFilters += "arm64-v8a"` is set at the
   app level, not scoped to the native library. This is defensible for phones,
   but it drops `x86_64` — which means **the app cannot run on an Android
   emulator**, and CI cannot do device-free visual checks. For a visual app
   that is a meaningful testing gap, and it is a consequence of the MilkDrop
   dependency that no document currently states.

3. **12.4 MB of the APK is libprojectM.** Worth stating explicitly in any
   decision about whether the style earns its place.

4. `nativeGetLastError()` is called every frame in `draw()` (a JNI round-trip
   per frame, returning null in the normal case). Minor; note it, don't chase
   it yet.

### Recommendation

**Do not delete this.** Rebuild the native pair from current source and
re-verify. If MilkDrop is later dropped, drop it for the size and ABI cost —
which are real and quantified above — not because the integration is broken,
because it is not. Butterchurn is a web/WebGL reimplementation and is not a
drop-in for an Android GLES target; it does not follow from this audit that it
would replace libprojectM.

## Still open

Unchanged by this pass, and still blocking synthesis:

- **Audio frame spec** — now with no external model, per the Fosfora finding.
  Must be derived from the in-tree `AudioFeatures` (which already carries
  chroma, pulse tracking and beat confidence) plus Meyda/audioFlux/Kymatik.
- **GLES 3.0 → 3.1 pathway** — the renderer is GLES 3.0 only, so simulation
  scenes need ping-pong textures; SwissGL is the verified model.
- **Scene roster** — the nine proposed engines were justified partly by
  sources that do not exist. Re-derive from the confirmed set before
  committing to a roster.
- **Implementation order** — deferred until the roster is re-derived.

## Verification method

Each project was fetched directly and its licence read from the repository,
in August 2026. Projects recorded as not found were searched by name and by
their claimed distinguishing features. Absence of a search result is weaker
evidence than a fetched licence file, so a "not found" entry means the name
could not be substantiated — not that no such code could exist anywhere.
