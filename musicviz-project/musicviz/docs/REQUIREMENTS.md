# MusicViz — Functional Requirements Specification

**Version described:** 1.2.0
**Status:** as-built — this describes the application as it currently
behaves.

## About this document

This document specifies **what MusicViz does**. It describes behaviour,
options, ranges and defaults exactly as the user encounters them, and
says nothing about how any of it works.

Every requirement is numbered `FR-<section>.<n>` so it can be referenced
from tickets and test plans. Notation:

- **Default** — the value a fresh install starts with.
- **Range** — the inclusive limits a control allows.
- Where a control is offered only on certain visual styles, those styles
  are named.

Section 25 lists **known gaps** — capabilities the product implies but
that the user cannot currently reach. It is part of the specification
because it defines where the product ends today.

---

## The picture

MusicViz is a music player whose screen is alive.

You point it at the music already on your phone — a folder of albums, a
handful of downloads, a lossless archive — and it plays them like any
player would: shuffle, repeat, a sleep timer, a ten-band equalizer,
speed and pitch that move independently of each other. What makes it a
different kind of player is what happens above the transport controls.
The whole screen becomes the music.

There are twenty-nine visual styles. Some are clouds of particles that
swarm, burst and fountain. Some are pure light: tunnels, fractals,
plasma, aurora, starfields, oscilloscopes. Three are simulations of
moving liquid — ink dropped into a current and dragged into swirls,
streams of light carried on invisible eddies, a water surface where every
beat drops a ring that spreads and glints. One plays the MilkDrop presets
that visualizer enthusiasts have been trading for two decades, and you
can bring your own.

None of it is a canned animation. The application is listening the whole
time — to the bass, to the mids, to the treble, to how hard each beat
lands relative to the rest of the track, to where you are in the song. It
can be told to study a track in advance and come back with its tempo, its
musical key, and a map of its sections.

Then it hands you the controls. All hundred and eighteen of them. Speed,
zoom, drift, kaleidoscope folds, palettes, trails, bloom, film grain,
glitch, fisheye, twenty-one colour palettes plus any gradient you care to
build yourself. Three low-frequency oscillators that can sweep any of
those parameters in time with the beat, and drive each other. Two
envelopes that fire on the beat and hold while the bass sustains — and
that open only as far as the beat was hard, so a soft hit moves them
gently and an accent throws them wide.

If that is more control than you want, one button randomizes everything
you have not locked, and another hands the whole decision to the
application: let it pick the look from the music, or let it hop through
styles and presets on the beat. Three hundred and nine presets ship with
it, and every look you build can be saved, filed into folders, mirrored
to a folder you can see in any file manager, and searched for by name
alongside your music.

When something looks right, you can keep it: a clean video at up to 4K,
thirty or sixty frames a second, in any of six shapes including vertical
and square, with the track's audio, handed straight to the share sheet.

And if fast flashing is a problem for you, one switch caps how quickly and
how strongly the screen is allowed to change — on the screen and in
anything you export. A second, separate switch calms the motion for
anyone who finds it uncomfortable.

It asks for one permission: to read the music on your device. It has no
account, no sign-in, and no network connection. Nothing you do in it
leaves your phone unless you export a video and share it yourself.

---

## 1. Product overview

**FR-1.1** MusicViz is an Android music player that renders a real-time,
audio-reactive visualization of the music it is playing, and can record
that visualization to a video file.

**FR-1.2** The application works entirely on the user's device. It
requires no account, no sign-in and no network connection. No usage data,
telemetry or analytics are collected or transmitted.

**FR-1.3** The application plays audio files held on the device. It does
not stream from online services, and it does not visualize audio played
by other applications.

**FR-1.4** The application runs on Android 8.0 and later, on devices with
hardware-accelerated graphics.

**FR-1.5** The application requests exactly one permission: access to the
audio on the device. It requests no microphone, camera, location,
contacts or network permission.

**FR-1.6** Until music access is granted, the Library shows an
explanatory message and an **Allow music access** button. Every other
area of the application remains usable.

---

## 2. Supported audio

**FR-2.1** The application plays the common audio formats, including MP3,
WAV, FLAC, OGG, M4A, AAC and Opus.

**FR-2.2** The application additionally plays **AIFF and AIFC** files.

**FR-2.3** When scanning a folder the user has added, files with these
extensions are recognised as music: `mp3`, `wav`, `flac`, `ogg`, `m4a`,
`aac`, `opus`, `wma`, `aiff`.

---

## 3. Getting around

**FR-3.1** The application has four main destinations, always reachable
from a bar at the bottom of the screen: **Home**, **Library**,
**Visuals**, **Settings**.

**FR-3.2** A **mini-player** sits above that bar whenever music is
loaded, showing the track title, play/pause, next, and a slim progress
bar. It is hidden when nothing is loaded.

