# MusicViz 2.0 repository harness

Before any task, read `musicviz-project/musicviz/docs/v2/MASTER_PLAN.md`
completely. It is the sole implementation work order for the MusicViz 2.0
audio/visual overhaul. Then read `musicviz-project/musicviz/docs/v2/STATUS.md`,
the current accepted ADRs,
`musicviz-project/musicviz/docs/v2/RETIREMENT_LEDGER.md`, `git status`, and the
last two commits. Continue only the one active slice.

All other Markdown is non-authoritative unless `MASTER_PLAN.md` or the current
slice explicitly allowlists it. Never resume historical gauntlet, backlog,
review, changelog, agent, or quality-plan tasks.

## Repository facts

- Native Android/Kotlin app; Gradle root: `musicviz-project/musicviz`.
- Application module: `:app`; package: `dev.musicviz`.
- JDK 17+; Android SDK with compileSdk 36 at the harness baseline.
- GLES 3.0 is required; GLES 3.1 compute is optional.
- Run commands from `musicviz-project/musicviz/`.
- No SDK in the environment? Run `tools/setup-android-sdk.sh`, or point
  `local.properties` (`sdk.dir=...`) at an existing one. The script needs
  `dl.google.com` reachable. Verified working at the Phase 0 baseline.

## Required local gate

```bash
./gradlew :app:testDebugUnitTest
./gradlew :app:ktlintCheck
./gradlew :app:lintDebug
./gradlew :app:assembleDebug
```

Run focused tests first, then the required slice/full matrix from
`musicviz-project/musicviz/docs/v2/MASTER_PLAN.md`. Never report an unrun
command as passed.

## Hard rules

- Preserve user changes; never discard a dirty worktree.
- Do not extend frozen legacy visualizer architecture. Build V2 and bridge it.
- Preserve the pre-user-DSP PCM tap and real-time no-allocation/no-lock rule.
- Before moving/renaming source, search tests for hard-coded paths/identifiers.
- One active slice, one writer, one conventional commit after its gate.
- Update `musicviz-project/musicviz/docs/v2/STATUS.md`, `RETIREMENT_LEDGER.md`, and verification evidence
  before ending a session.
- Do not add dependencies, permissions, ABI/platform changes, unclear-license
  code, or destructive migrations without the decision process in the master plan.
