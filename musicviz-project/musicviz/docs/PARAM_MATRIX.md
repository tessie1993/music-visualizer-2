# Param × Scene-Family Matrix (v0.14.0)

Authoritative record of what every Customize parameter actually does on every
scene family. Re-derived from the merged tree by tracing each `SceneParams`
field through the scene classes, the composite pass and the export compositor —
not from any earlier revision of this file.

## Families

| Key | Family | Classes |
|---|---|---|
| **SH** | Shader | `ShaderScene` (one class, ~20 fragment programs) |
| **PT** | Particle | `ParticleSceneBase` → Burst / Fountain / Nebula / Orbit / Swarm |
| **MD** | MilkDrop | `ProjectMScene` (+ `pm_post_frag`) |
| **FL** | Fluid | `render/fluid/FluidScene` |
| **CF** | Curl Flow | `render/fluid/CurlFlowScene` |
| **WA** | Water | `render/fluid/WaterScene` |

## Mechanism key

| Code | Meaning |
|---|---|
| **S** | in-shader, via the `res/raw` prelude (`view()` / `pal()` / `grade()`) |
| **P** | particle CPU/vertex pipeline (`ParticleSceneBase`, `particle_vert/frag`) |
| **PM** | the MilkDrop post pass (`pm_post_frag`) |
| **C** | composite pass (`composite_frag.glsl` `uPost*`), also mirrored by `FxCompositor` for exports |
| **R** | renderer-level, outside any scene (fade/trail path, param fade, LFO/ADSR) |
| **G** | global feature transform (`applyBandGains`, `SceneParams.kt:289`) |
| **—** | genuinely ignored by that family (reason in the notes) |

The three composite gates, all in `VisualizerRenderer.kt`:

* `applyGeo = activeScene !is ShaderScene` (`:770`) — geometry/stylize `uPost*`.
  Shader scenes do these in-shader, so they get 0; **everything else**,
  including the whole fluid family, is served by the composite.
* `ownsMirrorInvert = ShaderScene || ProjectMScene` (`:800`) — mirror/invert.
* `gradesItself = ShaderScene || ParticleSceneBase || ProjectMScene` (`:816`) —
  zoom/rotation + the colour grade, switched wholesale by `uPostGrade`
  (`:822`). Only the fluid family (FL/CF/WA) is graded by the composite; the
  flag exists because the neutral value of these uniforms is 1.0, not the GL
  default 0.0 (`composite_frag.glsl:43-58`).

`FxCompositor.kt:324/378/388` reproduces all three gates so exports match.

## The matrix

### Motion

| Param | SH | PT | MD | FL | CF | WA |
|---|---|---|---|---|---|---|
| `speed` | S `ShaderScene.kt:121` | P (each scene) | — ¹ | FL `FluidScene.kt:225,230` | CF `CurlFlowScene.kt:150,157` | WA `WaterScene.kt:226,232` |
| `zoom` | S `:181` | P `ParticleSceneBase.kt:143` | PM `ProjectMScene.kt:220` | C `:823` | C | C |
| `rotation` | S `:182` | P `:144` | PM `:221` | C ² | C ² | C ² |
| `endlessZoom` / `endlessZoomSpeed` | S `:123` | P (all five) | PM `:165` | — ³ | — ³ | — ³ |
| `sway` | S `:207` | C | C | C | C | C |
| `pulse` | S `:208` | P point-size swell `:152` | **— ⁴** | **— ⁴** | **— ⁴** | **— ⁴** |
| `driftX` / `driftY` | S `:210-211` | C | C | C | C | C |
| `shake` | S `:212` | C | C | C | C | C |

### Behaviour

