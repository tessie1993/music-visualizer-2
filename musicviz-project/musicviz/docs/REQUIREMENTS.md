# MusicViz — Functional Requirements Specification

**Version described:** 1.2.0 (versionCode 26)
**Status:** as-built. This document specifies what the application *does*,
derived from the shipping build.

## About this document

This is a **feature specification**. It describes behaviour, options,
ranges and defaults as the user encounters them. It deliberately contains
no architecture, no class or file names, no algorithms and no
implementation guidance.

Every requirement is numbered `FR-<section>.<n>` so it can be referenced
in tickets and test plans. Notation:

- **Default** — the value a fresh install starts with.
- **Range** — the inclusive limits the control allows.
- Where a control is only offered on some visual styles, the applicable
  styles are named.

Section 25 lists **known gaps** — behaviour that is specified elsewhere in
the product (empty-state text, existing capability with no way to reach
it) but is not currently available to the user. It is part of the
specification because it defines the current boundary of the product.

---

## 1. Product overview

**FR-1.1** MusicViz is an Android music player that renders a real-time,
audio-reactive visualization of the music it is playing, and can record
that visualization to a video file.

**FR-1.2** The application operates entirely on-device. It requires no
account, no sign-in and no network connection. No usage data, telemetry
or analytics are collected or transmitted.

**FR-1.3** The application plays audio files stored on the device. It
does not stream from online services and does not visualize audio played
by other applications.

**FR-1.4** Platform requirements:

| Requirement | Value |
|---|---|
| Minimum Android version | 8.0 (API 26) |
| Target Android version | API 36 |
| Graphics | OpenGL ES 3.0 (required) |
| CPU architecture | arm64-v8a |

**FR-1.5** The application requests exactly one runtime permission:
access to audio media on the device (`READ_MEDIA_AUDIO`; on Android 12L
and below, `READ_EXTERNAL_STORAGE`). It requests no microphone, camera,
location, contacts or network permission.

**FR-1.6** Until music-access permission is granted, the Library presents
an explanatory message and an **Allow music access** button. All other
areas of the application remain usable.

---

## 2. Supported audio

**FR-2.1** The application plays the audio formats supported by the
platform media stack, including MP3, WAV, FLAC, OGG/Vorbis, M4A/AAC and
Opus.

**FR-2.2** The application additionally supports **AIFF and AIFC**
playback, including big-endian and little-endian uncompressed PCM.

**FR-2.3** When scanning user-added folders, files with the following
extensions are recognised as audio: `mp3`, `wav`, `flac`, `ogg`, `m4a`,
`aac`, `opus`, `wma`, `aiff`.

---

## 3. Application shell and navigation

**FR-3.1** The application presents four primary destinations in a
persistent bottom navigation bar: **Home**, **Library**, **Visuals**,
**Settings**.

**FR-3.2** A **mini-player** is docked above the navigation bar whenever
media is loaded. It shows the track title, a play/pause control, a next
control, and a thin progress bar. It is hidden when no media is loaded.

**FR-3.3** Tapping the mini-player opens the **Now Playing** full-screen
visualizer as an overlay. Visual state is preserved across
collapse/expand — collapsing and re-expanding never restarts or resets
the running visualization.

**FR-3.4** A full-screen **Search** overlay is reachable from Home and
Library.

**FR-3.5** The system back gesture unwinds in this order:

1. Now Playing expanded → collapse to the shell
2. Search open → close Search
3. Library drill-in open → return to the group list
4. Any non-Home destination → return to Home
5. Home → exit the application

**FR-3.6** On cold start the application shows a system splash screen
followed by a one-shot **boot animation** (approximately 1.4 s; expanding
rings and the wordmark). Tapping skips it. It plays once per application
start and can be disabled (FR-20.12).

**FR-3.7** If the previous run of the application terminated abnormally,
a **Previous crash captured** dialog is shown on next start, offering
**Copy** (copies the report to the clipboard) and **Dismiss**.

---

## 4. Home

**FR-4.1** Home provides a **Search** action.

**FR-4.2** When a track is loaded, Home shows a **Resume** card; tapping
it opens Now Playing.

**FR-4.3** Home provides a **Shuffle all** action, which builds a shuffled
queue from up to the 100 most recently played tracks.

**FR-4.4** Home shows a **Recently played** row of tappable tracks;
tapping plays that track.

**FR-4.5** Home shows a **Most played** row, each entry annotated with
its play count; tapping plays that track.

**FR-4.6** When no play history exists, Home shows guidance directing the
user to the Library.

---

## 5. Search

**FR-5.1** Search matches across **tracks**, **music playlists** and
**visual presets** in a single query.

**FR-5.2** A query is split into whitespace-separated terms. A result
matches only when **every** term matches at least one of its fields.
Matching is case-insensitive and substring-based.

**FR-5.3** Track results are deduplicated by file, and are capped at
**30** results. Results are grouped under headings that show the count
per category.

**FR-5.4** Selecting a track result plays it. Each track result also
offers an **Add to queue** action.

**FR-5.5** Selecting a preset result applies that visual preset.

