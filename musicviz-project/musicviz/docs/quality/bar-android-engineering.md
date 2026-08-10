# Android Engineering Bar: How Gold-Standard Codebases Are Built

External research summary (2026-08-10) drawn from: **google/nowinandroid (NIA)**, **signalapp/Signal-Android**, **chrisbanes/tivi** (archived Nov 2024, still a reference), **OxygenCobalt/Auxio**, and official Android architecture guidance (developer.android.com, 2024–2026). Each section gives: the standard, evidence, and a concrete checkable criterion for reviewing a **~50k-line single-module Kotlin app**.

---

## 1. Architecture

### 1.1 Unidirectional Data Flow (UDF) with layered architecture

**Standard.** Official guidance (Strongly Recommended tier on the architecture-recommendations page): a clearly defined **UI layer** and **data layer**, optional **domain layer** for big apps. "Events flow down, data flows up." UI never talks to data sources directly — repositories are the exclusive public API of the data layer, *even for a single data source*.

**Evidence.**
- NIA's `ArchitectureLearningJourney.md`: three layers; repositories (`OfflineFirstNewsRepository`, `TopicsRepository`) wrap Room DAOs, Proto DataStore (`NiaPreferencesDataSource`), and Retrofit (`RetrofitNiaNetwork`). Data is never exposed as snapshots ("no guarantee it will still be valid by the time it is used") — always as `Flow`.
- NIA domain layer: single-responsibility use cases with `operator fun invoke()` (e.g. `GetUserNewsResourcesUseCase` combines `NewsRepository` + `UserDataRepository` streams) — used only to de-duplicate stream-combination logic across ViewModels, not as a mandatory pass-through layer.
- Tivi: same shape on Kotlin Multiplatform — `data`/`domain`/`ui` modules, Store 5 + SQLDelight for offline-first caching, interactor/use-case classes in `domain`.
- Auxio (Views, not Compose): still MVVM/UDF — `@HiltViewModel`s per feature (`HomeViewModel`, `PlaybackViewModel`, `DetailViewModel`, `SearchViewModel`, `MusicViewModel`, `QueueViewModel`), with music-library access isolated behind the `musikr` library module.
- Signal-Android: pragmatic MVVM; historically Rx-based stores, now migrating to StateFlow/Compose (84 files use `collectAsStateWithLifecycle`; 365 files still import RxJava3 vs 263 importing `kotlinx.coroutines.flow` — a live migration, with new code on coroutines).

**Checkable criterion.** Every screen has a ViewModel (or explicit state holder) exposing observable state; no composable/Fragment reads a database, `MediaStore`, file, preference store, or audio/network API directly. Grep test: zero imports of Room/DAO/DataStore/`MediaStore`/`AudioRecord`-style classes from UI files. Writes flow UI → ViewModel → repository; no layer skips.

### 1.2 ViewModel + StateFlow exposure pattern

**Standard.** One `uiState: StateFlow<XUiState>` per screen (Recommended), built from cold flows with `stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), initial)`; collected in Compose with `collectAsStateWithLifecycle` (Strongly Recommended). **Do not send events from ViewModel to UI** (Strongly Recommended) — model one-shot effects as state that the UI consumes and acknowledges. UI callbacks are plain method calls / lambdas (`followTopic` → `InterestsViewModel.followTopic()` → `UserDataRepository.toggleFollowedTopicId()`).

**Evidence.** NIA: 8 files use `SharingStarted.WhileSubscribed` (every feature ViewModel plus `NiaAppState`, `MainActivityViewModel`, `TimeZoneMonitor`). UI state is a sealed hierarchy (`NewsFeedUiState.Loading/Success`). Official docs use NIA's `BookmarksViewModel` verbatim as the canonical example.

**Checkable criterion.** ViewModels expose `StateFlow`/immutable state only (no public `MutableStateFlow`/`MutableLiveData`/`SharedFlow`-as-event-bus); reactive state uses `stateIn` + `WhileSubscribed(5_000)` (not `Eagerly`/`Lazily` without justification); Compose collects via `collectAsStateWithLifecycle`. Flag any `SingleLiveEvent`, channel-based event stream to UI, or `LaunchedEffect` collecting ViewModel "event" flows.

### 1.3 Dependency injection: Hilt vs manual

