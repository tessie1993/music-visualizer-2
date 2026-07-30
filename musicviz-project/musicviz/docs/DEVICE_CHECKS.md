# On-device verification (GL/native can't be checked headless)

Run after installing musicviz-debug.apk. Log: `adb logcat -s projectM-jni`

1. MilkDrop render: Style > MilkDrop > load a .milk. Expect motion, no
   solid black. Log shows preset load; errors surface via GetLastError.
2. Texture "Use": Textures > import a jpg/png > Use. Expect the image in
   the feedback loop. If black: capture the log block and the generated
   show_<name>.milk from files/milk/generated/.
3. Customizations on .milk: with a milk preset live, sweep warp, kaleido,
   tile, drift, sway, flash, temperature, invert, chroma, vignette — each
   must visibly change the output (composite path).
4. Preset roundtrip live: customize heavily > save > switch scenes > apply
   the preset. Expect scene + every slider position + shader restored.
5. Flicker: play a hi-hat-heavy track. Default Beat threshold (2.5σ)
   should not strobe; lowering the slider should visibly increase beat
   response, raising it should calm it.
6. Zoom AA: set Zoom high on julia/mandel — edges stay smooth
   (supersampled FBOs), no blocky texels.
7. Export parity: dial in FX + LFO on a particle scene, export 10 s.
   The mp4 must match the live look (FX chain, LFO motion, trails).
8. Fluid look (F4): on the FLUID style toggle Shading / Glow / Sunrays -
   each recompile shows the expected change; dark gradients show no
   banding (dither); "Glow (fluid)" is independent of the FX-tab Bloom.
9. Fluid quality (F6): sweep the Quality chips mid-playback - grids
   re-init without a crash and the ink survives (copy-resize). With Auto
   quality on, a sustained load drop lowers the tier once (never
   oscillates); a single stall (app switch) must NOT trigger it.
10. FlowField (F7): enable FlowField on a MilkDrop preset and a particle
    scene - fluidWarp visibly bends both; a 10 s export matches. With
    Flow strength 0 there must be zero visible/perf difference. On FLUID
    itself the warp follows the scene's own field.
11. Fluid injection shaders (F7): apply a deliberately broken force
    shader - the sim keeps running on the last good program and the error
    text appears; Reset to built-in restores the capsule splat.
12. Fluid resize (bugfix): rotate the device on FLUID - the ink pattern
    survives rotation; backgrounding and resuming does not reset it.
13. Journey rebuild (v0.13.0): play a full track on FLUID with the
    "fluid · Journey" preset. Expect: dye/particle activity visibly
    RELOCATES as the track advances (lower third early, center bloom
    mid-track, upper drift late), section changes glide the layout to a
    new arrangement (never a snap), and particles stream INTO the catch
    wells and re-emerge at the spawn points. Sweep Catch pull 0->3: the
    pull-in strengthens live. Set Progression to 0: the layout stops
    journeying and only orbits in place. Repeat on Curl Flow (same
    Journey section). Export 20 s from mid-track: the exported journey
    position must match the live view at the same timestamps (progress
    plumbing parity). Particle births/deaths must be soft fades - any
    popping = fade envelope regression. Known nuance: section re-seats
    only appear live once the track has been analyzed (AUTO/SUGGEST or
    cached analysis); in MANUAL mode with no cache the live view has no
    section data while the export always detects sections offline - the
    progress journey still matches, only the golden-angle re-seats
    differ. Verify with an analyzed track.
14. Transition flash fix (v0.13.0 r22): set Vignette + Chromatic
    aberration + Scanlines high, then switch scenes/presets under every
    transition style (fade/melt/slide/zoom). The screen FX must stay
    applied to BOTH the outgoing and incoming image for the whole
    transition — no brightness pop at transition start/end. Apply a
    preset with a different palette while a transition is running: the
    OUTGOING scene must keep its old colors (frozen params) as it fades.