**FR-5.6** Search input is debounced. With no query entered, the overlay
shows a prompt to type; with a query and no matches, it reports that
there are no results for that query.

---

## 6. Library

**FR-6.1** The Library presents five tabs: **Tracks**, **Albums**,
**Artists**, **Playlists**, **Folders**.

**FR-6.2 (Tracks)** Lists all audio on the device, merged with tracks the
user has added to their own library list. Each row shows title and a
subtitle of secondary metadata.

**FR-6.3 (Albums / Artists / Folders)** Present a two-level drill-in: a
list of groups showing the track count per group, then that group's
tracks. A **‹ Back** control and the system back gesture return to the
group list. Each group offers **Play all** and **Shuffle**.

**FR-6.4** Every track row offers an overflow menu with:

| Action | Behaviour |
|---|---|
| Play next | Inserts the track immediately after the current one |
| Add to queue | Appends the track to the end of the queue |
| Add to library list | Adds the track to the user's own library list |
| Edit track info | Opens the track info editor (FR-7) |

**FR-6.5 (Playlists)** Lists user music playlists with their track count.
Each playlist can be:

- expanded to reveal its tracks in order,
- **renamed**,
- **played**,
- reordered, by moving any track **up** or **down** one position.

**FR-6.6 (Folders)** Lists the music folders the user has added, and
provides:

- **Add folder** — choose a folder anywhere the system exposes,
- **Remove folder** — per folder,
- **Rescan** — re-reads the added folders, showing "Scanning…" while it
  runs.

**FR-6.7** Tracks are stored per file location, so the same file added
twice does not appear twice.

**FR-6.8** A track carries the following library metadata: title, artist,
album, genre, year, track number, comment, duration, **BPM**, **musical
key**, and an **analyzed** flag.

---

## 7. Track info editor

**FR-7.1** The user can edit, per track: **Title**, **Artist**, **Album**,
**Genre**, **Year**, **Track #**, **Comment / info**.

**FR-7.2** Genre offers quick-pick chips: Electronic, Rock, Pop, Hip-Hop,
Jazz, Classical, Ambient, Other. A free-text genre may also be typed.

**FR-7.3** Edits are stored inside the application only. **The audio
files on disk are never modified.** The editor states this to the user.

**FR-7.4** Edited metadata is used everywhere the track is displayed and
is searchable.

---

## 8. Playback

**FR-8.1** Transport controls: play/pause, next, previous, and seek to
any position in the track.

**FR-8.2** **Shuffle** can be toggled; the setting persists across
restarts.

**FR-8.3** **Repeat** cycles through three modes: off → repeat one →
repeat all; the setting persists across restarts.

**FR-8.4** Seek controls allow scrubbing by dragging, and seeking by a
relative offset.

**FR-8.5** The queue can be extended by **Play next** and **Add to
queue** from any track row or search result.

**FR-8.6** Playback preferences, all persisted:

| Setting | Range | Default |
|---|---|---|
| Speed | 0.5×–2.0×, in 0.05 steps | 1.00× |
| Pitch | −6 to +6 semitones, in 0.5 steps | 0 |
| Skip silence | on/off | off |
| Pause when unplugged | on/off | **on** |
| Keep screen on | on/off | off |
| Auto-resume last track | on/off | off |

**FR-8.7** Speed and pitch are independent: changing speed does not
change pitch, and vice versa.

**FR-8.8** **Pause when unplugged** pauses playback when headphones are
disconnected.

**FR-8.9** **Auto-resume last track** loads (but does not start) the last
played track when the application opens. Loading it in this way does not
add a play to the history.

**FR-8.10 (Sleep timer)** Selectable durations: **Off, 15, 30, 45, 60
minutes**. While a timer runs, the remaining time is shown as a live
countdown. Selecting **Off** cancels a running timer. The last chosen
duration persists across restarts; a **running** timer does not — it is
never resurrected after a restart.

**FR-8.11** Play history records each track played and its cumulative
play count, and drives the Home rows (FR-4.4, FR-4.5) and Shuffle all.

---

## 9. Equalizer and audio effects

**FR-9.1** A 10-band-class **Equalizer** is provided, with a master
on/off switch. Band count, band labels and gain limits are supplied by
the device.

**FR-9.2** Device equalizer presets are offered as chips. When band
levels no longer match any preset, the state is labelled **Custom**.

**FR-9.3** **Bass boost** — range 0–100%, adjustable in 0.1% units.

**FR-9.4** **Loudness** — a gain control displayed in dB.

**FR-9.5** All effect controls are disabled (greyed) while the master
switch is off.

**FR-9.6** On devices whose audio effects are unavailable, the whole card
degrades to **"Not supported on this device"** and no controls are
offered. This is not treated as an error.

---

## 10. Audio quality reporting

**FR-10.1** Now Playing displays a quality badge for the current track,
one of: **BIT-PERFECT**, **LOSSLESS**, **LOSSY**.

**FR-10.2** Expanded quality details, where known: **codec**,
**container**, **source** (sample rate, channels, bit depth),
**bitrate** in kbps, and **output** (the format actually delivered to the
device).

