# MusicViz — new-feature brainstorm (2026-07-31)

Ideas only. Nothing here is committed work, nothing here overrides
`todo.md` or `docs/FEATURES_TODO.md`. Each entry says what it is, why it
is worth doing, and what in the current tree it would attach to, so the
cost estimate is grounded rather than guessed.

## Where the app stands

v1.2.0 (versionCode 26). 29 scenes across three families (shader,
particle, MilkDrop/projectM), a full Stam fluid sim with GPU particles, a
real analysis chain (FFT → bands → pulse tracker → key detection →
cached per-track timeline), presets/palettes/LFOs/ADSRs, a 10-band EQ,
and MP4 export at up to 4K in six aspect ratios.

The existing backlog is deep, and almost all of it points *inward*: more
scenes (O3–O7 reaction-diffusion, physarum, raymarched fractals), more
parameter coverage, the VLC-style media browser, Drive. That work is
real. What no line of the backlog covers is the layer *around* the
engine:

- **The app can only ever visualize its own playback.** No `RECORD_AUDIO`
  in the manifest; the PCM tap (`audio/PcmTapSink.kt`,
  `audio/TapRenderersFactory.kt`) hangs off the ExoPlayer render pipeline.
  Music playing from Spotify, YouTube, a turntable or the room itself is
  invisible to it.
- **It is not yet a citizen of the Android media system.** No
  `MediaSession`, no foreground service, no notification or lockscreen
  controls, no Bluetooth/headset transport buttons, no Android Auto, no
  playback resumption. The player lives entirely in `PlayerViewModel`
  (2093 lines), so backgrounding the app loses the controls and risks the
  process.
- **Nothing leaves the phone except a manually driven export.** The share
  path exists (`ui/SettingsDialog.kt:73`) but only after the user
  chooses a quality, a ratio, an fps and waits out a full-length render.
- **The visuals can't reach a bigger screen.** No `Presentation`, no
  MediaRouter, no live wallpaper, no screensaver, no tile.
- **Album art is never read.** The MediaStore query
  (`PlayerViewModel.kt:1164`) pulls id/title/artist/album/duration and
  stops there, while `ui/ColorDerive.kt` and `ui/PaletteMaker.kt` already
  do all the color math a cover-derived palette would need.

Those five gaps are the spine of this brainstorm. Ideas are grouped by
the question they answer, not by subsystem.

---

## A. "Can it visualize *this*?" — input sources

### A1. Microphone mode — **the single biggest reach unlock**
Let the analysis chain read `AudioRecord` instead of the playback tap, so
MusicViz visualizes anything audible: another app, a record player, a
live room, a rehearsal.

*Fits:* the whole analysis stack downstream of `PcmRingBuffer` is already
source-agnostic — `FftProcessor`, `BandSmoother`, `PulseTracker`,
`FeatureExtractor` only ever see PCM frames. The work is a second
producer feeding the same ring buffer plus an input selector, not a new
pipeline.

*Costs:* one new runtime permission (a first for this app — it currently
asks for nothing but audio-media read, which is a genuinely strong
privacy story worth protecting in the Play listing and
`docs/PRIVACY_POLICY.md`); AGC/noise-floor handling so a quiet room does
not read as a beat; a mic-specific gain/sensitivity control.

*Effort:* M. *Risk:* low technically, medium in store positioning.

### A2. System audio capture (`AudioPlaybackCapture`, API 29+)
Same unlock as A1 without the acoustic round trip and without the room
noise. The catch is real and should be stated up front rather than
discovered: apps opt out of capture, and most large music apps do. Worth
building only as a *quiet upgrade path* — try capture, fall back to mic —
never as the advertised feature.

*Effort:* S on top of A1. *Risk:* medium (silently unavailable on the
apps users will most want it for).

### A3. USB / line-in interfaces
Niche, but the audiophile framing (the app already surfaces
`AudioQualityInfo` — bit depth, sample rate, container) makes it
coherent. Low priority.

---

## B. "Is it a music player I can live with?" — platform integration

