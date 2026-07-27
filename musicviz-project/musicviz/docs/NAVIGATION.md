# MusicViz — Navigation Map

Edit freely; this file is the single source of truth for navigation.
Legend: `[button]` action · `(sheet)` bottom sheet over the canvas · `>` opens

Architecture: SINGLE SCREEN. The visualizer is the app. One glass player
panel (top or bottom per Settings) holds everything; all features open as
sheets over the canvas. No bottom bar, no tab navigation.

```
MAIN SCREEN (visualizer canvas, themed border)
├── tap canvas ............ hide/show the player panel
├── PLAYER PANEL (one glass panel; position top/bottom from Settings)
│   ├── row 1  ▸ Track Title — artist                      [⚙ Settings]
│   ├── row 2  [⇄ shuffle] [◀◀] [▶/❚❚] [▶▶] [↻ repeat]
│   ├── row 3  0:42 ────●──────── 3:51   (seek)
│   └── row 4  [Style] [Customize] [Presets] [Library] [⚄ Random]
│              (the old canvas bottom bar is GONE — these are its icons)
│
├── [Style] > (STYLE sheet) — tabs: Particles | Shaders | MilkDrop
│   ├── Particles: Nebula · Bursts · Swarm · Fountain · Orbits
│   ├── Shaders:   scene grid (post style-dedupe)
│   └── MilkDrop:  [Load .milk file] · [Textures…] · your .milk list
│       ├── (no built-in milk presets — removed)
│       └── [Next preset ▸]
│
├── [Customize] > (CUSTOMIZE sheet)
│   ├── tabs: Motion · Shape · Behavior · Color · FX · Mod · GLSL
│   ├── every param row: slider + [lock 🔒] (locked = skipped by ⚄)
│   ├── [⚄ Randomize unlocked]
│   ├── Mod tab: 3 LFOs + 2 ADSRs (multi-target: params or LFOs)
│   └── GLSL tab (shader scenes only)
│
├── [Presets] > (PRESET BROWSER sheet)
│   ├── tree: user folders > presets  [+ folder] [rename] [move]
│   ├── preset row: [apply] [🗑 remove]  (built-ins: no 🗑)
│   └── [Save current…] (into selected folder; milkdrop saves .milk too)
│
├── [Library] > (MUSIC LIBRARY sheet)
│   ├── tabs: Tracks · Playlists · Folders · Drive
│   ├── Tracks: analyzed badge (key/BPM) · [add] [play]
│   ├── Playlists: create · rename · drag-reorder · delete
│   ├── Folders: device tree from detected media paths (dedupe on import)
│   └── Drive: connect > browse > [download] or [stream]
│
├── [⚄ Random] cycle: off / random / intelligent (section+energy switch)
│
└── [⚙] > (SETTINGS sheet)
    ├── Look: theme · UI opacity slider · themed border on/off
    │         (crystal palettes/overlays: see open question)
    ├── Player: panel position (top/bottom) · size · touch feedback
    ├── Analysis: beat threshold · reactivity · key detection on/off ·
    │             analysis database (view/clear)
    ├── Paths: media folders (add/remove/rescan) · preset folder path
    ├── Export: quality · ratio · fps · [render] [render to folder…]
    └── About / version

(EXPORT PROGRESS) overlay — progress bar · [cancel]
```