**FR-3.3** Tapping the mini-player opens **Now Playing** — the
full-screen visualizer. Closing and reopening it never restarts or resets
the running visualization; it picks up exactly where it was.

**FR-3.4** A full-screen **Search** is available from Home and Library.

**FR-3.5** The back gesture unwinds in this order:

1. Now Playing open → close it
2. Search open → close Search
3. Library drill-in open → return to the list of groups
4. Any destination other than Home → return to Home
5. Home → leave the application

**FR-3.6** On opening, the application plays a short introduction
animation of about a second and a half. Tapping skips it. It plays once
per session and can be switched off (FR-20.12).

**FR-3.7** If the application closed unexpectedly last time, a
**Previous crash captured** message is shown on the next start, offering
**Copy** — which puts the details on the clipboard so the user can send
them on — and **Dismiss**.

---

## 4. Home

**FR-4.1** Home offers **Search**.

**FR-4.2** When a track is loaded, Home shows a **Resume** card that
opens Now Playing.

**FR-4.3** Home offers **Shuffle all**, which builds a shuffled queue
from up to the last 100 tracks played.

**FR-4.4** Home shows a **Recently played** row; tapping an entry plays
it.

**FR-4.5** Home shows a **Most played** row, each entry showing how many
times it has been played; tapping an entry plays it.

**FR-4.6** With no listening history yet, Home points the user to the
Library.

---

## 5. Search

**FR-5.1** One search box covers **tracks**, **music playlists** and
**visual presets** at once.

**FR-5.2** A query may contain several words. A result is shown only when
**every** word appears somewhere in it. Case is ignored, and partial
words match.

**FR-5.3** The same track never appears twice. Track results are limited
to **30**, and each category is headed with the number of matches found.

**FR-5.4** Tapping a track plays it. Each track also offers **Add to
queue**.

**FR-5.5** Tapping a preset applies that look immediately.

**FR-5.6** With nothing typed, the screen invites a search; with a query
that matches nothing, it says so.

---

## 6. Library

**FR-6.1** The Library has five tabs: **Tracks**, **Albums**,
**Artists**, **Playlists**, **Folders**.

**FR-6.2 (Tracks)** Everything on the device, together with anything the
user has added to their own list.

**FR-6.3 (Albums / Artists / Folders)** A list of groups showing how many
tracks each contains, opening into that group's tracks. **‹ Back** or the
back gesture returns to the list. Each group offers **Play all** and
**Shuffle**.

**FR-6.4** Every track offers:

| Action | Behaviour |
|---|---|
| Play next | Plays it straight after the current track |
| Add to queue | Adds it to the end of the queue |
| Add to library list | Adds it to the user's own list |
| Edit track info | Opens the track editor (FR-7) |

**FR-6.5 (Playlists)** Each playlist shows how many tracks it holds, and
can be opened to show them in order. A playlist can be **renamed** and
**played**, and any track in it can be moved **up** or **down**.

**FR-6.6 (Folders)** The music folders the user has added, with:

- **Add folder** — pick any folder on the device
- **Remove folder**
- **Rescan** — re-reads the added folders, showing progress

**FR-6.7** Adding the same music twice never produces duplicates.

**FR-6.8** Each track carries: title, artist, album, genre, year, track
number, comment, length, **tempo (BPM)**, **musical key**, and whether it
has been analyzed.

---

## 7. Track editor

**FR-7.1** The user can edit a track's **Title**, **Artist**, **Album**,
**Genre**, **Year**, **Track #** and **Comment / info**.

**FR-7.2** Genre offers quick choices — Electronic, Rock, Pop, Hip-Hop,
Jazz, Classical, Ambient, Other — and accepts anything typed.

**FR-7.3** Edits live inside the application. **The music files
themselves are never changed.** The editor says so.

**FR-7.4** Edited details appear everywhere the track is shown, and are
searchable.

---

## 8. Playback

**FR-8.1** Play, pause, next, previous, and seek anywhere in the track.

**FR-8.2** **Shuffle** can be turned on and off, and is remembered.

**FR-8.3** **Repeat** cycles off → repeat one → repeat all, and is
remembered.

**FR-8.4** The position can be scrubbed by dragging, or nudged forward
and back.

**FR-8.5** The queue grows through **Play next** and **Add to queue**,
from any track or search result.

**FR-8.6** Playback settings, all remembered:

| Setting | Range | Default |
|---|---|---|
| Speed | 0.5×–2× | 1× |
| Pitch | −6 to +6 semitones | 0 |
| Skip silence | on/off | off |
| Pause when unplugged | on/off | **on** |
| Keep screen on | on/off | off |
| Auto-resume last track | on/off | off |

**FR-8.7** Speed and pitch are independent: slowing a track down does not
lower it, and transposing it does not slow it.

**FR-8.8** **Pause when unplugged** stops playback when headphones are
disconnected.