15. Back button (v0.13.0 r22): system back collapses Now Playing, then
    closes Search, then pops a Library drill-in, then returns any tab to
    Home, and only exits from Home.
16. Chrome translucency (v0.13.x): sweep Settings > Look > Bar opacity —
    the bottom nav bar, mini player, Now Playing header chip and
    transport card must visibly change translucency live. Transport
    never drops below ~25% opacity; the Search overlay stays near-opaque
    (>= 85%) regardless of the slider. On the LIGHT/PAPER themes the
    Now Playing header text must remain readable (onSurface, not white).
17. Appearance (0.13.x): in Settings, every slider/selector must visibly
    change the UI immediately, no navigation needed — theme cards recolor
    all surfaces, Accent intensity mutes/vivifies primary accents,
    Background dim darkens surfaces, Corner style reshapes cards/buttons
    (sharp/rounded/pill), Compact mini-player slims the bar, player
    position Top/Bottom moves the bar, and "Follow system light/dark"
    swaps to the LIGHT theme when the OS is in light mode.
18. Boot animation (0.13.x): Cold start: system splash hands off to the
    ripple intro, no flash of unstyled content; tap skips; rotating during
    the intro does not replay it; the Settings > Look "Boot animation"
    toggle off disables it on the next cold start.
19. Water style: select water — beat drops radiate as expanding
    interfering rings; specular glint tracks ripples; palette retints the
    pool; export falls back correctly (exportSceneFactory constructs
    WaterScene).
20. Ripple overlay (F2): enable "Water ripples" (Fluid tab) on a particle
    scene, a shader scene, AND a live MilkDrop preset — beat rings must
    visibly refract each one and the glint must sparkle on crests.
    Switch scenes mid-beat: the transition keeps the ripples on BOTH the
    outgoing and incoming image (postFx path). Ripple strength 0 must be
    zero visible difference. On the WATER style the overlay must NOT
    double-apply (its own surface already refracts; the renderer guard
    keeps the overlay off). Export 10 s of MilkDrop + overlay: the mp4
    must show the same refraction/glint as the live view. Watch frame
    time on a Min-tier device with overlay + FlowField both on (two
    extra sims per frame).
21. Fluid-family colour — palette identity + hue applied ONCE (v0.14.0;
    covers FLUID, CURL FLOW and WATER, run all three):
    (a) Sweep Color > Palette. Each palette must change the CHARACTER of
    the image, not just its tint — Fire/Cherry/Copper stay inside one
    narrow hot band while Spectrum/Aurora/Galaxy sweep visibly across the
    wheel in a single frame, on the dye splats AND the particle layer
    (FLUID), on the streams (CURL FLOW), on the pool (WATER). Repeat with
    a user-made custom palette selected, and with one of the palettes
    added in v0.14 (Cyan / Magenta / Yellow) — those must read as the
    named colour, not as a wash.
    (b) Pull "Hue range" to 0 — colours must narrow to a tight band but
    never collapse to one flat colour.
    (c) With Color cycle off, walk "Hue shift" 0 -> 0.25 -> 0.5 -> 0.75
    -> 1. Each quarter step advances the image a quarter turn: ONE full
    turn across the whole slider, not two. If 0.5 already lands back on
    the starting colours, a scene is folding the shift into its palette
    base on top of the composite's rotation again. Dye and particles must
    move together, no jump at the wrap, and 1.0 lands exactly on the 0
    colours.
    (d) Turn Color cycle ON at mid-scale Cycle speed and time one full
    trip round the wheel: it must take the SAME time as on a shader style
    (julia/plasma) at the same setting, not half. With Color cycle off,
    the fluid-only "Palette cycle" slider (Fluid tab) must still drift the
    dye on its own.
    (e) Export a ~10 s clip of each and walk the same sweeps against the
    recording: palette, hue range, hue shift and colour cycle must all
    match the live view (the export compositor uploads the same grading
    uniforms — see item 25).
