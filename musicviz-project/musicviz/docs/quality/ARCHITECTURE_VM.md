# ARCHITECTURE_VM — Final Blueprint: Container + Holders + Controller

**Status: FINAL. This document is the executable architecture for decomposing
`ui/PlayerViewModel.kt` (~4,000 lines) and the `ui/EnginePlumbing.kt` renderer
boundary. It is the judged winner of a three-way concurrent design competition
(pragmatic / clean-strict / realtime-first) with the judge-mandated grafts
integrated. Coding agents executing gauntlet rounds 3+ follow it as written;
there are no open design questions. Where this document is decisive
(bounds, timeouts, step order, gate lists), do not re-litigate.**

Inputs of record: dossier-nia.md, dossier-auxio.md, dossier-projectm.md,
dossier-state-frameworks.md, dossier-realtime-boundary.md, dossier-vm-coupling.md,
QUALITY_BAR.md, bar-android-engineering.md. Line anchors refer to the ~4,000-line
PlayerViewModel snapshot; re-anchor by symbol, never by line.

---

## 0. Ground rules

### 0.1 The three rules the design hangs on

1. **Two planes.** The UI-rate plane is `StateFlow<UiState>` in plain state
   holders. The 60fps plane is one volatile immutable snapshot (`RenderConfig`)
   plus one pre-allocated PCM tap (`AudioTap`), both owned by `AppContainer`,
   read by the GL thread, and **never routed through a ViewModel or the
   composition**.
2. **One writer per state.** Every holder writes its state only via
   `_state.update { it.copy(...) }`. The renderer's config is written only via
   `VisualizerController.update {}`. The PCM ring is written only by audio
   producers. `CancellationException` is rethrown in the one shared launch
   helper (`UiStateHolder.launchIntent`).
3. **Paths are API.** ~40 unit tests assert on main-source text and hard-code
   paths (`ParamSurface.source("ui/PlayerViewModel.kt")` and friends). New code
   = new files; migrated code shrinks **in place**. `ui/PlayerViewModel.kt`
   survives as a ≤300-line facade at its current path. Gate-content updates
   ship in the same commit as each extraction (per-step lists in §5).

### 0.2 The four laws of the frame loop

These go **verbatim** as KDoc on `VisualizerController` and are the checked-in
review checklist for any PR touching `render/` or `audio/`:

> 1. **One volatile read per concern per frame.** The GL thread reads: one
>    `RenderConfig` reference, one `AudioFrame` reference, and copies PCM into
>    a preallocated scratch. Nothing else. No field-by-field volatile chasing.
> 2. **Commands enter the GL thread only as values through the GLSurfaceView
>    event queue** (serialized with drawing by AOSP contract). No off-thread
>    assignment of renderer internals, ever.
> 3. **State exits the GL thread only as immutable values from one funnel
>    point** in `onDrawFrame` (`flushEvents()`). No ad-hoc callback fields.
> 4. **No thread ever calls "upward."** GL never calls a ViewModel/holder;
>    audio threads never call holders; holders never block on the GL thread —
>    structural edges (export start/stop, EGL recreation) use the
>    timeout-guarded handshake (§2.5), never an unbounded wait.

These laws are enforced mechanically by `RenderThreadPurityTest` (§2.7), which
lands **before** the renderer rewrite (§5 Step 3).

---

## 1. Target architecture

### 1.1 Diagram (packages are the module boundaries; single `:app` module)

```
                                PROCESS LIFETIME
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ di/AppContainer  (sole composition root; explicit start() from App)      │
 │   ├── stores (18, unmoved files)          ├── PlaybackEngine (+ ring)    │
 │   ├── AnalysisEngine, captures, bridge    ├── AppDispatchers, appScope   │
 │   ├── AudioTap ◄── FeatureEnricher ◄── EnricherInputs (@Volatile setter) │
 │   ├── VisualizerController (app surface)  ├── ListenTimeTracker          │
 │   └── RenderConfigFeed (appScope binder: prefs flows → controller)       │
 └──────────────────────────────────────────────────────────────────────────┘
        ▲ reads container                          ▲ reads container
 ┌──────┴────────────────────────┐   ┌─────────────┴──────────────────────┐
 │ SCREEN LIFETIME               │   │ SERVICES (no ViewModel, ever)      │
 │ ui/PlayerViewModel (facade)   │   │ PlaybackService                    │
 │  ├── 12 holders in ui/state/  │   │ PlaybackCaptureService             │
 │  │   (viewModelScope, ctor-   │   │ VisualizerWallpaperService         │
 │  │    injected container deps)│   │   └── own VisualizerController     │
 │  └── cross-domain glue (E-*)  │   │       seeded fromGuiPrefs + feed-  │
 └───────────────────────────────┘   │       lite; shared AudioTap        │
        │ update{} / submit()        └────────────────────────────────────┘
        ▼                                     │ update{}
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ render/contract/VisualizerController   (framework-free)                  │
 │   AtomicReference<RenderConfig> · bounded pending deque · events outbox  │
 └──────────────────────────────────────────────────────────────────────────┘
        │ config (1 volatile read/frame)   ▲ RenderEvent (funnel)
        ▼                                  │
 ┌──────────────────────────────────────────────────────────────────────────┐
 │ GL THREAD  render/VisualizerRenderer                                     │
 │   applyConfig diff · queueEvent commands · audioTap.readPcmInto(scratch) │
 │   · audioTap.frame (1 volatile read) · flushEvents()                     │
 └──────────────────────────────────────────────────────────────────────────┘
```

Data-flow direction rule: UI/holders → controller → GL; audio producers →
tap → GL; GL → events → holders. No cycles, no upward calls.

### 1.2 `dev.musicviz.di` — composition root (process lifetime)

| Class | Responsibility | ~size |
|---|---|---|
| `AppContainer` | The only place collaborators are constructed. `by lazy` vals for the 18 inline-constructed stores + engine borrowings + boundary objects (`visualizerController`, `audioTap`, `featureEnricher`, `enricherInputs`, `renderConfigFeed`, `listenTimeTracker`) + `AppDispatchers` + `applicationScope`. Explicit `fun start()` called from `MusicVizApp.onCreate` — **no lazy `.also { it.start() }` side effects anywhere.** | 150 |
| `AppDispatchers` | `data class AppDispatchers(val main, val io, val default: CoroutineDispatcher)` with a `Default` instance; injected everywhere a dispatcher is named (~0 hardcoded `Dispatchers.` outside this file). | 25 |
| `ViewModelFactory` | `viewModelFactory { initializer { PlayerViewModel(app.container, createSavedStateHandle()) } }`. | 30 |
| `RenderConfigFeed` | Process-scope binder (STRATA graft): collects the store-owned flows that must reach the renderer **with zero UI composed** — visual safety from `ThemeStore.guiPrefs`, LFO configs and ADSR configs from `LfoStore` — into `visualizerController.update {}`. Started by `container.start()`. Kills the "config stops applying when no screen is composed" bug class. A `feedLite(controller)` variant binds safety+preset flows for each wallpaper controller. | 80 |

`MusicVizApp` gains `val container: AppContainer by lazy { AppContainer(this) }`
and `onCreate()` calls `container.start()`. Reached as
`(application as MusicVizApp).container` from MainActivity, PlaybackService,
PlaybackCaptureService, and VisualizerWallpaperService — this deletes the
"PlaybackService constructs its own HistoryStore / wallpaper constructs its own
ThemeStore" duplications.

### 1.3 `dev.musicviz.render.contract` — the 60fps boundary (process lifetime)

No Compose, no ViewModel, no store, no `androidx.lifecycle` imports —
mechanically enforced (§2.7).