**FR-8.9** **Auto-resume last track** has the last track ready to play
when the application opens, without starting it, and without counting as
a play.

**FR-8.10 (Sleep timer)** **Off, 15, 30, 45 or 60 minutes**, with a live
countdown while it runs. **Off** cancels a running timer. The chosen
duration is remembered; a running timer is not — it never resumes after
the application is closed.

**FR-8.11** The application remembers what has been played and how often,
which is what fills the Home rows and feeds Shuffle all.

---

## 9. Equalizer

**FR-9.1** A graphic **equalizer** with a master on/off switch. The
number of bands, their labels and their limits come from the device, and
are typically ten.

**FR-9.2** The device's equalizer presets are offered as one-tap choices.
Once bands are moved away from a preset, the setting reads **Custom**.

**FR-9.3** **Bass boost** — 0 to 100%.

**FR-9.4** **Loudness** — a gain control shown in dB.

**FR-9.5** All controls grey out while the equalizer is switched off.

**FR-9.6** On a device that offers no audio effects, the panel reads
**"Not supported on this device"** and offers nothing further. This is
not presented as an error.

---

## 10. Sound quality

**FR-10.1** Now Playing labels the current track **BIT-PERFECT**,
**LOSSLESS** or **LOSSY**.

**FR-10.2** Where known, it also shows the **format**, the **file type**,
the **source** (sample rate, channels, bit depth), the **bitrate**, and
what is actually being **delivered to the device**.

**FR-10.3** **BIT-PERFECT** appears only when the track reaches the
device exactly as it was recorded — a lossless file, at its original
sample rate, with its channels intact and nothing altered on the way.

---

## 11. Now Playing

**FR-11.1** The visualization fills the screen, behind the controls.

**FR-11.2** Tapping the picture hides the controls, and tapping again
brings them back — so the visuals can be watched uninterrupted.

**FR-11.3** The controls are: close, the track title and artist, a seek
bar with elapsed and total time, and the transport row — shuffle,
previous, play/pause, next, repeat.

**FR-11.4** A **Visuals** button opens the visuals hub.

**FR-11.5** An **Auto** button cycles three modes:

| Mode | Behaviour |
|---|---|
| **Auto: off** | The chosen look stays until changed |
| **Auto: random** | The look changes by itself (FR-17) |
| **Auto: smart** | The application picks the style from the music (FR-18) |

**FR-11.6** The two automatic modes are mutually exclusive: switching one
on switches the other off.

**FR-11.7** Opening and closing Now Playing never interrupts the
visualization.

---

## 12. Visual styles

**FR-12.1** There are **29 visual styles**, in four groups, chosen from
**Visuals › Styles**.

**FR-12.2 (Particles — 5)** Nebula, Bursts, Swarm, Fountain, Orbits —
clouds and streams of individual points that gather, scatter and fall.

**FR-12.3 (Shaders — 20)** Julia, Tunnel, Mandel, Kaleido, Plasma, Bars,
Ring, Scope, Liss, Warp, Grid, Voronoi, Metaballs, Ripples, Starfield,
Waves, Hexgrid, Spiral, Aurora, Solar — full-screen patterns of pure
light, from spectrum bars and oscilloscope traces to fractals, tunnels
and aurorae.

**FR-12.4 (Fluid — 3)**

- **Fluid** — colour poured into moving liquid: it spreads, curls and is
  dragged into swirls by currents the music stirs up, can glow, and can
  throw shafts of light.
- **Curl Flow** — streams of light carried along invisible eddies,
  leaving trails behind them.
- **Water** — a water surface seen from above, where beats drop rings
  that spread and cross, bending and catching the light.

**FR-12.5 (MilkDrop — 1)** Plays MilkDrop presets, including ones the
user brings (FR-15).

**FR-12.6** Changing style takes effect immediately.

**FR-12.7** On a device that cannot run the MilkDrop style, its tab reads
**"MilkDrop engine unavailable on this device"** and offers nothing
further. Every other style remains available.

---

## 13. Customization

**FR-13.1** There are **118 adjustable visual controls**, in **Visuals ›
Customize**, grouped as **Motion, Shape, Behavior, Color, FX, Fluid**,
with a **GLSL** group added when a shader style is showing.

**FR-13.2** Every adjustment is visible immediately on the live picture.

**FR-13.3** Controls that a style cannot express are **hidden on that
style** rather than shown doing nothing. Where a control matters only for
certain styles but is shown everywhere, its name says which — "Trails
(particle scenes)", "Glow (fluid)", "MilkDrop palette tint".

**FR-13.4** Settings carry across styles, so a look built on one style
can be taken to another.

### 13.5 Motion