22. Water-specific controls: on the WATER style, with Catch points >= 1,
    sweep "Catch radius" (Journey) — the drain dimples on the pool must
    visibly grow/shrink with the slider (they used to be inert), and each
    well must read as a DIP that radiates rings, not as a splash. Sweep
    "Wave speed", "Damping", "Ripple strength" (0..2), "Depth",
    "Specular" and "Flow drift" — each must change the surface. Check
    against a saved preset roundtrip and confirm a 10 s export matches.
    Grading on WATER (Brightness/Intensity/Hue shift/…) is item 23.
23. Composite grading + geometry (fluid styles): on Fluid, Curl Flow AND
    Water, sweep Zoom, Rotation, Saturation, Brightness, Contrast, Gamma,
    Hue shift, Intensity, Color cycle, Mirror and Invert — every one must
    visibly change the image (they were dead: the fluid family grades
    nothing itself and the composite pass declared no grading uniforms).
    Rotation must SPIN continuously (it is a speed, not a static offset)
    and Zoom must magnify about the screen centre.
    Brightness and Intensity are now applied exactly ONCE, by the
    composite: the response must be smooth and LINEAR on all three styles
    — no blow-out in the top third, which is what the old double-apply
    looked like — the three styles must track each other at the same
    slider value, and both sliders at minimum must dim rather than leave
    the image fully lit.
    REGRESSION SIDE — the same sweep on a shader style (julia/plasma), a
    particle style (nebula/orbits) and a live MilkDrop preset must look
    EXACTLY as before: those grade themselves, so the composite sends the
    neutral identity (uPostGrade = 0). Anything twice as bright, twice as
    contrasty or twice as zoomed as before means the gate regressed.
    Switch fluid <-> shader mid-sweep and watch the transition for a
    grading pop on the outgoing image.
    (The export side of this is covered by item 25.)
