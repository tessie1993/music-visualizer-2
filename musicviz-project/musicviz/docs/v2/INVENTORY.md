# MusicViz 2.0 Inventory

Owners, entry points, outputs, durable formats and gates as they exist in
**code** at the Phase 0 baseline. Derived by reading source and tests, not by
copying review prose.

Baseline SHA: `faafe8f` (purge commit), on top of `5ceef8f` / `05aca01`.

## Scale

| Measure | Value |
|---|---|
| Main Kotlin | 51,153 lines |
| Test Kotlin | 27,449 lines |
| Top-level packages | `analysis`, `audio`, `data`, `export`, `playback`, `render`, `ui`, `wallpaper` (+ `MusicVizApp.kt`, `RingLog.kt`) |

### Corrections to the master plan's audit table

The plan says to re-check every claim against the execution SHA. Three differ:

| Plan claim | Actual at baseline |
|---|---|
| `PlayerViewModel` is "roughly 4,000 lines" | **2,518 lines** |
| `VisualizerRenderer` is "a ~1,690-line owner" | **1,651 lines** |
| `SceneParams` is "a flat ~169-field data class" | **181** `val`/`var` declarations across 521 lines |

The *shape* of each claim holds — these are still the God-classes the plan
targets — but the numbers are stale. No plan decision depends on the exact
counts, so this is recorded here rather than raised as an ADR.

## Largest main-source files

| File | Lines | Role |
|---|---|---|
| `ui/PlayerViewModel.kt` | 2,518 | Multi-domain UI façade |
| `ui/theme/ThemePackCatalog.kt` | 2,026 | Ten crystal/mineral packs |
| `render/VisualizerRenderer.kt` | 1,651 | Scene registry, clocks, transitions, layers, params, FBOs, safety, projectM, export factories |
| `render/scene/HyperspaceMath.kt` | 1,581 | Hyperspace formulas, five-act journey |
| `ui/VisualsHub.kt` | 1,459 | Preset/style browser |
| `ui/CustomizeTabs.kt` | 1,450 | Parameter surface |
| `export/VideoExporter.kt` | 892 | Encoder + its own scene/EGL loop |
| `render/scene/CymaticsMath.kt` | 820 | Modal/resonator math |

## Audio chain and transport

| Owner | File | Lines | Note |
|---|---|---|---|
| PCM tap | `audio/PcmTapSink` (via `TapRenderersFactory`) | — | **First** in the processor chain; pre-user-DSP. Binding invariant. |
| Ring buffer | `audio/PcmRingBuffer.kt` | 170 | Lock-free single-writer mid/side; latest-window snapshots; **no sample timestamp or source epoch** |
| Demand | `audio/AudioBus.kt` | 82 | `addConsumer()`/`removeConsumer()` integer counter + a single `onInterestChanged` callback slot. Confirmed exactly as the plan describes. |
| Capture | `audio/MicCapture.kt` (193), `PlaybackCapture.kt` (210), `AudioCapturePump.kt` (199), `PlaybackCaptureService.kt` (250) | — | Explicit-consent sources |
| Audio FX | `audio/AudioFxController.kt` | 294 | User DSP; downstream of the tap |

## Analysis

| Owner | File | Lines | Note |
|---|---|---|---|
| Live scheduler | `analysis/AnalysisEngine.kt` | 147 | Wakes ~16 ms by wall clock, snapshots latest 2048 samples. Can double-analyze or skip. |
| Features | `analysis/FeatureExtractor.kt` | 424 | 64 bands, waveform, beat/rhythm/stereo |
| Public type | `analysis/AudioFeatures.kt` | 154 | Flat feature carrier |
| Rhythm | `analysis/PulseTracker.kt` (553), `DrumChannels.kt` (273) | — | Beat/pulse + transient channels |
| Tonal | `analysis/Chromagram.kt` | 206 | Twelve-bin chroma |
| Offline | `analysis/OfflineAnalyzer.kt` | 285 | `StreamingPipeline` duplicates live setup/timing |
| Cache | `analysis/AnalysisCache.kt` | 262 | Schema v2 |
| Timeline | `analysis/FeatureTimeline.kt` | 295 | — |

Total `analysis/`: 3,767 lines.

## Render

