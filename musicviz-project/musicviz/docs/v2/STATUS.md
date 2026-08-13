# MusicViz 2.0 Status

## Authority

- Master plan: `docs/v2/MASTER_PLAN.md`
- Starting SHA: `05aca01` (plan audit baseline) → actual start `5ceef8f`
- Current SHA: `2a4c562` (PR #96 merged in)
- Branch: `claude/audio-visualizer-research-d8d92d-ifayw9` (PR #97)
- Worktree at start: clean
- Last updated: 2026-08-13
- Current phase/slice: **0.3 COMPLETE → Phase 0 done; Phase 1 unlocked**

## PR #96 merged into this branch

`main` gained four commits from the sibling branch while slice 0.2 was in
flight. Merged cleanly (disjoint paths). It brought a parallel v2 doc tree at
`docs/visualizer-v2/`:

| File | Disposition |
|---|---|
| `ENGINE_V2_PLAN.md` | **Superseded as a work order** — `docs/v2/MASTER_PLAN.md` is authoritative, confirmed by the operator. Header added; retained as design evidence. Whether to delete it per Appendix E is an **open operator question** — it is freshly merged work and was not removed unilaterally. |
| `provenance.json` | **Adopted as the authoritative provenance record.** Pinned commits and licence files outrank recollection. |
| `SOURCE_ARCHIVE.md`, `BASELINE.md` | Measured evidence; retained. |
| `.claude/settings.json` | Benign — allowlists read-only verification commands. Retained. |

**It also proved ADR-0002 wrong** — see the divergences section.

## Baseline

Full detail in `BASELINE.md`; raw evidence in `VERIFICATION_LOG.md`.

| Command/evidence | Result | Date/environment | Link/path |
|---|---|---|---|
| `:app:testDebugUnitTest` | **PASS** — 1,185 tests, 0 failures, 0 errors, 0 skipped | 2026-08-13, JDK 21 / Gradle 8.13 / SDK 36 | `app/build/test-results/testDebugUnitTest/` |
| `:app:ktlintCheck` | **PASS** | same | task output |
| `:app:lintDebug` | **PASS** | same | `app/build/reports/lint-results-debug.html` |
| `:app:assembleDebug` | **PASS** — 331 MiB debug APK | same | `app/build/outputs/apk/debug/` |
| Whole invocation | `BUILD SUCCESSFUL in 8m 42s` | same | — |
| Reference performance | `BLOCKED_ENVIRONMENT` | no device available | `DEVICE_MATRIX.md` |

**The baseline is green.** The master plan's own audit could not start Gradle;
that is now resolved — `tools/setup-android-sdk.sh` works in this container.

## Phase ledger

| Slice | State | Commit | Evidence | Legacy removed | Next |
|---|---|---|---|---|---|
| 0.0 Purge competing instructions | `COMPLETE` | `faafe8f` | No-production-change gate verified; E.4 searches clean; tooling claims checked against Gradle before deleting | 25 agent/command files, 11 skills, 3 unusable rule files, 9 quality-corpus docs | 0.1 |
| 0.1 Create v2 control documents | `COMPLETE` | `1999abe` | Baseline recorded from a real run; inventory and ledger derived from source | — | 0.2 |
| 0.2 Engine generation + diagnostics | `COMPLETE` | `b4a2fb1` | Behavioural red → green; 1,198 tests / 0 failures; ktlint, lint, assemble all pass | — | 0.2b |
| 0.2b Debug-only generation selector in settings | `COMPLETE` | `b038d03` | Behavioural red on both settings assertions → green; full matrix `BUILD SUCCESSFUL in 2m 3s` | — | 0.3 |
| 0.3 Photosensitivity safety as explicit v2 choice | `COMPLETE` | `2e861f8` | Choice model + migration; 1,216 tests / 0 failures | — | 0.3b |
| 0.3b Prompt, output coverage, exporter hardening | `COMPLETE` | this commit | Behavioural red ×6 → green; 1,226 tests / 0 failures; full matrix `BUILD SUCCESSFUL in 1m 28s` | `VideoExporter`'s unsafe `safety = OFF` default | **Phase 1** |
| **Phase 0** | **COMPLETE** | — | Baseline green; safety P0 closed | — | Phase 1.1 |
| Phase 1 onward | `LOCKED` | — | — | — | — |

## Current slice

**Phase 0 is COMPLETE.** Baseline green, harness authority established, engine
generation switch in place, and the photosensitivity P0 closed.

### Next: Phase 1.1 — introduce `MusicVizGraph` and `EngineHost`

- Problem: `PlaybackSession` is process-wide but ownership is spread across
  `PlayerViewModel` (2,518 lines), which also owns capture sessions, export and
  preset mutation. Nothing can outlive the ViewModel cleanly.
- Chosen boundary: a **hand-written** app graph in `MusicVizApp` — ADR-0001
  forbids adding Hilt/Koin during the migration. Android components become thin
  adapters that obtain graph services.
- Explicit non-goal: do **not** move an EGL context or surface into the graph.
  Each output owns its context; the graph owns engine-neutral state/factories.
- Red tests: Activity recreation returns the same process engine host and player
  session; destroying the ViewModel does not cancel an independently-owned
  wallpaper or export job; a fresh process constructs each singleton once.
- Risk: this touches playback ownership, the app's most load-bearing path.
  Extract one domain per slice; `PlayerViewModel` stays a façade until consumers
  migrate. No big-bang rewrite.

## Verification report

### Slice 0.1 (docs)

- Focused tests: n/a — documentation only.
- Full unit tests: PASS at `faafe8f` (1,185/0/0/0). Ktlint, lint, assemble: PASS.

### Slice 0.2 (`b4a2fb1`)

- Red proof: `resolve()` first returned `Active(requested)` unconditionally;
  `requesting an unavailable V2 falls back visibly and keeps the reason` failed
  with `expected a FellBack selection, got Active(active=V2)` while the other six
  tests passed. A compile-only failure was **not** accepted as red (H0.2 rule 5).
- Full unit tests: **PASS — 1,198 tests, 0 failures, 0 errors, 0 skipped**
  (1,185 baseline + 13 new).
- Ktlint: PASS. Lint: PASS. Assemble: PASS. `BUILD SUCCESSFUL in 2m 8s`.
- Legacy-growth search: `engine/*.kt` imports no `dev.musicviz` legacy type.
- Source-text gates: unaffected — full suite green with the new main-source files
  present.
- Instrumentation/device/performance/visual: `BLOCKED_ENVIRONMENT` — no device.
- Not run: `bundleRelease` (no release signing); `connectedDebugAndroidTest`;
  second-clean-checkout baseline reproduction.

## Open decisions/blockers

1. **No device in this environment.** Hard-blocks the Phase 4 exit gate and
   everything after it. Phases 0–3 are JVM-testable and can proceed. Needs the
   operator to provide a device or CI runner. — `BLOCKED_ENVIRONMENT`
2. **projectM binaries do not match current JNI source.** — **P1, IN PROGRESS.**
   `native-libs.yml` was dispatched on `main` with `projectm_tag=v4.1.7`
   (run `31707150435`). Landing it still needs the artifact downloaded and its
   two `.so` files committed **together with the run's `SHA256SUMS`**, which
   carries the run URL and source SHA. `android.yml` verifies the hashes on
   every PR.
3. **App is `arm64-v8a` only, app-wide.** Costs emulator support and device-free
   CI visual checks. Changing supported ABI is a listed user-decision boundary —
   **not** changing it unilaterally. Needs an operator decision before Phase 11.
4. ~~`gl_transitions.json` licensing~~ — **RESOLVED, and it was never a real
   problem.** All 122 entries carry per-entry `license`/`author`: **120 MIT,
   1 BSD-3-Clause** (`InvertedPageCurl`, Hewlett-Packard), **1 BSD-2-Clause**
   (`StereoViewer`, Ted Schundler). `THIRD_PARTY_NOTICES` already documents
   exactly that split with full licence texts. No action needed.
5. **~290 MB of PNG theme assets.** Release-size impact unmeasured
   (`bundleRelease` NOT RUN). Crystal-pack identity is a protected product
   promise, so this is a format/size question, not a deletion proposal.
6. **`UNKNOWN` retirement-ledger rows block edits in their area:**
   `FeatureTimeline.kt`, `SceneSuggester.kt`, `LiveInputProfile.kt`,
   `BeamScene.kt`, `TextureStore.kt`, `LfoStore.kt`, `AutoVisualsPrefsStore.kt`.

## Known defects

| Severity | Defect | Reproduction | Owner/target |
|---|---|---|---|
| ~~P0~~ | ~~Safe visuals default `false`~~ — **CLOSED** in `2e861f8`/`2a4c562`. Absence of a choice is no longer read as consent. | `engine/SafetyChoice.kt` | Done |
| P1 | Shipped projectM `.so` predates the hardening of `tools/pm_jni.c` | `jniLibs/arm64-v8a/SHA256SUMS` header | Operator: dispatch `native-libs.yml` |
| P2 | Debug artifact 331 MiB; 290 MB of it source PNGs | `assembleDebug` | Measure `bundleRelease` before Phase 11 |

## Divergences from the master plan

Recorded rather than silently followed, per H0.3.

- **ADR-0002 was wrong and is superseded by ADR-0003.** It struck `Fosfora` and
  `Colourful Attraction` from §11.3 as non-existent. Both exist:
  `kevinraymond/fosfora` (MIT or Apache-2.0) and `QC20/Colourful-Attraction`
  (MIT), resolved from PR #96's `provenance.json` and then fetched. `RDPE`
  (`sqrew/rdpe`, MIT) is real too; `ORPHIC` (`adityarajashekaran/orphic`) is
  real and **AGPL-3.0**, so prohibited.
  **§11.3 needed no amendment — the plan was right.** The audio-frame contract
  regains its external model, so Phase 3 may study Fosfora directly.
  Cause: keyword searches were treated as proof of non-existence. Binding rule
  now — *no reference may be recorded as non-existent without a fetch against a
  concrete URL; absent a URL the status is `UNVERIFIED`.*
  ADR-0002's addition of ENTHEA (AGPL-3.0) and BoomingMusic (GPL-3.0) to the
  prohibited list was correct and is carried forward.
- Three audit-table figures are stale (see `INVENTORY.md`). Shape of each claim
  holds; no decision depends on the numbers.
- `CLAUDE.md` keeps one line beyond Appendix F — the SDK-setup pointer — because
  the baseline proved a fresh container needs it.

## Resume instructions

1. Read `MASTER_PLAN.md` H0 (harness rules) and §6 Phase 0.2–0.3.
2. Read ADR-0001 and ADR-0002.
3. Inspect `git status`, current diff, and the last two commits (`b4a2fb1`,
   `1999abe`).
4. On a fresh container run `bash musicviz-project/musicviz/tools/setup-android-sdk.sh`
   first, then re-establish the gate from `musicviz-project/musicviz`:
   `./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:lintDebug :app:assembleDebug`
   (~9 min cold, ~2 min warm). Expect 1,198 tests, 0 failures.
5. Continue with **slice 0.2b**: write the failing test asserting the debug
   selector is absent in a release configuration *before* adding the UI, and
   update `AppSettingsTabSplitTest` in the same commit.
