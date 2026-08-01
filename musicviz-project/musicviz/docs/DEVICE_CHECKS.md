# On-device verification (GL/native can't be checked headless)

Everything here is a property no headless gate can reach: GL output, native
code, the audio the device actually hears, or state that only survives being
killed. Run it after installing musicviz-debug.apk.
Log: `adb logcat -s projectM-jni`

Items are numbered once and keep their number forever. The README's changelog
cites them by range ("DEVICE_CHECKS items 21-27"), so a renumbering silently
retargets those citations at the wrong checks; new work is appended instead,
and an item that stops applying says so in place rather than being deleted.

Last reconciled against the app at version 1.7.0 (code 31).

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
    how fast the streams settle into the flow; "Particle life (s)" sits
    directly under it (it moved out of the Journey section — see item 35)
    and must still work. On FLUID nothing changes: the layer checkbox
    still gates drag, life and brightness. On WATER the Particles section
    stays absent.
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
    (The former limitation — that both settings reached LIVE analysis
    only — is closed; check 30 covers the offline/export side.)
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
28. Audio drive + Beat response on the fluid family (v1.1.x): play a
    track with a clear kick and open Visuals > Customize > Behaviour on
    FLUID. REGRESSION SIDE FIRST — at the defaults (Audio drive 1.0, Beat
    response 1.0) the style must look EXACTLY as it did before this
    change; load an old saved preset that never touched either slider and
    confirm the same. Now drag Audio drive to 2.5: the dye splats must
    kick harder, the curl swirl tighten and the canvas hold its ink
    longer (the quiet-passage fade is audio-driven too). Drag it to 0.2:
    the same track must read as a near-idle drift. It must ramp smoothly,
    and it must NOT wash out to a blown white frame at the top — that is
    what a double-applied gain looks like. Then Beat response: at 2.0
    every beat must stamp a visibly wider, faster, brighter splat; at 0
    the beat pattern must stop firing entirely while stirrers, sparkle
    and bass pump keep running (the silence between beats is the tell).
    Repeat the sweep on WATER — drops grow and travel harder with Audio
    drive, beat rings vanish at Beat response 0 while stirrer wakes
    continue — and on CURL FLOW, where Beat response was inert before: at
    2.0 the field must lurch on the beat and the streams flash, at 0 the
    flow must stay perfectly even through a drop. On Curl Flow also check
    the TOP of Audio drive specifically: 2.0 -> 2.5 used to be flat and
    must now still increase the flow. Finally MILKDROP: Beat response
    must still change how eagerly presets react (it drives projectM's own
    beat sensitivity) and Audio drive must do NOTHING there — deliberate,
    not a bug to file (PARAM_MATRIX note 5). Leave FLUID at Audio drive
    2.5 for a minute and watch the frame rate: no auto-quality downgrade
    spiral, no NaN/black frame.
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
30. Beat sensitivity reaches exports and the cached analysis: pick a slow,
    sparse track and let it analyse fully (Settings shows the analysis
    cache growing by one track). At the shipped defaults export a short
    clip on a beat-reactive style — the flashes in the file must land
    where playback flashed. Now open Settings and tap "Slow track"
    (4.5σ / 700 ms), then export the SAME track again WITHOUT
    re-analysing: the cache entry count must NOT change (no second
    analysis pass, no progress bar crawl), and the new file must flash
    noticeably less — on the kick only — matching what live playback now
    does. Drag the sliders back to "Default" and export once more: the
    beat grid must come back to what the first clip had. Do the same in
    MANUAL intelligence mode, which only reads the cache: the fluid
    journey's beat response must follow the sliders there too.
    MIGRATION SIDE — install this build OVER a build from before it with
    tracks already analysed. Those entries are the old v1 format and are
    silently dropped: the first play/export of such a track re-analyses it
    once (progress bar), then behaves as above. Nothing must crash, and
    the "Analysis cache: N tracks" line must recover to a sensible count.
    Settings > "Clear" on the analysis cache must still empty it.
