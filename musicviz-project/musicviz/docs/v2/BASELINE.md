# MusicViz 2.0 Baseline

The measured starting point, recorded before any production edit.

## Repository state

| Item | Value |
|---|---|
| Branch | `claude/audio-visualizer-research-d8d92d-ifayw9` |
| HEAD at baseline run | `faafe8f` (purge commit 0.0) |
| Master plan's audit SHA | `05aca01ca0d7162c204ac803040b5cda74a97877` |
| Intervening commits | `5ceef8f` — `docs: verify the overhaul's sources and audit the MilkDrop path`. Adds `docs/RESEARCH_AUDIT.md` only; no production change. Its licence findings drive ADR-0002. |
| Worktree at start | Clean |
| Open PR | #97 |

HEAD is **newer** than the plan's audit baseline. The one intervening commit is
documentation, so no plan claim is invalidated by it. Divergences found between
the plan's audit table and actual code are recorded in `INVENTORY.md`.

## Environment

| Item | Value |
|---|---|
| OS | Linux 6.18.5, x86_64 container |
| JDK | OpenJDK 21.0.10 (repo requires 17+; AGP toolchain targets 17) |
| Gradle | 8.13 via wrapper |
| Android SDK | Installed during Phase 0 by `tools/setup-android-sdk.sh` → `/root/android-sdk`, `sdk.dir` written to `local.properties` |
| compileSdk / targetSdk / minSdk | 36 / 36 / 26 |
| ABI | `arm64-v8a` only (app-level `abiFilters`) |
| Physical or emulated device | **None available.** The app is arm64-only, so no x86_64 emulator image can run it. |

## Baseline gate results

Run 2026-08-13 from `musicviz-project/musicviz`, one invocation:

```bash
./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:lintDebug :app:assembleDebug
```

| Gate | Result | Detail |
|---|---|---|
| `:app:testDebugUnitTest` | **PASS** | 1,185 tests, 0 failures, 0 errors, 0 skipped, across 163 test classes |
| `:app:ktlintCheck` | **PASS** | — |
| `:app:lintDebug` | **PASS** | Report at `app/build/reports/lint-results-debug.html` |
| `:app:assembleDebug` | **PASS** | `app-debug.apk` produced |
| **Total** | `BUILD SUCCESSFUL in 8m 42s` | 66 actionable tasks, 65 executed, 1 from cache |

This is a genuine green baseline, not an inherited claim. The master plan's own
audit could not start Gradle at all; that limitation is now resolved.

Non-fatal warnings observed (recorded, not fixed — they are pre-existing and
out of slice):

- `stripDebugDebugSymbols` cannot strip `libprojectM-4.so`, `libprojectmjni.so`,
  `libandroidx.graphics.path.so` — they ship unstripped in the debug artifact.
- Deprecation warnings in `MilkImportNameTest`, `MvzAudioProcessorChainTest`,
  `SleepTimerTest`; a nullable-receiver warning in `PrefsRoundtripReflectionTest`.

## Artifact size

| Item | Value |
|---|---|
| `app-debug.apk` | 331 MiB (347,204,204 bytes); ~395 MB uncompressed, 1,360 entries |
| Largest entries | `classes.dex` 43.6 MB; `libprojectM-4.so` 12.5 MB; `classes12.dex` 12.2 MB; `classes13.dex` 9.7 MB |
| `res/` PNG payload | **930 files, ~290 MB** — the `tp_<mineral>_*` theme-pack assets |

Debug is unminified (`isMinifyEnabled = false`), so release will be
substantially smaller — but the 290 MB of PNG theme assets is source data that
shrinking cannot remove. Measuring `bundleRelease` size is **NOT RUN**; it is
required before any size claim is made.

## Not run at baseline

| Gate | Status | Reason |
|---|---|---|
| `:app:connectedDebugAndroidTest` | `BLOCKED_ENVIRONMENT` | No device; arm64-only build rules out an emulator |
| `:app:bundleRelease` | `NOT RUN` | Release signing not configured in this environment |
| Every device, GL, performance, thermal, A/V-sync and 16 KB gate | `BLOCKED_ENVIRONMENT` | See `DEVICE_MATRIX.md` |
| Startup time, analysis timing, allocation profile, frame times, memory trace | `BLOCKED_ENVIRONMENT` | Require a device |

Plan §0.1 action 3 asks for baseline performance capture. It is unobtainable
here and is recorded as blocked rather than estimated. **This hard-blocks the
Phase 4 exit gate onward**; Phases 0–3 are JVM-testable and can proceed.

## Reproducing

From a clean checkout with network access to `dl.google.com`:

```bash
bash musicviz-project/musicviz/tools/setup-android-sdk.sh
cd musicviz-project/musicviz
./gradlew :app:testDebugUnitTest :app:ktlintCheck :app:lintDebug :app:assembleDebug
```

First run takes ~9 minutes and populates ~3 GB in `~/.gradle`.
