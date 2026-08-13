# MusicViz 2.0 Retirement Ledger

Allowed dispositions: `KEEP`, `ADAPT`, `BRIDGE`, `REPLACE`,
`DELETE_AFTER_GATE`, `DELETE_NOW`, `UNKNOWN`.

Rules (from `MASTER_PLAN.md` Appendix H):

1. `UNKNOWN` blocks production edits in that responsibility.
2. `REPLACE`/`DELETE_AFTER_GATE` code is frozen by H0.5 — characterization, a
   narrow bridge, or a correctness fix only.
3. A bridge needs a named consumer, a removal phase, and an architecture test.
   "Maybe useful later" is not a bridge.
4. Before deletion, attach `rg` references, durable-data impact, test migration
   and rollback notes.
5. After deletion, prove one authoritative owner remains.

Last verified SHA for every row below: `faafe8f`.

## Analysis

| Path/type | Current responsibility | Disposition | V2 owner | Bridge allowed | Characterization tests | Delete gate |
|---|---|---|---|---|---|---|
| `analysis/AnalysisEngine.kt` | Wall-clock live analysis scheduler | `DELETE_AFTER_GATE` | `analysis.v2.AnalysisCore` + coordinator | Legacy feature adapter only | Phase 3.1 fixtures | Phase 3 live/offline parity + zero production refs |
| `analysis/AudioFeatures.kt` | Flat public feature carrier | `REPLACE` | `analysis.v2.AnalysisFrameView` | Adapter at the v2→legacy boundary | Phase 3.1 | Phase 10.1 |
| `analysis/FeatureExtractor.kt` | 64 bands, waveform, beat/rhythm/stereo | `ADAPT` | `analysis.v2` internal stages | n/a | Phase 3.1, marked `PRESERVE`/`FIX` per feature | Phase 10.1 for the scheduling half |
| `analysis/PulseTracker.kt` | Beat pulse/BPM/confidence | `ADAPT` | `analysis.v2` rhythm layer | n/a | Phase 3.3 | Musical behavior preserved behind fixtures |
| `analysis/DrumChannels.kt` | Kick/snare/hat transients | `ADAPT` | `analysis.v2` rhythm layer | n/a | Phase 3.3 | — |
| `analysis/Chromagram.kt` | Twelve-bin chroma | `ADAPT` | `analysis.v2` tonal layer | n/a | Phase 3.3 | — |
| `analysis/OfflineAnalyzer.kt` | Duplicated offline pipeline | `REPLACE` | `analysis.v2.OfflineAnalysisRunner` over the same core | n/a | Phase 3.5 parity | Phase 3 exit |
| `analysis/AnalysisCache.kt` (v2 schema) | Cache runtime | `REPLACE` | `AnalysisCacheV3` | Read-only v2 reader if semantics prove trustworthy | Phase 3.5 corruption tests | Phase 3 exit |
| `analysis/FeatureTimeline.kt` | Timeline accumulation | `UNKNOWN` | — | — | — | **Blocks edits here until Phase 3.1 classifies it** |
| `analysis/SceneSuggester.kt`, `LiveInputProfile.kt` | Auto-visual heuristics | `UNKNOWN` | — | — | — | **Blocks edits until classified** |

## Audio and transport

| Path/type | Current responsibility | Disposition | V2 owner | Bridge allowed | Delete gate |
|---|---|---|---|---|---|
| `audio/AudioBus.kt` | Integer consumer count + one callback slot | `DELETE_AFTER_GATE` | `engine.ConsumerLease` registry | `LegacyAudioBusAdapter` while unmigrated callers exist | Phase 1.2: zero production `addConsumer`/`removeConsumer` |
| `audio/PcmRingBuffer.kt` | Lock-free mid/side ring | `ADAPT` | `audio.v2.PcmTransport` | n/a | Concept survives; latest-window polling replaced by cursors (Phase 2.3) |
| `audio/PcmTapSink` / `TapRenderersFactory` | Pre-DSP tap, first in chain | `KEEP` | same | n/a | **Binding invariant.** Extended with metadata only. |
| `audio/MicCapture.kt`, `PlaybackCapture.kt`, `AudioCapturePump.kt`, `PlaybackCaptureService.kt` | Explicit-consent sources | `KEEP` | Source coordinators (Phase 1.3) | n/a | Ownership moves out of `PlayerViewModel`; capture logic itself stays |
| `audio/AudioFxController.kt` | User DSP | `KEEP` | same | n/a | Outside the overhaul boundary |

## Render