31. "Beat pulse" on the styles that never read it: play a track with a
    clear kick and open Visuals > Customize > Motion. On FLUID, CURL
    FLOW, WATER and on a live MilkDrop preset, drag "Beat pulse" from 0
    to 1 — the whole frame must now swell on each beat (a zoom-in of a
    few percent that falls away in about a third of a second) and settle
    back to still when the slider returns to 0. Before this change the
    slider did nothing at all on those four. Check the swell is centred:
    the middle of the screen must not drift while it pulses. Then set
    Zoom well above 1 on a fluid style and pulse again — the two must
    compound (pulse on top of the zoom), not fight.
    REGRESSION SIDE — on a shader style (julia/plasma) and on a particle
    style the slider must feel EXACTLY as before: those pulse in their
    own pipeline (uPulse / a point-size swell) and are excluded from the
    composite pulse, so a doubled swell — roughly twice the magnification
    on a shader style, or particles that both grow AND zoom — means the
    exclusion set is wrong. Note MilkDrop is excluded from the composite
    GRADING block but deliberately NOT from this one.
    EXPORT SIDE — with "Beat pulse" at ~0.8 on a fluid style and again
    on a MilkDrop preset, record a ~10 s clip and play the mp4 next to
    the live view: the swells must land on the same beats and be the
    same depth. Record the same settings at 30 fps and at 60 fps — the
    decay must look identical in both files; a 30 fps clip whose pulses
    hang around twice as long means the envelope is not running on the
    export's own frame delta.
32. Shape > "Particles" section per style. Open Visuals > Customize >
    Shape and walk the styles. On a particle style (nebula / bursts /
    swarm / fountain / orbits) the section must show BOTH "Particle
    shape" and "Particle size", and both must still work: switch the
    shape chips and the sprites change silhouette, drag the size slider
    and they grow. On FLUID and CURL FLOW the header and "Particle size"
    must be there but the shape chips must be GONE — those sprites are
    always round — and the slider must visibly resize the points on both.
    On the shader styles (julia / plasma / …), on MilkDrop and on WATER
    the ENTIRE section must be gone: no "Particles" heading left behind
    with nothing under it, and no gap where it used to be. Scroll the
    whole tab on a shader style to confirm Distortion / Symmetry &
    tiling / Stylize are untouched and it simply ends after Posterize.
    FLUID SPECIAL CASE — on FLUID open the Fluid tab, untick "Particle
    layer", then return to Shape: "Particle size" must STILL be visible,
    now with a one-line note saying the layer is off. Tick the layer back
    on and the note disappears and the slider resizes the points again.
    A vanished slider here is the bug, not the fix.
    REGRESSION SIDE — with a preset saved on a particle style, apply it
    while a shader style is active and then switch to the particle style:
    the shape/size it stored must still be in effect (gating hides the
    controls, it must never change or reset the params). Press "⚄
    Randomize unlocked" on a shader style, then switch to a particle
    style: shape and size must have rolled there too, and locking either
    on the particle style must hold it across further rolls.
33. MilkDrop palette tint (Color > Palettes, "MilkDrop palette tint"):
    load a handful of your own .milk presets, pick one with strong
    colour and one that is mostly white/grey, and start at 0. AT 0
    NOTHING MAY CHANGE — that is the whole contract: apply a preset
    saved before this build and confirm it looks exactly as it did.
    Now drag the slider up on the saturated preset with the palette set
    to Fire: the frame must slide into the reds while keeping its
    shapes, its light and shade and its motion — VALUE is never touched,
    so nothing may get brighter, darker or flatter, only differently
    coloured. Cycle the palette chips (Ocean, Aurora, Mono) at tint 1:
    each must be a clearly different colour world. Then switch between
    two DIFFERENT presets at tint 1 — they must still look like two
    different presets, not one. If they read as identical the tint is
    replacing colour instead of steering it, and the stage is wrong.
    GREY SIDE — on the white/grey preset the tint must still bite: its
    cores take the palette entry their own brightness selects. Watch a
    large flat white area as the slider moves; it must colour up
    smoothly, with no speckle or banding at the edge of the flat region.
    INTERACTION SIDE — with the tint at ~0.7 drag "Hue range" from 0 to
    1.5: at 0 the frame collapses toward one hue and it must widen
    smoothly, the 1.0–1.5 band included (unlike the fluid styles, which
    clamp it). Drag "Hue shift" and switch "Color cycle" on: the tinted
    frame must rotate as a whole, one turn per slider unit — a hue that
    travels twice as far as on a shader style means the tint is being
    applied after the rotation instead of before it. Build a palette in
    the gradient/palette maker, save it and select it: the tint must
    follow it with no separate handling, and the same for a custom
    palette restored from a saved preset.
    REGRESSION SIDE — switch to a shader, a particle and each fluid
    style with the tint at 1: nothing may change on any of them, since
    only MilkDrop reads it. Tap "⚄ Randomize unlocked" a few times on
    MilkDrop: the tint must move sometimes and mostly sit at 0, and
    locking "MilkDrop palette tint" must hold it.
    EXPORT SIDE — export a ~10 s clip of a MilkDrop preset at tint ~0.7
    and play it beside the live view: the colours must match. The tint
    lives in the scene's own post pass, so the exporter gets it for
    free — a clip that comes out untinted means the export path is
    building the scene without it.
