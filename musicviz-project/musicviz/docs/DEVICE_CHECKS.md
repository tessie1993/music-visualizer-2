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
21. Background playback (1.0.0): start a track, then lock the screen —
    audio must continue, and the lock screen must show title/artist with
    working play/pause/next. Swipe the notification's transport controls
    from the shade too. Now leave it playing and open several heavy apps
    to push MusicViz out of memory: playback must survive (it is a
    foreground service now). Return to the app: the visualizer must pick
    up the *same* track at the right position, NOT restart or jump to the
    auto-resume track — the ViewModel is rebuilt against the still-live
    player, so a regression here shows up as the queue being replaced.
    Then: pause, swipe the app off Recents — the service must stop and
    the notification disappear. With playback running, swipe Recents —
    audio must keep going. Bluetooth/wired headset play-pause and
    next-track buttons must work while the screen is off.
22. Equalizer + sleep timer after the service change (1.0.0): the audio
    session id now comes from a process-wide player, so re-verify that
    Settings > Playback > Equalizer still applies live, and that the
    sleep timer's 3 s fade still ramps and pauses.
