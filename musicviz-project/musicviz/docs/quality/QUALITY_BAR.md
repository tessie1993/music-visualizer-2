# MusicViz Quality Bar

Clean-room quality standard for this app, researched from external references only
(never from this repo's own conventions). Reviewers judge the codebase against this
bar; the bar is met when an independent critic panel is unanimously satisfied with
no CRITICAL/HIGH/MEDIUM findings remaining.

References researched (2026-08):

- **Audio player**: Poweramp (gold standard), Auxio, Musicolet, RetroMusicPlayer,
  Media3/AOSP guidance → [bar-audio-player.md](bar-audio-player.md)
- **Visualizer**: projectM/MilkDrop 3, Shadertoy/demoscene practice, Apple Music
  visualizer, Wallpaper Engine, Android GL specifics → [bar-visualizer.md](bar-visualizer.md)
- **Engineering**: Now in Android, Auxio, Tivi, Signal-Android, official Android
  architecture guidance → [bar-android-engineering.md](bar-android-engineering.md)

## Non-negotiable review criteria (condensed)

### Player

1. Gapless playback via one long-lived player + playlist API; re-prepare-on-ended is a fail.
2. Audio focus + becoming-noisy handled without releasing the player.
3. Playback owned by a media session service; UI holds a controller, never the player.
4. Notification/lockscreen/AVRCP are pure session mirrors with full metadata.
5. Library scanning off-main, incremental; cached library visible fast on launch.
6. Queue is a persisted domain model surviving process death, edge cases unit-tested.
7. Foreground service only while playing; visualizer loops stop when invisible.

### Visualizer

1. One normalized audio-uniform contract per frame, auto-gained; shaders never see raw FFT.
2. Hann-windowed FFT, log/dB magnitude mapping.
3. Asymmetric attack/release envelopes, frame-rate independent (exp(-dt/tau)).
4. Onsets via rectified spectral flux with adaptive threshold + refractory period.
5. Data-driven scenes behind a common interface; adding a scene touches no renderer/EGL/audio code.
6. Central palette discipline; dither before 8-bit write.
7. Choreographer-based frame pacing with measured clamped dt; p95 frame-time tracked.
8. Thermal quality scaler (render scale / fps cap / effect tier); judged on 20-minute soak.
9. Shader hygiene: mediump default, bounded loops, no divergent uniform ladders,
   no chained dependent texture reads, tile-based-GPU discipline.
10. Rendering fully stops when the surface is invisible.

### Engineering

1. UDF layering: UI → ViewModel → repository; data exposed as Flow, never snapshots.
2. One StateFlow<UiState> per screen via stateIn(WhileSubscribed); collectAsStateWithLifecycle.
3. Constructor injection everywhere; no inline construction of collaborators in ViewModels.
4. Package-by-feature; no util dumping grounds; files < ~700 lines; no God classes.
5. Injected dispatchers; no GlobalScope/runBlocking; CancellationException never swallowed.
6. Sealed UiState hierarchies; immutable state data classes.
7. Compose: stateless previewable screens, hoisted state, per-frame values read in the
   draw phase only, stable lazy-list keys, central theme tokens.
8. Tests: JVM-heavy pyramid, fakes over mocks, behavior over source-text assertions.
9. Hygiene: ktlint + lint enforced in CI, version catalog, complexity gate (detekt).

## Gauntlet exit condition

- Every critic on the panel (architecture, Kotlin/Compose, GPU/visual, audio,
  product polish) independently returns the top verdict ("wowed") against this bar.
- Zero CRITICAL, HIGH, or MEDIUM findings remain.
- A dedicated skeptic pass fails to void any of the verdicts.
- Build, unit tests, ktlint, and lint are green.
