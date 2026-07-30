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
16. Winter style + finger smear (v0.13.1 r23): Styles > Shaders > winter
    (or apply "winter · Flurry"). Expect icy interference ripples, three
    parallax snowfall layers (treble twinkles the flakes, beats ring the
    ice), and voronoi frost glints. Drag a finger on the Now Playing
    canvas: ripples, frost and snow must swirl along the drag (scene-side
    uFlow smear + composite fluidWarp) and relax over ~2.5 s. The smear
    must ALSO work: on any shader scene, on particle scenes (particles
    ride the field), on MilkDrop/CurlFlow (composite warp), and on FLUID
    (drags stir the sim AND paint palette ink) - all WITHOUT FlowField
    enabled (touch-wake) and while music is paused. A plain tap must
    still toggle the controls, never splat.
17. Crystal themes + press animations (v0.13.2 r24): Settings > Look —
    the first 8 theme chips are the design-sheet crystal themes (Rose
    Quartz, Sugilite, Lapis Lazuli, Malachite, Kyanite, Amethyst, Onyx,
    Clear Quartz); each must recolor the whole shell (gradient backdrop,
    bars, accents) to its sheet palette, with the original themes still
    after them. First run defaults to Kyanite. Pressing ANY card, chip,
    nav item, scene row or transport button must spring it down ~6% and
    bounce back on release. Corner style (sharp/rounded/pill) must
    visibly change component radii; Bar opacity must fade the mini
    player + nav bar over the backdrop.
18. Font color (v0.13.2 r24): Settings > Look > Font color — Auto
    follows the theme; each swatch (Frost, Silver, Gold, Rose, Cyan,
    Violet, Mint) recolors UI text app-wide on any theme, persists
    across restart, and stays readable on dialogs and the nav bar.
