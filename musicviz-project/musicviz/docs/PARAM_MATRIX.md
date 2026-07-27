# Param × Scene-Family Matrix (P0 verification, v0.9.6)

Method: static trace of every SceneParams field through ShaderScene uploads,
particle-family files, the renderer's composite pass (uPost*, guarded by
applyGeo = scene !is ShaderScene), and ProjectMScene/pm_post. Mechanism key:
S=in-shader (prelude view()/pal()/grade()), P=particle CPU/vertex pipeline,
C=composite pass, PM=pm_post shader, R=renderer, G=global (applyBandGains).

| Field | Shader | Particle | MilkDrop |
|---|---|---|---|
| speed, zoom, rotation | S | P | PM(zoom/rot) / C |
| endlessZoom(+Speed) | S | P (all 5 scenes as of v0.9.6) | PM (triangle-wave, no pop) |
| attack, decay | R (feature smoothing) | R | R + PM beat sensitivity |
| audioDrive, beatResponse | S | P | PM (PCM level) / C pulse |
| bassGain, midGain, trebGain | G | G | G |
| pulse | S | P point-size swell (v0.9.6) | C |
| flash, temperature, solarize | S | C | C |
| sway, driftX/Y, shake | S | C | C |
| warp, ripple, twist, tile | S | C | C |
| kaleidoscope, symmetry | S | C | C |
| pixelate, posterize, bloom | S | C | C |
| mirror, invert | S | C (single owner) | PM mirror; C invert |
| chromaAb, vignette, scanlines, grain, glitch, fisheye, strobe | C (screen-space, all families) | C | C |
| saturation, brightness, contrast, intensity | S | P(uSat)+C | PM+C |
| palette (index→base/range), colorShift, hueRange, colorCycle, cycleSpeed | S | P (hue span coloring) | — (preset-authored colors) |
| palette2, paletteMix, duotone | S | BY DESIGN shader-only¹ | BY DESIGN¹ |
| morph | S | BY DESIGN shader-only² | BY DESIGN² |
| turbulence | S | P | — |
| density, particleShape, particleSize, trails, trailLength | — (particle-only by design) | P | — |
| paramFadeSec | R (all families) | R | R |

¹ Dual-palette blending/duotone needs the fragment palette machinery;
particles color by single hue span, milkdrop colors are preset-authored.
Candidate for a later composite palette LUT — not a silent no-op: the
Customize UI only shows these on shader scenes.
² Morph deforms scene geometry inside each shader's pattern; there is no
meaningful post-hoc equivalent. UI hides it off shader scenes.

v0.9.6 fixes from this audit: pulse now works on particles (beat size
swell); endlessZoom added to Swarm/Fountain (outward flow) and Orbit
(radius drift with respawn) so all five particle scenes honor it.

Fluid additions (v0.12.0): the fluid* fields act on the FLUID scene only
(sim/emitters/look inside FluidScene; documented no-ops elsewhere by tab
scoping - the Fluid tab shows only the FlowField section on other styles).
flow* fields are global: flowStrength/flowCurl/flowForce drive the shared
FlowField consumed by the composite fluidWarp slot (C, all families incl.
MilkDrop + export), flowAdvectParticles by the particle CPU pipeline (P),
and uFlow/uFlowStrength by shader scenes (S, opt-in sampling).
