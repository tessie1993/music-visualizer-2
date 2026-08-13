# MusicViz 2.0 Verification Log

Append-only. Facts only. `NOT RUN` and `BLOCKED_ENVIRONMENT` stay explicit and
are never upgraded without a new entry.

---

## 2026-08-13 — Phase 0 baseline

**Environment:** Linux 6.18.5 x86_64 container; OpenJDK 21.0.10; Gradle 8.13
(wrapper); Android SDK installed this session via `tools/setup-android-sdk.sh`
to `/root/android-sdk`; no physical or emulated device.

**Command,** run from `musicviz-project/musicviz` at HEAD `faafe8f`:

```bash
./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:lintDebug :app:assembleDebug
```

**Result:** `BUILD SUCCESSFUL in 8m 42s` — 66 actionable tasks, 65 executed,
1 from cache.

| Gate | Result | Evidence |
|---|---|---|
| `:app:testDebugUnitTest` | PASS | 1,185 tests / 0 failures / 0 errors / 0 skipped / 163 classes, parsed from `app/build/test-results/testDebugUnitTest/*.xml` |
| `:app:ktlintCheck` | PASS | task output |
| `:app:lintDebug` | PASS | `app/build/reports/lint-results-debug.html` |
| `:app:assembleDebug` | PASS | `app/build/outputs/apk/debug/app-debug.apk`, 347,204,204 bytes |

**Duration:** 8m 42s wall clock, cold cache (first run also downloaded the
Gradle distribution, AGP and dependencies; ~3 GB in `~/.gradle`).

**Warnings recorded, not fixed** (pre-existing, out of slice): `libprojectM-4.so`,
`libprojectmjni.so` and `libandroidx.graphics.path.so` cannot be stripped;
deprecation warnings in `MilkImportNameTest`, `MvzAudioProcessorChainTest`,
`SleepTimerTest`; nullable-receiver warning in `PrefsRoundtripReflectionTest`.

**Not run:**

| Gate | Status | Reason |
|---|---|---|
| `:app:connectedDebugAndroidTest` | `BLOCKED_ENVIRONMENT` | No device available; the app is `arm64-v8a` only so no x86_64 emulator image can run it |
| `:app:bundleRelease` | `NOT RUN` | Release signing not configured here |
| Startup, analysis timing, allocation, frame time, memory trace | `BLOCKED_ENVIRONMENT` | Require a device |
| Every cell in `DEVICE_MATRIX.md` | `NOT RUN` / `BLOCKED_ENVIRONMENT` | Same |

**Artifact facts:** debug APK 331 MiB (unminified). Largest entries
`classes.dex` 43.6 MB, `libprojectM-4.so` 12.5 MB, `classes12.dex` 12.2 MB.
`app/src/main/res` carries 930 PNGs totalling ~290 MB.

---

## 2026-08-13 — Slice 0.0, harness purge

**Commit:** `faafe8f` — `chore(harness): establish MusicViz 2.0 authority`.

**Gate: no production change.** Verified by
`git diff --cached --name-only | grep -Ev '\.md$|^\.claude/'` returning empty —
the commit touches only Markdown and `.claude/` files. No source, resource,
manifest, Gradle or binary change.

**Gate: tooling claims verified before deletion.** `grep` over
`app/build.gradle.kts`, `build.gradle.kts` and `gradle/libs.versions.toml`
confirmed ktlint 12.3.0 and detekt 1.23.8 are configured, and that Kotest,
MockK, Kover, Turbine, Hilt, Koin, Room, SQLDelight and Ktor are **absent**.
This is what justified deleting `ecc/kotlin/patterns.md`, `testing.md` and
`hooks.md`.

**Gate: E.4 purge searches.** The absent-tooling search returns hits only in
`docs/v2/MASTER_PLAN.md` (the plan ordering the purge),
`docs/v2/QUALITY_GATES.md` (recording what was dropped) and
`.claude/skills/music-visualizer-2/SKILL.md` (the prohibition list). All three
name the tooling in order to forbid it. Allowlisted in `INVENTORY.md`.
`find .claude -type f` now returns 5 files: `identity.json`,
`settings.local.json`, the single retained skill, and the two rewritten
`ecc/kotlin` rules.