| Control | Range | Default |
|---|---|---|
| Speed | 0.05–4 | 1 |
| Zoom | 0.3–3 | 1 |
| Rotation | −3 to 3 | 0 |
| Sway | 0–1 | 0 |
| Drift X | −1 to 1 | 0 |
| Drift Y | −1 to 1 | 0 |
| Beat pulse | 0–1 | 0 |
| Beat shake | 0–1 | 0 |
| Endless zoom | on/off | off |
| Dive speed | 0.05–1.2 | 0.3 |

Rotation sets how fast the picture turns, not where it sits: the image
keeps turning at the chosen rate.

### 13.6 Shape

| Control | Range | Default |
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
| Particle size | 0.3–2.5 | 1 |

### 13.7 Behavior

| Control | Range | Default |
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

**Audio drive** sets how strongly the music moves the picture at all;
**Beat response** sets how much of that comes from beats specifically.
The three **gain** controls rebalance which part of the music does the
driving. **Reactivity attack** and **decay** set how quickly the visuals
answer a change and how slowly they settle afterwards.

This group also holds **Scene transition** (FR-19) and **Scene
intelligence** (FR-18).

### 13.8 Color

| Control | Range | Default |
|---|---|---|
| Palette | 21 built-in palettes, or any saved custom palette | Spectrum |
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

**FR-13.8.1** The 21 built-in palettes: Spectrum, Neon, Fire, Ocean,
Mono, Candy, Forest, Aurora, Sunset, Ice, Vapor, Toxic, Royal, Blush,
Copper, Mint, Galaxy, Cherry, Cyan, Magenta, Yellow.

**FR-13.8.2** MilkDrop presets bring their own colours, so the palette
reaches them as a **tint** — an optional pull toward the chosen colours —
rather than replacing them. It starts at zero, so presets already saved
look exactly as they did, and it keeps each preset's own light and shade
intact.

### 13.9 Screen effects

Available on every style.

| Control | Range | Default |
|---|---|---|
| Chromatic aberration | 0–1 | 0 |
| Vignette | 0–1 | 0 |
| Scanlines | 0–1 | 0 |
| Film grain | 0–1 | 0 |
| Glitch | 0–1 | 0 |
| Fisheye | −1 to 1 | 0 |
| Strobe | 0–1 | 0 |

### 13.10 Settings fade

**FR-13.10.1 Fade time** — 0–5 s, default 0. Slider moves and loaded
presets glide into place over this time instead of jumping.

### 13.11 Fluid controls

Shown on the styles they affect.

**Quality (Fluid, Water)**

| Control | Options | Default |
|---|---|---|
| Quality | Ultra, High, Medium, Low, Min | Medium |
| Auto quality | on/off | **on** |

**Auto quality** steps the quality down by itself if the picture has been
struggling, and never above what the user chose.

**Character (Fluid)**

| Control | Range | Default | Effect |
|---|---|---|---|
| Fluid curl | 0–50 | 30 | How much the liquid swirls rather than flows straight |
| Motion fade | 0–4 | 0.2 | How quickly movement dies away |
| Fluid fade | 0–4 | 1 | How quickly colour dissipates |
| Chromatic aging | 0–1 | 0.3 | How much colours drift apart as they fade |
| Pressure | 0–1 | 0.8 | How tightly the liquid holds together |
| Solver iterations | 8–40 | 20 | Detail of the motion, against the work the device must do |

**Emitters (Fluid, Water)**

| Control | Range | Default |
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

**Beat pattern** decides where each beat lands — in the middle, around a
ring, anywhere, or placed by pitch. **Stirrers** keep the liquid moving
between beats. **Bass pump** swells the whole picture with the low end;
**Treble sparkle** scatters bright flecks on high notes.

**Journey (Fluid, Curl Flow, Water)**

| Control | Range | Default |
|---|---|---|
| Path | Orbit, Lissajous, Rose, Bloom, Drift | Lissajous |
| Spawn points | 1–8 | 3 |
| Progression | 0–1 | 1 |
| Catch points | 0–4 | 2 |
| Catch pull | 0–3 | 1 |
| Catch radius | 0.03–0.3 | 0.12 |

Colour is born at the spawn points and drawn toward the catch points, so
the picture travels rather than sitting still. **Progression** sets how
far the journey is reshaped as the song plays on.

**Particles (Fluid, Curl Flow)**

| Control | Range | Default |
|---|---|---|
| Particle layer *(Fluid)* | on/off | **on** |
| Particle drag | 0.02–1 | 0.5 |
| Particle life | 1–20 s | 6 s |
| Particle brightness *(Fluid)* | 0–2 | 1 |
| Ink layer *(Fluid)* | on/off | **on** |

Lower **drag** lets particles keep their momentum and streak.

**Look (Fluid)**

| Control | Range | Default |
|---|---|---|
| Shading (embossed ink) | on/off | **on** |
| Glow (fluid) | on/off | **on** |
| Fluid glow | 0.1–2 | 0.8 |
| Glow threshold | 0–1 | 0.6 |
| Sunrays | on/off | **on** |
| Sunrays weight | 0.3–1 | 1 |