**Standard.** DI itself is Strongly Recommended (constructor injection); **Hilt is Recommended for complex projects**, manual DI acceptable for simple apps. The non-negotiable part is constructor-injected dependencies (including dispatchers and scopes), not the framework.

**Evidence.** NIA: Hilt throughout (`HiltConventionPlugin` in build-logic). Auxio: Hilt in a two-module app (11 `@HiltViewModel`s) — proof Hilt is proportionate even for small apps. Tivi: chose **kotlin-inject** (0.7.2) + **Circuit** (0.25.0) for KMP where Hilt doesn't reach. Signal: largely manual DI (`ApplicationDependencies`-style service locator, legacy) — widely regarded as its weakest architectural feature and being ground down over time.

**Checkable criterion.** No singletons reached via `object` globals, `companion` service locators, or `Context.get...()` chains inside business logic; every class's collaborators visible in its constructor. If Hilt is present: `@HiltViewModel` + modules bind interfaces to implementations; if manual: a single composition root, and classes are still constructor-injected (testable without the container).

### 1.4 Module boundaries in single-module apps

**Standard.** NIA's `ModularizationLearningJourney.md`: modularization is a scale tool, not a virtue — "no universal solution"; over-modularizing a small app is an anti-pattern. What must survive in a single-module app is the *logical* layering: "if a class is needed only by one feature, it should remain within that feature" — i.e. package-by-feature with an enforced (by review, if not by Gradle) rule that feature packages don't reach into each other, and core/shared packages never depend on features. NIA's dependency rules: features depend only on core; core never depends on features; app sits on top.

**Evidence.** Auxio is the single-module reference: everything under `org.oxycblt.auxio.{home,playback,detail,search,music,list,...}`, with the one genuinely reusable subsystem (music indexing/metadata: `musikr`) extracted to a real Gradle module. Signal shows the mature-monolith endgame: a giant `app` module gradually extracting `core/*`, `lib/*` (`lib/network`, `lib/qr`, `lib/paging`), and `feature/*` (`feature/media-send`) modules plus `build-logic/plugins`.

**Checkable criterion.** For a ~50k-line single-module app: top-level packages are features + a small `core`/`common` set; no `ui/`, `viewmodels/`, `utils/` layer-based dumping grounds; cross-feature imports go through shared abstractions, not sibling internals. Flag: any "God" package or class that most features import concretely, and any `util` package over ~1–2k lines.

---

## 2. Kotlin Quality

### 2.1 Structured concurrency

**Standard (official coroutines best-practices page).** ViewModels create coroutines in `viewModelScope` and expose state, not suspend functions; **never `GlobalScope`** — inject an application-scoped `CoroutineScope` for work that must outlive callers; coroutines must be cancellation-cooperative (`ensureActive()` in loops over blocking work); never swallow `CancellationException`; catch *specific* exceptions near the launch site and turn them into UI state.

**Evidence.** NIA injects an `@ApplicationScope CoroutineScope` for sync-type work; Tivi's interactors run in injected scopes with Turbine-tested flows; Signal's newer Kotlin code follows the same pattern while legacy Java uses executors.

**Checkable criterion.** Grep: zero `GlobalScope`, zero `runBlocking` outside tests/main functions, zero `CoroutineScope(Dispatchers.X)` created ad-hoc inside classes that already have a lifecycle scope. Every `launch` has an owner whose cancellation is meaningful. Any bare `catch (e: Exception)` inside a coroutine must rethrow `CancellationException`.

### 2.2 Dispatchers discipline

**Standard.** Inject dispatchers (`@IoDispatcher`, `@DefaultDispatcher` qualifiers in NIA), never hardcode `Dispatchers.IO/Default/Main` at call sites; suspend functions are **main-safe** (the layer that does blocking work wraps itself in `withContext(injectedDispatcher)`), so callers never need `withContext`. Room/Retrofit/Ktor are already main-safe — wrapping them again is a smell.

**Evidence.** NIA `core/common` defines dispatcher qualifiers consumed everywhere; official docs list "Inject Dispatchers" as rule #1; test guidance depends on it (swap in `TestDispatcher`).

**Checkable criterion.** Count hardcoded `Dispatchers.` references outside a DI module/composition root — target ~0 in production code. Every suspend function callable from the main thread without jank; CPU-heavy audio/FFT-style work runs on injected `Default`, file/DB on injected `IO`.

### 2.3 Flow operators vs manual loops; exposure rules

