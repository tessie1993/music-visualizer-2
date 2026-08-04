# MusicViz Perfection Plan — v1.8 → v2.0

Written 2026-08-04 from a full-codebase audit (16 verification agents over every subsystem), a
green build/test/lint run, and online research into competitors, user forums, open-source prior
art, and design references (evidence base: `docs/RESEARCH_APPENDIX.md`). Every bug below was
adversarially verified against the code with file:line evidence; nothing here is speculative.

The plan is organised as eight workstreams (A–H) and a release sequence. Each item carries an
acceptance criterion — the plan is done when every criterion is met, not when the code merely
changes.

---

## 0. Where we stand

**Green:** unit tests (debug+release), ktlint, Android lint, assembleDebug all pass; the APK
builds clean. The codebase is unusually disciplined: source-level contract tests
(RendererWiringTest, HyperspaceUniformParityTest, SceneFailureTest), a durability-designed store
layer (AtomicWrite), derived numeric invariants (TIME_WRAP_SEC), and zero TODO/FIXME debt across
42k lines.

**Not green:** `docs/VISUAL_STYLES_PLAN.md` — the twenty-style 3D rebuild — is ~10% done. What
shipped (commits 6fe781a, 315645e) is a smaller "profiles" slice: all twenty style names as
`uStyle` shader branches with per-style control biases. The plan's Phase 0 foundation
(`render/space/`, depth, cameras, meshes, volumes, quality ladder) does not exist. Two planned
styles (`hyper_membrane`, `hyper_vivarium`) were silently replaced by unplanned ones.

**Verified defects:** 1 high-severity data-loss bug, ~12 medium correctness/durability/UX bugs,
~20 low-severity polish items. Full list in Workstream A. Two shipped features are unreachable
(Playlists, Layers). One export-parity gap (BEAM afterglow).