**Gate: no dangling references.** One found and fixed —
`README.md:102` linked to the deleted `docs/quality/QUALITY_BAR.md`; it now
points to `docs/v2/MASTER_PLAN.md` and `docs/v2/QUALITY_GATES.md`.

**Build gates:** not re-run for this commit. The baseline above ran at this
exact SHA and passed, and the commit contains no compilable file.

---

## 2026-08-13 — Slice 0.1, control documents

**Commit:** see `STATUS.md`.

**Gate: no production behavior change.** Documentation only.

**Gate: baseline reproducible from a second clean checkout.** `NOT RUN` — the
procedure is recorded in `BASELINE.md` but has not been executed twice in this
environment. Re-running it costs ~9 minutes and should be done before Phase 1.

**Findings recorded during inventory** (all verified against source, not prose):

1. `AudioBus.kt` has exactly the `addConsumer`/`removeConsumer` +
   single `onInterestChanged` slot the plan describes — confirmed at
   `audio/AudioBus.kt:70-80`.
2. `GuiPrefs.safeVisuals` defaults to `false` at `ui/AppTheme.kt:188`,
   confirming the Phase 0.3 P0. `reducedMotion` is already independent
   (`AppTheme.kt:195`), as the plan requires.
3. Three plan audit-table figures are stale: `PlayerViewModel` is 2,518 lines
   (not ~4,000), `VisualizerRenderer` 1,651 (not ~1,690 — close), `SceneParams`
   181 declarations (not ~169). Shape of each claim holds.
4. `docs/VISUAL_STYLE_RESEARCH.md` claimed no texture assets are shipped. False
   at this SHA: 930 PNGs, ~290 MB. Corrected in place.
5. The projectM `SHA256SUMS` verifies the blobs are unmodified but records that
   they predate provenance tracking and that `tools/pm_jni.c` has been hardened
   since — so the shipped binary does not match current JNI source.
   Release-blocking; tracked in `RETIREMENT_LEDGER.md`.

---

## 2026-08-13 — Slice 0.2, engine generation and diagnostics

**Commit:** `b4a2fb1` — `feat(engine): add the generation switch and local diagnostics`.

**Red proof (behavioural, not compile-only).** `EngineGeneration.resolve()` was
first written to return `EngineSelection.Active(requested)` unconditionally.
Result:

```
EngineGenerationTest > requesting an unavailable V2 falls back visibly and keeps the reason FAILED
    java.lang.AssertionError: expected a FellBack selection, got Active(active=V2)
7 tests completed, 1 failed
```

The other six tests passed, so the failure isolates the intended defect — the
silent fallback. An earlier compile-error state was **not** accepted as red,
per H0.2 rule 5.

A second, unrelated red was hit and fixed first: `initializationError` —
`Package targetSdkVersion=36 > maxSdkVersion=35`. Robolectric in this project
requires an explicit `@Config(sdk = [34])` or `[35]`; 33 existing tests use 34.

**Green.** After implementing the `when`-based resolve with no `else` branch:

| Gate | Command | Result |
|---|---|---|
| Focused | `--tests 'dev.musicviz.engine.*'` | PASS |
| Full unit | `:app:testDebugUnitTest` | **PASS — 1,198 / 0 failures / 0 errors / 0 skipped** (1,185 baseline + 13) |
| Ktlint | `:app:ktlintCheck` | PASS |
| Lint | `:app:lintDebug` | PASS |
| Assemble | `:app:assembleDebug` | PASS |
| Whole matrix | one invocation | `BUILD SUCCESSFUL in 2m 8s` |

**Review searches.**

- Legacy growth: `grep -n "import dev.musicviz" app/src/main/java/dev/musicviz/engine/*.kt`
  returns nothing — the new package imports no legacy implementation.
- Source-text gates: unaffected. The full suite passes with two new main-source
  files present, so no `ParamSurface`/`ParamMatrix` path assumption broke.
- Diff reviewed: 4 files, +455 lines, no production file modified.

**Not run:** device, GL, performance, allocation and visual gates —
`BLOCKED_ENVIRONMENT`. `bundleRelease` — `NOT RUN` (no release signing).

**Deliberately deferred:** the debug-only generation selector in settings, which
Phase 0.2 also lists. There is no existing debug settings section, and
`AppSettingsTabSplitTest` pins the tab structure, so it is tracked as slice 0.2b
rather than bundled here.