34. Cross-family TRANSITIONS must not flash, and Curl Flow must look
    right the moment it is selected. TRANSITION SIDE — Visuals >
    Customize > Transition: style "Fade", duration 1200 ms (the
    default). Now push the grade hard: Brightness 2.0, Intensity 2.0,
    Zoom 2.0, Contrast 2.0. Play a track on JULIA, then switch to FLUID
    and watch the OLD image for the whole 1.2 s: it must fade out
    looking exactly as it did while it was live — same exposure, same
    zoom. A white, blown-out, over-zoomed old frame means the outgoing
    texture is being graded under the incoming scene's rule (it was
    already graded in julia's own shader, so it lands twice: 16x
    brightness, 4x zoom, contrast squared). Switch straight back
    (FLUID -> JULIA) and watch the outgoing FLUID frame: it must KEEP
    its exposure and zoom for the whole fade, not snap dark/small the
    instant the switch starts. Repeat both ways with the SLIDE, ZOOM and
    MELT transition styles, and again with a cross-family PRESET switch
    (a julia preset -> a fluid preset) and with auto-switch on with
    "Include styles" enabled — every one of those routes through the
    same composite. Also do a same-family switch (julia -> plasma,
    nebula -> bursts) as the control: those looked fine before and must
    be unchanged. REGRESSION SIDE — with no transition in flight, every
    style must look EXACTLY as it did in the previous build; sweep
    Warp/Kaleido/Tile/Posterize/Mirror/Invert/Temperature/Solarize on a
    shader style (must still be applied once, in-shader) and on a fluid
    style (must still be applied once, in the composite). Record a ~10 s
    export on a fluid style and on a shader style and compare each with
    the live view — exports never transition, so they must be pixel-wise
    unchanged. CURL FLOW SIDE — from a clean install (or after Reset),
    open Visuals > Styles and tap "Curl Flow" WITHOUT touching anything
    else. Trails is off by default: the streams must still read as
    moving strands with a short motion-blur tail, never as a field of
    dots strobing on a black canvas. Now switch Trails ON — the tails
    must visibly lengthen into long ribbons, and the "Trail length"
    slider must sweep smoothly from short to nearly-permanent. Switch
    Trails back off — the tails must shorten again (the toggle is a
    band, not a wipe). Turn "Trail zoom"/"Trail warp" up with Trails on
    and off in turn: neither may break the streams into dots. Export
    ~10 s of Curl Flow with Trails off and again with it on: the mp4s
    must match the live look in both cases.
35. Ripple-overlay physics and particle life reach exactly the styles that
    read them (Visuals > Customize > Fluid). OVERLAY SIDE — pick PLASMA (or
    any non-fluid style), switch "Water ripples enabled" on and confirm
    "Wave speed" and "Damping" now appear right under it, above "Ripple
    overlay strength". Drag Wave speed 0.2 -> 2.0 on a beat-heavy track: the
    rings must visibly expand slower/faster from where they drop. Drag
    Damping 0.9 -> 0.999: at 0.9 a ring must die almost immediately, at
    0.999 it must ring on for seconds and the surface never settle. Both
    must behave the same on a MilkDrop preset and on a particle style, and a
    ~10 s export at an extreme setting (Damping 0.999) must match the live
    look — the exporter runs the same solver. Switch the overlay off: both
    sliders disappear with the rest of its controls.
    WATER SIDE — select WATER: "Wave speed" and "Damping" must appear ONCE,
    in the "Water" section at the top of the tab (that style's own surface
    physics), and must NOT be repeated in the "Water ripples (all styles)"
    section even with the overlay checkbox on. Sweep both there and confirm
    the surface responds exactly as it did before this change.
    PARTICLE LIFE SIDE — on WATER, "Particle life (s)" must NOT appear
    anywhere (Water has no particle layer); the rest of the Journey section
    — Path, Spawn points, Progression, Catch points, Catch pull, Catch
    radius — must still be there and each must still visibly move where the
    drops and wakes land. On CURL FLOW the slider must appear next to
    "Particle drag" in the Particles section, and sweeping it 1 -> 20 s must
    visibly change how far a strand travels before it is recycled. On FLUID
    the same, and switching "Particle layer" off must take BOTH Particle
    drag and Particle life away, then bring both back when switched on.
    LOCK SIDE — lock "Wave speed" on a non-water style and press "⚄
    Randomize unlocked" ten times: the ripple speed must hold (the roll
    moves it otherwise), and the chip must still read "locked" after
    switching to WATER, since it is the same param.
    LABEL SIDE — the Behaviour tab now reads "Reactivity attack" /
    "Reactivity decay" and the FX tab's envelope cards "Env attack" / "Env
    decay". Lock "Reactivity attack" and confirm the envelope cards' attack
    sliders show NO lock chip (they used to mirror it), and the reverse.