**Music routing (Fluid)**

| Control | Range | Default |
|---|---|---|
| Curl from mids | 0–1 | 0.5 | 
| Glow from loudness | 0–1 | 0.5 |
| Fade when quiet | 0–1 | 0.6 |

These decide how much of the picture's swirl, glow and clearing comes
from the music rather than staying constant — quiet passages can wash the
canvas clean.

**Water surface (Water)**

| Control | Range | Default |
|---|---|---|
| Wave speed | 0.2–2 | 1 |
| Damping | 0.9–0.999 | 0.985 |
| Ripple strength | 0–2 | 1 |
| Depth | 0–1 | 0.6 |
| Specular | 0–1 | 0.7 |
| Flow drift | 0–1 | 0.3 |

### 13.12 Liquid effects on any style

**FlowField (all styles)** — bends any style, including the flat and
particle ones, as though it were being carried on a current.

| Control | Range | Default |
|---|---|---|
| FlowField enabled | on/off | off |
| Flow strength | 0–1 | 0.35 |
| Flow force | 0–3 | 1 |
| Flow curl | 0–50 | 25 |
| Particles ride the field | on/off | **on** |

**Water ripples (every style except Water)** — lays beat-driven ripples
over whatever else is showing.

| Control | Range | Default |
|---|---|---|
| Water ripples enabled | on/off | off |
| Wave speed | 0.2–2 | 1 |
| Damping | 0.9–0.999 | 0.985 |
| Ripple overlay strength | 0–1 | 0.4 |
| Ripple glint | 0–1 | 0.3 |

The overlay is not offered on the Water style, which already has a
rippling surface of its own.

### 13.13 Advanced fluid injection

**FR-13.13.1** On the Fluid style, an advanced user can supply their own
definitions for how force and colour enter the liquid, apply them live,
and **Reset to built-in** at any time.

---

## 14. Palettes

**FR-14.1** Either palette slot can hold a built-in palette (FR-13.8.1)
or one the user has made.

**FR-14.2** The palette maker builds a gradient from two controls: where
the colour starts, and how far around the spectrum it travels — with a
live preview of the result.

**FR-14.3** It offers:

| Action | Behaviour |
|---|---|
| Apply gradient | Puts the gradient into the current palette slot |
| From current | Loads what the slot is using into the editor |
| Save palette | Saves the gradient under a chosen name |

**FR-14.4** Saved palettes are listed, and can be **edited** or
**deleted**.

**FR-14.5** Saved palettes sit alongside the built-in ones, so picking a
palette you made is the same gesture as picking Neon.

**FR-14.6** A slot can take its starting colour from a built-in palette
and its spread from a custom one, or the other way round.

**FR-14.7** Custom colours are stored inside presets, so a preset always
comes back in the colours it was saved with.

---

## 15. MilkDrop presets and textures

**FR-15.1** The user can bring in `.milk` preset files from the device
with **Load .milk file**.

**FR-15.2** Imported presets are listed under **Your .milk presets** and
can be applied. When there are none, the screen says so.

**FR-15.3** The user can bring in images for MilkDrop to use, with
**Import images** in **Visuals › Textures**.

**FR-15.4** Each imported image offers **Use**, which puts it to work in
the MilkDrop style. Images can be removed.

**FR-15.5** Saving a preset while MilkDrop is showing also writes a
`.milk` file, so the look can be taken elsewhere.

---

## 16. Presets

**FR-16.1** A preset holds the complete look: the **style**, all **118
controls**, the reactivity settings, any custom code, and any custom
colours.

**FR-16.2** The current look is saved under a chosen name with **Save
current as…**.

**FR-16.3** Presets live in **folders** the user creates, and the
destination folder is chosen at save time.

**FR-16.4** Each saved preset offers:

| Action | Behaviour |
|---|---|
| Apply | Loads the look |
| ♥ | Adds it to the visual playlist |
| 🗑 | Deletes it |

**FR-16.5** **309 presets ship with the application**: 12 crafted looks —
Chill, Punchy, Hypno, Vivid, Retro, Glitch, Dream, Warp, Prism, Noir,
Strobe, Deep — applied to each of the 25 particle and shader styles, plus
9 built for the liquid styles: Inkdrop, Vortex, Spectrum, Nebula, Lava,
Storm and Journey for Fluid, Rainfall for Water, and Streams for Curl
Flow.

**FR-16.6** Bundled presets can be applied, added to the visual playlist
and picked by Random mode, but cannot be deleted or overwritten.

**FR-16.7** Bundled presets are named `style · Look`. That separator
cannot appear in a name the user gives, so the two can never be confused.

**FR-16.8** Bundled presets for the current style are listed separately
from the user's own.