Scene package (`render/scene/`) holds 27 files. CPU particle family:
`ParticleSceneBase.kt` plus `AttractorScene`, `SwarmScene`, `OrbitScene`,
`GalaxyScene`, `FountainScene`, `BurstScene`, `StormScene`, `NebulaScene`,
`InkflowScene` — nine subclasses that simulate on CPU and upload vertex arrays
per frame. `BeamScene` and `ShaderScene` are wrappers.

Preserved math: `HyperspaceMath.kt`, `CymaticsMath.kt`. Fluid:
`render/fluid/FluidSim.kt` (655) plus format probes in `FluidBuffers`.

projectM: `render/scene/ProjectMScene.kt` (346), `PMBridge.kt` (62), native
`libprojectM-4.so` (12.4 MB, arm64-v8a only) + `libprojectmjni.so`.

## Safety

`render/VisualSafety.kt` clamps parameters. **`GuiPrefs.safeVisuals` defaults to
`false`** (`ui/AppTheme.kt:188`), and the persisted default matches — so a
9 Hz full-frame strobe is reachable today. This is the Phase 0.3 P0.
`reducedMotion` is already modelled as independent of `safeVisuals`
(`AppTheme.kt:195`), which the plan requires.

## Durable formats

`data/PresetStore.kt` (649), `TextureStore.kt`, `TrackLibrary.kt`,
`MusicPlaylistStore.kt`, `HistoryStore.kt`, `LfoStore.kt`,
`AutoVisualsPrefsStore.kt`, `PerformanceTake.kt`, `AnalysisCache` (v2).
Existing stores already use atomic rename + fsync with corrupt-file quarantine —
this is stronger than the plan assumes and should be preserved, not rebuilt.

## Native packaging

`abiFilters += "arm64-v8a"` is set at the **app** level
(`app/build.gradle.kts:59-60`), not scoped to the projectM library. Consequences:
no `x86_64` build, so the app cannot run on an emulator and CI cannot do
device-free visual checks. `jniLibs.useLegacyPackaging = false` requests
uncompressed/aligned `.so`; per plan §11.2 that flag is **not** evidence of
16 KB compliance.

`jniLibs/arm64-v8a/SHA256SUMS` verifies against the committed blobs, but its own
header records that they predate provenance tracking and that `tools/pm_jni.c`
has been hardened since. The shipped binary therefore does not correspond to
current JNI source — see `RETIREMENT_LEDGER.md` and `docs/RESEARCH_AUDIT.md`.

## Shipped assets

`app/src/main/res` carries **930 PNGs totalling ~290 MB** — the
`tp_<mineral>_*` theme-pack tiles, bottom sheets and masters, plus `.webp`
ambient backdrops. Individual files reach 1.2 MB
(`tp_amethyst_bottom_sheet_focused.png`) and 3.1 MB
(`tp_kyanite_material_master.png`).

`docs/VISUAL_STYLE_RESEARCH.md` previously claimed no texture assets were
shipped. That was true when written and stopped being true when the packs' PNGs
landed in PR #95; the doc is corrected. These assets are source data, so
minification cannot remove them — the release-size consequence needs measuring
against `bundleRelease` (currently `NOT RUN`) before 2.0 ships. Crystal-pack
identity is a product promise the plan protects, so this is a size/format
question, not a licence to delete the packs.

## Source-text gates

`app/src/test/java/dev/musicviz/ParamSurface.kt`, `ParamSurface*`, and
`ParamMatrix.kt` parse the main source tree as text and hard-code paths.
`CustomizeSurfaceTest.the_param_matrix_document_is_current` regenerates
`docs/PARAM_MATRIX.md` — never hand-edit it. `SharedShaderPreludeTest` and
`CompositeUniformParityTest` pin duplicated shader/uniform writers.

Moving or renaming a main-source file requires updating these in the same change.

## Retained non-Claude tooling

`.codex/AGENTS.md` and `.agents/skills/music-visualizer-2/` are Codex-specific
and have **no authority** over this implementation. Left untouched; changing
them is a separately-scoped tooling decision.

## Allowlist explanations for E.4 search hits

The E.4 searches return matches only in:

- `docs/v2/MASTER_PLAN.md` — the plan describing the purge it ordered;
- `docs/v2/QUALITY_GATES.md` — recording which claims were dropped;
- `.claude/skills/music-visualizer-2/SKILL.md` — the prohibition list itself.

All three are the harness naming absent tooling in order to forbid it. No
remaining file instructs Claude to use it.
