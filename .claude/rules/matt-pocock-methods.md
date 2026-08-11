# Matt Pocock Methods — How Claude Codes in This Repo

Working rules distilled from Matt Pocock's agent-workflow writing (AI Hero,
mattpocock/skills) and his type-safety craft (Total TypeScript), translated
to Kotlin/Android for this codebase. These complement `CLAUDE.md` and
`.claude/rules/ecc/kotlin/`; where they overlap, the stricter rule wins.

## 1. Align before you code (the "grill me" phase)

- Never jump straight to implementation on a non-trivial request. First
  restate the problem, surface the design decisions, and list the modules
  that will change. If the request is ambiguous, ask — one round of good
  questions beats three rounds of rework.
- For anything bigger than a small fix, write a short plan first: problem
  statement, chosen approach, files/modules touched, and how it will be
  tested. The plan is a hint, not scripture — don't gold-plate it; real
  value emerges during implementation and review.
- Planning stays human-in-the-loop. Propose; don't silently commit to
  architectural decisions the user hasn't seen.

## 2. Work in vertical slices, not horizontal layers

- Break features into thin slices that cut through every layer (data →
  engine → UI) and produce something runnable/testable at each step. Never
  build layer-by-layer and integrate at the end — that's coding blind, and
  integration bugs surface where they're most expensive.
- One slice = one concern = one conventional commit. Keep diffs small and
  reviewable. If a task is ballooning, stop and split it.

## 3. TDD and feedback loops are the quality lever

- "If your codebase doesn't have feedback loops you're never going to get
  decent AI output." Red-green-refactor: write (or extend) a failing test,
  make it pass, then clean up. Do not write implementation and tests as one
  parallel blob — that lets the implementation cheat.
- Wrap tests around module boundaries (the public interface of a deep
  module), not around every private function. Boundary tests catch
  integration issues and survive refactors.
- After every slice, actually run the loops before claiming done:
  `./gradlew :app:testDebugUnitTest`, `:app:ktlintCheck`, `:app:lintDebug`.
  Failing output gets reported verbatim, never papered over.
- Remember this repo's source-text gate tests (`ParamSurface*` /
  `ParamMatrix.kt`): moving or renaming main-source files requires updating
  the gates in the same change.

## 4. Deep modules, simple interfaces

- Prefer few deep modules (simple external API, rich internals) over many
  shallow ones with tangled cross-file dependencies. Shallow, coupled
  modules create integration risk and make every future change harder.
- Design the interface first, then implement behind it. If a change forces
  callers to know internals, the boundary is wrong — fix the boundary, not
  the callers.
- Leave module boundaries better than you found them; don't add "just one
  more helper file" that smears a concern across the tree.

## 5. Make invalid states unrepresentable (types first)

Pocock's TypeScript rules, in their Kotlin form:

- Model closed state spaces as `sealed interface`/`sealed class` (Kotlin's
  discriminated unions) with exhaustive `when` and **no `else` branch**, so
  new variants fail compilation instead of failing at runtime.
- Design the data types before the runtime code. If two fields can
  contradict each other (`isLoading` + `error` + `data` all nullable),
  replace them with one sealed state.
- The `any` rule: no `Any` in public signatures, no unchecked casts, no
  `!!`, no suppressed warnings to "get it through the compiler". If you're
  fighting the type system, the model is wrong.
- Expected failures are values, not exceptions: return a sealed result type
  (or `Result`) from operations that can legitimately fail; reserve thrown
  exceptions for bugs.
- Parse, don't validate: convert raw input (prefs, files, intents, audio
  session config) into well-typed domain objects once, at the boundary, and
  pass only typed values inward.
- Let inference work locally, but write explicit types on public API
  signatures — they're documentation and a compatibility contract.
- Exception (from `CLAUDE.md`): real-time render/audio hot paths reuse
  preallocated mutable buffers by design. Do not "fix" them into immutable
  style.

## 6. Context discipline

- Agents have no memory — the repo's docs and tests are the memory. When a
  decision matters beyond this session, encode it in code, a test, or the
  relevant doc; don't leave it in chat.
- Size tasks so they fit comfortably in one focused session (the "smart
  zone"). A fresh, small, well-specified task beats a sprawling one worked
  in a degraded long context. Prefer finishing and committing a slice over
  starting a second concern mid-stream.
- Pull knowledge when needed (read the relevant rule/skill/doc for the file
  you're touching) instead of guessing from memory.

## 7. Review is where taste is imposed

- Before every commit, re-read the full diff as a skeptical reviewer:
  naming, dead code, accidental API changes, comment rot, allocation in
  hot paths. QA is how human taste gets imposed on the codebase — make the
  diff easy to judge.
- Report honestly: what was done, what was verified (with which commands),
  what was deliberately skipped, and any known risks. "Done" means the
  feedback loops passed, not that the code was written.

## Session shape (summary)

1. Grill/align → tiny plan (files, approach, tests).
2. Slice vertically; pick the tracer bullet first.
3. Red → green → refactor per slice; run the Gradle feedback loops.
4. Self-review the diff; fix what a reviewer would flag.
5. Conventional commit per slice; push; report honestly.