**FR-16.9 (Preset folder)** The user can nominate a folder on the device;
saved presets are copied there too, so their own filing is visible in any
file manager. The choice can be cleared, after which presets are kept
only inside the application.

**FR-16.10** Presets are searchable (FR-5.1), and applying one from
Search takes effect immediately.

---

## 17. Automatic looks

**FR-17.1** Random mode changes the look by itself while music plays.

**FR-17.2** Its settings:

| Setting | Range | Default |
|---|---|---|
| Enabled | on/off | off |
| Interval | seconds | 20 s |
| Change on beat | on/off | **on** |
| Include styles | on/off | **on** |
| Include presets | on/off | **on** |
| Include `.milk` presets | on/off | off |
| Randomize colours | on/off | off |

**FR-17.3** With **Change on beat**, changes land on musical moments
rather than arriving at arbitrary points.

**FR-17.4** Random mode and the visual playlist cannot both run.

### 17.5 Visual playlist

**FR-17.5.1** The user can build an ordered playlist of styles, presets
and `.milk` presets, adding to it with ♥ (FR-16.4).

**FR-17.5.2** It can be switched on, given a step **interval** (default
30 s), or set to advance **intelligently**, moving with the music instead
of on a timer.

**FR-17.5.3** Entries can be removed.

### 17.6 Randomize and lock

**FR-17.6.1** **⚄ Randomize unlocked** rerolls the customization controls
in one tap.

**FR-17.6.2** Any control can be **locked** by tapping its name, and
locked controls are left alone by the randomizer. Locking works on
sliders and on choices alike — Palette, Palette 2, Particle shape, Beat
pattern and Path.

---

## 18. Scene intelligence

**FR-18.1** Three modes:

| Mode | Behaviour |
|---|---|
| **manual** | The chosen style is always used |
| **suggest** | The application recommends a style; the user decides |
| **auto** | The recommendation is applied automatically |

**FR-18.2** Recommendations come from the track's tempo, its energy and
its brightness. They are intentionally simple and always overridable.

**FR-18.3** An explicit choice by the user always beats a recommendation.

---

## 19. Transitions

**FR-19.1** Five ways to change between styles: **cut, fade, melt,
slide, zoom**. Default: fade.

**FR-19.2** Transition **duration** — 0.3–5 s, default 1.2 s.

**FR-19.3 (Preset morph)** A loaded preset can ease into place over a
number of beats instead of snapping: **0–16 beats**, default **4**, where
0 means snap.

**FR-19.4** Preset morphing is counted in beats and so follows the music;
the settings fade (FR-13.10.1) is counted in seconds. They are
independent.

---

## 20. Appearance

**FR-20.1** **26 themes**: Lapis (default), Malachite, Clear Quartz, Rose
Quartz, Sugilite, Amethyst, Kyanite, Onyx, Midnight, Neon, Sunset,
Forest, Mono, Ocean, Violet, Ember, Candy, Slate, Rose, Mint, Cobalt,
Sand, Grape, Ink, Light, Paper.

**FR-20.2** Two are light — **Light** and **Paper**; the other 24 are
dark.

**FR-20.3** **Bar opacity** — 20–100%, default 72%. How see-through the
panels and sheets are.

**FR-20.4** **Player position** — **Top** or **Bottom**, default Bottom.
The controls sit opposite.

**FR-20.5** **Corner style** — **Sharp**, **Rounded** (default) or
**Pill**.

**FR-20.6** **Accent intensity** — 50–150%, default 100%. Makes the
theme's colours more muted or more vivid.

**FR-20.7** **Background dim** — 0–60%, default 0%. Darkens the
background.

**FR-20.8** **Follow system light/dark** — default off. Switches to the
Light theme whenever the phone is in light mode.

**FR-20.9** **White font** — default off. Forces all text to pure white.
It does nothing on the two light themes, where it would be unreadable,
and says so.

**FR-20.10** **Compact mini-player** — default off. A slimmer bar.

**FR-20.11** **Clear-overlay Visuals menu** — default off. Shows the
visuals menu as plain text over the live picture, so the effect of every
adjustment is visible while it is being made. It can also be toggled from
inside the visuals menu.

**FR-20.12** **Boot animation** — default on. Turns off the opening
animation (FR-3.6).

---

## 21. Visual safety and comfort

**FR-21.1 (Safe visuals)** A switch — default **off** — that limits how
fast and how strongly the whole screen may flash. Left off, everything
looks exactly as it was saved.

**FR-21.2** **Maximum flashes per second** — 1–9, default **3**. The
setting notes that published guidance puts the general limit at three
flashes per second.

**FR-21.3** **Maximum flash strength** — 0–100%, default **25%**. The
largest change in overall brightness a flash may make; 0 removes
full-screen flashing entirely.

**FR-21.4** **Allow invert and solarize** — default **off**. These
reverse the whole picture at once; the setting keeps them available for
users who want them.