**Standard.** Data layer exposes `Flow` for observable data and suspend functions for one-shots; transformation happens with operators (`map`, `combine`, `flatMapLatest`, `distinctUntilChanged`), not `while` loops polling state or manual callback plumbing. Never expose mutable types (`MutableStateFlow` up-cast to `StateFlow` behind a private backing field).

**Evidence.** NIA ViewModels are essentially declarative `combine(...)`/`map(...).stateIn(...)` pipelines; `GetUserNewsResourcesUseCase` exists purely to share a `combine` of two repository streams. Tivi is built on Store 5 (flow-based caching) + SQLDelight flow queries. Signal's paging lib bridges into flows.

**Checkable criterion.** Flag: polling loops (`while(true) { delay(...) }`) where a `Flow`/callbackFlow fits; `.collect {}` inside ViewModel that copies values into a second `MutableStateFlow` when a `stateIn` pipeline would do; any public mutable state; `liveData`/`Flow` conversions bouncing back and forth.

### 2.4 Immutability and sealed hierarchies for state

**Standard.** UI state is an immutable `data class` or `sealed interface` per screen (`${Screen}UiState`), with variants for Loading/Success/Error — making unrenderable states unrepresentable. Model-per-layer (network model → domain model → UI state) is Recommended for complex apps. NIA additionally wraps risky streams in a small `Result<T>` sealed type (`core/common/result/Result.kt`, `asResult()` operator) rather than letting exceptions escape flows.

**Evidence.** NIA `NewsFeedUiState`, `InterestsUiState`, `OnboardingUiState` sealed hierarchies; `Result.kt` + `ResultKtTest`. Auxio models its domain (tags, release types, disc numbers) as dedicated immutable types with unit-tested parsing (`musikr/src/test/.../tag/*Test.kt`).

**Checkable criterion.** Each screen's state is one sealed/data type with `val`s and immutable collections in its API; no `var` fields mutated from multiple places; error/empty/loading are explicit variants, not nullable fields plus booleans. Flag exposed `MutableList`/`ArrayList` in state, and `catch { emit(emptyList()) }`-style silent-swallow error handling.

---

## 3. Compose Quality

### 3.1 State hoisting & state holders

**Standard.** ViewModels only at screen level (Strongly Recommended); reusable components take `(state, onEvent)` parameters or a plain `remember`ed state-holder class — never grab a ViewModel from inside a reusable composable. Lifecycle handled with `LifecycleStartEffect`/`repeatOnLifecycle`/`collectAsStateWithLifecycle`, not Activity callback overrides.

**Evidence.** NIA: `NiaAppState` (a remembered plain state holder, itself using `WhileSubscribed` flows) coordinates app-level UI; feature screens are `Screen(uiState, onAction...)` with a thin `Route` composable binding the ViewModel. Signal's Compose screens follow the same stateless-screen + preview pattern.

**Checkable criterion.** Every screen splits into a stateful wrapper (gets ViewModel, collects state) and a stateless, previewable content composable; `hiltViewModel()`/`viewModel()` appears only in route/screen-level entry points; interactive sub-components receive lambdas, not ViewModels.

### 3.2 Stability, skippability, recomposition discipline