| Path/type | Current responsibility | Disposition | V2 owner | Bridge allowed | Delete gate |
|---|---|---|---|---|---|
| `render/VisualizerRenderer.kt` (1,651 lines) | Scene registry, clocks, transitions, layers, params, FBOs, safety, projectM, export factories | `REPLACE` | `render.v2.FrameRunner` + graph + pool + output adapters | `LegacySceneAdapter` during migration | Phase 10.2: every style and output on V2 |
| `render/scene/Scene.kt` | Legacy scene interface | `REPLACE` | `render.v2.scene.SceneDefinition`/`SceneInstance` | `LegacySceneAdapter` | Phase 10.2 |
| `render/scene/ParticleSceneBase.kt` + 9 CPU subclasses (`Attractor`, `Swarm`, `Orbit`, `Galaxy`, `Fountain`, `Burst`, `Storm`, `Nebula`, `Inkflow`) | CPU simulation + per-frame vertex upload | `DELETE_AFTER_GATE` | `render.v2.sim` GPU-resident state | None — concepts migrate, not the hierarchy | Phase 5 exit: all style IDs resolve to V2; zero live refs. Deleted in Phase 10.2 |
| `render/FlowField` readback path | Synchronous `glReadPixels` advection | `DELETE_AFTER_GATE` | GPU texture sampling in sim shaders | None | Phase 5.2 + the no-readback architecture gate |
| `render/scene/SceneParams.kt` (181 fields) + reflective `lerpParams` | Flat per-frame params | `REPLACE` | `render.v2.params` typed schemas + compiled modulation | Compatibility read for preset migration | Phase 10.3 |
| `render/scene/HyperspaceMath.kt` (1,581) | Hyperspace formulas, five-act journey | `KEEP` | Imported by Hyperspace V2 | n/a | Preserve ten authored variants + IDs (Phase 6.1) |
| `render/scene/CymaticsMath.kt` (820) | Modal/resonator math | `KEEP` | Imported by Cymatic Matter V2 | n/a | Preserve ten authored variants + IDs (Phase 6.2) |
| `render/fluid/FluidSim.kt`, `FluidBuffers` format probes | Fluid math + empirical capability probes | `ADAPT` | `render.v2` resource pool + `GpuCapabilities` | n/a | Probes are the reliable part — adapt, don't re-derive (Phase 4.1) |
| `render/scene/ProjectMScene.kt`, `PMBridge.kt` | MilkDrop adapter over LGPL native | `KEEP` | Special scene adapter into the common graph (Phase 6.7) | n/a | **Dynamic-link boundary and notices are non-negotiable.** See the native row below. |
| `render/scene/BeamScene.kt` | Beam wrapper | `UNKNOWN` | — | — | Phase 5.3 decides: particle mode or Phase/Scope member |
| `render/scene/ShaderScene.kt` | User shader wrapper | `ADAPT` | V2 user-shader adapter (Phase 6.7) | n/a | Keep validation + last-known-good |
| `render/VisualSafety.kt` | Parameter clamps | `ADAPT` | First layer under `render.v2.SafetyPass` | n/a | Phase 4.3 adds the final-frame guard; clamps stay |

## Export, takes, outputs

| Path/type | Current responsibility | Disposition | V2 owner | Delete gate |
|---|---|---|---|---|
| `export/VideoExporter.kt` (892) — **visual half** | Own scene/EGL loop | `REPLACE` | `FrameRunner` via `EncoderOutput` | Phase 8.3 |
| `export/VideoExporter.kt` — **codec/EGL/mux half** | Encoder, muxing, MediaStore finalization | `KEEP` | Retained expertise | — |
| `export/FxCompositor.kt` | Duplicate composite | `DELETE_AFTER_GATE` | Shared graph | Phase 10.3; `CompositeUniformParityTest` narrows to the legacy pair, then goes |
| `data/PerformanceTake.kt` | Wall-clock deltas + sparse snapshots | `REPLACE` | `take.v2` media-time event timeline | Phase 8.1, with migration or a truthful "cannot guarantee deterministic export" notice |
| `wallpaper/VisualizerWallpaperService.kt` | Renders while visible; settings read once at start | `ADAPT` | `output.WallpaperOutput` | Phase 8.4 |

## UI

| Path/type | Disposition | Note |
|---|---|---|
| `ui/PlayerViewModel.kt` (2,518) | `ADAPT` | Shrink one domain per slice. Façade until consumers migrate — no big-bang rewrite. |
| `ui/theme/ThemePackCatalog.kt` (2,026) + crystal packs | `KEEP` | Product identity. Ten packs stay visually distinct. |
| `ui/EnginePlumbing.kt` | `REPLACE` | Direct mutable renderer fields in Compose → immutable commands/state (Phase 9.1) |
| `ui/VisualsHub.kt`, `CustomizeTabs.kt` | `ADAPT` | Regenerated from typed schemas in Phase 7.4, keeping art direction |

## Durable stores

| Path | Disposition | Note |
|---|---|---|
| `data/PresetStore.kt` | `ADAPT` | → Preset V2 + migrations + alias table (Phase 7.3) |
| `data/TrackLibrary.kt`, `HistoryStore.kt`, `MusicPlaylistStore.kt` | `KEEP` | Already atomic rename + fsync with corrupt-file quarantine. **Stronger than the plan assumes — preserve, do not rebuild.** |
| `data/TextureStore.kt`, `LfoStore.kt`, `AutoVisualsPrefsStore.kt` | `UNKNOWN` | Classify before Phase 7 edits |

## Native

| Item | Disposition | Note |
|---|---|---|
| `jniLibs/arm64-v8a/libprojectM-4.so` + `libprojectmjni.so` | `KEEP`, **rebuild required** | `SHA256SUMS` verifies the blobs are unmodified but records that they predate provenance tracking and that `tools/pm_jni.c` has been hardened since. The shipped binary does not correspond to current JNI source. Rebuild via `native-libs.yml` and commit with a provenance header — **release-blocking**, tracked into Phase 11.2/11.3. |
| App-level `abiFilters += "arm64-v8a"` | `UNKNOWN` | Dropping `x86_64` removes emulator support and device-free CI visual checks. Changing supported ABI is a **user-decision boundary** (plan §0). Do not change unilaterally; raise it before Phase 11. |

## Source-text gates

| Item | Disposition | Note |
|---|---|---|
| `ParamSurface*.kt`, `ParamMatrix.kt` | `ADAPT` | Keep while the legacy param surface exists. Replaced by behavioral/architecture gates in the same slice that deletes their subject (plan §10.4). |
| `CompositeUniformParityTest` | `ADAPT` | Narrows to the legacy pair in Phase 4.5, deleted in Phase 10.3 |
| `SharedShaderPreludeTest` | `KEEP` | Enforces one definition of shared shader helpers — still the right rule in V2 |
