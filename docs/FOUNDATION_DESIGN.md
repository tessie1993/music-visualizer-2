# Synesthesia — Foundation Design (how the layout was designed)
Companion to docs/ARCHITECTURE_BLUEPRINT.md (the map) · This doc explains WHY the scaffold is shaped this way · v1.0

## 1. Design philosophy: contracts before engines
Every pillar lands as an INTERFACE first, engine second. A seam that compiles today with zero implementation is the cheapest possible guarantee that features will plug in without re-architecture. The five seams shipped in this commit:

| Seam | Module | Contract | Engine comes later as |
|---|---|---|---|
| Audio ingest | :core:audio | `AudioSource` → `PcmSink` | PlayerTap/Mic/Capture/File impls |
| Analysis output | :core:audio | `AudioFeatures` snapshot | ReactiveAnalyzer port |
| Render entry | :core:visualizer | `Style` + `StyleManifest` + `RenderClock` | Shader/Fluid/ProjectM/bgfx engines |
| Modulation+Safety | :core:visualizer | `ModulationRouter` + `ParamClamp` | LFO/ADSR routes + two-stage FlashBudget |
| Money | :core:billing/:core:export | `PurchasePort`/`EntitlementRepository` + `ExportLimitsResolver` | PBL 9.x impl / DebugPurchasePort |

Why fun interfaces & tiny data classes: they compile on every module with zero Android deps, keep the DAG honest, and let each engine be built/tested in isolation against a fake counterpart.

## 2. Data flow (the law everything obeys)
```
source(AudioSource) --f32@48k--> SampleRing --> analyzer --> AudioFeatures
    --> ModulationRouter(stage-1 clamp) --> Style.render(clock, features, params)
    --> post-FX --> FlashBudget(stage-2) --> surface | encoder
```
Live mode: clock = media position. Offline mode: clock = frameIndex/fps, same render fn — determinism is a property of the CONTRACT, not of an engine.

## 3. Feature-integration recipe ("easy feature integration")
Adding anything to Synesthesia = one of five moves:
1. **New audio source** → implement `AudioSource`, register in DI. Nothing else changes.
2. **New visual style** → implement `Style` (+ manifest costClass/statefulness), drop into StyleRepository.
3. **New modulation** → new route type inside ModulationRouter's registry (data, not code paths).
4. **New export capability** → extend ExportLimits + pipeline step behind TransformerEditor.
5. **New UI surface** → NavKey + feature module; depends only on cores via ports.
Forbidden everywhere: core→feature imports, cross-feature imports, Media3 types outside player/export wrappers.

## 4. Thread law summary (enforced later by arch-tests)
main(UI) · GL(render) · audio(tap writes ring, never locks; seqlock snapshots) · IO(stores) · Default(offline analyze). Cross-thread handoff ONLY via AudioFeatures immutable snapshots.

## 5. What deliberately does NOT exist yet
Engines (analyzer, GL renderer, projectM bridge, billing impl), Room schema, Compose shell. Contracts first means none of them can distort the seams. Each arrives behind its interface with characterization tests mined from the legacy suite (LEGACY_VERDICTS top-15).
