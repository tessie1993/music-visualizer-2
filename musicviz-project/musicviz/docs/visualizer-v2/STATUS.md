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

## V2-1-01: add build conventions and whole-project gates

State: COMPLETE

Goal: give the module split somewhere to put shared configuration, and one command that
covers every module including the ones that do not exist yet.

User-visible effect: none. Build configuration only.

In scope: a `build-logic` included build; the `musicviz.kotlin-common` convention plugin
carrying the JDK target and ktlint; `:app` adopting it; a root `checkAll` declared over
`subprojects` so a new module is covered the day it appears.

Out of scope: extracting the rest of `app/build.gradle.kts`. Signing, packaging, Robolectric
jar resolution and the Compose setup belong to the application module and to nothing else —
there is no second consumer to share them with, and a convention plugin with one caller is
indirection rather than convention. What the engine modules need gets extracted when they
exist, in V2-1-02.

Files expected to change: `settings.gradle.kts`, `build.gradle.kts`, `app/build.gradle.kts`,
`gradle/libs.versions.toml`, `build-logic/`.

Compatibility contract: unchanged. The convention plugin sets the same JVM target and the same
ktlint configuration `:app` already resolved to, so no source file is formatted differently.

External source/provenance entries: none.

Tests written first: none, and the exception is worth stating rather than glossing. A Gradle
convention plugin has no unit-test seam here — what it does is observable only as build
behaviour, so the proof is that `checkAll` covers `:app`, that the suite and lint are
unchanged, and that removing the plugin breaks compilation. A test-fixture abstraction over
`repoFile`, which §V2-1-01 also mentions, is deliberately left to the slice that first moves
a file — writing it before then would be a helper with nothing to help.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. `:app` returns to declaring ktlint and its JVM target inline.

Risks: an included build is a real change to how the build resolves plugins, and it runs
before everything. Mitigated by keeping the plugin to two settings and by running the full
suite, lint, ktlint and detekt afterwards.

Commands and results: below.

Review findings: **`checkAll` failed on its first run**, and not on anything this slice wrote.
`detekt` reported `FlashBudget.gainFor` with four returns against a limit of two — code from
V2-0-02b. It passed that slice's gates because §2.4's verification order lists unit tests,
ktlint, lint and assemble, and **detekt is in none of them**; `:app:check` was the only path
that ran it, and no slice had been running `check`. That is precisely the gap this slice
exists to close, found by the thing built to find it. `gainFor` is now a single `when` with
one return — the same four branches, better shape — and detekt joins the per-slice list.

Commit: `build: add convention plugins and a check that covers every module`

Next slice: **V2-1-02 — create the six engine modules.**

### Verification

| Command | Result |
|---|---|
| `checkAll`, first run | **FAILED** on `detekt`: `FlashBudget.gainFor` ReturnCount 4 > 2 |
| `checkAll`, after the fix | BUILD SUCCESSFUL, 93 tasks |
| `:app:testDebugUnitTest` | **1,237 tests, 0 failures** — unchanged by this slice |
| `:app:ktlintCheck` | BUILD SUCCESSFUL through the convention plugin |
| `:app:detekt` | BUILD SUCCESSFUL |

---

## V2-0-04: collect runtime baseline

State: LOCKED

Goal: measure what the current engine actually does on real hardware, so the V2 budgets in
§14 and the Lite/Balanced/Ultra tiers in §6.7 are set from evidence rather than from the
plan's provisional numbers.

User-visible effect: none. Measurement only.

In scope: golden frames for all 38 scene IDs and the 22 named Hyperspace/Cymatics looks;
cold and warm scene creation; steady-state allocations; CPU and GPU p50/p95; memory;
context-loss recovery; transition spikes; export and wallpaper timings; scatter/deposit,
float-target, vertex-fetch and timer-query probes on one current Mali and one current Adreno;
`PERFORMANCE_BASELINE.md` with raw captures and device metadata.

Out of scope: setting any budget. §6.7's particle counts and grid sizes stay provisional until
this slice produces the evidence — "do not lock these numbers until scatter/deposit and
overdraw tests run on a real Mali and Adreno device".

