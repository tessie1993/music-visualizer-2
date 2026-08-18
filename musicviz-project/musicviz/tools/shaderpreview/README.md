# shaderpreview

Renders the app's GPU styles headlessly, off-device, and **measures** them.

There are ~37 visual styles in this app and, without this, the only way to see
any of them is to build an APK and put it on a phone. That is slow enough that
shaders get changed blind, and "it looks wrong" comes back as a sentence
instead of a number. This turns a style into PNGs plus a table of luminance
statistics you can assert on.

It renders the app's **real** GLSL — the files in `app/src/main/res/raw`, with
their `//#include` directives resolved exactly the way `GlUtil.kt` resolves
them — driven by uniform values computed by a JS mirror of the Kotlin scene
that would upload them.

**Read [What it cannot tell you](#what-it-cannot-tell-you) before you trust
anything it prints.** An over-trusted harness is worse than no harness.

## Running it

No install step, no `node_modules`, no network.

```sh
cd musicviz-project/musicviz/tools/shaderpreview

node preview.mjs --list

# HYPERSPACE, eight frames of a beat track, PNGs + report.json into out/hs
node preview.mjs --scene hyperspace --frames 8 --audio beat --out out/hs

# one of the 22 fragment styles
node preview.mjs --scene shader --shader julia --frames 4 --audio tone

# Emergence, the style that replaced the CPU particle family - sprites plus
# the acid echo pass; read the standIns in report.json before trusting a frame
node preview.mjs --scene emergence --frames 4 --count 1200 --out out/em

# a minute of steady music, sampled every 5 s, with fluid-field statistics
node preview.mjs --scene hyperspace --frames 13 --every 300 --audio tone --field-stats

# what the style looks like on a GPU with no float render targets
node preview.mjs --scene hyperspace --no-melt --out out/no-melt

# five hours of wallpaper uptime, after a 10 s warm-up
node preview.mjs --scene shader --shader plasma --warmup 600 --frames 6 --clock-jump 3600

# the Layers blend algebra: with both layers identical, DIFFERENCE must go
# to exactly 0, MULTIPLY must darken and SCREEN must brighten
node preview.mjs --scene shader --shader plasma --composite --layer 1,4 --frames 1

# through the composite pass, which is where Zoom and Rotation live on a
# fluid-family style - without this you are looking at a frame the user never
# sees, and half its controls are applied nowhere in it
node preview.mjs --scene hyperspace --composite --param zoom=3 --frames 1
```

### Flags

| flag | meaning |
| --- | --- |
| `--scene hyperspace \| shader \| emergence` | which driver to use |
| `--shader <id>` | style id for `--scene shader` (see `--list`) |
| `--count N` | particle population for `--scene emergence` (default 2600) |
| `--frames N` | frames to **capture** |
| `--every N` | simulate N frames per captured frame |
| `--warmup N` | simulate N frames before capturing anything |
| `--fps N` | simulated frame rate (default 60); sets `dt` |
| `--size N` / `--width` / `--height` | render size (default 480) |
| `--audio silence\|tone\|beat\|arc` | audio model |
| `--param name=value` | override one `SceneParams` field, repeatable |
| `--no-melt` | force `uHasMelt = 0` (the no-float-buffer fallback) |
| `--composite` | run the scene through `composite_frag` afterwards, as the app does |
| `--layer mix,mode` | force the Layers branch (`uStyle` 6) at that mix and `BlendMode` ordinal. Both layers are the SAME texture, so this checks the blend algebra, not a real two-scene composite |
| `--field-stats` | read back the fluid dye/velocity grids and report their statistics |
| `--clock-jump S` | advance the free-running clocks by S seconds per captured frame |
| `--seed N` | seed for the body RNG |
| `--out DIR` | write `frame_NNN.png` and `report.json` |
| `--json` | print the whole report as JSON |

Frames that are not captured skip the draw entirely (every simulation still
steps), which is what makes `--every 300` finish — the raymarch is ~99% of the
wall clock on software GL.

## How it stays honest

A preview harness that invents its own uniform values is a drawing of a
shader, not a preview of a style. A uniform the harness forgets defaults to
**zero** in GL, and a zeroed uniform is indistinguishable from a feature that
is switched off — which is exactly how a working feature gets reported as
dead. So nothing here is allowed to be implicit.

**Includes.** `lib/glsl.mjs` parses the `GlUtil.INCLUDES` map out of
`GlUtil.kt` rather than hardcoding a list, and applies the same anchored
`^[ \t]*//#include[ \t]+(\w+)[ \t]*$` pattern, one level, unknown include is a
hard error. Add a library to the app and this picks it up; rename one and this
fails the same way the app does.

**Uniforms — a three-way audit, run before every render.**

| set | source |
| --- | --- |
| A. what the shader **declares** | `parseUniforms` over the resolved GLSL (array lengths resolved through `#define`) |
| B. what the Kotlin **uploads** | `loc("uX")` / `glGetUniformLocation(program, "uX")` / `setUniform1f("uX")` scraped from the scene's `.kt` |
| C. what the harness **supplies** | the driver in `lib/scenes.mjs` |

- `A \ C` → **fatal**: the render would contain a silent zero.
- `B \ C` → **fatal**: the harness has drifted behind the app.
- `C \ B` → **fatal**: the harness is inventing an input the app never sends.
- `A \ B` → reported as a note: the *app* leaves it zero too, which is a
  finding about the app.
- declared-but-dead-stripped-by-the-linker is reported separately, so the
  audit does not cry wolf about a uniform the shader never reads.

When the audit fails, the tool prints the mismatch and **refuses to render**.
That is the point: a picture from a drifted harness is worse than no picture.

**Uniform values** are computed by JS ports of the Kotlin that owns them:
`lib/hyperspace-math.mjs` (`HyperspaceMath.kt`, `MeltMath`, `FluidHue`),
`lib/emitters.mjs` (the `FluidEmitters` paths `MeltField` actually enables),
and `lib/scenes.mjs` (the two `draw()` methods). Every constant is
copied with the comment that justifies it, so a drift is visible in a diff.

**The melt is not modelled — it is run.** `page/harness.html` executes the
app's own `fluid_*.glsl` fragment shaders in the app's own pass order
(`FluidSim.step`: advect → velocity splats → curl → vorticity → divergence →
Jacobi → gradient → dye splats → dye advect) on RGBA16F/RG16F/R16F ping-pong
FBOs at the app's grid sizes. There is no re-implementation of the fluid to be
wrong about.

**Stand-ins are named.** Where the app binds something conditionally (the
FlowField texture, the cyclic-palette atlas), the harness supplies the neutral
value the app itself sends when it is absent, and prints it as a stand-in
rather than pretending it is the real thing.

**The composite pass is the app's, and audited the same way.** With
`--composite` the scene draws into an RGBA8 target (`VisualizerRenderer`'s
`TargetFbo`, filter and wrap included) and `composite_frag` draws that to the
screen, driven by `lib/composite.mjs` mirroring the renderer's `cLoc()` block
and gated by `CompositeGrade.gateFor` for the scene's family. Its uniforms go
through the same three-way audit against `VisualizerRenderer.kt`. Only the
non-transition case is modelled - `uStyle = CUT`, one scene texture, both
gates from the active family; two live scenes would need two drivers, and the
gate algebra a transition exercises is pinned by `CompositeGradeTest`
instead.

## What it measures

Per captured frame, from a `readPixels` of the default framebuffer:

- `meanLuma`, `maxLuma` — Rec. 709 luminance of the **stored 8-bit values**
- `fracBlownOut` — fraction of pixels with R, G *and* B above 0.95
- `fracBlack` — fraction of pixels with luminance below 0.02
- `deltaMeanLuma` — change in `meanLuma` since the previous **rendered** frame
- `skippedUniforms` — anything the plan set that the linker had dropped
- `glError`

With `--field-stats`, also the dye and velocity grids read back as float:
`max`, `mean`, `meanMagnitude`, `fracAbove1`, `nonFinite`.

## What it cannot tell you

Take these seriously. Every one of them is a way to be confidently wrong.

1. **SwiftShader is not a phone GPU.** Rendering happens on ANGLE over
   SwiftShader's Vulkan backend. It is a correct, conservative software
   rasteriser, and that is the problem: it agrees with the spec, and phone
   drivers do not. It will not reproduce Mali's or Adreno's fast-math
   reassociation, their `pow`/`atan` approximations, their loop-unrolling
   limits, or their register-pressure cliffs.

2. **Precision qualifiers are effectively ignored.** SwiftShader computes
   `mediump` and `lowp` at full float32. Every bug in this codebase's history
   that came from a real `mediump`/`lowp` range limit — including the
   `precision highp sampler2D` fix in the fluid shaders, whose comment
   explains that GLSL ES defaults fragment `sampler2D` to `lowp` and that Mali
   honoured it — is **invisible here**. A shader that renders perfectly in
   this harness can still clamp and quantise to garbage on a real device.
   `--clock-jump` tests float precision at large `t` in *float32*; a driver
   that runs that expression at `mediump` will fail much sooner than this
   says.

3. **Float render targets are available here, and are not everywhere.**
   `EXT_color_buffer_float` is present under SwiftShader, so the melt always
   runs unless you pass `--no-melt`. On a device without renderable half-float
   the app takes the `uHasMelt = 0` path. Use `--no-melt` deliberately; do not
   assume the default run is what a given phone shows.

4. **Driver-specific compile and link failures do not happen here.** A shader
   compiling in this harness says nothing about whether a device driver will
   accept it: uniform-array sizes, loop bounds, `MAX_FRAGMENT_UNIFORM_VECTORS`
   (4096 here, as low as 224 on real ES 3.0 hardware), and vendor-specific
   optimiser bugs all differ. This tool is a *lower* bound on portability.
   `docs/DEVICE_CHECKS.md` is still the authority.

Also, more mundanely but just as capable of misleading you:

5. **The bodies are not the device's bodies.** `HyperspaceScene` seeds its
   bank from `Random.Default`; this uses a seeded xorshift. Species, axes,
   hues, sizes and lifetimes are drawn from the same distributions but are not
   the same draws. Any claim that depends on a *specific* body is not a valid
   finding from this tool. `--seed` makes a run reproducible, not faithful.

6. **`--clock-jump` moves the clocks and nothing else.** It advances `uTime`
   (and the camera drift phase, and `uRotation`) without ageing the body bank
   or the fluid, because the app clamps its own `dt` to 1/15 s and a scene
   stepped at a 60-second `dt` is a state the app can never be in. Read a
   jumped run for precision behaviour only; the geometry in it is whatever
   the warm-up left there.

7. **The composite pass is off unless you ask for it.** Without
   `--composite` what you see is the scene's own output before the app grades
   it - which is what you want for debugging a scene, and is *not* what the
   user sees. On a fluid-family style that difference is not cosmetic:
   `CompositeGrade.gateFor(FLUID)` hands the composite the whole
   zoom/rotation/colour-grade block, so Zoom and Rotation are applied nowhere
   in the scene-only frame and reading one is a good way to conclude that a
   working control is dead. Even with `--composite`, transitions and the
   spliced gl-transition variants are not modelled.

8. **Luminance is measured on the stored 8-bit values**, not on display-linear
   light. It is a good relative measure and a poor absolute one; use it to
   compare runs, not to make a claim about perceived brightness.

9. **Three scene families are wired up**: `HyperspaceScene`, the 22
   `ShaderScene` styles, and `EmergenceScene`. The fluid family's own
   display passes, WATER, CYMATICS, BEAM and MILKDROP have their own uniform
   contracts and are not covered. Adding one means adding a driver to
   `lib/scenes.mjs` — and the audit will tell you when you have not finished.

10. **`--scene emergence` previews the style's RENDER, not its simulation.**
    `EmergenceSim.step()` is pure CPU and is already covered on the JVM by
    `EmergenceSimTest` through the records it publishes. What this driver
    covers is what those tests cannot see without a GL context: the instanced
    attribute layout, the billboard and stretch maths in
    `lib_particle_common`, the shading in `lib_particle_shade`, the
    palette/density post-process, the sprite program's thirteen-uniform
    contract, and the acid echo's six. The population it feeds them is a
    **named stand-in** — a deterministic orbit field spanning the full
    size/speed/hue range, with every 64th particle dead so the `vFade <= 0`
    discard path runs every frame — and the beat envelope stands in for
    `sim.beatEnvelope()`. Any claim about how the sim MOVES is not a valid
    finding from this driver.

11. **The sprite pass clears the target only when the echo is off.** With
    Trails on, the echo blit is the background exactly as `drawWithEcho()`
    sequences it — previous frame warped, decayed and redrawn under the new
    sprites, ping-ponged between two RGBA8 targets. `--param trails=false`
    previews the cleared, sprites-only path instead.

## Layout

```
preview.mjs             CLI: resolves shaders, runs the audit, drives the page
lib/cdp.mjs             Chromium launch + CDP over Node 22's built-in WebSocket
lib/glsl.mjs            GlUtil-equivalent include resolution; uniform declaration scan
lib/kotlin.mjs          uniform names scraped from Kotlin; the three-way audit
lib/audio.mjs           AudioFeatures models + the 64x2 uAudioTex rows
lib/hyperspace-math.mjs JS port of HyperspaceMath.kt / MeltMath / FluidHue
lib/emitters.mjs        JS port of the FluidEmitters paths MeltField enables
lib/scenes.mjs          per-frame uniform plans, mirroring the Kotlin draw()s
lib/composite.mjs       the same, for VisualizerRenderer's composite_frag pass
page/harness.html       WebGL2: compile, upload, run the fluid passes, measure
```

### Why CDP and not Playwright

`node -e "require('playwright')"` fails with `MODULE_NOT_FOUND` in this
environment, and `playwright install` is explicitly off the table. Node 22
ships a global `WebSocket` and `fetch`, which is all the DevTools Protocol
needs, so `lib/cdp.mjs` drives the browser in ~120 lines with no dependency at
all. A `--headless --screenshot` invocation was rejected because the harness
has to keep GL state (the fluid ping-pong grids) alive **across** frames and
read values back out, which one-shot screenshotting cannot do.

The Chromium binary comes from `/opt/pw-browsers/chromium`; override with
`MUSICVIZ_CHROMIUM`.
