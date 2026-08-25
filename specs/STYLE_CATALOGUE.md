# Synesthesia — Style Catalogue v1 (research output → M6 build list)
Source: deep-dive agent survey (Shadertoy canon w/ verified IDs, projectM Cream-of-the-Crop corpus 9,795 presets, STAELLA/Spectrolizer/Avee catalogues, Resolume footage taxonomy, demoscene) · Aug 2026
Contract (owner law): HIGH-END ONLY. Cost classes are **base** / **high** / **ultra** — `base` = guaranteed 60fps @1080p on the upper-mid-tier reference device (SD 7-series / Dimensity 8000-class); no sub-baseline "lite" paths exist anywhere in the app. Every style authored to the premium grade; nothing basic ships.

## A. v1 catalogue assembly (≥25 looks — plan of record)
| Source | Looks |
|---|---|
| New 3D flagships (already specced §3.2) | 5 |
| Top-15 new styles (§D below) | 15 |
| Fluid trio re-grades (Fluid / Curl Flow / Water) | 3 |
| Cymatics authored substyles | 2 |
| Re-graded legacy shader scenes kept from golden renders | ≥3 |
| **Total** | **≥28** |

## B. Genre taxonomy (16 genres beyond the 3D five)
| Genre | Look | Ref (Shadertoy ID) | Audio map | Cost | Tech |
|---|---|---|---|---|---|
| Psychedelic feedback | zoom-decay warp trails | Geiss Feedback lineage | bass→zoom, treble→warp | SAFE | ping-pong tex |
| Fractal flythrough | Kali-fold infinite detail | Apollonian `4ds3zn`, Star Nest `XlfGRj` | beat→fold jump | SAFE→ULTRA | frag iter |
| Raymarch landscape/clouds | FBM terrain, god rays | Elevated `MdX3Rr`, Clouds `XslGRr` | bass→fog density | ULTRA | sphere-trace |
| Space nebula/starfield | layered dust parallax | Star Nest `XlfGRj`, Dusty Nebula `MsVXWW` | sub-bass→layer velocity | SAFE | domain-warp |
| Plasma/lava-lamp | metaball blobs | Plasma Globe `XsjXRm`, Flame `MdX3zr` | band energy→blob radius | SAFE | metaballs |
| Reaction-diffusion | coral/print growth | Expansive RD `4dcGW2` | peaks inject chemical | SAFE@½res | Gray-Scott |
| Tiling/truchet/hex | maze tiles, hex grids | Truchet Tentacles `ldfGWn`, Neon Hexagons `MsVfz1`, Voronoi `ldl3W8` | beat→tile flip | SAFE | pure frag |
| Kaleidoscope/mandala | n-fold symmetry | Seigaiha Mandala `WdtGWf` | mids→segment count | SAFE | post mirror |
| Tunnel/wormhole | velocity corridor | Warp Tunnel `XtdGR7`, Disco Tunnel `XstfzB` | BPM→speed, snare→hue | SAFE | polar frag |
| Analog decay CRT/VHS | scanlines, chroma bleed | metaCRT `4dlyWX`, Glitch `XtyXzW` | transients→glitch rows | SAFE–MID | post pass |
| Halftone/ASCII/print | dot screens, glyphs | mrange ASCII series | luma→glyph density | SAFE | post pass |
| Neon cityscape/rain | wet streets, signage | Drive Home Rain `tdXBRs`, Tokyo `Xtf3zn` | hi-hat→rain streaks | MID | frag+layers |
| Fire/smoke | curl-noise embers | Campfire `Wtc3W2`, Smoke and Fire `3dXfW2` | kick→flare height | MID | FBM/curl sim |
| Y2K liquid chrome | iridescent blob metal | Liquid Metal `3tGXz3`, Molten Bismuth `WdVXWy` | bass→tension wobble | MID | SDF+envmap |
| Wireframe/blueprint | vector glow grids | Neon Road `WllSDM`, Steel Lattice `4tlSWl` | notes→node pulse | SAFE | line mesh |
| Bioluminescence | jellies, plankton trails | Jellyfish `ttdSWN` | transients spawn particles | MID | GPU particles |

## C. MilkDrop compatibility QA targets (archetypes in the wild corpus)
Prioritize parity testing against the corpus's mass archetypes: 1) zoom-decay psychedelic (~30%), 2) per-frame swirl fields, 3) custom-wave rings, 8) spectrum-bar centered — then corridors, strobes, texture-wrapped feedback, MD2-shader fractal flights, shape-tunnels, mashup blends.

## D. TOP-15 new styles (wow-per-cost, ranked)
1. **Seigaiha Mandala** — wave-scale radial bloom; band→ring depth, beat→scale pop · SAFE · frag (`WdtGWf`)
2. **Star Nest Drift** — multi-layer warp starfield; sub-bass→layer velocity · SAFE · frag (`XlfGRj`)
3. **Liquid Chrome Blob** — iridescent mercury metaball; bass wobbles normals · SAFE→ULTRA · SDF+envmap (`3tGXz3`)
4. **Reaction Coral** — RD growth painting itself; peaks seed pattern · SAFE@½res · Gray-Scott (`4dcGW2`)
5. **Plasma Globe** — filaments to glass; treble→arc jitter · SAFE · frag (`XsjXRm`)
6. **Neon Hex Pulse** — glowing hex grid city; per-band cell brightness · SAFE · frag (`MsVfz1`)
7. **Aurora Veil** — polar curtains sway on pads · SAFE · FBM frag
8. **Truchet Runway** — endless tile-maze flight; BPM scroll, beat turns · SAFE · frag (`ldfGWn`)
9. **Black Hole** — lensed accretion disk; spin follows energy · ULTRA · lensing raymarch (`tsBXW3`)
10. **Ink Marbling** — suminagashi curl advection; melody combs the ink · MID · curl sim
11. **ASCII Terminal** — scene as glyphs; luma→density · SAFE · post pass
12. **Campfire Embers** — volumetric flame+sparks; kick flares · MID · FBM+particles (`Wtc3W2`)
13. **Pixel Sort Decay** — data-bent corruption on transients · FLAGSHIP · post (fake-smear at low tiers)
14. **Voxel Wave City** — skyline ripples as EQ columns · ULTRA · voxel raymarch (`4dfGzs`)
15. **Scope Ring** — circular oscilloscope hero · SAFE · line mesh

## E. Post-FX chain menu (premium defaults)
Cheap always-available: bloom (0.4 default), film grain (3–5%), vignette, mirror/kaleido, feedback trails, chromatic aberration (transient-gated ONLY). Mid: halation (bright-pass), VHS/CRT one-pass decay. Flagship: true pixel-sorting.
Law (premium-not-childish): never all simultaneously; CA/glitch are transient-gated; restraint is the aesthetic.

## F. Palette families (AMOLED + export-optimized; one dominant accent each)
Obsidian Neon (#000+magenta/cyan) · Cyberpunk Rain (teal+sodium orange) · Vaporwave Dusk (pink/violet/peach) · Y2K Chrome (silver/lilac/ice) · Acid Lime (black+chartreuse) · Biolum Abyss (navy+electric cyan-green) · Ember Forge (black/crimson/amber) · Blueprint (Prussian+cyan lines) · Gold Noir (black/gold/champagne) · UV Rave (UV+acid green).