| Param | SH | PT | MD | FL | CF | WA |
|---|---|---|---|---|---|---|
| `audioDrive` | S `:125-128` | P (each scene) | **— ⁵** | FL feature snapshot `FluidScene.kt:180` (`FluidAudioDrive`) | CF field amp `CurlFlowMath.fieldAmp` | WA feature snapshot `WaterScene.kt:200` |
| `beatResponse` | S `:191` | P `:143` + scenes | PM beat sensitivity `:197,220` | FL emitter beat envelope `FluidEmitters.beatResponse` | CF `CurlFlowMath.beatDrive` | WA emitter beat envelope (same) |
| `bassGain` / `midGain` / `trebGain` | G | G | G | G | G | G |
| `turbulence` | S `:192` | P (each scene) | — | — | CF `:166` | — |
| `density` | — ⁶ | P `ParticleSceneBase.kt:122` | — | FL point exposure `FluidScene.kt:325` | — | — |
| `trails` / `trailLength` | — ⁷ | R `VisualizerRenderer.kt:668` | — ⁷ | — ⁷ | R `:667-669` (remapped, `CurlFlowMath.retention`) | — ⁷ |
| `trailZoom` / `trailWarp` | — ⁷ | R `drawTrailWarp` `:847` | — ⁷ | — ⁷ | R (same, `CurlFlowMath.warpDecay`) | — ⁷ |
| `mirror` | S `:190` | C `:801` | PM `:223` | C | C | C |
| `flash` | S `:217` | C | C | C | C | C |

### Shape

| Param | SH | PT | MD | FL | CF | WA |
|---|---|---|---|---|---|---|
| `warp`, `ripple`, `twist`, `tile` | S `:200-201,213-214` | C | C | C | C | C |
| `kaleidoscope` + `symmetry` | S `:202-203` | C | C | C | C | C |
| `pixelate`, `posterize` | S `:205-206` | C | C | C | C | C |
| `morph` | S `:204` | — ⁸ | — ⁸ | — ⁸ | — ⁸ | — ⁸ |
| `particleShape` | — | P `:149` | — | — | — | — |
| `particleSize` | — | P `:152` | — | FL `FluidScene.kt:320` | CF `CurlFlowScene.kt:207` | — |

### Colour

| Param | SH | PT | MD | FL | CF | WA |
|---|---|---|---|---|---|---|
| `palette` → `paletteBase`/`paletteRange` | S `:193-194` | P `ParticleSceneBase.kt:123-124` | — ⁹ | FL `FluidScene.kt:258-259` | CF `CurlFlowScene.kt:208-209` | WA `WaterScene.kt:247,280` |
| `hueRange` | S `:185` | P `:124` | — ⁹ | FL (via `FluidHue.span`) | CF (same) | WA (same) |
| `palette*Override` / `customPalette*Id` | resolved inside `paletteBase`/`paletteRange` (`SceneParams.kt:271-281`) — every family that reads a palette reads overrides for free | | | | | |
| `colorShift` | S `:184` | P `:123` | PM `:224` | C `:829` | C | C |
| `colorCycle` / `cycleSpeed` | S `:124,184` | P `:88,123` | PM `:166,224` | C (`postCyclePhase`) | C | C |
| `saturation`, `contrast`, `gamma` | S `:186,218-219` | P `:145,147-148` | PM `:225,227-228` | C `:825,827-828` | C | C |
| `brightness` × `intensity` | S `:187,189` | P `:146` | PM `:226,230` | C `:826` (`CompositeGrade.brightness`) | C | C |
| `invert` | S `:188` | C `:802` | PM `:229` | C | C | C |
| `palette2`, `paletteMix`, `duotone` | S `:195-198` | **— ⁸** | **— ⁸** | **— ⁸** | **— ⁸** | **— ⁸** |
| `bloom` | S `:199` | C | C | C | C | C |
| `temperature`, `solarize` | S `:215-216` | C | C | C | C | C |

### Screen FX, automation

| Param | All six families |
|---|---|
| `chromaAb`, `vignette`, `scanlines`, `grain`, `glitch`, `fisheye`, `strobe` | C — screen-space, ungated, applied to BOTH images during a transition |
| `paramFadeSec` | R (`VisualizerRenderer.lerpParams`, `:119`) |
| LFO / ADSR targets | R, applied on top of the faded params before any scene sees them |

