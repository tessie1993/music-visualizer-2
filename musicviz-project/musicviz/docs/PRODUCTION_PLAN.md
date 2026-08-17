# MusicViz Production Plan

The route from the current build to a Play-ready product: a music player, a
visualizer, and a video creation suite that are each competitive on their own.

This plan is the execution authority for the production programme. It does not
replace [`visualizer-v2/MASTER_PLAN.md`](visualizer-v2/MASTER_PLAN.md), which
governs the engine overhaul; where they overlap, the engine plan wins on engine
questions and this one wins on product scope and sequencing.

## 0. What already exists

Established by reading the source, not by assumption. ~50k lines of Kotlin
across 176 files.

| Area | State |
|---|---|
| Playback | Single shared ExoPlayer, Media3 `MediaSession` foreground service, audio focus, gapless, sleep timer, A-B loop, speed/pitch, device EQ |
| Library | MediaStore + SAF, five tabs, search, playlists with drag reorder, atomic-write stores with corruption quarantine |
| Visuals | GL ES 3.0, ~58 styles across 7 rendering technologies, ~140 parameters, 3 LFOs + 2 ADSRs, 21 palettes, 128 transitions, live GLSL editor, projectM/MilkDrop |
| Analysis | Rebuilt reactive engine (`engine/audio-core`): adaptive normalization, SuperFlux onsets, comb-filter tempo, beat grid |
| Export | Deterministic offline renderer with full live parity, 6 aspect ratios, loop-safe bar trim, takes as parameter automation |
| Studio | Clip library, trim/grade/speed/rotate/crop/caption via Media3 Transformer |
| Safety | WCAG-grounded photosensitivity engine, export-consistent |

Build baseline: `compileSdk`/`targetSdk` 36, `minSdk` 26, AGP 8.13.2, Kotlin
2.2.0, Media3 1.10.0, Compose. 1,262 unit tests green.

**The Play target-API deadline is already met.** New apps and updates must
target API 36 from 31 August 2026; this app targets 36 today. That removes what
would otherwise be the most urgent item on this list.

## 1. What is actually wrong

From [`quality/PRODUCT_REVIEW.md`](quality/PRODUCT_REVIEW.md) (six-reviewer
fresh-eyes critique) and [`quality/GAUNTLET_BACKLOG.md`](quality/GAUNTLET_BACKLOG.md)
(architecture survey). Both predate this programme and neither has been worked
through.

The pattern across all three product areas is the same: **the expensive
engineering is done and the last mile to user value is missing.** Listening
history is written to disk and never displayed. Favourites are write-only.
Artwork is decoded for the app's own UI but never attached to the media session.
Analysis computes BPM, beats and sections that the editor ignores. This is
mostly UI over paid-for plumbing, which is why the plan is achievable.

### Ship blockers

| # | Finding | Why it blocks |
|---|---|---|
| B1 | 9 Hz strobe at 85% depth reachable by default; safe visuals ships **off**; no first-run disclosure | Photosensitive-seizure exposure with no consent. The code documents the hazard itself. |
| B2 | No playback error handling anywhere — zero `onPlayerError` in main source | A deleted file or stale SAF grant stops playback silently, mid-queue, at night |
| B3 | Queue and position do not survive process death; resume rebuilds one track at 0:00 | The defining baseline of every competitor |
| B4 | No artwork on lock screen / notification / watch | The OS surface users see most looks broken |
| B5 | Seek bar has zero accessibility semantics | TalkBack and switch users cannot seek, anywhere |
| B6 | Permanent permission denial dead-ends with no settings redirect | Button silently does nothing forever |
| B7 | Long exports die when the app backgrounds; `onCleared` cancels them by design | The rage-inducing failure of the flagship creator feature |
| B8 | English hardcoded in Kotlin at hundreds of call sites; 4 strings in `strings.xml` | Not shippable outside English markets; blocks Play featuring |

### Product completeness

Export/Studio: no time-range export (render the whole song to get 15 seconds),
no preview anywhere, no ETA, no clip management, takes are not anchored to track
position so every automation move lands at the wrong musical moment.

Player: no Favourites/Recently-Played/Most-Played surfaces, no library sort or
fast-scroll, shuffle-all shuffles history rather than the library, play counts
inflate on skips and repeat-one, embedded lyrics read the songwriter tag.

System: no widget, no Android Auto browse tree, no quick-settings tiles, no
adaptive layouts, no haptics.

### Architecture debt

`PlayerViewModel` is 4,042 lines across ~15 domains with 15 inline
collaborators and no DI. `VisualizerRenderer` is 1,690 lines with a 408-line
`onDrawFrame` and 22 cross-thread `@Volatile` fields. `SceneParams` is a
169-field god class referenced in 40 files. These are the reason parallel agent
work would collide, so they are sequenced before the features that touch them.

## 2. Module ownership