### B1. MediaSession + foreground playback service — **foundational**
Notification and lockscreen transport, hardware/Bluetooth/headset
buttons, Android Auto, playback resumption, and a process the system
stops killing mid-track.

*Fits:* `media3-exoplayer` and `media3-common` are already dependencies;
this adds `media3-session` and moves player ownership out of
`PlayerViewModel` into a `MediaSessionService`. That refactor is the bulk
of the cost — 2093 lines of ViewModel currently own the player, the
queue, the tap, the analysis trigger and the export.

*Effort:* M–L. *Risk:* medium (touches everything), but it is the kind of
debt that only gets more expensive.

### B2. "Open with MusicViz" — intent filters
`AndroidManifest.xml` declares only MAIN/LAUNCHER, so the app is
invisible in every share sheet and every file manager's "open with". An
`ACTION_VIEW` filter on `audio/*` plus `ACTION_SEND`/`SEND_MULTIPLE`
makes MusicViz a first-class handler for a file the user already has.

*Effort:* S. *Risk:* low. Best effort-to-value ratio in this document.

### B3. Album art — read it, then paint with it
Two features for one query change. First the obvious one: art in library
rows and on the now-playing panel. Then the interesting one: derive a
palette from the cover and drive the visuals with it, so every track
brings its own color. `ColorDerive` already does ARGB math in a headless-
testable object and `PaletteMaker`/`PaletteStore` already model
user-authored palettes — a cover-derived palette slots in as another
source.

*Effort:* S for art, M for the palette pipeline. *Risk:* low. High
delight per unit of work; also the most screenshot-able idea here.

### B4. Playback niceties
Crossfade, gapless, ReplayGain-style normalization. Ordinary, expected,
cheap next to the rest — worth batching behind B1 since they live at the
same layer.

---

## C. "Can other people see it?" — getting off the phone

### C1. External display / Cast presentation mode
`android.app.Presentation` (or a Cast route) renders the scene full-screen
on a TV, monitor or projector while the phone keeps the controls. This is
the party/DJ/venue use case, and it is what turns the app from a personal
toy into something a room experiences.

*Fits:* the renderer can already target a surface it does not own — the
export path (`export/EncoderSurface.kt`, `export/VideoExporter.kt`) drives
the scene into an encoder surface at an arbitrary resolution with its own
GL context. A second live surface is closer to that than to new work.

*Effort:* M–L. *Risk:* medium (a second GL context and second-screen
lifecycles are a real test burden, and `docs/DEVICE_CHECKS.md` exists
because this codebase has been bitten by device-specific GL before).

### C2. Live wallpaper
A `WallpaperService` hosting the existing renderer, fed by either the
in-app player or the mic (A1). "My home screen moves to my music" is a
feature people demo to other people unprompted.

*Effort:* M. *Risk:* medium — battery and thermals become a headline
concern, though `render/fluid/FluidQuality.kt` and `PerformanceMonitor.kt`
already implement adaptive quality that would carry over, and
`render/VisualSafety.kt` already bounds flash rate.

### C3. Dock / screensaver / Quick Settings tile
A charging-dock ambient mode (`DreamService`) and a one-tap tile.
Small, and they make the app the thing a phone does while it sits idle.

*Effort:* S each.

---

## D. "Will anyone else ever see this?" — the sharing loop

### D1. One-tap highlight clip — **best growth-to-effort ratio**
Today, sharing means picking quality/ratio/fps and rendering a full
track. Instead: use the analysis the app already has to find the most
energetic ~15 seconds, render *that* at 9:16, and hand it straight to the
share sheet.

*Fits:* `FeatureTimeline` + `AnalysisCache` already store per-track
energy over time, `ExportRatio` already offers 9:16 and 4:5, and the
share intent already exists. This is mostly a selection heuristic and a
much shorter path through UI that is already built.

*Effort:* M. *Risk:* low. Turns a five-minute chore into a two-tap habit.

