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

## V2-A-02a: pin and hash every source in the provenance registry

State: COMPLETE

Goal: make every licence claim in the V2 corpus a piece of evidence — a hash of a file read
at a named commit — and make the registry and `MASTER_PLAN.md` §3.1 cover each other
exactly, so neither can drift alone.

User-visible effect: none. Documentation, a test-source validator and one new test class.

In scope: `provenance.json` at schemaVersion 2 — 39 repositories against §3.1's 37 ledger
rows, each with a resolved commit, the licence file's path, SHA-256, byte length and first
line; the seven-tier vocabulary from §3; the seven sources §3.1 names that the registry was
missing; `SOURCE_ARCHIVE.md` reconciled to the same vocabulary and facts; `ProvenanceRegistry`
and `EngineProvenanceRegistryTest`.

Out of scope: the reference coverage ledger (V2-A-02b, split off because it is a separate
concern and a separate commit). The `checkEngineProvenance` Gradle task itself — §3.3 puts
it at V2-1-04, when it must also scan the new modules; the rules run as a unit test until
then so they cannot rot in the gap. No production source file is touched.

Files expected to change: `docs/visualizer-v2/{provenance.json,SOURCE_ARCHIVE.md,STATUS.md}`,
`app/src/test/java/dev/musicviz/{ProvenanceRegistry,EngineProvenanceRegistryTest}.kt`.

Compatibility contract: untouched. Nothing in the registry is reachable from production code.

External source/provenance entries: this slice *is* the provenance work. Nothing was copied
from any source; only licence files were read, at the commits recorded in the registry.

Tests written first: `EngineProvenanceRegistryTest` — eleven assertions, four of which are
negative fixtures that mutate the real registry text (rename a required key, corrupt a tier,
give an EXCLUDE source an adopted file, replace a hash with prose) and assert the validator
names the problem. A fixture whose mutation failed to apply would leave the document valid
and fail its own assertion, so the fixtures cannot silently stop testing anything.

Benchmark or visual evidence: not applicable. The evidence here is the licence-hash table in
`provenance.json` itself.

Rollback: revert the one commit.

Risks: pins resolved from `HEAD` today rather than from a reviewed clone are weaker evidence
than the ones carried over from the earlier research session, and the registry says which is
which in each entry's `pin.source`. Ten sources are in that category, and none is under the
one tier where it would matter: nine sit under no-code tiers, the tenth (`acidcam-gpu`) is
REIMPLEMENT, which forbids copying anyway. All four ADAPT sources — the only tier that may
contribute upstream text — keep their reviewed-clone pins.

Commands and results: below.

Review findings: the first draft asserted that no source is `unresolved`, which would have
forced a guess about Geno-1's repository. Replaced with the weaker true claim — an
unresolved source may only sit under a no-code tier — so the gap stays visible instead of
being papered over.

Commit: `docs(visualizer-v2): pin and hash every source in the provenance registry`

Next slice: **V2-A-02b — enumerate every researched effect in the coverage ledger.**

### What changed in the registry, and why it matters

| Correction | Before | After |
|---|---|---|
| Sources vs. §3.1 | 32 entries, 7 ledger rows unrepresented | 39 entries, all 37 rows covered in both directions |
| Tier vocabulary | `ALGORITHM` / `EXCLUDED`, neither in the plan | the plan's `ADAPT`/`REIMPLEMENT`/`ORACLE`/`STUDY`/`EXCLUDE`, plus `BENCHMARK` and `RETAIN` for the two §3.1 rows that use them |
| Licence evidence | a licence *name* per source | file path, SHA-256, byte length, first line and the commit each was read at |
| Velo Visualiser | absent | present, **GPL-3.0 confirmed from the licence file** — the one source whose licence actively forbids what a careless slice would do |
| LYGIA | one word inside a Shadertoy row | its own entry: Prosperity Public License 3.0.0, noncommercial-only |
| projectM | pinned to the tag `v4.1.7` | tag resolved to `e0b0a96`; licence file is `LICENSE.txt`, not `LICENSE` |
| RDPE | licence text reported missing | re-checked at `28db17f`: still no `LICENSE`, `LICENCE`, `COPYING` or `UNLICENSE`. Stays STUDY |
| Geno-1 | absent | present and explicitly **unresolved** — §3.1 names it, §21 gives no URL, and it could not be located. No Geno-1-derived idea may cite provenance until it is |

The single most valuable line is Velo's. It is GPL-3.0, it is the richest scene checklist in
the corpus, and its 48 scene names are exactly the kind of thing that gets skimmed and then
reimplemented from memory. The registry now states the boundary where a later session will
look for it.

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*EngineProvenanceRegistryTest*'` | 11 passed |
| `:app:testDebugUnitTest` | **1,202 tests, 0 failures** (1,191 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |

Licence evidence was gathered with `git ls-remote` for the commit and
`raw.githubusercontent.com/<slug>/<sha>/<file>` for the text, hashed locally. `api.github.com`
and `github.com` HTML are blocked from this container; neither is needed, and nothing in the
registry depends on a source that could not be read.

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