36. Exported video pulses on EVERY beat at every frame rate, and the
    Hue range slider means the same thing on every family. BEAT SIDE —
    pick a track with an obvious four-on-the-floor kick and a style in
    the fluid family (Fluid, Curl Flow or Water). Visuals > Customize >
    FX: "Beat pulse" ~0.8, and turn "Beat flash"/"Beat shake" up too so
    the beat is unmissable. Export the same ~20 s at 60 fps and again at
    30 fps (4K also falls back to 30 fps on its own). Play the two clips
    side by side against live playback: the 30 fps clip must swell and
    flash on EVERY kick the 60 fps clip does — same count, same places.
    A 30 fps clip that pulses on roughly every OTHER kick (and a 24 fps
    one that is worse still) is the exported frame sampling one 60 Hz
    analysis frame instead of the whole span it covers; the beat flag is
    one frame wide, so half of them fall in the gap. Also check the
    continuous motion is UNCHANGED against the previous build: bass
    swell, band-driven speed and the waveform must look the same, not
    softened — only the beat impulses were meant to change. HUE RANGE
    SIDE — on Fluid, Curl Flow and Water, drag Visuals > Customize >
    Color > "Hue range" across its WHOLE travel. Colour must keep
    widening past the two-thirds mark all the way to the top (above 1.0
    the palette walks more than one turn of the wheel); the top third
    looking identical to the middle is the bug. At the very bottom of
    the slider the style must narrow to a tight band but never collapse
    to one flat colour. Compare the same slider positions against a
    shader style (e.g. Julia) and a particle style (e.g. Swarm): the
    amount of colour spread must track across all three families. On
    WATER specifically, watch the splashes as well as the pool — both
    must widen together.

37. Beat tracking restarts on a track change and on a seek (v1.1.x): the
    analyzer keeps ONE tempo grid, energy envelope and flux window for the
    whole session, and it now clears them whenever the audio jumps.
    TRACK CHANGE — queue a loud, fast, four-on-the-floor track and a quiet,
    slow one back to back, and watch the FIRST FIVE SECONDS of the second
    track. The visuals must react to its kicks straight away and at a
    sensible size. The bug this fixes looks like near-total deafness at the
    start of the new track (headless measurement on a 128 -> 75 BPM change:
    1 beat in the first 5 s instead of 6, and the few that survived came
    through at roughly a third of their proper strength), fading in over
    ~15 s as the old grid decayed. Try it in both orders — quiet-then-loud
    is milder but should also be wrong-free — and on shuffle/next as well
    as auto-advance at the end of a track.
    SEEK — during one track, drag the transport bar a long way (a verse to
    a chorus and back). Beats must keep landing on the music immediately
    after the seek, not go quiet for several seconds. Repeat while paused,
    then play: the first beats after resuming must be right too.
    EXPORT PARITY — this is the check that matters most. Play a track from
    the start, then WITHOUT changing anything export ~20 s of it and play
    the clip against live playback. They must pulse on the same hits. Then
    do the same after arriving at the track from a different one: the
    export must still match, because both now start from a cold tracker.
    NOT AFFECTED, and worth confirming: pause/resume in place is not a
    discontinuity and must NOT reset — the visuals should pick up where
    they were, not re-acquire the beat grid.

38. Beat-sensitivity slider no longer stalls (v1.1.x): the tempo
    autocorrelation used to run on every frame of an offline replay.
    Open Settings > Visuals & Analysis on an ANALYSED track (one showing
    the key/BPM badge) and drag "Beat sensitivity" and "Minimum gap
    between beats" across their whole range, in both directions, without
    pausing. The UI must stay smooth and the visuals must follow the
    slider within a moment of each settle; the bug looks like the whole
    app hitching for a second or more per settle (measured at ~1.5 s per
    call on a 4-minute track, now ~60 ms on the same machine). Same check
    when STARTING a cached track and when pressing Export: both run the
    same replay and both used to stall. Confirm the beat behaviour itself
    is unchanged at the default settings — this was a cost fix, and the
    headless gate pins the decisions as byte-identical.