24. Curl Flow trails + particle layer: on the CURL FLOW style,
    (a) Toggle Motion > Trails OFF — the streams must turn into crisp
    per-frame points with no echo (it used to be impossible to switch
    off); toggle it back ON and sweep "Trail length" across its WHOLE
    range — the echo must lengthen continuously, with the shortest
    setting still reading as streams rather than strobing dots.
    (b) With Trails ON, set "Trail zoom (echo in/out)" and "Trail warp
    (liquid echo)" non-zero: the warp path must decay at the SAME rate as
    the plain fade — the streams must not break up into dots the moment
    either knob leaves zero (they did: the warp path read the raw slider
    instead of Curl Flow's remapped retention band).
    (c) Fluid tab > Particles: "Particle drag" must be VISIBLE on Curl
    Flow (there is no "Particle layer" checkbox or "Particle brightness"
    there — Curl Flow ignores both) and dragging it must visibly change
    how fast the streams settle into the flow; "Particle life (s)" in the
    Journey section must still work. On FLUID nothing changes: the layer
    checkbox still gates drag and brightness. On WATER the Particles
    section stays absent.
25. Composite grading in EXPORTS: with Zoom, Rotation, Saturation,
    Brightness, Contrast, Gamma, Hue shift, Intensity, Color cycle,
    Mirror and Invert all pushed well off neutral on a fluid style
    (Fluid, Curl Flow AND Water), record a ~10 s clip and play the mp4
    back next to the live view: the grade, the mirror/invert and the
    zoom must match, and the rotation/colour cycle must have travelled
    the SAME distance over those 10 s (it is a speed integrated on the
    export's own clock). Repeat the recording at 30 fps and at 60 fps —
    the spin rate must be identical in both files; a 30 fps clip that
    spins at half speed means the frame delta is wrong.
    REGRESSION SIDE — export a shader style, a particle style and a live
    MilkDrop preset with the same sliders: those grade themselves, so
    their files must look exactly as they did before (uPostGrade = 0).
    A black or near-black exported frame at high Zoom is the signature
    of the grading uniforms being left unset.
26. Beat sensitivity for slow tracks: play a slow, sparse track (ballad,
    ambient, ~60-80 BPM) on a beat-reactive style (flash/pulse/strobe).
    At the shipped defaults note how often it flashes. Drag "Beat
    sensitivity" toward 6.0σ — flashes must get RARER, not denser, and
    the top of the range must be reachable (it used to stop at 4.0σ).
    Then drag "Minimum gap between beats" to 1200 ms: flashes must be at
    least ~1.2 s apart no matter how busy the track gets. Tap "Slow
    track": both sliders jump to 4.5σ / 700 ms and the visual should
    pulse on the kick only. Tap "Default": back to 2.5σ / 333 ms.
    REGRESSION SIDE — on a busy dance track at the defaults the beat
    response must look EXACTLY as before this change. Kill and relaunch
    the app after each change: both values must persist, and a profile
    that last stored a sigma under the old 1.5-4.0 range must reload at
    that same value with the slider thumb sitting on it (not snapped).
    KNOWN LIMITATION (not a bug to file): both settings apply to LIVE
    analysis only. Cached/offline beat grids are built with the extractor
    defaults, so an exported clip and any section-driven intelligence
    still use 2.5σ / 333 ms whatever the sliders say — see todo.md
    "Beat sensitivity does not reach the offline analyzer".
27. Randomize locks + Customize labels (v0.14.0): open Visuals >
    Customize. Every control must show a "lock"/"locked" affordance,
    INCLUDING the chip selectors — Palette, Palette 2 (on a SHADER style
    only, and only once Palette blend > 0 — see check 29), Particle shape
    (Shape tab), and Beat pattern /
    Path (Fluid tab, on a fluid style). Lock Palette, pick a palette you
    like — including a user-made one from the palette maker — then press
    "⚄ Randomize unlocked" ten times: the palette and its gradient must
    survive every roll, while unlocked params keep changing. Unlock it
    and roll again: the palette must now change AND a custom palette must
    be dropped back to the rolled built-in (no stale custom gradient).
    Then confirm the labels that used to collide are independent: locking
    the Water section's "Depth" must NOT lock an LFO's "LFO depth", and
    locking "Ripple strength" (Water) must NOT lock "Ripple overlay
    strength" (Water ripples, all styles) — roll and watch both move
    independently. Finally, Visuals > Customize must be the only
    customization surface: there is no second full-screen dialog to reach
    from anywhere in the app.
29. Shape/Color controls only where they work (v1.1.0): the rule is "if
    you can see it, it works". Pick a SHADER style (Visuals > Styles >
    Shaders > julia). Customize > Shape must show "Morph"; drag it and
    the pattern must fold toward polar. Customize > Color must show
    "Palette blend"; raise it above 0 and a "Palette 2" chip row must
    appear underneath, and picking a second palette must visibly mix.
    "Duotone" (Color > Effects) must be there and must flatten the image
    onto the palette. Now switch to a PARTICLE style (nebula), then
    MilkDrop, then each fluid style (fluid, curlflow, water): on all of
    them "Morph", "Palette blend", "Palette 2" and "Duotone" must be
    GONE — no greyed rows, no empty gaps, and the sections around them
    ("Distortion", "Palettes", "Effects") must still read as complete
    lists. Everything else in those two tabs must stay visible AND keep
    working on every style: Domain warp, Ripple, Twist, Kaleidoscope +
    Folds, Tile, Pixelate, Posterize, Palette, the gradient/palette
    maker, Hue shift, Hue range, Color cycle, Saturation, Brightness,
    Contrast, Gamma, Intensity, Temperature, Bloom, Solarize, Invert —
    move each one on a particle style and confirm it bites. Switch back
    to julia: the four must return with the values they had. Finally,
    save a preset on julia with Morph high, Palette blend 0.5 and
    Duotone on, switch to nebula, re-apply it (no visible change is
    correct there), switch back to julia and re-apply: the look must
    come back intact — hiding a control must never drop its value.