**Market position (from research):** MusicViz's visualizer moats are real — no competitor
combines on-device projectM, 57 GPU scenes, video export, system-audio capture, performance
takes and a photosensitivity limiter. The player half is what reads as incomplete: the
table-stakes list every well-sold player ships (folder browsing, playlists UI, gapless,
ReplayGain, Android Auto, sleep timer, widgets, backup) is mostly missing. Users' loudest
deal-breakers in this category: subscriptions (we're one-time — a moat), clutter (Poweramp's
curse — our 200-param risk), crashes (projectM apps' curse), and battery drain.

---

## Workstream A — Fix every known bug

Ordered by severity; each item names the file, the fix, and the test that pins it.

### P0 — data loss and safety (ship first, in one patch release)

| # | Bug | Fix | Acceptance |
|---|-----|-----|-----------|
| A1 | Filename sanitizer collapses distinct non-ASCII names onto one file — saving "月光" silently destroys "夜曲" (`PresetStore.kt:291`, shared by presets/playlists/palettes) | Percent-encode or append an 8-char content hash of the raw name; one-time migration renames existing files | Two CJK/emoji-named items co-exist; migration test; no existing user file orphaned |
| A2 | `PresetStore.save` uses truncating `writeText` — process death mid-save destroys the only copy (`PresetStore.kt:97`); `TrackLibrary.writeLocked` omits fsync (`TrackLibrary.kt:172`) | Route both through `AtomicWrite.stream` (temp sibling + fsync + rename) | StoreDurabilityTest extended to both stores |
| A3 | `PresetLink.decode` performs unbounded gzip decompression on hostile intent data — gzip bomb OOM (`PresetLink.kt:70`) | Bounded inflate (4 MB cap, fail with a friendly error) | Crafted-bomb test asserts bounded memory + clean rejection |

### P1 — user-visible correctness

| # | Bug | Fix | Acceptance |
|---|-----|-----|-----------|
| A4 | BEAM loses phosphor afterglow in video export — export omits the `isBeam` trail gate the live path has (`VideoExporter.kt:564`) | Mirror the live gate; prefer deriving both from one predicate | Export-parity test over the trail gate set |
| A5 | Take-driven exports allocate FlowField/ripple from the take's END state only — effects toggled on mid-song are missing from the whole render (`VideoExporter.kt:398`) | Scan the entire take timeline for any effect use before allocating | Test: take enabling Flow mid-way exports with Flow |
| A6 | ROOM/INSTRUMENT live-input profiles ship thresholds below the engine's clamps — the profile silently doesn't apply and Settings shows a value the engine isn't running (`LiveInputProfile.kt:70,90` vs `FeatureExtractor.kt:344,361`) | Either widen engine clamps deliberately (with the DSP argument written down) or correct the profiles; align the Settings slider range either way | Profile values round-trip into the running engine unclamped |
| A7 | Lyrics and Queue auto-scroll yank the list back while the user is reading/browsing (`PlayerPanels.kt:174`) | Suspend follow while the user is scrolling; resume after ~5s idle or on tap-to-recenter | Manual: scroll up during a synced track, list stays put |
| A8 | Customize lock chips ~16dp tall vs 48dp Android minimum (`CustomizeTabs.kt:122`) | Minimum 48dp touch area (visual size can stay compact via padding) | Accessibility scanner passes on the Customize panel |
| A9 | Preset/take deletion is single-tap irreversible while Reset confirms (`VisualsHub.kt:309`) | Confirm-or-undo (snackbar undo preferred over dialog) | Deleting then undoing restores the file intact |

### P2 — longevity, device-specific, latent

| # | Bug | Fix |
|---|-----|-----|
| A10 | `LfoEngine.totalPhase` unbounded float accumulator freezes S&H LFO after days of wallpaper uptime (`Lfo.kt:160`) | Wrap like `phases[i]` (S&H needs only the integer transition); pin next to RenderClockWrapTest |
| A11 | Sampler objects unbound only on units 0–3; the composite's blue-noise dither runs on unit 4 and a leaked projectM sampler would silently break dithering (`GlUtil.kt:41`) | Unbind 0–7; comment names the unit-4 consumer |
| A12 | `hyperspace_frag.glsl` samples the half-float melt field through default-lowp samplers — the exact Mali failure the fluid pipeline documents (`hyperspace_frag.glsl:131`) | `precision highp sampler2D;` like every fluid pass |
| A13 | `CrystalBackground` animates a full-screen Canvas forever, ignoring the app's own Reduced-motion setting (`Crystal.kt:629`) | Gate the infinite transition on reduced-motion AND on whether the theme actually shows moving elements; cap the invalidation rate |
| A14 | Crash-report file read on the main thread during first composition (`AppShell.kt:115`) | Move to `LaunchedEffect` + IO dispatcher; cap read size |
| A15 | `exportSceneFactory` is a second hand-maintained scene switch with a silent Nebula fallback (`VisualizerRenderer.kt:1612,1659`) | Have it delegate to `createScene`; extend RendererWiringTest to walk it |
| A16 | `lerpParams` hand-lists 114 of ~200 SceneParams floats; three sliders already snap instead of fading (`VisualizerRenderer.kt:225`) | Reflection-driven lerp with a declared `NOT_FADED` exclusion list, guarded by a test like PresetRoundtripTest |
| A17 | Low-severity batch: StereoField NaN on near-silent channel; MicCapture stop/start zombie-worker race; PlaybackCaptureService MediaProjection replaced without stop; AIFC 'sowt' plays but won't analyze/export (`AiffPcm` vs `AiffExtractor` parity); 62.5 Hz hop-rate assumption biasing live BPM; `PcmRingBuffer.copyNewSince` full-capacity clamp; PerformanceMonitor Int overflow; RippleSim `inkEnabled` post-create crash; BeamScene NaN auto-gain latch; queue rows keyed by index+uri; export dialog loses state on rotation (`remember` → `rememberSaveable`); GLSL editor persisting whole source into saved state; emoji used as icons where the design system mandates vector glyphs; SceneSuggester duplicating id literals; FxCompositor re-implementing `CurlFlowMath.warpDecay`; harmonic_shell atan seam | One PR per subsystem, tests where cheap |

Stability is a market position, not just hygiene: crashes are projectM-family apps' dominant
review complaint, and battery drain is the #1 live-wallpaper killer. Alongside the fixes above:
per-preset crash quarantine for MilkDrop presets, and a visible battery-saver mode (FPS cap,
resolution scale, pause-when-hidden — the engine already has the knobs).

---

## Workstream B — Complete the incomplete features

| # | Feature | State today | Completion |
|---|---------|-------------|-----------|
| B1 | **Music playlists** | Full VM API + Library tab + rename/reorder UI exist; nothing can CREATE one — the empty state instructs a flow that doesn't exist (`LibraryScreen.kt:323`) | Add "Save queue as playlist" in the Queue panel, "New playlist" in the Playlists tab, and "Add to playlist" in track long-press menus. All three call the existing `createMusicPlaylist`/`addTrackToPlaylist`. Follow the settled flow conventions (playlist detail with play/shuffle split button, drag-handle reorder) |
| B2 | **Layers** (two scenes blended) | Fully built render-side (`layerSceneId`/`layerMix`/`layerBlend`, composite branch, VisualSafety integration) but zero writers — invisible to users | 1) Wire FlowField service/readback for the layer scene (`VisualizerRenderer.kt:1086-1226` operates on active scene only — a flow-defined layer like Inkflow currently loses its identity); 2) add the Layers card to Customize (scene picker, blend mode, mix slider); 3) presets carry it |
| B3 | Substyle display names | Home/preset tiles show raw ids ("Hyper_liquid_warp") | Resolve through `VisualStyleCatalog` labels everywhere a scene id is displayed |
| B4 | AIFC little-endian ('sowt') | Plays but can't analyze/export | Teach `AiffPcm` the sowt path `AiffExtractor` already has; parity test |
| B5 | Loop-safe export chip | Looks tappable, silently does nothing without a detected tempo | Disabled visual state + hint text |