### D2. Presets as shareable files
Presets are already JSON on disk (`ui/PresetStore.kt`, with
`mirrorPresetToChosenFolder` for export). Add a share action and a
matching intent filter on a `.mvz` extension, and a preset becomes
something you send someone in a chat and they install by tapping it.
This is how a visualizer grows a scene around itself, and it costs
almost nothing because the format exists.

*Effort:* S. *Risk:* low. Should ship with strict validation on import —
presets carry shader source (`applyCustomShader`), so an imported file is
untrusted input, not just data.

### D3. Seamless loop export
A short, perfectly-looping clip for a cover/canvas or a profile
background. Constrains the export to a beat-aligned loop length, which
`PulseTracker` can already supply.

*Effort:* S–M.

---

## E. "Does it understand the music?" — intelligence

### E1. Section-aware choreography
`analysis/SceneSuggester.kt` is four rules over bpm/energy/centroid, and
it picks *once*, for a whole track. Meanwhile `KeyDetector`,
`FeatureTimeline` and `FeatureExtractor` produce far more than those
three numbers. Driving scene and intensity from detected sections —
intro, build, drop, breakdown — rather than from a track average is the
difference between "a visualizer that reacts" and "a visualizer that
anticipates."

*Note:* the backlog already carries "musically intelligent preset switch
(section + energy driven)" under P4. This entry is that item, restated
with the observation that the analysis layer is further ahead of the
suggester than the todo implies.

### E2. Storyboard / automation lane — **the most differentiating idea here**
Tracks are already analyzed and cached. Give the user a timeline they can
see: waveform + detected sections, with pinned scene changes and param
automation, saved per track and replayed on every playback. Nothing about
this is impossible today — it is a UI over data the app already computes
and stores.

That single feature moves MusicViz from "a visualizer" into "a tool you
prepare a set with," which is a different market and a much stickier one.

*Effort:* L. *Risk:* medium-high (new persistence, new UI surface), and
it should not start until B1's ownership refactor settles.

### E3. One-knob "vibe" control
`CustomizeDialog.kt` is 894 lines and `SceneParams` exposes dozens of
knobs. That depth is a genuine asset for the people who want it, and a
wall for everyone else. A single macro dial — calm ↔ intense — mapping
onto a curated bundle of params gives the second group a way in without
taking anything from the first.

*Effort:* M. *Risk:* low. This is a taste problem, not an engineering one:
the mapping has to be authored by someone with an opinion.

---

## F. Smaller, cheaper, still worth listing

- **Beat haptics.** `PulseTracker` already emits beats; the vibrator is
  one call away. Surprisingly good on a phone in the hand.
- **Preset packs by genre**, as an onboarding path into 480 lines of
  `BuiltInPresets` that a new user currently meets as an undifferentiated
  list.
- **Favorite/rate presets**, so the randomizer can bias toward what the
  user actually keeps.
- **Second-phone remote.** The controls and the canvas are already
  separable concepts (`VisualizerScreen` vs the player panel); with C1 in
  place, a phone driving a TV is a natural extension.

---

## Recommended sequence

If the goal is impact per unit of risk, in this order:

1. **B2 (intent filters)** and **B3 (album art)** — days, not weeks, and
   both are immediately visible to every user.
2. **B1 (MediaSession + service)** — the foundation. Everything in C and
   E2 is easier after the player stops living in the ViewModel, and it
   closes the most glaring "this is not a real music player" gap.
3. **A1 (mic input)** — the biggest expansion of what the app can be
   pointed at, and cheap because the analysis chain is already
   source-agnostic.
4. **D1 (highlight clip)** — the growth loop, built almost entirely from
   parts that already exist.
5. **C2 (live wallpaper)** or **C1 (external display)** — pick one as the
   flagship. Wallpaper is the wider audience; external display is the
   stronger story for the people who care most.
6. **E2 (storyboard)** — the differentiator, once the foundation is
   settled.

A deliberate note on what this list defers: the backlog's remaining scene
work (O3–O7) is *more of what the app is already very good at*. Every
item above is something the app currently cannot do at all. That is the
argument for the ordering, not a judgement on the scenes.
