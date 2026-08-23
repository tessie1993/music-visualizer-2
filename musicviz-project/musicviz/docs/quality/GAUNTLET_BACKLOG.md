# Gauntlet Backlog

Ranked refactor targets from the Phase-0 repo survey (worst first), scheduled into
rounds. Behavior may be redesigned wherever the result is a clear improvement;
build + unit tests + ktlint + lint stay green every round. Each green round is
committed and pushed.

**Cross-cutting constraint**: 40 of 152 test files assert on main *source text*
(`ParamSurface`, `ParamMatrix` + 38 consumers, with hard-coded file paths). Any
file split/move must update those gates in the same change — they are refactor
inputs, not collateral.

## Ranked targets

| # | Target | Smell | Blast radius |
|---|--------|-------|--------------|
| 1 | `ui/PlayerViewModel.kt` (4,042 lines) | God class: ~15 domains, 15 inline collaborators, no DI | Maximum |
| 2 | `render/VisualizerRenderer.kt` (1,690; `onDrawFrame` 408 lines) | God method; 22 @Volatile fields mutated cross-thread | Maximum |
| 3 | `render/scene/SceneParams.kt` (169 fields) | God data class, referenced in 40 files | Maximum |
| 4 | `ui/VisualsHub.kt` (1,460; highest churn) | God file; 15 scene-capability predicates in UI layer | High |
| 5 | `render/fluid/*Scene.kt` | Missing FluidSceneBase; verbatim duplicated blocks | Med-high |
| 6 | `export/VideoExporter.kt` + `FxCompositor.kt` | Second copy of render pipeline; 411-line function | High |
| 7 | `audio/MicCapture.kt` / `PlaybackCapture.kt` | Same class twice; non-volatile stop flags | Low-med |
| 8 | `ui/Crystal.kt` (1,854 lines) | Six responsibilities in one file, zero tests | Medium |
| 9 | `ui/CustomizeTabs.kt` + `ParamRandomizer.kt` | Stringly-typed 4-way parameter wiring | High |
| 10 | `render/scene/CymaticsMath.kt` | Multi-class monolith files | Medium |
| 11 | GL bootstrap duplication across `render/*` | Quad VBO ×7, loadRaw ×8, compile-catch ×12 | Low |
| 12 | `data/` stores | migrateLegacyFileNames ×4; no store base; serializer drift | Medium |
| 13 | Global mutable singletons (`AudioBus`, `LayersBus`, …) | Unowned process-wide state | High/diffuse |
| 14 | `ui` layering (stores in ui; wallpaper imports ui) | Layer inversion; unowned SharedPreferences | Med-high |
| 15 | Missing detekt/coverage; gradle.properties; dead deps | No complexity gate; slow builds | Process-wide |
| 16 | Docs: README dead refs (DEVICE_CHECKS ×11, todo.md ×2), changelog-as-README | Misleads agents and humans | Low |
| 17 | ECC bulk install (`.claude/`, `.agents/`, `.codex`): wrong auto-generated skill (says Python), 500+ irrelevant files | Actively wrong instructions | Low (sequence last) |

## Round plan

- **R1 (foundations + safe wins)**: #16 docs fixes, #15 build config (properties,
  catalog, dead dep), #11 GL dedup, #7 capture merge, dead-function removal.
- **R2 (renderer + scene architecture)**: #2 renderer command/state redesign, #5
  fluid base, #10 file splits; informed by deep reads of projectM/Auxio/NIA source.
- **R3 (UI + ViewModel decomposition)**: #1, #4, #14, #13 — extract stores/domain,
  DI seams, scene capability interface.
- **R4 (params + export + data)**: #3, #9, #12, #6.
- **R5 (gates + purge)**: #15 detekt/coverage baseline, #17 ECC purge, final polish.
- Rounds repeat/extend until the critic panel is unanimously wowed; critic findings
  reshape the plan between rounds.