---

## Workstream C — Complete the Visual Styles Plan (the Todo list)

The plan document is explicit that profiles are not the deliverable: each of the twenty styles
must be real 3D (camera, depth, geometry or marched volume) coupled to a fluid/wave medium both
ways. The shipped profiles slice stays — it becomes the low-tier fallback the plan's
QualityLadder needs anyway. Build order follows the plan's own dependency order:

**C0 — Phase 0, the foundation (blocking everything):**
- `render/space/`: `DepthStage` (first depth-attached FBO in the app), `ResTarget`
  (sub-resolution render + upscale), `SpaceCamera` (shared projection contract for rasterised
  and marched content), `SpaceMesh`, `VolumeAtlas`, `GpuGrains`, `QualityLadder`.
- The 8 planned GLSL libraries in `GlUtil.INCLUDES` (`lib_sdf`, `lib_shade`, `lib_volume`,
  `lib_film`, `lib_fluidsample`, `lib_tiling`, `lib_legendre`, `lib_modes`).
- `FluidSim.pressureTex` exposure; gated 9-point Laplacian + circular mask in
  `ripple_update_frag.glsl`; `MeltField` per-style configuration (SIM_RES/DYE_RES/scale become
  constructor params).
- `SpaceFoundationTest` + preview-harness `space-drivers.mjs`.
- **Immediate cheap win, before any of the above:** make the preview harness drive every
  `uStyle` branch (today it renders only style 0, so 20 shipped branches have zero frame
  coverage), and add the on-device Adreno measurement the plan says SwiftShader cannot provide —
  the 11-way branch chain in the raymarch inner loop is the exact register-spill pattern the
  plan deleted.

**C1–C5 — the twenty styles, in the plan's phases** (harmonograph + caduceus proofs; chladni_sand
+ polytope theses; the mesh/instancing four; the cheap remainder + second solvers; the expensive
six) — each phase ends at the plan's own measurement gate, with its named math tests
(HarmonographMathTest, PlateResponseTest, PolytopeMathTest, AttractorPathTest, LobeMathTest,
faraday safety gates, rosensweig soak). Reference material for the physically-driven cymatics
upgrades: open Chladni-plate simulations (schroffl/chladni-simulation, ChladniPlate2) as
technique references.

**C6 — plan-ordered corrections that need no foundation (do alongside A/B):** retire the
dish/plate duplicates of Drumhead/Chladni Sand; Bessel-zero mode ranking for the membrane
(`CymaticsMath.kt:139`); fix the stale twin-file comment and the attribution issue in
`CymaticsMath.kt`; decide membrane/vivarium — restore the planned designs or amend the plan to
bless liquid_warp/resonant_wormhole (the plan and the catalog must stop disagreeing silently).