Files expected to change: `docs/visualizer-v2/PERFORMANCE_BASELINE.md`,
`docs/visualizer-v2/benchmarks/`, `docs/visualizer-v2/captures/`, and a capture harness
under `app/src/androidTest/`.

Compatibility contract: untouched; nothing here changes behaviour.

External source/provenance entries: none.

Tests written first: not started. The harness is itself the deliverable and cannot be written
blind — what it captures depends on what the timer queries turn out to report.

Benchmark or visual evidence: **this slice is the evidence.** None of it exists.

Rollback: nothing to roll back.

Risks: the risk is doing it badly rather than not doing it. A benchmark table with no device
behind it is worse than an empty one, because the next session would build budgets on numbers
nobody measured. §2.1 rule 8 lists what a benchmark must record — device, OS, GPU, thermal
state, build variant, scene, quality tier, resolution, sample count, median, p95 and raw
evidence location — and none of those can be invented.

Commands and results: none run.

Review findings: none yet.

Commit: none.

Next slice: **V2-1-01 — add build conventions and whole-project gates.**

### Why this is parked, and what lifts it

Every deliverable needs a physical device. This session runs headless: no GPU, no
`adb`-reachable hardware, no thermal envelope. The parts that look software-only are not —
golden frames need a GL context, and allocation counts need the ART heap the app actually
runs on.

It lifts when **an Android device is reachable from the session, or a CI job with one is
wired up**, specifically:

| Needed | Why that one |
|---|---|
| a current Mali device | tiler binning and fill rate are where GLES 3.0 scatter/deposit collapses first (§16) |
| a current Adreno device | the other half of the scatter/deposit matrix; different driver behaviour for float render targets |
| one lower-tier GLES 3.0 device | sets the Lite tier honestly rather than by scaling down from a flagship |

`adr/0002` records why parking it does not stop the queue: `LOCKED` means specified and not
begun, so V2-1-01 onward proceed while this stays visibly open.

---

## V2-0-03: verify and gate 16 KB native libraries

State: COMPLETE

Goal: check the binaries that actually ship, not the ones a workflow happens to build.

User-visible effect: none today. **Release builds now fail**, deliberately — see the finding
below. Debug builds, tests and lint are unaffected.

In scope: `checkNativePageAlignment`, a Gradle task that reads ELF program headers out of the
`.so` entries inside the packaged APK or AAB and is wired to `assembleRelease`/`bundleRelease`;
`NativeLibraryAlignmentTest`, which does the same over the checked-in `jniLibs` sources.

Out of scope: rebuilding the libraries. That is NDK r28 plus a full projectM CMake build —
`.github/workflows/native-libs.yml`, which budgets 90 minutes — and it would produce native
binaries no device here can load. Also out of scope: the second ABI. `abiFilters` is
`arm64-v8a` alone, so there is one to check.

Files expected to change: `app/build.gradle.kts`,
`app/src/test/java/dev/musicviz/NativeLibraryAlignmentTest.kt`.

Compatibility contract: nothing user-facing. No packaging option, ABI or dependency changes;
the gate only reads what the existing build already produces.

External source/provenance entries: none. The ELF64 layout is the published format; no code
was taken from anywhere.

Tests written first: three. The load-bearing one is the positive control — it copies a real
library, rewrites `p_align` to 16384 in every `PT_LOAD` header, and asserts the reader now
reports 16384. Without it a reader that returned 4096 for everything would pass the main
assertion for the wrong reason and keep passing after a real rebuild fixed the libraries.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit. The gate is additive.

Risks: the release gate is red until the rebuild lands, which is the intended behaviour and
still needs saying out loud — anyone cutting a release will hit it. The alternative is
shipping an app that does not start on a 16 KB-page device.

Commands and results: below.

Review findings: the ELF reader now exists twice, in the Gradle task and in the test. That is
deliberate for one slice — the task reads a zip entry and the test reads a file — and
**V2-1-01 collapses it into the build-conventions plugin**, which is the next slice and the
right home for logic two modules will want.

Commit: `feat(build): gate release artifacts on 16 KB page alignment`