| Class | Responsibility | ~size |
|---|---|---|
| `RenderConfig` | Immutable aggregate of everything the renderer reads per frame that is *configuration*: `sceneId`, `layer: LayerSpec?`, `transition: TransitionSpec`, `sceneParams`, `safety`, `lfoConfigs`, `adsrConfigs`, `milkPath: String?`, `generation: Long`. Companion `DEFAULT` carries the defaults `LayersCustomizeTest` pins today, plus `fun fromGuiPrefs(prefs: GuiPrefs): RenderConfig` for wallpaper seeding. | 100 |
| `LayerSpec`, `TransitionSpec` | Value groups whose fields must land atomically: `LayerSpec(sceneId, mix, blend)`, `TransitionSpec(id, style, durationMs)`. | 30 |
| `RenderCommand` | Sealed vocabulary for GL-thread work that allocates or must be serialized with drawing: `LoadMilkPreset(path)`, `SubmitShader(sceneId, source)`, `WarmTransition(id)`, `BeginParamMorph(fade)`. Values → replayable on re-attach, loggable, testable without GL. | 40 |
| `RenderEvent` | Sealed outbox: `ShaderError(sceneId, message)`, `SceneListAvailable(ids)`, `MilkPresetLoaded(path)`, `Stats(fps, p95Ms)`. Published only from the frame loop's single funnel point. | 30 |
| `RenderSink` | `interface { fun enqueue(command: RenderCommand); fun onConfigChanged() }` — implemented by `VisualizerView` (`enqueue` = `queueEvent`, `onConfigChanged` = `requestRender`). | 15 |
| `VisualizerController` | The boundary object. Full mechanism in §2 — atomic config, `@Synchronized` submit/attach/detach with **bounded** pending replay, DROP_OLDEST events outbox, timeout-guarded structural handshake. Carries the four laws (§0.2) as class KDoc. | 150 |

### 1.4 `dev.musicviz.audio` — additions (process lifetime)

| Class | Responsibility | ~size |
|---|---|---|
| `AudioTap` | The only audio→renderer surface. Wraps the existing `PcmRingBuffer`; `readPcmInto(dest: FloatArray): Int` (GL thread, zero alloc, zero-pads on underrun — Oboe `readNow` semantics); `@Volatile var frame: AudioFrame` written by `FeatureEnricher` only; `isLive(nowMs)`/`clear()` retained so `WallpaperIdleTest` semantics re-point 1:1 from `AudioBus`. | 80 |
| `AudioFrame` | Immutable per-frame feature snapshot: existing `AudioFeatures` fields + `progress`, `sectionIndex` (the enrichment that today happens in `viewModel.enrichFeatures`). One value object = the audio→visual contract. | 40 |
| `FeatureEnricher` | Plain class composed in the container: collects `AnalysisEngine` features on `dispatchers.default`, merges `Progression` extrapolation + sections from `EnricherInputs`, writes `audioTap.frame`. Lifecycle = `applicationScope`; started by `container.start()`. | 70 |
| `EnricherInputs` | **The volatile-handoff seam (realtime graft).** `class EnricherInputs { @Volatile var sections: SectionsSnapshot? = null }`. VM-scoped code (AnalysisState) *pushes values* into it via the setter; the container-lifetime enricher reads it. **Never a lambda capturing ViewModel-scoped state** — that is the scope leak this class exists to make impossible. | 15 |
| `Progression` | Auxio §5 verbatim shape: `(isPlaying, isAdvancing, initPositionMs, creationTime)` + `elapsedMs()` extrapolation. **`equals()`/`hashCode()` deliberately ignore `creationTime`** so functionally identical snapshots never retrigger collectors. Emitted by `PlaybackEngine` only on discontinuities (play/pause/seek/track change) as `val progression: StateFlow<Progression>`. | 45 |

### 1.5 `dev.musicviz.ui.state` — domain state holders (ViewModel lifetime)

All: `class XxxState(scope: CoroutineScope, deps..., dispatchers: AppDispatchers)`
— constructor-injected, `stateIn(scope, WhileSubscribed(5_000), initial)` idiom,
named intent functions (not sealed events — preserves call-site text for the 20
consuming UI files). Shared base `UiStateHolder<S>` (§7.1).

| Class | Absorbs (vm-coupling §1 sections) | Deps from container | ~size |
|---|---|---|---|
| `UiStateHolder<S>` | Base: private `MutableStateFlow`, `reduce {}`, `launchIntent {}` with CancellationException rethrow, `close()`. | — | 40 |
| `PlaybackState` | §§0d(uiState) 2 5 6 12 + queue/fades from 0e/11: transport verbs, queue ops, shuffle/repeat, sleep-timer UI, player prefs. Transport verbs collapse into `PlaybackCommand` values (§3 note). **A-B loop and sleep-timer fade are engine-side (§1.8); this holder only sets/clears them and mirrors state.** | `playback` (engine), `playerPrefsStore` | 320 |
| `AudioInputState` | §§0b 1 + E-23: mic, external capture, now-playing, consent, MediaProjection collectors. | `micCapture`, `playbackCapture`, `nowPlayingBridge` | 250 |
| `AudioFxState` | §3: EQ bands, presets, bass boost, loudness, session re-attach on audio-session change (E-6). | `audioFxController` | 120 |
| `VisualsState` | §§4(part) 15: scene select, params, reactivity, locks, randomize, transitions, custom shaders, textures, LFO/ADSR, milk import/list, `nudgeTransform`, shader-error intake from `controller.events`. **The only holder that writes the app `VisualizerController`.** Exposes `currentSnapshot()` pure function for export/takes. | `visualizerController`, `textureStore`, `lfoStore`, `autoVisualsPrefsStore`(read) | 400 |
| `PresetsState` | §15(part) E-19: preset CRUD, share links, import/launder, folders, SAF mirror. Applies presets through `VisualsState` verbs (one direction, no cycle). | `presetStore`, `visualsState` | 250 |
| `AutoVisualsState` | §§7 8 + `cycleAutoMode`: viz playlist, random mode, section staging, preset lock; per-holder ticker replaces its slice of the 500ms poll; beat impulse read from `audioTap.frame` at tick (not per frame). | `autoVisualsPrefsStore`, `visualsState`, `presetsState`(read), `audioTap` | 300 |
| `MusicLibraryState` | §9 + trackOverrides/mediaRoots: MediaStore scan, imports, SAF roots, playlist CRUD, track info. | `trackLibrary`, `musicPlaylistStore` | 350 |
| `SessionState` | history/favourites from §§0e 11 (E-2, E-20): `recentlyPlayed`, `shuffleAllHistory`, `toggleFavourite`. **Listen-time accrual is NOT here** — it lives in container-side `ListenTimeTracker` (§1.8) so it survives the ViewModel while the service plays. | `historyStore`, `favouritesStore` | 160 |
| `AnalysisState` | §13 + waveform/lyrics from 0e/11 (E-12/13/14): intelligence mode, analyze track, key colour, artwork palette, timeline/waveform, lyrics via `flatMapLatest` on current track id. Pushes sections into `container.enricherInputs` (value handoff, §1.4). | `offlineAnalyzer`, `analysisEngine`, `visualsState`(param writes) | 300 |
| `ExportState` | §§14 16 17: takes record/replay, export, studio. Long verbs as internal Tivi-lite interactor methods with `inProgress` in state. Export start/stop uses the controller's structural handshake (§2.5). | `exportEngine`, `studioExportEngine`, `takeStore`, `visualsState`(snapshot read) | 350 |
| `ThemeState` | §0d(theme, guiPrefs): theme, GUI prefs, beat-sigma pass-through to engine (E-10). Safety→renderer flows via `RenderConfigFeed`, not this holder. | `themeStore` (as `ThemeStoreApi`), `analysisEngine` | 120 |