**C7 — scene transitions polish (from user research):** beat-quantized auto-switching and
smooth cross-scene blending are the most-celebrated features in the MilkDrop ecosystem
(projectM shipped preset blending for exactly this demand). The transition catalog exists;
add "switch on the drop" (beat-aligned scene advance) as a playback option.

---

## Workstream D — Refactor where needed

Targeted at the audit's confirmed debt, nothing speculative:

- **D1 — decompose PlayerViewModel (3,863 lines, ~20 domains).** Extract per-domain
  controllers (Queue, Library, Presets, Takes, Export, Lyrics, Equalizer, Intelligence) owned by
  the ViewModel, keeping every public StateFlow signature stable so no Composable changes. The
  comment-enforced construction-order invariant (fields read by the poll loop must be declared
  above it — already crashed the app once) becomes constructor dependency order. Interim: a
  source-level test enforcing the declaration-order rule until the split lands.
- **D2 — split `onDrawFrame` (~400 lines)** into named stage functions (input drain → sim →
  scene → layer → transition → composite) with the existing comments as stage docs.
- **D3 — unify `exportSceneFactory` with `createScene`** (same change as A15).
- **D4 — reflection-guard `lerpParams`** (same change as A16).
- **D5 — split `AppSettingsTab` (442 lines)** into per-section composables.
- **D6 — small dedups:** SceneSuggester uses `SceneIds` constants; FxCompositor calls
  `CurlFlowMath.warpDecay`.