39. Visual safety (v1.1.x) — Settings > Visual safety. Read the WARNING at
    the end of this item before running the "off" half.
    OFF IS UNCHANGED — with both switches off, everything must look exactly
    as it did before this feature existed. Compare a preset with strobe and
    flash turned up against the previous build: identical rate, identical
    depth. This is the property the headless gate pins (the clamp returns the
    same object instance when neutral), but it is worth one eyeball.
    RATE, NOT JUST DEPTH — this is the point of the change. Load a preset with
    "Strobe" at maximum. With Safe visuals OFF the whole frame flickers about
    nine times a second. Turn Safe visuals ON: it must visibly SLOW to about
    three, not merely get dimmer. A dimmer 9 Hz flicker means `uStrobeHz` is
    not reaching the shader — check that both the live path and an export
    changed, since they upload it from different files.
    BEAT FLASH — set "Beat flash" high and play something fast (170 BPM+).
    With Safe visuals on, flashes must be capped at the "Maximum flashes per
    second" setting; Settings > Visuals & Analysis will show the minimum gap
    between beats has been floored to match. Drop the flash-rate slider to
    1 Hz and confirm the flashing slows further.
    THE MODULATION PATH — Visuals > Customize > Mod: set LFO 1 to target
    Brightness, wave SQUARE, and drive its rate as high as it will go (use
    beat-sync at 1/16 on a fast track, or chain LFO 1 -> LFO 2 rate, to get
    past the 8 Hz slider limit). With Safe visuals OFF this is the harshest
    flash the app can make. With it ON the oscillation must slow to the flash
    limit while keeping its shape. Repeat with Intensity, and confirm a
    Rotation-targeted LFO is NOT slowed (that is motion, not flashing).
    RANDOMIZER — press Randomize twenty times with Safe visuals on, on a loud
    track. Nothing in any roll may produce a fast full-screen flash. This is
    the realistic path to a dangerous frame, since the randomizer can roll
    strobe, flash, glitch, invert and an LFO onto brightness.
    TRANSITIONS — set the transition style to Cut. With Safe visuals on,
    scene and preset changes must crossfade instead of snapping. Turning
    safety back off must restore Cut (the stored choice is not overwritten).
    INVERT — with Safe visuals on and "Allow invert and solarize" off, the
    Invert and Solarize checkboxes must have no visible effect. Turning that
    sub-switch on restores them while the rate and depth limits still hold.
    REDUCED MOTION — independent of the above. On its own it must slow
    movement, shake, drift and rotation and leave flashing exactly as it was.
    EXPORT PARITY — the one that matters most. With Safe visuals on, export
    ~20 s of a track whose preset has strobe and flash up, and play the clip
    next to live playback. The clip must be as slow and as shallow as the
    screen was. An export that flashes harder than the screen is the failure
    this whole file exists to catch, and it has its own risk here because the
    live and export paths clamp in two different files.
    WARNING: the "Safe visuals OFF" comparisons in this item deliberately
    produce fast full-screen flashing. Do not run them if you are
    photosensitive, and do not hand this checklist to someone else to run
    without saying so.

40. Cymatics (v1.3.0) — Visuals > Styles > Cymatics. The claim this style
    makes is that the picture IS the sound, so check that before anything
    else: play a pure sine tone (a tuner app through the microphone on live
    input works) and expect ONE clean symmetric figure that holds still while
    the tone holds. Play a chord and expect the superposition of its notes'
    figures, not a busier version of the same one. Stop the audio and the
    field must fall almost to nothing rather than idling on a pattern.
    Sweep "Fundamental" — the figure must get FINER as the pitch rises (mode
    order goes with the square root of frequency, so the change is gradual,
    not a jump). "Geometry" switches between Water dish (concentric rings
    crossed by petals) and Chladni plate (a square nodal lattice); both must
    answer the same music. Sweep Modes, Ring, Focus, Scale, Fill, Line, Glow,
    Iridescence, Caustic, Flow and Swirl — each must visibly change the
    field, and none may produce a black frame at either end of its range.
    Watch a large flat region as Caustic rises: it must stay smooth, with no
    speckle. Export ~10 s and compare: the figures must land on the same
    notes. The style is composite-graded, so also confirm Hue shift,
    Brightness, Contrast, Gamma, Zoom and Rotation bite exactly once (item 23
    describes what a double-apply looks like).
