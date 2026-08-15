# Milestone 0 — frozen baseline

[ENGINE_V2_PLAN.md](ENGINE_V2_PLAN.md) §10 Milestone 0 required the baseline to be captured
before any V2 work starts — section numbers here are that document's, not
[MASTER_PLAN.md](MASTER_PLAN.md)'s. The measurements below stand either way:
the repository commit, bundled presets, shader inventory, and a catalogue of the
source-text tests and identifiers that need temporary compatibility.

Everything below was measured on the tree, not estimated.

**Frozen at:** `bd1aa2c` on `claude/audio-visualizer-research-d8d92d`, whose engine content
is identical to `05aca01` (PR #95). Measured 2026-08-12.

---

## 1. Size and shape

| Metric | Value |
|---|---|
| Main Kotlin | **179 files, 51,153 LOC** |
| Unit tests | **165 files, 27,449 LOC** |
| GLSL shaders in `res/raw` | **65 files** |
| Distinct `R.raw.*` references in Kotlin | **97** |
| Registered scene ids (`SceneIds`) | **38** |
| `SceneParams` fields | **165** |
| Distinct preset keys in `PresetStore` | **164** |
| Bundled presets (`BuiltInPresets`) | **19** |
| Gradle modules | **1** (`include(":app")`) |
| Dependency-injection framework | **none** |
| ViewModels | **1** — `PlayerViewModel`, 2,518 LOC |

## 2. The GL ceiling

`AndroidManifest.xml:4` declares `android:glEsVersion="0x00030000"`. `GLES30` is the only
GL import in the tree — no `GLES31`, no compute shader, no SSBO. `minSdk = 26`,
`targetSdk = 36`, `compileSdk = 36`, `abiFilters += "arm64-v8a"`.

`VisualizerView` sets `setEGLContextClientVersion(3)` and `RENDERMODE_CONTINUOUSLY`, and
deliberately does **not** call `setPreserveEGLContextOnPause(true)` — preserved-context
resume is a known source of device-specific GL hangs. Every scene must therefore rebuild
all GL resources in `init()`.

**Nothing currently enforces the 3.0 ceiling.** There is no test asserting it. Adding one
is milestone-0 work.

## 3. Source-text test catalogue

These parse the main tree as text. They are invisible to any new Gradle module, which is
both the migration escape hatch and the risk: escaping a gate silently discards what it
encodes, and in one case actively disarms it.

| Measure | Count |
|---|---|
| Test files containing the literal `src/main` | 39 |
| Test files using `ParamSurface.*` | 16 |
| Test files using `readText()` | 47 |
| **Union — test files reading main source** | **57** |
| Test files defining their own `private fun repoFile` | **18** |
| Test files reading `src/main/res` or `src/main/assets` | **11** |

An earlier draft of the V2 plan said "39 gates" and "three copies of `repoFile`". Both were
wrong. Collapsing the 18 `repoFile` copies to one shared helper is prerequisite work.

### 3.1 Paths pinned by `ParamSurface.FAMILIES`

`ParamSurface.moduleRoot` locates the project by walking up until it finds
`app/src/main/java/dev/musicviz/render/scene/SceneParams.kt`. **If that file moves, every
`ParamSurface`-based test dies with `"musicviz project root not found"`.**

`FAMILIES` then hard-codes 15 paths across 11 families:

| Family | Pinned paths |
|---|---|
| Shader | `render/scene/ShaderScene.kt` |
| Particle | `render/scene/ParticleSceneBase.kt`, `NebulaScene.kt`, `BurstScene.kt`, `SwarmScene.kt`, `FountainScene.kt`, `OrbitScene.kt` |
| MilkDrop | `render/scene/ProjectMScene.kt` |
| Fluid | `render/fluid/FluidScene.kt` |
| Curl Flow | `render/fluid/CurlFlowScene.kt` |
| Water | `render/fluid/WaterScene.kt` |
| Cymatics | `render/scene/CymaticsScene.kt` |
| Beam | `render/scene/BeamScene.kt` |
| Hyperspace | `render/scene/HyperspaceScene.kt` |
| Composite | `render/VisualizerRenderer.kt`, `render/CompositeGrade.kt` |
| Export | `export/FxCompositor.kt`, `export/VideoExporter.kt` |

**Note the Particle family lists only 6 of the 9 scenes that extend `ParticleSceneBase`.**
Galaxy, Attractor, Storm and Inkflow were never registered, so the param-coverage matrix
has been blind to four scenes.

### 3.2 Identifiers requiring temporary compatibility

| Identifier / path | Why it must survive |
|---|---|
| `render/scene/SceneParams.kt` | Anchors `ParamSurface.moduleRoot`. Delete last, even as a shim. |
| `render/scene/CymaticsScene.kt` | `FAMILIES`, `CymaticsClockSafetyTest`, `CymaticsStyleIdentityTest` |
| `render/VisualizerRenderer.kt` | `FAMILIES`, `RendererWiringTest`, `CompositeUniformParityTest`, `RenderClockWrapTest` |
| `render/CompositeGrade.kt` | `FAMILIES` "Composite" |
| `export/FxCompositor.kt`, `export/VideoExporter.kt` | `FAMILIES` "Export", `CompositeUniformParityTest`, `ExportDeterministicQualityTest` |
| `render/scene/BeamScene.kt` + `beam_vert.glsl`/`beam_frag.glsl` | `SceneUniformParityTest` |
| `audio/TapRenderersFactory.kt` | `AudioChainContractTest` |
| `ui/EnginePlumbing.kt` | `EnginePlumbingCoverageTest` |
| `SceneIds` constants for deleted styles | Preset `sceneId` remapping has nothing to key on otherwise |

### 3.3 One gate that breaks by going *quiet*

`AudioChainContractTest` proves tap-first by string index inside a single file:
`tapAt = factory.indexOf("TeeAudioProcessor(")`, then for each of seven DSP stage names it
asserts `at > tapAt` **only if `at >= 0`**. If tap construction and the DSP stages ever
live in different files or modules, the loop matches nothing, every guard is false, and
**the test passes unconditionally forever.**

It must be replaced by a runtime assertion — instantiate the real `RenderersFactory` and
check the tap's index in the constructed `AudioProcessor[]` — in the same commit that moves
the tap. Not afterwards.

## 4. Tree-wide gates that constrain any new code

These walk the whole main tree rather than named files, so new engine packages inside
`:app` inherit them immediately:

- **`RenderTargetOwnershipTest`** — every `glGenFramebuffers` must be in the allowlist or
  go through `RenderTarget`. A new GPU engine allocating its own FBOs fails at once.
  `release()` (live context) and `forget()` (dead context) are not interchangeable.
- **`SceneFailureTest`** — every scene's `init` must be exception-safe and every `draw`
  must self-guard within 3 code lines. A throw takes down the whole visualizer, because
  all scenes are constructed and `init`-ed before the user picks one.
- **`BackupRulesCoverageTest`** — every `File(context.filesDir, …)` needs a declared backup
  decision in both XML rule files. A new store that ships backed-up by default can blow
  the ~25 MB Auto Backup quota and silently kill all backup.
- **`PersistableUriGuardTest`** — every `takePersistableUriPermission` inside `runCatching`.
- **`HotPathReuseTest`** — preallocated buffer reuse must equal the allocating form
  value-for-value.
- **`SharedShaderPreludeTest`, `ShaderSamplerPrecisionTest`** — walk `res/raw`.

## 5. Known live/export divergences at baseline

Four, none covered by a test. These are the measurable justification for the rebuild, and
closing them is Milestone 1's exit criterion.

| # | Divergence | Effect |
|---|---|---|
| 1 | Live analysis is a 62.5 Hz wall-clock loop using `snapshotLatest` ("newest window"); offline is 60 Hz sample-locked | Different hop *and* alignment; under load live silently drops or repeats audio |
| 2 | `OfflineAnalyzer` downmixes to mono and never constructs `StereoField` | **`stereoWidth` is 0 in every exported video** |
| 3 | Offline computes whole-track key but never per-frame chroma | **Every harmony-driven visual is dead in exports** |
| 4 | Live box-averages the waveform (deliberately, to stop hi-hats aliasing into shimmer); offline point-samples | Scope scenes differ between screen and file |

## 6. Not yet captured

Milestone 0 also calls for recorded runtime baselines. These need a device and are **not
done**:

- [ ] Live and export golden frames per scene
- [ ] Memory, steady-state allocation counts
- [ ] CPU analysis cost (target: mean < 3 ms, p95 < 6 ms on the mid-tier reference device)
- [ ] GPU frame time on one Adreno and one Mali
- [ ] Scatter-deposit throughput at 8k / 32k / 128k agents, linear vs log-packed
      accumulation, R16F vs RGBA8 — this sets the deposit format, particle budget and
      simulation-resolution multiplier, all of which are currently extrapolated from a
      desktop browser rather than measured

Exit criteria for Milestone 0 are met only when the above are reproducible.