**FR-21.5 (Reduced motion)** A separate switch, default off, that slows
movement, shake, drift and rotation. It is there for motion discomfort,
and is deliberately independent of Safe visuals, which is about flashing.

**FR-21.6** Both apply to **exported video** as well as to the screen, so
a saved clip is as safe as the screen was. The settings screen says so.

**FR-21.7** The limits are applied last, after everything else — presets,
randomization, automation — so no combination can slip past them while
Safe visuals is on.

---

## 22. Listening to the music

**FR-22.1** While music plays, the application follows the sound
continuously: the level across the frequency range, the shape of the
wave, overall loudness, bass, mid and treble energy, note onsets, beats,
tempo, and how bright or dark the sound is. All of it drives the picture
as it happens.

**FR-22.2** Beats are graded by how hard they land relative to the rest
of the track, so a soft hit produces a smaller response than an accent.
Hits that fall between beats are reported in their own right, so real
detail is not lost to the beat grid.

**FR-22.3** The application also knows where it is between one beat and
the next, so movement can anticipate a beat and land on it rather than
only reacting after it.

**FR-22.4** Beat tracking starts fresh with each track: one song's rhythm
is never carried into the next.

**FR-22.5** A track can be studied ahead of playback to find its
**tempo**, its **musical key** and a map of its **sections**, with
progress shown while it runs. A whole playlist can be studied in one go.

**FR-22.6** Key is reported in the usual form — "A minor", "F# major".

**FR-22.7** Studied tracks are marked as analyzed and show their tempo
and key in the library.

**FR-22.8** How far through the song it is, and which section it is in,
are available to the visuals — so a look can develop over the course of a
track.

**FR-22.9** Results are kept for the last **15 tracks** so they need not
be worked out twice. Settings shows how much space they take and offers
**Clear**.

**FR-22.10 (Beat sensitivity)** Two controls tune what counts as a beat:

| Setting | Range | Default |
|---|---|---|
| Beat sensitivity | 1.5–6 (higher = fewer beats) | 2.5 |
| Minimum gap between beats | 200–1200 ms | ≈333 ms |

**FR-22.11** Two one-tap settings are offered: **Slow track** and
**Default**.

**FR-22.12** Changing the sensitivity re-reads tracks already studied at
the new setting, without studying them again.

---

## 23. Video export

**FR-23.1** The visualization can be saved as an **MP4 video with the
track's audio**.

**FR-23.2** The video is produced separately from the live screen, so the
controls never appear in it and the result does not depend on how the
device was performing while it was made.

**FR-23.3 Quality** — **720p**, **1080p** or **4K**.

**FR-23.4 Frame rate** — **30** or **60** frames per second.

**FR-23.5 Shape** — **16:9**, **9:16**, **1:1**, **4:5**, **4:3** or
**21:9**, covering widescreen, vertical, square and cinematic.

**FR-23.6** The button states exactly what will be made — "Render 1080p
9:16 60fps".

**FR-23.7** Choosing 4K warns that it depends on the device, and the
application **steps down automatically** rather than failing if the
device cannot manage it.

**FR-23.8** Two destinations:

| Destination | Behaviour |
|---|---|
| Default | Into the device's Videos, under **Movies/MusicViz** |
| Chosen folder | The user picks where it goes and what it is called |

**FR-23.9** Progress is shown while it works, and it can be
**cancelled**.

**FR-23.10** When finished, the application says where the file went and
offers **Upload to Drive / Share**, handing it to the phone's share
sheet.

**FR-23.11** If it fails, the reason is shown.

**FR-23.12** The video matches what was on screen — the same style, the
same settings, the same effects, the same safety limits.

---

## 24. About and privacy

**FR-24.1** Settings shows the application name and version.

**FR-24.2** The **privacy policy** can be read in the application.

**FR-24.3** It states that no analytics are collected, and that **nothing
leaves the phone unless the user exports a video and shares it**.

**FR-24.4** An **Open source licenses** screen carries the full notices
for everything the application includes.

---

## 25. Known gaps

Things the product implies, or half-offers, but that the user cannot
reach today. They are listed here because they mark the current edge of
the product; each is a candidate requirement rather than a fault in
anything above.

**GAP-1 — Playlists cannot be created.** A playlist can be renamed,
played, opened and reordered (FR-6.5), but nothing in the application
makes one or adds a track to one. The empty Playlists tab tells the user
to "build a queue in Now Playing and save it", and no such action exists.

**GAP-2 — Playlists cannot be deleted.**

**GAP-3 — The queue cannot be seen.** Tracks can be added to it
(FR-6.4), but there is no queue screen, so a queued track cannot be
checked, removed or jumped to.