41. Hyperspace (v1.4.0) — Visuals > Styles > Hyperspace, the first raymarched
    style, and the one most likely to be slow or wrong on an unusual driver.
    FRAME RATE FIRST: play a busy track for a minute at the shipped Detail and
    watch for a downward drift. Then walk Detail across its range — it must
    trade sharpness for speed smoothly and never black-frame.
    Confirm the design: several bodies alive at once, each turning on its own
    clock and orbiting on its own path, budding on hits and dissolving. A room
    where everything turns together is a regression. Pick each "Fractal" in
    turn (Gasket, Temple, Jewel, Coral, Bulb) and confirm five different
    creatures rather than five settings of one; leave it Mixed and confirm
    they coexist. Three of the five describe unbounded sets, so watch
    specifically for a body STREAKING across the whole scene as a stripe —
    that is the bounding-sphere clip failing.
    Journey: "Music" must visibly deepen the act on loud passages and come
    back more slowly on quiet ones; "Hold" must pin one act; "Cycle" must walk
    all five on a timer. In every mode the camera must stay OUTSIDE every body
    — a screen of flat stripes is the camera inside a folded estimator, and it
    is the failure this style's tests exist to prevent. Leave the app paused
    on Hyperspace for three minutes: the idle drive must keep walking the
    journey and spawning bodies rather than parking on the empty opening act.
    Check the three built-in presets (`hyperspace · Breakthrough`,
    `· Chrysanthemum`, `· Coral garden`) load and look distinct.
    VISUAL SAFETY: nothing here may strobe. The kaleidoscopic mirror is the
    one discontinuous change and may only happen on an act change, never more
    than once every four seconds.
42. The melt on Hyperspace (v1.6.0) — Customize > Hyperspace > Melt. At Melt 0
    NOTHING MAY CHANGE and nothing may be spent: compare against a preset
    saved before the melt existed and watch the frame rate, which must be the
    same as with the group untouched.
    Raise Melt: the geometry must STRETCH and smear like taffy, as one
    continuous medium, rather than turning as a rigid object. Watch a body
    drift across the room — it must leave a wake of its own colour behind it,
    and a body being born or dissolving must bloom ink outward. Drag a finger
    across the canvas: the fractals it crosses must be pulled out of shape and
    stained in the same gesture, and the ink must stay stuck to the WORLD when
    the camera moves — ink that slides across the bodies as the view turns is
    the field being screen-anchored.
    Two failure signatures to watch for specifically. A body sliced along a
    perfect circle is the displacement exceeding the bounding-sphere
    inflation. A ray walking straight THROUGH thin geometry (filigree that
    disappears when the medium is stirred hard) is the march step not being
    relaxed. Both are pinned headless, but a driver that computes the field
    differently can still reach them.
    Sweep Ink stain, Liquid light, Ridges, Stir, Vorticity and Flow fade. Ink
    stain must read as WET on the surfaces it lights, not as a decal. Liquid
    light must dim a body that has ink in front of it, and the near side of a
    cloud must be brighter than the far side. Ridges must comb each surface
    across the flow, hue included. Look hard at a heavily inked region for
    concentric shells or contour lines — that is the jittered sampling
    failing. Load `hyperspace · Molten` and confirm the ink builds into strata
    over a minute rather than washing flat.
    ON A DEVICE WITHOUT HALF-FLOAT SIM BUFFERS the style must still run, as
    solid geometry, with the whole medium gated off — not black, not crashed.
43. Beam (oscilloscope trace) — Visuals > Styles > Beam. The trace must be
    drawn the way a real oscilloscope makes one: a continuous glowing stroke
    whose brightness follows how long the spot dwells, not a polyline of
    segments with visible joins. Toggle "X/Y" — the trace must switch from a
    time sweep to a vectorscope figure, and a stereo track with wide imaging
    must open the figure out. Sweep Width, Intensity and Tail: Tail must
    lengthen the persistence smoothly and the shortest setting must still read
    as a trace, not as dots. Export ~10 s and confirm the same.