**Cross-holder direction rule:** `PlaybackState` is upstream of everything
(current track id, progression). `VisualsState` is the sole app-controller
writer; `PresetsState`/`AutoVisualsState`/`AnalysisState` call `VisualsState`
verbs. No holder ever calls "up" into the facade; no holder pair is mutually
referential. The facade wires the DAG.

### 1.6 `dev.musicviz.ui.PlayerViewModel` — the facade (shrinks in place)

Final shape ~250–300 lines: constructor
`PlayerViewModel(container: AppContainer, savedStateHandle: SavedStateHandle)`;
property initializers construct the 12 holders passing `viewModelScope` +
container deps; exposes them (`val playback`, `val visuals`, …); retains only:

- **Cross-domain glue** as explicit collectors (the E-edges): `onTrackChanged`
  fan-out (E-1: playback current-track flow → analysis reload + session record
  + AB-loop clear + lyrics), capture-start → playback pause (E-7/E-8),
  `applyLiveInputProfile` (E-9), export pre-flight (E-15). Each glue block ≤10
  lines, delegating to holder verbs.
- **Teardown contract** (`onCleared`, E-21): calls `holder.close()` in reverse
  construction order. Engine release is container/registration-owned (§5 Step 12).
- **Temporary delegating one-liners** during migration, deleted screen-by-screen.
- **Navigation additions** (§10, 2 members) — stays facade-level, not worth a holder.

`InitOrderTest` stays satisfiable: holder construction in property
initializers; the single `init` with glue collectors stays physically last.

### 1.7 `ui/EnginePlumbing.kt` — shrinks in place to ~35 lines

One `DisposableEffect` doing `container.visualizerController.attach(view)` /
`onDispose { detach(view) }` plus the `keepScreenOn` binding. All 9
`LaunchedEffect`s and both buses deleted.

### 1.8 Engine-side and container-side homes (realtime grafts)