`lerpParams` starts from `to.copy(...)`, so any field it does not name **snaps**
to the target. That is deliberate for choices/toggles and for the palette
override floats: lerping `paletteBaseOverride` from `UNSET_OVERRIDE` (-1) to a
real hue would cross zero and flicker the slot between built-in and custom.

### Fluid / FlowField / Water blocks

| Param group | Read by | Gate that shows it |
|---|---|---|
| `fluidQuality`, `fluidAutoQuality` | FL `FluidScene.kt:118`, WA `WaterScene.kt:142` | `isEmitterSceneId` |
| `fluidIterations`, `fluidPressure`, `fluidCurl`, `fluidVelocityDissipation`, `fluidDensityDissipation`, `fluidChromaticAging` | FL only `FluidScene.kt:205-212` | `isFluidSceneId` |
| emitter schedule: `fluidBeatPattern`, `fluidBeatSplats`, `fluidStirrers`, `fluidStirrerSpeed`, `fluidSplatRadius`, `fluidRadiusPulse`, `fluidSplatForce`, `fluidBassPump`, `fluidSparkle` | FL `:227-242`, WA `:229-238` | `isEmitterSceneId` |
| `fluidPaletteCycleSpeed` | FL only `:241` (WATER discards splat colour) | `isFluidSceneId` |
| journey: `fluidSpawnPath`, `fluidSpawnPoints`, `fluidSpawnProgress`, `fluidCatchPoints`, `fluidCatchPull`, `fluidCatchRadius`, `fluidParticleLife` | FL `:221-224,279-280`, CF `CurlFlowScene.kt:153-157,181-187`, WA `WaterScene.kt:222-225,237,243` | `isJourneySceneId` |
| `fluidParticleDrag` | FL `:274`, CF `:181` | `isParticleLayerSceneId` |
| `fluidParticlesEnabled`, `fluidParticleBrightness` | FL only (`:273,324`) | `isFluidSceneId` inside the particle section |
| `fluidDyeEnabled`, `fluidShading`, `fluidBloom*`, `fluidSunrays*`, `fluidCurlAudio`, `fluidBloomAudio`, `fluidFadeAudio` | FL only `:288-306` | `isFluidSceneId` |
| `waterWaveSpeed`, `waterDamping`, `waterRippleStrength`, `waterDepth`, `waterSpecular`, `waterFlow` | WA only `WaterScene.kt:181,218-219,242,281-283` | `isWaterSceneId` |
| `flowEnabled`, `flowStrength`, `flowForce`, `flowCurl` | C fluidWarp for every family (`VisualizerRenderer.kt:716-729`); FL substitutes its own velocity field | always |
| `flowAdvectParticles` | P `ParticleSceneBase.kt:96-112` | always |
| `rippleOverlayEnabled`, `rippleOverlayStrength`, `rippleOverlaySpecular` | C for every family (`:738-750`); forced off on WATER, whose own display already refracts | always |

The gate predicates live in `VisualsHub.kt:372-400` and are pinned by
`FluidTabGatingTest` / `CurlFlowCustomizeTest`.

## Notes

1. **MilkDrop ignores `speed`.** The preset's motion runs on projectM's own
   clock inside the native library; there is no host-side rate to scale.
2. **Rotation is a SPEED everywhere.** Scenes integrate `rotationAngle +=
   rotation * dt`; the composite integrates its own `postRotationAngle`
   (`CompositeGrade.integrateRotation`) so the fluid family spins at the same
   rate rather than sitting at a static offset.
3. `endlessZoom` is a per-scene *simulation* behaviour (respawn/outflow), not a
   post transform. The fluid family has no equivalent — its "endlessness" is
   the flow field itself. **Gap:** the checkbox is still shown on those styles.
4. **`pulse` (Beat pulse) has no reader on MD/FL/CF/WA.** Shader scenes use
   `uPulse`, particles swell their point size; the composite declares no beat
   pulse at all. Closing it means a new `uPost*` uniform plus its `FxCompositor`
   and `CompositeGrade` mirrors — written up in `todo.md`.