**GAP-4 — Random mode and the visual playlist have no settings screen.**
Random mode can only be switched on from the Auto button (FR-11.5), which
uses the defaults for everything; the visual playlist can be added to
(FR-16.4) but cannot be switched on, timed, set to advance intelligently
or edited. Everything described in FR-17.2 and FR-17.5.2 works, but
cannot be reached.

**GAP-5 — "Beat pulse" does nothing on MilkDrop or the liquid styles.**
The control is shown on every style; only the particle and shader styles
answer it.

**GAP-6 — "Endless zoom" is shown on the liquid styles**, which have
nothing corresponding to it.

**GAP-7 — "Hue range" behaves differently on the liquid styles.** There,
values above 1 make no further difference, and 0 does not reduce the
picture to a single colour as it does elsewhere.

**GAP-8 — "Particle shape" does nothing on Fluid and Curl Flow**, whose
particles are always round, though the choice is still offered.

**GAP-9 — Album art is never shown anywhere.**

**GAP-10 — There are no notification, lockscreen or headphone-button
controls.** Playback can only be controlled from inside the application.

**GAP-11 — The application does not appear in the share sheet or in
"Open with" for audio files.** A track can only be reached by finding it
inside the application.

---

## Appendix A — Automation

### A.1 Oscillators

**FR-A.1.1** Three oscillators, each set up independently, sweep any
control back and forth on their own.

**FR-A.1.2** Each has:

| Setting | Options | Default |
|---|---|---|
| Enabled | on/off | off |
| Target | 44 choices | None |
| Shape | Sin, Tri, Saw, Sqr, S&H | Sin |
| Rate | 0.02–8 Hz | 0.5 Hz |
| Sync to BPM | on/off | off |
| Beat division | 0.25 (sixteenth) – 8 (two bars) | 1 beat |
| Depth | 0–1 | 0.3 |

**FR-A.1.3** With **Sync to BPM**, the cycle is measured in beats and
follows the music's tempo instead of a fixed speed.

**FR-A.1.4** They can be pointed at: Speed, Zoom, Rotation, Sway, Pulse,
Drift X, Drift Y, Warp, Ripple, Morph, Twist, Tile, Pixelate, Posterize,
Hue shift, Palette blend, Saturation, Brightness, Intensity, Bloom,
Temperature, Turbulence, Chroma AB, Vignette, Glitch, Fisheye, Particle
size, Trail length, Fluid curl, Fluid splat radius, Fluid splat force,
Fluid glow, Fluid fade, Catch pull, Catch radius, Flow strength, Ripple
amp and Ripple ovl — plus the six chaining choices below.

**FR-A.1.5 (Chaining)** An oscillator can drive the **rate** or **depth**
of a higher-numbered one, so 1 shapes 2 and 2 shapes 3. Pointing one back
at itself or at an earlier one is not offered, so a runaway loop cannot
be built.

### A.2 Envelopes

**FR-A.2.1** Two envelopes fire on the beat.

**FR-A.2.2** Each **opens on a detected beat** and **holds while the
chosen part of the music stays loud**, releasing when it drops away.

**FR-A.2.3** Each has:

| Setting | Range | Default |
|---|---|---|
| Enabled | on/off | off |
| Targets | **several**, from the same list as the oscillators | none |
| Attack | 0.005–1 s | 0.05 s |
| Decay | 0.01–1.5 s | 0.25 s |
| Sustain | 0–1 | 0.5 |
| Release | 0.02–2 s | 0.35 s |
| Amount | 0–1.5 | 0.5 |
| Band | Bass, Mid, Treble, Level | Bass |
| Gate threshold | 0–1 | 0.25 |
| Sustain tracks band | on/off | off |
| Retrigger | on/off | **on** |

**FR-A.2.4** Unlike an oscillator, an envelope can drive **several**
controls at once, including the oscillators' own rate and depth.

**FR-A.2.5** An envelope opens only as far as the beat that triggered it
was strong, the way a keyboard responds to how hard a key is struck: a
soft hit moves it a little, and only a real accent throws it wide.

**FR-A.2.6** With **Retrigger** on, a fresh beat restarts the envelope
even while it is still open.

---

## Appendix B — Custom visuals

**FR-B.1** While a shader style is showing, the definition behind it can
be opened and edited.

**FR-B.2** **Apply shader** runs the edited version live; **Revert**
restores the original.

**FR-B.3** A mistake is reported to the user rather than failing
silently, and the picture that was working keeps running.

**FR-B.4** A custom visual is saved inside a preset (FR-16.1) and comes
back with it.

---

## Appendix C — Performance

**FR-C.1** The liquid styles ease their own quality down if the picture
has been struggling (FR-13.11), unless **Auto quality** is switched off.

**FR-C.2** They only ever step down, never above what the user chose.

**FR-C.3** Quality can also be set by hand across five levels, so
sharpness can be traded for smoothness on any device.

**FR-C.4** Every control can be moved while the visuals are running.
Nothing requires the music, or the picture, to be restarted.
