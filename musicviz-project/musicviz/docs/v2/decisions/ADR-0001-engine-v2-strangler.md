# ADR-0001: Engine V2 as a strangler migration inside one module

- Status: Accepted
- Date: 2026-08-13
- Deciders: Repository operator (via `MASTER_PLAN.md`), implementing agent
- Related phase/slice: Phase 0.1

## Context

The current engine has one owner per *file* but not one owner per
*responsibility*. `VisualizerRenderer` (1,651 lines) owns the scene registry,
clocks, transitions, layers, params, FBOs, safety, projectM and the export
factories at once. `AnalysisEngine` schedules on wall clock and analyses "the
latest 2048 samples", so it can analyse a window twice or skip samples.
`OfflineAnalyzer` duplicates that setup. `VideoExporter` runs its own scene/EGL
loop and `FxCompositor` duplicates the composite, kept in sync only by a
source-text parity test.

Around this sits a working product — player, queue, library, lyrics, capture,
wallpaper, Export Studio, projectM, ten crystal packs — that must keep working
throughout.

## Decision

1. **One app module.** No multi-module Gradle reorganization for 2.0; package
   boundaries under `dev.musicviz.*.v2` are sufficient. Revisit only once the
   new boundaries are stable.
2. **Strangler migration behind an explicit generation switch.**
   `EngineGeneration.LEGACY` stays the production default until the Phase 4
   vertical slice passes its gates, and remains buildable until Phase 10.5.
   Exactly one generation runs at a time — never both engines continuously.
3. **GLES 3.0 is the required baseline.** GLES 3.1 compute is an optional
   accelerator chosen by capability probe, never the only implementation of a
   scene. Simulation uses ping-pong textures at baseline.
4. **Process-scoped engine host.** A hand-written `MusicVizGraph` owns
   long-lived services. **No DI framework** (Hilt/Koin) is added during the
   migration — it would be a second large change landing on top of this one.
5. **One `FrameRunner` for every output.** Live, preview, wallpaper, take
   replay and export drive the same runner and render graph. The encoder is an
   output adapter, not a parallel engine.
6. **One `AnalysisCore` for live and offline.** Offline differs only in
   supplying decoded chunks faster than real time.

## Alternatives considered

### A. Rewrite in place, no generation flag

- Benefits: no adapter code, no dual maintenance, smaller total diff.
- Costs: the app is unshippable for the whole migration; no way to A/B a
  regression against legacy behavior; a single bad slice blocks everything.
- Evidence: the existing engine is the product's entire visual surface — there
  is no degraded mode to fall back to.
- Rejected.

### B. Multi-module split first, then migrate

- Benefits: compiler-enforced boundaries instead of package discipline.
- Costs: pays the reorganization cost against boundaries that are still
  guesses, and every source-text gate pins current paths — a move would break
  ~40 tests before any behavior improves.
- Rejected for 2.0; explicitly a non-goal in the master plan.

### C. Vulkan or wgpu rewrite

- Benefits: compute shaders as a baseline, better tooling.
- Costs: discards working GLES scenes, the projectM boundary and every capability
  probe; multiplies device risk.
- Rejected; explicit non-goal.

## Consequences

- **Positive:** the app stays shippable throughout; each slice is revertible;
  legacy remains a live comparison oracle for parity tests.
- **Negative:** adapters and bridges exist for several phases, and the
  temptation to keep both architectures indefinitely is the main risk. H0.5's
  freeze plus the retirement ledger's delete gates are the countermeasure.
- **Migration:** per-phase, as sequenced in `MASTER_PLAN.md` §6.
- **Rollback:** flip `EngineGeneration` to `LEGACY`; every slice must leave that
  path working until Phase 10.5.
- **Validated by:** the Phase 4 exit gate (Morphic Atlas live + export through
  one runner, matching frames on the same GPU), the Phase 3 exit gate
  (live/offline parity, byte-identical cache for identical input), and the
  architecture tests in §8.
