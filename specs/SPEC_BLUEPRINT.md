# Synesthesia — Spec Blueprint v1.2
Status: data-safety gate PASSED · architecture review pending (M2) · Owner: tessie1993 · Date: 2026-08-25
Source: owner interview (3 rounds) + research fleet (Poweramp inventory, export-gap scan, 3D style/engine survey)

---

## 0. Product thesis

**Synesthesia** is an Android music visualizer where listening and creating are the same act:
a full local music player whose every screen breathes with a GPU audio-reactive engine, and a
deterministic offline renderer that turns any track into export-ready video up to 4K.
Rebuild, not refactor — legacy code is a mine and an inspiration, not a foundation.

- **Audience**: listeners (eye-candy while music plays) AND creators (social/Canvas exports) AND VJs (live). No primary segment exclusion.
- **Identity**: high-end glowy crystal-glass dark UI. Premium feel; never childish.
- **Positioning vs market**: Poweramp's audio depth without its settings maze; CapCut's ease without flaky beat-sync; Avee's template economy without template-bound visuals; Specterr/Videobolt quality without cloud renders.

## 1. The five pillars

| # | Pillar | One-line spec |
|---|--------|---------------|
| P1 | **Audio-Reactive Core** | One deterministic engine drives everything live and offline |
| P2 | **Visualizer** | Style families: prettified classics + new 3D + MilkDrop compat |
| P3 | **Player** | Full modern Media3 player, rebuilt clean |
| P4 | **Export & Edit Suite** | Creator essentials + 6 unique differentiators |
| P5 | **UI** | Immersive crystal-glass shell over all of it |

Dependency law: P1 knows nothing about P3/P4/P5. P2 consumes P1. P4 re-drives P1+P2 offline. P5 orchestrates.

## 2. P1 — Audio-Reactive Core

### 2.1 Determinism contract (the product's soul)
One scene graph, one parameter set, one render function:

```
render(clock, audioFrame, params) -> frame
```

- **Live mode**: clock = media clock, frames dropped under load, resolution = surface.
- **Offline mode**: clock = frame index × frame duration; NO drops; identical math; resolution free (720p→4K).
**Parity contract (two tiers, both testable):**
- **Tier 1 — offline↔offline EXACT**: same (track bytes, recipe, schema version, seed) rendered twice offline ⇒ pixel-identical frames. This is the acceptance gate (CI-run on software/GL harness); any style failing it is broken by definition.
- **Tier 2 — live↔offline STRUCTURAL**: live playback approximates the offline frame sequence (same scene graph, same param timeline; frames may drop/scale under load). Verified by sampled perceptual-hash distance ≤ threshold at matched beats, not byte equality.
- Harness owner: render-engine module's own test source set. Metric: pHash Hamming distance, threshold pinned per family in code.
- All randomness seeded; the seed is part of the recipe record → exports reproducible **for a given schema version** (see §2.5).

### 2.2 Audio pipeline
- Sources → single-writer PCM ring (48 kHz mono analysis bus):
  player tap (Media3 audio processor), microphone, other-apps capture (API 29 playback capture w/ MediaProjection foreground service + prominent disclosure), file decoder (offline path reads file directly — no realtime dependency).
- Analysis: FFT (2048) → log-spaced bands (~64) → beat/onset tracker (spectral flux + tempo grid) → loudness/RMS curve cache per track (reused by seek-bar waveform + export normalization).
- Offline analysis cache keyed by (content hash, params version).

### 2.3 Modulation system (modular rebuild of legacy LFO/ADSR)
Every stylable parameter is addressable: `paramId → value`:
- Static value · envelope (ADSR) · LFO (rate clamped ≤ photosensitivity limits) · beat-gated impulse · band follower (FFT band → param, attack/release shaped)
- Modulation routes are data (JSON), stackable per parameter, shareable inside recipes/presets.
- Safety architecture (D-SAFE-1): a HARD, NON-DEFEATABLE WCAG flash ceiling (≤3 flashes/s equivalent) is applied LAST in every modulation chain — always on, all tiers, all styles, not exposed as a setting. Optional comfort controls (flash depth, reduced-motion) operate BELOW the ceiling, default OFF, off = exact no-op.

