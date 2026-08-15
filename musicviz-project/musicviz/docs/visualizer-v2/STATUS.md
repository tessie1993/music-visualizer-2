# V2 slice log

The one place that answers "where is the overhaul". [`MASTER_PLAN.md`](MASTER_PLAN.md) §2
is the protocol this file obeys: one slice at a time, through

`LOCKED → DISCOVERY → SPECIFIED → RED → IMPLEMENTING → VERIFYING → REVIEWING → READY_TO_COMMIT → COMPLETE`

and nothing below `COMPLETE` may be running twice. `EngineV2PlanAuthorityTest` enforces
the shape of this file — the state names, one unfinished slice, and the full §2.3 field
set on every entry — so a session that skips a field fails the build rather than leaving
the next session to guess.

Newest slice first.

---

## V2-A-01: install this plan as the repository's execution authority

State: COMPLETE

Goal: make `docs/visualizer-v2/` the memory the overhaul runs on — one live plan, one
slice log, and a build gate that keeps both honest — and re-audit the tree against
`MASTER_PLAN.md` §1 so later slices start from measured numbers rather than the plan's.

User-visible effect: none. Documentation and one new unit test.

In scope: `MASTER_PLAN.md` as the verbatim plan; `STATUS.md`; `DECISIONS.md` as the ADR
index; `LEGACY_DISPOSITION.md` seeded from §12; `REFERENCE_COVERAGE.md` schema; the
`adr/`, `benchmarks/` and `captures/` directories; a superseded banner on
`ENGINE_V2_PLAN.md`; the authority/link/status gate test; the §1 drift record below.

Out of scope: every production behaviour. No `:app` source file changed. Populating the
coverage ledger (V2-A-02), the legacy per-subsystem discovery columns (V2-A-02 onward)
and the ABI/baseline/safety documents §2.2 lists, each of which belongs to the slice that
first has evidence for it: `AUDIO_FEATURE_ABI.md` (V2-2-01), `GPU_RESOURCE_ABI.md`
(V2-4-01), `PERFORMANCE_BASELINE.md` (V2-0-04), `SAFETY_MODEL.md` (V2-0-02),
`PRESET_SCHEMA.md` (V2-7-03), `RELEASE_GATES.md` (Phase 11). They are deliberately absent
rather than present and empty.

Files expected to change:
`docs/visualizer-v2/{MASTER_PLAN,STATUS,DECISIONS,LEGACY_DISPOSITION,REFERENCE_COVERAGE}.md`,
`docs/visualizer-v2/ENGINE_V2_PLAN.md` (banner only),
`docs/visualizer-v2/adr/README.md`,
`app/src/test/java/dev/musicviz/EngineV2PlanAuthorityTest.kt`.

Compatibility contract: untouched. No scene ID, preset key, audio semantic or public API
is involved.

External source/provenance entries: none. No external code, shader or constant enters the
tree in this slice.

Tests written first: `EngineV2PlanAuthorityTest` — six assertions written and run red
before any document existed (missing `MASTER_PLAN.md` and the rest, no authority marker,
no `STATUS.md`). It reuses `ParamSurface.moduleRoot` rather than adding a nineteenth
private `repoFile` copy, which `BASELINE.md` §3 names as prerequisite cleanup.

Benchmark or visual evidence: not applicable — no runtime path is touched.

Rollback: revert the one commit. Nothing depends on these documents at runtime.

Risks: a docs-only gate can rot into ceremony. Mitigated by keeping the assertions about
facts a stale session would actually get wrong — which document is live, whether a link
lands, whether two slices are open — rather than about wording.

Commands and results: recorded under "Verification" below. The red proof is the one worth
naming — the six assertions were run with the five new documents moved out of the tree and
the banner stashed, and all six failed for the right reasons before being run again green.

Review findings: `REFERENCE_COVERAGE.md` and `LEGACY_DISPOSITION.md` initially read as if
they were finished. Both now carry an explicit open marker naming the slice that completes
them, per §2.1 rule 10.

Commit: `docs(visualizer-v2): install the master plan as the execution authority`

Next slice: **V2-A-02 — expand provenance and coverage registry.**

### §1 drift record, measured at this HEAD

`main` at `54630a8`, the commit `MASTER_PLAN.md` §1 audited. Worktree clean at the start
of the slice; branch `claude/visualizer-patch-plan-dg8r5u`.

| §1 claim | Measured | Verdict |
|---|---|---|
| Main Kotlin: 179 files, ~51,153 lines | 179 files, 51,153 lines | exact |
| Test Kotlin: 165 files, ~27,449 lines | 165 files, 27,449 lines (before this slice's test) | exact |
| GLSL resources: 65 | 65 | exact |
| `SceneId` values: 38 | 38 `const val` in `SceneIds.kt` | exact |
| `SceneParams` fields: 165 | 165 | exact |
| Serialized preset keys: 164 | 164 parameter keys **+ 4 envelope keys** (`name`, `sceneId`, `attack`, `decay`) = 168 distinct `put("…")` | clarified |
| Bundled presets: 19 | 19 | exact |
| Modules: 1 | `include(":app")` | exact |
| Largest coordinator: `PlayerViewModel` ~2,518 lines | 2,518 | exact |

One correction carries forward: V2-7-03 must give a disposition to **168** serialized
keys, not 164. The extra four are the preset document's own envelope and are not
`SceneParams` fields, which is why the two counts differ and why silently migrating "the
164" would drop the envelope.

### Verification

Run from `musicviz-project/musicviz/`, narrow to wide per §2.4.

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*EngineV2PlanAuthorityTest*'`, documents removed | **6 tests, 6 failed** — the intended red |
| `:app:testDebugUnitTest --tests '*EngineV2PlanAuthorityTest*'`, documents in place | 6 passed, BUILD SUCCESSFUL |
| `:app:testDebugUnitTest` | **1,191 tests, 0 failures, 0 skipped** |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |

The Android SDK is not present in a fresh cloud container; `tools/setup-android-sdk.sh`
installs it and writes `local.properties`, which is what these runs used. No pre-existing
failure was observed to hide behind this slice: the suite was green before it and after.