44. The particle look, on every particle style — Visuals > Styles > Particles.
    Walk nebula, bursts, swarm, fountain, orbits, galaxy, attractor, storm,
    inkflow, starfield, winter and lava. Every one must draw instanced glowing
    billboards with SDF shapes, and "Particle shape" must change the
    silhouette on all of them (item 32 covers where the section is shown).
    Winter and lava are rebuilt for the current engine, so give them a
    specific look: winter must read as falling snow that settles and drifts on
    the beat rather than as generic white points, and lava as rising, cooling
    blobs whose colour follows their age. Both must honour Speed, Density,
    Audio drive, Particle size and the palette like the rest of the family,
    and both must appear in Styles, in the randomizer's style pool and in a
    saved preset's roundtrip.
45. Home, artwork, and the listening figures (v1.7.0) — the Home tab.
    The hero card must be live: artwork, transport, progress and a spectrum
    that moves with whatever is feeding the analyser (a track, the microphone,
    another app's audio). The spectrum runs on its own 20 Hz clock, so watch
    the LIST while it moves — scrolling a shelf must stay smooth.
    Shelves: Jump back in, Favourites, On repeat, Recently added, and your
    saved looks. Tapping any card must queue THAT WHOLE SHELF starting at the
    tap, not just the one track. Tracks with no embedded picture must get a
    stable gradient derived from their uri — the same track must show the same
    gradient everywhere it appears, and across a restart.
    THE FIGURES ARE THE POINT, and they are measured rather than counted.
    Play a track for two minutes and check the week strip grows by about two
    minutes, not by one "play". Then SEEK repeatedly through a track for a
    minute: the figure must grow by roughly the minute you were there, not by
    the distance you seeked. Background the app for ten minutes with playback
    stopped and confirm nothing was banked while it was asleep. Kill the app
    from Recents and reopen: every figure must survive (see item 47).
46. Other apps' audio (v1.7.0) — Settings > Visualize other apps, or the quick
    action on Home. Start YouTube or a podcast player, come back, switch it
    on, grant the projection prompt: the visuals must react to what that app
    is playing, on every style, and the exporter and live wallpaper must be
    unaware of where the samples came from.
    A foreground-service notification must be present for as long as it runs —
    it is the honest statement that the app can currently hear the device —
    and revoking the capture from the system UI must tear the whole thing
    down cleanly.
    THE SPOTIFY CASE is the one that matters. Spotify declares that it may not
    be captured, and the system honours that by handing over perfect digital
    silence rather than an error, which looks exactly like a working
    visualizer with nothing to draw. Play Spotify with capture on: within a
    few seconds the app must SAY it is being refused, name Spotify, and offer
    the microphone instead. It must not say that about a genuinely silent
    passage of a track that is allowed — leave an allowed app paused for a
    minute and confirm no false accusation appears.
    Naming the foreign track is separate: without notification access the
    visuals must still work and only the title be missing.
    Nothing captured may be written to disk or leave the device; the app holds
    no INTERNET permission, so a network access here is a build error, not a
    setting.
47. The player's face (v1.7.0) — expand Now Playing and walk Now / Lyrics /
    Queue over the live canvas.
    SEEK BAR: on an analysed track it must be the track's own loudness curve,
    with peaks that line up with the loud parts when you scrub to them; on an
    unanalysed track a plain bar. Drag it — the position must commit on
    RELEASE, not per pointer event (a decoder re-preparing dozens of times per
    drag stutters audibly).
    LYRICS: put a `.lrc` beside a track and confirm it beats the file's own
    embedded tags. A line carrying several timestamps (how LRC writes a
    repeated chorus) must appear at each of them. Tapping a line must seek to
    it. Nothing may be fetched.
    QUEUE: the highlighted row must follow what you are hearing and scroll to
    it. Tap a row to jump, pull a row forward, remove a row. Removing a row
    ABOVE the playing one must not skip or restart playback; removing the
    playing row must advance.
    Favourites (the heart, the Home shelf, the star in the queue) must agree
    with each other immediately and survive a restart. A-B repeat must paint
    into the waveform and loop exactly between the marks. Fades (0-6 s) must
    apply on pause, resume and skip — and must NOT be a crossfade, which one
    player cannot do. The sleep timer's "let the track finish" must do that
    rather than cutting at the timer.
48. The Export Studio (v1.7.0) — the Studio tab. It must list what MusicViz has
    rendered and also open any video.
    Trim over the filmstrip and render: the output must start and end where the
    handles were, to the frame. A TRIM-ONLY edit must be fast and lossless — a
    trim that takes as long as a full export is the transformer re-encoding
    when it did not need to.
    Then each edit in turn: the seven looks (each must write ordinary grade
    slider values you can then see and adjust), brightness/contrast/saturation/
    hue, speed, rotate, reframe to each of the six export ratios (which must
    CROP, never pillarbox), mute, and a burnt-in caption.
    EVERY RENDER MUST BE A NEW FILE in Movies/MusicViz — check the original is
    still there and unmodified after each one. "Send" must open the system
    share sheet; the app holds no network permission and no API keys, so
    anything that looks like a direct upload is a bug.
49. Live wallpaper and second screen. Set MusicViz as the live wallpaper: it
    must render the current style behind the launcher, react to what is
    playing, and — this is the part that regressed once — STOP working when
    nobody is looking. Lock the screen or open a full-screen app and confirm
    the wallpaper's GL work stops rather than draining the battery behind the
    foreground; come back and confirm it resumes rather than staying black.
    With nothing playing, the idle drive must keep it alive with audio-shaped
    motion rather than freezing.
    Second screen: connect an external display (HDMI or Cast). The visuals
    must move to it fullscreen while the phone keeps the controls, and
    disconnecting must return them to the phone without killing playback.
50. Performance takes, live input, gestures and shared presets.
    TAKES: record a take while moving sliders, switching styles and applying
    presets, then replay it — everything you did must come back, including
    changes made by Randomize and the auto-switcher. Save two takes with the
    default name in a row: the second must get a numeric suffix, never
    overwrite the first (a performance cannot be repeated). Rename and delete
    from the list. Export a take at a higher quality than it was recorded at.
    LIVE INPUT: switch the microphone on — playback must pause (one analysis
    window, one source), the permission must be asked for AT THE SWITCH and
    never at launch, and the state must never come back on after a restart.
    Nothing may be recorded or transmitted.
    GESTURES on the fullscreen canvas: swipe down collapses, swipe up opens
    the queue, swipe left/right steps presets, double-tap at an edge seeks.
    SHARED PRESETS: export a preset and re-import it on the same device; a
    MilkDrop preset must carry its .milk source, so it must render the saved
    visual rather than projectM's idle logo.
51. Auto visuals (Settings > Auto visuals) — these controls existed in the
    engine for a long time with nothing wired to them, so the check is that
    each one now BITES. Turn Random on from Now Playing's Auto button, then:
    set the interval to its shortest and confirm looks change at about that
    rate; turn "on the beat" on and confirm changes land on beats instead of
    on the clock; turn each of the three "pick from" sources (styles, saved
    presets, MilkDrop) off in turn and confirm the roll stops offering that
    kind; turn all three off and confirm the section SAYS the roll now does
    nothing. Turn "roll colours" on and off and confirm the palette does or
    does not move with each change.
    Then the visual playlist: turning it on must CLEAR Random, and you must be
    able to watch that happen in the section's live status line rather than
    discover it later. Both interval sliders must stop exactly where their
    setter clamps — a slider that can ask for something the engine refuses is
    the bug this section fixed.
52. A failed export says so. Start an export and make it fail (fill the
    device, or revoke storage access mid-render). The app must report the
    failure with what went wrong, and must NOT report success or leave a
    zero-length file in Movies/MusicViz. Cancel an export half way: same
    rule. Then export a long track and watch the UI while it runs — the
    progress must move and the app must stay responsive, because the file
    work is off the main thread.
53. A rejected shader darkens one style, not the app. Visuals > Styles >
    Shaders > the GLSL editor: paste something that cannot compile and apply
    it. The style must fall back to its last working program and show the
    error text; the app must not crash and every OTHER style must keep
    rendering. Do the same for a broken fluid force shader (item 11) and for a
    .milk preset projectM rejects.
54. Nothing on disk is lost to being killed mid-write. Build up real data:
    several playlists, a few saved palettes, imported textures, a couple of
    performance takes, and a day of listening history. Now kill the app from
    Recents WHILE it is writing — the easiest triggers are during an import of
    several textures, immediately after saving a palette, and on the track
    change at the end of a song (which is when the history is written).
    Reopen: every playlist, palette, texture, take and the listening figures
    must still be there. Repeat a few times, and once with a force-stop from
    Settings > Apps rather than a swipe.
    What must never appear: an EMPTY history, an empty playlist list, a
    playlist that suddenly holds one track, a texture that renders as noise or
    black, or a take that will not replay. If a document really was damaged it
    is moved aside as `<name>.corrupt` in the app's private storage rather
    than overwritten, so `adb shell run-as dev.musicviz ls files` after a
    suspicious loss says whether anything was quarantined.
