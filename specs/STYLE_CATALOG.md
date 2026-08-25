# Synesthesia — Style Catalog (CANONICAL, merged v2)
Supersedes specs/STYLE_CATALOGUE.md (merged in full) · Date 2026-08-25
Sources: two research agents (Shadertoy canon w/ verified IDs · projectM Cream-of-the-Crop corpus 9,795 presets · STAELLA/Spectrolizer/Avee catalogues · Resolume taxonomy · demoscene)
Contract (owner law): HIGH-END ONLY. Cost classes **base** / **high** / **ultra** (SPEC §2.4 canonical; legacy docs' MID/HIGH/CINEMATIC ≙ these). `base` = guaranteed 60fps@1080p on SD 7-series / Dimensity 8000-class. No sub-baseline "lite" paths anywhere; nothing basic ships.
Tier-tag equivalence for old notes: MID ≙ base · HIGH ≙ high · CINEMATIC ≙ ultra.

## A. v1 assembly plan of record (≥28 looks)
| Source | Looks |
|---|---|
| New 3D flagships (SPEC §3.2) | 5 |
| Top-15 new styles (§D) | 15 |
| Fluid trio re-grades (Fluid/Curl Flow/Water) | 3 |
| Cymatics authored substyles | 2 |
| Re-graded legacy shader scenes kept | ≥3 |

## B. Genre taxonomy (16 genres)
| Genre | Look | Ref (Shadertoy ID) | Audio map | Cost | Tech |
|---|---|---|---|---|---|
| Psychedelic feedback | zoom-decay warp trails | Geiss lineage | bass→zoom, treble→warp | base | ping-pong tex |
| Fractal flythrough | Kali-fold infinite detail | Apollonian `4ds3zn`, Star Nest `XlfGRj` | beat→fold jump | base→ultra | frag iter |
| Raymarch landscape/clouds | FBM terrain, god rays | Elevated `MdX3Rr`, Clouds `XslGRr` | bass→fog density | ultra | sphere-trace |
| Space nebula/starfield | layered dust parallax | Star Nest `XlfGRj`, Dusty Nebula `MsVXWW` | sub-bass→layer velocity | base | domain-warp |
| Plasma/lava-lamp | metaball blobs | Plasma Globe `XsjXRm`, Flame `MdX3zr` | band→blob radius | base | metaballs |
| Reaction-diffusion | coral growth | Expansive RD `4dcGW2` | peaks inject chemical | base@½res | Gray-Scott |
| Tiling/truchet/hex | maze tiles, hex grids | Truchet Tentacles `ldfGWn`, Neon Hexagons `MsVfz1` | beat→tile flip | base | pure frag |
| Kaleidoscope/mandala | n-fold symmetry | Seigaiha `WdtGWf` | mids→segment count | base | post mirror |
| Tunnel/wormhole | velocity corridor | Warp Tunnel `XtdGR7`, Disco `XstfzB` | BPM→speed, snare→hue | base | polar frag |
| Analog decay CRT/VHS | scanlines, chroma bleed | metaCRT `4dlyWX`, Glitch `XtyXzW` | transients→glitch rows | base–high | post pass |
| Halftone/ASCII/print | dot screens, glyphs | mrange ASCII series | luma→glyph density | base | post pass |
| Neon cityscape/rain | wet streets, signage | Drive Home Rain `tdXBRs`, Tokyo `Xtf3zn` | hi-hat→rain streaks | high | frag+layers |
| Fire/smoke | curl-noise embers | Campfire `Wtc3W2`, Smoke and Fire `3dXfW2` | kick→flare height | high | FBM/curl sim |
| Y2K liquid chrome | iridescent blob metal | Liquid Metal `3tGXz3`, Bismuth `WdVXWy` | bass→tension wobble | high | SDF+envmap |
| Wireframe/blueprint | vector glow grids | Neon Road `WllSDM`, Steel Lattice `4tlSWl` | notes→node pulse | base | line mesh |
| Bioluminescence | jellies, plankton trails | Jellyfish `ttdSWN` | transients spawn particles | high | GPU particles |

## C. Extended candidate pool (34, incl. judged five)
1 Neon Terrain Flyover — ridgelines rush past; bass=height, kick=bob, treble=shimmer · RM heightfield (`MdX3Rr`) · base hero
2 Fractal Warp Tunnel — kali corridor; sub=radius pulse, snare=palette flip · RM fractal DE · high
3 Bass Vortex Galaxy — spiral arms wind tighter with energy; vocal=core glow · particles+curl noise (`stBcW1`) · base
4 Spectral Ocean — swells shaped by spectrum history; treble=sparkle · RM sea+FFT heightfield (`Ms2SD1`) · high
5 Cymatic Cathedral — Chladni ripples across nave walls; bass=node count · SIM+instanced columns · high
6 Aurora Veil — volumetric curtains over ridge · thin-volume cheat · base
7 Storm Cell — cumulus god-rays, lightning on downbeats (`MdGfzh` lineage) · ultra
8 Analog Dunes — tungsten desert flyby, grain/halation · domain-warp+post · base
9 Glacier Prism — iridescent glass cave caustics · SDF+refraction · high
10 Ink Nebula — ink blooming in zero-g · SIM 256² NS · base
11 Liquid Chrome — mirror blob swallows reflections; bass=merge/split · metaball SDF · high
12 Ferro Bloom — ferrofluid spikes per transient · heightfield+rim · base
13 Plasma Choir — vorticity flame tendrils (`4tGfDW`) · high
14 Silk Storm — cloth-like fluid tears on drops · SIM+compute · ultra
15 Outrun Grid — retro highway, BPM-synced strobes · instanced quads · base
16 Truchet Transit — tile kaleidoscope rotating per bar (`7lKSWW`) · base
17 Datastream Rain — glyph rain forms waveform silhouettes · atlas+trails · base
18 Wormhole Slalom — gates fly past; near-miss whoosh on snares · boxes+motion blur · high
19 Supernova Shell — kick detonates shockwave shell · 100k particles · high
20 Ringed Giant — gas giant rings; DOF focus rides melody · rings+sphere · high
21 Gravity Lens — black hole warps starfield; disk spins with tempo · lensing RM · ultra
22 Oscilloscope Ribbon — true waveform as glossy 3D ribbon sculpture · line strip · base
23 Spectrogram Canyon — fly over your song's spectrogram terrain · FFT-texture heightfield · base
24 Mandala Petals — radial FFT petals; key change rotates palette · polar shader · base
25 Organ Pipes — pipe heights=bands; motes ride reverb tails · boxes+fog · high
26 CRT Chapel — phosphor triads, halation, curvature · post skin · base
27 VHS Reverie — tape warble, chroma bleed, glitch on drops · uv-jitter post · base
28 Ink Brush Calligraphy — strokes land flourishes on downbeats · trail-buffer particles · high
29 Glyph Rain — generative typeforms assemble/dissolve with vocals · font atlas+dissolve · high
30 Prism Shatter — world fractures into refracting shards on hits · shard mesh+refraction · ultra
31 Mandelbulb Sanctum — cathedral-interior dive; power morphs with harmonics · RM fractal · ultra
32–34 = covered by §D entries 13–15 (Pixel Sort Decay, Voxel Wave City, Scope Ring).

## D. TOP-15 new styles (wow-per-cost)
1 **Seigaiha Mandala** — wave-scale radial bloom; band→ring depth, beat→scale pop · base · (`WdtGWf`)
2 **Star Nest Drift** — multi-layer warp starfield; sub-bass→layer velocity · base · (`XlfGRj`)
3 **Liquid Chrome Blob** — iridescent mercury metaball; bass wobbles normals · base→ultra · (`3tGXz3`)
4 **Reaction Coral** — RD growth paints itself; peaks seed pattern · base@½res · Gray-Scott (`4dcGW2`)
5 **Plasma Globe** — filaments to glass; treble→arc jitter · base · (`XsjXRm`)
6 **Neon Hex Pulse** — hex grid city; per-band cell brightness · base · (`MsVfz1`)
7 **Aurora Veil** — polar curtains sway on pads · base · FBM
8 **Truchet Runway** — endless tile-maze flight; BPM scroll, beat turns · base · (`ldfGWn`)
9 **Black Hole** — lensed accretion disk; spin follows energy · ultra · lensing raymarch (`tsBXW3`)
10 **Ink Marbling** — suminagashi curl advection; melody combs ink · high · curl sim
11 **ASCII Terminal** — scene as glyphs; luma→density · base · post pass
12 **Campfire Embers** — volumetric flame+sparks; kick flares · high · (`Wtc3W2`)
13 **Pixel Sort Decay** — data-bent corruption on transients · ultra · post (fake-smear below)
14 **Voxel Wave City** — skyline ripples as EQ columns · ultra · voxel raymarch (`4dfGzs`)
15 **Scope Ring** — circular oscilloscope hero · base · line mesh

## E. MilkDrop compat QA targets (corpus archetypes)
Parity-test against mass archetypes: zoom-decay psychedelic (~30%) · per-frame swirl fields · custom-wave rings · spectrum-bar centered · corridors · strobes · texture-wrapped feedback · MD2-shader fractal flights · shape-tunnels · mashup blends.

## F. AAA polish layer (NON-NEGOTIABLE product bar)
- Fixed post order: HDR render → soft-knee bright-pass → dual-Kawada mip-chain bloom → ACES tonemap → edge-only CA (<1px) → animated fine grain → **FlashBudget luminance pass (D-SAFE-1 stage 2, last before surface/encoder)** → vignette → optional ultra DOF (CoC bokeh sprites). Post-clamp effects must be non-additive or budget-aware.
- FX menu economics: cheap always-available (bloom 0.4 default, grain 3–5%, vignette, mirror/kaleido, feedback trails); mid tier adds halation + one-pass VHS/CRT; true pixel-sorting is flagship-only. **Law:** never all simultaneously; CA/glitch are transient-gated; restraint IS the aesthetic.
- Palette discipline: one graded LUT per scene; hues shift on musical SECTIONS, never continuously.
- Motion law: spring-damper smoothing on band energies (fast attack, release 3–4× slower); drive from onset grid + BPM clock so peaks land ON beats; anticipate via pre-roll ramps.
- Transitions: beat-quantized crossfades + shared-element morphs; never mid-bar cuts.
- Latency: audio→pixel <50 ms live; export locks determinism.

## G. Performance budgets @1080p60
| Budget | SD7-series / Adreno 642L-class (base) | Adreno 750-class (high/ultra) |
|---|---|---|
| Raymarch steps | 48–64 primary; volumes half-res + FSR upscale | 64–96 std; ultra 96–128 + soft shadows |
| Sim grids | fluid 128², wave 192², RG16F ping-pong | 256² std; ultra 512² + vorticity confinement |
| Instanced particles | 20–40k | 80–150k; ultra 300k+ |
| Fullscreen post passes | ≤4 | +grain/CA/DOF; ultra 8–10 |
| Escape hatch | render-scale 0.66 on RM scenes | temporal reprojection |

## H. Palette families (AMOLED + export-optimized)
Obsidian Neon (#000+magenta/cyan) · Cyberpunk Rain (teal+sodium orange) · Vaporwave Dusk (pink/violet/peach) · Y2K Chrome (silver/lilac/ice) · Acid Lime (black+chartreuse) · Biolum Abyss (navy+electric cyan-green) · Ember Forge (black/crimson/amber) · Blueprint (Prussian+cyan) · Gold Noir (black/gold/champagne) · UV Rave (UV+acid green).

## I. Recommended launch lineup (12, merged verdict)
Free signature trio (base): **Neon Terrain Flyover · Bass Vortex Galaxy · Seigaiha Mandala**
High: **Star Nest Drift · Liquid Chrome Blob · Aurora Veil · Fractal Warp Tunnel · Supernova Shell · Truchet Runway · Spectral Ocean** (after polish pass)
Ultra teasers: **Storm Cell · Gravity Lens**
Mix: 5×base / 5×high / 2×ultra; families span land/fluid/fractal/cosmos/audio-literal/mandala for varied reels. Rest = post-launch scene-pack cadence (subscription value).
Rotating weekly preview slot (free-tier taste per SPEC §7) draws from High class first.