**FR-10.3** **BIT-PERFECT** is shown only when the source is a lossless
codec, is delivered at its original sample rate with no resampling, and
retains its channel layout — i.e. when the signal reaching the device is
the source signal, sample for sample.

---

## 11. Now Playing (visualizer screen)

**FR-11.1** Now Playing renders the visualization full-screen behind the
controls.

**FR-11.2** Tapping the canvas hides or shows the control surfaces, for
an unobstructed view.

**FR-11.3** Controls comprise: a collapse control, the track title and
artist, a seek bar with elapsed and total time, and the transport row —
shuffle, previous, play/pause, next, repeat.

**FR-11.4** A **Visuals** action opens the Visuals hub.

**FR-11.5** An **Auto** action cycles three modes:

| Mode | Behaviour |
|---|---|
| **Auto: off** | The chosen style stays until changed |
| **Auto: random** | Random mode selects new looks automatically (FR-17) |
| **Auto: smart** | Scene intelligence selects the style from the music (FR-18) |

**FR-11.6** The two automatic modes are mutually exclusive; selecting one
disables the other.

**FR-11.7** The visualization is rendered by a single engine shared by
the collapsed and expanded views, so switching between them never
interrupts or restarts it.

---

## 12. Visual styles

**FR-12.1** The application provides **29 visual styles**, organised into
four families presented as sub-tabs of **Visuals › Styles**.

**FR-12.2 (Particles — 5)** Nebula, Bursts, Swarm, Fountain, Orbits.

**FR-12.3 (Shaders — 20)** Julia, Tunnel, Mandel, Kaleido, Plasma, Bars,
Ring, Scope, Liss, Warp, Grid, Voronoi, Metaballs, Ripples, Starfield,
Waves, Hexgrid, Spiral, Aurora, Solar.

**FR-12.4 (Fluid — 3)**

- **Fluid** — a full fluid simulation: ink and velocity fields, vorticity,
  pressure solve, an optional particle layer, glow and sunrays.
- **Curl Flow** — a curl-noise flow field with a particle layer and
  feedback trails.
- **Water** — a heightfield water surface where beats drop expanding
  rings, with refraction and specular highlights.

**FR-12.5 (MilkDrop — 1)** A MilkDrop-compatible style that renders
`.milk` presets (FR-15).

**FR-12.6** Selecting a style takes effect immediately on the live
visualization.

**FR-12.7** On devices where the MilkDrop engine is unavailable, the
MilkDrop tab reports **"MilkDrop engine unavailable on this device"** and
offers no controls. All other styles remain available.

---

## 13. Customization parameters

**FR-13.1** The application exposes **118 user-controllable visual
parameters**, presented in **Visuals › Customize** across the sub-tabs
**Motion, Shape, Behavior, Color, FX, Fluid**, plus **GLSL** when a
shader style is active.

**FR-13.2** Every parameter change takes effect on the live visualization
immediately.