Agents own modules, not features. An agent may only edit files inside its
module; anything crossing a boundary goes through a contract below. This is what
prevents overlapping edits and duplicated work.

| ID | Module | Paths owned | Owns |
|---|---|---|---|
| **M1** | Playback | `playback/`, `audio/` (non-DSP), `PlaybackService` | Player lifetime, session, queue, focus, errors, resume |
| **M2** | Data | `data/` | Stores, persistence, migrations, prefs, history/favourites |
| **M3** | Player UI | `ui/Player*`, `ui/Library*`, `ui/Search*` | Listening surfaces |
| **M4** | Visual engine | `render/`, `engine/visual-core`, `engine/gl`, `engine/scenes` | Renderer, scenes, params, GL |
| **M5** | Audio engine | `engine/audio-core`, `engine/audio-android`, `analysis/` | DSP, features, analysis cache |
| **M6** | Creation | `export/`, `ui/Studio*`, `ui/Export*` | Render pipeline, editor, clip library |
| **M7** | Platform | `wallpaper/`, manifest, widget, Auto, tiles | System integration surfaces |
| **M8** | Shell | `ui/` shared kit, theme, nav, `res/` | Design system, a11y, localization, adaptive layout |

### Shared contracts

Changing one of these is a contract change: it requires updating the contract
doc and every consumer in the same commit, and no agent may change one
unilaterally.

1. **`AudioFeatures`** (M5 → M4, M6) — the per-frame analysis ABI. Documented in
   [`visualizer-v2/AUDIO_FEATURE_ABI.md`](visualizer-v2/AUDIO_FEATURE_ABI.md).
2. **`SceneParams`** (M4 → M6, M8) — the visual parameter surface. Append-only
   for palette/enum tables so saved presets survive.
3. **Playback session** (M1 → M3, M7) — `MediaController` surface. UI never
   touches the player directly.
4. **Store interfaces** (M2 → all) — `Flow`-exposed repositories, never snapshots.
5. **`ExportRequest` / `ClipEdit`** (M6 → M3, M8) — what a render is, so the
   entry points can multiply without the pipeline changing.
6. **String resources** (M8 → all) — once extraction starts, no new hardcoded
   user-facing English anywhere.

## 3. Sequencing

Rounds are ordered so that architecture work lands before the features built on
top of it, and so that no two concurrent agents need the same file.

### R0 — Contracts and seams (blocking, mostly sequential)

Nothing else can parallelise safely until `PlayerViewModel` is decomposed.

- Extract domain controllers out of `PlayerViewModel` behind interfaces: queue,
  library, favourites/history, visuals, export, sleep/AB. Constructor injection.
- `VisualizerRenderer`: command/state split, remove cross-thread `@Volatile`
  sprawl.
- Store base + repository interfaces exposed as `Flow` (M2).
- String-resource extraction harness + lint rule banning new hardcoded strings.

### R1 — Ship blockers — **DONE except B8**

| # | Outcome |
|---|---|
| B1 | **Corrected, then done.** Safe-by-default was already fixed before this programme — `VisualSafetyChoice.UNKNOWN` resolves to `SAFE_DEFAULTS`. What was missing was the asking, and it cut both ways: nobody was warned, and nobody who wanted full effects knew they were being held back. A first-run consent screen now gates the app, with no live preview, because demonstrating a 9 Hz flash to someone deciding about 9 Hz flashes is the harm itself. |
| B2 | Done. `onPlayerError` reports the failure and skips past one dead file; a dead *source* stops after three failures in a row rather than burning the queue. |
| B3 | Done. `SessionStore` persists queue, index and position; both auto-resume and media-session resumption restore them. |
| B4 | Done. Artwork reaches the lock screen via a custom `BitmapLoader` — the default one decodes `artworkUri` and is handed an MP3. |
| B5 | Done. Seek bar has `progressBarRangeInfo` + `setProgress`; `formatClock` gained hours. |
| B6 | Done. Permanent permission denial offers a settings route. |
| B7 | Done. Renders survive backgrounding: process-scoped coroutine plus a `mediaProcessing` foreground service, and `onCleared` no longer kills them. |
| B8 | **Outstanding.** Localization is untouched — still hundreds of hardcoded English strings. |

Suite went 1,262 → 1,325 tests, all green, with ktlint and lint clean throughout.

- **M8**: first-run safety consent as the boot intro's final beat, with live
  side-by-side preview; safe visuals defaults on until answered. Seek-bar
  semantics. Permission-denial settings redirect.
- **M1**: `onPlayerError` → user-visible surface + auto-skip; full session
  restore (queue + index + position) fed to both auto-resume and
  `onPlaybackResumption`; artwork on `MediaItem`.
- **M6**: export foreground service using Android 15's `mediaProcessing` FGS
  type (6 h/24 h budget, `onTimeout` → `stopSelf`), progress + completion
  notification, survives backgrounding.
- **M8**: string extraction, top-5 Play locales for shell chrome.

