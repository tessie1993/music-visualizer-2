# MusicViz 2.0 Status

## Authority

- Master plan: `docs/v2/MASTER_PLAN.md`
- Starting SHA: `05aca01` (plan audit baseline) → actual start `5ceef8f`
- Current SHA: `b4a2fb1`
- Branch: `claude/audio-visualizer-research-d8d92d-ifayw9` (PR #97)
- Worktree at start: clean
- Last updated: 2026-08-13
- Current phase/slice: **0.2 PARTIAL — engine core landed; debug selector outstanding**

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
| 0.2 Engine generation + diagnostics | **`PARTIAL`** | `b4a2fb1` | Behavioural red → green; 1,198 tests / 0 failures; ktlint, lint, assemble all pass | — | 0.2b |
| 0.2b Debug-only generation selector in settings | `SPECIFIED` | — | — | — | 0.3 |
| 0.3 Photosensitivity safety as explicit v2 choice | `LOCKED` | — | — | — | — |
| Phase 1 onward | `LOCKED` | — | — | — | — |

## Current slice

**0.2 landed its engine core. 0.2b — the debug-only selector — is next.**

What landed in `b4a2fb1`:

- `engine/EngineGeneration.kt` — `LEGACY`/`V2`, `DEFAULT = LEGACY`,
  `EngineGenerationStore` (unknown stored value falls back rather than throwing),
  and `resolve()` returning a sealed `EngineSelection` so a capability fallback
  carries its reason and cannot be silently ignored.
- `engine/EngineDiagnostics.kt` — primitive atomics only, detached snapshots,
  and a `report()` that formats on user action, never per frame.
- 13 tests across the two classes.

The `engine` package imports **no** legacy implementation — verified by grep, and
the property the Phase 0 architecture gates will later enforce mechanically.

### Next: slice 0.2b

- Problem: the generation switch has no user-reachable control, and the plan
  requires release builds to be unable to expose one.
- Chosen boundary: a debug-only section in the settings surface that selects the
  generation and shows `EngineDiagnostics.report()`.
- Expected files: one of `ui/AppSettingsTab.kt` / `SettingsDialog.kt` /
  `AboutSettings.kt`, plus **`app/src/test/java/dev/musicviz/AppSettingsTabSplitTest.kt`**
  — a source-text gate that pins the settings tab structure and will need
  updating in the same commit.
- Red test: the selector is absent from a release configuration, and selecting an
  unavailable V2 surfaces the fallback reason in the UI.
- Risk: this is the first slice to touch the existing UI surface. Keep it out of
  `PlayerViewModel` (2,518 lines) — read the store directly at the settings
  boundary rather than adding another ViewModel domain.

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
2. **projectM binaries do not match current JNI source.** `SHA256SUMS` verifies
   the blobs are unmodified but records that they predate provenance tracking
   and that `tools/pm_jni.c` has been hardened since. Fix is to dispatch
   `.github/workflows/native-libs.yml` and commit the rebuilt pair. Release
   blocking; needs operator action (workflow dispatch). — **P1**
3. **App is `arm64-v8a` only, app-wide.** Costs emulator support and device-free
   CI visual checks. Changing supported ABI is a listed user-decision boundary —
   **not** changing it unilaterally. Needs an operator decision before Phase 11.
4. **`gl_transitions.json` licensing is unverified.** A vendored shader
   collection where individual entries may carry individual terms. Blocks
   Phase 11.3. — `UNKNOWN`
5. **~290 MB of PNG theme assets.** Release-size impact unmeasured
   (`bundleRelease` NOT RUN). Crystal-pack identity is a protected product
   promise, so this is a format/size question, not a deletion proposal.
6. **`UNKNOWN` retirement-ledger rows block edits in their area:**
   `FeatureTimeline.kt`, `SceneSuggester.kt`, `LiveInputProfile.kt`,
   `BeamScene.kt`, `TextureStore.kt`, `LfoStore.kt`, `AutoVisualsPrefsStore.kt`.

## Known defects

| Severity | Defect | Reproduction | Owner/target |
|---|---|---|---|
| P0 | Safe visuals default `false`; a 9 Hz full-frame strobe is reachable with no informed choice | `ui/AppTheme.kt:188` | Slice 0.3 |
| P1 | Shipped projectM `.so` predates the hardening of `tools/pm_jni.c` | `jniLibs/arm64-v8a/SHA256SUMS` header | Operator: dispatch `native-libs.yml` |
| P2 | Debug artifact 331 MiB; 290 MB of it source PNGs | `assembleDebug` | Measure `bundleRelease` before Phase 11 |

## Divergences from the master plan

Recorded rather than silently followed, per H0.3.

- **ADR-0002** strikes `Fosfora` and `Colourful Attraction` from §11.3's
  reference table — neither project could be located. This also removes the
  stated external model for the audio-frame contract, so Phase 3 designs it from
  the in-tree `AudioFeatures` plus verified references. ENTHEA (AGPL-3.0) and
  BoomingMusic (GPL-3.0) are added to the prohibited list.
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
