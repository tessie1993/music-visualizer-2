# Geode (repo: music-visualizer-2)

Android/Kotlin music player and visualizer, branded **Geode** (`dev.geode`). The app lives in `musicviz-project/musicviz`
(Gradle root; the `:app` module is the application).

## Build & Checks

Run from `musicviz-project/musicviz/`:

```bash
./gradlew :app:assembleDebug       # build
./gradlew :app:lintDebug           # Android lint
./gradlew :app:ktlintCheck         # Kotlin style
./gradlew :app:detekt              # static analysis
```

- The repository has no test suites; the build, lint, ktlint and detekt
  gates above are the verification loop.
- Requires JDK 17+ and Android SDK with compileSdk 36.
- SDK setup: run `tools/setup-android-sdk.sh`, or point `local.properties`
  (`sdk.dir=...`) at an existing SDK, e.g. `/home/user/android-sdk`.

## Commits

Conventional commits: `feat:`, `fix:`, `refactor:`, `docs:`, `test:`, `chore:`.

## Coding Rules

- `.claude/rules/ecc/kotlin/` applies to all `**/*.kt` files.
- `.claude/rules/matt-pocock-methods.md` — workflow rules (plan → vertical
  slices → TDD → review) for all Claude coding sessions in this repo.
- Immutability by default: prefer `val`, data classes, and copies over
  in-place mutation — EXCEPT in the real-time render/audio hot paths,
  which deliberately reuse preallocated mutable buffers to avoid
  per-frame allocation. Do not "fix" those into immutable style.
- Never commit secrets (API keys, tokens, keystores, passwords). Use
  environment variables or untracked local files.

## Key Docs

- `musicviz-project/musicviz/docs/visualizer-v2/STATUS.md` — the Visualizer 2.0
  slice log; says which slice is active. Read it before touching engine code.
- `musicviz-project/musicviz/docs/AUDIO_CHAIN.md` — audio chain order, the
  tap-first invariant, and why DSP never moves the visuals
- `musicviz-project/musicviz/docs/quality/QUALITY_BAR.md` — quality bar
- `musicviz-project/musicviz/docs/quality/PRODUCT_REVIEW.md` — product review
- `musicviz-project/musicviz/docs/quality/GAUNTLET_BACKLOG.md` — gauntlet backlog
- `musicviz-project/musicviz/docs/PARAM_MATRIX.md` — parameter matrix
