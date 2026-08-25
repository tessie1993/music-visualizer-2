# Synesthesia — Style Catalog & AAA Bar (M1b-visual output)
Status: candidate pool for owner selection · Date 2026-08-25
Cost classes: MID = upper-mid device 60fps (Adreno 642L/Mali-G68) · HIGH = flagship-comfortable · CINEMATIC = flagship-only Settings opt-in ("Cinematic shaders")
Tier-tag equivalence: MID ≙ SPEC `base` · HIGH ≙ `high` · CINEMATIC ≙ `ultra` (SPEC §2.4 is canonical; no lite/degraded paths exist below Base — see SPEC performance floor).

## Positioning law
Synesthesia sells BEAUTY. Baseline target is the TOP of mid-tier; nothing ships below "looks premium at medium settings on an upper-mid phone." Cinematic class exists to make flagships feel like a superpower — never to strand mid-tier users with a broken-looking app.

## Candidate catalog (34)
### Judged from earlier shortlist
| # | Style | Look / audio mapping | Path | Class |
|---|-------|----------------------|------|-------|
| 1 | Neon Terrain Flyover | glowing ridgelines rush past; bass=height, kick=camera bob, treble=shimmer | RM heightfield (iq *Elevated* MdX3Rr) | MID — free hero |
| 2 | Fractal Warp Tunnel | kali-fold corridor; sub=radius pulse, snare=palette flip | RM fractal DE (Kali NlsXDH, Shane 4scXzn) | HIGH |
| 3 | Bass Vortex Galaxy | spiral arms wind tighter with energy; vocal=core glow | instanced particles + curl noise (mrange stBcW1) | MID — free |
| 4 | Spectral Ocean | swells shaped by spectrum history; treble=facet sparkle | RM sea + FFT heightfield (TDM Ms2SD1) | HIGH |
| 5 | Cymatic Cathedral | Chladni ripples across nave walls; bass=node count | SIM heightfield + instanced columns | HIGH |

### Land / atmosphere
6 Aurora Veil — volumetric curtains over dark ridge (RM thin-volume cheat) · MID
7 Storm Cell — cumulus + god rays, lightning on downbeats (reindernijhoff MdGfzh lineage) · CINEMATIC
8 Analog Dunes — tungsten desert flyby, animated grain/halation · MID (2026 analog trend)
9 Glacier Prism — iridescent glass cave, refraction+dispersion caustics · HIGH

### Fluid / matter
10 Ink Nebula — ink blooming in zero-g advected by velocity · SIM 256² NS · MID
11 Liquid Chrome — mirror blob swallowing reflections; bass=merge/split · metaball SDF · HIGH (2026 chrome trend)
12 Ferro Bloom — ferrofluid spikes erupt per transient · heightfield + rim light · MID
13 Plasma Choir — vorticity flame tendrils dance (Nimitz 4tGfDW) · HIGH
14 Silk Storm — cloth-like fluid sheets tear on drops · SIM+compute · CINEMATIC

### Tunnels / motion
15 Outrun Grid — retro highway, BPM-synced lane strobes · instanced quads · MID
16 Truchet Transit — truchet kaleidoscope rotating per bar (mrange 7lKSWW) · MID — free
17 Datastream Rain — glyph rain forming waveform silhouettes · text atlas + trails · MID
18 Wormhole Slalom — gates fly past; near-miss whoosh on snares · boxes + motion blur · HIGH

### Cosmos
19 Supernova Shell — kick detonates shockwave shell, debris orbits decay · 100k particles · HIGH
20 Ringed Giant — gas giant ring plane; DOF focus rides melody · rings+sphere · HIGH
21 Gravity Lens — black hole warps starfield; accretion disk spins with tempo · RM lensing · CINEMATIC

### Audio-literal
22 Oscilloscope Ribbon — true waveform extruded into glossy 3D ribbon sculpture · line strip · MID
23 Spectrogram Canyon — fly over your song's own spectrogram terrain · FFT-texture heightfield · MID
24 Mandala Petals — radial FFT petals breathe; key changes rotate palette · polar shader · MID
25 Organ Pipes — pipe heights=bands; dust motes ride reverb tails · boxes+fog · HIGH

### Retro surface layers (post-FX skins applicable OVER other scenes)
26 CRT Chapel — phosphor triads, halation, curvature · post chain · MID
27 VHS Reverie — tape warble, chroma bleed, tracking glitch on drops · uv-jitter post · MID

### Craft / generative
28 Ink Brush Calligraphy — strokes draw flourishes landing on downbeats · trail-buffer particles · HIGH
29 Glyph Rain — generative typeforms assemble/dissolve with vocals · font atlas + noise dissolve · HIGH

### Showpiece
30 Prism Shatter — world fractures into refracting glass shards on big hits · shard mesh+refraction · CINEMATIC
31 Mandelbulb Sanctum — slow cathedral-interior dive; power morphs with harmonic complexity · RM fractal · CINEMATIC

## A. AAA polish layer (product vs tech-demo — NON-NEGOTIABLE)
- Fixed post order: HDR render → soft-knee bright-pass → dual-Kawade mip bloom → ACES tonemap → edge-only subtle CA (<1px) → animated fine grain → vignette → optional Cinematic DOF (CoC bokeh sprites).
- Palette discipline: one graded LUT per scene; hues shift on musical SECTIONS, never continuously.
- Motion law: spring-damper smoothing on band energies (fast attack, release 3–4× slower); drive from onset grid + BPM clock so peaks land ON beats; anticipate via pre-roll ramps, don't merely react.
- Transitions: beat-quantized crossfades + shared-element morphs (light/color carries across); never mid-bar cuts.
- End-to-end audio→pixel latency target <50 ms live; export locks determinism (STAELLA two-mode lesson).

## B. Performance budgets @1080p60
| Budget | Adreno 642L (MID) | Adreno 750 (HIGH/CIN) |
|---|---|---|
| Raymarch steps | 48–64 primary; volumes at half-res+FSR upscale | 64–96 std; CIN 96–128 + soft shadows |
| Sim grids | fluid 128², wave 192², RG16F ping-pong | 256² std; CIN 512² + vorticity confinement |
| Instanced particles | 20–40k | 80–150k; CIN 300k+ |
| Fullscreen post passes | ≤4 | +grain/CA/DOF; CIN 8–10 |
| Escape hatch | render-scale 0.66 on RM scenes | temporal reprojection |

## C. Recommended launch lineup (12, ranked)
1 Neon Terrain Flyover (MID/free) · 2 Bass Vortex Galaxy (MID/free) · 3 Truchet Transit (MID/free) · 4 Ink Nebula (MID) · 5 Aurora Veil (MID) · 6 Liquid Chrome (HIGH) · 7 Fractal Warp Tunnel (HIGH) · 8 Supernova Shell (HIGH) · 9 Outrun Grid (MID) · 10 Spectral Ocean (HIGH, after polish pass) · 11 Storm Cell (CINEMATIC teaser) · 12 Gravity Lens (CINEMATIC teaser)
Mix: 6×MID (free tier shines), 4×HIGH, 2×CINEMATIC upsell. Families span land/fluid/fractal/cosmos/audio-literal for varied marketing reels. Remaining catalog = post-launch drops (scene-pack cadence supports subscription value).

## Free-tier signature three (per SPEC §7): #1, #3, + rotating preview slot (weekly premium taste).