---

## 2026-08-13 — Slice 0.2b, debug-only engine selector

**Commit:** `b038d03` — `feat(ui): add the debug-only engine generation selector`.

**Red proof (behavioural).** With `engineControlsVisible` defined but the
settings section not yet written, both settings-gate assertions failed for their
intended reason:

```
AppSettingsTabSplitTest > theEngineSelectorCannotReachAReleaseBuild FAILED
    java.lang.AssertionError: the engine section must be gated by engineControlsVisible(BuildConfig.DEBUG)
AppSettingsTabSplitTest > theWholeSettingsCatalogMovedAndNoneStayedBehind FAILED
    java.lang.AssertionError: "Engine generation" must appear in exactly one settings file (AboutSettings.kt), found in: [] expected:<[AboutSettings.kt]> but was:<[]>
10 tests completed, 2 failed
```

`EngineDebugPolicyTest` passed at this point, isolating the failure to the
missing UI.

**Two defects in my own new test, found and fixed before green** — recorded
because the fixes tightened the gate rather than weakening it:

1. First assertion used `Regex("EngineDebugSection\\(\\s*\\)")` to prove the
   section was not shown unconditionally. That regex matches the legitimate call
   site regardless of any guard, so it could never pass. Replaced with a
   structural check: exactly one call site, positioned after the guard.
2. That replacement then failed `expected:<1> but was:<2>` — the regex also
   matched the *declaration* `fun EngineDebugSection()`. Fixed with a
   `(?<!fun )` lookbehind.

Neither fix relaxed an existing gate; the primary assertion (guard present) was
unchanged throughout.

**Green.**

| Gate | Result |
|---|---|
| Focused (`AppSettingsTabSplitTest`, `engine.*`) | PASS — 23 tests |
| `:app:testDebugUnitTest` | PASS |
| `:app:ktlintCheck` | PASS |
| `:app:lintDebug` | PASS |
| `:app:assembleDebug` | PASS |
| Whole matrix | `BUILD SUCCESSFUL in 2m 3s` |

**Coverage strengthened:** two catalog entries added to
`AppSettingsTabSplitTest`, so the new controls are now covered by the
appears-in-exactly-one-file check, plus one new test pinning the release guard.

---

## 2026-08-13 — PR #96 merge and the ADR-0002 correction

**Merge:** `origin/main` (`54630a8`, PR #96) merged into this branch. Clean —
disjoint paths, no conflicts.

**Material finding: ADR-0002 was wrong.** It asserted that Fosfora and Colourful
Attraction do not exist. PR #96's `docs/visualizer-v2/provenance.json` supplies
concrete owner/repo pairs and pinned commits; fetching each URL directly
resolves all four questioned projects:

| Project | Repository | Licence | Verified by |
|---|---|---|---|
| Fosfora | `kevinraymond/fosfora` @ `09132c01` | MIT or Apache-2.0 | direct fetch |
| Colourful Attraction | `QC20/Colourful-Attraction` @ `6e502d36` | MIT | direct fetch |
| RDPE | `sqrew/rdpe` @ `28db17f8` | MIT (`Cargo.toml`; no LICENSE file) | direct fetch |
| ORPHIC | `adityarajashekaran/orphic` | AGPL-3.0 (dual) | direct fetch |

**Root cause:** the earlier audit searched by name and description keyword, and
treated the failed searches as proof of non-existence. `RESEARCH_AUDIT.md`
carried the correct caveat and it was not heeded.

**Binding rule adopted (ADR-0003):** no reference may be recorded as
non-existent without a fetch against a concrete URL. Absent a URL, the status is
`UNVERIFIED` — never "fabricated".

**Corrections applied in the same change:** ADR-0003 written and ADR-0002
marked superseded (retained unedited as the record of a wrong call);
`docs/RESEARCH_AUDIT.md` corrected; `LICENSE_LEDGER.md` now defers to
`provenance.json` and carries the three MIT/Apache rows plus ORPHIC as
prohibited. `Velo Visualiser`, `Musicya` and `Kiln` are relabelled `UNVERIFIED`.

**Consequence for Phase 3:** the audio-frame contract regains its external
model. Fosfora is MIT/Apache-2.0 and may be studied, and its source adapted
under notice obligations.
