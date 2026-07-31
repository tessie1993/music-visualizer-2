# Working in this repository

Deliberately short, and deliberately free of version numbers, line references and
counts — those are exactly the things that went stale in every other doc here.

## Layout

- Git root: this directory.
- Gradle module: `musicviz-project/musicviz` — run every Gradle command there.
- Android app: Kotlin, Jetpack Compose, GL ES 3, libprojectM via JNI (arm64-v8a).

## Code wins over docs

Where a document and the code disagree, **the code is right and the document is
stale**. Read the source before acting on any doc claim, and fix the doc when you
find a mismatch. For anything versioned (SDK levels, versionCode/Name, defaults
like beat threshold), read `app/build.gradle.kts` or the relevant constant — never
a Markdown file.

## There is no Android SDK in this container

Gradle cannot run here. **CI is the gate of record**: `.github/workflows/android.yml`
runs ktlint + `checkThirdPartyNotices`, the Robolectric suite, `assembleDebug`, APK
verification, `bundleRelease`, the 16 KB native-alignment check, a "no INTERNET
permission" assertion, and lint.

Three of those catch things nothing else does:

- **`checkThirdPartyNotices`** fails if `app/src/main/assets/third_party_notices.txt`
  drifts from `THIRD_PARTY_NOTICES`. libprojectM is LGPL-2.1; the notice has to
  reach users, so the asset is a build-gated copy. Run `:app:syncThirdPartyNotices`
  after editing the source file.
- **`bundleRelease`** is the only place a missing R8 keep rule shows up. `pm_jni.c`
  registers JNI symbols statically, so `PMBridge`'s class *and* method names must
  survive minification. A mistake here fails at runtime, not at build time.
- **ktlint** runs on `.kts` as well as `.kt`. It checks formatting only — it will
  not catch an unresolved reference in a build script.

Local verification that *does* work: read the code, and run ktlint if you have a
standalone jar. Do not claim a change builds until CI says so.

## Documents and what owns what

| Topic | File |
| --- | --- |
| Open work, working rules | `musicviz-project/musicviz/todo.md` |
| Changelog | `musicviz-project/musicviz/README.md` |
| On-device checks | `docs/DEVICE_CHECKS.md` |
| Param × scene coverage | `docs/PARAM_MATRIX.md` |
| Navigation / wireframes | `docs/NAVIGATION.md`, `docs/WIREFRAME.md` |
| Fluid design rationale | `docs/ORGANIC_MOTION.md` |
| Play Store release | `docs/PLAY_STORE_RELEASE.md` |
| Privacy policy | `docs/PRIVACY_POLICY.md` (a copy is hosted from `docs/` at the repo root) |
| Native rebuild recipe | `tools/build-projectm.md` |

**Frozen numbering.** `docs/DEVICE_CHECKS.md` items and `docs/ORGANIC_MOTION.md`
sections A and D are cited *by ordinal* from workflows, other docs and source
comments. Annotate in place; append at the end; never renumber.

**`FLUID_SIM_2.md` does not exist** and never has in this repository, despite being
cited by many files. The spec of record for `render/fluid/` is the code plus its
headless tests.

## Parallel sessions

Several agents commit to `main` concurrently, and it moves fast. Merge `main` into
your branch **before** starting a round and again before opening a PR — otherwise
you resolve a large backlog at the worst moment. `docs/DEVICE_CHECKS.md` collides
often because both sides append; keep main's numbering and move yours to the end.

Before committing, `git diff` anything you did not write, and integrate rather
than discard it.

## When you change behaviour

Update the doc that owns the topic and add a `README.md` changelog entry. GL and
audio output cannot be verified headlessly — when a change affects rendering,
playback or the native engine, add a `docs/DEVICE_CHECKS.md` item and say plainly
in your summary that it is unverified until someone runs it on a device.
