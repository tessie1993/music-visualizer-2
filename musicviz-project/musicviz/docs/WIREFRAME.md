# MusicViz — Wireframes (low-fi)

Structure only; GUI/visual design is a later pass. Sheets slide from the
bottom, ~60% height, glass background (opacity = Settings slider).

Glass status (implemented, v0.13.x): the shell chrome is alpha glass
driven by the Settings "Bar opacity" slider (range 0.2–1.0, stored as
`GuiPrefs.barOpacity`). Surfaces that respect the slider: the bottom
navigation bar, the mini player (glassPanel with hairline top border),
the Now Playing header chip and transport card (clamped to >= 0.25 so
controls stay readable; a bottom scrim gradient aids legibility over
bright visuals), and the Search overlay background (clamped to >= 0.85
— a fullscreen overlay needs more opacity). This is flat alpha glass:
no backdrop blur, and outside Now Playing the translucency reveals the
theme background color, since the GL canvas only lives behind the Now
Playing overlay in v1. Helpers: `ui/Glass.kt` (`glassPanel`,
`glassScrim`).

## Main screen — single glass player panel, canvas is the app
```
┌─────────────────────────────────┐ ← themed border
│ ▸ Track Title — artist    [⚙]  │
│ ⇄   ◀◀   ▶/❚❚   ▶▶   ↻        │
│ 0:42 ───────●──────────── 3:51 │
│ [Style][Custmz][Presets][Lib][⚄]│
│                                 │
│                                 │
│          VISUALIZER             │
│     (tap = hide whole panel)    │
│                                 │
│                                 │
└─────────────────────────────────┘
```
The old canvas bottom bar is REMOVED; its icons are row 4 of the player
panel. Panel honors the top/bottom position setting as one unit.

## Style sheet — MilkDrop gets its own tab
```
┌─ Particles | Shaders | MilkDrop ─┐
│ MilkDrop tab:                    │
│  [ Load .milk file ]  ← moved    │
│  [ Textures… ]        ← moved    │
│  ── Your .milk files ──────────  │
│  ▸ my_save.milk          [apply] │
│  ▸ imported_1.milk       [apply] │
│  [ Next preset ▸ ]               │
│  (built-in milk presets removed) │
└──────────────────────────────────┘
```

## Customize — randomizer, locks, Mod tab (LFO + ADSR)
```
┌ Motion Shape Behav Color FX Mod ┐
│ Speed      ────●────       [🔒] │
│ Zoom       ──●──────       [🔓] │
│ ...        [ ⚄ Randomize ]      │
│ Mod tab:                        │
│  LFO 1..3  (wave rate depth →)  │
│  ADSR 1..2 (A D S R, trigger,   │
│    targets: +param/+LFO, multi) │
└─────────────────────────────────┘
```

## Preset browser — tree, folders, remove
```
┌ Presets ────────────── [+folder]┐
│ ▾ My Chill/          [rename]   │
│    julia · Dream    [apply][🗑] │
│ ▾ Party/                        │
│    plasma · Strobe  [apply][🗑] │
│ ▸ Built-in/          (no 🗑)    │
│         [ Save current… ]       │
└─────────────────────────────────┘
```

## Music library — device browser + Drive
```
┌ Tracks | Playlists | Folders | ☁┐
│ Tracks: ▸ Song A  Bm·124  [▶]   │
│ Folders: ▾ /Music/DJ Sets/…     │
│ Drive:  [connect] > browse >    │
│         [download] [stream]     │
└─────────────────────────────────┘
```

## Settings (key items)
```
┌ Settings ───────────────────────┐
│ Look: theme ▾ · UI opacity ──●─ │
│ Player: position ▾ · size ──●── │
│ Analysis: beat thresh ──●─ ·    │
│   key detection ☑ · DB [view]   │
│ Paths: media [+][rescan] ·      │
│   preset folder [choose]        │
│ Export: … [render][to folder…]  │
└─────────────────────────────────┘
```