**Standard.** Composables should be skippable for stable inputs; with Kotlin 2.0.20+ / Compose compiler 2.x, **strong skipping** is on by default, but unstable collections and lambda-capturing patterns still cause churn. Official rules: avoid backwards writes (writing state you've already read in the same composition); defer state reads to layout/draw phase via lambda modifiers (`Modifier.drawBehind`, `graphicsLayer{}` lambdas) for per-frame values — critical for anything animating at 60fps (e.g. visualizers). NIA publishes Compose-compiler metrics tasks to audit stability.

**Evidence.** NIA README documents compiler-metrics gradle commands; NIA uses immutable model classes throughout so parameters are stable; official performance page codifies remember/defer-reads/no-backwards-writes.

**Checkable criterion.** For per-frame data (playback position, audio amplitudes): the value must be read inside `Canvas`/`drawBehind`/`graphicsLayer` lambdas or a `State` read scoped to draw — not passed as a composable parameter recomposing the whole tree per frame. Flag unstable `List<T>` parameters on hot composables (use `kotlinx.collections.immutable` or wrappers), backwards writes, and composables doing allocation/sorting per recomposition without `remember`.

### 3.3 Side-effect APIs

**Standard.** `LaunchedEffect(key)` for suspend work tied to composition (with honest keys), `DisposableEffect` for teardown-paired subscriptions, `rememberUpdatedState` for capturing latest lambdas in long-lived effects, `rememberCoroutineScope` for event-handler-launched work, `snapshotFlow` to bridge Compose state → flows. No business logic in effects — they trigger ViewModel methods.

**Evidence.** NIA uses `LaunchedEffect` sparingly (analytics/first-run) and `DisposableEffect` for lifecycle observers; official docs' side-effects page is the reference.

**Checkable criterion.** Flag: `LaunchedEffect(Unit)` doing per-state work its keys don't express; effects mutating ViewModel state that should be a flow pipeline; missing `DisposableEffect.onDispose` for listeners (sensor/audio callbacks); loops in composition; `GlobalScope`/`CoroutineScope()` inside composables.

### 3.4 Performance APIs

**Standard.** `remember(keys)` for expensive computation (sorting, gradient/path building); `derivedStateOf` only when mapping frequently-changing state to rarely-changing state (e.g. `firstVisibleItemIndex > 0`); **stable `key = { it.id }` on every lazy list `items`**; `contentType` for heterogeneous lists; baseline profiles for startup (NIA ships `app/src/main/baseline-prof.txt` + benchmark module).

**Evidence.** Official performance best-practices page (all five rules); NIA baseline-profile + macrobenchmark setup; Tivi ships baseline-profile benchmarking (1.3.3) too.

**Checkable criterion.** Every `LazyColumn/LazyRow/LazyGrid` `items` call has a stable unique `key`; no `derivedStateOf` wrapping already-cheap state (misuse) and no scroll-position-driven booleans *without* it; expensive per-composition computation is `remember`ed with correct keys (stale-key bugs count as errors).

### 3.5 Theming

**Standard.** One `MaterialTheme` wrapper (`NiaTheme`) defining color scheme (with dark + dynamic-color support), typography, shapes; components consume `MaterialTheme.colorScheme.*` tokens — no hardcoded `Color(0xFF...)` in feature code; a design-system layer of app-branded components (`NiaButton`, `NiaTab`...) wraps Material components, enforced in NIA by a **custom lint rule** (`DesignSystemDetector`) that forbids raw Material components outside the design-system module.

**Checkable criterion.** Hardcoded color/dp/sp literals concentrated in a theme/tokens file; feature composables reference theme tokens. Grep `Color(0x` outside theme files — should be ~0 (a visualizer's palette engine is a legitimate exception *if* centralized).

---

## 4. Testing

### 4.1 What gold-standard repos actually test (pyramid shape)

**Standard.** Official "what to test" (Strongly Recommended): ViewModels (including their flows), data layer (repositories + data sources), navigation. The observed pyramid is: **broad JVM unit tests** (ViewModel/repository/use-case/parsing logic) → **screenshot tests on JVM (Robolectric+Roborazzi)** → **thin instrumentation/E2E layer** (navigation + critical flows, benchmark).

**Evidence.**
- NIA: unit tests per ViewModel/repository/use case (`testDemoDebug`); Roborazzi screenshot suites (20 references; `core/screenshot-testing` module, `NiaAppScreenSizesScreenshotTests`, `SnackbarScreenshotTests`); instrumentation via `connectedDemoDebugAndroidTest` using **real DataStore with temp folders** rather than mocks; Jacoco convention plugins for coverage.
- Tivi: Turbine 1.2.0 for flow assertions, AssertK matchers, JUnit4, uiautomator + baseline-profile benchmarks.
- Auxio: focused unit tests where logic is hairiest — tag/date/disc/release-type parsing in `musikr` (9 `@Test` files); little UI test coverage (honest pyramid for a solo project: test the domain, not the glue).
- Signal: large unit-test suite with Robolectric 4.16, MockK 1.13.x, AssertK, MockWebServer; instrumentation tests for database/crypto layers.

**Checkable criterion.** For a 50k-line app: ViewModels, repositories, and every non-trivial pure algorithm (parsing, DSP/FFT, mapping) have JVM tests; test-to-code ratio isn't the bar — *coverage of decision-heavy code* is. Red flag: tests only for trivial utilities while state machines/pipelines are untested, or zero tests.

### 4.2 Fakes vs mocks

**Standard.** **Prefer fakes to mocks** — Strongly Recommended officially; NIA is doctrinal about it: no mocking library at all (0 Mockito/MockK references), instead `Fake*` implementations of production interfaces ("test doubles that share production interfaces... reduces brittleness and exercises more production code"). Naming convention: `Fake` prefix for test doubles, `Default` prefix for sole production impls. Signal (MockK) shows big legacy codebases do use mocks, but the modern bar is fakes for repository/data-source seams.

**Checkable criterion.** Repositories/data sources are interfaces with in-memory fake implementations reused across tests; mock usage limited to awkward framework edges. Flag: `verify(mock).method()` interaction-testing of state that could be asserted on a fake, and mocking types you own.

### 4.3 Coroutine/Flow test norms

**Standard.** `runTest` + injected `TestDispatcher` (`MainDispatcherRule` swapping `Dispatchers.Main`); assert on `StateFlow.value` where possible; for `WhileSubscribed` flows you must have an active collector in the test (NIA pattern: `backgroundScope.launch(UnconfinedTestDispatcher()) { uiState.collect() }`); Turbine for sequence assertions (Tivi).

**Checkable criterion.** Tests use `runTest` (not `runBlocking`/`Thread.sleep`); a main-dispatcher rule exists if ViewModels are tested; time controlled via the scheduler (`advanceTimeBy`), not real delays. Flag flaky sleeps and `Dispatchers.setMain` scattered without a rule.

### 4.4 Screenshot tests

**Standard.** JVM screenshot tests (Roborazzi in NIA/Signal-era Android; Paparazzi elsewhere) with record/verify/compare gradle tasks, run on Linux CI for determinism, covering themes, font scales, and screen sizes. This is the cheapest UI-regression layer and where NIA invests instead of broad Espresso suites.

**Checkable criterion.** For an app whose output *is* visual (e.g. a visualizer): some pixel- or golden-based verification of rendering exists, or at minimum previews + a stated manual-QA story; absence is a noted gap rather than an automatic fail.

---

## 5. Project Hygiene

### 5.1 File/class size norms

**Standard.** No published hard limits in these repos, but observed norms: NIA ViewModels ~100–250 lines; feature screen files usually 300–700 lines; classes have one reason to change (NIA splits e.g. `TopicsRepository` / `NewsRepository` / `UserDataRepository` rather than one `DataManager`). Auxio splits by concern per feature package (list vs detail vs decision sub-packages). The failure mode to catch is God-classes: Signal's `ApplicationDependencies`-style registries and multi-thousand-line legacy Java classes are the cautionary tale, not the model.

**Checkable criterion.** Files >800 lines or classes with 10+ unrelated public methods need justification; a screen file containing ViewModel + state + 15 composables + preview data should be split. Count files >500 lines and check each is cohesive (a single complex screen is fine; a `Utils.kt` is not).

### 5.2 Package-by-feature

**Standard & evidence.** All four repos organize by feature/domain, not layer: NIA `feature/*` (+`api`/`impl` split) and `core/*`; Auxio `org.oxycblt.auxio.{home,playback,detail,search,...}`; Signal extracting `feature/*` and `core/*`; Tivi `ui/`, `domain/`, `data/` modules each internally by feature.

**Checkable criterion.** Top-level packages read as the app's feature list; no `activities/`, `fragments/`, `adapters/`, `viewmodels/` layer packages. A new feature lands as one new package, not edits across five layer folders.

### 5.3 Formatting & lint baselines

**Standard.** Formatting is automated and CI-enforced, not stylistic opinion: **Spotless + ktlint** in NIA and Tivi (`./gradlew spotlessApply`), ktlint via a convention plugin + `.editorconfig` in Signal (plus a custom `fast-lint` module). Android Lint runs in CI; NIA writes **custom lint rules** (`NiaIssueRegistry`: `DesignSystemDetector`, `TestMethodNameDetector`) to enforce project conventions mechanically. Notably, none of these four leans on detekt — ktlint+Android Lint(+custom rules) is the dominant stack; detekt is a fine addition but not the observed baseline.

**Checkable criterion.** A formatter (ktlint/ktfmt via Spotless or plugin) is configured and the codebase is clean under it; Android Lint passes or has a checked-in, dated baseline file; warnings aren't globally suppressed (`@Suppress` count low and localized). Flag: no formatter config at all, or `lintOptions { abortOnError false }` as a way of ignoring everything.

### 5.4 Gradle conventions

**Standard.** Version catalog (`gradle/libs.versions.toml`) everywhere; multi-module repos add `build-logic` **convention plugins** (NIA's 17: `AndroidApplicationConventionPlugin`, `AndroidFeature[Api|Impl]ConventionPlugin`, `HiltConventionPlugin`, `AndroidRoomConventionPlugin`, Jacoco/Compose/Lint/Flavors variants; Signal has `build-logic/plugins` incl. `ktlint.gradle.kts`). For a **single-module** app the equivalent bar is much smaller: a version catalog, Kotlin DSL, no duplicated ad-hoc script blocks, deterministic dependency versions (no `+`), and CI that runs `test` + `lint`.

**Checkable criterion.** `libs.versions.toml` exists and build files reference it; no dynamic versions; compile/target SDK current-ish (NIA/Signal track latest stable); one place defines Java/Kotlin toolchain. Convention plugins are *not* required at single-module scale — flag their absence only if build scripts show heavy duplication.

### 5.5 Docs & repo craft

**Standard.** NIA ships `docs/ArchitectureLearningJourney.md` + `ModularizationLearningJourney.md`; Auxio documents feature rationale in its wiki; Signal maintains CONTRIBUTING.md + wiki. Baseline: a README that states architecture, how to build, and how to test.

**Checkable criterion.** README (or docs/) explains the architecture in ≥ a few paragraphs that match the actual code; build instructions work; test invocation documented.

---

## Quick-Reference Review Checklist (single-module, ~50k lines)

| # | Criterion | Pass looks like |
|---|-----------|-----------------|
| 1 | UDF layering | UI → VM → repository; zero data-source imports in UI files |
| 2 | State exposure | One `StateFlow<UiState>` per screen; `WhileSubscribed(5_000)`; `collectAsStateWithLifecycle` |
| 3 | No VM→UI events | No SingleLiveEvent/event channels; effects modeled as state |
| 4 | DI | Constructor injection everywhere; Hilt or a single composition root; no global `object` service locators |
| 5 | Package-by-feature | Top-level packages = features + small core; no layer packages, no giant `util` |
| 6 | Structured concurrency | No `GlobalScope`, no ad-hoc scopes, no `runBlocking` in prod, `CancellationException` never swallowed |
| 7 | Dispatchers | Injected via qualifiers; ~0 hardcoded `Dispatchers.` outside DI; suspend fns main-safe |
| 8 | Flows over loops | Operator pipelines, no polling loops, no exposed mutable state |
| 9 | Sealed immutable state | `sealed interface XUiState` per screen; Loading/Error explicit; immutable collections |
| 10 | Compose hoisting | Stateless previewable screen composables; ViewModel only at route level |
| 11 | Recomposition discipline | Per-frame values read in draw phase; stable lazy `key`s; `remember` for expensive work; no backwards writes |
| 12 | Side effects | Correct effect API per job; `onDispose` cleanup for listeners |
| 13 | Theming | Central theme tokens; ~0 `Color(0x` in feature code |
| 14 | Test pyramid | JVM tests for VMs/repos/algorithms; fakes over mocks; `runTest` + main-dispatcher rule |
| 15 | Hygiene | Formatter configured & clean; Lint in CI; version catalog; files >800 lines justified; docs match code |

### Sources
- https://github.com/android/nowinandroid (docs/ArchitectureLearningJourney.md, docs/ModularizationLearningJourney.md, README.md, build-logic/, lint/, core/common/result/Result.kt)
- https://developer.android.com/topic/architecture/recommendations
- https://developer.android.com/kotlin/coroutines/coroutines-best-practices
- https://developer.android.com/develop/ui/compose/performance/bestpractices
- https://github.com/chrisbanes/tivi (archived 2024-11-12; gradle/libs.versions.toml: kotlin-inject, Circuit, Store 5, SQLDelight, Turbine, Spotless/ktlint)
- https://github.com/signalapp/Signal-Android (build-logic/plugins/ktlint.gradle.kts, gradle/test-libs.versions.toml: Robolectric 4.16/MockK/AssertK; core/*, lib/*, feature/* extraction; 84× collectAsStateWithLifecycle vs 365× RxJava3 imports)
- https://github.com/OxygenCobalt/Auxio (settings.gradle: `:app` + `:musikr`; 11 @HiltViewModel; package-by-feature org.oxycblt.auxio.*; musikr/src/test tag-parsing tests)
