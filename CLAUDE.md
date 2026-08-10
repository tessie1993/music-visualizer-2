# music-visualizer-2

Android/Kotlin music visualizer. The app lives in `musicviz-project/musicviz`
(Gradle root; the `:app` module is the application).

## Build & Test

Run from `musicviz-project/musicviz/`:

```bash
./gradlew :app:testDebugUnitTest   # unit tests
./gradlew :app:lintDebug           # Android lint
./gradlew :app:ktlintCheck         # Kotlin style
```

- Requires JDK 17+ and Android SDK with compileSdk 36.
- SDK setup: run `tools/setup-android-sdk.sh`, or point `local.properties`
  (`sdk.dir=...`) at an existing SDK, e.g. `/home/user/android-sdk`.

## Source-Text Test Gates (read before moving files)

About 40 unit tests parse the main source tree as text. In particular,
`app/src/test/java/dev/musicviz/ParamSurface*`/`ParamMatrix.kt` hard-code
source file paths. If you move or rename a main-source file, update the
corresponding gate tests in the same change or the suite fails.

## Commits

Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`.

## Coding Rules

- `.claude/rules/ecc/kotlin/` applies to all `**/*.kt` files.
- Immutability by default: prefer `val`, data classes, and copies over
  in-place mutation — EXCEPT in the real-time render/audio hot paths,
  which deliberately reuse preallocated mutable buffers to avoid
  per-frame allocation. Do not "fix" those into immutable style.
- Never commit secrets (API keys, tokens, keystores, passwords). Use
  environment variables or untracked local files.

## Key Docs

- `musicviz-project/musicviz/docs/quality/QUALITY_BAR.md` — quality bar
- `musicviz-project/musicviz/docs/quality/PRODUCT_REVIEW.md` — product review
- `musicviz-project/musicviz/docs/quality/GAUNTLET_BACKLOG.md` — gauntlet backlog
- `musicviz-project/musicviz/docs/PARAM_MATRIX.md` — parameter matrix