Next slice: **V2-0-04 — collect runtime baseline.**

### The finding

Both shipped libraries are **4 KB aligned**, and the app targets SDK 36:

```
app-debug.apk!lib/arm64-v8a/libprojectM-4.so   aligned to 4096
app-debug.apk!lib/arm64-v8a/libprojectmjni.so  aligned to 4096
```

Android 15 ships devices with 16 KB memory pages, and a library laid out for 4 KB will not
load on them. `MASTER_PLAN.md` §1.2 listed this as unverified; it is now verified, and it
fails.

The repository was not unaware of the requirement — `native-libs.yml` is literally titled
"Rebuild native libs (16 KB aligned)" and verifies alignment on its own output. The gap was
narrower and easier to miss: **a workflow that checks what it builds says nothing about
whether that output was ever committed.** The binaries in `jniLibs` predate it.

Fixing it is one run of that workflow followed by committing the artifacts. Until then the
release path is blocked, which is the correct failure: an unloadable app is worse than an
unbuilt one.

### Verification

| Command | Result |
|---|---|
| `:app:checkNativePageAlignment` | **FAILED**, naming both libraries and their 4096 alignment — the finding above |
| `:app:testDebugUnitTest --tests '*NativeLibraryAlignmentTest*'` | 3 passed, including the 16 KB positive control |
| `:app:testDebugUnitTest` | **1,237 tests, 0 failures** (1,234 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL, after `ktlintFormat` on the buildscript |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL — the gate is on the release outputs only |

`assembleRelease` was not run: it needs signing configuration this container does not have.
The gate was exercised directly against the debug APK instead, which is the same code path
over the same kind of archive.

---

## V2-0-02b: bound how often the beat flash may fire

State: COMPLETE

Goal: close the one full-frame luminance event whose *rate* nothing downstream controls.
`VisualSafety` bounds how big a flash may be and `strobeHz` bounds the strobe's oscillator,
but the beat flash fires at the track's rate, and the only lever on that sits upstream in the
analyzer where four things can still change the answer.

User-visible effect: at high beat rates the flash is held to three per second and the excess
rolls off instead of firing. Nothing changes below that rate, or for a Custom opt-out.

In scope: `FlashBudget`; `VisualSafety.flashImpulse`; the gain applied at the two `uPostFlash`
upload sites, live and export; ADR 0001 for the deviation from §11.2.

Out of scope: measuring the frame. §11.2's limiter is defined over measured luminance and
saturated-red change, which needs a downsampled target, an async PBO readback and a device to
prove the readback does not stall — none of which exist here. That is **V2-0-02c**, and
until it lands a projectM preset, a Shader Studio shader or a scene's own internal brightness
can still flash without the budget seeing it, because none of those passes through
`uPostFlash`. The `alternating stripes` and `red transition` vectors §11.2 names are part of
that slice for the same reason: both are frame-content tests.

Files expected to change: `app/src/main/java/dev/musicviz/render/{FlashBudget,VisualSafety,VisualizerRenderer}.kt`,
`app/src/main/java/dev/musicviz/export/{FxCompositor,VideoExporter}.kt`,
`app/src/test/java/dev/musicviz/FlashBudgetTest.kt`,
`docs/visualizer-v2/{DECISIONS.md,adr/0001-flash-budget-follows-the-safety-choice.md}`.

Compatibility contract: no uniform is added or renamed, so `CompositeUniformParityTest` still
compares the same two sets. `SafetyConfig.OFF` stays an exact no-op, which is what the export
byte-parity tests rest on. No preset key, scene ID or audio semantic moves.

External source/provenance entries: none. The three-per-second figure is WCAG 2.3.1, already
cited by `VisualSafety` and already in the tree.

Tests written first: `FlashBudgetTest`, eleven assertions. Ten are behavioural vectors on the
pure limiter; the eleventh is the bypass gate, and it was proved non-vacuous by stripping the
gain from the renderer and watching it name the exact offending line.

Benchmark or visual evidence: none, and none is claimed. The limiter is arithmetic on a
16-entry ring with no allocation; what it needs is a device, and that belongs to V2-0-02c.

Rollback: revert the one commit. Both upload sites return to the raw parameter.

Risks: the estimate is `flash × beat × 0.6`, the product the shader is about to apply — a
real quantity, but a parameter estimate rather than a measurement, so `RISK_THRESHOLD` is set
below WCAG's 10% of full scale deliberately. If it turns out to suppress flashes a viewer
would not have perceived, the threshold moves by evidence and an ADR, never by editing a test
until it passes.

Commands and results: below.

Review findings: three.

1. The first draft had the budget observe `fx.flash` alone. The shader applies
   `uPostFlash × uBeat × 0.6`, so a flash of 1.0 on a frame with no beat under it changes
   nothing — and would have spent budget on a non-event. It now judges the product, and
   `flashImpulse` lives in `VisualSafety` because that is where the shader's coefficients are
   already documented.
2. A stateful per-frame call is only correct if it runs once per frame, so both call sites
   were traced rather than assumed: the renderer's upload is inline in `onDrawFrame`, and
   `FxCompositor.composite` is called once per exported frame. Had either sat inside the
   transition or layer path it would have double-counted every edge.
3. The `FxCompositor` doc first claimed live and export "arrive at the same gains". They
   arrive at the same *rule*; identical gains need the sample-locked clock §10.3 is working
   toward, because live advances on a jittering wall clock. Corrected rather than left as a
   claim the code does not support.

Commit: `feat(safety): hold the beat flash to three per second`

Next slice: **V2-0-03 — verify and gate 16 KB native libraries.**

### What the budget counts

Rising edges past a risk threshold, inside a rolling second — not frames. The distinction is
the whole design:

| Input | Treated as | Why |
|---|---|---|
| impulse rises past the threshold | one flash, budget spent | this is the event WCAG counts |
| impulse held high for 60 frames | one flash | a bright scene is not a strobe |
| impulse below the threshold | not a flash | too small to be the hazard |
| the 4th rise in one second | rolled off below the threshold | not cut to zero: a cut to black is itself a full-frame change |
| the clock stepping backwards | a new session | `uTime` wraps at `TIME_WRAP_SEC`, so this is normal, not exceptional |

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*FlashBudgetTest*'`, gain stripped from the renderer | 11 tests, 1 failed, naming the exact bypassed line |
| `:app:testDebugUnitTest --tests '*FlashBudgetTest*'` | 11 passed |
| `:app:testDebugUnitTest` | **1,234 tests, 0 failures** (1,223 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-0-02a: make visual safety a versioned choice

State: COMPLETE

Goal: stop one boolean answering two different questions. "Off by default" meant both *this
person wants the strobe* and *nobody has ever been asked*, and the app could not tell them
apart. Flash safety is now a four-valued, versioned choice whose unknown state runs safe.

User-visible effect: **an install that has never chosen runs with flash limiting on.** The
9 Hz strobe, the beat flash and a randomized 30 Hz luminance LFO are all bounded until the
user says otherwise, and the settings screen says so in words rather than leaving them to
wonder why the strobe looks tame. Anyone who wants the unlimited behaviour picks Custom.

In scope: `VisualSafetyChoice` and `VisualSafety.resolve`; `GuiPrefs.safetyChoice` and the
resolution of `GuiPrefs.safety` through it; versioned persistence and the legacy migration;
the settings UI replacing the switch with the choice.

Out of scope: the temporary global flash limiter §11.2 asks for, and the audit that projectM,
user shaders and legacy bridges also traverse it — both moved to **V2-0-02b**. Also the
separate reduced-motion, brightness, transition and chromatic controls of §11.3;
`REDUCED_MOTION` covers the motion half today and the rest is a later slice.

Files expected to change: `app/src/main/java/dev/musicviz/render/VisualSafety.kt`,
`app/src/main/java/dev/musicviz/ui/{AppTheme,BehaviorSettings}.kt`,
`app/src/test/java/dev/musicviz/{VisualSafetyChoiceTest,AppSettingsTabSplitTest}.kt`.

Compatibility contract: no preset key, scene ID or audio semantic changes. `gui_safe_visuals`
is still written and still read, so a downgrade to a build without the choice finds the
prefs file it expects. Presets are untouched: safety clamps final params, it does not
rewrite stored ones.

External source/provenance entries: none.

Tests written first: `VisualSafetyChoiceTest`, twelve assertions, run red as a compile
failure (`Unresolved reference 'VisualSafetyChoice'`) before any of it existed. They pin both
directions of the migration, the version gate, the unreadable-name case, and the property the
whole slice exists for — that only CUSTOM can reach `enabled = false`.

Benchmark or visual evidence: none. The resolution is pure and the clamp it feeds already had
its own tests.

Rollback: revert the one commit. Stored `gui_safety_choice` keys become inert; the legacy
boolean the old build reads was never stopped being written.

Risks: this changes what existing users see on upgrade, which is the intended behaviour of
§11.1 and still worth stating plainly. Someone who had the strobe and never touched the
switch will find it limited until they pick Custom. The alternative — treating an untouched
default as consent to a 9 Hz full-frame strobe — is the thing the plan forbids.

Commands and results: below.

Review findings: three, all from re-reading rather than from a failing test.

1. `AppSettingsTabSplitTest` pins the Safe-visuals control to `BehaviorSettings.kt` by
   searching for the literal `"Safe visuals"`. After the rewrite that control no longer
   exists, and the gate still passed — on two passing mentions of the phrase in unrelated
   body text. Exactly the vacuous-gate failure §18.3 asks about. The gate now names the
   labels the controls actually carry.
2. The standalone "Reduced motion" switch would have become a control that does nothing
   under SAFE, since the choice resolves motion scaling itself. It moved inside CUSTOM,
   where it is a parameter rather than a contradiction.
3. `SafetyConfig`'s doc said the false default was a deliberate product position and pointed
   at an open question in `PRODUCT_REVIEW.md`. That question is now answered, so the comment
   would have argued against the code. Rewritten to say what the defaults are actually for —
   keeping `OFF` an exact no-op for export byte-parity.

Commit: `feat(safety): make flash safety a versioned choice that defaults to safe`

Next slice: **V2-0-02b — global flash limiter and the paths that bypass the clamp.**

### Why the boolean could not answer this

`saveGui` writes every key on every save. So the first time a user changes any unrelated
setting, `gui_safe_visuals=false` is written for them — an untouched switch and a deliberately
disabled one are byte-identical in the prefs file. There is no way to read consent out of it,
which is why §11.1 says not to try.

The migration therefore runs one way only:

| Stored | Resolves to | Why |
|---|---|---|
| nothing | UNKNOWN → safe | fresh install, or one that predates the choice |
| `gui_safe_visuals=false` | UNKNOWN → safe | proves nothing; written by any other settings change |
| `gui_safe_visuals=true` | SAFE | false was the default, so true was deliberate |
| a choice at the current version | that choice | the explicit answer wins over the legacy key |
| a choice at an older version | UNKNOWN → safe | consent was to behaviours that have since changed |
| an unknown name | UNKNOWN → safe | downgrade or corruption; never a guess |

Resolution happens in exactly one function, and all four outputs — live renderer, transition
picker, exporter and wallpaper — already read `GuiPrefs.safety`, so none of them can disagree
about what was chosen.

### Verification

| Command | Result |
|---|---|
| `:app:compileDebugUnitTestKotlin`, before the implementation | 12 unresolved references — the intended red |
| `:app:testDebugUnitTest --tests '*VisualSafetyChoiceTest*'` | 12 passed |
| `:app:testDebugUnitTest` | **1,223 tests, 0 failures** (1,211 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL, after `ktlintFormat` fixed import order |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

Not verified here, and left open: how the choice reads on a device with TalkBack.
`CrystalSegmented` carries `Role.RadioButton` on a `selectable`, which is the right
semantics for a three-way choice and better than the switch it replaces, but that is a
code-level claim rather than a tested one.

---

## V2-0-01: fix the first shared-player acquisition hold

State: COMPLETE

Goal: make the first hold of the process survive the binding that creates the player, so no
release from a second owner can free a player somebody is still using.

User-visible effect: **background playback stops dying when the screen closes.** The service
starts playback, a screen opens over it, the user leaves the screen — before this the music
stopped and every later call the service made on that ExoPlayer threw.

In scope: the two `PlaybackEngine.acquireFor*` methods and four tests.

Out of scope: everything else in `PlaybackEngine`. The reference-counting design, the
deliberate drop-without-release in `rebindTo` and the `PlaybackSession` lifetime are all
correct and untouched. No new API, no debug accessor — the tests observe the defect through
the same surface a caller uses.

Files expected to change: `app/src/main/java/dev/musicviz/playback/PlaybackEngine.kt`,
`app/src/test/java/dev/musicviz/playback/PlaybackEngineTest.kt`.

Compatibility contract: unchanged. `acquireForUi`/`acquireForService`/`releaseUi`/
`releaseService` keep their signatures and their meaning; only the order of two statements
inside the acquire methods moves.

External source/provenance entries: none.

Tests written first: three added to `PlaybackEngineTest`, run red before the fix. Two failed
(the two orderings that reach a user) and the third passed, which is what made it worth
writing — it pins the other half of the invariant, that the player is still released once
both owners let go, so the fix cannot turn a lost hold into a leaked player.

Benchmark or visual evidence: not applicable; this is a lifecycle defect, not a render path.

Rollback: revert the one commit. The two methods return to their previous bodies.

Risks: the opposite failure — a player that never goes away — is the thing a fix like this
causes, and it is covered by the third test. Not covered here: the wallpaper's relationship
to the player hold, which `MASTER_PLAN.md` §10.4 owns and V2-8-02 verifies on a device.

Commands and results: below.

Review findings: both production acquire sites were re-read — `PlayerViewModel:184` and
`PlaybackService:48` — and each takes exactly one hold that is given back in `onCleared` and
`onDestroy` respectively. No third acquirer exists. The rebind path itself is exercised only
implicitly, by Robolectric handing each test method a fresh Application; asserting it
directly would need two live Applications in one method, which Robolectric does not give,
so it stays an implicit guarantee rather than a claimed one.

Commit: `fix(playback): keep the first hold taken on the shared player`

Next slice: **V2-0-02 — make visual safety a versioned choice.**

### The defect

`acquireForUi` counted the hold and then asked for the session:

```kotlin
uiHolds++
return sessionFor(context)      // -> rebindTo(context) -> uiHolds = 0
```

`rebindTo` clears both counters whenever the Application it is bound to changes, which is
right: holds taken against a dead Application are worthless. But on the **first** acquire of
the process, `app` is null, so the change fires — and the counter the caller had just taken
went `0 → 1 → 0`. The caller walked away holding nothing.

Nothing looked wrong until a second owner appeared. The two paths that reach a user:

| Order | What used to happen |
|---|---|
| screen opens, second screen opens, second closes | first screen's hold was never counted, so one release dropped the count to zero and released the player it was still driving |
| service starts playback, screen opens, screen closes | the **service's** hold was the lost one, so closing the screen released the player the notification was playing through |

The fix is the statement order: bind first, count second.

```kotlin
fun acquireForUi(context: Context): PlaybackSession = sessionFor(context).also { uiHolds++ }
```

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*PlaybackEngineTest*'`, before the fix | 11 tests, **2 failed** — the two user-visible orderings |
| `:app:testDebugUnitTest --tests '*PlaybackEngineTest*' --tests '*PlaybackResumptionTest*' --tests '*SleepTimerDelegationTest*'` | all passed |
| `:app:testDebugUnitTest` | **1,211 tests, 0 failures** (1,208 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL |
| `:app:lintDebug` | BUILD SUCCESSFUL |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

---

## V2-A-02b: enumerate every researched effect in the coverage ledger

State: COMPLETE

Goal: turn "did we already look at that?" into a question with an answer — every effect
`MASTER_PLAN.md` §8.1 names, the V2 family that owns it, and what is being done with it —
so the same source is not re-researched and the catalogue does not fill with four
near-duplicates of one idea found in four repositories.

User-visible effect: none. A data file, a generated document, and two test-source files.

In scope: `reference-coverage.json` with 162 rows across the five catalogued sources;
`REFERENCE_COVERAGE.md` regenerated from it; `ReferenceCoverage` renderer and
`ReferenceCoverageTest`; one correctness fix to `ProvenanceRegistry.NO_CODE_TIERS`.

Out of scope: characterising the opaque Fosfora codenames — that needs the upstream looks,
which this container cannot render. They are DEFER rows with the reason stated, not guesses.
Recipe IDs, tests, captures and shipped versions stay empty until the owning family's slice
fills them.

Files expected to change: `docs/visualizer-v2/{reference-coverage.json,REFERENCE_COVERAGE.md,STATUS.md}`,
`app/src/test/java/dev/musicviz/{ReferenceCoverage,ReferenceCoverageTest,ProvenanceRegistry}.kt`.

Compatibility contract: untouched. No production source file is involved.

External source/provenance entries: every row names one, and the ledger is rejected if a row
cites a source the registry does not hold or disagrees with it about the licence tier.

Tests written first: `ReferenceCoverageTest` — six assertions, the load-bearing one being
that every name §8.1 lists has a row, parsed out of the plan text itself rather than a
hand-kept copy. Run red with the ledger moved out of the tree: six tests, six failures. Two
more reds followed on the real data — a rationale too short to be reasoning on nine rows, and
the generated document out of date — and both were fixed rather than relaxed.

Benchmark or visual evidence: not applicable.

Rollback: revert the one commit.

Risks: two of the six assertions are tripwires that pass vacuously today — nothing is ported
out of a no-code source yet, and no forbidden origin is cited in the tree. They are worth
keeping because the day they stop being vacuous is exactly the day a mistake would otherwise
ship silently. The ledger's family assignments are also judgements, not facts; each row's
rationale cites the §7 recipe list or §8.2 rule it came from so a later session can disagree
with the reasoning rather than the conclusion.

Commands and results: below.

Review findings: `NO_CODE_TIERS` listed ORACLE, BENCHMARK, STUDY and EXCLUDE but not
REIMPLEMENT — so the registry would have accepted a REIMPLEMENT source declaring adopted
files, which §3 forbids ("do not copy code, shader text, constant tables, names or layout").
It is now defined as every tier except ADAPT and RETAIN, the two that may legitimately carry
upstream text. Found by asking why a coverage assertion passed vacuously.

Commit: `docs(visualizer-v2): enumerate every researched effect in the coverage ledger`

Next slice: **V2-0-01 — fix the first shared-player acquisition hold.**

### What the ledger says

| | Rows |
|---|---:|
| MERGE — folds into a family as a recipe, mode, field or post node | 123 |
| DEFER — catalogued, not this wave | 30 |
| PORT — becomes its own engine or kernel | 9 |
| **Total** | **162** |

Nine PORT rows for 162 catalogued effects is the plan's §8.2 consolidation rule turned into
numbers: four upstream projects each having a tunnel is one family with four recipes, not
four engines. Nine rows cover seven distinct kernels — Particle Life, Particle Lenia,
Physarum, Gray–Scott, Firefly Sync, the attractor field library and the strobe-safe post
node — because SwissGL and Threelab each name Physarum and reaction-diffusion, and one
kernel serves both rows.

Twenty of the thirty DEFER rows are Fosfora codenames — Protea, Cleave, Vessel and the
rest. Nothing in §8.1 or §7 says what they look like, so a family assignment would be
invention; they are recorded as open with that reason, which is the honest form of coverage.

### Verification

| Command | Result |
|---|---|
| `:app:testDebugUnitTest --tests '*ReferenceCoverageTest*'`, ledger absent | 6 tests, 6 failed — the intended red |
| `:app:testDebugUnitTest --tests '*ReferenceCoverageTest*' --tests '*EngineProvenanceRegistryTest*'` | 17 passed |
| `:app:testDebugUnitTest` | **1,208 tests, 0 failures** (1,202 before this slice) |
| `:app:ktlintCheck` | BUILD SUCCESSFUL, after one blank-line fix it caught in `ReferenceCoverage.kt` |
| `:app:lintDebug` | BUILD SUCCESSFUL |

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
