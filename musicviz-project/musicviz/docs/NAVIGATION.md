# MusicViz — Navigation Map

Edit freely; this file is the single source of truth for navigation.
Legend: `[button]` action · `>` opens · v0.13.0 (navigation-v2 shell)

Architecture: BOTTOM-NAV SHELL (AppShell.AppRoot). Four destinations with a
persistent mini-player docked above the nav bar. The fullscreen visualizer
("Now Playing") is an overlay expanded from the mini-player; the single
VisualizerView is owned by the shell so renderer state survives
collapse/expand. Search is a fullscreen overlay available from Home and
Library.

```
APP SHELL (Scaffold)
├── MINI-PLAYER (hidden until media loaded; tap > Now Playing overlay)
│   ├── title — artist · [▶/❚❚] · [▶▶]
│   └── thin progress bar
├── NAV BAR:  Home · Library · Visuals · Settings
│
├── HOME
│   ├── [Search 🔍] > SEARCH overlay (tracks + visual presets)
│   ├── Resume card (tap > Now Playing)
│   ├── [Shuffle all]
│   └── Recently played · Most played chip rows
│
├── LIBRARY (tabs: Tracks · Albums · Artists · Folders · Playlists)
│   ├── Albums/Artists/Folders: two-level drill-in (group > track list,
│   │   "‹ Back" or system back pops the level)
│   └── [Search 🔍] > SEARCH overlay
│
├── VISUALS hub (tabs: Presets · Styles · Customize · Textures)
│   ├── [View live] > Now Playing overlay
│   ├── Presets: user folder tree ([+ folder], apply, ♥ viz-playlist, 🗑)
│   │   · built-ins for the current scene · [Save current as…]
│   ├── Styles (sub-tabs: Particles · Shaders · Fluid · MilkDrop)
│   │   · one pick path: selectScene > vizState > EnginePlumbing > renderer
│   │   · MilkDrop: [Load .milk] · [Textures…] · your .milk list
│   ├── Customize (sub-tabs: Motion · Shape · Behavior · Color · FX ·
│   │   Fluid · GLSL when shader scene) · [⚄ Randomize unlocked] ·
│   │   per-param locks · LFO/ADSR editors in FX
│   └── Textures: [Import images] · per-texture [Use]
│
├── SETTINGS
│   ├── Look: theme chips (8 crystal design-sheet themes first — Rose
│   │   Quartz, Sugilite, Lapis Lazuli, Malachite, Kyanite, Amethyst,
│   │   Onyx, Clear Quartz — then the original set) · font color
│   │   swatches (Auto + 7 fixed) · bar opacity
│   ├── Player: position · corner style
│   ├── Paths: preset mirror folder (SAF)
│   ├── Analysis: cache view/clear · preset morph beats · beat threshold
│   └── [Export video…] > export host dialog
│
├── SEARCH overlay (fullscreen; tracks + visual presets; tap applies/plays)
│
└── NOW PLAYING overlay (VisualizerScreen, fullscreen canvas)
    ├── tap canvas: hide/show controls
    ├── drag canvas: finger smear — stirs the fluid velocity field so any
    │   style mixes around under the finger (FLUID also paints ink);
    │   works with FlowField off via a ~2.5 s touch-wake
    ├── collapse chip · title/artist
    ├── transport card: seek · shuffle/prev/play/next/repeat
    └── [Visuals] (> hub) · [Auto: off/random/smart]
```

## System back button (integrated v0.13.0)

Compose `BackHandler`s, last-composed enabled handler wins, so back unwinds
in overlay order:

1. Now Playing expanded  → collapse to the shell
2. Search overlay open   → close search
3. Library drill-in open → pop to the group list
4. Any non-Home tab      → return to Home
5. Home                  → system default (exit)