### R2 — Creation suite (M6, the goal's centre of gravity) — **in progress**

Done: time-range export; takes anchored to track position; clip delete, rename
and storage totals; render ETA in both the notification and the dialog.

Outstanding: `CompositionPlayer` live preview, mid-render scene switches for
takes, beat-snapped trim, HEVC, aspect-crop preview, export queue.


- **Time-range export.** Choose start–end; stop rendering whole songs to get
  15 seconds.
- **Live preview via `CompositionPlayer`** (Media3 1.9.0+, already on 1.10.0):
  the same `Composition` object drives preview and export, so trim/grade/speed/
  caption preview in real time instead of being applied blind.
- **Anchor takes to track position** and teach the exporter to switch scenes
  mid-render. This converts takes from a mistimed single-scene approximation
  into the headline "re-render your VJ set at 4K" feature.
- **Clip management**: delete, rename, storage totals, custom naming.
- **Beat-snapped trim** using the analysis timeline the app already computes.
- ETA, HEVC option, aspect-crop preview, export queue.

### R3 — Listening completeness (M2, M3)

Favourites/Recently-Played/Most-Played surfaces over data already on disk;
library sort + alphabet fast-scroll; true shuffle-all; play-count thresholds;
genre tab; M3U import/export; go-to-album/artist; skip ±10s; per-track resume
bookmarks.

### R4 — System integration (M7)

Widget, Android Auto `MediaLibraryService` browse tree, quick-settings tiles,
app shortcuts, wallpaper battery controls + `onComputeColors`, moving live
analysis into the playback layer so the wallpaper reacts with the app swiped
away.

### R5 — Polish and gates (M8, all)

Adaptive layouts (window size classes), haptics, predictive back, OS
reduce-motion, 48dp touch targets, full localization, detekt + coverage gates,
Play Data Safety declaration, store listing assets.

## 4. Verification

Every round runs four gates before commit: `:app:detekt`,
`:app:testDebugUnitTest`, `ktlintCheck` and `lintDebug`. Detekt was missed for
several rounds and CI caught it — it is part of the local loop now.

### What can and cannot be verified here

This container has no emulator package, no `/dev/kvm` and no CPU
virtualization, so **instrumented tests and the running app cannot be executed
in the development environment.** That is a hard constraint on Phase 3, not a
scheduling one, and it splits verification in two:

| Runs locally | Needs a device |
|---|---|
| JVM + Robolectric unit tests, adversarial and edge-case suites | MediaStore round trips, a live MediaSession, permission dialogs, GL rendering, real export encode |
| Static gates: detekt, ktlint, lint | Anything visual, thermal or timing-dependent |

Robolectric is not a substitute where it shadows the platform: its
`ContentResolver` reports success for any content uri (so a delete of a missing
file "succeeds") and its `BitmapFactory` fabricates a bitmap for any bytes.
Tests that would assert against those shadows assert "does not throw" instead,
each saying so at the point of the compromise.

Instrumented tests therefore run in CI, in their own emulator job, and are
triggered per run rather than assumed. **A change is not verified until that
job is green on it.**

### Still outstanding

- Instrumented coverage beyond clip management: session restore across process
  death, artwork on a real session, permission flows, export encode.
- Device matrix: API 26 floor, 34, 36; phone, tablet, foldable; low-RAM.
- Thermal and soak behaviour, which the engine plan also owes a benchmark for.
- No visual-regression capability at all.

## 5. Open decision: upload

The goal asks for upload capability. This is the one item that cannot be built
from the repo alone, for two reasons.

**The app currently holds no `INTERNET` permission**, and its own review copy
treats that as a feature: "no watermark, no account, no network". Adding upload
reverses that positioning and changes the Play Data Safety declaration.

**Direct posting needs credentials and platform review** that only the account
owner can obtain: YouTube requires OAuth with `youtube.upload` and a verified
Google Cloud project for public videos; TikTok's Content Posting API requires a
registered developer app and an audit before it may post publicly; Instagram
requires a Business/Creator account behind Facebook app review.

Three options, in order of cost:

| Option | Network | Credentials | User experience |
|---|---|---|---|
| **A. Share sheet only** (today) | none | none | User taps Send, picks the app, that app uploads. Works now; keeps the privacy claim. |
| **B. Share-sheet plus deep integration** | none | none | Direct Share targets, per-platform presets (9:16 + loop-safe for Reels), metadata prefilled. Feels integrated without a network stack. |
| **C. In-app direct upload** | `INTERNET` | YouTube + TikTok + Instagram developer apps, OAuth clients, platform review | Post without leaving the app; requires accounts, token storage, Data Safety changes. |

Recommendation: **B**, and treat C as a later opt-in module gated behind
credentials the owner supplies. B delivers most of the user value ("get this
clip to my platform in the right format") at zero privacy cost and zero
external dependency, and it is the only option that can be finished without
blocking.

Everything else in this plan proceeds regardless of which is chosen.