**FR-13.3** Parameters that a given style cannot express are **hidden on
that style**, not shown inert. Where a control is meaningful only for
certain styles but is shown everywhere, its label names the style it
affects (e.g. "Trails (particle scenes)", "Glow (fluid)", "MilkDrop
palette tint").

**FR-13.4** Parameter values are shared across styles: switching style
keeps the current settings, so a look can be carried from one style to
another.

### 13.5 Motion

| Parameter | Range | Default |
|---|---|---|
| Speed | 0.05–4 | 1 |
| Zoom | 0.3–3 | 1 |
| Rotation (rate) | −3 to 3 | 0 |
| Sway | 0–1 | 0 |
| Drift X | −1 to 1 | 0 |
| Drift Y | −1 to 1 | 0 |
| Beat pulse | 0–1 | 0 |
| Beat shake | 0–1 | 0 |
| Endless zoom | on/off | off |
| Dive speed | 0.05–1.2 | 0.3 |

Rotation is a **speed**, not an angle: the image turns continuously at
the set rate.

### 13.6 Shape

| Parameter | Range | Default |
|---|---|---|
| Domain warp | 0–1 | 0 |
| Ripple | 0–1 | 0 |
| Morph *(shader styles)* | 0–1 | 0 |
| Twist | −1 to 1 | 0 |
| Kaleidoscope | on/off | off |
| Folds | 0, 2, 3, 4, 5, 6, 7, 8, 9, 12, 16 | 0 (off) |
| Tile | 1–6 | 1 |
| Pixelate | 0–1 | 0 |
| Posterize | 0–1 | 0 |
| Particle shape *(particle styles)* | Dot, Ring, Star, Square, Spark, Hex, Bubble | Dot |
| Particle size *(point-sprite styles)* | 0.3–2.5 | 1 |

### 13.7 Behavior

| Parameter | Range | Default |
|---|---|---|
| Audio drive | 0.2–2.5 | 1 |
| Beat response | 0–2 | 1 |
| Beat flash | 0–1 | 0 |
| Bass gain | 0–2 | 1 |
| Mid gain | 0–2 | 1 |
| Treble gain | 0–2 | 1 |
| Turbulence | 0–1.5 | 0 |
| Density | 0.1–1 | 1 |
| Mirror | on/off | off |
| Trails *(particle styles)* | on/off | off |
| Trail length | 0.05–0.98 | 0.5 |
| Trail zoom (echo in/out) | −0.5 to 0.5 | 0 |
| Trail warp (liquid echo) | 0–1 | 0 |
| Reactivity attack | 0.05–1 | 0.6 |
| Reactivity decay | 0.02–0.6 | 0.12 |

This tab also hosts **Scene transition** (FR-19) and **Scene
intelligence** (FR-18).

### 13.8 Color

| Parameter | Range | Default |
|---|---|---|
| Palette | 21 built-in palettes, or a saved custom palette | Spectrum |
| Palette 2 *(shader styles)* | as above | Neon |
| Palette blend *(shader styles)* | 0–1 | 0 |
| MilkDrop palette tint | 0–1 | 0 |
| Hue shift | 0–1 | 0 |
| Hue range | 0–1.5 | 1 |
| Color cycle | on/off | off |
| Cycle speed | 0.02–0.6 | 0.1 |
| Saturation | 0–1.5 | 1 |
| Brightness | 0.2–2 | 1 |
| Contrast | 0.3–2.5 | 1 |
| Gamma | 0.3–2.5 | 1 |
| Intensity | 0.2–2 | 1 |
| Temperature | −1 to 1 | 0 |
| Bloom | 0–1 | 0 |
| Duotone *(shader styles)* | on/off | off |
| Solarize | on/off | off |
| Invert | on/off | off |

**FR-13.8.1** The 21 built-in palettes are: Spectrum, Neon, Fire, Ocean,
Mono, Candy, Forest, Aurora, Sunset, Ice, Vapor, Toxic, Royal, Blush,
Copper, Mint, Galaxy, Cherry, Cyan, Magenta, Yellow.

**FR-13.8.2** MilkDrop presets author their own colours. The palette
therefore reaches them as an opt-in **tint** — a blend toward the chosen
palette — which defaults to 0 so that every existing preset looks
unchanged. The tint preserves each preset's structure and contrast.

### 13.9 Screen FX

Applied to every style.

| Parameter | Range | Default |
|---|---|---|
| Chromatic aberration | 0–1 | 0 |
| Vignette | 0–1 | 0 |
| Scanlines | 0–1 | 0 |
| Film grain | 0–1 | 0 |
| Glitch | 0–1 | 0 |
| Fisheye | −1 to 1 | 0 |
| Strobe | 0–1 | 0 |

### 13.10 Settings fade

**FR-13.10.1 Fade time** — range 0–5 s, default 0. Slider changes and
preset loads glide to their new values over this time rather than
snapping.

### 13.11 Fluid family parameters

Shown only on the styles that use them.

**Quality (Fluid, Water)**

| Parameter | Options | Default |
|---|---|---|
| Quality | Ultra, High, Medium, Low, Min | Medium |
| Auto quality | on/off | **on** |

**Auto quality** lowers the tier automatically after sustained low frame
rates; it never raises it above the user's choice.

**Character (Fluid)**

| Parameter | Range | Default |
|---|---|---|
| Fluid curl | 0–50 | 30 |
| Motion fade | 0–4 | 0.2 |
| Fluid fade | 0–4 | 1 |
| Chromatic aging | 0–1 | 0.3 |
| Pressure | 0–1 | 0.8 |
| Solver iterations | 8–40 | 20 |

**Emitters (Fluid, Water)**

| Parameter | Range | Default |
|---|---|---|
| Beat pattern | Center, Ring, Random, Spectrum | Ring |
| Beat splats | 0–8 | 3 |
| Stirrers | 0–4 | 2 |
| Stirrer speed | 0–2 | 1 |
| Fluid splat radius | 0.02–0.4 | 0.12 |
| Radius on beat | 0–1 | 0.4 |
| Fluid splat force | 0–3 | 1 |
| Bass pump | on/off | off |
| Treble sparkle | on/off | **on** |
| Palette cycle *(Fluid)* | 0–2 | 0.5 |

**Journey — spawn & catch (Fluid, Curl Flow, Water)**

| Parameter | Range | Default |
|---|---|---|
| Path | Orbit, Lissajous, Rose, Bloom, Drift | Lissajous |
| Spawn points | 1–8 | 3 |
| Progression | 0–1 | 1 |
| Catch points | 0–4 | 2 |
| Catch pull | 0–3 | 1 |
| Catch radius | 0.03–0.3 | 0.12 |

**Progression** sets how strongly the track's playback position reshapes
the journey over the course of the song.

**Particles (Fluid, Curl Flow)**

| Parameter | Range | Default |
|---|---|---|
| Particle layer *(Fluid)* | on/off | **on** |
| Particle drag | 0.02–1 | 0.5 |
| Particle life | 1–20 s | 6 s |
| Particle brightness *(Fluid)* | 0–2 | 1 |
| Ink layer *(Fluid)* | on/off | **on** |

**Look (Fluid)**

| Parameter | Range | Default |
|---|---|---|
| Shading (embossed ink) | on/off | **on** |
| Glow (fluid) | on/off | **on** |
| Fluid glow | 0.1–2 | 0.8 |
| Glow threshold | 0–1 | 0.6 |
| Sunrays | on/off | **on** |
| Sunrays weight | 0.3–1 | 1 |

**Audio routing (Fluid)**

| Parameter | Range | Default |
|---|---|---|
| Curl from mids | 0–1 | 0.5 |
| Glow from loudness | 0–1 | 0.5 |
| Fade when quiet | 0–1 | 0.6 |

**Water surface (Water)**

| Parameter | Range | Default |
|---|---|---|
| Wave speed | 0.2–2 | 1 |
| Damping | 0.9–0.999 | 0.985 |
| Ripple strength | 0–2 | 1 |
| Depth | 0–1 | 0.6 |
| Specular | 0–1 | 0.7 |
| Flow drift | 0–1 | 0.3 |

### 13.12 Cross-style fluid effects

These apply fluid behaviour to **any** style, including shader, particle
and MilkDrop.

**FlowField (all styles)**

| Parameter | Range | Default |
|---|---|---|
| FlowField enabled | on/off | off |
| Flow strength | 0–1 | 0.35 |
| Flow force | 0–3 | 1 |
| Flow curl | 0–50 | 25 |
| Particles ride the field | on/off | **on** |

**Water ripples (all styles except Water)**

| Parameter | Range | Default |
|---|---|---|
| Water ripples enabled | on/off | off |
| Wave speed | 0.2–2 | 1 |
| Damping | 0.9–0.999 | 0.985 |
| Ripple overlay strength | 0–1 | 0.4 |
| Ripple glint | 0–1 | 0.3 |

The ripple overlay is unavailable on the Water style, whose own surface
already refracts.

### 13.13 Injection shaders (advanced, Fluid)

**FR-13.13.1** For the Fluid style, the user may supply their own **force**
and **dye** injection shader source, apply them, and **Reset to
built-in**.

---

## 14. Palettes and the palette maker

**FR-14.1** Either palette slot can use a built-in palette (FR-13.8.1) or
a user-made palette.

**FR-14.2** The palette maker builds a gradient from two controls: a
**base hue** (where the gradient starts) and a **hue span** (how far it
travels), with a live gradient preview.

**FR-14.3** The maker offers:

| Action | Behaviour |
|---|---|
| Apply gradient | Applies the gradient to the current palette slot |
| From current | Loads the slot's current palette into the editor |
| Save palette | Saves the gradient under a user-supplied name |

**FR-14.4** Saved palettes are listed and can be **edited** or
**deleted**.

**FR-14.5** Saved palettes appear alongside the built-in palettes, so
choosing one is the same gesture as choosing a built-in.

**FR-14.6** A palette slot may override the base hue, the span, or both
— a custom span can be combined with a built-in base and vice versa.

**FR-14.7** Custom palettes are saved inside presets, so a preset restores
the exact colours it was saved with.

---

## 15. MilkDrop presets and textures

**FR-15.1** The user can import `.milk` preset files from device storage
via **Load .milk file**.

**FR-15.2** Imported `.milk` presets are listed under **Your .milk
presets** and can be applied. When none exist, guidance is shown.

**FR-15.3** The user can import images to be used as MilkDrop **textures**
via **Import images**, in the **Visuals › Textures** tab.

**FR-15.4** Each imported texture offers a **Use** action, which makes it
active for the MilkDrop style. Textures can be removed.

**FR-15.5** Saving a preset while the MilkDrop style is active also
writes a `.milk` file, so the look is portable.

---

## 16. Presets

**FR-16.1** A preset captures the complete look: the **style**, all
**118 parameters**, the **reactivity envelope**, any **custom shader**,
and any **custom palettes**.

**FR-16.2** The user can save the current look under a chosen name via
**Save current as…**.

**FR-16.3** Presets are organised in a **folder tree**. The user can
create folders (**+ folder**), and choose the destination folder when
saving.

**FR-16.4** Each user preset row offers:

| Action | Behaviour |
|---|---|
| Apply | Loads the preset onto the live visualization |
| ♥ | Adds the preset to the visual playlist |
| 🗑 | Deletes the preset |

**FR-16.5** The application ships **309 built-in presets**: 12 curated
looks — Chill, Punchy, Hypno, Vivid, Retro, Glitch, Dream, Warp, Prism,
Noir, Strobe, Deep — applied to each of the 25 particle and shader
styles, plus 9 purpose-built fluid-family presets: Inkdrop, Vortex,
Spectrum, Nebula, Lava, Storm, Journey (Fluid), Rainfall (Water) and
Streams (Curl Flow).

**FR-16.6** Built-in presets can be applied, added to the visual
playlist, and selected by Random mode, but cannot be deleted or
overwritten.

**FR-16.7** Built-in presets are named `style · Look`. That separator
never appears in a user preset name, so the two can never collide.

**FR-16.8** Built-in presets for the current style are listed separately
from the user's own.

**FR-16.9 (Preset folder)** The user may nominate a folder on the device
as their **preset folder**. Saved presets are mirrored there so the user's
own filing is visible in any file manager. The choice can be cleared, at
which point presets are kept internally only.

**FR-16.10** Presets are searchable (FR-5.1) and applying one from Search
takes effect immediately.

---

## 17. Random mode

**FR-17.1** Random mode automatically changes the look while music plays.

**FR-17.2** Options:

| Setting | Range | Default |
|---|---|---|
| Enabled | on/off | off |
| Interval | seconds | 20 s |
| Change on beat | on/off | **on** |
| Include styles | on/off | **on** |
| Include presets | on/off | **on** |
| Include `.milk` presets | on/off | off |
| Randomize colours | on/off | off |

**FR-17.3** With **Change on beat** enabled, changes land on musical
moments rather than purely on the clock.

**FR-17.4** Random mode and the visual playlist are mutually exclusive.

### 17.5 Visual playlist

**FR-17.5.1** The user can build an ordered **visual playlist** of styles,
presets and `.milk` presets, added via the ♥ action (FR-16.4).

**FR-17.5.2** The visual playlist can be enabled, has a step **interval**
(default 30 s), and can be set to advance **intelligently** — driven by
the music rather than a fixed interval.

**FR-17.5.3** Entries can be removed by position.

### 17.6 Parameter randomizer and locks

**FR-17.6.1** A **⚄ Randomize unlocked** action randomizes the
customization parameters in one gesture.

**FR-17.6.2** Any parameter can be **locked**, individually, by tapping
its label. Locked parameters are excluded from randomization. Locks apply
to sliders and to selector chips alike (Palette, Palette 2, Particle
shape, Beat pattern, Path).

---

## 18. Scene intelligence

**FR-18.1** Three modes are offered:

| Mode | Behaviour |
|---|---|
| **manual** | The user's chosen style is always used |
| **suggest** | The application recommends a style; the user decides |
| **auto** | The recommended style is applied automatically |

**FR-18.2** The recommendation is derived from the track's tempo, energy
and brightness. The rules are deliberately simple and always overridable
by the user.

**FR-18.3** The user's explicit style choice always wins over a
recommendation.

---

## 19. Transitions and morphing

**FR-19.1** Five **scene transition** styles: **cut, fade, melt, slide,
zoom**. Default: fade.

**FR-19.2** Transition **duration** — range 0.3–5 s, default 1.2 s.

**FR-19.3 (Preset morph)** When a preset is applied, its parameters can
interpolate over a number of beats rather than snapping. Range **0–16
beats**, default **4**; 0 means snap.

**FR-19.4** Preset morphing is beat-locked (it follows the music's tempo),
whereas the settings fade (FR-13.10.1) is measured in seconds. The two
are independent.

---

## 20. Appearance

**FR-20.1** **26 themes** are offered as selectable cards: Lapis
(default), Malachite, Clear Quartz, Rose Quartz, Sugilite, Amethyst,
Kyanite, Onyx, Midnight, Neon, Sunset, Forest, Mono, Ocean, Violet,
Ember, Candy, Slate, Rose, Mint, Cobalt, Sand, Grape, Ink, Light, Paper.

**FR-20.2** Two of the themes (**Light**, **Paper**) are light; the other
24 are dark.

**FR-20.3** **Bar opacity** — range 20–100%, default 72%. Controls the
translucency of the control panels and sheets.

**FR-20.4** **Player position** — **Top** or **Bottom**, default Bottom.
Controls sit on the opposite side.

**FR-20.5** **Corner style** — **Sharp**, **Rounded** (default) or
**Pill**, applied to every floating control surface.

**FR-20.6** **Accent intensity** — range 50–150%, default 100%. Scales
the saturation of the theme's accent colours.

**FR-20.7** **Background dim** — range 0–60%, default 0%. Darkens
backgrounds and surfaces.

**FR-20.8** **Follow system light/dark** — on/off, default off. When on,
the application switches to the Light theme whenever the system is in
light mode.

**FR-20.9** **White font** — on/off, default off. Forces all body and
label text to pure white. It has no effect on the light themes, where it
would be unreadable; the setting explains this.

**FR-20.10** **Compact mini-player** — on/off, default off. Renders a
slimmer mini-player bar.

**FR-20.11** **Clear-overlay Visuals menu** — on/off, default off. Renders
the Visuals hub as a text-only overlay directly on the live visualization,
so adjustments are visible while they are being made. The mode can also be
toggled from within the Visuals hub itself.

**FR-20.12** **Boot animation** — on/off, default on. Disables the
start-up animation (FR-3.6).

---

## 21. Visual safety and accessibility

**FR-21.1 (Safe visuals)** A master switch — default **off** — that caps
how fast and how strongly the entire screen may flash. When off, saved
presets look exactly as the user left them.

**FR-21.2** **Maximum flashes per second** — range 1–9 Hz, default
**3 Hz**. The setting cites published guidance (WCAG 2.3.1), which puts
the general limit at three flashes per second.

**FR-21.3** **Maximum flash strength** — range 0–100%, default **25%**.
This is the largest full-screen brightness swing a flash may produce; 0
removes full-screen flashing entirely.

**FR-21.4** **Allow invert and solarize** — on/off, default **off**.
These effects reverse the whole frame at once; the setting keeps them
available inside Safe visuals for users who want them.

**FR-21.5 (Reduced motion)** An independent switch — default off — that
slows movement, shake, drift and rotation. It is a comfort setting for
motion sensitivity and is deliberately separate from Safe visuals, which
addresses photosensitivity.

**FR-21.6** Both settings apply to **exported video** as well as to the
screen, so a clip is as safe as the screen was. This is stated in the
settings screen.

**FR-21.7** The safety limits are applied after every other modulation —
including automation, randomization and presets — so no combination of
settings can exceed them while Safe visuals is on.

---

## 22. Music analysis

**FR-22.1 (Live analysis)** While music plays, the application
continuously derives from the audio: frequency band levels, waveform,
overall level, bass/mid/treble energy, onsets, beats, tempo (BPM) and
spectral brightness. These drive the visuals in real time.

**FR-22.2 (Beat grading)** Beats carry a **strength** — how hard the hit
was relative to the track's own dynamics — so a soft hit produces a
smaller response than an accent. Off-grid transients are reported
separately from grid beats, so real hits between beats remain visible.

**FR-22.3 (Beat phase)** The position within the current beat interval is
available continuously, so motion can anticipate and land on beats rather
than only reacting after them.

**FR-22.4** Beat tracking resets between tracks: one track's beat grid is
never carried into the next.

**FR-22.5 (Offline analysis)** A track can be analyzed ahead of playback
to produce its **BPM**, **musical key** and a **section map**, with
progress reported while it runs. A whole playlist can be analyzed in one
action.

**FR-22.6** Musical key is reported in standard form (e.g. "A minor",
"F# major").

**FR-22.7** Analyzed tracks are marked with an **analyzed** flag and show
their BPM and key in the library.

**FR-22.8 (Track position)** Playback position, current section index and
total section count are available to the visuals, enabling looks that
progress over the course of a song.

**FR-22.9 (Analysis cache)** Analysis results are cached on device, up to
**15 tracks**, evicting the oldest. Settings displays the cache size and
entry count and offers **Clear**.

**FR-22.10 (Beat sensitivity)** Two user controls tune beat detection:

| Setting | Range | Default |
|---|---|---|
| Beat sensitivity | 1.5σ–6σ (higher = less sensitive) | 2.5σ |
| Minimum gap between beats | 200–1200 ms | ≈333 ms |

**FR-22.11** Two presets are offered: **Slow track** (4.5σ / 700 ms) and
**Default** (2.5σ / 333 ms).

**FR-22.12** Changing beat sensitivity re-decides the beats of already
analyzed tracks from the cache, without re-analyzing them.

---

## 23. Video export

**FR-23.1** The visualization can be rendered to an **MP4 video file with
the track's audio**.

**FR-23.2** Export is rendered offline — not screen-recorded — so the
output is unaffected by on-screen controls and by device performance
during rendering.

**FR-23.3 Quality** — **720p**, **1080p** or **4K**.

**FR-23.4 Frame rate** — **30 fps** or **60 fps**.

**FR-23.5 Aspect ratio** — **16:9**, **9:16**, **1:1**, **4:5**, **4:3**,
**21:9**.

**FR-23.6** The render button states the exact target, e.g. "Render 1080p
9:16 60fps".

**FR-23.7** When 4K is selected, the application warns that 4K depends on
the device's encoder and **falls back automatically** if unsupported. An
export must never fail outright because a requested combination exceeds
the device's encoder.

**FR-23.8** Two destinations are offered:

| Destination | Behaviour |
|---|---|
| Default | Saved to the device's Videos library, under **Movies/MusicViz** |
| Chosen folder | The user picks the destination and file name |

**FR-23.9** Progress is reported while rendering, and the export can be
**cancelled**.

**FR-23.10** On completion the application reports where the file was
saved and offers **Upload to Drive / Share**, handing the video to the
system share sheet.

**FR-23.11** On failure the reason is shown to the user.

**FR-23.12** The exported video matches what was on screen: the same
style, the same parameters, the same effects chain, the same band gains,
and the same visual-safety limits.

---

## 24. About, privacy and licensing

**FR-24.1** Settings displays the application name and version.

**FR-24.2** A **Privacy policy** is available in the application.

**FR-24.3** The privacy statement is that the application collects no
analytics and that **nothing leaves the phone unless the user exports a
video and shares it**.

**FR-24.4** An **Open source licenses** screen displays the full
third-party notices for the components the application bundles.

---

## 25. Known gaps

Behaviour that the product implies or partially provides, but that the
user cannot currently reach. Listed for completeness; each is a candidate
requirement rather than a defect in the above.

**GAP-1 — Music playlists cannot be created.** Playlists can be renamed,
played, expanded and reordered (FR-6.5), but no user-facing action
creates one or adds a track to one. The Playlists empty state directs the
user to "build a queue in Now Playing and save it", and no such save
action exists.

**GAP-2 — Music playlists cannot be deleted.**

**GAP-3 — The playback queue cannot be viewed or reordered.** Tracks can
be added to the queue (FR-6.4), but there is no queue screen, so a queued
track cannot be inspected, removed or jumped to.

**GAP-4 — Random mode and the visual playlist have no settings screen.**
Random mode can only be switched on via the Auto control (FR-11.5) with
its default interval and inclusion rules; the visual playlist can be
added to (FR-16.4) but its enable switch, interval, intelligent mode and
entry removal are not exposed. The behaviour in FR-17.2 and FR-17.5.2
describes the settings that exist but are not reachable.

**GAP-5 — "Beat pulse" has no effect on MilkDrop or the fluid family.**
The control is offered on all styles; only shader and particle styles
respond to it.

**GAP-6 — "Endless zoom" is offered on the fluid family**, which has no
equivalent behaviour.

**GAP-7 — "Hue range" behaves differently on the fluid family**, where it
is internally limited: values above 1 have no further effect, and 0 does
not collapse the palette to a single colour as it does elsewhere.

**GAP-8 — "Particle shape" has no effect on the Fluid and Curl Flow
particle layers**, whose sprites are always round, although the selector
is shown.

**GAP-9 — Album art is never displayed.** No screen shows cover art.

**GAP-10 — There is no notification, lockscreen or headphone-button
control.** Playback can only be controlled from inside the application.

**GAP-11 — The application does not appear in the system share sheet or
in "Open with" for audio files.** A track can only be reached by
browsing to it inside the application.

---

## Appendix A — Automation (LFOs and envelopes)

### A.1 LFOs

**FR-A.1.1** Three LFOs are provided, each independently configurable.

**FR-A.1.2** Per LFO:

| Setting | Options | Default |
|---|---|---|
| Enabled | on/off | off |
| Target | 44 assignable targets | None |
| Waveform | Sin, Tri, Saw, Sqr, S&H | Sin |
| Rate | 0.02–8 Hz | 0.5 Hz |
| Sync to BPM | on/off | off |
| Beat division | 0.25 (16th) – 8 (2 bars) | 1 beat |
| LFO depth | 0–1 | 0.3 |

**FR-A.1.3** With **Sync to BPM** on, the cycle length is expressed in
beats and follows the detected tempo instead of a fixed rate.

**FR-A.1.4** Assignable targets are: Speed, Zoom, Rotation, Sway, Pulse,
Drift X, Drift Y, Warp, Ripple, Morph, Twist, Tile, Pixelate, Posterize,
Hue shift, Palette blend, Saturation, Brightness, Intensity, Bloom,
Temperature, Turbulence, Chroma AB, Vignette, Glitch, Fisheye, Particle
size, Trail length, Fluid curl, Fluid splat radius, Fluid splat force,
Fluid glow, Fluid fade, Catch pull, Catch radius, Flow strength, Ripple
amp, Ripple ovl — plus six **chain** targets.

**FR-A.1.5 (Chaining)** An LFO may drive the **rate** or **depth** of a
higher-numbered LFO (LFO 1 → 2 → 3). Chaining back to itself or to a
lower-numbered LFO is not offered, so feedback loops cannot be built.

### A.2 Envelopes (ADSR)

**FR-A.2.1** Two beat-triggered envelopes are provided.

**FR-A.2.2** Each envelope's **attack is triggered by detected beats**,
and its **sustain is gated by band energy**: it holds while the chosen
band stays above the threshold and releases when the band drops.

**FR-A.2.3** Per envelope:

| Setting | Range | Default |
|---|---|---|
| Enabled | on/off | off |
| Targets | **several**, from the same list as the LFOs | none |
| Env attack | 0.005–1 s | 0.05 s |
| Env decay | 0.01–1.5 s | 0.25 s |
| Sustain | 0–1 | 0.5 |
| Release | 0.02–2 s | 0.35 s |
| Amount | 0–1.5 | 0.5 |
| Band | Bass, Mid, Treble, Level | Bass |
| Gate threshold | 0–1 | 0.25 |
| Sustain tracks band | on/off | off |
| Retrigger | on/off | **on** |

**FR-A.2.4** Unlike an LFO, an envelope may drive **multiple** targets
simultaneously, including LFO rate and depth.

**FR-A.2.5** An envelope peaks at the **strength of the beat that
triggered it**, in the manner of a velocity-sensitive instrument: a soft
hit opens it part-way, and only a strong accent drives it to full.

**FR-A.2.6** With **Retrigger** on, a new beat during sustain or release
restarts the attack.

---

## Appendix B — Custom shaders

**FR-B.1** When a shader style is active, a **GLSL** tab exposes that
style's fragment source for editing.

**FR-B.2** The user can **Apply shader** to run their edited source live,
and **Revert** to restore the built-in source.

**FR-B.3** Compilation errors are reported to the user rather than
failing silently; the previous working visualization continues.

**FR-B.4** A custom shader is saved as part of a preset (FR-16.1) and is
restored with it.

---

## Appendix C — Performance behaviour

**FR-C.1** The fluid styles monitor their own frame rate and reduce
quality automatically after sustained low performance (FR-13.11), unless
**Auto quality** is switched off.

**FR-C.2** Automatic downgrade only ever lowers quality; it never raises
it above the user's chosen tier.

**FR-C.3** Quality can also be set manually across five tiers, so the
user can trade fidelity for frame rate on any device.

**FR-C.4** Every parameter is adjustable while the visualization runs;
no change requires a restart of playback or of the visualization.
