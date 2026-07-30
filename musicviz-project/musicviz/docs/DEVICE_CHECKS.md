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
21. Fluid colours: on the FLUID style, sweep Color > Palette. Each
    palette must change the CHARACTER of the ink, not just its tint —
    Fire/Cherry/Copper stay inside one narrow hot band while
    Spectrum/Aurora/Galaxy sweep visibly across the wheel in a single
    frame, on the dye splats AND the particle layer. Then sweep "Hue
    shift" 0 -> 1: the whole fluid must rotate through the wheel and land
    back on the starting colours at 1 (dye and particles together, no
    jump at the wrap). Pull "Hue range" to 0 — colours must narrow to a
    tight band but never collapse to one flat colour. Repeat with a
    user-made custom palette selected.
22. Water customization gaps: on the WATER style, with Catch points >= 1,
    sweep "Catch radius" (Journey) — the drain dimples on the pool must
    visibly grow/shrink with the slider (they used to be inert), and each
    well must read as a DIP that radiates rings, not as a splash. Then
    sweep "Hue shift" (Color) — the whole pool must retint continuously
    and wrap around the hue circle without a jump; at Hue shift 0 the
    pool must look exactly as it did before this change (palette base
    only). Check both against a saved preset roundtrip, and confirm a
    10 s export matches the live view for both sliders.
    Brightness/Intensity: the water display pass no longer applies these
    (the composite grade owns them for the whole fluid family), so sweep
    Brightness and Intensity on WATER and confirm the response is smooth
    and LINEAR — no sudden blow-out in the top third, which is what the
    old double-apply looked like — and that Water tracks Fluid and Curl
    Flow at the same slider value. Both sliders at minimum must dim the
    pool rather than leave it fully lit.
23. Composite grading + geometry (fluid styles): on Fluid, Curl Flow AND
    Water, sweep Zoom, Rotation, Saturation, Brightness, Contrast, Gamma,
    Hue shift, Intensity, Color cycle, Mirror and Invert — every one must
    now visibly change the image (they were dead: the fluid family grades
    nothing itself and the composite pass declared no grading uniforms).
    Rotation must SPIN continuously (it is a speed, not a static offset)
    and Zoom must magnify about the screen centre.
    REGRESSION SIDE — the same sweep on a shader style (julia/plasma), a
    particle style (nebula/orbits) and a live MilkDrop preset must look
    EXACTLY as before: those grade themselves, so the composite sends the
    neutral identity (uPostGrade = 0). Anything twice as bright, twice as
    contrasty or twice as zoomed as before means the gate regressed.
    Switch fluid <-> shader mid-sweep and watch the transition for a
    grading pop on the outgoing image.
    Known gap (follow-up): the export compositor (FxCompositor) does not
    upload the new uniforms yet, so an exported fluid clip is ungraded
    while the live view is graded. Exports of every other style must be
    unchanged.
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
27. Hue shift applied ONCE on the fluid family: on FLUID, Curl Flow and
    Water, with Color cycle off, walk "Hue shift" 0 -> 0.25 -> 0.5 ->
    0.75 -> 1. Each quarter step must advance the image a quarter turn of
    the wheel — ONE full turn across the whole slider, not two. If 0.5
    already lands back on the starting colours, a scene is folding the
    shift into its palette base on top of the composite's rotation again.
    Dye splats and the particle layer must move together, with no jump at
    the wrap, and 1.0 must land back exactly on the 0 colours.
    Then turn Color cycle ON with Cycle speed mid-scale and time one full
    trip round the wheel: it must take the SAME time as on a shader style
    (julia/plasma) at the same setting, not half. With Color cycle off,
    the fluid-only "Palette cycle" slider (Fluid tab) must still drift the
    dye on its own.
    Palette and "Hue range" must keep changing the CHARACTER of the ink as
    in item 21 — palette identity stays scene-side, only the rotation
    moved to the composite.
    Export note: until FxCompositor uploads the grading uniforms (item 23's
    known gap), an exported FLUID clip now renders with Hue shift / Color
    cycle neutral instead of baked into the dye — the same state Curl Flow
    and Water exports are already in. Live view is the gate here; Palette
    and Hue range must still show in the export.
