# Legacy disposition

**Open — completed progressively.** The decision column below is binding now, transcribed
from [`MASTER_PLAN.md`](MASTER_PLAN.md) §12 and checked against the tree at `54630a8`. The
*proof* column is what has to exist before the decision may be acted on, and none of it
exists yet. A subsystem does not move state without its proof landing in the same slice.

Reversing a row needs an ADR with repository evidence, not a judgement call in a later
session (§12).

## Decisions

| Subsystem | Where it lives now | Decision | Proof required before the legacy code moves or dies | Status |
|---|---|---|---|---|
| Player / library / Media3 workflow | `playback/`, `data/`, `ui/` | KEEP | only narrow engine ports change | not started |
| PCM tap placement | `engine/audio-android/…/PcmTap.kt`; `audio/TapRenderersFactory.kt` stays | KEEP semantic order, MOVE to `:engine:audio-android` | runtime stage-order assertion, waveform fixtures, route tests | **tap moved, V2-2-03**; `PcmTapSink` awaits its deletion slice; route tests **synthesized only** — no real route change has been exercised |
| `PlaybackSession` process lifetime | `playback/PlaybackEngine.kt` | KEEP / REFACTOR | first-acquire hold fixed; lifecycle and multi-consumer tests | **hold fixed, V2-0-01** |
| `PcmRingBuffer` | `audio/PcmRingBuffer.kt` (170) | REPLACE contract incrementally | `Ok`/`Gap`/`NotYetAvailable`, wrap and competing-reader tests | not started |
| `AnalysisEngine` | `analysis/AnalysisEngine.kt` (147) | REPLACE after V2 graph parity | corpus features, CPU/allocation, live/export parity | not started |
| `AudioBus` / `BandSmoother` latest-state transport | `audio/AudioBus.kt` (82), `analysis/BandSmoother.kt` | BRIDGE then DELETE | time-addressed ring serves every consumer | not started |
| `PulseTracker` | `analysis/PulseTracker.kt` (553) | KEEP, improve inputs | beat corpus comparison before any replacement | not started |
| `FeatureTimeline` | `analysis/FeatureTimeline.kt` (295) | KEEP until semantics reproduced | event span and cache tests | not started |
| `VisualizerRenderer` | `render/VisualizerRenderer.kt` (1,651) | BRIDGE then DELETE | every output on `FrameRunner`; every legacy ID resolves | not started |
| `Scene` interface | `render/scene/Scene.kt` (31) | PRESERVE lifecycle shape, ADAPT | V2 bridge and context-loss tests | not started |
| `ParticleSceneBase` + CPU subclasses | `render/scene/ParticleSceneBase.kt` (444) + 9 scenes | REPLACE then DELETE | GPU family parity/fallback, screenshots, device benchmarks | not started |
| Existing GLSL scenes | `app/src/main/res/raw/*.glsl` (65) | RECIPE, ADAPT or LEGACY bridge, individually | a coverage ledger row and a golden image each | include manifest checked offline, V2-1-04b; `ShaderIncludeManifestTest` moves with the shaders |
| `SceneParams` | `render/scene/SceneParams.kt` (521, 165 fields) | FREEZE then REPLACE | disposition for all 168 serialized keys; preset round trips | not started |
| `VisualStyleCatalog` | `render/scene/VisualStyleCatalog.kt` | MIGRATE IDs and names | all 11 Cymatics looks resolve recognisably | not started |
| `CompositeGrade` / `FxCompositor` | `render/CompositeGrade.kt`, `export/FxCompositor.kt` (468) | CONSOLIDATE into the render graph | blend, colour and golden tests | not started |
| `VideoExporter` visual loop | `export/VideoExporter.kt` (892) | REPLACE duplicate path | unified `FrameRunner` parity and A/V sync | not started |
| Live wallpaper integration | `wallpaper/` | KEEP shell, REPLACE runner | lifecycle, visibility, battery, context-loss tests | not started |
| Shader editor | `render/scene/ShaderScene.kt`, `ui/VisualsHub.kt` | GENERALISE into Shader Studio | last-good, bounded multipass, audio ABI, wallpaper tests | not started |
| projectM | `render/scene/{ProjectMScene,PMBridge}.kt`, `tools/pm_jni.c` | RETAIN isolated dynamic adapter | notices, source offer, ABI and fallback checks | not started |
| `PlayerViewModel` | `ui/PlayerViewModel.kt` (2,518) | DECOMPOSE only at proven seams | behaviour tests; no speculative rewrite | not started |

## Paths pinned by source-text tests

`CLAUDE.md` and §12 both say it: about 40 unit tests parse the main tree as text, and
`BASELINE.md` §3 counts 57 test files that read main source. Moving any file below
without replacing its assertion in the same slice turns a gate into a vacuous one — it
still passes, and it no longer proves anything.

`SceneParams.kt`, `CymaticsScene.kt`, `VisualizerRenderer.kt`, `CompositeGrade.kt`,
`FxCompositor.kt`, `VideoExporter.kt`, `BeamScene.kt` and its shaders, `TapRenderersFactory.kt`.

`AudioChainContractTest` is the sharpest case: it proves the tap's position in the
processor chain by reading source text, so the moment those stages move it asserts
nothing. §12 requires the runtime stage-order assertion to land **before** its text target
changes, not after — `AudioChainOrderRuntimeTest` is that assertion, landed in V2-2-01.

It has not retired yet. V2-2-03 moved the tap's *implementation*, not
`TapRenderersFactory`, which §12 keeps in `:app` along with the rest of the Media3 player
workflow — so the text target still says what it did. It retires in the slice that moves
the factory, if one ever does.

## Hard-delete gates

Deletion is always its own slice, after the proof above and never in the slice that
introduces the replacement (§2.1 rule 7, §12.1). Targets: `VisualizerRenderer`, the CPU
particle base and subclasses, the legacy `AnalysisEngine`, `AudioBus`/`BandSmoother`,
`SceneParams`, and the duplicate export compositor.