| Concern | Home | Why |
|---|---|---|
| A-B loop enforcement | `PlaybackEngine` (`setAbLoop(startMs, endMs)?`/`clearAbLoop()`; enforced against the player's own position callbacks/seek scheduling) | Must survive the ViewModel while the service plays; a VM-scoped Progression check dies with the screen. |
| Sleep-timer fade hook | `PlaybackEngine` (timer countdown may live in `PlaybackState` UI, but the fade+stop execution is an engine call that completes even if the VM is gone) | Same argument. |
| Listen-time accrual | `ListenTimeTracker` — container-owned, `applicationScope`, collects `playback.progression`, accrues into `HistoryStore` | Accrual during service-only playback with no UI alive. |
| Feature enrichment | `FeatureEnricher` (container) reading `playback.progression.value` + `enricherInputs.sections` | Off the UI thread; no VM in the path. |

---

## 2. Renderer boundary — exact mechanism

### 2.1 `VisualizerController` — full source contract (write it exactly like this)

The synchronization below is adopted verbatim from the clean design's
controller (its single best piece of code), hardened with the realtime
design's bounds and backpressure. The four laws (§0.2) go in the class KDoc.

```kotlin
package dev.musicviz.render.contract

/** [Four laws of the frame loop KDoc — §0.2 verbatim] */
class VisualizerController(initial: RenderConfig = RenderConfig.DEFAULT) {

    // ---- config: UI-rate writers, GL-rate single reader ----
    private val _config = AtomicReference(initial)
    val config: RenderConfig get() = _config.get()

    /** UI/holder threads only. Atomic read-modify-write; generation bumps for
     *  cheap change detection. Low frequency; CAS cost irrelevant. */
    fun update(transform: (RenderConfig) -> RenderConfig) {
        _config.updateAndGet { transform(it).copy(generation = it.generation + 1) }
        sink?.onConfigChanged()
    }

    // ---- events: GL-thread emitter (funnel only), UI-plane consumers ----
    private val _events = MutableSharedFlow<RenderEvent>(
        extraBufferCapacity = 16,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,   // loss-tolerant UI feedback
    )
    val events: SharedFlow<RenderEvent> = _events

    /** Called ONLY from the renderer's flushEvents() funnel (and tests). */
    fun emit(event: RenderEvent) { _events.tryEmit(event) }

    // ---- commands + attach/detach handshake ----
    @Volatile private var sink: RenderSink? = null
    private val pending = ArrayDeque<RenderCommand>()     // guarded by @Synchronized

    @Synchronized
    fun submit(command: RenderCommand) {
        val s = sink
        if (s != null) { s.enqueue(command); return }
        if (pending.size >= MAX_PENDING) pending.removeFirst()  // bounded: drop oldest
        pending.add(command)                                    // milk-preset-before-surface case
    }

    @Synchronized
    fun attach(sink: RenderSink) {
        this.sink = sink
        while (pending.isNotEmpty()) sink.enqueue(pending.removeFirst())
    }

    @Synchronized
    fun detach(sink: RenderSink) { if (this.sink === sink) this.sink = null }

    private companion object { const val MAX_PENDING = 32 }
}
```

Notes that are part of the contract:
- `sink` is `@Volatile` because `update {}` reads it outside the lock; the
  identity check in `detach` prevents a stale surface from detaching its
  replacement (Auxio registered-holder discipline).
- The pending deque is **bounded at 32, drop-oldest, with a log line on drop**.
  An unbounded deque is a memory leak wearing a correctness costume.
- Events: `extraBufferCapacity = 16` **and** `onBufferOverflow = DROP_OLDEST`,
  both explicit. Consumers are loss-tolerant UI state; the GL thread must
  never block or fail on emit.

### 2.2 Config read side (top of `VisualizerRenderer.onDrawFrame`)

```kotlin
@Volatile private var controller: VisualizerController? = null  // set at attach
private var applied: RenderConfig = RenderConfig.DEFAULT

override fun onDrawFrame(gl: GL10?) {
    val cfg = controller?.config ?: RenderConfig.DEFAULT   // ONE volatile read
    if (cfg !== applied) { applyConfig(old = applied, new = cfg); applied = cfg }
    // ... the rest of the frame reads only `cfg`, `frame`, and locals —
    //     never a controller/tap field twice ...
    flushEvents()                                          // §2.4, single funnel
}
```

`applyConfig(old, new)` runs ON the GL thread, so it may legally do GL work
(warm a transition program when `old.transition !== new.transition`, switch
scene when `sceneId` changed). It diffs by reference per field group
(`old.layer !== new.layer` → apply the layer trio) so cheap changes stay
cheap. Guarantee: a frame can never observe `transition.id` from one write and
`transition.durationMs` from another — they ride one `TransitionSpec` inside
one `RenderConfig`.

### 2.3 Allocation-free per-frame audio reads

```kotlin
// VisualizerRenderer — constructor/attach injection, NOT a lambda into a VM
private val pcmScratch = FloatArray(MAX_TAP_SAMPLES)   // preallocated once

// per frame:
val frame = audioTap.frame                 // 1 volatile read of an immutable value
val n = audioTap.readPcmInto(pcmScratch)   // memcpy into caller-owned buffer,
                                           // zero-pad on underrun, never blocks
```

`viewModel.latestPcm()` and `pcmProvider` are deleted. Enrichment (progress,
sections) happens producer-side in `FeatureEnricher` on `dispatchers.default`,
extrapolating position from the engine's `Progression` snapshot — no ticker,
no Compose hop. Compose overlays wanting live features (VU meter, HUD) read
`audioTap.frame` inside `drawBehind`/`Canvas` draw lambdas only.

### 2.4 Events out — hardened outbox

The renderer **accumulates events into a preallocated list during the frame**
and flushes at exactly one point:

```kotlin
private val pendingEvents = ArrayList<RenderEvent>(8)   // preallocated, GL thread only

private fun queueEventOut(e: RenderEvent) { pendingEvents.add(e) }   // during frame

private fun flushEvents() {                              // end of onDrawFrame + error paths
    val c = controller ?: run { pendingEvents.clear(); return }
    for (i in pendingEvents.indices) c.emit(pendingEvents[i])
    pendingEvents.clear()
}
```

`VisualsState` collects `controller.events` and turns `ShaderError` into UI
state; the `onShaderError` volatile callback field is deleted.

### 2.5 Structural edges — timeout-guarded handshake (libGDX pause pattern)

Config/commands/events cover steady-state. Two edges must *synchronize* with
the GL thread: **export start/stop** (encoder needs the GL thread quiesced or
redirected) and **EGL surface recreation**. Contract:

```kotlin
// VisualizerRenderer — structural handshake. Caller thread: any non-GL thread.
private val structuralLock = Object()
@Volatile private var structuralRequest: StructuralRequest? = null  // sealed: BeginExport(...), EndExport, SurfaceReset

/** Posts the request and waits for the GL thread to acknowledge it at its next
 *  frame boundary. NEVER waits unbounded: 1000 ms timeout, then log + return
 *  false — callers must handle the false path (retry or abort), not hang. */
fun requestStructural(req: StructuralRequest, timeoutMs: Long = 1_000): Boolean {
    synchronized(structuralLock) {
        structuralRequest = req
        val deadline = System.currentTimeMillis() + timeoutMs
        while (structuralRequest != null) {
            val remaining = deadline - System.currentTimeMillis()
            if (remaining <= 0) { structuralRequest = null; return false }
            structuralLock.wait(remaining)
        }
    }
    return true
}

// GL thread, at the frame boundary (before applyConfig):
private fun serviceStructural() {
    val req = structuralRequest ?: return
    applyStructural(req)                    // runs ON the GL thread
    synchronized(structuralLock) { structuralRequest = null; structuralLock.notifyAll() }
}
```

Rules: exactly one in-flight structural request; the GL thread services it at
the frame boundary; the waiting thread always has a timeout; a `false` return
is a handled outcome (export aborts cleanly with a user-visible error), never
a silent hang. `ExportState` uses this for `startExport`/`stopExport`.

### 2.6 Threading contract summary

UI/holder threads: `controller.update{}` + `submit()` + `requestStructural()`.
GL thread: reads `config` once per frame, runs queued commands between frames,
services one structural request per frame boundary, reads the tap, flushes
events. Audio threads: write the ring; `FeatureEnricher` writes
`audioTap.frame`. No thread ever calls upward.

### 2.7 Mechanical enforcement — `RenderThreadPurityTest` (new gate)

Source-text test (deliberately — this is what source-text gates are *for*),
landed in its own commit BEFORE the renderer rewrite (§5 Step 3):

1. **(a)** Every file under `render/contract/` and the boundary files
   `audio/AudioTap.kt`, `audio/AudioFrame.kt`, `audio/FeatureEnricher.kt`,
   `audio/EnricherInputs.kt`, `audio/Progression.kt` contains zero imports of
   `dev.musicviz.ui.` or `androidx.lifecycle.` or `androidx.compose.`.
2. **(b)** `render/VisualizerRenderer.kt` contains no identifier matching
   `viewModel|ViewModel`.
3. **(c)** The only `object` declarations in `src/main` containing
   `MutableStateFlow`/`MutableSharedFlow` properties are on an explicit
   allowlist, and **the allowlist is empty**. This makes recreating
   `AudioBus`/`LayersBus` a failing build for the next two years.
4. **(d)** `VisualizerRenderer.kt` declares ≤2 public mutable `var`s.

### 2.8 Boundary test template (real controller, no mocks, no GL)

All holder→renderer tests assert on **values the frame loop would read**, with
a real `VisualizerController` (it is a plain object):

```kotlin
@Test
fun `publishes complete LayerSpec atomically when enabled`() = runTest {
    val controller = VisualizerController()
    val holder = VisualsState(backgroundScope, controller, /* fakes... */)
    holder.selectLayerScene("plasma"); holder.setLayerMix(0.8f); holder.setLayerBlend(SCREEN)
    val genBefore = controller.config.generation
    holder.setLayersEnabled(true)
    assertEquals(LayerSpec("plasma", 0.8f, LayerBlend.SCREEN), controller.config.layer)
    assertTrue(controller.config.generation > genBefore)
}
```

This template applies to every `VisualsState`/`AutoVisualsState` boundary test
(§5 Steps 4 and 10). Never mock the controller; never spin a GL thread.

---

## 3. God-ViewModel domain → new home (complete mapping)

| Domain (vm-coupling anchors) | Destination | Notes |
|---|---|---|
| Transport/queue/seek/shuffle/repeat (§§5 12) | `PlaybackState` | verbs collapse into a sealed `PlaybackCommand` (+ factory: `track(uri)`, `from(list, index)`, `all()`, `playlist(id)`, `shuffleAll()`) replacing the `playTrack/playFrom/playAll/playPlaylist` overload family; public holder functions keep their names and build commands internally. ExoPlayer never exposed (`player` public leak deleted). |
| Playback prefs (§2), sleep timer (§6) | `PlaybackState` (UI) + `PlaybackEngine` (fade execution, §1.8) | timer countdown flow-driven, no poll |
| A-B loop (§§0e 11) | `PlaybackEngine.setAbLoop/clearAbLoop` (§1.8); `PlaybackState` mirrors | enforcement survives the ViewModel |
| Listen-time accrual (E-2) | container `ListenTimeTracker` (§1.8) | driven by `playback.progression`, not a poll |
| Fades (§11) | `PlaybackState` verbs → engine calls | |
| Mic + external capture + now-playing (§§0b 1, E-23) | `AudioInputState` | pause-player edges via facade glue (E-7/E-8) |
| EQ/audio FX (§3, E-6) | `AudioFxState` | session id arrives via `PlaybackState` flow |
| Scene/params/locks/randomizer/transitions/shaders/textures/LFO/ADSR (§§4 15) | `VisualsState` → `VisualizerController` | replaces renderer vars 2, 6–12 |
| Milk presets (list/import/note/active path) | `VisualsState` (files); `LoadMilkPreset` via commands | load→note→persist round trip = command + `MilkPresetLoaded` event |
| Presets CRUD/share/folders/mirror (§15, E-19) | `PresetsState` | applies via `VisualsState.applyPresetSnapshot(...)` |
| Viz playlist / random mode / section staging / auto mode (§§7 8) | `AutoVisualsState` | own ticker (interval from prefs), beat impulse from `audioTap.frame` |
| Library/playlists/roots/track info (§9) | `MusicLibraryState` | `TrackLibrary` stays the data source |
| History/favourites/recently played (§§10 11) | `SessionState` | `historyTick` replaced by flows from `HistoryStore` |
| Intelligence/key colour/palette/waveform/lyrics (§13, 0e) | `AnalysisState` | per-track streams via `flatMapLatest(currentTrackId)`; sections pushed to `EnricherInputs` |
| Takes / export / studio (§§14 16 17) | `ExportState` | long verbs get `inProgress` in state; GL quiescence via structural handshake |
| Theme / guiPrefs (§0d) | `ThemeState` | wallpaper reads the same `ThemeStore` via container; safety→renderer via `RenderConfigFeed` |
| `enrichFeatures` / `latestPcm` (§4) | `FeatureEnricher` / `AudioTap` | leaves the UI layer entirely |
| 500ms poll loop (E-3, 7 domains) | dissolved: A-B loop → engine (§1.8); queue refresh → player-listener events; capture/mic refresh → `AudioInputState` flows; intelligence/viz-playlist/random/section → `AutoVisualsState`/`AnalysisState` tickers gated on `isPlaying`; position → `Progression` extrapolation | the single biggest behavioral risk; each slice migrates with its holder |
| `onCleared` (E-21) | facade calls `holder.close()` in reverse order; engine lifetime → container registration (§5 Step 12) | |
| Navigation additions (§10) | facade-level (2 members) | not worth a holder |

UI repointing: each of the 20 consumer files changes `viewModel.x` →
`viewModel.<holder>.x` in its thin stateful wrapper only; stateless
composables below are untouched (NIA two-layer contract).

---

## 4. Manual DI container spec

### 4.1 Scopes

| Scope | Owner | Contents |
|---|---|---|
| Process | `MusicVizApp.container` | all stores, `PlaybackEngine`, ring, `AnalysisEngine`, captures, `VisualizerController`, `AudioTap`, `FeatureEnricher`, `EnricherInputs`, `RenderConfigFeed`, `ListenTimeTracker`, `applicationScope`, `AppDispatchers` |
| Screen/VM | `PlayerViewModel` | the 12 holders (constructed with `viewModelScope`) + glue collectors |
| Surface | `VisualizerView` ↔ controller | attach/detach handshake; renderer instance lifetime = GL surface lifetime |

Holders are deliberately VM-scoped: `stateIn(WhileSubscribed)` economics and
`SavedStateHandle` access stay standard; config-change survival comes free.
**Anything a Service or the wallpaper needs must live in the container, never
in a holder.**

### 4.2 AppContainer (write it exactly like this)

```kotlin
class AppContainer(private val app: Application) {
    val dispatchers = AppDispatchers.Default
    val applicationScope = CoroutineScope(SupervisorJob() + dispatchers.default)

    val playback by lazy { PlaybackEngine.obtain(app) }        // refcount deleted in Step 12
    val ring get() = playback.ring
    val analysisEngine by lazy { AnalysisEngine(ring) }
    val micCapture by lazy { MicCapture(app, ring) }
    val playbackCapture by lazy { PlaybackCapture(ring) }
    val nowPlayingBridge by lazy { NowPlayingBridge(app) }

    val presetStore by lazy { PresetStore(app) }
    val trackLibrary by lazy { TrackLibrary(app) }
    val themeStore: ThemeStoreApi by lazy { ThemeStore(app) }  // interface-typed: faked in tests
    val playerPrefsStore by lazy { PlayerPrefsStore(app) }
    val autoVisualsPrefsStore by lazy { AutoVisualsPrefsStore(app) }
    val textureStore by lazy { TextureStore(app) }
    val lfoStore by lazy { LfoStore(app) }
    val musicPlaylistStore by lazy { MusicPlaylistStore(app) }
    val historyStore by lazy { HistoryStore(app) }
    val takeStore by lazy { TakeStore(app) }
    val favouritesStore by lazy { FavouritesStore(app) }
    val offlineAnalyzer by lazy { OfflineAnalyzer(app) }
    val exportEngine: ExportEngine by lazy { VideoExporter(app) }        // interface-typed
    val studioExportEngine: StudioExportEngine by lazy { StudioExporter(app) }

    val visualizerController by lazy { VisualizerController() }
    val audioTap by lazy { AudioTap(ring) }
    val enricherInputs = EnricherInputs()
    val featureEnricher by lazy {
        FeatureEnricher(analysisEngine, audioTap, playback, enricherInputs, dispatchers)
    }
    val renderConfigFeed by lazy {
        RenderConfigFeed(applicationScope, themeStore, lfoStore, visualizerController)
    }
    val listenTimeTracker by lazy { ListenTimeTracker(applicationScope, playback, historyStore) }

    fun newWallpaperController(): VisualizerController =
        VisualizerController(RenderConfig.fromGuiPrefs(themeStore.loadGui()))

    /** Explicit start — called from MusicVizApp.onCreate. No lazy `.also { start() }`
     *  side effects anywhere in this class. */
    fun start() {
        featureEnricher.start()
        renderConfigFeed.start()
        listenTimeTracker.start()
    }
}
```

### 4.3 Interface-seam policy (fold of the clean design, scoped down)

**Do NOT build a 13-repository interface layer.** Extract a narrow interface
only when a holder's JVM test needs a fake of a store — lazily, in the same
step as the extraction that needs it — and type the container val with the
interface (the hand-written `@Binds` pattern). Known-required set:

| Interface | Impl (unmoved file) | Needed by | Extracted in step |
|---|---|---|---|
| `ThemeStoreApi` (`load/save/loadGui/saveGui` + `guiPrefs: Flow<GuiPrefs>`) | `ThemeStore` | `ThemeStateTest`, `RenderConfigFeedTest` | 5 (feed) / 6 (holder) |
| `ExportEngine` | `VideoExporter` | `ExportStateTest` | 7 |
| `StudioExportEngine` | `StudioExporter` | `ExportStateTest` | 7 |
| further seams | per store | only when a test fakes it | that step |

Interfaces live next to their impl file (same package, new file, no moves).
Everything else stays concrete — fakes for prefs-shaped stores that already
have trivial constructors may simply be in-memory subclass-free fakes of the
interface; never mocking libraries.

### 4.4 `RenderConfigFeed` (write it exactly like this)

```kotlin
class RenderConfigFeed(
    private val scope: CoroutineScope,
    private val themeStore: ThemeStoreApi,
    private val lfoStore: LfoStore,
    private val controller: VisualizerController,
) {
    fun start() {
        scope.launch {
            themeStore.guiPrefs.map { it.safety }.distinctUntilChanged()
                .collect { safety -> controller.update { it.copy(safety = safety) } }
        }
        scope.launch {
            lfoStore.configs.distinctUntilChanged()
                .collect { (lfos, adsrs) ->
                    controller.update { it.copy(lfoConfigs = lfos, adsrConfigs = adsrs) }
                }
        }
    }
}
```

Covered by a JVM test (`RenderConfigFeedTest`): fake store emits a prefs
change → assert `controller.config.safety` changed and generation bumped.
A silent feed bug means safety stops applying with no UI alive — this test is
not optional.

### 4.5 Wallpaper / service path

- `VisualizerWallpaperService`: `val c = (application as MusicVizApp).container`
  → reads `c.audioTap` (replacing `AudioBus`), `c.themeStore` (replacing its
  private `ThemeStore(this)`), `c.presetStore` instance reads. Its renderer
  gets its **own** controller from `c.newWallpaperController()` — seeded from
  persisted `GuiPrefs` so the wallpaper renders correct safety **before any
  feed tick** — plus a feed-lite binding (safety + selected preset) on
  `applicationScope`. The tap is shared; controllers are per-surface; each
  renderer instance owns its own `applied` diff state.
- `PlaybackService` / `PlaybackCaptureService`: container access for
  `historyStore`, `playback`, MediaProjection holder replacement. Services
  never see holders or ViewModels.

### 4.6 Testing story

- Holders: `runTest` + `AppDispatchers(testDispatcher, testDispatcher,
  testDispatcher)` + collector on `backgroundScope` for `WhileSubscribed`;
  fakes over mocks, behavior over source text (§7.5 recipe).
- Boundary: real `VisualizerController`, assert on `config` values (§2.8).
- Controller itself: `VisualizerControllerTest` — pending replay on attach,
  bound-at-32 drop-oldest, detach identity check, DROP_OLDEST on events.
- Feed: `RenderConfigFeedTest` (§4.4).
- Shared fixture: `TestContainer.kt` builds an `AppContainer`-shaped test
  double for the 13 behavior tests that construct the real VM.

---

## 5. Migration plan — 13 individually-green steps with named gates

Rules for every step: (a) one conventional commit per step; (b)
`./gradlew :app:testDebugUnitTest :app:lintDebug :app:ktlintCheck` green before
merge; (c) gate updates ship IN the step's commit; (d) before declaring any
step done, run `grep -rl "<old path or symbol>" app/src/test/` — 32 test files
reference `src/main` paths in some form; (e) gates are re-pointed, never
deleted — they encode real invariants. Rollback: every step is a single commit
touching new files + shrink-in-place files + named gates; `git revert` of any
step is clean because later steps only add holders and delete delegators.

**Step 0 — Gate inventory (no prod change).**
Script (scratchpad, output in the PR description, not the repo) listing every
`ParamSurface`/`ParamMatrix` `source()` call and every `src/main` path literal
in `app/src/test`. *Gates touched: none.*

**Step 1 — AppContainer + factory (construction farm moves, nothing else).**
New `di/AppContainer.kt`, `di/AppDispatchers.kt`, `di/ViewModelFactory.kt`;
`MusicVizApp.container` + `container.start()` (initially near-empty) in
`onCreate`; `PlayerViewModel` constructor becomes `(container,
savedStateHandle)`; the 18 `= XxxStore(application)` lines become
`container.xxx` reads; MainActivity uses the factory; PlaybackService and the
wallpaper repoint their private `HistoryStore`/`ThemeStore` constructions to
the container.
*Gates:* `InitOrderTest`, `ViewModelSurfaceTest`, `PresetMirrorSyncTest`,
`playback/SleepTimerDelegationTest`, `ExportTakeSceneTest`, `DeadVmApiTest`
re-anchor on changed VM text; the 13 behavior tests constructing the real VM
(`VmBehaviorTest` et al.) switch to `TestContainer.kt` (one shared helper).
*Risk:* lowest; pure plumbing.

**Step 2 — AudioTap: kill the GL→ViewModel per-frame call.**
New `audio/AudioTap.kt`, `audio/AudioFrame.kt`, `audio/Progression.kt`
(equality ignores `creationTime`), `audio/FeatureEnricher.kt`,
`audio/EnricherInputs.kt`. `PlaybackEngine` gains
`val progression: StateFlow<Progression>` written from its own player-listener
discontinuities (the engine owns the player; no VM involvement). Renderer
gains `attachAudio(tap)`; `pcmProvider`, `latestPcm()`, `enrichFeatures()`
deleted; EnginePlumbing's features-collect `LaunchedEffect` deleted; wallpaper
repointed `AudioBus`→`container.audioTap`; `AudioBus` deleted.
*Gates:* `WallpaperIdleTest` (re-point publish/features/isLive/clear to
`AudioTap`, same semantics), `EnginePlumbingCoverageTest` (two fewer
bindings), `DeadVmApiTest` (pin `latestPcm`/`enrichFeatures` OUT),
`ViewModelSurfaceTest`. New: `AudioFrameParityTest` (JVM) asserting
`AudioFrame.progress` matches `Progression.elapsedMs()/duration`.
*Risk:* enrichment parity — the parity test is the mitigation.

**Step 3 — Contract package + purity gates land FIRST (new files only).**
New `render/contract/` package in full: `RenderConfig` (+`fromGuiPrefs`),
`LayerSpec`, `TransitionSpec`, `RenderCommand`, `RenderEvent`, `RenderSink`,
`VisualizerController` exactly per §2.1 with the four-laws KDoc. New gate
`RenderThreadPurityTest` (§2.7 clauses a–d; clause (b)/(d) assert on the
*current* renderer only after Step 4 — in this step they are written but
scoped to the contract package + allowlist clause (c), with (b)/(d) marked
`@Ignore` and un-ignored in Step 4's commit). New `VisualizerControllerTest`
(pending replay, bound, DROP_OLDEST, detach identity). **Nothing is rewired;
no existing gate is touched.** This is the commit that makes the boundary a
failing build instead of a review habit, in place before the mechanical
rewrite can regress anything.
*Gates:* none re-pointed; two new gates added.
*Risk:* none — additive.

**Step 4 — Renderer rewrite: 12 vars → one config (mechanical).**
`VisualizerRenderer`: 12 public `@Volatile` vars become private; single
`config` read + `applyConfig(old, new)` diff at frame top; event accumulation
+ `flushEvents()` funnel (§2.4); structural handshake primitives (§2.5);
`onShaderError` → `RenderEvent.ShaderError`. EnginePlumbing keeps its 9
effects **temporarily** but each body becomes mechanical
`controller.update { it.copy(...) }` / `controller.submit(...)` — flow
sources untouched. `LayersBus.state` writes into the controller too.
Un-ignore purity clauses (b)/(d).
*Gates:* `EnginePlumbingCoverageTest` **inverted** — re-specified as: every
`RenderConfig` field is consumed in `applyConfig` AND no public renderer
mutable var is written outside `render/**` and tests; `LayersCustomizeTest`
re-points defaults to `RenderConfig.DEFAULT`; `RendererWiringTest`,
`RecoveredShaderStylesTest`, `TrailWarpDecayDedupTest`, `RenderClockWrapTest`
re-anchor to `applyConfig` text (file path unchanged);
`RenderThreadPurityTest` now fully armed.
*Risk:* applyConfig ordering — port the existing per-var side effects (e.g.
`warmTransition`) into the diff branches verbatim; boundary tests use the §2.8
real-controller template.

**Step 5 — RenderConfigFeed + wallpaper controller seeding.**
New `di/RenderConfigFeed.kt` per §4.4, started from `container.start()`;
extract `ThemeStoreApi` (first interface seam, §4.3); wallpaper switches to
`container.newWallpaperController()` (seeded `fromGuiPrefs`) + feed-lite.
*Gates:* new `RenderConfigFeedTest` (feed writes on pref change — mandatory);
`WallpaperIdleTest` unchanged (tap semantics untouched); sweep for
`ThemeStore` symbol assertions in `app/src/test`.
*Risk:* low; the feed is additive and the wallpaper seeding replaces
equivalent hand-rolled reads.

**Step 6 — ThemeState (first holder; template for the rest).**
New `ui/state/UiStateHolder.kt` + `ui/state/ThemeState.kt`; facade exposes
`val theme: ThemeState`, keeps `setTheme`/`setGuiPrefs`/`guiPrefs`
delegators; repoint LookSettings + FontColorChoice to `viewModel.theme`.
Full worked example in §7.
*Gates:* `ViewModelSurfaceTest`, `DeadVmApiTest`, `LiveStateWriteTest`
(guiPrefs writes) re-anchor.
*Risk:* minimal — proof-of-shape step; the E-10 engine pass-through stays a
3-line block.

**Step 7 — ExportState (takes + export + studio).**
Extract §§14/16/17; extract `ExportEngine`/`StudioExportEngine` interfaces
(§4.3); export start/stop goes through the structural handshake (§2.5);
ExportHost, StudioScreen, VisualsHub(takes slice) repoint. The viz-snapshot
read (E-15/E-16) is expressed as a `currentVizSnapshot()` pure function on the
facade in this step and moves to `VisualsState.currentSnapshot()` in Step 10.
*Gates:* `ExportTakeSceneTest` (pins `ui/PlayerViewModel.kt` — keep
`exportSceneIdFor` in the facade OR re-point the gate to
`ui/state/ExportState.kt` in the same commit; pick one, do it in this commit),
`TakeRecordingUxTest`, `ExportOutcomeTest` sweep.
*Risk:* the handshake path — `ExportStateTest` must cover the `false`
(timeout) branch aborting cleanly.

**Step 8 — MusicLibraryState + SessionState + ListenTimeTracker.**
Extract §9 and history/favourites; new container-side `ListenTimeTracker`
(§1.8) collecting `playback.progression`; library screens, PlayerPanels,
history/favourites repoint. E-2 listen-time glue leaves the VM entirely.
*Gates:* `PlaylistCreationTest`, `ListeningHistoryTest`,
`ViewModelSurfaceTest`, `DeadVmApiTest`; run the inventory sweep.
*Risk:* accrual parity — `ListenTimeTrackerTest` (JVM) drives a fake
progression sequence and asserts identical accrual to the old poll logic.

**Step 9 — AudioInputState + AudioFxState.**
AudioSettings, ExternalAudioSettings, EqualizerSettings repoint. E-7/E-8
pause-player edges become facade glue collectors.
*Gates:* `CaptureStartFailureTest`, `LiveStateWriteTest`,
`LiveInputProfileTest`, `audio/PlaybackCaptureContractTest` sweep.
*Risk:* covered by existing capture tests.

**Step 10 — VisualsState + PresetsState + AutoVisualsState + AnalysisState
(the big one — four green sub-commits).**
10a `VisualsState`: extract §§4/15; holder verbs write the controller
directly; EnginePlumbing's remaining effects DELETE one-by-one as verbs take
over; `LayersBus` dissolved into a `layer` field on `VisualsState` state;
`currentSnapshot()` moves here. 10b `PresetsState` (§15-part, E-19).
10c `AutoVisualsState` (§§7/8, E-17/E-18): per-holder ticker replaces its
poll slices. 10d `AnalysisState` (§13, E-12/13/14): sections handoff via
`enricherInputs.sections = ...` setter — **never a capturing lambda**.
EnginePlumbing lands at its final ~35-line form after 10c.
*Gates:* `EnginePlumbingCoverageTest` (final form), `LayersCustomizeTest`,
`CustomizeLockAffordanceTest`, `TextureRemovalCoherenceTest`,
`MilkPresetSaveTest`, `MilkImportNameTest`, `PresetShareImportTest`,
`PresetImportLaunderTest`, `PresetMirrorSyncTest`,
`AutoVisualsPersistenceTest`, `VisualsHubLogicTest`, `DeadVmApiTest`
(`removeVizPlaylistAt` — update BOTH halves: deleted-name AND pinned-caller),
`ParamSurface`/`ParamMatrix` map entries for every moved member.
*Risk:* highest of the plan; the four sub-commits are each individually green,
and every boundary assertion uses the §2.8 template.

**Step 11 — PlaybackState last (everything reads it).**
Extract transport/queue/loop-mirror/timer-UI/prefs; introduce
`PlaybackCommand` sealed values + factory (§3) behind the existing function
names; move A-B loop enforcement and sleep-fade execution INTO
`PlaybackEngine` (§1.8); decompose the remaining 500ms poll (position →
`Progression` extrapolation with a `WhileSubscribed` deci-second UI ticker;
queue refresh → player-listener events); delete the `player` public leak.
Write the facade E-1/E-3 glue FIRST against the old code, then swap internals.
*Gates:* `playback/SleepTimerDelegationTest`, `playback/SleepTimerTest`,
`playback/QueueOpsTest`, `PlaybackQueueTest`, `playback/PlaybackEngineTest`,
`playback/PlaybackResumptionTest`, `InitOrderTest` (init stays last),
`ViewModelSurfaceTest`, `VmBehaviorTest`.
*Risk:* E-1/E-3 fan-outs; glue-first ordering is the mitigation.

**Step 12 — Refcount deletion + facade slimming + sweep.**
`PlaybackEngine.acquireForUi/releaseUi` → container-owned `obtain()` +
Auxio-style "is a UI attached" registration for service-stop decisions; delete
remaining zero-consumer members (`presetLocked` public + `togglePresetLock`,
`open`, `queueTitles`, `setTransitionStyle` — vm-coupling §7 watchlist);
delete every temporary delegator whose screens have repointed; final
`ParamSurface` map audit; 20-minute soak per the quality bar.
*Gates:* `DeadVmApiTest` (pin deletions out), final `ViewModelSurfaceTest`
shape (facade ≤300 lines), full suite.
*Risk:* low; deletions only, each with a green build.

---

## 6. Deletion list (and what replaces each)

| Deleted | Replaced by | Step |
|---|---|---|
| `pcmProvider = { viewModel.latestPcm() }` + `PlayerViewModel.latestPcm/pcmScratch/pcmCursor` | `AudioTap.readPcmInto` (renderer-owned scratch) | 2 |
| `PlayerViewModel.enrichFeatures` + features `LaunchedEffect` hop | `FeatureEnricher` on `dispatchers.default` | 2 |
| `AudioBus` (process-global object) | container-owned `AudioTap` | 2 |
| 12 public renderer `@Volatile` vars + 9 EnginePlumbing effects | `RenderConfig` + `VisualizerController.update{}` | 4, 10 |
| `onShaderError` volatile callback field | `RenderEvent.ShaderError` on the events flow | 4 |
| `LayersBus` (process-global object) | `VisualsState` layer field → `RenderConfig.layer` | 10a |
| 500 ms poll loop (E-3) | per-holder flows/tickers + `Progression` + engine-side A-B loop | 7–11 |
| VM-scoped listen-time accrual | container `ListenTimeTracker` | 8 |
| `PlayerViewModel.player` public ExoPlayer leak | nothing (zero consumers) | 11 |
| `playTrack/playFrom/playAll/playPlaylist` overload family internals | `PlaybackCommand` values + factory | 11 |
| `PlaybackEngine.acquireForUi`/`releaseUi` refcounting | container-owned singleton + attach registration | 12 |
| Zero-consumer members (`presetLocked` public + `togglePresetLock`, `open`, `queueTitles`, `setTransitionStyle`) | nothing (internal equivalents in holders) | 12 |
| VM-level MediaProjection collector wiring | `AudioInputState` | 9 |
| Inline construction farm (18 `= XxxStore(application)` lines) | `AppContainer` | 1 |
| PlaybackService's private `HistoryStore`, wallpaper's private `ThemeStore` | container access | 1 |

Explicitly NOT deleted: the store classes (already data-source-shaped), the
Scene interface and render pipeline internals, `SavedStateHandle` usage,
`queueEvent` (it becomes the command transport), and the source-text gates
themselves — re-pointed, never removed.

**Explicitly deferred (documented option, NOT part of this migration):** the
Auxio `PlaybackStateManager`/`StateAck`/registered-holder playback core. If
playback bugs cluster after Step 12 (queue/restore/fade edge cases), adopt it
as a post-migration phase: the thin `PlaybackState` holder and
`PlaybackCommand` values from Step 11 are exactly the shape that core slots
under. Do not fold it into rounds 3+ work unless that bug cluster materializes.

---

## 7. Worked example — ThemeState end-to-end (Step 6)

### 7.1 `ui/state/UiStateHolder.kt` (new, shared)

```kotlin
package dev.musicviz.ui.state

abstract class UiStateHolder<S : Any>(
    protected val scope: CoroutineScope,
    initial: S,
) {
    private val _state = MutableStateFlow(initial)
    val state: StateFlow<S> = _state.asStateFlow()

    /** The only state writer. Atomic, safe from any thread. */
    protected fun reduce(block: (S) -> S) = _state.update(block)

    /** Launch helper: CancellationException is always rethrown, never swallowed. */
    protected fun launchIntent(block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            onError(e)
        }
    }

    protected open fun onError(e: Exception) { Log.e(javaClass.simpleName, "intent failed", e) }
    open fun close() {}
}
```

### 7.2 `ThemeStoreApi` seam (next to `ThemeStore`, no file moves)

```kotlin
interface ThemeStoreApi {
    val guiPrefs: Flow<GuiPrefs>          // hot flow consumed by RenderConfigFeed
    fun load(): AppThemeSpec
    fun loadGui(): GuiPrefs
    fun save(theme: AppThemeSpec)
    fun saveGui(prefs: GuiPrefs)
}
// existing class gains ": ThemeStoreApi"; container val is typed ThemeStoreApi (§4.2)
```

### 7.3 `ui/state/ThemeState.kt` (new)

```kotlin
package dev.musicviz.ui.state

data class ThemeUiState(
    val theme: AppThemeSpec = AppThemeSpec.DEFAULT,
    val guiPrefs: GuiPrefs = GuiPrefs(),
)

class ThemeState(
    scope: CoroutineScope,
    private val themeStore: ThemeStoreApi,
    private val analysisEngine: AnalysisEngine,
    private val dispatchers: AppDispatchers,
) : UiStateHolder<ThemeUiState>(
    scope = scope,
    initial = ThemeUiState(theme = themeStore.load(), guiPrefs = themeStore.loadGui()),
) {
    init {
        // E-10 pass-through: beat gate tuning lives with the engine, sourced here.
        applyEngineTuning(state.value.guiPrefs)
    }

    fun setTheme(theme: AppThemeSpec) {
        reduce { it.copy(theme = theme) }
        launchIntent { withContext(dispatchers.io) { themeStore.save(theme) } }
    }

    fun setGuiPrefs(prefs: GuiPrefs) {
        reduce { it.copy(guiPrefs = prefs) }
        applyEngineTuning(prefs)
        launchIntent { withContext(dispatchers.io) { themeStore.saveGui(prefs) } }
        // NOTE: renderer safety is NOT written here — RenderConfigFeed observes
        // themeStore.guiPrefs and writes the controller (works with zero UI alive).
    }

    private fun applyEngineTuning(prefs: GuiPrefs) {
        analysisEngine.beatThresholdSigma = prefs.beatSigma
        analysisEngine.beatMinIntervalMs = prefs.beatMinIntervalMs
    }
}
```

### 7.4 Facade wiring (in `ui/PlayerViewModel.kt`, shrink-in-place)

```kotlin
val theme: ThemeState = ThemeState(
    scope = viewModelScope,
    themeStore = container.themeStore,
    analysisEngine = container.analysisEngine,
    dispatchers = container.dispatchers,
)

// Temporary delegators — deleted when LookSettings/FontColorChoice repoint:
fun setTheme(t: AppThemeSpec) = theme.setTheme(t)
fun setGuiPrefs(p: GuiPrefs) = theme.setGuiPrefs(p)
```

### 7.5 Screen usage (`LookSettings.kt` thin wrapper — stateless UI untouched)

```kotlin
@Composable
fun LookSettingsRoute(viewModel: PlayerViewModel) {
    val ui by viewModel.theme.state.collectAsStateWithLifecycle()
    LookSettingsScreen(                 // existing stateless composable, unchanged
        theme = ui.theme,
        guiPrefs = ui.guiPrefs,
        onThemeChange = viewModel.theme::setTheme,
        onGuiPrefsChange = viewModel.theme::setGuiPrefs,
    )
}
```

### 7.6 Tests (`ui/state/ThemeStateTest.kt` — JVM, fakes, behavior-over-text)

```kotlin
class FakeThemeStore : ThemeStoreApi {
    private val _gui = MutableStateFlow(GuiPrefs())
    override val guiPrefs: Flow<GuiPrefs> = _gui
    var savedGui: GuiPrefs? = null; var failNextSave = false
    override fun load() = AppThemeSpec.DEFAULT
    override fun loadGui() = _gui.value
    override fun save(theme: AppThemeSpec) { if (failNextSave) error("disk full") }
    override fun saveGui(prefs: GuiPrefs) { savedGui = prefs; _gui.value = prefs }
}

class ThemeStateTest {
    private val store = FakeThemeStore()
    private val engine = FakeAnalysisEngine()

    private fun TestScope.holder() = ThemeState(
        scope = backgroundScope,
        themeStore = store,
        analysisEngine = engine,
        dispatchers = AppDispatchers(testDispatcher, testDispatcher, testDispatcher),
    )

    @Test
    fun `setGuiPrefs persists and retunes the beat gate`() = runTest {
        val theme = holder()
        theme.setGuiPrefs(GuiPrefs(beatSigma = 2.5f))
        advanceUntilIdle()
        assertEquals(2.5f, theme.state.value.guiPrefs.beatSigma)   // state
        assertEquals(2.5f, store.savedGui?.beatSigma)              // persistence
        assertEquals(2.5f, engine.beatThresholdSigma)              // E-10 edge
    }

    @Test
    fun `setTheme survives store failure without corrupting state`() = runTest {
        store.failNextSave = true
        val theme = holder()
        theme.setTheme(AppThemeSpec(name = "midnight"))
        advanceUntilIdle()
        assertEquals("midnight", theme.state.value.theme.name)     // optimistic UI kept
    }
}

// Companion boundary test (RenderConfigFeedTest, Step 5) — the no-UI safety path:
class RenderConfigFeedTest {
    @Test
    fun `safety pref change reaches the controller with no UI composed`() = runTest {
        val store = FakeThemeStore()
        val controller = VisualizerController()
        RenderConfigFeed(backgroundScope, store, FakeLfoStore(), controller).start()
        val genBefore = controller.config.generation
        store.saveGui(GuiPrefs(safety = SafetyMode.REDUCED_FLASH))
        runCurrent()
        assertEquals(SafetyMode.REDUCED_FLASH, controller.config.safety)
        assertTrue(controller.config.generation > genBefore)
    }
}
```

This template (interface seam where a fake is needed + holder + facade val +
delegators + route repoint + fake-based behavior test + real-controller
boundary test where the render plane is touched) applies verbatim to every
subsequent extraction step.

---

## 8. Quality-bar compliance checklist

| Bar item | Where satisfied |
|---|---|
| Eng 1–2 (UDF, StateFlow per concern, WhileSubscribed, collectAsStateWithLifecycle) | holders §1.5, template §7 |
| Eng 3 (constructor injection, no inline construction) | `AppContainer` §4, Step 1 |
| Eng 4 (package-by-feature, <700-line files, no God classes) | §1 size budgets; facade ≤300 |
| Eng 5 (injected dispatchers, no GlobalScope, CE rethrow) | `AppDispatchers`; `UiStateHolder.launchIntent` |
| Eng 6–7 (immutable state; per-frame values in draw phase) | UiState data classes; §2.3 drawBehind rule |
| Eng 8 (JVM fakes, behavior over source-text) | §7.6 recipe; gates re-pointed not multiplied; purity gates are the deliberate exception (§2.7) |
| Viz 1 (one normalized audio-uniform contract/frame) | `AudioFrame` via `AudioTap` |
| Viz 5 (scenes touch no renderer/EGL/audio code) | `RenderConfig`/commands keep the Scene interface closed |
| Viz 10 / Player 7 (render stops when invisible) | controller detach on surface teardown; no Compose effect keeps loops alive |
| Player 3 (UI holds controller, never the player) | `player` leak deleted; `PlaybackCommand` verbs only |
| Player 6 (queue survives; enforcement outlives UI) | engine-side A-B loop/fades §1.8; deferred Auxio core documented §6 |

---

*End of blueprint. Execute §5 in order; every step ends green; every commit
names its gates.*