5. **`audioDrive` and `beatResponse` now read on the whole fluid family**
   (v1.1.x). FL/WA apply `audioDrive` ONCE, in `draw`, to the feature snapshot
   the sim uniforms, the choreography and the emitters all share
   (`FluidAudioDrive`, reusing `ShaderScene`'s `x * drive` clamped to 1.5 so a
   slider value means the same thing on both families), and hand `beatResponse`
   to `FluidEmitters` as the DEPTH of the beat envelope every beat-driven
   emitter term rides. CF already had `audioDrive` (its field kick) and now
   scales its own beat envelope by `beatResponse` too. Both are exact no-ops at
   the neutral default of 1, and neither is folded into `applyBandGains` /
   `gainAdjusted`: SH and PT apply `audioDrive` themselves, so a central gain
   would apply it twice there. **Still `—` on MD:** the only audio a `.milk`
   preset ever sees is the mono PCM handed to libprojectM, whose beat detector
   is ratio-based (instant band energy over its own running average), so a
   constant gain cancels out of exactly what presets react to while clipping
   the waveform many of them draw directly — the honest knob there is projectM's
   beat sensitivity, which `beatResponse` already drives
   (`ProjectMScene.update` KDoc).
6. `density` thins a point population; a fullscreen fragment shader has none.
7. Trails are a renderer-level canvas-persistence path, gated to
   `ParticleSceneBase || CurlFlowScene` (`:668`). The other styles either clear
   every frame by design or run their own dissipation (`fluidDensityDissipation`
   on FL, `waterDamping` on WA). The label says "(particle scenes)".
8. **By design, shader-only:** `morph` deforms geometry inside each fragment
   pattern; `palette2`/`paletteMix`/`duotone` need the fragment palette
   machinery. There is no meaningful post-hoc equivalent. Since v1.1.0 the
   Shape and Color tabs GATE all four on `VisualsHub.isShaderLookSceneId`, so
   they only appear on shader styles — the same rule the Fluid tab uses, and
   the reason a "—" in this row is no longer a visible dead control.
   `paletteMix` and `palette2` are gated as one group (a blend slider with
   nothing to blend is worse than no slider). Pinned by
   `ShaderLookGatingTest`, which parses the gating back out of
   `CustomizeDialog.kt`.
9. MilkDrop colours are authored by the preset; `pm_post_frag` rotates hue but
   has no palette table to key off.

## Divergences worth knowing

* **`hueRange` clamping.** The fluid family runs it through
  `FluidHue.span`, which clamps to `MIN_HUE_RANGE`(0.1)..1 — so on those three
  styles the slider's 1.0–1.5 range is flat and 0 does not collapse the
  palette to one colour. Shader and particle scenes multiply the raw value.
  Intentional (a 0 span kills the fluid look), documented here rather than
  fixed, because it also means those styles cannot over-span.
* **Hue rotation is applied exactly once on the fluid family.** The scene owns
  palette IDENTITY (base + span, decided at emission time), the composite owns
  ROTATION (`colorShift + cyclePhase`). See `FluidHue.kt:5-24`; folding either
  into the scene as well turned the wheel twice per slider unit.
* **Brightness/intensity is applied exactly once.** `CurlFlowMath` and
  `WaterMath` deliberately return exposure-free values; the composite's
  `uPostBright = brightness * intensity` is the single owner for FL/CF/WA.
* **Chip selectors are lockable.** `Palette`, `Palette 2`, `Particle shape`,
  `Beat pattern` and `Path` now render `LockableChipLabel`, so every parameter
  `ParamRandomizer` rolls can be held. Lock keys are the label strings, which
  is why `"Ripple strength"` (Water, 0..2) and `"Ripple overlay strength"`
  (all-styles overlay, 0..1) had to stop sharing a label, and why the LFO
  card's depth slider is now `"LFO depth"` rather than colliding with the
  Water section's `"Depth"`.