### 2.5 Determinism & data-model versioning (architecture prerequisite)
Four JSON artifact kinds exist — **recipes**, **presets**, **themes**, **modulation routes**. Rules:
- One canonical schema registry module owns all four schemas; every artifact carries `schemaVersion`.
- Recipes REFERENCE presets/themes by stable id + content hash; they never inline them.
- Migration rule: read forward-compatible (unknown keys ignored); breaking schema change bumps major and ships a migrator; old artifacts never silently reinterpret.
- Export records the exact schemaVersion used — that is what makes an old export reproducible.

### 2.4 Adaptive performance tiers (default ON)
Auto-tier selects from measured frame time: render scale (0.5–1.0), sim grid res, particle budget, raymarch step count, half-res offscreen passes. Manual override Low/Med/High/**Ultra-flagship**. Battery-saver caps tier. Export ignores tiers (uses recipe's locked quality).

**Ultra-flagship tier (owner decision):** Settings exposes a "High-end shaders" switch, visible ONLY on devices whose GL probe (GlTier/CapabilityCache port) certifies flagship-class GPU + thermals. When ON, styles may unlock their top-end paths (full-res raymarch step counts, 4K-class sim grids, compute-assisted scenes, heavier bloom chains) — the very ceiling of what a gaming phone sustains. Every style ships MOBILE-SAFE at default tiers regardless; Ultra paths are additive quality, never required to render the style. Styles declare per-path cost class in their manifest entry; picker badges them (⚡ mobile-safe / 🔥 flagship) and hides/badges locked ones per device+setting.

## 3. P2 — Visualizer styles

### 3.1 Families at v1
| Family | Origin | v1 work |
|---|--------|---------|
| Shader scenes (fullscreen GLSL) | legacy, prettify | re-grade palettes/bloom; kill "basic" looks |
| Fluid / Curl Flow / Water | legacy GPU sim | keep sims; modernize look chain |
| Cymatics | legacy | keep, re-grade |
| MilkDrop compat | libprojectM 4.2 (.so, pinned) | full .milk load; FBO hand-off (no fb0 copy) |
| **NEW: 3D set** | this spec §3.2 | five flagship concepts |

Particles-as-generic-family: CUT as separate family; particle techniques absorbed inside styles (e.g., Bass Vortex uses instanced points internally).

### 3.2 New 3D styles (ranked wow-per-cost; research-backed)
1. **Neon Terrain Flyover** — ridged-FBM glowing terrain; bass=speed, sub=heave, treble=rim sparkle. Pure GLSL displaced grid.
2. **Fractal Warp Tunnel** — Menger/kaleido tunnel; beat=fold count, snare=flash, energy=zoom. Pure GLSL raymarch @half-res.
3. **Bass Vortex Galaxy** — ~80k instanced stars around core; kicks=emission bursts. bgfx-assisted points/compute.
4. **Spectral Ocean** — Seascape-style ocean whose swell IS the FFT; transients=lightning. Pure GLSL.
5. **Cymatic Cathedral** — gyroid SDF temple, walls ripple with waveform texture, kaleido mirrors pulse per bar. Hybrid GLSL + C++ camera choreography.

### 3.3 Native C++ policy
GPU does shader math (GLSL stays primary). C++ earns its place ONLY for: mesh/scene plumbing via **bgfx** (BSD-2, GLES+future Vulkan), preset parsing hot paths, camera choreography (style #5). No vanity rewrites of working GL.

### 3.4 Style framework requirements
Every style exposes: typed param schema (auto-generated Customize panel), modulation targets (§2.3), palette hooks, randomize-with-lock chips, presets (JSON) + **recipes** (§4.4), safety clamps, tier hints (what degrades first).
**Catalogue breadth goal (owner decision): v1 ships a WIDE selection — target ≥25 distinct looks across families** (shader scenes, fluid trio, cymatics substyles, MilkDrop starter pack, five 3D flagships), each re-graded to the premium bar; breadth beats three perfect styles. Deep-dive catalogue source: specs/STYLE_CATALOGUE.md (research agent output, feeds M6).

## 4. P4 — Export & Edit Suite

### 4.1 Pipeline
Offline analyzer → deterministic re-render (P1 offline mode) into H.264 (HEVC opt-in) + source-audio mux via Media3 Transformer; trim-only edits stay lossless container rewrites. Alpha lane bypasses muxer (GL frames → WebP-anim/VP9-alpha/GIF encoders).

### 4.2 Creator essentials
Trim (filmstrip) · grade (bright/contrast/sat/hue/vignette) · speed (with pitch option) · reframe (crop not pillarbox) · captions/text overlay · mute/audio-swap.

### 4.3 Six differentiators (research gap-list)
1. **Loop-perfect Canvas exporter** — spec-lock preset (≤8s, 9:16, ~10MB, silent). Loop integrity via **Rebound only** (forward-reverse render, Spotify-endorsed); plain end-crossfade offered as a separate, non-"loop-perfect" option so the claim stays honest.
2. **FFT-baked audio-reactive text/effects** — caption scale/glow bound to band envelopes at render time.
3. **Trustworthy beat-synced auto-cut** — spectral-flux onset grid drives cuts/text pops (CapCut's weakness).
4. **One-tap loudness-normalized export** — −14 LUFS streaming target via limiter AudioProcessor.
5. **Alpha/loop formats** — animated WebP, VP9-alpha WebM, GIF.
6. **Recipes** — save grade+scene+modulation+caption style as one JSON; re-render against any track/ratio in one tap.

### 4.4 Entitlement × export matrix (single canonical table — §7 references this)
| Capability | Free | Premium |
|---|---|---|
| Export duration | ≤ 3 min | unlimited |
| Quality ladder | rung 1 of 5 = **720p30 H.264 baseline bitrate** | full ladder → 4K60 |
| FPS cap | ≤ 60 | device max |
| Alpha lane (WebP/VP9α/GIF) | — | ✓ |
| Recipes save/share | ✓ (free forever) | ✓ |
| Styles at export time | the 3 free styles + rotating preview style | all |
| Watermark on exports | **YES** — small corner mark (DECISION D-1, default yes; owner may veto before M8) | none |
Visual-safety: the hard WCAG ceiling (§2.3 D-SAFE-1) applies to ALL tiers unconditionally — monetization never touches photosafety; comfort controls below it are cosmetic preferences.

### 4.5 Offline render budgets & lifecycle
- Time budget model per (resolution tier × SoC class {flagship/mid/entry}): pre-flight estimate shown BEFORE render starts; >15 min requires explicit confirm; >45 min suggests lower tier.
- Cancellation always available; **resume** via chunked segment cache (renders in N-second segments to app storage, stitched losslessly at finalize — mp4parser-style sample-copy where container allows).
- Long renders run under foreground service `mediaProcessing` type; doze-safe; battery-undone warning <15%.
- Encoder strategy: HW H.264/HEVC via Media3; **VP9-alpha & GIF/WebP via software encoders bundled from NDK-pinned sources** (libvpx et al.) — hardware VP9 encode does not exist on phones; cost accepted and budgeted above.

## 5. P3 — Player (rebuilt clean; zero legacy player code)

Base: Media3/ExoPlayer + MediaSessionService (background play), MediaStore library + SAF folder roots.

| Block | Features |
|---|---|
| Library core | tracks/albums/artists/genres, folders view, queue edit (jump/pull/remove), favorites, playlists, multi-term search |
| Audio controls | parametric EQ (band types LP/HP/shelf/peak, presets **per output device**), bass/treble, speed **and pitch**, gapless, crossfade (0–6s, pause/resume/skip fades), ReplayGain, skip silence, preamp |
| Comfort set | sleep timer (let-track-finish), A-B repeat painted on waveform seek bar, pause-on-unplug, auto-resume last track |
| Poweramp-inspired advanced | direct-volume path where possible, .lrc timed lyrics over the visualizer, m3u/pls import/export, cue-sheet read, smart playlists (rules-based — Poweramp's top complaint gap), native scrobble hook |
| Explicitly skipped | skins marketplace, DSD/exotic formats, 64-band EQ depth, Chromecast |

Loudness-curve waveform seek bar (from analysis cache) is player+visualizer shared surface.

## 6. P5 — UI

- Design language: **immersive crystal glass** — per-theme mineral texture packs (legacy webp packs mined as references) composited WITH glass blur/glow layers; high-end, never childish. Theme = {mineral texture set, derived color scheme, sound pack}.
- New theme packs required → asset generation pipeline: procedural-first (deterministic generator producing WebP/shader textures, reproducible in-build). AI image-generation assist is stretch, not dependency (tooling risk logged).
- IA: bottom nav — Home (resume hero + live spectrum + shelves) · Library · Visuals (styles/customize hub) · Studio (export/edit) · Settings. Now-Playing = full-screen over live canvas (player face: wave seekbar, lyrics, queue).
- Customization surfaces: per-style modular panels (schema-driven, not hand-built screens).
- Accessibility/photosensitivity: hard WCAG flash ceiling ALWAYS ON by construction (§2.3 D-SAFE-1); optional comfort controls default OFF with first-run prompt; reduced-motion independent switch.
- Light theme exists but dark-glass is the identity.

## 7. Monetization port (subscription, single premium tier)

Free (permanent): full player · 3 signature styles + randomizer · standard-quality live visuals · export ≤3 min, lowest quality, ≤60fps.
Premium (sub monthly/yearly): ALL styles incl. new 3D + future packs · export to 4K/full quality/no time cap · alpha lane · recipes cloud-free sharing (file export).
Paywall UX (D-SAFE-2): unlock sheet's 3-second timer gates ONLY the purchase-button enablement; the dismiss/close control is NEVER delayed, obscured, or penalized. Trigger surfaces = (a) app boot/reopen unlock sheet, (b) contextual feature-gate nudges shown only AT a gated feature's touchpoint, max one per session each, (c) evergreen Settings entry. Unlock-sheet cadence capped at 1 per 4 hours and 1 per session (numbers are law, tunable only downward); never mid-playback, never during export. Play's own transactional message sheet (`showInAppMessages`) is invoked at launch only — a platform-owned surface, never triggered by us mid-playback/export.
Free-tier 3D sampling: a **rotating preview style** (one premium style free each week, live use allowed, export locked) so free users can taste depth — answers "randomizer over 3 styles ≠ customization" loophole.
Known accepted risk (documented): client-only entitlement means a refund revokes at next `queryPurchasesAsync` (session-length premium leak ≤ one session) and sideloaded APKs can forge grants — serverless v1 stance, revisit post-launch.
Architecture (port seams, built day-1): `EntitlementRepository` (DataStore-cached, queryPurchasesAsync on resume) ← `PurchasePort` interface ← **PBL 9.x-only impl for v1** — D-SAFE-3 RESOLUTION: RevenueCat (or any validation SDK) embeds INTERNET permission and transmits identifiers/purchase tokens off-device, contradicting the no-network product law; RC is therefore EXCLUDED from v1 and reconsidered only as a formal owner decision to amend the network law (with data-safety form + privacy policy updates). v1 stays serverless client-side per §7's own design. PBL rules: acknowledge ≤3 days after verification, PENDING never grants, grace/account-hold read from query results + `DebugPurchasePort` stub simulating grant/pending/expiry. AdPolicy hooks reserved, ads NOT in v1 plan.

## 8. Platform, toolchain, distribution

| Item | Pin | Note |
|---|---|---|
| minSdk | **29** | playback capture era; drops ~5% legacy devices |
| compile/target SDK | **37** | ahead of Play floor (36 until Aug 31 2026) |
| JDK | **26** (Temurin) | bytecode target conservative (17) |
| AGP / Gradle / Kotlin | 9.3.x / 9.7.x / 2.4.10 (+KSP paired `2.4.10-x`) | new DSL only; AGP-10-proof |
| NDK | **r29 pinned** for native builds | reproducibility exception to always-latest law |
| Native blobs | libprojectM pinned upstream commit + SHA256SUMS provenance | 16KB page gates as Gradle tasks |
| Billing | Play Billing **9.x** | mandatory ≥8 Aug 31 2026 |
| Workflows | zero version literals; catalog+wrapper are only pins; drift job opens auto-PRs; actions majors via Renovate/Dependabot | rot-proofing |
| Distribution | **silent build** (internal APKs) until Play-ready; then closed testing (owner: recruit 20 testers + developer verification BEFORE Sept 2026) | owner-action items flagged |

Play-readiness checklist (living): 16KB pages verified across ALL shipped .so · media-projection FGS type + in-context disclosure · RECORD_AUDIO prominent disclosure · data-safety form coherence ("processed locally, never transmitted" vs no-network permission) · LGPL-2.1 attribution for projectM · privacy policy rewrite for Synesthesia branding.

## 9. Legacy codebase verdicts (preliminary — deep comparison task pending)

| Asset | Verdict |
|---|---|
| engine/audio-core (analysis math) | MINE (port algorithms + tests-as-spec) |
| GPU fluid/water sims + shaders (82 GLSL) | MINE as inspiration/reference renders; re-grade |
| projectM JNI bridge C + committed .so blobs | REUSE (bridge source; blobs re-provenanced under r29 if rebuilt) |
| Theme texture packs (960 webp) + sound packs | REFERENCE for new generated packs |
| Player/playback code | REBUILD (declared broken by owner) |
| UI Compose code | REBUILD (new design language) |
| Export pipeline | PORT concepts, REBUILD on current Media3 patterns |
| billing/{AdPolicy,Entitlement} | ABSORB into new entitlement port design |
| Deleted ~900-test suite | MINE as behavioral spec for ported algorithms (characterization source), not restored wholesale |

## 10. Open questions
- Q-1 ~~watermark~~ **RESOLVED D-1: watermark YES on free** (§4.4) — owner veto window open until M8.
- Q-2 Android Auto / widgets in v1 or defer? *(default: defer)*
- Q-3 Recipe sharing v1: plain file export/import only? *(default: yes)*
- Q-4 Streaming honesty: app-capture works ONLY for capture-friendly apps (YouTube, podcasts, games); **Spotify and most streamers block it by platform design** — all copy/UX must say so plainly. Mic is offered as the fallback path.
- Q-5 GLES feature floor decision: app floor = ES 3.0; per-style gates above it (MilkDrop needs 3.2; compute-based styles need 3.1 with non-compute fallbacks). Styles declare requirements in their manifest entry.
- Q-6 First internal milestone shape: engine-first demo APK vs player-first — decided at M1b strategy output.

## 12. Cross-cutting policies
- **Error & degradation UX**: GPU context loss → auto scene rebuild + toast; corrupt .milk → style marked unavailable with reason in picker; MediaProjection revoked → capture stops + settings card explains; disk-full mid-export → segment cache flushed, partial kept with ".partial" suffix + retry.
- **Permission flow order**: first-run: none required → on first mic/capture use: in-context prominent disclosure → OS prompt. Projection consent per session (platform law). No permission asked before it has a visible feature behind it.
- **Telemetry stance**: NO network permission ⇒ no crash reporting SDK, period. Silent build ships with a LOCAL crash ring buffer (last 20 traces) the owner can pull from their own device via share sheet. Play-native ANR/vitals coverage begins at closed testing. Traces are SANITIZED AT WRITE TIME: file paths reduced to hashes/relative form; track titles/artists never included; share-sheet export re-sanitizes.
- **i18n**: English-first; all strings via resources from day 1 (Compose lint enforced); layouts RTL-safe; locales_config shipped. New languages post-Play.
- **Backup/restore**: Android auto-backup rules EXCLUDE entitlement tokens + analysis cache + **the crash ring buffer** (D-SAFE-4); include presets/recipes/themes/playlists (user work is sacred).
- **Normative disclosure copy (D-SAFE-5)** — required verbatim elements in BOTH mic and capture flows, shown immediately BEFORE the OS prompt, never buried in settings:
  1. WHAT: "Synesthesia will listen to {your microphone | audio from other apps}."
  2. WHY: "…to create live visuals synced to sound."
  3. PROMISE: "Audio is processed on this device only — never stored or transmitted."
  4. ESCAPE: "You can decline and everything else keeps working."
  Projection additionally: per-session system consent; foreground service of type mediaProjection starts ONLY after consent (Android 14+ requirement); manifest carries FOREGROUND_SERVICE_MEDIA_PROJECTION.

---

*Compaction protocol: after any context compaction, re-read REBUILD_PLAN.md §Mission, this file, and the progress table; continue at first unchecked item.*