Explicitly NOT refactoring: the shader prelude duplication (tested byte-for-byte, GLSL has no
includes across uniform blocks), the store fleet (each store's divergence is documented and
tested), the pure-math/GL-packing split (it is the codebase's best pattern).

---

## Workstream E — Streamline customization (keep every option)

Poweramp — the category leader — is routinely rejected as "cluttered" and "a learning curve";
that is MusicViz's risk with 57 scenes × 200 params. The research consensus from
customization-heavy tools that stay approachable (Halide, Koala, Serum-class synths, projectM,
Resolume): never delete a control — change how controls are reached.

- **E1 — Macro dials (highest leverage).** 4–8 designer-authored macros per scene family
  (Energy, Density, Refraction, Glow, Chaos…) as large crystal-knob controls on the first
  Customize tab, each mapped with ranges onto multiple raw params (the catalog's bias table
  already encodes per-style importance). Macros write through to real params, so presets stay
  honest. Macros also become the natural LFO and performance-take targets — one macro lane
  instead of ten parameter lanes. Full lists move behind an "Expert" expander, same order as
  today.
- **E2 — Preset-first Visuals hub.** Searchable card grid with live (or cached-clip)
  thumbnails, favourite chips, "Recently used" and "For this track's energy" rows (beat/key
  detection already exists), one "Customize" affordance per card. Parameters never the landing
  surface. (projectM's browser and synth preset UX are the models.)
- **E3 — One search index over Settings AND scene parameters** ("bloom", "sensitivity",
  "fps") deep-linking to the exact control, with a Recent/Pinned group. Android's own settings
  guidance mandates grouping+search at this option count.
- **E4 — Halide-style canvas chrome.** Auto-hide after ~3s; swipe-up reveals a one-row
  quick-strip (macros + randomize + scene switch); long-press a macro to expand its underlying
  params in place; performance-take mode transforms the overlay into record-armed controls.
- **E5 — Randomizer locks that survive randomize AND preset load.** Lock chips exist per
  slider today (A8 fixes their size); extend locking to palette slots and macro groups, add A/B
  compare slots and undo so exploration is never destructive.
- **E6 — XY performance pad** assignable to any two macros (defaults per scene), gestures
  recordable as performance takes — unifying LFOs, takes and touch under one performable
  surface (Kaoss-pad lineage; Resolume practice).
- **E7 — Interaction hygiene** (overlaps A8/A9): 48dp targets, per-slider reset, undo for
  destructive actions, haptics on chip toggles.
- **E8 — "Performance bar" of global post-effects** (bloom, chromatic aberration, color grade)
  as tap-to-toggle chips over the canvas — Vythm's most-marketed feature, and MusicViz already
  has the effects buried in per-scene params.

---

## Workstream F — Finish the music player (parity with well-sold apps)

Grounded in the competitor matrix and user-demand research (full evidence in
`RESEARCH_APPENDIX.md`). Sequenced by how often each item is a purchase decider.

**Table stakes (must — "complete player" definition across Poweramp/Musicolet/AIMP/Oto/Gramophone):**
- **F1 — Folder/filesystem browsing** with per-folder play/shuffle/queue. The most-cited
  must-have in every comparison thread; even free players ship it.
- **F2 — Gapless playback, verified per-format (incl. AIFF) and advertised**; crossfade
  (fade-on-pause first, then dual-player/mixing-processor crossfade — Media3 still has no
  built-in crossfade).
- **F3 — Android Auto** via Media3 `MediaLibraryService` (androidx/media session demos are
  Apache-2.0 and liftable; UAMP is archived). A hard deal-breaker for commuters.
- **F4 — Sleep timer** — minutes AND track-count variants, finish-current-track, fade-out
  (Musicolet's differentiator; cheap).
- **F5 — Home-screen widgets (Glance, 2+ sizes) + verified MediaStyle notification with seek.**
  The crystal design language could make the platform's best-looking widgets.
- **F6 — ReplayGain 2.0** (track+album, R128 for opus) via a custom ExoPlayer AudioProcessor
  (Auxio's pattern — reimplement, don't copy GPL); on-device scanner for untagged files via the
  existing offline analyzer (foobar2000-class differentiator inside a must).
- **F7 — Backup/restore as trust infrastructure:** M3U/M3U8 playlist import/export via SAF
  into user-visible storage (scoped-storage export failures are a named Poweramp complaint),
  plus one-file full backup (settings + playlists + favourites + presets + palettes + EQ).
- **F8 — Library completeness:** album-artist view (compilations), genre/composer/year,
  configurable multi-artist tag separators ("A; B" — the #1 concrete power-user ask),
  natural sort, per-list sort persistence, search on every list, grid/list toggle.
- **F9 — Headset/Bluetooth polish:** resume-on-connect (opt-in), pause-on-disconnect,
  multi-press headset actions, correct AVRCP metadata on car units.

**Strong differentiators (should):**
- **F10 — Cast/TV path.** GL surfaces can't cast directly (Remote Display is dead); the
  realistic pattern is a Cast Web Receiver running a WebGL visualizer (butterchurn is MIT)
  driven by beat/FFT messages from the phone — closing the #1 visualizer wish ("visuals on the
  TV") AND audio-cast parity in one project. A `DreamService` screensaver is the cheap sibling
  (reuses the wallpaper renderer) and the projectM-TV use case users love.
- **F11 — Auto-fetched synced lyrics** via LRCLIB (free, keyless, proven in Namida/Gramophone);
  word-level karaoke rendering later; lyrics *inside the visualizer* would be unique to us.
- **F12 — Listening stats** (play counts, permanent history, Wrapped-style shareable recap —
  HistoryStore already records the data) + smart playlists (most/never played, recently added)
  + favourite/recency-weighted shuffle ("true random" toggle included).
- **F13 — Scrobbling** (Last.fm/ListenBrainz) — cheap goodwill with the exact demographic that
  buys players.
- **F14 — Bulk tag editing** (multi-select) on the existing editor; M4A edge cases.
- **F15 — Social-format export:** vertical 9:16 and 1:1 presets, 4K/60 tier, optional
  text/logo overlay — the Avee/Specterr use case, watermark-free and strictly WYSIWYG (Avee's
  two most-complained failures are watermarks and exports not matching in-app audio).

**Deliberately deferred (could):** multiple named queues, internet radio/podcasts, NAS/Subsonic
sources, hi-res/USB-DAC bit-perfect output, parametric EQ upgrade, WLED/Art-Net room-lighting
sync, MIDI/OSC control, Android TV build, community preset marketplace.

**Moats to protect and market:** one-time purchase (subscription fatigue is the #1 stated
deal-breaker), no ads, minimal permissions/offline-clean privacy stance, system-audio capture
("visualize Spotify" is a decade-old unmet demand — lead the Play listing with it), projectM on
device, video export, live wallpaper, second display, performance takes, photosensitivity
safety (surface it — no competitor markets one).

---

## Workstream G — Design polish (research-informed)

- **G1 — Write the glass-surface spec** and enforce it: 8–16px blur equivalents, max 2–3 glass
  panels per view, 1px light border, dark scrim under any text on glass, playback-critical
  controls always on frosted-toward-opaque surfaces. Ship "Reduce transparency" and "Increase
  contrast" toggles — the Liquid Glass backlash is the cautionary tale (Apple added exactly
  these controls under pressure).
- **G2 — Glass performance budget.** Real-time blur is capped at ONE surface (the active
  sheet); all other glass uses the cheap approximation (gradient + noise + border — which
  `crystalPanel` already is, deliberately); auto-degrade when frame time exceeds budget. A
  visualizer that drops frames for its chrome is self-defeating.
- **G3 — Ambient artwork/scene treatment on Now Playing:** blurred-stretched art as backdrop
  glow with 3-color extraction tinting panels when visuals are minimized; when visuals run
  full-bleed, sample the scene's palette instead — the chrome becomes a prism over the scene
  (crystal identity, literally).
- **G4 — Dark-first OLED surface tokens:** true-black base so the canvas fuses with the bezel
  (and saves OLED power), 4 defined elevation levels for lists/reading surfaces, all 8 mineral
  themes mapped onto the same token system.
- **G5 — Thumb-zone + bottom sheets:** transport/scene-switch/randomize in the bottom third;
  queue, preset pickers, palette pickers and share flows as draggable glass bottom sheets.
  Queue gets the peeking "Up Next" edge (Apple Music's discoverability lesson) with play-next
  vs add-last split and clear-all.
- **G6 — M3 Expressive adoption where it strengthens the brand:** custom faceted-gem Compose
  shapes with shape-morph on press (the play button morphing between gem cuts), spatial springs
  for sheet/panel motion, button group for transport, floating toolbar for canvas quick
  actions. Skip dynamic-color wholesale theming — the 8 mineral themes stay curated.
- **G7 — Signature wavy/waveform seekbar** rendered as a refracting crystal line,
  amplitude-modulated by the live audio level (the analysis exists); scrubbing raises a glass
  tooltip with time + lyric snippet.
- **G8 — Typography discipline:** serif strictly display-only (titles, scene names, headers)
  over sans controls; consider a variable-weight axis subtly driven by audio energy; never
  serif at small sizes over unscrimmed glass.
- **G9 — Prismatic effects as scarce hero moments** (play-button edge refraction, caustic
  sweep on preset-save/export-complete, faceted theme icons) — never wallpaper; kitsch is the
  failure mode.
- **G10 — Vector icon sweep** replacing emoji/dingbat glyphs (audit + skill checklist + the
  design system's own stated discipline).
- **G11 — Value-first onboarding:** first run reaches a crown-jewel scene with real audio in
  under 60 seconds (permission → auto-pick track/demo/mic → hero scene), then a 3–4 stop glass
  coach-mark tour (swipe scenes, tap to smear, Customize, export). Theme picker and library
  indexing defer to later moments.
- **G12 — Curation over toggles for appearance:** the 8 mineral themes are complete looks;
  per-user appearance tweaks stay at 2–3 options in an overflow, not a settings tree
  (Poweramp's sprawl is the cautionary tale).

---

## Workstream H — Licence & compliance (paid-app legal hygiene)

From the open-source research; treat as P0-adjacent since the app charges money:

- **H1 — Shadertoy provenance audit (must).** Shadertoy's default licence is CC BY-NC-SA 3.0 —
  non-commercial. Audit all 22 fragment-shader styles for Shadertoy-derived code; anything
  traceable to a default-licensed shader gets rewritten from first principles or replaced with
  MIT-licensed ISF sources (Vidvox ISF-Files, 200+ shaders, MIT — also a legally-safe quarry
  for NEW styles). Record provenance per shader in `third_party_notices.txt`.
- **H2 — projectM LGPL-2.1 compliance.** Keep `libprojectM-4.so` dynamically linked (it is —
  keep it that way through any build changes), ship the LGPL text + attribution in the
  licences screen, publish any modifications to the library itself.
- **H3 — MilkDrop preset packs.** The packs' "assumed public domain, takedown on request"
  position is tolerable but must be documented: record pack/version shipped, keep per-preset
  author attribution, honor removal requests.
- **H4 — Fluid sim attribution.** If the fluid scene derives from PavelDoGreat's
  WebGL-Fluid-Simulation (MIT), the copyright notice must appear in the licences screen — MIT
  requires it. While there: mine the repo's dye-dissipation curves and sunrays pass (cheap
  wins, legally clean).
- **H5 — GPL hygiene rule, written down:** Auxio/RetroMusic/Gramophone/Metro/Vinyl/Odyssey are
  GPL-3.0, Symphony/audioMotion AGPL — ideas only, zero code reuse, ever. Apache-2.0 sources
  (androidx/media demos, Amplituda) are liftable with attribution.

---

## Priority order (revised 2026-08-04, per owner direction)

1. **The visualizer is priority #1.** Before any player work: make the cymatics and
   hyperspace substyles genuinely unique (research-driven — real cymatics phenomena per
   cymatics style, DMT/psychedelic form-constant geometry per hyperspace style), and land
   the richer per-theme crystal textures in the UI. The twenty-style 3D rebuild (Workstream C)
   remains the v2.0 horizon; substyle uniqueness is the near-term deliverable on the shipped
   profile architecture.
2. **Then the music player** — planned first, not built first: a Poweramp feature inventory
   with a reasoned have/need/skip verdict per feature. **No online/streaming/cloud music
   functions** (owner constraint): streaming, cloud sync, internet radio, and network sources
   are out of scope regardless of competitor parity.
3. Everything else keeps its Workstream ordering below.

## Execution retrospective (waves 1-3)

What worked, kept as rules: disjoint file ownership derived from grep evidence before
launching parallel agents (25 agents, zero conflicts); agents forced to read neighbours and
imitate style (three waves, zero first-compile failures); adversarial verification before any
fix was written; one pinning test per fix. What failed, and the correction now in force:
run ktlintFormat as its own step before any check; file ownership must include the tests that
pin the owned files (a visible-string change broke an unowned smoke test); agents whose
changes regenerate an artifact must own that artifact (PARAM_MATRIX.md cost an extra cycle);
never draft research-dependent conclusions before the research lands.

## Release sequencing

| Release | Contents | Gate |
|---------|----------|------|
| **v1.7.1** (patch, ~days) | A1–A3 (data loss/safety), A4–A5 (export correctness), B3, B5, H2/H4 (licence screen) | All new tests green; no schema changes |
| **v1.8** (~2–3 weeks) | Rest of A incl. battery-saver mode; B1 playlists; B2 Layers; H1 shader audit; E1 macros + E3 search + E7 hygiene; F4 sleep timer; G1/G2 glass spec + G10 icons | Accessibility scan clean; store durability suite extended; provenance table complete |
| **v1.9** (player completeness) | F1–F3, F5–F9; F11 lyrics; F12 stats; D1 ViewModel decomposition behind stable flows; E2/E4/E5 customization; G3–G5, G7, G11 | Parity demo: folder nav, gapless A/B, Auto session, backup round-trip on a clean device |
| **v2.0** (the twenty styles) | C0 foundation → C1–C5 by plan phases; C7 transitions; E6/E8; F10 Cast/TV; F13–F15; G6/G8/G9/G12 | The plan's own per-phase measurement gates on real Adreno + Mali hardware |

Rule of thumb throughout: every release keeps the moats loud (one-time purchase, no ads,
offline-clean, safety limiter) and never ships a feature without its test.

---

## Sources

Full annotated evidence with per-item URLs: `docs/RESEARCH_APPENDIX.md`. Highlights:
competitor matrices (Musicolet, Poweramp, Gramophone, Symfonium feature pages; How-To Geek 2026
player test), user demand (r/androidapps feature-request and player threads, Poweramp forum,
projectM GitHub issues #169/#727/#900, Muviz Edge), open source (Auxio, RetroMusicPlayer,
Gramophone, Namida, androidx/media, butterchurn, WebGL-Fluid-Simulation, Vidvox ISF-Files,
Amplituda, LRCLIB, Shadertoy terms), design (Material 3 Expressive docs, Liquid Glass
accessibility coverage, Muzli 2026 patterns, Halide case studies, Pinterest/Dribbble/Mobbin
music-player pattern hubs, glassmorphism performance guidance).
